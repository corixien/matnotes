import type { Store } from './store';
import { fileKey } from './store';

const BAD = /[\\/:*?"<>|\u0000-\u001f]/g;
export const EXT = /\.(docx|odt|pdf)$/i;

/** A valid file name from user input: forbidden characters replaced, original extension kept when the user left it out. */
export function cleanName(input: string, oldName: string): string {
  let n = input.replace(BAD, '_').trim().replace(/^\.+/, '').slice(0, 120);
  if (!n) return oldName;
  if (!EXT.test(n)) n += oldName.match(EXT)?.[0] ?? '.docx';
  return n;
}

/**
 * Renames a stored file. A document that is linked to the cloud keeps its link (same cloud document, new name): the stored hash is
 * cleared so the next sync uploads it once more with the new encrypted name. Returns false when the name is taken.
 */
export async function renameFile(store: Store, dir: string, oldName: string, newName: string): Promise<boolean> {
  if (newName === oldName) return true;
  if ((await store.files.inDir(dir)).some((f) => f.name.toLowerCase() === newName.toLowerCase() && f.name !== oldName)) return false;
  const f = await store.files.get(dir, oldName);
  if (!f) return false;
  await store.files.put(dir, newName, f.bytes, f.modified);
  await store.files.remove(dir, oldName);
  const key = fileKey(dir, oldName);
  for (const folder of await store.folders.all()) {
    for (const d of await store.docs.byFolder(folder.id)) {
      if (d.file === key) await store.docs.put({ ...d, file: fileKey(dir, newName), name: newName, baseHash: null, localSize: -1, localModified: -1 });
    }
  }
  return true;
}
