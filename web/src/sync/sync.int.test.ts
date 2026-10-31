// Web client against a real server (opt-in): MATNOTES_TEST_SERVER=http://127.0.0.1:3000/api npm test
// Two "devices" = two isolated IndexedDB databases, each with its own Account and SyncEngine.
import 'fake-indexeddb/auto';
import { describe, expect, it } from 'vitest';
import { renameFile } from '../rename';
import { Store } from '../store';
import { Account } from './account';
import { ApiException, SyncApi } from './api';
import { SyncEngine } from './engine';
import { Vault, unb64, b64, Identity } from './crypto';

const base = process.env.MATNOTES_TEST_SERVER;
const bytesOf = (s: string) => new TextEncoder().encode(s);
const text = async (st: Store, dir: string, name: string) => { const f = await st.files.get(dir, name); return f ? new TextDecoder().decode(f.bytes) : null; };
const uid = () => crypto.randomUUID().slice(0, 8);

class Device {
  store = new Store('dev-' + uid());
  account = new Account(this.store, new SyncApi(base!));
  open = new Set<string>();
  engine = new SyncEngine(this.store, this.account, (dir, name) => this.open.has(`${dir}/${name}`));
  signedOut = 0;
  constructor() { this.account.onSignedOut = () => { this.signedOut++; return this.engine.reset(); }; }
  /** Local edit: new content, new timestamp. */
  edit(dir: string, name: string, content: string) { return this.store.files.put(dir, name, bytesOf(content), Date.now() + Math.floor(Math.random() * 1000) + 1); }
  sync() { return this.engine.sync(); }
}

const email = (n: string) => `${n}-${uid()}@example.com`;

describe.skipIf(!base)('web sync against a real server', () => {
  it('register, personal folder, upload, second device downloads, edit, delete', async () => {
    const a = new Device(), b = new Device();
    const mail = email('anna');
    const recovery = await a.account.register('Anna', mail, 'pässwörd-12345');
    expect(recovery).toMatch(/^([A-Z2-7]{4}-){12}[A-Z2-7]{4}$/);
    expect(a.account.loggedIn).toBe(true);

    await a.edit('', 'Notiz.docx', 'eins');
    let r = await a.sync();
    expect(r.errors).toEqual([]);
    expect(r.uploaded).toBe(1);
    expect(a.engine.folders.some((f) => f.personal)).toBe(true);

    await b.account.login(mail, 'pässwörd-12345');
    r = await b.sync();
    expect(r.errors).toEqual([]);
    expect(r.downloaded).toBe(1);
    expect(await text(b.store, '', 'Notiz.docx')).toBe('eins');

    // unchanged: nothing happens
    r = await b.sync();
    expect([r.uploaded, r.downloaded, r.conflicts, r.deleted]).toEqual([0, 0, 0, 0]);

    // edit on b -> push, a pulls
    await b.edit('', 'Notiz.docx', 'zwei');
    r = await b.sync();
    expect(r.uploaded).toBe(1);
    r = await a.sync();
    expect(r.downloaded).toBe(1);
    expect(await text(a.store, '', 'Notiz.docx')).toBe('zwei');

    // delete on a -> b deletes too
    await a.store.files.remove('', 'Notiz.docx');
    r = await a.sync();
    expect(r.deleted).toBe(1);
    r = await b.sync();
    expect(r.deleted).toBe(1);
    expect(await text(b.store, '', 'Notiz.docx')).toBeNull();
  }, 120000);

  it('conflict: cloud wins at the original name, the local version survives as a copy and syncs', async () => {
    const a = new Device(), b = new Device();
    const mail = email('conf');
    await a.account.register('Conf', mail, 'pässwörd-12345');
    await a.edit('', 'Plan.docx', 'start');
    await a.sync();
    await b.account.login(mail, 'pässwörd-12345');
    await b.sync();

    await a.edit('', 'Plan.docx', 'von A');
    await b.edit('', 'Plan.docx', 'von B');
    expect((await a.sync()).uploaded).toBe(1);
    const r = await b.sync();
    expect(r.conflicts).toBe(1);
    expect(await text(b.store, '', 'Plan.docx')).toBe('von A');
    const names = (await b.store.files.inDir('')).map((f) => f.name);
    const copy = names.find((n) => n.startsWith('Plan (Konflikt'));
    expect(copy, names.join(',')).toBeTruthy();
    expect(await text(b.store, '', copy!)).toBe('von B');
    // the copy was uploaded in the second pass and reaches device a
    await a.sync();
    expect(await text(a.store, '', copy!)).toBe('von B');
  }, 120000);

  it('a renamed document stays the same cloud document and arrives under its new name', async () => {
    const a = new Device(), b = new Device();
    const mail = email('ren');
    await a.account.register('Ren', mail, 'pässwörd-12345');
    await a.edit('', 'Alt.docx', 'inhalt');
    await a.sync();
    await b.account.login(mail, 'pässwörd-12345');
    await b.sync();
    expect(await text(b.store, '', 'Alt.docx')).toBe('inhalt');
    expect(await renameFile(a.store, '', 'Alt.docx', 'Neu.docx')).toBe(true);
    const r = await a.sync();
    expect(r.errors).toEqual([]);
    expect([r.uploaded, r.deleted]).toEqual([1, 0]); // a new version of the same document, not a delete + new upload
    await b.sync();
    expect((await b.store.files.inDir('')).map((f) => f.name)).toEqual(['Neu.docx']);
    expect(await text(b.store, '', 'Neu.docx')).toBe('inhalt');
  }, 120000);

  it('an open document is not replaced', async () => {
    const a = new Device(), b = new Device();
    const mail = email('open');
    await a.account.register('Open', mail, 'pässwörd-12345');
    await a.edit('', 'Doc.docx', 'v1');
    await a.sync();
    await b.account.login(mail, 'pässwörd-12345');
    await b.sync();
    await a.edit('', 'Doc.docx', 'v2');
    await a.sync();
    b.open.add('/Doc.docx');
    let r = await b.sync();
    expect(r.downloaded).toBe(0);
    expect(await text(b.store, '', 'Doc.docx')).toBe('v1');
    b.open.clear();
    r = await b.sync();
    expect(r.downloaded).toBe(1);
    expect(await text(b.store, '', 'Doc.docx')).toBe('v2');
  }, 120000);

  it('shared folder: invite with key fingerprint, accept, two-way sync, leave', async () => {
    const a = new Device(), b = new Device();
    const mailB = email('ben');
    await a.account.register('Anna', email('anna2'), 'pässwörd-12345');
    await b.account.register('Ben', mailB, 'pässwörd-12345');

    const id = await a.engine.createSharedFolder('Schule äöü');
    await a.engine.sync();
    const folderA = a.engine.folders.find((f) => f.id === id)!;
    expect(folderA.name).toBe('Schule äöü');
    expect(folderA.localDir).toBe('Schule äöü');
    await a.edit('Schule äöü', 'Bericht.docx', 'inhalt');
    await a.sync();

    const ben = await a.engine.lookupUser(mailB);
    expect(ben.fingerprint).toMatch(/^([0-9A-F]{4} ){4}[0-9A-F]{4}$/);
    expect(ben.fingerprint).toBe((await b.engine.lookupUser(mailB)).fingerprint); // same key from either side
    await a.engine.invite(id, ben);

    await b.sync();
    const inv = b.engine.folders.find((f) => f.id === id)!;
    expect(inv.status).toBe('invited');
    expect(inv.name).toBe('Schule äöü'); // the folder key came with the invitation
    await b.engine.answerInvite(id, true);
    const r = await b.sync();
    expect(r.errors).toEqual([]);
    expect(await text(b.store, 'Schule äöü', 'Bericht.docx')).toBe('inhalt');
    expect(await b.store.files.get('', 'Bericht.docx')).toBeUndefined(); // not mixed into the personal folder

    await b.edit('Schule äöü', 'Bericht.docx', 'von Ben');
    await b.sync();
    await a.sync();
    expect(await text(a.store, 'Schule äöü', 'Bericht.docx')).toBe('von Ben');

    // an outsider cannot read the folder
    const c = new Device();
    await c.account.register('Cleo', email('cleo'), 'pässwörd-12345');
    await expect(c.account.call('GET', `/folders/${id}/docs`)).rejects.toBeInstanceOf(ApiException);

    // Ben leaves: stops receiving
    await b.engine.removeMember(id, b.account.userId!);
    await a.edit('Schule äöü', 'Bericht.docx', 'nach dem Austritt');
    await a.sync();
    await b.sync();
    expect(await text(b.store, 'Schule äöü', 'Bericht.docx')).toBe('von Ben');
  }, 180000);

  it('declined and removed invitations', async () => {
    const a = new Device(), b = new Device();
    const mailB = email('dec');
    await a.account.register('Anna', email('anna3'), 'pässwörd-12345');
    await b.account.register('Dec', mailB, 'pässwörd-12345');
    const id = await a.engine.createSharedFolder('Privat');
    await a.engine.invite(id, await a.engine.lookupUser(mailB));
    await b.sync();
    await b.engine.answerInvite(id, false);
    expect(b.engine.folders.find((f) => f.id === id)?.status).not.toBe('active');
    await expect(a.engine.lookupUser('nobody-' + uid() + '@example.com')).rejects.toMatchObject({ status: 404 });
  }, 120000);

  it('password change signs other devices out; recovery key restores access', async () => {
    const a = new Device(), b = new Device();
    const mail = email('pw');
    const recovery = await a.account.register('Pw', mail, 'pässwörd-12345');
    await b.account.login(mail, 'pässwörd-12345');
    await a.account.changePassword('pässwörd-12345', 'neues-pässwörd-67890');
    // the short-lived access token still works until it expires; the refresh token is what the password change revoked
    await b.store.kv.set('account', { ...(await b.store.kv.get<any>('account')), accessExp: 0 });
    await expect(b.sync().then((r) => { if (r.errors.length) throw new Error(r.errors[0]); })).rejects.toThrow();
    expect(b.account.loggedIn).toBe(false);
    expect(b.signedOut).toBe(1);
    await expect(b.account.login(mail, 'pässwörd-12345')).rejects.toBeInstanceOf(ApiException);
    await b.account.login(mail, 'neues-pässwörd-67890');
    expect(b.account.loggedIn).toBe(true);

    // forgotten password
    const c = new Device();
    await c.account.recover(mail, recovery, 'dritt-pässwörd-1');
    expect(c.account.loggedIn).toBe(true);
    expect(await c.account.privateKey()).toEqual(await a.account.privateKey());
    await expect(c.account.recover(mail, 'AAAA-AAAA-AAAA-AAAA-AAAA-AAAA-AAAA-AAAA-AAAA-AAAA-AAAA-AAAA-AAAA', 'x-yyyyyyyy')).rejects.toBeTruthy();
  }, 120000);

  it('wrong password, session survives reload (state restored from storage), logout and account deletion', async () => {
    const a = new Device();
    const mail = email('life');
    await a.account.register('Life', mail, 'pässwörd-12345');
    await a.edit('', 'x.docx', 'data');
    await a.sync();
    // "reload": a new Account on the same store restores the session
    const again = new Account(a.store, new SyncApi(base!));
    await again.init();
    expect(again.loggedIn).toBe(true);
    expect(await again.privateKey()).toEqual(await a.account.privateKey());
    expect((await again.call('GET', '/auth/me')).user.email).toBe(mail);

    await expect(new Device().account.login(mail, 'falsch')).rejects.toMatchObject({ status: 401 });
    await a.account.deleteAccount('pässwörd-12345');
    expect(a.account.loggedIn).toBe(false);
    await expect(new Device().account.login(mail, 'pässwörd-12345')).rejects.toBeInstanceOf(ApiException);
  }, 120000);

  it('the server only ever sees ciphertext and no names', async () => {
    const a = new Device();
    await a.account.register('Spy', email('spy'), 'pässwörd-12345');
    await a.edit('', 'Geheim-Dokument.docx', 'TOP SECRET CONTENT TOP SECRET CONTENT');
    await a.sync();
    const feed = await a.account.call('GET', '/sync/changes?since=0');
    const doc = feed.docs[0];
    expect(JSON.stringify(feed)).not.toContain('Geheim');
    const blob = await a.account.bytes('GET', `/folders/${doc.folderId}/docs/${doc.id}/blob`);
    expect(new TextDecoder('latin1').decode(blob.body)).not.toContain('SECRET');
    // opening needs the folder key: a different account's key cannot open it
    const stranger = await Identity.create('whatever-password-1');
    await expect(Vault.unwrapFolderKey(stranger.privateKey, feed.folders[0].wrappedKey)).rejects.toBeTruthy();
    expect(unb64(b64(blob.body)).length).toBe(blob.body.length);
  }, 120000);
});
