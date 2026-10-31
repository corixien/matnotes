declare module 'hunspell-asm/dist/esm/lib/browser/hunspell' { const runtime: unknown; export default runtime; }
declare module 'hunspell-asm/dist/esm/hunspellLoader' { export const hunspellLoader: (runtime: any) => any; }
declare module 'emscripten-wasm-loader' { export const getModuleLoader: (factory: (rt: any) => any, runtime: any, a?: unknown, opts?: unknown) => Promise<() => Promise<any>>; }
