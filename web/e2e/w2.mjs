// W2: home screen, editor chrome, ribbon commands, desktop and phone layouts.
import { startPreview, launch, check, failures, sleep } from './lib.mjs';

const srv = await startPreview();
const browser = await launch();
const errors = [];
try {
  const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
  page.on('pageerror', (e) => { errors.push(e.message); console.log('pageerror', e.message.slice(0, 200)); });
  await page.goto(srv.url);
  await sleep(500);
  await page.screenshot({ path: 'e2e/out/w2-home-desktop.png' });
  check('home shows title and two actions', (await page.locator('.home h1').innerText()) === 'MatNotes' && (await page.locator('.card').count()) === 2);
  await page.waitForFunction(() => window.matnotes?.engine, null, { timeout: 240000 });

  await page.click('#new');
  await page.waitForSelector('.editor .ribbon', { timeout: 30000 });
  await sleep(6000);
  const box = await page.locator('#qtcanvas').boundingBox();
  check('editor visible, home hidden', await page.locator('.home.hidden').count() === 1);
  check('canvas has room', box.width > 800 && box.height > 400, JSON.stringify(box));

  await page.mouse.click(box.x + box.width / 2, box.y + 200);
  await page.keyboard.type('Test', { delay: 30 });
  await page.keyboard.press('Control+a');
  await sleep(500);
  await page.click('button[title="Bold"]');
  await sleep(2500);
  await page.screenshot({ path: 'e2e/out/w2-editor-desktop.png' });
  const boldOn = await page.evaluate(() => document.querySelector('button[title="Bold"]').classList.contains('on'));
  check('Bold button reflects selection state', boldOn, JSON.stringify(await page.evaluate(() => [...window.matnotes.app.states.entries()].filter(([k]) => /Bold/.test(k)))));
  const text = await page.evaluate(() => window.matnotes.engine.text());
  check('text intact after bold', text.trim() === 'Test', text);

  // ribbon tabs
  for (const tab of ['insert', 'layout', 'refs', 'review', 'view']) {
    await page.click(`.tab[data-tab="${tab}"]`);
    const groups = await page.locator('.ribbon-panel .group').count();
    check(`tab ${tab} renders groups`, groups >= 1, String(groups));
  }

  // phone layout
  await page.setViewportSize({ width: 390, height: 800 });
  await sleep(1500);
  await page.screenshot({ path: 'e2e/out/w2-editor-phone.png' });
  const order = await page.evaluate(() => {
    const r = document.querySelector('.ribbon-host').getBoundingClientRect(), c = document.querySelector('#qtcanvas').getBoundingClientRect();
    return { ribbonBelow: r.top >= c.bottom - 1, canvasW: Math.round(c.width) };
  });
  check('phone: ribbon below canvas', order.ribbonBelow, JSON.stringify(order));

  // back to home, document list
  await page.click('.back');
  await sleep(300);
  await page.screenshot({ path: 'e2e/out/w2-home-phone.png' });
} finally { await browser.close(); srv.stop(); }
check('no page errors', errors.length === 0, errors.join('; ').slice(0, 300));
process.exit(failures() ? 1 : 0);
