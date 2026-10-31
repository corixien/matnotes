// End-to-end encryption, wire-compatible with app/.../sync/Crypto.kt and Identity.kt (same formats, labels and AADs).
// WebCrypto only: AES-256-GCM, PBKDF2-HMAC-SHA256, HMAC-SHA256, RSA-OAEP-3072 (SHA-256 / MGF1-SHA-256).
//
// Key hierarchy (the server never sees a password or a key):
// password --PBKDF2--> master --HMAC--> auth key (sent to the server) and enc key (wraps the private key)
// RSA private key --opens--> folder key --wraps--> document key --encrypts--> document (one key per version)

export const KDF_ITERATIONS = 600_000;
export const RSA_BITS = 3072;
const NONCE = 12;
const TAG_BYTES = 16;
const enc = new TextEncoder();
const dec = new TextDecoder('utf-8', { fatal: true });
const subtle = globalThis.crypto.subtle;

export type Bytes = Uint8Array<ArrayBuffer>;
/** Fresh ArrayBuffer-backed copy: WebCrypto's types do not accept views on a SharedArrayBuffer (the engine's FS hands out those). */
export const bytes = (b: ArrayLike<number>): Bytes => Uint8Array.from(b as ArrayLike<number>) as Bytes;

export class CryptoError extends Error {}

export function random(n: number): Bytes {
  const out = new Uint8Array(n);
  for (let i = 0; i < n; i += 65536) globalThis.crypto.getRandomValues(out.subarray(i, Math.min(n, i + 65536))); // 64 KiB per call is the API limit
  return out as Bytes;
}

export function b64(b: Uint8Array): string {
  let s = '';
  for (let i = 0; i < b.length; i += 0x8000) s += String.fromCharCode(...b.subarray(i, i + 0x8000));
  return btoa(s);
}
export function unb64(s: string): Bytes {
  const bin = atob(s);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out as Bytes;
}
export const sha256 = async (b: Uint8Array): Promise<Bytes> => new Uint8Array(await subtle.digest('SHA-256', bytes(b))) as Bytes;
export const concat = (...parts: Uint8Array[]): Bytes => {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let o = 0;
  for (const p of parts) { out.set(p, o); o += p.length; }
  return out as Bytes;
};

export async function hmac(key: Uint8Array, label: string): Promise<Bytes> {
  const k = await subtle.importKey('raw', bytes(key), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  return new Uint8Array(await subtle.sign('HMAC', k, enc.encode(label))) as Bytes;
}

/** Master key from the password (NFKC-normalised, UTF-8); the salt is public and per account. */
export async function deriveMaster(password: string, salt: Uint8Array, iterations: number): Promise<Bytes> {
  const k = await subtle.importKey('raw', enc.encode(password.normalize('NFKC')), 'PBKDF2', false, ['deriveBits']);
  return new Uint8Array(await subtle.deriveBits({ name: 'PBKDF2', hash: 'SHA-256', salt: bytes(salt), iterations }, k, 256)) as Bytes;
}

// ---- AES-GCM: output = nonce(12) || ciphertext || tag(16). The AAD binds a ciphertext to its place (folder, document, version). ----

export async function seal(key: Uint8Array, plain: Uint8Array, aad: string): Promise<Bytes> {
  const nonce = random(NONCE);
  const k = await subtle.importKey('raw', bytes(key), 'AES-GCM', false, ['encrypt']);
  const ct = new Uint8Array(await subtle.encrypt({ name: 'AES-GCM', iv: nonce, additionalData: enc.encode(aad), tagLength: 128 }, k, bytes(plain)));
  return concat(nonce, ct);
}

/** Throws CryptoError when the key, the AAD or the data is wrong or was tampered with. */
export async function open(key: Uint8Array, sealed: Uint8Array, aad: string): Promise<Bytes> {
  if (sealed.length <= NONCE + TAG_BYTES) throw new CryptoError('ciphertext too short');
  const k = await subtle.importKey('raw', bytes(key), 'AES-GCM', false, ['decrypt']);
  try {
    return new Uint8Array(await subtle.decrypt({ name: 'AES-GCM', iv: bytes(sealed.subarray(0, NONCE)), additionalData: enc.encode(aad), tagLength: 128 }, k, bytes(sealed.subarray(NONCE)))) as Bytes;
  } catch { throw new CryptoError('decryption failed'); }
}

// ---- RSA-OAEP ----

const RSA = { name: 'RSA-OAEP', hash: 'SHA-256' } as const;

export async function generateRsa(): Promise<{ publicDer: Bytes; privateDer: Bytes }> {
  const p = await subtle.generateKey({ ...RSA, modulusLength: RSA_BITS, publicExponent: new Uint8Array([1, 0, 1]) }, true, ['encrypt', 'decrypt']);
  return {
    publicDer: new Uint8Array(await subtle.exportKey('spki', p.publicKey)) as Bytes,
    privateDer: new Uint8Array(await subtle.exportKey('pkcs8', p.privateKey)) as Bytes,
  };
}
export async function rsaWrap(publicDer: Uint8Array, secret: Uint8Array): Promise<Bytes> {
  const k = await subtle.importKey('spki', bytes(publicDer), RSA, false, ['encrypt']);
  return new Uint8Array(await subtle.encrypt(RSA, k, bytes(secret))) as Bytes;
}
export async function rsaUnwrap(privateDer: Uint8Array, wrapped: Uint8Array): Promise<Bytes> {
  const k = await subtle.importKey('pkcs8', bytes(privateDer), RSA, false, ['decrypt']);
  try { return new Uint8Array(await subtle.decrypt(RSA, k, bytes(wrapped))) as Bytes; } catch { throw new CryptoError('unwrap failed'); }
}

/** Short code of a public key, shown to both people so they can compare it. */
export async function fingerprint(publicDer: Uint8Array): Promise<string> {
  const hex = [...(await sha256(publicDer)).subarray(0, 10)].map((x) => x.toString(16).padStart(2, '0').toUpperCase()).join('');
  return hex.match(/.{4}/g)!.join(' ');
}

// ---- recovery key: 32 random bytes shown once as Base32 groups of 4 ----

const B32 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';

export function encodeRecoveryKey(key: Uint8Array): string {
  let out = '', buffer = 0, bits = 0;
  for (const b of key) {
    buffer = (buffer << 8) | b; bits += 8;
    while (bits >= 5) { out += B32[(buffer >>> (bits - 5)) & 31]; bits -= 5; }
    buffer &= (1 << bits) - 1;
  }
  if (bits > 0) out += B32[(buffer << (5 - bits)) & 31];
  return out.match(/.{1,4}/g)!.join('-');
}

/** Null when the text is not a well-formed recovery key. Accepts lower case, spaces and missing dashes. */
export function decodeRecoveryKey(text: string): Bytes | null {
  const clean = [...text.toUpperCase()].filter((c) => B32.includes(c)).join('');
  if (clean.length !== 52) return null;
  const out: number[] = [];
  let buffer = 0, bits = 0;
  for (const ch of clean) {
    buffer = (buffer << 5) | B32.indexOf(ch); bits += 5;
    if (bits >= 8) { out.push((buffer >>> (bits - 8)) & 0xff); bits -= 8; buffer &= (1 << bits) - 1; }
  }
  return out.length === 32 ? (Uint8Array.from(out) as Bytes) : null;
}

// ================================================================== Identity

const AAD_PRIVATE = 'account-private-key';
const AAD_RECOVERY = 'account-private-key-recovery';

export type AccountSecrets = {
  kdfSalt: string; kdfIterations: number; authKey: string; publicKey: string; encPrivateKey: string;
  recoveryAuthKey: string; encPrivateKeyRecovery: string;
};
export type NewAccount = { secrets: AccountSecrets; privateKey: Bytes; publicKey: Bytes; recoveryKey: string };
export type LoginKeys = { authKey: string; enc: Bytes };

async function derive(password: string, salt: Uint8Array, iterations: number) {
  const master = await deriveMaster(password, salt, iterations);
  return { auth: await hmac(master, 'matnotes-auth-v1'), enc: await hmac(master, 'matnotes-enc-v1') };
}
async function recoveryDerived(recoveryKey: Uint8Array) {
  return { auth: await hmac(recoveryKey, 'matnotes-recovery-auth-v1'), enc: await hmac(recoveryKey, 'matnotes-recovery-enc-v1') };
}

export const Identity = {
  async create(password: string): Promise<NewAccount> {
    const salt = random(16);
    const d = await derive(password, salt, KDF_ITERATIONS);
    const rsa = await generateRsa();
    const recovery = random(32);
    const r = await recoveryDerived(recovery);
    return {
      secrets: {
        kdfSalt: b64(salt), kdfIterations: KDF_ITERATIONS, authKey: b64(d.auth), publicKey: b64(rsa.publicDer),
        encPrivateKey: b64(await seal(d.enc, rsa.privateDer, AAD_PRIVATE)),
        recoveryAuthKey: b64(r.auth),
        encPrivateKeyRecovery: b64(await seal(r.enc, rsa.privateDer, AAD_RECOVERY)),
      },
      privateKey: rsa.privateDer, publicKey: rsa.publicDer, recoveryKey: encodeRecoveryKey(recovery),
    };
  },

  /** Both keys of one password derivation (the slow step runs once per login). */
  async loginKeys(password: string, saltB64: string, iterations: number): Promise<LoginKeys> {
    const d = await derive(password, unb64(saltB64), iterations);
    return { authKey: b64(d.auth), enc: d.enc };
  },

  /** The account private key; throws CryptoError when the password is wrong. */
  unlock: (keys: LoginKeys, encPrivateKeyB64: string) => open(keys.enc, unb64(encPrivateKeyB64), AAD_PRIVATE),

  /** New salt, new keys, same private key: for a password change and for recovery. */
  async rewrap(newPassword: string, privateKey: Uint8Array) {
    const salt = random(16);
    const d = await derive(newPassword, salt, KDF_ITERATIONS);
    return { kdfSalt: b64(salt), kdfIterations: KDF_ITERATIONS, authKey: b64(d.auth), encPrivateKey: b64(await seal(d.enc, privateKey, AAD_PRIVATE)) };
  },

  async recoveryAuthKey(text: string): Promise<string | null> {
    const k = decodeRecoveryKey(text);
    return k ? b64((await recoveryDerived(k)).auth) : null;
  },

  /** The private key from the recovery key; null for a malformed key, throws when it is the wrong key. */
  async unlockWithRecovery(text: string, encPrivateKeyRecoveryB64: string): Promise<Bytes | null> {
    const k = decodeRecoveryKey(text);
    if (!k) return null;
    return open((await recoveryDerived(k)).enc, unb64(encPrivateKeyRecoveryB64), AAD_RECOVERY);
  },
};

// ================================================================== Vault: folder and document encryption

const utf8 = (s: string) => enc.encode(s);

export const Vault = {
  newFolderKey: () => random(32),
  wrapFolderKey: async (memberPublicDer: Uint8Array, folderKey: Uint8Array) => b64(await rsaWrap(memberPublicDer, folderKey)),
  unwrapFolderKey: (privateDer: Uint8Array, wrappedB64: string) => rsaUnwrap(privateDer, unb64(wrappedB64)),

  sealFolderName: async (fk: Uint8Array, folderId: string, name: string) => b64(await seal(fk, utf8(name), `folder-name|${folderId}`)),
  openFolderName: async (fk: Uint8Array, folderId: string, sealedB64: string) => dec.decode(await open(fk, unb64(sealedB64), `folder-name|${folderId}`)),
  sealDocName: async (fk: Uint8Array, docId: string, name: string) => b64(await seal(fk, utf8(name), `doc-name|${docId}`)),
  openDocName: async (fk: Uint8Array, docId: string, sealedB64: string) => dec.decode(await open(fk, unb64(sealedB64), `doc-name|${docId}`)),

  /** One fresh document key per version; the key travels wrapped with the folder key. */
  async sealDoc(fk: Uint8Array, docId: string, version: number, plain: Uint8Array): Promise<{ data: Bytes; wrappedKey: string }> {
    const docKey = random(32);
    return { data: await seal(docKey, plain, `doc|${docId}|${version}`), wrappedKey: b64(await seal(fk, docKey, `doc-key|${docId}|${version}`)) };
  },
  async openDoc(fk: Uint8Array, docId: string, version: number, wrappedKeyB64: string, sealed: Uint8Array): Promise<Bytes> {
    const docKey = await open(fk, unb64(wrappedKeyB64), `doc-key|${docId}|${version}`);
    return open(docKey, sealed, `doc|${docId}|${version}`);
  },
};
