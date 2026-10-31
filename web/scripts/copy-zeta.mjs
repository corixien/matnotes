// zeta.js has to be served as a plain file: the engine loads it into its worker (Module.uno_scripts).
import { copyFileSync, mkdirSync } from 'node:fs';
mkdirSync(new URL('../public/vendor/', import.meta.url), { recursive: true });
copyFileSync(new URL('../node_modules/zetajs/source/zeta.js', import.meta.url), new URL('../public/vendor/zeta.js', import.meta.url));
