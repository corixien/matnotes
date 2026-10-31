// Runs the dictionaries off the main thread: loading a Hunspell dictionary takes seconds (French about 10 s).
import { fetchLoader, type SpellLang, Spelling } from './spelling';

let sp: Spelling | null = null;
type Msg = { id: number; op: 'init'; base: string; userWords: string[] }
  | { id: number; op: 'check'; lang: SpellLang; text: string }
  | { id: number; op: 'detect'; text: string; docTag: string | null; deviceTag: string }
  | { id: number; op: 'add'; word: string };

self.onmessage = async (e: MessageEvent<Msg>) => {
  const m = e.data;
  try {
    let result: unknown = null;
    if (m.op === 'init') sp = new Spelling(fetchLoader(m.base), () => m.userWords);
    else if (!sp) throw new Error('not initialised');
    else if (m.op === 'check') result = await sp.check(m.lang, m.text);
    else if (m.op === 'detect') result = await sp.detect(m.text, m.docTag, m.deviceTag);
    else if (m.op === 'add') await sp.addUserWord(m.word);
    (self as unknown as Worker).postMessage({ id: m.id, result });
  } catch (err) { (self as unknown as Worker).postMessage({ id: m.id, error: (err as Error).message }); }
};
