// Local storage in one IndexedDB database (the web counterpart of Documents/MatNotes plus sync.db).
// `files`   document metadata keyed by [dir, name] ('' = personal folder, otherwise the shared folder's local name), so lists never read bytes
// `blobs`   the document bytes under the same key
// `kv`      small records (sync cursor, sealed tokens and keys, ...)
// `folders` / `docs`  sync bookkeeping, same shape as SyncDb.kt (folders with wrapped keys, links between files and cloud documents)

export type StoredFile = { dir: string; name: string; bytes: Uint8Array; modified: number };
export type FileInfo = { dir: string; name: string; modified: number; size: number };

export type FolderRow = {
  id: string; ownerId: string | null; name: string; personal: boolean; role: string; status: string;
  wrappedKey: string; deleted: boolean; localDir: string; members: string;
};
/** Documents of this folder are synced. */
export const folderActive = (f: FolderRow) => f.status === 'active' && !f.deleted;

export type DocRow = {
  id: string; folderId: string;
  /** Local file as `dir/name`; null = known in the cloud, not stored here yet. */
  file: string | null;
  name: string | null; baseVersion: number; baseHash: string | null;
  localSize: number; localModified: number; remoteVersion: number; remoteDeleted: boolean;
};
export const fileKey = (dir: string, name: string) => `${dir}/${name}`;

const STORES = ['files', 'blobs', 'kv', 'folders', 'docs'] as const;
type StoreName = (typeof STORES)[number];

export class Store {
  private dbp: Promise<IDBDatabase> | null = null;

  constructor(readonly name = 'matnotes') {}

  private db(): Promise<IDBDatabase> {
    return (this.dbp ??= new Promise((resolve, reject) => {
      const r = indexedDB.open(this.name, 3);
      r.onupgradeneeded = (ev) => {
        const d = r.result;
        if (!d.objectStoreNames.contains('files')) d.createObjectStore('files', { keyPath: ['dir', 'name'] });
        if (!d.objectStoreNames.contains('blobs')) d.createObjectStore('blobs', { keyPath: ['dir', 'name'] });
        if (ev.oldVersion === 2) { // v2 kept the bytes inside `files`: split them
          const tx = r.transaction!;
          tx.objectStore('files').openCursor().onsuccess = (e) => {
            const c = (e.target as IDBRequest<IDBCursorWithValue | null>).result;
            if (!c) return;
            const { bytes, ...meta } = c.value;
            tx.objectStore('blobs').put({ dir: meta.dir, name: meta.name, bytes });
            c.update({ ...meta, size: bytes.length });
            c.continue();
          };
        }
        if (!d.objectStoreNames.contains('kv')) d.createObjectStore('kv');
        if (!d.objectStoreNames.contains('folders')) d.createObjectStore('folders', { keyPath: 'id' });
        if (!d.objectStoreNames.contains('docs')) d.createObjectStore('docs', { keyPath: 'id' }).createIndex('folder', 'folderId');
      };
      r.onsuccess = () => resolve(r.result);
      r.onerror = () => reject(r.error);
    }));
  }

  private run<T>(store: StoreName, mode: IDBTransactionMode, f: (s: IDBObjectStore) => IDBRequest<T>): Promise<T> {
    return this.db().then((d) => new Promise<T>((resolve, reject) => {
      const req = f(d.transaction(store, mode).objectStore(store));
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    }));
  }

  /** Closes the connection (tests that delete the database, sign-out in another tab). */
  async close() { (await this.db()).close(); this.dbp = null; }

  /** Runs reads/writes on several stores in one transaction (a file and its bytes change together). */
  private async tx<T>(names: StoreName[], mode: IDBTransactionMode, f: (t: IDBTransaction) => Promise<T>): Promise<T> {
    const d = await this.db();
    const t = d.transaction(names, mode);
    const done = new Promise<void>((resolve, reject) => { t.oncomplete = () => resolve(); t.onerror = () => reject(t.error); t.onabort = () => reject(t.error); });
    const result = await f(t);
    await done;
    return result;
  }
  private static req<T>(r: IDBRequest<T>): Promise<T> { return new Promise((ok, fail) => { r.onsuccess = () => ok(r.result); r.onerror = () => fail(r.error); }); }

  readonly files = {
    put: (dir: string, name: string, bytes: Uint8Array, modified = Date.now()): Promise<void> => this.tx(['files', 'blobs'], 'readwrite', async (t) => {
      t.objectStore('blobs').put({ dir, name, bytes });
      t.objectStore('files').put({ dir, name, modified, size: bytes.length } satisfies FileInfo);
    }),
    get: (dir: string, name: string): Promise<StoredFile | undefined> => this.tx(['files', 'blobs'], 'readonly', async (t) => {
      const [meta, blob] = await Promise.all([Store.req<FileInfo | undefined>(t.objectStore('files').get([dir, name])), Store.req<{ bytes: Uint8Array } | undefined>(t.objectStore('blobs').get([dir, name]))]);
      return meta && blob ? { dir, name, bytes: blob.bytes, modified: meta.modified } : undefined;
    }),
    remove: (dir: string, name: string): Promise<void> => this.tx(['files', 'blobs'], 'readwrite', async (t) => { t.objectStore('files').delete([dir, name]); t.objectStore('blobs').delete([dir, name]); }),
    all: async (): Promise<FileInfo[]> => (await this.run<FileInfo[]>('files', 'readonly', (s) => s.getAll())).sort((a, b) => b.modified - a.modified),
    inDir: async (dir: string): Promise<FileInfo[]> => (await this.files.all()).filter((f) => f.dir === dir),
    /** A name that does not exist in `dir` yet: "Name.docx", "Name (2).docx", ... */
    freeName: async (dir: string, name: string): Promise<string> => {
      const dot = name.lastIndexOf('.');
      const [base, ext] = dot > 0 ? [name.slice(0, dot), name.slice(dot)] : [name, ''];
      let n = name, i = 1;
      const taken = new Set((await this.files.inDir(dir)).map((f) => f.name.toLowerCase()));
      while (taken.has(n.toLowerCase())) n = `${base} (${++i})${ext}`;
      return n;
    },
  };

  readonly kv = {
    get: <T = unknown>(key: string): Promise<T | undefined> => this.run('kv', 'readonly', (s) => s.get(key)),
    set: async (key: string, value: unknown): Promise<void> => { await this.run('kv', 'readwrite', (s) => s.put(value, key)); },
    del: async (key: string): Promise<void> => { await this.run('kv', 'readwrite', (s) => s.delete(key)); },
  };

  readonly folders = {
    all: async (): Promise<FolderRow[]> => (await this.run<FolderRow[]>('folders', 'readonly', (s) => s.getAll()))
      .sort((a, b) => Number(b.personal) - Number(a.personal) || a.name.localeCompare(b.name, undefined, { sensitivity: 'base' })),
    get: (id: string): Promise<FolderRow | undefined> => this.run('folders', 'readonly', (s) => s.get(id)),
    put: async (f: FolderRow): Promise<void> => { await this.run('folders', 'readwrite', (s) => s.put(f)); },
  };

  readonly docs = {
    byFolder: (folderId: string): Promise<DocRow[]> => this.run('docs', 'readonly', (s) => s.index('folder').getAll(folderId)),
    get: (id: string): Promise<DocRow | undefined> => this.run('docs', 'readonly', (s) => s.get(id)),
    put: async (d: DocRow): Promise<void> => { await this.run('docs', 'readwrite', (s) => s.put(d)); },
    remove: async (id: string): Promise<void> => { await this.run('docs', 'readwrite', (s) => s.delete(id)); },
  };

  /** Wipes everything that belongs to a signed-in account (sync state, keys, tokens); documents and device settings stay. */
  async clearAccount(): Promise<void> {
    const d = await this.db();
    const keys = await this.run<IDBValidKey[]>('kv', 'readonly', (s) => s.getAllKeys());
    await new Promise<void>((resolve, reject) => {
      const tx = d.transaction(['kv', 'folders', 'docs'], 'readwrite');
      for (const k of keys) if (typeof k === 'string' && (k === 'account' || k === 'account-seal-key' || k.startsWith('sync:'))) tx.objectStore('kv').delete(k);
      tx.objectStore('folders').clear();
      tx.objectStore('docs').clear();
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
  }
}

export const store = new Store();
export const Files = store.files;
export const Kv = store.kv;
