// LibreOffice (WebAssembly, ZetaOffice) behind a small promise API. The engine draws into the page's #qtcanvas; the
// UNO side runs in public/office_thread.js and talks to us over a message port.

export const SOFFICE_URL: string = import.meta.env.VITE_SOFFICE_URL || 'https://cdn.zetaoffice.net/zetaoffice_latest/';

type Port = { postMessage(m: unknown): void; onmessage: ((e: MessageEvent) => void) | null };
type EmFS = { mkdir(p: string): void; writeFile(p: string, d: Uint8Array): void; readFile(p: string): Uint8Array; unlink(p: string): void };
declare global { interface Window { Module?: Record<string, unknown>; FS?: EmFS } }

/** `t` forces a UNO type for numbers (long is the default): float for FontHeight, short for some enums. */
export type UnoArg = { n: string; v: string | number | boolean; t?: 'float' | 'short' | 'long' | 'double' | 'unsigned_short' };
export type CommandState = { value: boolean | number | string | null; enabled: boolean };
/** A border line as in the docx table model: `sz` in eighths of a point, colour RRGGBB. */
export type LineSpec = { style: 'single' | 'dashed' | 'dotted' | 'double'; sz: number; color: string };
export type LookSpec = { edges: Partial<Record<'top' | 'bottom' | 'left' | 'right' | 'insideH' | 'insideV', LineSpec | null>>; headerFill?: string | null; headerText?: string | null; headerBold?: boolean; band?: string | null };
export type EngineFormat = 'docx' | 'odt' | 'pdf' | 'txt';

const DIR = '/tmp/office/';

export class Engine {
  private seq = 0;
  /** Files we put into the engine's memory file system for the open document (removed when the next document comes). */
  private scratch: string[] = [];
  private pending = new Map<number, { ok: (v: any) => void; fail: (e: Error) => void }>();
  private docWaiters: Array<() => void> = [];
  private stateListeners = new Set<(id: string, s: CommandState) => void>();
  private port!: Port;

  /** Loads the engine and resolves when the UNO thread is up (no document yet). */
  static start(canvas: HTMLCanvasElement, base = SOFFICE_URL): Promise<Engine> {
    const engine = new Engine();
    const module: Record<string, unknown> = {
      canvas,
      uno_scripts: ['/vendor/zeta.js', '/office_thread.js'],
      locateFile: (path: string, prefix?: string) => (prefix || base) + path,
    };
    if (new URL(base, location.href).origin !== location.origin) {
      module.mainScriptUrlOrBlob = new Blob([`importScripts('${base}soffice.js');`], { type: 'text/javascript' });
    }
    window.Module = module;
    return new Promise((resolve, reject) => {
      const s = document.createElement('script');
      s.src = base + 'soffice.js';
      s.onerror = () => reject(new Error('engine download failed'));
      s.onload = () => {
        (module.uno_main as Promise<Port>).then((port) => {
          engine.port = port;
          port.onmessage = (e) => engine.onMessage(e.data);
        });
      };
      engine.onThread = () => resolve(engine);
      document.body.appendChild(s);
    });
  }
  private onThread: () => void = () => {};

  private onMessage(m: any) {
    switch (m.cmd) {
      case 'thr_running': this.onThread(); break;
      case 'doc_ready': this.docWaiters.splice(0).forEach((f) => f()); break;
      case 'log': console.log('engine:', m.text); break;
      case 'state': this.stateListeners.forEach((l) => l(m.id, { value: m.value, enabled: m.enabled })); break;
      case 'error': this.settle(m.id, null, new Error(m.message)); break;
      default: this.settle(m.id, m, null);
    }
  }
  private settle(id: number | undefined, value: any, err: Error | null) {
    const p = id === undefined ? undefined : this.pending.get(id);
    if (!p) { if (err) console.error('engine:', err.message); return; }
    this.pending.delete(id!);
    err ? p.fail(err) : p.ok(value);
  }
  call<T = any>(msg: Record<string, unknown>): Promise<T> {
    const id = ++this.seq;
    return new Promise((ok, fail) => { this.pending.set(id, { ok, fail }); this.port.postMessage({ ...msg, id }); });
  }
  private docReady(): Promise<void> { return new Promise((r) => this.docWaiters.push(r)); }

  private fs(): EmFS {
    const fs = window.FS;
    if (!fs) throw new Error('engine file system missing');
    try { fs.mkdir(DIR); } catch { /* exists */ }
    return fs;
  }

  /** Opens a document from bytes. `name` decides the import filter (extension). */
  async open(name: string, bytes: Uint8Array): Promise<void> {
    const safe = name.replace(/[^\w.\- ]/g, '_');
    const path = DIR + safe;
    this.fs().writeFile(path, bytes);
    this.cleanScratch(path);
    const ready = this.docReady();
    this.port.postMessage({ cmd: 'open', path });
    await ready;
    this.nudgeCanvas();
  }

  async newDocument(): Promise<void> {
    this.cleanScratch();
    const ready = this.docReady();
    this.port.postMessage({ cmd: 'new' });
    await ready;
    this.nudgeCanvas();
  }

  async save(format: EngineFormat): Promise<Uint8Array<ArrayBuffer>> {
    const path = `${DIR}export-${++this.seq}.${format}`;
    await this.call({ cmd: 'save', path, ext: format });
    const fs = this.fs();
    const data = new Uint8Array(fs.readFile(path));
    fs.unlink(path);
    return data;
  }

  /** The memory file system never shrinks by itself: drop the previous document's input files. */
  private cleanScratch(keep?: string) {
    const fs = window.FS;
    for (const p of this.scratch) { if (p !== keep) { try { fs?.unlink(p); } catch { /* already gone */ } } }
    this.scratch = keep ? [keep] : [];
  }

  dispatch(uno: string, args: UnoArg[] = []) { this.port.postMessage({ cmd: 'dispatch', uno, args }); }
  watch(ids: string[]) { this.port.postMessage({ cmd: 'watch', ids }); }
  onState(l: (id: string, s: CommandState) => void) { this.stateListeners.add(l); return () => this.stateListeners.delete(l); }
  /** Commands that have no dispatcher in the current document (used by tests to catch typos). */
  async missing(ids: string[]): Promise<string[]> { return (await this.call<{ missing: string[] }>({ cmd: 'probe', ids })).missing; }
  /** Page style of the caret's page (1/100 mm); `set` changes properties first. */
  async pageStyle(set?: Record<string, number | boolean>): Promise<Record<string, number | boolean>> { return (await this.call<{ page: Record<string, number | boolean> }>({ cmd: 'pagestyle', set })).page; }
  async counts(): Promise<{ words: number; chars: number }> { return this.call({ cmd: 'counts' }); }
  /** Inserts a picture at the caret: bytes go to the engine file system, then `.uno:InsertGraphic`. */
  insertImage(name: string, bytes: Uint8Array) {
    const path = DIR + 'img-' + (++this.seq) + '-' + name.replace(/[^\w.\-]/g, '_');
    this.fs().writeFile(path, bytes);
    this.dispatch('.uno:InsertGraphic', [{ n: 'FileName', v: 'file://' + path }, { n: 'AsLink', v: false }]);
  }
  /** A character/paragraph property of the current selection (tests). */
  async selProp(name: string): Promise<unknown> { return (await this.call<{ value: unknown }>({ cmd: 'selprop', name })).value; }
  /** Table borders and looks for the table at the caret (false when the caret is not in a table). */
  async tableBorder(preset: string, line: LineSpec): Promise<boolean> { return (await this.call<{ ok: boolean }>({ cmd: 'tableBorder', preset, line })).ok; }
  async tableLook(look: LookSpec): Promise<boolean> { return (await this.call<{ ok: boolean }>({ cmd: 'tableLook', look })).ok; }
  async tableInfo(): Promise<Record<string, any> | null> { return (await this.call<{ info: Record<string, any> | null }>({ cmd: 'tableInfo' })).info; }
  async text(): Promise<string> { return (await this.call<{ text: string }>({ cmd: 'text' })).text; }
  async insertText(text: string): Promise<void> { await this.call({ cmd: 'insertText', text }); }

  /** Qt sizes its window from the canvas on a resize event; send one after the document appears. */
  private nudgeCanvas() { setTimeout(() => window.dispatchEvent(new Event('resize')), 300); }
}
