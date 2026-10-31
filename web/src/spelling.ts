// Spell check of the document text with Hunspell dictionaries (Hunspell compiled to WebAssembly, hunspell-asm), language detection by script, stop words and the share of
// known words per dictionary. Port of Spelling.kt: same language list, stop words, thresholds and tie-breakers.
import type { HunspellFactory } from 'hunspell-asm';
import { LANGS, STOPWORDS } from './spell-data.gen';

export type SpellLang = { code: string; tag: string };
export type SpellProblem = { word: string; count: number; suggestions: string[] };
export interface Dict { spell(w: string): boolean; suggest(w: string): string[]; add(w: string): void }
export type DictLoader = (code: string) => Promise<Dict>;

export const languages: SpellLang[] = LANGS.map(([code, tag]) => ({ code, tag }));
const byCode = new Map(languages.map((l) => [l.code, l]));

/** Name in the UI language, e.g. "Deutsch (Österreich)". */
export function displayName(l: SpellLang, ui: string): string {
  try {
    const loc = new Intl.Locale(l.tag);
    const dn = new Intl.DisplayNames([ui], { type: 'language' });
    const rn = new Intl.DisplayNames([ui], { type: 'region' });
    const name = dn.of(loc.language) ?? l.tag;
    const script = loc.script ? ` (${new Intl.DisplayNames([ui], { type: 'script' }).of(loc.script)})` : loc.region ? ` (${rn.of(loc.region)})` : '';
    return name.charAt(0).toLocaleUpperCase(ui) + name.slice(1) + script;
  } catch { return l.tag; }
}

/** Language for a BCP 47 tag ("de-DE" -> German dictionary); exact tag first, then the first with the same language. */
export function forTag(tag: string | null | undefined): SpellLang | undefined {
  if (!tag?.trim()) return undefined;
  const t = tag.replace('_', '-');
  const exact = languages.find((l) => l.tag.toLowerCase() === t.toLowerCase());
  if (exact) return exact;
  const lang = t.split('-')[0].toLowerCase();
  return languages.find((l) => l.tag.split('-')[0].toLowerCase() === lang); // plain language first: "de" before "de-AT"
}

// ---- dictionaries ----

/** Hunspell (WebAssembly) over aff/dic bytes; user words live next to it because this build has no addWord. */
export type Files = (code: string) => Promise<{ aff: Uint8Array; dic: Uint8Array }>;
let factory: Promise<HunspellFactory> | null = null;

/**
 * hunspell-asm's own loadModule passes the emscripten module as a namespace import, which bundlers turn into a non-callable
 * object (the page then fails with "runtimeModule is not a function"): in the browser its parts are wired together here with
 * the default export. Node (tests) takes the package's own loader.
 */
async function loadModule(): Promise<HunspellFactory> {
  if (typeof window === 'undefined' && typeof (globalThis as any).importScripts === 'undefined') return (await import('hunspell-asm')).loadModule();
  const [{ getModuleLoader }, runtime, { hunspellLoader: makeFactory }] = await Promise.all([
    import('emscripten-wasm-loader'), import('hunspell-asm/dist/esm/lib/browser/hunspell'), import('hunspell-asm/dist/esm/hunspellLoader'),
  ]);
  const rt: any = (runtime as any).default ?? runtime;
  return (await getModuleLoader((r: any) => makeFactory(r), rt, undefined, {}))();
}

export function hunspellLoader(files: Files): DictLoader {
  return async (code) => {
    const f = await (factory ??= loadModule());
    const { aff, dic } = await files(code);
    const h = f.create(f.mountBuffer(aff, code + '.aff'), f.mountBuffer(dic, code + '.dic'));
    const user = new Set<string>();
    return { spell: (w) => user.has(w) || h.spell(w), suggest: (w) => h.suggest(w), add: (w) => void user.add(w) };
  };
}

/** Loads aff/dic from `base` (default /dict/) the first time a language is used. */
export function fetchLoader(base = '/dict/'): DictLoader {
  return hunspellLoader(async (code) => {
    const [aff, dic] = await Promise.all(['aff', 'dic'].map(async (e) => {
      const r = await fetch(`${base}${code}.${e}`);
      if (!r.ok) throw new Error(`dictionary ${code}: HTTP ${r.status}`);
      return new Uint8Array(await r.arrayBuffer());
    }));
    return { aff, dic };
  });
}

export class Spelling {
  private cache = new Map<string, Promise<Dict>>();
  private user = new Set<string>();
  constructor(private load: DictLoader, private userWords: () => string[] = () => [], private saveWord: (w: string) => void = () => {}) {}

  private dict(lang: SpellLang): Promise<Dict> {
    let p = this.cache.get(lang.code);
    if (!p) {
      p = this.load(lang.code).then((d) => { for (const w of this.userWords()) d.add(w); for (const w of this.user) d.add(w); return d; });
      p.catch(() => this.cache.delete(lang.code)); // a failed download can be retried
      this.cache.set(lang.code, p);
    }
    return p;
  }

  async addUserWord(word: string) {
    this.user.add(word);
    this.saveWord(word);
    for (const p of this.cache.values()) (await p).add(word);
  }

  // ---- text ----

  /** Distinct words of `text` with their counts, in order of first appearance; acronyms and single letters are left out. */
  static words(text: string): Map<string, number> {
    const out = new Map<string, number>();
    for (const m of text.replace(URL_LIKE, ' ').matchAll(TOKEN)) {
      const w = m[0];
      if (w.length < 2) continue;
      if ([...w].every((c) => c === c.toUpperCase() && c === c.toLocaleUpperCase() && c !== c.toLowerCase())) continue; // ALL CAPS: acronym
      out.set(w, (out.get(w) ?? 0) + 1);
    }
    return out;
  }

  /** Misspelled words of `text` in `lang`, each with up to six suggestions. */
  async check(lang: SpellLang, text: string): Promise<SpellProblem[]> {
    const d = await this.dict(lang);
    const accepted = (w: string) => d.spell(w.replace(/’/g, "'")) || d.spell(w.replace(/^[-']+|[-']+$/g, ''));
    const out: SpellProblem[] = [];
    for (const [w, n] of Spelling.words(text)) if (!accepted(w)) out.push({ word: w, count: n, suggestions: d.suggest(w.replace(/’/g, "'")).slice(0, 6) });
    return out;
  }

  // ---- language detection ----

  /**
   * Guesses the language of `text`: script and stop words give a few candidates, the dictionaries then decide by the share of
   * known words. `docTag` (document language) and the device language join the candidates and break ties. Falls back to
   * `docTag`, then the device language, then English.
   */
  async detect(text: string, docTag: string | null, deviceTag: string): Promise<SpellLang> {
    const sample = text.length > 20000 ? text.slice(0, 20000) : text;
    const toks = [...sample.replace(URL_LIKE, ' ').matchAll(TOKEN)].map((m) => m[0].toLowerCase().replace(/’/g, "'"));
    const prior = [forTag(docTag), forTag(deviceTag)].filter((x): x is SpellLang => !!x);
    const fallback = prior[0] ?? byCode.get('en')!;
    if (toks.length < 3) return fallback;

    const candidates = new Set<string>();
    scriptGuess(sample)?.forEach((c) => candidates.add(c));
    if (!candidates.size) {
      const scores = Object.entries(STOPWORDS).map(([c, sw]) => [c, toks.filter((x) => sw.has(x)).length] as const).filter(([, n]) => n > 0).sort((a, b) => b[1] - a[1]);
      for (const [c] of scores.slice(0, 3)) (GROUPS[c] ?? [c]).forEach((x) => candidates.add(x));
    }
    prior.forEach((l) => candidates.add(l.code));
    if (!candidates.size) return fallback;

    const distinct = [...new Set(toks.filter((x) => x.length > 1))].slice(0, 400);
    let best: SpellLang | null = null, bestScore = -1;
    for (const code of candidates) {
      const lang = byCode.get(code);
      if (!lang || !distinct.length) continue;
      let d: Dict;
      try { d = await this.dict(lang); } catch { continue; }
      const known = distinct.filter((x) => d.spell(x)).length / distinct.length;
      const bonus = prior[0]?.code === code ? 0.02 : prior.some((l) => l.code === code) ? 0.01 : 0;
      if (known + bonus > bestScore) { bestScore = known + bonus; best = lang; }
    }
    // under ~half the words known: the text is not really in any candidate language, trust the document language
    return !best || bestScore < 0.4 ? fallback : best;
  }
}

const URL_LIKE = /(https?:\/\/|www\.)\S+|\S+@\S+\.\S+/g;
const TOKEN = /\p{L}[\p{L}\p{M}]*(?:['’\-]\p{L}[\p{L}\p{M}]*)*/gu;
/** Dictionary candidates for the same detected language group (the stop word lists cannot tell these apart). */
const GROUPS: Record<string, string[]> = { hr: ['hr', 'sr-latn'], pt: ['pt', 'pt-pt'], en: ['en', 'en-gb'] };

function scriptGuess(letters: string): string[] | null {
  let cyr = 0, grk = 0, total = 0;
  for (const c of letters) {
    if (!/\p{L}/u.test(c)) continue;
    total++;
    if (/\p{Script=Cyrillic}/u.test(c)) cyr++; else if (/\p{Script=Greek}/u.test(c)) grk++;
  }
  if (!total) return null;
  if (grk * 2 > total) return ['el'];
  if (cyr * 2 > total) {
    const l = letters.toLowerCase();
    if ([...'іїєґ'].some((c) => l.includes(c))) return ['uk'];
    if ([...'ќѓ'].some((c) => l.includes(c))) return ['mk'];
    if ([...'ђћ'].some((c) => l.includes(c))) return ['sr'];
    const hard = [...l].filter((c) => c === 'ъ').length;
    if (hard * 40 > total && ![...'ыэ'].some((c) => l.includes(c))) return ['bg', 'ru'];
    return ['ru', 'uk', 'bg'];
  }
  return null;
}
