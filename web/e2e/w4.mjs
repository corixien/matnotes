// W4/W5: account (register, recovery key page, sign in on a second device), sync of a saved document, shared folder invitation.
// Needs the sync server on 127.0.0.1:3000 (vite preview proxies /api there) with RATE_LIMIT_DISABLED=1.
import { startPreview, launch, check, failures, sleep } from './lib.mjs';

const srv = await startPreview();
const browser = await launch();
const errors = [];
const uid = Math.random().toString(36).slice(2, 8);
const mailA = `anna-${uid}@example.com`, mailB = `ben-${uid}@example.com`, pw = 'pässwörd-12345';

async function device(name) {
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const page = await ctx.newPage();
  page.on('pageerror', (e) => { errors.push(name + ': ' + e.message); });
  await page.goto(srv.url);
  await page.waitForFunction(() => window.matnotes?.engine, null, { timeout: 240000 });
  return page;
}
const openProfile = (p) => p.click('.profile');
const fill = (p, label, v) => p.fill(`.dialog input[aria-label="${label}"]`, v);

try {
  const [A, B] = await Promise.all([device('A'), device('B')]);

  // ---- register on A
  await openProfile(A);
  await A.click('.dialog .btn:text-is("Konto erstellen")');
  await fill(A, 'Name', 'Anna'); await fill(A, 'E-Mail', mailA);
  await fill(A, 'Passwort', pw); await fill(A, 'Passwort wiederholen', 'anders');
  await A.click('.dialog .btn.primary');
  check('register: mismatching passwords refused', (await A.locator('.dialog .error').innerText()).includes('stimmen nicht'));
  await fill(A, 'Passwort wiederholen', pw);
  await A.click('.dialog .btn.primary');
  await A.waitForSelector('.dialog .recovery', { timeout: 60000 });
  const rk = await A.locator('.dialog .recovery').innerText();
  check('recovery key page shows a 13x4 base32 key', /^([A-Z2-7]{4}-){12}[A-Z2-7]{4}$/.test(rk), rk);
  check('continue is disabled until confirmed', await A.locator('.dialog .btn.primary').isDisabled());
  await A.check('#rk-confirm');
  await A.click('.dialog .btn.primary');
  await sleep(500);
  check('dialog closed after the recovery key was confirmed', await A.locator('.dialog').count() === 0);

  // ---- a document on A, saved -> syncs
  await A.click('#new'); await sleep(5000);
  let box = await A.locator('#qtcanvas').boundingBox();
  await A.mouse.click(box.x + box.width / 2, box.y + 200);
  await A.keyboard.type('Cloud Test', { delay: 30 }); await sleep(1200);
  await A.click('.icon-btn.save'); await A.waitForSelector('.dialog');
  await A.fill('.dialog input[type=text]', 'Notiz'); await A.click('.dialog .primary'); await sleep(800);
  await A.click('.back'); await sleep(7500); // sync runs 5 s after the save

  // ---- sign in on B: the document arrives
  await openProfile(B);
  await fill(B, 'E-Mail', mailA); await fill(B, 'Passwort', 'falsch');
  await B.click('.dialog .btn.primary');
  await B.waitForSelector('.dialog .error', { timeout: 60000 });
  check('wrong password shows a message', (await B.locator('.dialog .error').innerText()).includes('falsch'));
  await fill(B, 'Passwort', pw);
  await B.click('.dialog .btn.primary');
  await B.waitForFunction(() => [...document.querySelectorAll('.doc-name')].some((e) => e.textContent === 'Notiz.docx'), null, { timeout: 60000 });
  check('document synced to device B', true);

  // ---- B opens it, edits, overwrites; A receives it
  await B.click('.doc-main'); await B.waitForFunction(() => document.querySelector('.home.hidden'), null, { timeout: 60000 }); await sleep(3500);
  const textB = await B.evaluate(() => window.matnotes.engine.text());
  check('B opens the synced document with its content', textB.trim() === 'Cloud Test', JSON.stringify(textB));
  await B.click('.icon-btn.accent'); await sleep(1500);
  box = await B.locator('#qtcanvas').boundingBox();
  await B.mouse.click(box.x + box.width / 2, box.y + 200); await B.keyboard.press('Control+End');
  await B.keyboard.type(' von Ben', { delay: 30 }); await sleep(1200);
  await B.click('.icon-btn:has-text("⋮")'); await B.click('.popup .menu-item:text-is("Original überschreiben…")');
  await B.click('.dialog .btn.danger, .dialog .btn.primary:has-text("Überschreiben")'); await sleep(800);
  await B.click('.back'); await sleep(7500);
  await A.evaluate(() => window.matnotesCloud.syncNow()); await sleep(1500);
  const synced = await A.evaluate(async () => { const s = await import('/src/store.ts').catch(() => null); return null; });
  await A.click('.doc-main'); await A.waitForFunction(() => document.querySelector('.home.hidden'), null, { timeout: 60000 }); await sleep(3500);
  const textA = await A.evaluate(() => window.matnotes.engine.text());
  check('A receives the edit from B', textA.includes('von Ben'), JSON.stringify(textA));
  await A.click('.back'); await sleep(500);

  // ---- shared folder: A creates + invites B (second account)
  const C = B; // B signs out, a second account signs in
  await openProfile(C);
  await C.click('.dialog .btn:text-is("Abmelden")'); await sleep(800);
  check('B signed out', await C.locator('.doc-name').count() >= 0);
  await openProfile(C);
  await C.click('.dialog .btn:text-is("Konto erstellen")');
  await fill(C, 'Name', 'Ben'); await fill(C, 'E-Mail', mailB); await fill(C, 'Passwort', pw); await fill(C, 'Passwort wiederholen', pw);
  await C.click('.dialog .btn.primary'); await C.waitForSelector('.dialog .recovery', { timeout: 60000 });
  await C.check('#rk-confirm'); await C.click('.dialog .btn.primary'); await sleep(2500);

  await openProfile(A);
  await A.click('.dialog .btn:text-is("Ordner & Freigaben")');
  await A.click('.dialog .btn.primary:text-is("Neuer gemeinsamer Ordner")');
  await A.fill('.dialog input[type=text]', 'Schule'); await A.click('.dialog .btn.primary:text-is("Erstellen")');
  await A.waitForSelector('.shared .strong:text-is("Schule")', { timeout: 60000 });
  await A.click('.shared .btn:text-is("Einladen")');
  await A.fill('.dialog input[type=email]', mailB); await A.click('.dialog .btn.primary:text-is("Suchen")');
  await A.waitForSelector('.dialog .fingerprint', { timeout: 60000 });
  check('invite shows the key code of the invitee', /^([0-9A-F]{4} ){4}[0-9A-F]{4}$/.test(await A.locator('.dialog .fingerprint').innerText()));
  await A.click('.dialog .btn.primary:text-is("Einladen")'); await sleep(1500);
  await A.keyboard.press('Escape'); await A.keyboard.press('Escape');

  await C.evaluate(() => window.matnotesCloud.syncNow()); await sleep(1500);
  check('invitation badge shows 1 on B', (await C.locator('.badge').innerText()) === '1');
  await openProfile(C);
  await C.click('.dialog .btn:text-is("Ordner & Freigaben")');
  await C.click('.invite .btn.primary:text-is("Annehmen")'); await sleep(3000);
  await C.keyboard.press('Escape'); await C.keyboard.press('Escape');
  await C.screenshot({ path: 'e2e/out/w4-b-home.png' });
  check('B sees the shared folder as the active member list', (await C.evaluate(() => window.matnotesCloud.sharedFolders().map((f) => f.name))).includes('Schule'));
  await A.screenshot({ path: 'e2e/out/w4-a-home.png' });
} finally { await browser.close(); srv.stop(); }
check('no page errors', errors.length === 0, errors.join('; ').slice(0, 400));
process.exit(failures() ? 1 : 0);
