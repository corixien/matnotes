// Cross-implementation vectors against the Kotlin app (see app/.../WebVectorsTest.kt).
// kotlin.json: made by the app, must open here. web.json: made here (WRITE_WEB_VECTORS=1), must open in the app.
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { b64, fingerprint, Identity, unb64, Vault } from './crypto';

const dir = new URL('../../test-vectors/', import.meta.url);
const kotlin = new URL('kotlin.json', dir);

describe('Kotlin-made data opens in the web client', () => {
  it.skipIf(!existsSync(kotlin))('account, recovery, folder and document', async () => {
    const o = JSON.parse(readFileSync(kotlin, 'utf8'));
    const s = o.secrets;
    const keys = await Identity.loginKeys(o.password, s.kdfSalt, s.kdfIterations);
    expect(keys.authKey).toBe(s.authKey);
    const priv = await Identity.unlock(keys, s.encPrivateKey);
    expect(b64(priv)).toBe(o.privateKey);
    expect(b64((await Identity.unlockWithRecovery(o.recoveryKey, s.encPrivateKeyRecovery))!)).toBe(o.privateKey);
    expect(await Identity.recoveryAuthKey(o.recoveryKey)).toBe(s.recoveryAuthKey);
    expect(await fingerprint(unb64(s.publicKey))).toBe(o.fingerprint);
    const fk = await Vault.unwrapFolderKey(priv, o.wrappedFolderKey);
    expect(b64(fk)).toBe(o.folderKey);
    expect(await Vault.openFolderName(fk, o.folderId, o.sealedFolderName)).toBe(o.folderName);
    expect(await Vault.openDocName(fk, o.docId, o.sealedDocName)).toBe(o.docName);
    expect(b64(await Vault.openDoc(fk, o.docId, o.version, o.wrappedDocKey, unb64(o.sealedDoc)))).toBe(o.plain);
  }, 30000);
});

describe('web-made data (opened by the Kotlin test)', () => {
  it.runIf(process.env.WRITE_WEB_VECTORS === '1')('writes web.json', async () => {
    const password = 'pässwörd ✓ 12345';
    const acc = await Identity.create(password);
    const folderId = '3b1f2a64-0000-4000-8000-000000000011', docId = '3b1f2a64-0000-4000-8000-000000000012';
    const fk = Vault.newFolderKey();
    const plain = crypto.getRandomValues(new Uint8Array(3000));
    const sealed = await Vault.sealDoc(fk, docId, 4, plain);
    const out = {
      password, secrets: acc.secrets, privateKey: b64(acc.privateKey), recoveryKey: acc.recoveryKey, fingerprint: await fingerprint(acc.publicKey),
      folderId, docId, folderKey: b64(fk), wrappedFolderKey: await Vault.wrapFolderKey(acc.publicKey, fk),
      folderName: 'Schule äöü', sealedFolderName: await Vault.sealFolderName(fk, folderId, 'Schule äöü'),
      docName: 'Bericht ✓.docx', sealedDocName: await Vault.sealDocName(fk, docId, 'Bericht ✓.docx'),
      version: 4, plain: b64(plain), sealedDoc: b64(sealed.data), wrappedDocKey: sealed.wrappedKey,
    };
    mkdirSync(dir, { recursive: true });
    writeFileSync(new URL('web.json', dir), JSON.stringify(out, null, 1));
  }, 30000);
});
