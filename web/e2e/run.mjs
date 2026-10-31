// Runs every e2e suite (e2e/w*.mjs) one after another; exit code 1 if any fails.
import { readdirSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
const only = process.argv[2];
let bad = 0;
for (const f of readdirSync(new URL('.', import.meta.url)).filter((n) => /^w.*\.mjs$/.test(n) && (!only || n.includes(only))).sort()) {
  console.log('==', f);
  const r = spawnSync('node', ['e2e/' + f], { stdio: 'inherit' });
  if (r.status !== 0) bad++;
}
process.exit(bad ? 1 : 0);
