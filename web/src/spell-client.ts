// Main-thread side of the spell worker; same calls as Spelling.
import type { SpellLang, SpellProblem } from './spelling';
import { Kv } from './store';

export class SpellClient {
  private worker: Worker | null = null;
  private seq = 0;
  private pending = new Map<number, { ok: (v: any) => void; fail: (e: Error) => void }>();
  private ready: Promise<unknown> | null = null;

  private send<T>(msg: Record<string, unknown>): Promise<T> {
    const id = ++this.seq;
    return new Promise((ok, fail) => { this.pending.set(id, { ok, fail }); this.worker!.postMessage({ ...msg, id }); });
  }

  private async start() {
    if (this.ready) return this.ready;
    this.worker = new Worker(new URL('./spell.worker.ts', import.meta.url), { type: 'module' });
    this.worker.onmessage = (e) => {
      const p = this.pending.get(e.data.id);
      if (!p) return;
      this.pending.delete(e.data.id);
      e.data.error ? p.fail(new Error(e.data.error)) : p.ok(e.data.result);
    };
    const userWords = (await Kv.get<string[]>('user-words')) ?? [];
    return (this.ready = this.send({ op: 'init', base: new URL('/dict/', location.href).href, userWords }));
  }

  async check(lang: SpellLang, text: string): Promise<SpellProblem[]> { await this.start(); return this.send({ op: 'check', lang, text }); }
  async detect(text: string, docTag: string | null, deviceTag = navigator.language): Promise<SpellLang> { await this.start(); return this.send({ op: 'detect', text, docTag, deviceTag }); }
  async addUserWord(word: string) {
    await this.start();
    await this.send({ op: 'add', word });
    await Kv.set('user-words', [...new Set([...((await Kv.get<string[]>('user-words')) ?? []), word])]);
  }
}
