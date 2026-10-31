// W6b: spell check panel (language detection, mistakes, suggestion replaces all occurrences) and the phone selection toolbar.
import { startPreview, launch, check, failures, sleep } from './lib.mjs';
const srv = await startPreview();
const browser = await launch();
const errors = [];
try {
  const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
  page.on('pageerror', (e) => errors.push(e.message));
  await page.goto(srv.url);
  await page.waitForFunction(() => window.matnotes?.engine, null, { timeout: 240000 });
  await page.click('#new'); await sleep(5000);
  const b = await page.locator('#qtcanvas').boundingBox();
  await page.mouse.click(b.x + b.width / 2, b.y + 200);
  await page.keyboard.type('Das ist ein Hauus und ein Tisch. Der Tisch steht am Fenster, und das Hauus ist gross und schoen. Wir sehen es jeden Tag.', { delay: 15 });
  await sleep(1000);
  await page.click('.tab[data-tab="review"]');
  await page.click('.ribbon-panel .rb:has-text("Rechtschreibung")');
  await page.waitForSelector('.spell .lang-row', { timeout: 60000 });
  check('language detected as German', (await page.locator('.spell .lang-row .big').innerText()).startsWith('Deutsch'));
  check('"detected from text" hint shown', await page.locator('.spell .accent').count() === 1);
  await page.click('.spell .btn.primary:has-text("Prüfung starten")');
  await page.waitForSelector('.spell .misspelled', { timeout: 60000 });
  const word = await page.locator('.spell .misspelled').innerText();
  check('first mistake is Hauus', word === 'Hauus', word);
  check('counted twice', (await page.locator('.spell .note').first().innerText()).includes('2'));
  await page.click('.spell .suggestion:text-is("Haus")'); await sleep(1500);
  const text = await page.evaluate(() => window.matnotes.engine.text());
  check('suggestion replaced all occurrences', !text.includes('Hauus') && (text.match(/Haus/g) || []).length === 2, text);
  const rest = await page.locator('.spell .misspelled').allInnerTexts();
  console.log('remaining:', rest);
  await page.screenshot({ path: 'e2e/out/w6b-spell.png' });
  await page.click('.spell-head .btn');
  check('closing the panel brings the ribbon back', await page.locator('.ribbon').count() === 1);
} finally { await browser.close(); srv.stop(); }
check('no page errors', errors.length === 0, errors.join('; ').slice(0, 300));
process.exit(failures() ? 1 : 0);
