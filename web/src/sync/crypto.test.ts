import { beforeAll, describe, expect, it } from 'vitest';
import { b64, CryptoError, decodeRecoveryKey, encodeRecoveryKey, fingerprint, generateRsa, Identity, open, random, rsaUnwrap, rsaWrap, seal, unb64, Vault, type NewAccount } from './crypto';

const rejects = async (p: Promise<unknown>) => { await expect(p).rejects.toBeInstanceOf(CryptoError); };
let account: NewAccount;
beforeAll(async () => { account = await Identity.create('correct horse battery staple'); }, 30000);

describe('AES-GCM', () => {
  it('round trips and rejects tampering, wrong key and wrong AAD', async () => {
    const key = random(32);
    const sealed = await seal(key, new TextEncoder().encode('hello'), 'ctx');
    expect(new TextDecoder().decode(await open(key, sealed, 'ctx'))).toBe('hello');
    await rejects(open(key, sealed, 'other'));
    await rejects(open(random(32), sealed, 'ctx'));
    const flipped = sealed.slice(); flipped[20] ^= 1;
    await rejects(open(key, flipped, 'ctx'));
    expect(b64(sealed)).not.toBe(b64(await seal(key, new TextEncoder().encode('hello'), 'ctx')));
    await rejects(open(key, sealed.subarray(0, 20), 'ctx'));
  });
});

describe('RSA-OAEP', () => {
  it('wraps and unwraps, other key fails', async () => {
    const secret = random(32);
    const wrapped = await rsaWrap(account.publicKey, secret);
    expect(wrapped.length).toBe(384);
    expect(await rsaUnwrap(account.privateKey, wrapped)).toEqual(secret);
    await rejects(rsaUnwrap((await generateRsa()).privateDer, wrapped));
  });
});

describe('Identity', () => {
  it('password unlocks, wrong password fails', async () => {
    const s = account.secrets;
    const keys = await Identity.loginKeys('correct horse battery staple', s.kdfSalt, s.kdfIterations);
    expect(keys.authKey).toBe(s.authKey);
    expect(await Identity.unlock(keys, s.encPrivateKey)).toEqual(account.privateKey);
    const bad = await Identity.loginKeys('wrong', s.kdfSalt, s.kdfIterations);
    expect(bad.authKey).not.toBe(s.authKey);
    await rejects(Identity.unlock(bad, s.encPrivateKey));
  }, 30000);

  it('password is NFKC-normalised', async () => {
    const salt = b64(random(16));
    const a = await Identity.loginKeys('café', salt, 1000);
    const b = await Identity.loginKeys('café', salt, 1000);
    expect(a.authKey).toBe(b.authKey);
  });

  it('recovery key unlocks and the password can be reset', async () => {
    const s = account.secrets;
    const pk = (await Identity.unlockWithRecovery(account.recoveryKey, s.encPrivateKeyRecovery))!;
    expect(pk).toEqual(account.privateKey);
    expect(await Identity.recoveryAuthKey(account.recoveryKey)).toBe(s.recoveryAuthKey);
    expect(await Identity.unlockWithRecovery('not a key', s.encPrivateKeyRecovery)).toBeNull();
    await rejects(Identity.unlockWithRecovery(encodeRecoveryKey(random(32)), s.encPrivateKeyRecovery) as Promise<unknown>);
    const r = await Identity.rewrap('new password', pk);
    const keys = await Identity.loginKeys('new password', r.kdfSalt, r.kdfIterations);
    expect(keys.authKey).toBe(r.authKey);
    expect(await Identity.unlock(keys, r.encPrivateKey)).toEqual(account.privateKey);
  }, 30000);

  it('recovery key text format', () => {
    const key = random(32);
    const text = encodeRecoveryKey(key);
    expect(text.length).toBe(64);
    expect(text.split('-').length).toBe(13);
    expect(decodeRecoveryKey(text)).toEqual(key);
    expect(decodeRecoveryKey(text.toLowerCase().replaceAll('-', ' '))).toEqual(key);
    expect(decodeRecoveryKey(text.slice(0, -4))).toBeNull();
  });
});

describe('Vault', () => {
  it('names and documents are bound to their ids and versions', async () => {
    const fk = Vault.newFolderKey();
    const wrapped = await Vault.wrapFolderKey(account.publicKey, fk);
    expect(await Vault.unwrapFolderKey(account.privateKey, wrapped)).toEqual(fk);
    expect(await Vault.openFolderName(fk, 'f1', await Vault.sealFolderName(fk, 'f1', 'Schule'))).toBe('Schule');
    await rejects(Vault.openFolderName(fk, 'f2', await Vault.sealFolderName(fk, 'f1', 'Schule')));
    expect(await Vault.openDocName(fk, 'd1', await Vault.sealDocName(fk, 'd1', 'Bericht äöü.docx'))).toBe('Bericht äöü.docx');
    const plain = random(100_000);
    const s = await Vault.sealDoc(fk, 'd1', 3, plain);
    expect(await Vault.openDoc(fk, 'd1', 3, s.wrappedKey, s.data)).toEqual(plain);
    await rejects(Vault.openDoc(fk, 'd1', 4, s.wrappedKey, s.data)); // version swap
    await rejects(Vault.openDoc(fk, 'd2', 3, s.wrappedKey, s.data)); // document swap
    expect(s.data.length).toBe(plain.length + 12 + 16);
  });

  it('fingerprint format', async () => {
    expect(await fingerprint(account.publicKey)).toMatch(/^([0-9A-F]{4} ){4}[0-9A-F]{4}$/);
  });
});

it('base64 round trip for large and binary data', () => {
  const x = random(70000);
  expect(unb64(b64(x))).toEqual(x);
});
