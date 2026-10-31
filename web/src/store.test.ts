import 'fake-indexeddb/auto';
import { describe, expect, it } from 'vitest';
import { Files, Kv } from './store';

describe('Files', () => {
  it('stores, lists per folder and removes', async () => {
    await Files.put('', 'a.docx', new Uint8Array([1, 2, 3]), 10);
    await Files.put('Schule', 'b.docx', new Uint8Array([4]), 20);
    expect((await Files.get('', 'a.docx'))!.bytes).toEqual(new Uint8Array([1, 2, 3]));
    expect((await Files.inDir('Schule')).map((f) => f.name)).toEqual(['b.docx']);
    expect((await Files.all()).map((f) => f.name)).toEqual(['b.docx', 'a.docx']); // newest first
    await Files.remove('', 'a.docx');
    expect(await Files.get('', 'a.docx')).toBeUndefined();
  });
  it('finds a free name', async () => {
    await Files.put('x', 'Doc.docx', new Uint8Array(), 1);
    await Files.put('x', 'Doc (2).docx', new Uint8Array(), 1);
    expect(await Files.freeName('x', 'Doc.docx')).toBe('Doc (3).docx');
    expect(await Files.freeName('x', 'New.docx')).toBe('New.docx');
  });
});

describe('Kv', () => {
  it('round-trips values', async () => {
    await Kv.set('k', { a: 1 });
    expect(await Kv.get('k')).toEqual({ a: 1 });
    await Kv.del('k');
    expect(await Kv.get('k')).toBeUndefined();
  });
});
