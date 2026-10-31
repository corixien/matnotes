// Table borders by patching the exported docx and reloading (port of the edge part of TablePatch.kt). The UNO border structs
// cannot be used from JavaScript (zetajs cannot convert BorderLine2: its Color member is a util.Color typedef); fills, header
// text and bold go through the UNO cell API instead (engine.tableLook).
// The caret's table is found through a marker typed into its cell beforehand; the marker disappears with the reload.
import { unzipSync, zipSync } from 'fflate';

export const MARK = 'MNTBLMARK7Q';
const W = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main';

export type BorderLine = { style: string; size: number; color: string };
export type Edges = { top?: BorderLine | null; left?: BorderLine | null; bottom?: BorderLine | null; right?: BorderLine | null; insideH?: BorderLine | null; insideV?: BorderLine | null };
export type Edge = 'all' | 'outside' | 'inside' | 'top' | 'bottom' | 'left' | 'right' | 'inside_h' | 'inside_v' | 'none';
export type TableOp = { edge?: Edge; line: BorderLine; /** Replaces the table's borders (a table look). */ edges?: Edges };

const TBLPR_ORDER = ['tblStyle', 'tblpPr', 'tblOverlap', 'bidiVisual', 'tblStyleRowBandSize', 'tblStyleColBandSize', 'tblW', 'jc', 'tblCellSpacing', 'tblInd', 'tblBorders', 'shd', 'tblLayout', 'tblCellMar', 'tblLook', 'tblCaption', 'tblDescription'];

// ---- pure edge logic ----

const all = (l: BorderLine | null): Required<Edges> => ({ top: l, left: l, bottom: l, right: l, insideH: l, insideV: l });
const norm = (e: Edges): Required<Edges> => ({ top: e.top ?? null, left: e.left ?? null, bottom: e.bottom ?? null, right: e.right ?? null, insideH: e.insideH ?? null, insideV: e.insideV ?? null });
const same = (a: Required<Edges>, b: Required<Edges>) => JSON.stringify(a) === JSON.stringify(b);

/** Same toggle behaviour as the app: pressing a preset that is already fully applied removes it. */
export function applyEdge(cur: Edges, which: Edge, l: BorderLine): Required<Edges> {
  const e = norm(cur);
  const toggle = (c: BorderLine | null) => (c === null ? l : null);
  switch (which) {
    case 'all': return same(e, all(l)) ? all(null) : all(l);
    case 'none': return all(null);
    case 'outside': return [e.top, e.left, e.bottom, e.right].every((x) => x !== null) ? { ...e, top: null, left: null, bottom: null, right: null } : { ...e, top: l, left: l, bottom: l, right: l };
    case 'inside': return e.insideH !== null && e.insideV !== null ? { ...e, insideH: null, insideV: null } : { ...e, insideH: l, insideV: l };
    case 'top': return { ...e, top: toggle(e.top) };
    case 'bottom': return { ...e, bottom: toggle(e.bottom) };
    case 'left': return { ...e, left: toggle(e.left) };
    case 'right': return { ...e, right: toggle(e.right) };
    case 'inside_h': return { ...e, insideH: toggle(e.insideH) };
    case 'inside_v': return { ...e, insideV: toggle(e.insideV) };
  }
}

// ---- DOM helpers (browser DOM; tests inject xmldom through globalThis.DOMParser) ----

const child = (parent: Element | null | undefined, name: string): Element | null => {
  for (let n = parent?.firstChild ?? null; n; n = n.nextSibling) if (n.nodeType === 1 && (n as Element).localName === name && (n as Element).namespaceURI === W) return n as Element;
  return null;
};
const children = (parent: Element, name: string): Element[] => {
  const out: Element[] = [];
  for (let n = parent.firstChild; n; n = n.nextSibling) if (n.nodeType === 1 && (n as Element).localName === name && (n as Element).namespaceURI === W) out.push(n as Element);
  return out;
};
const ancestor = (n: Node, name: string): Element | null => {
  for (let p = n.parentNode; p; p = p.parentNode) if (p.nodeType === 1 && (p as Element).localName === name && (p as Element).namespaceURI === W) return p as Element;
  return null;
};
const attr = (el: Element, name: string) => el.getAttributeNS(W, name) || el.getAttribute('w:' + name) || '';

function readLine(el: Element | null): BorderLine | null {
  if (!el) return null;
  const v = attr(el, 'val');
  if (!v || v === 'nil' || v === 'none') return null;
  const sz = parseInt(attr(el, 'sz'), 10);
  const color = attr(el, 'color');
  return { style: v, size: Number.isFinite(sz) ? sz : 4, color: !color || color === 'auto' ? '000000' : color };
}

/** Edges currently on the table: its w:tblBorders, else derived from the cells' own borders (LibreOffice writes those). */
function readEdges(tbl: Element): Required<Edges> {
  const tb = child(child(tbl, 'tblPr'), 'tblBorders');
  if (tb) {
    const side = (n: string) => readLine(child(tb, n) ?? child(tb, n === 'left' ? 'start' : n === 'right' ? 'end' : n));
    return { top: side('top'), left: side('left'), bottom: side('bottom'), right: side('right'), insideH: side('insideH'), insideV: side('insideV') };
  }
  const rows = children(tbl, 'tr');
  if (!rows.length) return all(null);
  const cells = (r: Element) => children(r, 'tc');
  const cb = (tc: Element | undefined, n: string) => readLine(child(child(child(tc ?? null, 'tcPr'), 'tcBorders'), n));
  const first = cells(rows[0])[0], last = cells(rows[rows.length - 1]).at(-1), lastOfFirst = cells(rows[0]).at(-1);
  return {
    top: cb(first, 'top'), left: cb(first, 'left') ?? cb(first, 'start'),
    bottom: cb(last, 'bottom'), right: cb(lastOfFirst, 'right') ?? cb(lastOfFirst, 'end'),
    insideH: rows.length > 1 ? cb(first, 'bottom') : null,
    insideV: cells(rows[0]).length > 1 ? cb(first, 'right') ?? cb(first, 'end') : null,
  };
}

function lineEl(doc: Document, name: string, l: BorderLine | null): Element {
  const e = doc.createElementNS(W, 'w:' + name);
  if (!l) e.setAttributeNS(W, 'w:val', 'nil');
  else { e.setAttributeNS(W, 'w:val', l.style); e.setAttributeNS(W, 'w:sz', String(l.size)); e.setAttributeNS(W, 'w:space', '0'); e.setAttributeNS(W, 'w:color', l.color); }
  return e;
}

function insertOrdered(parent: Element, el: Element, order: string[]) {
  const rank = order.indexOf(el.localName as string);
  for (let n = parent.firstChild; n; n = n.nextSibling) {
    if (n.nodeType === 1 && order.indexOf((n as Element).localName) > rank) { parent.insertBefore(el, n); return; }
  }
  parent.appendChild(el);
}

function writeEdges(doc: Document, tbl: Element, e: Required<Edges>) {
  let pr = child(tbl, 'tblPr');
  if (!pr) { pr = doc.createElementNS(W, 'w:tblPr'); tbl.insertBefore(pr, tbl.firstChild); }
  const old = child(pr, 'tblBorders');
  if (old) pr.removeChild(old);
  const b = doc.createElementNS(W, 'w:tblBorders');
  for (const [n, l] of [['top', e.top], ['left', e.left], ['bottom', e.bottom], ['right', e.right], ['insideH', e.insideH], ['insideV', e.insideV]] as const) b.appendChild(lineEl(doc, n, l));
  insertOrdered(pr, b, TBLPR_ORDER);
}

function removeMarker(t: Element) {
  const text = (t.textContent ?? '').replace(MARK, '');
  if (text === '') {
    const run = t.parentNode as Element | null;
    if (run && run.localName === 'r' && run.getElementsByTagNameNS(W, 't').length === 1) run.parentNode!.removeChild(run);
    else t.textContent = '';
  } else t.textContent = text;
}

/** Edges of the first table of a docx (tests and diagnostics). */
export function edgesOfDocx(docx: Uint8Array): Required<Edges> | null {
  const xml = unzipSync(docx)['word/document.xml'];
  if (!xml) return null;
  const tbl = new DOMParser().parseFromString(new TextDecoder().decode(xml), 'application/xml').getElementsByTagNameNS(W, 'tbl')[0];
  return tbl ? readEdges(tbl) : null;
}

/** Patches document.xml; null when the marker is not inside a table. */
export function patchXml(xml: string, op: TableOp): string | null {
  const doc = new DOMParser().parseFromString(xml, 'application/xml');
  const ts = doc.getElementsByTagNameNS(W, 't');
  let markT: Element | null = null;
  for (let i = 0; i < ts.length; i++) if ((ts[i].textContent ?? '').includes(MARK)) { markT = ts[i]; break; }
  if (!markT) return null;
  const tc = ancestor(markT, 'tc');
  const tbl = tc ? (tc.parentNode!.parentNode as Element) : null;
  if (!tc || !tbl) return null;
  removeMarker(markT);

  let edges = readEdges(tbl);
  if (op.edges) edges = norm(op.edges);
  if (op.edge) edges = applyEdge(edges, op.edge, op.line);
  // flatten: cell-level borders would override the table-level ones; an "auto" fill (a cleared cell) must not come back as white
  for (const tr of children(tbl, 'tr')) for (const c of children(tr, 'tc')) {
    const pr = child(c, 'tcPr');
    const cb = child(pr, 'tcBorders');
    if (cb) cb.parentNode!.removeChild(cb);
    const shd = child(pr, 'shd');
    if (shd && attr(shd, 'fill') === 'auto') shd.parentNode!.removeChild(shd);
  }
  writeEdges(doc, tbl, edges);
  const out = new XMLSerializer().serializeToString(doc);
  return out.startsWith('<?xml') ? out : '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n' + out;
}

/** The docx with the caret's table patched, or null when the marker was not found. */
export function patchDocx(docx: Uint8Array, op: TableOp): Uint8Array | null {
  const files = unzipSync(docx);
  const xml = files['word/document.xml'];
  if (!xml) return null;
  const patched = patchXml(new TextDecoder().decode(xml), op);
  if (patched === null) return null;
  files['word/document.xml'] = new TextEncoder().encode(patched);
  return zipSync(files, { level: 6 });
}
