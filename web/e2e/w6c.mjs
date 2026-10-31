// W6c: zoom (buttons, Ctrl+wheel, Ctrl +/-/0, pinch) and that the browser's own page zoom stays untouched.
import { startPreview, launch, check, failures, sleep } from './lib.mjs';
const srv = await startPreview();
const browser = await launch();
const errors = [];
try {
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 }, hasTouch: true });
  const page = await ctx.newPage();
  page.on('pageerror', (e) => errors.push(e.message));
  await page.goto(srv.url);
  await page.waitForFunction(() => window.matnotes?.engine, null, { timeout: 240000 });
  await page.click('#new'); await page.waitForSelector('.editor .ribbon', { timeout: 60000 }); await sleep(2500);
  const zoom = async () => (await page.evaluate(() => window.matnotes.engine.zoom())).value;
  const label = () => page.locator('.zoom-label').innerText();
  const box = await page.locator('#qtcanvas').boundingBox();
  const dpr0 = await page.evaluate(() => window.devicePixelRatio);

  await page.waitForFunction(() => document.querySelector('.zoom-label')?.textContent === '100 %');
  check('starts at 100 %', (await zoom()) === 100);
  await page.click('.zoombar .icon-btn[title="Zoom +"]'); await sleep(400);
  check('plus: 110 %', (await zoom()) === 110 && (await label()) === '110 %', `${await zoom()} ${await label()}`);
  await page.click('.zoombar .icon-btn[title="Zoom −"]'); await page.click('.zoombar .icon-btn[title="Zoom −"]'); await sleep(400);
  check('minus twice: 90 %', (await zoom()) === 90, String(await zoom()));
  await page.click('.zoom-label'); await sleep(400);
  check('label resets to 100 %', (await zoom()) === 100);

  await page.mouse.move(box.x + 400, box.y + 300);
  await page.keyboard.down('Control');
  for (let i = 0; i < 4; i++) { await page.mouse.wheel(0, -100); await sleep(60); }
  await page.keyboard.up('Control'); await sleep(500);
  const afterWheel = await zoom();
  check('Ctrl+wheel up zooms in', afterWheel > 130, String(afterWheel));
  check('browser page zoom untouched', (await page.evaluate(() => window.devicePixelRatio)) === dpr0 && (await page.evaluate(() => visualViewport.scale)) === 1);
  await page.keyboard.press('Control+0'); await sleep(400);
  check('Ctrl+0 resets', (await zoom()) === 100);
  await page.keyboard.press('Control+-'); await sleep(400);
  check('Ctrl+- zooms out', (await zoom()) < 100);

  // pinch with two fingers moving apart
  const cdp = await ctx.newCDPSession(page);
  const cx = box.x + box.width / 2, cy = box.y + 300;
  await page.click('.zoom-label'); await sleep(300);
  const touch = (type, pts) => cdp.send('Input.dispatchTouchEvent', { type, touchPoints: pts.map((p, i) => ({ x: p[0], y: p[1], id: i })) });
  await touch('touchStart', [[cx - 40, cy], [cx + 40, cy]]);
  for (let i = 1; i <= 8; i++) { await touch('touchMove', [[cx - 40 - i * 15, cy], [cx + 40 + i * 15, cy]]); await sleep(40); }
  await touch('touchEnd', []);
  await sleep(500);
  const pinched = await zoom();
  check('pinch out zooms in', pinched > 150, String(pinched));
  const label2 = await label();
  check('label follows the pinch', label2 === `${pinched} %`, label2);

  // fit width
  await page.click('.zoombar .icon-btn[title="Page width"]'); await sleep(600);
  const fit = await zoom();
  check('page width sets a zoom that fits', fit > 100 && fit < 200, String(fit));
  await page.screenshot({ path: 'e2e/out/w6c-zoom.png' });
} finally { await browser.close(); srv.stop(); }
check('no page errors', errors.length === 0, errors.join('; ').slice(0, 300));
process.exit(failures() ? 1 : 0);
