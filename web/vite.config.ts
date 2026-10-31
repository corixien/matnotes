import { existsSync, readFileSync } from 'node:fs';
import { defineConfig } from 'vite';

// pinned engine copy (scripts/fetch-engine.mjs), served by server/src/web.js with an immutable cache; none = use the CDN
const engineVersion = existsSync('public/engine/version.txt') ? readFileSync('public/engine/version.txt', 'utf8').trim() : '';

// The engine needs cross-origin isolation (SharedArrayBuffer); the Express server sets the same headers in production.
const isolation = { 'Cross-Origin-Opener-Policy': 'same-origin', 'Cross-Origin-Embedder-Policy': 'require-corp' };

// hunspell-asm 4 (and emscripten-wasm-loader) call a namespace import of the CJS package `nanoid`; bundlers make that object non-callable.
const fixNanoid = {
  name: 'fix-nanoid-namespace',
  enforce: 'pre' as const,
  transform(code: string, id: string) {
    if (/hunspell-asm|emscripten-wasm-loader/.test(id) && code.includes("import * as nanoid from 'nanoid'")) return code.replace("import * as nanoid from 'nanoid'", "import nanoid from 'nanoid'");
    return null;
  },
};

export default defineConfig({
  plugins: [fixNanoid],
  define: { __ENGINE_BASE__: JSON.stringify(engineVersion ? `/engine/${engineVersion}/` : '') },
  server: { port: 5173, headers: isolation, proxy: { '/api': 'http://127.0.0.1:3000' } },
  preview: { port: 5173, headers: isolation, proxy: { '/api': 'http://127.0.0.1:3000' } },
  worker: { format: 'es', plugins: () => [fixNanoid] },
  build: { target: 'es2022', minify: process.env.NO_MINIFY ? false : 'esbuild' },
  test: { environment: 'node' },
});
