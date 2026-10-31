// Shared e2e helpers: static preview server (built dist with the isolation headers) + headless Chrome.
import { spawn, execSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { chromium } from 'playwright-core';

export const CHROME = [process.env.HOME + '/.cache/ms-playwright/chromium-1243/chrome-linux64/chrome', '/usr/bin/google-chrome'].find(existsSync);

export async function startPreview(port = 5199) {
  execSync('npx vite build', { stdio: 'ignore' }); // e2e always runs against a fresh build
  // E2E_URL=http://127.0.0.1:3100/ tests the real Express server (security headers, CSP, compression) instead of vite preview
  if (process.env.E2E_URL) return { url: process.env.E2E_URL, stop: () => {} };
  const p = spawn('npx', ['vite', 'preview', '--port', String(port), '--strictPort', '--host', '127.0.0.1'], { stdio: 'ignore', detached: true });
  for (let i = 0; i < 50; i++) {
    try { if ((await fetch(`http://127.0.0.1:${port}/`)).ok) break; } catch { /* not yet */ }
    await new Promise((r) => setTimeout(r, 200));
  }
  return { url: `http://127.0.0.1:${port}/`, stop: () => { try { process.kill(-p.pid); } catch { /* gone */ } } };
}

export async function launch(opts = {}) {
  const browser = await chromium.launch({ executablePath: CHROME, args: ['--no-sandbox'], ...opts });
  return browser;
}

let failed = 0;
export function check(name, ok, detail = '') {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${ok ? '' : ' ' + detail}`);
  if (!ok) failed++;
}
export const failures = () => failed;
export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
