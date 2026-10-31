// W6d: rename documents (home list menu, document title in the editor), name clash refused.
import { startPreview, launch, check, failures, sleep } from './lib.mjs';
const srv = await startPreview();
const browser = await launch();
const errors = [];
try {
  const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
  page.on('pageerror', (e) => errors.push(e.message));
  await page.goto(srv.url);
  await page.waitForFunction(() => window.matnotes?.engine, null, { timeout: 240000 });
  const names = () => page.locator('.doc-name').allInnerTexts();
  const save = async (name) => {
    await page.click('#new'); await page.waitForSelector('.editor .ribbon', { timeout: 60000 }); await sleep(2500);
    const b = await page.locator('#qtcanvas').boundingBox(); await page.mouse.click(b.x + b.width / 2, b.y + 200);
    await page.keyboard.type(name, { delay: 20 }); await sleep(800);
    await page.click('.icon-btn.save'); await page.waitForSelector('.dialog');
    await page.fill('.dialog input[type=text]', name); await page.click('.dialog .primary'); await sleep(800);
    await page.click('.back'); await page.waitForSelector('.home:not(.hidden)'); await sleep(900);
  };
  await save('Alt'); await save('Zweit');
  await page.waitForFunction(() => document.querySelectorAll('.doc-name').length === 2, null, { timeout: 15000 }).catch(() => {});
  check('both documents listed', (await names()).sort().join() === 'Alt.docx,Zweit.docx', (await names()).join());

  // home menu
  await page.locator('.doc-row', { hasText: 'Alt.docx' }).locator('.icon-btn').click();
  await page.click('.popup .menu-item:text-is("Umbenennen")');
  const input = page.locator('.dialog input[type=text]');
  check('dialog is prefilled without the extension', (await input.inputValue()) === 'Alt');
  await input.fill('Neu'); await page.click('.dialog .primary'); await sleep(800);
  check('home list shows the new name (extension kept)', (await names()).sort().join() === 'Neu.docx,Zweit.docx', (await names()).join());

  // name clash
  await page.locator('.doc-row', { hasText: 'Neu.docx' }).locator('.icon-btn').click();
  await page.click('.popup .menu-item:text-is("Umbenennen")');
  await page.fill('.dialog input[type=text]', 'zweit'); await page.click('.dialog .primary'); await sleep(600);
  check('taken name is refused (case-insensitive)', (await page.locator('.toast').innerText()).includes('gibt es schon') && (await names()).includes('Neu.docx'));

  // title in the editor
  await page.locator('.doc-main', { hasText: 'Zweit.docx' }).click();
  await page.waitForFunction(() => document.querySelector('.home.hidden'), null, { timeout: 60000 }); await sleep(3000);
  await page.click('.doc-title'); await page.fill('.dialog input[type=text]', 'Drittes'); await page.click('.dialog .primary'); await sleep(600);
  check('title shows the new name', (await page.locator('.doc-title').innerText()).startsWith('Drittes.docx'));
  await page.click('.back'); await sleep(800);
  check('list after renaming the open document', (await names()).sort().join() === 'Drittes.docx,Neu.docx', (await names()).join());
} finally { await browser.close(); srv.stop(); }
check('no page errors', errors.length === 0, errors.join('; ').slice(0, 300));
process.exit(failures() ? 1 : 0);
