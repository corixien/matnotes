// W2b: viewer (read-only) vs editor mode, stored documents on the home screen, save flows.
import { startPreview, launch, check, failures, sleep } from './lib.mjs';
const srv = await startPreview();
const browser = await launch();
const errors = [];
try {
  const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
  page.on('pageerror', (e) => errors.push(e.message));
  await page.goto(srv.url);
  await page.waitForFunction(() => window.matnotes?.engine, null, { timeout: 240000 });
  const canvasClick = async (dy = 200) => { const b = await page.locator('#qtcanvas').boundingBox(); await page.mouse.click(b.x + b.width / 2, b.y + dy); };

  // new document -> type -> Save (name dialog) -> lands in the store and on the home list
  await page.click('#new'); await sleep(5000);
  await canvasClick(); await page.keyboard.type('Eins', { delay: 30 }); await sleep(2500);
  check('new document starts in edit mode (ribbon)', await page.locator('.ribbon').count() === 1);
  check('unsaved marker shown', await page.waitForSelector('.dirty', { timeout: 20000 }).then(() => true, () => false)); // the engine reports the modified state a moment after typing
  await page.click('.icon-btn.save');
  await page.waitForSelector('.dialog');
  await page.fill('.dialog input[type=text]', 'Notiz');
  await page.click('.dialog .primary');
  await sleep(1000);
  check('unsaved marker cleared after save', await page.locator('.dirty').count() === 0);
  const stored = await page.evaluate(() => indexedDB.databases().then((d) => d.map((x) => x.name)));
  check('store database exists', stored.includes('matnotes'));
  await page.click('.back'); await sleep(500);
  await page.waitForSelector('.doc-name', { timeout: 10000 }).catch(() => {});
  check('stored document listed on home', (await page.locator('.doc-name').allInnerTexts()).includes('Notiz.docx'));

  // open it again: viewer first (no ribbon), edit button turns the ribbon on
  await page.click('.doc-main'); await page.waitForFunction(() => document.querySelector('.home.hidden') && !document.querySelector('.ribbon'), null, { timeout: 60000 }); await sleep(3500);
  check('opened stored document is a viewer (no ribbon)', await page.locator('.ribbon').count() === 0);
  await canvasClick(); await page.keyboard.type('X', { delay: 30 }); await sleep(800);
  let text = await page.evaluate(() => window.matnotes.engine.text());
  check('viewer blocks typing', text.trim() === 'Eins', JSON.stringify(text));
  await page.click('.icon-btn.accent'); await sleep(1500);
  check('edit mode shows ribbon', await page.locator('.ribbon').count() === 1);
  await canvasClick(); await page.keyboard.press('Control+End'); await page.keyboard.type('Zwei', { delay: 30 }); await sleep(800);
  text = await page.evaluate(() => window.matnotes.engine.text());
  check('edit mode accepts typing', text.includes('Zwei'), JSON.stringify(text));

  // Done keeps unsaved edits (viewer is a UI mode, nothing is reloaded)
  await page.click('.icon-btn.accent'); await sleep(2000);
  text = await page.evaluate(() => window.matnotes.engine.text());
  check('Done keeps unsaved edits', text.includes('Zwei'), JSON.stringify(text));
  check('Done hides the ribbon', await page.locator('.ribbon').count() === 0);
  await page.click('.icon-btn.accent'); await sleep(1500);

  // back with unsaved changes asks; discard returns home
  await page.click('.back'); await page.waitForSelector('.dialog');
  check('unsaved dialog offered', (await page.locator('.dialog h2').innerText()).length > 0);
  await page.click('.dialog .btn.danger'); await sleep(500);
  check('back on home after discard', await page.locator('.home.hidden').count() === 0);
} finally { await browser.close(); srv.stop(); }
check('no page errors', errors.length === 0, errors.join('; ').slice(0, 300));
process.exit(failures() ? 1 : 0);
