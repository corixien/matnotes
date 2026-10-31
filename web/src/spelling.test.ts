import { existsSync, readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { displayName, forTag, hunspellLoader, languages, Spelling } from './spelling';

const dir = new URL('../public/dict/', import.meta.url);
const have = existsSync(new URL('de.dic', dir)) && existsSync(new URL('en.dic', dir));
const loader = hunspellLoader(async (code) => ({ aff: readFileSync(new URL(`${code}.aff`, dir)), dic: readFileSync(new URL(`${code}.dic`, dir)) }));

describe('language table', () => {
  it('maps tags like the app', () => {
    expect(forTag('de-DE')?.code).toBe('de');
    expect(forTag('de')?.code).toBe('de'); // plain language before de-AT
    expect(forTag('de-AT')?.code).toBe('de-at');
    expect(forTag('en_GB')?.code).toBe('en-gb');
    expect(forTag('xx')).toBeUndefined();
    expect(forTag(null)).toBeUndefined();
    expect(languages.length).toBe(47);
  });
  it('names languages in the UI language', () => {
    expect(displayName(forTag('de-AT')!, 'de')).toBe('Deutsch (Österreich)');
    expect(displayName(forTag('en-GB')!, 'en')).toBe('English (United Kingdom)');
  });
});

describe('words', () => {
  it('skips acronyms, single letters, urls and e-mail addresses', () => {
    const w = [...Spelling.words('Das ist NASA und ein a Test https://example.com/x mail@example.com don’t Haus Haus')];
    expect(w.map(([x]) => x)).toEqual(['Das', 'ist', 'und', 'ein', 'Test', 'don’t', 'Haus']);
    expect(w.find(([x]) => x === 'Haus')![1]).toBe(2);
  });
});

describe.skipIf(!have)('with the real dictionaries', () => {
  const sp = new Spelling(loader);
  it('finds mistakes and suggests', async () => {
    const r = await sp.check(forTag('de')!, 'Das ist ein Hauus und ein Tisch. Der Tisch steht am Fenster.');
    expect(r.map((p) => p.word)).toEqual(['Hauus']);
    expect(r[0].suggestions).toContain('Haus');
  }, 60000);
  it('detects German and English, document and device language only break ties', async () => {
    const de = 'Das ist ein Test und der Text ist nicht sehr lang, aber er wird von der Erkennung gut erkannt, weil er viele Wörter enthält.';
    const en = 'This is a test and the text is not very long, but it will be recognised well because it contains many words of the language.';
    expect((await sp.detect(de, null, 'en-US')).code).toBe('de');
    expect((await sp.detect(en, null, 'de-DE')).code).toMatch(/^en/);
    expect((await sp.detect('Hi', 'fr-FR', 'en')).code).toBe('fr'); // too short: document language
    expect((await sp.detect(en, 'en-GB', 'de-DE')).code).toBe('en-gb'); // document language wins the tie between en and en-gb
  }, 120000);
  it('detects a Latin-script language that is neither document nor device language', async () => {
    const fr = 'Ceci est un test et le texte est assez long pour que la détection fonctionne avec les mots de la langue que nous avons choisie dans les dictionnaires.';
    expect((await sp.detect(fr, 'de-DE', 'de-DE')).code).toBe('fr');
  }, 120000);
  it('user words are accepted', async () => {
    const s2 = new Spelling(loader);
    expect((await s2.check(forTag('de')!, 'Das Xyzzyx steht hier')).map((p) => p.word)).toContain('Xyzzyx');
    await s2.addUserWord('Xyzzyx');
    expect((await s2.check(forTag('de')!, 'Das Xyzzyx steht hier')).map((p) => p.word)).not.toContain('Xyzzyx');
  }, 60000);
  it('script detection: Greek and Cyrillic', async () => {
    expect((await sp.detect('Αυτό είναι ένα δοκιμαστικό κείμενο για την ανίχνευση γλώσσας', null, 'de')).code).toBe('el');
    expect((await sp.detect('Это тестовый текст для определения языка в документе, который достаточно длинный', null, 'de')).code).toMatch(/^(ru|uk|bg)$/);
  }, 120000);
});
