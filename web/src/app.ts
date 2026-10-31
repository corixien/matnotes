// App shell: home screen, editor (top bar + ribbon + engine canvas), file flows (open, new, save, save as, overwrite,
// export, print). Mirrors MainActivity.kt / Screens.kt. Desktop (>= 900 px): ribbon under the top bar. Phone: ribbon at the bottom.
import { h, clear, add } from './dom';
import { Engine, type CommandState, type EngineFormat, type LineSpec, type LookSpec } from './engine';
import { t } from './i18n';
import { Ribbon, type Context, watchedCommands, allCommands } from './ribbon';
import { cleanName, renameFile } from './rename';
import { Files, type FileInfo, store } from './store';
import { ZoomControl } from './zoom';
import { spellPanel } from './spell-panel';
import { SpellClient } from './spell-client';
import { MARK, patchDocx, edgesOfDocx, type TableOp, type Edge } from './tablepatch';
import { confirmDialog, dialog, promptDialog, toast, popup } from './ui';

const MIME: Record<string, string> = {
  docx: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  odt: 'application/vnd.oasis.opendocument.text',
  pdf: 'application/pdf',
};
const ext = (name: string) => name.slice(name.lastIndexOf('.') + 1).toLowerCase();
const stem = (name: string) => (name.lastIndexOf('.') > 0 ? name.slice(0, name.lastIndexOf('.')) : name);
export const isPhone = () => matchMedia('(max-width: 899px)').matches;

const thin = (color: string, sz = 4): LineSpec => ({ style: 'single', sz, color });
const allEdges = (l: LineSpec) => ({ top: l, bottom: l, left: l, right: l, insideH: l, insideV: l });
/** The eight table looks of TablePatch.kt (TableLooks.all): borders, header fill/text/bold, banded rows. */
export const TABLE_LOOKS: Record<string, LookSpec> = {
  tl_grid: { edges: allEdges(thin('000000')) },
  tl_grid_gray: { edges: allEdges(thin('A6A6A6')) },
  tl_blue: { edges: allEdges(thin('8EAADB')), headerFill: '2F5597', headerText: 'FFFFFF', headerBold: true, band: 'DAE3F3' },
  tl_green: { edges: allEdges(thin('A9D18E')), headerFill: '548235', headerText: 'FFFFFF', headerBold: true, band: 'E2EFDA' },
  tl_orange: { edges: allEdges(thin('F4B183')), headerFill: 'C55A11', headerText: 'FFFFFF', headerBold: true, band: 'FBE5D6' },
  tl_striped: { edges: { top: thin('7F7F7F', 8), bottom: thin('7F7F7F', 8), insideH: thin('D9D9D9') }, headerFill: 'F2F2F2', headerBold: true, band: 'F7F7F7' },
  tl_minimal: { edges: { top: thin('000000', 8), bottom: thin('000000', 8) }, headerBold: true },
  tl_dark: { edges: allEdges(thin('595959')), headerFill: '262626', headerText: 'FFFFFF', headerBold: true, band: 'EDEDED' },
};

const MAX_OPEN = 100 * 1024 * 1024;
const PAGE_SIZES: Record<string, [number, number]> = { A3: [29700, 42000], A4: [21000, 29700], A5: [14800, 21000], B5: [17600, 25000], Letter: [21590, 27940], Legal: [21590, 35560] };
const MARGINS: Record<string, { top: number; side: number }> = { normal: { top: 2540, side: 2540 }, narrow: { top: 1270, side: 1270 }, moderate: { top: 2540, side: 1910 }, wide: { top: 2540, side: 5080 } };

/** Where the open document came from: `dir`/`name` = stored copy ('' = personal folder), null = picked from disk or new. */
type Doc = { name: string; dir: string | null; editing: boolean; modified: boolean };

export interface Hooks {
  /** Profile button / account dialog (W4). */
  onProfile?: () => void;
  /** Called after a file was written to the local store so sync can pick it up (W4). */
  onStored?: () => void;
  /** Number of pending invitations shown as a badge on the profile button (W5). */
  badge?: () => number;
  /** Shared folders with their local dir name, for sections and the save dialog (W5). */
  folders?: () => { dir: string; name: string }[];
}

export class App {
  readonly root: HTMLElement;
  private engine: Engine | null = null;
  private engineError: string | null = null;
  private canvas: HTMLCanvasElement;
  private home = h('div.home');
  private editor = h('div.editor');
  private topbar = h('div.topbar');
  /** Cut / copy / paste next to a selection on phones, like the app's floating selection toolbar. */
  private floating = h('div.floating.hidden', { role: 'toolbar' });
  private ribbonHost = h('div.ribbon-host');
  private ribbon: Ribbon | null = null;
  private states = new Map<string, CommandState>();
  private doc: Doc | null = null;
  private files: FileInfo[] = [];
  private homeError: string | null = null;
  private engineWaiters: Array<() => void> = [];
  hooks: Hooks = {};
  /** Diagnostics for the e2e tests: edges of the first table in the exported docx. */
  async docTableEdges() { return edgesOfDocx(await this.engine!.save('docx')); }
  private zoom!: ZoomControl;
  private spell = new SpellClient();
  private spelling = false;
  private spellOnlineOff = false;
  private borderLine: LineSpec = { style: 'single', sz: 4, color: '000000' };

  constructor(root: HTMLElement) {
    this.root = root;
    this.canvas = h('canvas#qtcanvas', { contenteditable: 'true', oncontextmenu: (e: Event) => e.preventDefault(), onkeydown: (e: Event) => e.preventDefault(), 'aria-label': 'Document' });
    this.canvas.addEventListener('wheel', (e) => e.preventDefault(), { passive: false });
    const stage = h('div.stage', null, this.canvas, this.floating);
    this.zoom = new ZoomControl(stage, () => this.engine, () => !!this.doc);
    stage.append(this.zoom.bar);
    this.editor.append(this.topbar, h('div.editor-body', null, stage), this.ribbonHost);
    root.append(this.editor, this.home);
    matchMedia('(max-width: 899px)').addEventListener('change', () => { this.buildRibbon(); this.renderTopbar(); });
    this.startEngine();
    this.refreshHome();
    this.renderHome();
    addEventListener('keydown', (e) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's' && this.doc) { e.preventDefault(); void this.save(); } // the browser's "save page" is never wanted here
    });
    addEventListener('beforeunload', (e) => { if (this.doc?.modified) { e.preventDefault(); } });
    addEventListener('popstate', () => { if (this.doc) void this.closeDoc(true); });
  }

  // ------------------------------------------------------------------ engine

  private startEngine() {
    Engine.start(this.canvas).then((engine) => {
      this.engine = engine;
      engine.onState((id, s) => {
        this.states.set(id, s);
        // LibreOffice's "check spelling while typing" has no dictionaries here (our own spell panel does the work) but costs ~40 % of the
        // typing latency: switch it off once per document (it is a toggle that reports its state)
        if (id === '.uno:SpellOnline' && s.value === true && this.doc && !this.spellOnlineOff) { this.spellOnlineOff = true; engine.dispatch('.uno:SpellOnline'); }
        if (id === '.uno:ModifiedStatus' && this.doc) { this.doc.modified = s.value === true; this.renderTopbar(); }
        this.ribbon?.refresh();
        this.updateContext();
        this.updateFloating();
      });
      (window as any).matnotes = { engine, app: this, allCommands };
      this.engineWaiters.splice(0).forEach((f) => f());
      this.renderHome();
    }).catch((e) => { this.engineError = e.message; this.renderHome(); });
  }

  /** Resolves with the engine once it is up (downloads about 50 MB the first time). */
  private async ready(): Promise<Engine> {
    if (this.engine) return this.engine;
    if (this.engineError) throw new Error(this.engineError);
    await new Promise<void>((r) => this.engineWaiters.push(r));
    return this.engine!;
  }

  private updateFloating() {
    const show = isPhone() && !!this.doc?.editing && !this.spelling && this.states.get('.uno:Cut')?.enabled === true;
    this.floating.classList.toggle('hidden', !show);
    if (show && !this.floating.firstChild) {
      for (const [icon, label, cmd] of [['✂', t('cut'), '.uno:Cut'], ['⧉', t('copy'), '.uno:Copy'], ['📋', t('paste'), '.uno:Paste']]) {
        const b = h('button.icon-btn', { type: 'button', 'aria-label': label, title: label, onclick: () => this.engine?.dispatch(cmd) }, icon);
        b.addEventListener('mousedown', (e: Event) => e.preventDefault());
        this.floating.append(b);
      }
    }
  }

  private updateContext() {
    let c: Context = 'text';
    if (this.states.get('.uno:DeleteTable')?.enabled) c = 'table';
    else if (this.states.get('.uno:SetAnchorToPage')?.enabled) c = 'picture';
    this.ribbon?.setContext(c);
  }

  // ------------------------------------------------------------------ home

  /** Is this stored file the one open in the editor? (sync never replaces it) */
  isOpen(dir: string, name: string) { return !!this.doc && this.doc.dir === dir && this.doc.name === name; }

  async refreshHome() { this.files = await Files.all(); this.renderHome(); }

  renderHome() {
    const folders = this.hooks.folders?.() ?? [];
    const personal = this.files.filter((f) => f.dir === '');
    const fmt = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' });
    const badge = this.hooks.badge?.() ?? 0;
    const row = (f: FileInfo) => h('div.doc-row', null,
      h('button.doc-main', { type: 'button', onclick: () => this.openStored(f.dir, f.name) }, h('span.doc-icon', null, '▤'), h('span.doc-text', null, h('span.doc-name', null, f.name), h('span.doc-date', null, fmt.format(f.modified)))),
      h('button.icon-btn', { type: 'button', 'aria-label': t('more'), title: t('more'), onclick: (e: Event) => this.docMenu(e.currentTarget as HTMLElement, f) }, '⋮'));
    const section = (title: string, files: FileInfo[]) => [h('h3.section', null, title), ...files.map(row)];
    clear(this.home);
    add(this.home,
      h('header.home-head', null,
        h('h1', null, 'MatNotes'),
        h('button.profile', { type: 'button', 'aria-label': t('acc_profile'), title: t('acc_profile'), onclick: () => this.hooks.onProfile?.() }, '👤', badge > 0 ? h('span.badge', null, String(badge)) : null)),
      h('p.tagline', null, t('tagline')),
      h('div.actions', null,
        h('button.card#new', { type: 'button', onclick: () => this.newDoc() }, h('span.card-ic', null, '+'), h('span.card-lb', null, t('new_document'))),
        h('button.card#open', { type: 'button', onclick: () => this.pickFile() }, h('span.card-ic', null, '▤'), h('span.card-lb', null, t('open_document')))),
      this.engine || this.engineError ? null : h('p.engine-status', { role: 'status' }, t('loading') + ' (LibreOffice)'),
      this.engineError ? h('p.error', null, this.engineError, ' ', h('button.btn', { type: 'button', onclick: () => location.reload() }, t('acc_continue'))) : null,
      this.homeError ? h('p.error', null, this.homeError) : null,
      personal.length ? h('div.list', null, ...section(t('my_documents'), personal)) : null,
      ...folders.map((f) => { const fs = this.files.filter((x) => x.dir === f.dir); return fs.length ? h('div.list', null, ...section(f.name, fs)) : null; }),
      !this.files.length ? h('p.empty', null, t('no_recent')) : null);
  }

  private docMenu(anchor: HTMLElement, f: FileInfo) {
    const list = h('div.menu');
    const close = popup(anchor, list);
    list.append(
      h('button.menu-item', { type: 'button', onclick: () => { close(); void this.renameStored(f); } }, t('rename')),
      h('button.menu-item', { type: 'button', onclick: async () => { close(); const r = await Files.get(f.dir, f.name); if (r) this.download(f.name, r.bytes); } }, t('share_system') === 'share_system' ? 'Download' : 'Download'),
      h('button.menu-item.danger', { type: 'button', onclick: async () => { close(); if (await confirmDialog(t('delete'), f.name, t('delete'), true)) { await Files.remove(f.dir, f.name); this.hooks.onStored?.(); this.refreshHome(); } } }, t('delete')));
  }

  // ------------------------------------------------------------------ open / new

  private pickFile() {
    const input = h('input', { type: 'file', accept: '.docx,.odt,.doc,.rtf,.txt', style: { display: 'none' } });
    input.addEventListener('change', async () => {
      const f = input.files?.[0];
      input.remove();
      if (f) await this.openBytes(f.name, new Uint8Array(await f.arrayBuffer()), null);
    });
    document.body.append(input);
    input.click();
  }

  async openStored(dir: string, name: string) {
    const f = await Files.get(dir, name);
    if (!f) { this.homeError = t('open_failed'); this.renderHome(); return; }
    await this.openBytes(name, f.bytes, dir);
  }

  async openBytes(name: string, bytes: Uint8Array, dir: string | null) {
    this.homeError = null;
    try {
      if (bytes.length > MAX_OPEN) throw new Error(`${(bytes.length / 1048576).toFixed(0)} MB > ${MAX_OPEN / 1048576} MB`); // the in-browser engine keeps the whole document in memory
      this.showLoading(true);
      const engine = await this.ready();
      await engine.open(name, bytes);
      this.start({ name, dir, editing: false, modified: false });
    } catch (e) {
      this.homeError = t('open_failed') + ': ' + (e as Error).message;
      this.renderHome();
    } finally { this.showLoading(false); }
  }

  async newDoc() {
    try {
      this.showLoading(true);
      const engine = await this.ready();
      await engine.newDocument();
      this.start({ name: t('new_doc_name'), dir: null, editing: true, modified: false });
    } catch (e) { this.homeError = (e as Error).message; this.renderHome(); } finally { this.showLoading(false); }
  }

  private showLoading(on: boolean) { this.home.classList.toggle('busy', on); }

  private start(doc: Doc) {
    this.doc = doc;
    this.spelling = false;
    this.spellOnlineOff = false;
    this.engineEditable = true;
    this.engine!.watch([...watchedCommands(), '.uno:ModifiedStatus']);
    history.pushState({ doc: true }, '');
    document.body.classList.add('in-editor');
    this.home.classList.add('hidden');
    this.buildRibbon();
    this.renderTopbar();
    setTimeout(() => { window.dispatchEvent(new Event('resize')); this.canvas.focus(); if (isPhone()) void this.zoom.fit(); else void this.zoom.sync(); }, 100);
    this.setEditing(doc.editing, true);
  }

  /** Documents open editable in the engine; `.uno:EditDoc` toggles read-only mode, so remember where we are. */
  private engineEditable = true;

  private async setEditing(on: boolean, force = false) {
    if (!this.doc || (!force && this.doc.editing === on)) return;
    this.doc.editing = on;
    if (this.engineEditable !== on) { this.engine!.dispatch('.uno:EditDoc'); this.engineEditable = on; }
    this.buildRibbon();
    this.renderTopbar();
    setTimeout(() => window.dispatchEvent(new Event('resize')), 80);
  }

  async closeDoc(fromPopstate = false) {
    if (!this.doc) return;
    if (this.doc.modified) {
      const keep = await this.unsavedDialog();
      if (keep === 'cancel') { if (fromPopstate) history.pushState({ doc: true }, ''); return; }
      if (keep === 'save') { if (!(await this.save())) return; }
    }
    if (!fromPopstate) history.back();
    this.doc = null;
    document.body.classList.remove('in-editor');
    this.home.classList.remove('hidden');
    await this.refreshHome();
  }

  private unsavedDialog(): Promise<'save' | 'discard' | 'cancel'> {
    return new Promise((resolve) => {
      dialog(t('unsaved_title'), t('unsaved_msg'), [
        { label: t('cancel'), onClick: () => resolve('cancel') },
        { label: t('discard'), danger: true, onClick: () => resolve('discard') },
        { label: t('save'), primary: true, onClick: () => resolve('save') },
      ]);
    });
  }

  // ------------------------------------------------------------------ editor chrome

  private buildRibbon() {
    clear(this.ribbonHost);
    this.ribbon = null;
    this.editor.classList.toggle('phone', isPhone());
    this.editor.classList.toggle('viewing', !this.doc?.editing);
    if (!this.doc?.editing || !this.engine) return;
    if (this.spelling) { // the spell panel takes the ribbon's place until it is closed
      this.ribbonHost.append(spellPanel({
        engine: this.engine, spell: this.spell,
        docTag: () => { const v = this.states.get('.uno:LanguageStatus')?.value; return typeof v === 'string' ? v.split(';')[1] || null : null; },
        close: () => { this.spelling = false; this.buildRibbon(); },
      }));
      return;
    }
    this.ribbon = new Ribbon({
      engine: this.engine,
      state: (id) => this.states.get(id),
      act: (name, arg) => void this.act(name, arg),
    }, isPhone());
    this.ribbonHost.append(this.ribbon.el);
    if (isPhone()) {
      const toggle = h('button.ribbon-toggle', { type: 'button', 'aria-label': 'Collapse', onclick: () => { this.ribbon!.el.classList.toggle('collapsed'); setTimeout(() => window.dispatchEvent(new Event('resize')), 80); } }, '⌄');
      this.ribbon.el.querySelector('.ribbon-tabs')!.append(toggle);
    }
  }

  private renderTopbar() {
    clear(this.topbar);
    const d = this.doc;
    if (!d) return;
    const phone = isPhone();
    const btn = (icon: string, title: string, f: (e: Event) => void, cls = '') => h('button.icon-btn' + cls, { type: 'button', 'aria-label': title, title, onclick: f }, icon);
    add(this.topbar,
      btn('←', t('back'), () => void this.closeDoc(), '.back'),
      phone ? null : h('span.logo', null, 'MatNotes'),
      h('button.doc-title', { type: 'button', title: t('rename'), 'aria-label': t('rename') + ': ' + d.name, onclick: () => void this.renameOpen() }, d.name, d.modified ? h('span.dirty', { title: t('unsaved_changes_short') }, ' ●') : null),
      h('span.spacer'),
      d.editing
        ? btn('✓', t('done'), () => void this.setEditing(false), '.accent')
        : btn('✎', t('edit'), () => void this.setEditing(true), '.accent'),
      btn('💾', t('save'), () => void this.save(), '.save'),
      btn('⋮', t('more'), (e) => this.fileMenu(e.currentTarget as HTMLElement)),
      phone ? null : btn('👤', t('acc_profile'), () => this.hooks.onProfile?.()));
  }

  private fileMenu(anchor: HTMLElement) {
    const d = this.doc!;
    const list = h('div.menu');
    const close = popup(anchor, list);
    const item = (label: string, f: () => void, cls = '') => list.append(h('button.menu-item' + cls, { type: 'button', onclick: () => { close(); f(); } }, label));
    item(t('save_as'), () => void this.saveAs());
    if (d.dir !== null) item(t('overwrite_original'), () => void this.overwrite());
    item(t('fmt_docx'), () => void this.exportAs('docx'));
    item(t('fmt_odt'), () => void this.exportAs('odt'));
    item(t('fmt_pdf'), () => void this.exportAs('pdf'));
    if (typeof navigator.share === 'function') item(t('share_system'), () => void this.shareFile());
    item(t('print'), () => void this.print());
  }

  // ------------------------------------------------------------------ rename

  private async askName(current: string): Promise<string | null> {
    const stemOf = current.replace(/\.[^.]+$/, '');
    const input = await promptDialog(t('rename'), t('rename_name'), stemOf, t('rename'));
    return input === null ? null : cleanName(input, current);
  }

  /** Rename from the home list (the file is not open). */
  async renameStored(f: FileInfo) {
    const name = await this.askName(f.name);
    if (name === null || name === f.name) return;
    if (!(await renameFile(store, f.dir, f.name, name))) { toast(t('rename_exists')); return; }
    this.hooks.onStored?.();
    await this.refreshHome();
  }

  /** Rename the open document: a stored file moves, a new or picked one just gets the name it will be saved under. */
  async renameOpen() {
    const d = this.doc;
    if (!d) return;
    const name = await this.askName(d.name);
    if (name === null || name === d.name) return;
    if (d.dir !== null) {
      if (!(await renameFile(store, d.dir, d.name, name))) { toast(t('rename_exists')); return; }
      this.hooks.onStored?.();
    }
    d.name = name;
    this.renderTopbar();
  }

  // ------------------------------------------------------------------ files

  private download(name: string, bytes: Uint8Array) {
    const a = h('a', { href: URL.createObjectURL(new Blob([bytes as BlobPart], { type: MIME[ext(name)] ?? 'application/octet-stream' })), download: name, style: { display: 'none' } });
    document.body.append(a);
    a.click();
    setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 1000);
  }

  private async bytes(format: EngineFormat) { return this.engine!.save(format); }

  /** Save = write a copy into the local store (new name each time unless overwriting, like the app). */
  async save(): Promise<boolean> {
    const d = this.doc!;
    if (d.dir !== null && d.modified === false) { toast(t('saved')); return true; }
    return this.saveAs();
  }

  async saveAs(): Promise<boolean> {
    const d = this.doc!;
    const folders = [{ dir: '', name: t('share_personal') }, ...(this.hooks.folders?.() ?? [])];
    const start = await Files.freeName(d.dir ?? '', stem(d.name) + '.docx');
    const picked = await this.nameDialog(start, folders, d.dir ?? '');
    if (!picked) return false;
    try {
      const data = await this.bytes('docx');
      await Files.put(picked.dir, picked.name, data);
      Object.assign(d, { name: picked.name, dir: picked.dir, modified: false });
      toast(t('saved'));
      this.renderTopbar();
      this.hooks.onStored?.();
      return true;
    } catch (e) { toast(t('save_failed') + ': ' + (e as Error).message); return false; }
  }

  private nameDialog(name: string, folders: { dir: string; name: string }[], dir: string): Promise<{ dir: string; name: string } | null> {
    return new Promise((resolve) => {
      const input = h('input.field', { type: 'text', value: name, 'aria-label': t('file_name'), spellcheck: false });
      const sel = h('select.field', { 'aria-label': t('share_in_folder') }, ...folders.map((f) => h('option', { value: f.dir, selected: f.dir === dir }, f.name)));
      const body = h('div.form', null, h('label.field-label', null, t('file_name'), input), folders.length > 1 ? h('label.field-label', null, t('share_in_folder'), sel) : null);
      const d = dialog(t('save_document_title'), body, [
        { label: t('cancel'), onClick: () => resolve(null) },
        {
          label: t('save'), primary: true, onClick: async () => {
            let n = input.value.trim().replace(/[\\/:*?"<>|]/g, '_');
            if (!n) return false;
            if (!/\.(docx|odt)$/i.test(n)) n += '.docx';
            resolve({ dir: sel.value, name: await Files.freeName(sel.value, n) });
          },
        },
      ]);
      input.addEventListener('keydown', (e: KeyboardEvent) => { if (e.key === 'Enter') (d.el.querySelector('.primary') as HTMLElement).click(); });
      input.select();
    });
  }

  async overwrite(): Promise<void> {
    const d = this.doc!;
    if (d.dir === null) { await this.saveAs(); return; }
    if (!(await confirmDialog(t('overwrite_title'), t('overwrite_msg'), t('overwrite_ok'), true))) return;
    const target = d.name.toLowerCase().endsWith('.odt') ? 'odt' : 'docx';
    await Files.put(d.dir, d.name, await this.bytes(target));
    d.modified = false;
    toast(t('saved'));
    this.renderTopbar();
    this.hooks.onStored?.();
  }

  async exportAs(format: EngineFormat) {
    try { this.download(stem(this.doc!.name) + '.' + format, await this.bytes(format)); } catch (e) { toast(t('export_failed') + ': ' + (e as Error).message); }
  }

  private async shareFile() {
    const name = stem(this.doc!.name) + '.docx';
    try {
      const file = new File([(await this.bytes('docx')) as BlobPart], name, { type: MIME.docx });
      if (navigator.canShare?.({ files: [file] })) await navigator.share({ files: [file], title: name });
      else this.download(name, await this.bytes('docx'));
    } catch (e) { if ((e as Error).name !== 'AbortError') toast(t('export_failed')); }
  }

  /** Print = render a PDF and print it from a hidden frame (the engine has no print dialog in the browser). */
  async print() {
    try {
      const pdf = await this.bytes('pdf');
      const url = URL.createObjectURL(new Blob([pdf as BlobPart], { type: 'application/pdf' }));
      const frame = h('iframe', { src: url, style: { position: 'fixed', right: 0, bottom: 0, width: 0, height: 0, border: 0 } });
      frame.onload = () => { try { frame.contentWindow!.focus(); frame.contentWindow!.print(); } catch { window.open(url); } setTimeout(() => { frame.remove(); URL.revokeObjectURL(url); }, 60000); };
      document.body.append(frame);
    } catch (e) { toast(t('export_failed') + ': ' + (e as Error).message); }
  }

  // ------------------------------------------------------------------ ribbon actions

  async act(name: string, arg?: string) {
    const e = this.engine!;
    switch (name) {
      case 'type': e.dispatch('.uno:InsertText', [{ n: 'Text', v: arg ?? '' }]); break;
      case 'shape': e.dispatch('.uno:' + (arg ?? 'DrawText')); break;
      case 'picture': this.pickImage(); break;
      case 'link': this.linkDialog(); break;
      case 'header': e.dispatch('.uno:InsertPageHeader', [{ n: 'PageStyle', v: 'Default Page Style' }, { n: 'On', v: true }]); break;
      case 'footer': e.dispatch('.uno:InsertPageFooter', [{ n: 'PageStyle', v: 'Default Page Style' }, { n: 'On', v: true }]); break;
      case 'find': this.findDialog(); break;
      case 'zoomin': this.zoom.step(1); break;
      case 'zoomout': this.zoom.step(-1); break;
      case 'zoomfit': void this.zoom.fit(); break;
      case 'spell': this.spelling = true; this.buildRibbon(); break;
      case 'wordcount': { const c = await e.counts(); toast(`${c.words} / ${c.chars}`); break; }
      case 'splitcols': e.dispatch('.uno:SplitCell', [{ n: 'Amount', v: 2 }, { n: 'HorizontalFlag', v: false }]); break;
      case 'splitrows': e.dispatch('.uno:SplitCell', [{ n: 'Amount', v: 2 }, { n: 'HorizontalFlag', v: true }]); break;
      case 'border': await this.patchTable({ edge: (arg ?? 'all') as Edge, line: this.tableLine() }); break;
      case 'look': {
        const look = TABLE_LOOKS[arg ?? ''];
        if (!look) break;
        if (!(await e.tableLook(look))) { toast(t('table_not_found')); break; } // fills, header text and bold: UNO cell API
        await this.patchTable({ line: this.tableLine(), edges: look.edges as TableOp['edges'] }); // borders: docx patch
        break;
      }
      case 'borderstyle': this.borderLine = { ...this.borderLine, style: arg as LineSpec['style'] }; break;
      case 'borderwidth': this.borderLine = { ...this.borderLine, sz: Number(arg) }; break;
      case 'bordercolor': this.borderLine = { ...this.borderLine, color: arg ?? '000000' }; break;
      case 'orientation': await this.setOrientation(arg === 'landscape'); break;
      case 'pagesize': await this.setPageSize(arg ?? 'A4'); break;
      case 'margins': if (arg === 'custom') this.marginsDialog(); else await this.setMargins(MARGINS[arg ?? 'normal']); break;
      default: this.hooksAct?.(name, arg);
    }
  }
  hooksAct?: (name: string, arg?: string) => void;

  /** Page changes go through the page style of the caret's page (sizes in 1/100 mm), no docx patching needed in the browser. */
  private tableLine() { return { style: this.borderLine.style, size: this.borderLine.sz, color: this.borderLine.color }; }

  /**
   * Table borders have no UNO path that works from JavaScript, so (like the app) they are written into the exported docx and the
   * document is reloaded. A marker typed into the caret's table finds the table; the reload drops it. Undo history is lost.
   */
  private async patchTable(op: TableOp): Promise<boolean> {
    const e = this.engine!, d = this.doc!;
    e.dispatch('.uno:InsertText', [{ n: 'Text', v: MARK }]);
    await new Promise((r) => setTimeout(r, 300));
    const patched = patchDocx(await e.save('docx'), op);
    if (!patched) { e.dispatch('.uno:Undo'); toast(t('table_not_found')); return false; }
    await e.open(d.name.replace(/\.[^.]+$/, '') + '.docx', patched);
    this.engineEditable = true;
    d.modified = true;
    this.renderTopbar();
    return true;
  }

  private async setOrientation(landscape: boolean) {
    const p = (await this.engine!.pageStyle()) as Record<string, number>;
    const [w, hgt] = [Math.min(p.Width, p.Height), Math.max(p.Width, p.Height)];
    await this.engine!.pageStyle(landscape ? { Width: hgt, Height: w, IsLandscape: true } : { Width: w, Height: hgt, IsLandscape: false });
  }
  private async setPageSize(name: string) {
    const [w, hgt] = PAGE_SIZES[name] ?? PAGE_SIZES.A4;
    const p = (await this.engine!.pageStyle()) as Record<string, number | boolean>;
    await this.engine!.pageStyle(p.IsLandscape ? { Width: hgt, Height: w } : { Width: w, Height: hgt });
  }
  private async setMargins(m: { top: number; side: number }) {
    await this.engine!.pageStyle({ TopMargin: m.top, BottomMargin: m.top, LeftMargin: m.side, RightMargin: m.side });
  }
  private marginsDialog() {
    const field = (key: string) => h('input.field', { type: 'number', min: '0', max: '10', step: '0.1', value: '2.54', 'aria-label': t(key) });
    const top = field('margin_top'), bottom = field('margin_bottom'), left = field('margin_left'), right = field('margin_right');
    void this.engine!.pageStyle().then((p) => {
      top.value = String((Number(p.TopMargin) / 1000).toFixed(2)); bottom.value = String((Number(p.BottomMargin) / 1000).toFixed(2));
      left.value = String((Number(p.LeftMargin) / 1000).toFixed(2)); right.value = String((Number(p.RightMargin) / 1000).toFixed(2));
    });
    const lab = (key: string, el: HTMLElement) => h('label.field-label', null, `${t(key)} (${t('unit_cm')})`, el);
    dialog(t('margins_custom'), h('div.form', null, lab('margin_top', top), lab('margin_bottom', bottom), lab('margin_left', left), lab('margin_right', right)), [
      { label: t('cancel') },
      {
        label: t('insert_ok'), primary: true, onClick: async () => {
          const v = [top, bottom, left, right].map((i) => Math.round(Number(i.value) * 1000));
          if (v.some((x) => !Number.isFinite(x) || x < 0 || x > 10000)) return false;
          await this.engine!.pageStyle({ TopMargin: v[0], BottomMargin: v[1], LeftMargin: v[2], RightMargin: v[3] });
        },
      },
    ]);
  }

  private pickImage() {
    const input = h('input', { type: 'file', accept: 'image/*', style: { display: 'none' } });
    input.addEventListener('change', async () => {
      const f = input.files?.[0]; input.remove();
      if (f) this.engine!.insertImage(f.name, new Uint8Array(await f.arrayBuffer()));
    });
    document.body.append(input);
    input.click();
  }

  private linkDialog() {
    const text = h('input.field', { type: 'text', 'aria-label': t('link_text') });
    const url = h('input.field', { type: 'url', placeholder: 'https://', 'aria-label': t('link_address') });
    dialog(t('link'), h('div.form', null, h('label.field-label', null, t('link_text'), text), h('label.field-label', null, t('link_address'), url)), [
      { label: t('cancel') },
      {
        label: t('insert_ok'), primary: true, onClick: () => {
          const u = url.value.trim();
          if (!/^(https?:|mailto:)/i.test(u)) { url.focus(); return false; } // never create javascript: links
          this.engine!.dispatch('.uno:SetHyperlink', [{ n: 'Hyperlink.Text', v: text.value || u }, { n: 'Hyperlink.URL', v: u }]);
        },
      },
    ]);
  }

  private findDialog() {
    const find = h('input.field', { type: 'text', 'aria-label': t('find'), spellcheck: false });
    const rep = h('input.field', { type: 'text', 'aria-label': t('replace_with'), spellcheck: false });
    const run = (command: number) => this.engine!.dispatch('.uno:ExecuteSearch', [
      { n: 'SearchItem.SearchString', v: find.value }, { n: 'SearchItem.ReplaceString', v: rep.value },
      { n: 'SearchItem.Command', v: command }, { n: 'SearchItem.Backward', v: false },
    ]);
    dialog(t('find'), h('div.form', null, h('label.field-label', null, t('find'), find), h('label.field-label', null, t('replace_with'), rep)), [
      { label: t('close') },
      { label: t('replace_all'), keepOpen: true, onClick: () => run(3) },
      { label: t('replace'), keepOpen: true, onClick: () => run(2) },
      { label: t('find'), primary: true, keepOpen: true, onClick: () => run(0) },
    ]);
  }
}

export { promptDialog };
