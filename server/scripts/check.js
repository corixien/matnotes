// Syntax check of every first-party JS file (no linter on purpose).
const { execFileSync } = require('child_process');
const fs = require('fs');
const path = require('path');
const root = path.join(__dirname, '..');
function walk(d) {
    return fs.readdirSync(d, { withFileTypes: true }).flatMap((e) => {
        if (e.name === 'node_modules') return [];
        const p = path.join(d, e.name);
        return e.isDirectory() ? walk(p) : p.endsWith('.js') ? [p] : [];
    });
}
for (const f of walk(root)) execFileSync(process.execPath, ['--check', f], { stdio: 'inherit' });
console.log('syntax ok');
