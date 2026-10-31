// W1: engine loads, document can be created, typed into, saved as docx/odt/pdf and reopened.
import { startPreview, launch, check, failures, sleep } from './lib.mjs';

const srv = await startPreview();
const browser = await launch();
try {
  const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
  page.on('pageerror', (e) => console.log('pageerror', e.message.slice(0, 200)));
  await page.goto(srv.url);
  check('cross-origin isolated', await page.evaluate(() => self.crossOriginIsolated));
  await page.waitForFunction(() => window.matnotes?.engine, null, { timeout: 240000 });
  check('engine started', true);

  await page.click('#new');
  await sleep(4000);
  const box = await page.locator('#qtcanvas').boundingBox();
  await page.mouse.click(box.x + box.width / 2, box.y + 200);
  await page.keyboard.type('Hallo Welt', { delay: 40 });
  await sleep(500);
  const text = await page.evaluate(() => window.matnotes.engine.text());
  check('typing reaches the document', text.trim() === 'Hallo Welt', JSON.stringify(text));

  const docx = await page.evaluate(async () => Array.from(await window.matnotes.engine.save('docx')));
  check('docx export is a zip', docx[0] === 0x50 && docx[1] === 0x4b && docx.length > 2000, 'len ' + docx.length);
  const pdf = await page.evaluate(async () => Array.from(await window.matnotes.engine.save('pdf')));
  check('pdf export', String.fromCharCode(...pdf.slice(0, 4)) === '%PDF');

  await page.evaluate(async (bytes) => { await window.matnotes.engine.open('round.docx', new Uint8Array(bytes)); }, docx);
  await sleep(1500);
  const again = await page.evaluate(() => window.matnotes.engine.text());
  check('docx reopens with same text', again.trim() === 'Hallo Welt', JSON.stringify(again));
  await page.screenshot({ path: 'e2e/out/w1.png' });
} finally { await browser.close(); srv.stop(); }
process.exit(failures() ? 1 : 0);
