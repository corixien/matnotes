import 'fake-indexeddb/auto';
import { describe, expect, it } from 'vitest';
import { cleanName, renameFile } from './rename';
import { Store } from './store';

describe('cleanName', () => {
  it('keeps the extension, replaces forbidden characters, falls back to the old name', () => {
    expect(cleanName('Bericht', 'a.docx')).toBe('Bericht.docx');
    expect(cleanName('Bericht', 'a.odt')).toBe('Bericht.odt');
    expect(cleanName('a/b:c?.docx', 'x.docx')).toBe('a_b_c_.docx');
    expect(cleanName('  ', 'x.docx')).toBe('x.docx');
    expect(cleanName('..hidden', 'x.docx')).toBe('hidden.docx');
  });
});

describe('renameFile', () => {
  it('moves the bytes, refuses taken names and keeps the cloud link', async () => {
    const s = new Store('rename-' + Math.random());
    await s.files.put('', 'a.docx', new Uint8Array([1, 2]), 5);
    await s.files.put('', 'b.docx', new Uint8Array([3]), 6);
    await s.folders.put({ id: 'f', ownerId: null, name: 'P', personal: true, role: 'owner', status: 'active', wrappedKey: '', deleted: false, localDir: '', members: '[]' });
    await s.docs.put({ id: 'd', folderId: 'f', file: '/a.docx', name: 'a.docx', baseVersion: 3, baseHash: 'h', localSize: 2, localModified: 5, remoteVersion: 3, remoteDeleted: false });
    expect(await renameFile(s, '', 'a.docx', 'B.docx')).toBe(false); // case-insensitive clash
    expect(await renameFile(s, '', 'a.docx', 'neu.docx')).toBe(true);
    expect(await s.files.get('', 'a.docx')).toBeUndefined();
    expect((await s.files.get('', 'neu.docx'))!.bytes).toEqual(new Uint8Array([1, 2]));
    const d = (await s.docs.get('d'))!;
    expect([d.file, d.name, d.baseHash, d.baseVersion]).toEqual(['/neu.docx', 'neu.docx', null, 3]);
  });
});
