// SPDX-License-Identifier: MIT
// Runs inside the engine's worker ("em-pthread", the one where `Module.zetajs` resolves). Plain JS on purpose:
// the engine loads it through `Module.uno_scripts`. Talks to the page through `zetajs.mainPort` (see src/engine.ts).
'use strict';

let zetajs, css, context, desktop, xModel, ctrl;
const watchers = new Map(); // uno command -> { url, listener }

const FILTERS = { docx: 'MS Word 2007 XML', odt: 'writer8', pdf: 'writer_pdf_Export', txt: 'Text (encoded)' };

const post = (msg) => zetajs.mainPort.postMessage(msg);
const prop = (Name, Value) => new css.beans.PropertyValue({ Name, Value });

function transformUrl(unoUrl) {
  const io = { val: new css.util.URL({ Complete: unoUrl }) };
  css.util.URLTransformer.create(context).parseStrict(io);
  return io.val;
}

// The app's own chrome (ribbon, bars) lives in HTML: switch every LibreOffice toolbar, the menubar, sidebar, ruler
// and statusbar off, once, before the first document opens.
function hideChrome() {
  const config = css.configuration.ReadWriteAccess.create(context, 'en-US');
  const elems = config.getByHierarchicalName('/org.openoffice.Office.UI.WriterWindowState/UIElements/States');
  for (const name of elems.getElementNames()) {
    const el = elems.getByName(name);
    if (el.getByName('Visible')) el.setPropertyValue('Visible', false);
  }
  config.commitChanges();
}

function attach(model) {
  xModel = model;
  ctrl = model.getCurrentController();
  const frame = ctrl.getFrame();
  frame.getContainerWindow().FullScreen = true;
  try { frame.LayoutManager.hideElement('private:resource/menubar/menubar'); } catch (e) { /* already hidden */ }
  try { frame.LayoutManager.hideElement('private:resource/statusbar/statusbar'); } catch (e) { /* already hidden */ }
  rewatch();
  // sidebar and ruler are toggles that start visible; flip them once the view exists (too early is ignored)
  setTimeout(() => {
    for (const cmd of ['.uno:Sidebar', '.uno:Ruler']) dispatch(cmd);
    post({ cmd: 'doc_ready' });
  }, 1200);
}

function dispatch(uno, args) {
  const url = transformUrl(uno);
  const d = ctrl.queryDispatch(url, '_self', 0);
  if (!d) { post({ cmd: 'dispatch_missing', uno }); return false; }
  d.dispatch(url, (args || []).map((a) => prop(a.n, a.t ? new zetajs.Any(zetajs.type[a.t], a.v) : a.v)));
  if (uno === '.uno:EditDoc') setTimeout(() => { try { refreshCurrent(); rewatch(); post({ cmd: 'view_reloaded' }); } catch (e) { /* closing */ } }, 1500);
  return true;
}

function watch(ids) {
  for (const id of ids) {
    if (watchers.has(id)) continue;
    const url = transformUrl(id);
    const listener = zetajs.unoObject([css.frame.XStatusListener], {
      disposing() {},
      statusChanged(state) {
        let value = null;
        try { value = zetajs.fromAny(state.State); } catch (e) { value = null; }
        // font name / height arrive as structs; every other struct is not needed by the UI
        if (value !== null && typeof value === 'object') value = value.Name !== undefined ? String(value.Name) : value.Height !== undefined ? Number(value.Height) : null;
        post({ cmd: 'state', id, value, enabled: !!state.IsEnabled });
      },
    });
    watchers.set(id, { url, listener });
  }
  rewatch();
}

function rewatch() {
  if (!ctrl) return;
  for (const w of watchers.values()) {
    // keep the dispatch object referenced, otherwise the listener stops receiving updates
    try { w.dispatch = ctrl.queryDispatch(w.url, '_self', 0); if (w.dispatch) w.dispatch.addStatusListener(w.listener, w.url); } catch (e) { /* command unavailable */ }
  }
}

function save(path, ext, id) {
  const filter = FILTERS[ext];
  if (!filter) { post({ cmd: 'error', id, message: 'unknown format ' + ext }); return; }
  const props = [prop('Overwrite', true), prop('FilterName', filter)];
  if (ext === 'txt') props.push(prop('FilterOptions', 'UTF8'));
  xModel.storeToURL('file://' + path, props);
  post({ cmd: 'saved', id, path });
}

// Reads (and optionally changes) the page style of the page the caret is on. Sizes are 1/100 mm.
function pageStyle(set) {
  const name = ctrl.getViewCursor().getPropertyValue('PageStyleName');
  const st = xModel.getStyleFamilies().getByName('PageStyles').getByName(name);
  if (set) for (const [k, v] of Object.entries(set)) st.setPropertyValue(k, v);
  const out = {};
  for (const k of ['Width', 'Height', 'LeftMargin', 'RightMargin', 'TopMargin', 'BottomMargin', 'IsLandscape']) out[k] = st.getPropertyValue(k);
  return out;
}

// ---- tables: borders and looks through the UNO table API (no docx patching needed in the browser) ----
function currentTable() {
  const t = ctrl.getViewCursor().getPropertyValue('TextTable');
  return t && typeof t === 'object' ? t : null;
}

const PT = 35.278; // 1/100 mm per point
function borderLine(line) {
  if (!line) return new css.table.BorderLine2({ InnerLineWidth: 0, OuterLineWidth: 0, LineDistance: 0, LineStyle: 0, LineWidth: 0 });
  const w = Math.round((line.sz / 8) * PT);
  const style = { single: 0, dotted: 1, dashed: 2, double: 3 }[line.style] ?? 0;
  const third = Math.max(1, Math.round(w / 3));
  const l = new css.table.BorderLine2({
    LineStyle: style, LineWidth: w,
    InnerLineWidth: style === 3 ? third : 0, OuterLineWidth: style === 3 ? third : w, LineDistance: style === 3 ? third : 0,
  });
  const color = parseInt(line.color, 16);
  if (color !== 0) { try { l.Color = color; } catch (e) { post({ cmd: 'log', text: 'border colour: ' + (e && e.message || e) }); } }
  return l;
}

// edges: { top, bottom, left, right, insideH, insideV } each a line or null (= no border), undefined = leave as is.
// The struct is built fresh: zetajs cannot read the existing one (its BorderLine2.Color member is a util.Color typedef).
function setBorders(table, edges) {
  const map = { top: ['TopLine', 'IsTopLineValid'], bottom: ['BottomLine', 'IsBottomLineValid'], left: ['LeftLine', 'IsLeftLineValid'], right: ['RightLine', 'IsRightLineValid'], insideH: ['HorizontalLine', 'IsHorizontalLineValid'], insideV: ['VerticalLine', 'IsVerticalLineValid'] };
  const b = new css.table.TableBorder2({});
  for (const [k, [prop, valid]] of Object.entries(map)) {
    b[prop] = borderLine(edges[k] === undefined ? null : edges[k]);
    b[valid] = edges[k] !== undefined;
  }
  b.IsDistanceValid = false;
  table.setPropertyValue('TableBorder2', b);
}

function tableBorder(preset, line) {
  const table = currentTable();
  if (!table) return false;
  const none = null;
  const sets = {
    all: { top: line, bottom: line, left: line, right: line, insideH: line, insideV: line },
    outside: { top: line, bottom: line, left: line, right: line },
    inside: { insideH: line, insideV: line },
    none: { top: none, bottom: none, left: none, right: none, insideH: none, insideV: none },
    top: { top: line }, bottom: { bottom: line }, left: { left: line }, right: { right: line },
    inside_h: { insideH: line }, inside_v: { insideV: line },
  };
  if (!sets[preset]) return false;
  setBorders(table, sets[preset]);
  return true;
}

function cellRow(name) { return parseInt(name.replace(/^[A-Za-z]+/, ''), 10) - 1; }

// look: { edges, headerFill, headerText, headerBold, band } (colours RRGGBB or null), same data as TableLooks in TablePatch.kt
function tableLook(look) {
  const table = currentTable();
  if (!table) return false;
  for (const name of table.getCellNames()) {
    const ri = cellRow(name);
    const header = ri === 0;
    const fill = header && look.headerFill ? look.headerFill : (!header && look.band && ri % 2 === 0 ? look.band : null);
    const cell = table.getCellByName(name);
    if (fill) { cell.setPropertyValue('BackTransparent', false); cell.setPropertyValue('BackColor', parseInt(fill, 16)); } else { cell.setPropertyValue('BackColor', -1); cell.setPropertyValue('BackTransparent', true); } // -1 = automatic: a plain transparent flag leaves the old colour behind
    if (header && (look.headerBold || look.headerText)) {
      const cur = cell.createTextCursor();
      cur.gotoStart(false); cur.gotoEnd(true);
      if (look.headerBold) cur.setPropertyValue('CharWeight', 150);
      if (look.headerText) cur.setPropertyValue('CharColor', parseInt(look.headerText, 16));
    }
  }
  return true;
}

function tableInfo() {
  const table = currentTable();
  if (!table) return null;
  const cellInfo = (n) => { try { return { transparent: table.getCellByName(n).getPropertyValue('BackTransparent') }; } catch (e) { return null; } };
  return { rows: table.getRows().getCount(), a1: cellInfo('A1'), a2: cellInfo('A2'), a3: cellInfo('A3') };
}

function open(path) {
  if (xModel) { try { xModel.close(true); } catch (e) { /* ignore */ } }
  attach(desktop.loadComponentFromURL('file://' + path, '_default', 0, []));
}

function newDoc() {
  if (xModel) { try { xModel.close(true); } catch (e) { /* ignore */ } }
  attach(desktop.loadComponentFromURL('private:factory/swriter', '_default', 0, []));
}

// .uno:EditDoc (viewer <-> editor) reloads the document in a new model: always work on the frame's current one.
function refreshCurrent() {
  const frame = desktop.getCurrentFrame();
  const c = frame && frame.getController();
  if (!c) return;
  ctrl = c;
  xModel = c.getModel();
}

function onMessage(e) {
  const m = e.data;
  try {
    if (xModel && m.cmd !== 'open' && m.cmd !== 'new') refreshCurrent();
    switch (m.cmd) {
      case 'open': open(m.path); break;
      case 'new': newDoc(); break;
      case 'save': save(m.path, m.ext, m.id); break;
      case 'dispatch': dispatch(m.uno, m.args); break;
      case 'watch': watch(m.ids); break;
      case 'probe': post({ cmd: 'probe', id: m.id, missing: m.ids.filter((c) => { try { return !ctrl.queryDispatch(transformUrl(c), '_self', 0); } catch (e) { return true; } }) }); break;
      case 'pagestyle': post({ cmd: 'done', id: m.id, page: pageStyle(m.set) }); break;
      case 'counts': post({ cmd: 'counts', id: m.id, words: xModel.getPropertyValue('WordCount'), chars: xModel.getPropertyValue('CharacterCount') }); break;
      case 'selprop': { const sel = ctrl.getSelection(); const r = sel.getByIndex ? sel.getByIndex(0) : sel; post({ cmd: 'selprop', id: m.id, value: zetajs.fromAny(r.getPropertyValue(m.name)) }); break; }
      case 'tableBorder': post({ cmd: 'done', id: m.id, ok: tableBorder(m.preset, m.line) }); break;
      case 'tableLook': post({ cmd: 'done', id: m.id, ok: tableLook(m.look) }); break;
      case 'tableInfo': post({ cmd: 'tableInfo', id: m.id, info: tableInfo() }); break;
      case 'text': post({ cmd: 'text', id: m.id, text: xModel.getText().getString() }); break;
      case 'insertText': xModel.getText().getEnd().setString(m.text); post({ cmd: 'done', id: m.id }); break;
      default: post({ cmd: 'error', id: m.id, message: 'unknown command ' + m.cmd });
    }
  } catch (err) {
    post({ cmd: 'error', id: m.id, message: String(err && err.message || err) });
  }
}

Module.zetajs.then((z) => {
  zetajs = z;
  css = zetajs.uno.com.sun.star;
  context = zetajs.getUnoComponentContext();
  desktop = css.frame.Desktop.create(context);
  hideChrome();
  zetajs.mainPort.onmessage = onMessage;
  post({ cmd: 'thr_running' });
});
