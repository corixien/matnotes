// The signed-in account: tokens, name and keys (port of Account.kt). The refresh token and the account private key are stored
// sealed with a non-extractable AES-GCM key that lives in IndexedDB next to them: a copied database file is useless without the
// browser profile's key store, but script running on our own origin could still use them (documented limit, see web-plan.md).
// The password is never stored. Server calls that need a login go through `call` / `bytes`, which refresh the access token.
import type { Store } from '../store';
import { ApiException, type ApiResponse, type SyncApi } from './api';
import { b64, bytes, type Bytes, Identity, random, unb64 } from './crypto';
import { exclusive } from './lock';

export type AccountState = { userId: string; name: string; email: string } | null;
type Json = Record<string, any>;

type Saved = { userId: string; name: string; email: string; publicKey: string; privateKey: string; refresh: string; access: string; accessExp: number };
const KEY = 'account';
const SEAL_KEY = 'account-seal-key';

export class Account {
  private saved: Saved | null = null;
  private sealKey: CryptoKey | null = null;
  private cachedPrivate: Bytes | null = null;
  private listeners = new Set<(s: AccountState) => void>();
  /** Set by the sync layer: wipes its local state (folder keys, cursor, links) when the account goes away. */
  onSignedOut: (() => void | Promise<void>) | null = null;

  constructor(private store: Store, readonly api: SyncApi, private lockName = 'matnotes-' + store.name) {}

  // ---- state ----

  get state(): AccountState { return this.saved ? { userId: this.saved.userId, name: this.saved.name, email: this.saved.email } : null; }
  get loggedIn() { return this.saved !== null; }
  get userId() { return this.saved?.userId ?? null; }
  get publicKey(): Bytes | null { return this.saved ? unb64(this.saved.publicKey) : null; }
  subscribe(f: (s: AccountState) => void) { this.listeners.add(f); return () => this.listeners.delete(f); }
  private emit() { const s = this.state; this.listeners.forEach((l) => l(s)); }

  async init() {
    this.saved = (await this.store.kv.get<Saved>(KEY)) ?? null;
    this.emit();
  }

  /** The account private key (DER), unsealed from storage; null when signed out or unreadable. */
  async privateKey(): Promise<Bytes | null> {
    if (this.cachedPrivate) return this.cachedPrivate;
    if (!this.saved) return null;
    try { return (this.cachedPrivate = await this.unseal(this.saved.privateKey)); } catch { return null; }
  }

  // ---- sealing at rest ----

  private async key(): Promise<CryptoKey> {
    if (this.sealKey) return this.sealKey;
    let k = await this.store.kv.get<CryptoKey>(SEAL_KEY);
    if (!k) {
      k = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
      try { await this.store.kv.set(SEAL_KEY, k); } catch { /* cannot persist the key here: the session works, a reload signs out */ }
    }
    return (this.sealKey = k);
  }
  private async seal(plain: Uint8Array): Promise<string> {
    const nonce = random(12);
    const ct = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv: nonce }, await this.key(), bytes(plain)));
    return b64(new Uint8Array([...nonce, ...ct]));
  }
  private async unseal(s: string): Promise<Bytes> {
    const all = unb64(s);
    return new Uint8Array(await crypto.subtle.decrypt({ name: 'AES-GCM', iv: all.subarray(0, 12) }, await this.key(), all.subarray(12))) as Bytes;
  }

  private async persist() { if (this.saved) await this.store.kv.set(KEY, this.saved); }

  private async storeSession(session: Json, user: Json, privateKey: Bytes) {
    this.cachedPrivate = privateKey;
    this.saved = {
      userId: user.id, name: user.name, email: user.email, publicKey: user.publicKey,
      privateKey: await this.seal(privateKey),
      refresh: await this.seal(new TextEncoder().encode(session.refreshToken)),
      access: session.accessToken, accessExp: Date.now() + session.expiresIn * 1000,
    };
    await this.persist();
    this.emit();
  }
  private async storeTokens(t: Json) {
    if (!this.saved) return;
    this.saved = { ...this.saved, refresh: await this.seal(new TextEncoder().encode(t.refreshToken)), access: t.accessToken, accessExp: Date.now() + t.expiresIn * 1000 };
    await this.persist();
  }

  private deviceName() { return 'Web (' + (/Firefox/.test(navigator.userAgent) ? 'Firefox' : /Edg\//.test(navigator.userAgent) ? 'Edge' : /Chrome/.test(navigator.userAgent) ? 'Chrome' : /Safari/.test(navigator.userAgent) ? 'Safari' : 'Browser') + ')'; }

  // ---- flows ----

  /** Creates the account and signs in. Returns the recovery key; it is shown once and is the only way back in after a forgotten password. */
  async register(name: string, email: string, password: string): Promise<string> {
    const acc = await Identity.create(password);
    const s = acc.secrets;
    const r = await this.api.json('POST', '/auth/register', {
      name: name.trim(), email: email.trim(), deviceName: this.deviceName(),
      authKey: s.authKey, kdfSalt: s.kdfSalt, kdfIterations: s.kdfIterations, publicKey: s.publicKey, encPrivateKey: s.encPrivateKey,
      recoveryAuthKey: s.recoveryAuthKey, encPrivateKeyRecovery: s.encPrivateKeyRecovery,
    });
    await this.storeSession(r, r.user, acc.privateKey);
    return acc.recoveryKey;
  }

  async login(email: string, password: string) {
    const params = await this.api.json('POST', '/auth/params', { email: email.trim() });
    const keys = await Identity.loginKeys(password, params.salt, params.iterations);
    const r = await this.api.json('POST', '/auth/login', { email: email.trim(), authKey: keys.authKey, deviceName: this.deviceName() });
    const priv = await Identity.unlock(keys, r.user.encPrivateKey);
    await this.storeSession(r, r.user, priv);
  }

  /** Forgotten password: the recovery key opens the private key, a new password re-wraps it. Throws CryptoError for a wrong key. */
  async recover(email: string, recoveryKeyText: string, newPassword: string) {
    const start = await this.api.json('POST', '/auth/recover/start', { email: email.trim() });
    const proof = await Identity.recoveryAuthKey(recoveryKeyText);
    const priv = proof ? await Identity.unlockWithRecovery(recoveryKeyText, start.encPrivateKeyRecovery) : null;
    if (!proof || !priv) throw new RecoveryKeyError('malformed recovery key');
    const w = await Identity.rewrap(newPassword, priv);
    const t = await this.api.json('POST', '/auth/recover/finish', {
      email: email.trim(), recoveryAuthKey: proof, deviceName: this.deviceName(),
      newAuthKey: w.authKey, newKdfSalt: w.kdfSalt, newKdfIterations: w.kdfIterations, newEncPrivateKey: w.encPrivateKey,
    });
    const me = (await this.api.json('GET', '/auth/me', null, t.accessToken)).user;
    await this.storeSession(t, me, priv);
  }

  /** Changes the password; the other devices have to sign in again. */
  async changePassword(currentPassword: string, newPassword: string) {
    const me = (await this.call('GET', '/auth/me')).user;
    const cur = (await Identity.loginKeys(currentPassword, me.kdfSalt, me.kdfIterations)).authKey;
    const priv = await this.privateKey();
    if (!priv) throw new Error('not signed in');
    const w = await Identity.rewrap(newPassword, priv);
    const t = await this.call('POST', '/auth/password', {
      currentAuthKey: cur, deviceName: this.deviceName(), newAuthKey: w.authKey, newKdfSalt: w.kdfSalt, newKdfIterations: w.kdfIterations, newEncPrivateKey: w.encPrivateKey,
    });
    await this.storeTokens(t);
  }

  async deleteAccount(password: string) {
    const me = (await this.call('GET', '/auth/me')).user;
    const key = (await Identity.loginKeys(password, me.kdfSalt, me.kdfIterations)).authKey;
    await this.call('POST', '/auth/delete', { authKey: key });
    await this.clearLocal();
  }

  async logout() {
    if (this.saved) {
      try { const refresh = new TextDecoder().decode(await this.unseal(this.saved.refresh)); await this.api.json('POST', '/auth/logout', { refreshToken: refresh }); } catch { /* offline: the session simply expires */ }
    }
    await this.clearLocal();
  }

  private async clearLocal() {
    this.cachedPrivate = null;
    this.saved = null;
    await this.store.clearAccount();
    this.sealKey = null; // a fresh seal key is generated for the next account
    this.emit();
    await this.onSignedOut?.();
  }

  // ---- authorized calls ----

  private accessToken(force = false): Promise<string> {
    return exclusive(this.lockName + '-refresh', async () => {
      // another tab may have refreshed (the refresh token rotates): read the stored state inside the lock
      const stored = await this.store.kv.get<Saved>(KEY);
      if (stored) this.saved = stored;
      if (!this.saved) throw new ApiException(401, 'unauthorized', 'Not signed in');
      if (!force && this.saved.accessExp - Date.now() > 60_000) return this.saved.access;
      const refresh = new TextDecoder().decode(await this.unseal(this.saved.refresh));
      try {
        const t = await this.api.json('POST', '/auth/refresh', { refreshToken: refresh });
        await this.storeTokens(t);
        return t.accessToken as string;
      } catch (e) {
        if (e instanceof ApiException && e.status === 401) await this.clearLocal(); // session revoked (password changed elsewhere, removed)
        throw e;
      }
    });
  }

  async call(method: string, path: string, body?: Json | null, headers: Record<string, string> = {}): Promise<Json> {
    try { return await this.api.json(method, path, body, await this.accessToken(), headers); } catch (e) { if (!(e instanceof ApiException) || e.status !== 401) throw e; }
    return this.api.json(method, path, body, await this.accessToken(true), headers);
  }

  async bytes(method: string, path: string, headers: Record<string, string> = {}, body?: Uint8Array | null): Promise<ApiResponse> {
    try { return await this.api.bytes(method, path, await this.accessToken(), headers, body); } catch (e) { if (!(e instanceof ApiException) || e.status !== 401) throw e; }
    return this.api.bytes(method, path, await this.accessToken(true), headers, body);
  }
}

export class RecoveryKeyError extends Error {}
