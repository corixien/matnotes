// Pins the ZetaOffice engine next to the app: public/engine/<version>/<file>.br (the CDN's own brotli payload, byte for byte) so
// the server can hand it out with a one-year immutable cache instead of the CDN's 20 minutes, same origin, no CORS.
// <version> = hash of the files, so a new engine means a new URL. Without this step the app falls back to the CDN.
import { createHash } from 'node:crypto';
import { mkdirSync, readdirSync, renameSync, rmSync, writeFileSync, createWriteStream, existsSync } from 'node:fs';
import { get } from 'node:https';
import { join } from 'node:path';
import { brotliCompressSync, constants } from 'node:zlib';
import { readFileSync } from 'node:fs';

const BASE = process.env.ENGINE_SOURCE || 'https://cdn.zetaoffice.net/zetaoffice_latest/';
const FILES = ['soffice.js', 'soffice.wasm', 'soffice.data', 'soffice.data.js.metadata'];
const root = new URL('../public/engine/', import.meta.url).pathname;
mkdirSync(root, { recursive: true });
const tmp = join(root, '.tmp');
rmSync(tmp, { recursive: true, force: true });
mkdirSync(tmp);

function download(name) {
  return new Promise((resolve, reject) => {
    get(BASE + name, { headers: { 'accept-encoding': 'br' } }, (res) => {
      if (res.statusCode !== 200) return reject(new Error(`${name}: HTTP ${res.statusCode}`));
      const out = join(tmp, name + '.raw');
      res.pipe(createWriteStream(out)).on('finish', () => resolve({ out, encoding: res.headers['content-encoding'] || 'identity' })).on('error', reject);
    }).on('error', reject);
  });
}

const hash = createHash('sha256');
for (const f of FILES) {
  const { out, encoding } = await download(f);
  let data = readFileSync(out);
  if (encoding !== 'br') data = brotliCompressSync(data, { params: { [constants.BROTLI_PARAM_QUALITY]: 9 } }); // identity answer: compress ourselves
  writeFileSync(join(tmp, f + '.br'), data);
  rmSync(out);
  hash.update(f).update(data);
  console.log(f, (data.length / 1048576).toFixed(1), 'MB (br)');
}
const version = hash.digest('hex').slice(0, 10);
for (const d of readdirSync(root)) if (d !== '.tmp' && d !== version) rmSync(join(root, d), { recursive: true, force: true });
if (!existsSync(join(root, version))) renameSync(tmp, join(root, version)); else rmSync(tmp, { recursive: true, force: true });
writeFileSync(join(root, 'version.txt'), version);
console.log('engine pinned as', version);
