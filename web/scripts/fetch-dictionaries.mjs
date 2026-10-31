// Hunspell dictionaries for the web spell check: public/dict/<code>.aff|.dic (gitignored, 145 MB unpacked, loaded one language at a
// time by the browser). Reuses the app's copy (scripts/fetch-dictionaries.sh at the repo root) when it exists, else downloads the
// same npm "dictionary-*" packages by wooorm (unchanged upstream files, each under its own licence).
import { copyFileSync, existsSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const here = new URL('.', import.meta.url).pathname;
const out = join(here, '..', 'public', 'dict');
const appDict = join(here, '..', '..', 'app', 'src', 'main', 'assets', 'dict');
mkdirSync(out, { recursive: true });

if (existsSync(appDict)) {
  for (const f of readdirSync(appDict)) if (/\.(aff|dic|license)$/.test(f) && !existsSync(join(out, f))) copyFileSync(join(appDict, f), join(out, f));
} else {
  const sh = readFileSync(join(here, '..', '..', 'scripts', 'fetch-dictionaries.sh'), 'utf8');
  const list = /LIST="([^"]+)"/s.exec(sh)[1].split(/\s+/).filter(Boolean);
  for (const entry of list) {
    const [code, ver] = entry.split(':');
    if (existsSync(join(out, code + '.dic'))) continue;
    const res = await fetch(`https://registry.npmjs.org/dictionary-${code}/-/dictionary-${code}-${ver}.tgz`);
    if (!res.ok) throw new Error(`dictionary-${code}: HTTP ${res.status}`);
    const tmp = join(tmpdir(), `mn-dict-${code}-${process.pid}`);
    mkdirSync(tmp, { recursive: true });
    writeFileSync(join(tmp, 'd.tgz'), Buffer.from(await res.arrayBuffer()));
    execFileSync('tar', ['-xzf', join(tmp, 'd.tgz'), '-C', tmp]);
    copyFileSync(join(tmp, 'package', 'index.aff'), join(out, code + '.aff'));
    copyFileSync(join(tmp, 'package', 'index.dic'), join(out, code + '.dic'));
    const lic = readdirSync(join(tmp, 'package')).find((n) => /^licen[cs]e/i.test(n));
    if (lic) copyFileSync(join(tmp, 'package', lic), join(out, code + '.license'));
    rmSync(tmp, { recursive: true, force: true });
  }
}
console.log('dictionaries:', readdirSync(out).filter((f) => f.endsWith('.dic')).length);
