// Keeps the local folders (the personal folder and one directory per shared folder in the local store) and the encrypted cloud
// folders in step. Port of SyncEngine.kt.
//
// Per cycle: read the change feed -> update folders (keys, names, members) and the known cloud state of each document ->
// per folder compare the local files with the state of the last sync (hash) and the cloud (version) and act on the result of
// the planner. Files are encrypted before the upload and decrypted after the download; only ciphertext and a version number are
// exchanged. Whole files are synced, so a conflict keeps both versions (see planner.ts).
import { t } from '../i18n';
import { type DocRow, type FileInfo, fileKey, type FolderRow, folderActive, type Store } from '../store';
import type { Account } from './account';
import { ApiException, NetworkError } from './api';
import { b64, Vault, sha256, unb64, type Bytes, fingerprint } from './crypto';
import { exclusive } from './lock';
import { plan } from './planner';

export class SyncResult { uploaded = 0; downloaded = 0; deleted = 0; conflicts = 0; errors: string[] = []; }
export type SyncStatus =
  | { kind: 'idle' } | { kind: 'running' }
  | { kind: 'done'; time: number; result: SyncResult }
  | { kind: 'failed'; time: number; message: string; offline: boolean };

export const MAX_FILE = 25 * 1024 * 1024;
export const EXTENSIONS = new Set(['docx', 'odt', 'pdf']);
const UNKNOWN = -1;
const ext = (name: string) => name.slice(name.lastIndexOf('.') + 1).toLowerCase();
const BAD_NAME = /[\\/:*?"<>|\u0000-\u001f]/g;

export type UserInfo = { id: string; name: string; publicKey: Bytes; fingerprint: string };
type Json = Record<string, any>;

export class SyncEngine {
  private keys = new Map<string, Bytes>();
  private statusValue: SyncStatus = { kind: 'idle' };
  private listeners = new Set<() => void>();
  folders: FolderRow[] = [];

  constructor(private store: Store, private account: Account, private isOpen: (dir: string, name: string) => boolean = () => false, private now: () => number = Date.now) {}

  get status() { return this.statusValue; }
  /** Called whenever status or folder list changed. */
  subscribe(f: () => void) { this.listeners.add(f); return () => this.listeners.delete(f); }
  private emit() { this.listeners.forEach((l) => l()); }
  private setStatus(s: SyncStatus) { this.statusValue = s; this.emit(); }
  private async publishFolders() { this.folders = await this.store.folders.all(); this.emit(); }
  async load() { await this.publishFolders(); }

  /** Forgets everything local about the account (keys, cursor, links). Files stay. */
  async reset() {
    this.keys.clear();
    this.folders = [];
    this.statusValue = { kind: 'idle' };
    this.emit();
  }

  /** Runs one sync cycle. Files open in the editor are never replaced or deleted. */
  sync(): Promise<SyncResult> {
    return exclusive('matnotes-sync-' + this.store.name, async () => {
      const res = new SyncResult();
      this.setStatus({ kind: 'running' });
      try {
        if (!this.account.loggedIn) throw new Error('not signed in');
        await this.pullFeed();
        await this.ensurePersonalFolder();
        let pass = 0, again: boolean;
        do { // conflict copies are new files: a second pass uploads them
          again = false;
          for (const f of (await this.store.folders.all()).filter(folderActive)) {
            try { if (await this.syncFolder(f, res)) again = true; } catch (e) { res.errors.push(`${f.name}: ${(e as Error).message}`); }
          }
          pass++;
        } while (again && pass < 2);
        this.setStatus({ kind: 'done', time: this.now(), result: res });
      } catch (e) {
        const msg = (e as Error).message || String(e);
        res.errors.push(msg);
        this.setStatus({ kind: 'failed', time: this.now(), message: msg, offline: e instanceof NetworkError });
      }
      return res;
    });
  }

  // ---- folders and the change feed ----

  private async folderKey(f: FolderRow): Promise<Bytes | null> {
    const cached = this.keys.get(f.id);
    if (cached) return cached;
    if (!f.wrappedKey) return null;
    const priv = await this.account.privateKey();
    if (!priv) return null;
    try { const k = await Vault.unwrapFolderKey(priv, f.wrappedKey); this.keys.set(f.id, k); return k; } catch { return null; }
  }

  private sanitize(name: string) { return name.replace(BAD_NAME, '_').trim().replace(/^\.+|\.+$/g, '').slice(0, 60) || 'Folder'; }
  private uniqueDir(name: string, taken: string[]) {
    const base = this.sanitize(name);
    let candidate = base, n = 2;
    while (taken.some((x) => x.toLowerCase() === candidate.toLowerCase())) candidate = `${base} (${n++})`;
    return candidate;
  }

  private async pullFeed() {
    const since = (await this.store.kv.get<string>('sync:cursor')) ?? '0';
    const feed = await this.account.call('GET', `/sync/changes?since=${since}`);
    await this.applyFolders(feed.folders);
    await this.applyDocs(feed.docs);
    await this.store.kv.set('sync:cursor', String(feed.cursor));
    await this.publishFolders();
  }

  private async applyFolders(arr: Json[]) {
    for (const j of arr) {
      const old = await this.store.folders.get(j.id);
      let f: FolderRow = {
        id: j.id, ownerId: j.ownerId || null, name: old?.name ?? '…', personal: !!j.personal, role: j.role, status: j.status,
        wrappedKey: j.wrappedKey, deleted: !!j.deleted, localDir: old?.localDir ?? '', members: JSON.stringify(j.members ?? []),
      };
      this.keys.delete(j.id);
      if (j.status !== 'removed' && j.wrappedKey) {
        const key = await this.folderKey(f);
        if (key) { try { f = { ...f, name: await Vault.openFolderName(key, j.id, j.encName) }; } catch { /* keep the old name */ } }
      }
      if (folderActive(f) && !f.localDir && !f.personal) {
        f = { ...f, localDir: this.uniqueDir(f.name, (await this.store.folders.all()).filter((x) => x.id !== j.id).map((x) => x.localDir)) };
      }
      await this.store.folders.put(f);
    }
  }

  private async applyDocs(arr: Json[]) {
    for (const j of arr) {
      const folder = await this.store.folders.get(j.folderId);
      if (!folder) continue;
      const old = await this.store.docs.get(j.id);
      const key = await this.folderKey(folder);
      let name = old?.name ?? null;
      if (key) { try { name = await Vault.openDocName(key, j.id, j.encName); } catch { /* keep */ } }
      await this.store.docs.put(old
        ? { ...old, name, remoteVersion: j.version, remoteDeleted: !!j.deleted }
        : { id: j.id, folderId: folder.id, file: null, name, baseVersion: 0, baseHash: null, localSize: UNKNOWN, localModified: UNKNOWN, remoteVersion: j.version, remoteDeleted: !!j.deleted });
    }
  }

  /** Every account has one personal folder; it maps to the main folder of the app. The first device creates it. */
  private async ensurePersonalFolder() {
    if ((await this.store.folders.all()).some((f) => f.personal && folderActive(f))) return;
    const pub = this.account.publicKey;
    if (!pub) return;
    const id = crypto.randomUUID();
    const key = Vault.newFolderKey();
    try {
      await this.account.call('POST', '/folders', { id, personal: true, encName: await Vault.sealFolderName(key, id, 'Personal'), wrappedKey: await Vault.wrapFolderKey(pub, key) });
    } catch (e) {
      if (!(e instanceof ApiException) || e.code !== 'personal_exists') throw e; // another device was faster: its folder arrives with the next feed
    }
    await this.pullFeed();
  }

  // ---- one folder ----

  /** Returns true when files were created that need another pass (conflict copies). */
  private async syncFolder(folder: FolderRow, res: SyncResult): Promise<boolean> {
    const key = await this.folderKey(folder);
    if (!key) throw new Error(t('sync_err_key'));
    const local = (await this.store.files.inDir(folder.localDir)).filter((f) => EXTENSIONS.has(ext(f.name)));
    const byKey = new Map(local.map((f) => [fileKey(f.dir, f.name), f]));
    let again = false;
    for (const d of await this.store.docs.byFolder(folder.id)) {
      try { if (await this.handleDoc(folder, key, d, d.file ? byKey.get(d.file) : undefined, res)) again = true; } catch (e) {
        res.errors.push(`${d.name ?? d.id}: ${(e as Error).message}`);
      }
    }
    // files that are not linked to a cloud document yet
    const linked = new Set((await this.store.docs.byFolder(folder.id)).map((d) => d.file).filter(Boolean));
    for (const f of local) {
      if (linked.has(fileKey(f.dir, f.name))) continue;
      try { await this.upload(folder, key, f, res); } catch (e) { res.errors.push(`${f.name}: ${(e as Error).message}`); }
    }
    return again;
  }

  private async read(dir: string, name: string): Promise<Uint8Array> {
    const f = await this.store.files.get(dir, name);
    if (!f) throw new Error(t('sync_err_read'));
    return f.bytes;
  }

  private async handleDoc(folder: FolderRow, key: Bytes, d: DocRow, file: FileInfo | undefined, res: SyncResult): Promise<boolean> {
    if (d.file === null) { // known in the cloud, not stored here yet
      if (d.remoteDeleted) { await this.store.docs.remove(d.id); return false; }
      await this.download(folder, key, d, true, res);
      return false;
    }
    const exists = !!file;
    let localChanged = false, hash: string | null = null, bytes: Uint8Array | null = null;
    if (file) {
      if (file.size === d.localSize && file.modified === d.localModified) hash = d.baseHash;
      else {
        bytes = await this.read(file.dir, file.name);
        hash = b64(await sha256(bytes));
        localChanged = hash !== d.baseHash;
        if (!localChanged) await this.store.docs.put({ ...d, localSize: file.size, localModified: file.modified });
      }
    }
    const remoteChanged = d.remoteVersion !== d.baseVersion;
    const action = plan(exists, localChanged, d.remoteDeleted, remoteChanged);
    const [dir, name] = this.split(d.file);
    // an open document is neither replaced nor deleted; it is picked up again on the next cycle
    if (this.isOpen(dir, name) && ['PULL', 'CONFLICT', 'DELETE_LOCAL', 'REDOWNLOAD'].includes(action)) return false;
    switch (action) {
      case 'NOTHING': break;
      case 'FORGET': case 'DETACH': await this.store.docs.remove(d.id); break; // DETACH: the file stays and is uploaded as a new document right after
      case 'DELETE_LOCAL': await this.store.files.remove(dir, name); await this.store.docs.remove(d.id); res.deleted++; break;
      case 'DELETE_REMOTE':
        try {
          await this.account.call('DELETE', `/folders/${folder.id}/docs/${d.id}`, null, { 'If-Match': String(d.baseVersion) });
          await this.store.docs.remove(d.id); res.deleted++;
        } catch (e) {
          if (e instanceof ApiException && e.status === 409) await this.store.docs.put({ ...d, remoteVersion: e.serverVersion ?? d.remoteVersion, remoteDeleted: e.deleted });
          else throw e;
        }
        break;
      case 'PUSH': await this.push(folder, key, d, file!, bytes ?? (await this.read(dir, name)), hash!, res); break;
      case 'PULL': await this.download(folder, key, d, false, res); break;
      case 'REDOWNLOAD': await this.download(folder, key, { ...d, file: null }, true, res); break;
      case 'CONFLICT': return this.resolveConflict(folder, key, d, file!, bytes ?? (await this.read(dir, name)), hash!, res);
    }
    return false;
  }

  private split(file: string): [string, string] { const i = file.lastIndexOf('/'); return [file.slice(0, i), file.slice(i + 1)]; }

  private async push(folder: FolderRow, key: Bytes, d: DocRow, file: FileInfo, bytes: Uint8Array, hash: string, res: SyncResult) {
    if (bytes.length > MAX_FILE) throw new Error(t('sync_err_too_large'));
    const version = d.baseVersion + 1;
    const sealed = await Vault.sealDoc(key, d.id, version, bytes);
    try {
      await this.account.bytes('PUT', `/folders/${folder.id}/docs/${d.id}`,
        { 'If-Match': String(d.baseVersion), 'X-Enc-Name': await Vault.sealDocName(key, d.id, file.name), 'X-Wrapped-Key': sealed.wrappedKey }, sealed.data);
    } catch (e) {
      // someone else wrote first: remember the new cloud version, the next cycle resolves it as a conflict
      if (e instanceof ApiException && e.status === 409) { await this.store.docs.put({ ...d, remoteVersion: e.serverVersion ?? d.remoteVersion, remoteDeleted: e.deleted }); return; }
      throw e;
    }
    await this.store.docs.put({ ...d, name: file.name, baseVersion: version, baseHash: hash, remoteVersion: version, localSize: file.size, localModified: file.modified });
    res.uploaded++;
  }

  private async upload(folder: FolderRow, key: Bytes, file: FileInfo, res: SyncResult) {
    if (file.size > MAX_FILE) throw new Error(t('sync_err_too_large'));
    const bytes = await this.read(file.dir, file.name);
    const id = crypto.randomUUID();
    const sealed = await Vault.sealDoc(key, id, 1, bytes);
    await this.account.bytes('PUT', `/folders/${folder.id}/docs/${id}`,
      { 'If-Match': '0', 'X-Enc-Name': await Vault.sealDocName(key, id, file.name), 'X-Wrapped-Key': sealed.wrappedKey }, sealed.data);
    await this.store.docs.put({ id, folderId: folder.id, file: fileKey(file.dir, file.name), name: file.name, baseVersion: 1, baseHash: b64(await sha256(bytes)), localSize: file.size, localModified: file.modified, remoteVersion: 1, remoteDeleted: false });
    res.uploaded++;
  }

  private async fetchRemote(folder: FolderRow, key: Bytes, id: string): Promise<{ version: number; plain: Bytes }> {
    const r = await this.account.bytes('GET', `/folders/${folder.id}/docs/${id}/blob`);
    const version = Number(r.headers.get('x-version'));
    return { version, plain: await Vault.openDoc(key, id, version, r.headers.get('x-wrapped-key')!, r.body) };
  }

  private safeName(name: string | null) {
    const n = (name ?? 'document.docx').replace(BAD_NAME, '_').trim() || 'document.docx';
    return EXTENSIONS.has(ext(n)) && n.includes('.') ? n : n + '.docx';
  }

  private async download(folder: FolderRow, key: Bytes, d: DocRow, createNew: boolean, res: SyncResult) {
    const remote = await this.fetchRemote(folder, key, d.id);
    const hash = b64(await sha256(remote.plain));
    let file: string;
    if (createNew || d.file === null) {
      const name = await this.store.files.freeName(folder.localDir, this.safeName(d.name));
      await this.store.files.put(folder.localDir, name, remote.plain, this.now());
      file = fileKey(folder.localDir, name);
    } else {
      const [dir, name] = this.split(d.file);
      // the cloud name wins: a document renamed on another device arrives under its new name
      const wanted = d.name ? this.safeName(d.name) : name;
      const target = wanted.toLowerCase() === name.toLowerCase() ? name : await this.store.files.freeName(dir, wanted);
      await this.store.files.put(dir, target, remote.plain, this.now());
      if (target !== name) await this.store.files.remove(dir, name);
      file = fileKey(dir, target);
    }
    // size/modified are unknown after our own write: forces one hash check next cycle, which finds "unchanged"
    await this.store.docs.put({ ...d, file, baseVersion: remote.version, baseHash: hash, remoteVersion: remote.version, localSize: UNKNOWN, localModified: UNKNOWN });
    res.downloaded++;
  }

  /** The cloud version takes the original name; this device's version survives as "<name> (Konflikt <date>)" and is uploaded as its own document. */
  private async resolveConflict(folder: FolderRow, key: Bytes, d: DocRow, file: FileInfo, localBytes: Uint8Array, localHash: string, res: SyncResult): Promise<boolean> {
    const remote = await this.fetchRemote(folder, key, d.id);
    const remoteHash = b64(await sha256(remote.plain));
    if (remoteHash === localHash) { // both sides made the same change
      await this.store.docs.put({ ...d, baseVersion: remote.version, baseHash: localHash, remoteVersion: remote.version, localSize: file.size, localModified: file.modified });
      return false;
    }
    const copyName = await this.store.files.freeName(folder.localDir, this.conflictName(file.name));
    await this.store.files.put(folder.localDir, copyName, localBytes, this.now());
    await this.store.files.put(file.dir, file.name, remote.plain, this.now());
    await this.store.docs.put({ ...d, baseVersion: remote.version, baseHash: remoteHash, remoteVersion: remote.version, localSize: UNKNOWN, localModified: UNKNOWN });
    res.conflicts++;
    return true;
  }

  private conflictName(name: string) {
    const dot = name.lastIndexOf('.');
    const [stem, e] = dot > 0 ? [name.slice(0, dot), name.slice(dot)] : [name, ''];
    const d = new Date(this.now());
    const date = `${String(d.getDate()).padStart(2, '0')}.${String(d.getMonth() + 1).padStart(2, '0')}.${d.getFullYear()}`;
    return `${stem} (${t('sync_conflict_word')} ${date})${e}`;
  }

  // ---- sharing ----

  /** Finds an account by e-mail (throws ApiException "not_found" when there is none). */
  async lookupUser(email: string): Promise<UserInfo> {
    const j = await this.account.call('GET', '/users/lookup?email=' + encodeURIComponent(email.trim()));
    const publicKey = unb64(j.publicKey);
    return { id: j.id, name: j.name, publicKey, fingerprint: await fingerprint(publicKey) };
  }

  createSharedFolder(name: string): Promise<string> {
    return exclusive('matnotes-sync-' + this.store.name, async () => {
      const pub = this.account.publicKey;
      if (!pub) throw new Error('not signed in');
      const id = crypto.randomUUID();
      const key = Vault.newFolderKey();
      await this.account.call('POST', '/folders', { id, encName: await Vault.sealFolderName(key, id, name.trim()), wrappedKey: await Vault.wrapFolderKey(pub, key) });
      await this.pullFeed();
      return id;
    });
  }

  /** The folder key is wrapped with the invitee's public key right now, so they can join later without this device being online. */
  invite(folderId: string, user: UserInfo): Promise<void> {
    return exclusive('matnotes-sync-' + this.store.name, async () => {
      const f = await this.store.folders.get(folderId);
      const key = f ? await this.folderKey(f) : null;
      if (!key) throw new Error(t('sync_err_key'));
      await this.account.call('POST', `/folders/${folderId}/members`, { userId: user.id, wrappedKey: await Vault.wrapFolderKey(user.publicKey, key) });
      await this.pullFeed();
    });
  }

  answerInvite(folderId: string, accept: boolean): Promise<void> {
    return exclusive('matnotes-sync-' + this.store.name, async () => { await this.account.call('POST', `/folders/${folderId}/${accept ? 'accept' : 'decline'}`); await this.pullFeed(); });
  }

  /** Owner removes a member / cancels an invitation, or a member leaves (userId = own id). */
  removeMember(folderId: string, userId: string): Promise<void> {
    return exclusive('matnotes-sync-' + this.store.name, async () => { await this.account.call('DELETE', `/folders/${folderId}/members/${userId}`); await this.pullFeed(); });
  }

  deleteFolder(folderId: string): Promise<void> {
    return exclusive('matnotes-sync-' + this.store.name, async () => { await this.account.call('DELETE', `/folders/${folderId}`); await this.pullFeed(); });
  }
}
