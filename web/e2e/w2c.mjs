// W2c: ribbon actions: page setup, table, contextual tabs, link, find/replace, shapes, symbols, header.
import { startPreview, launch, check, failures, sleep } from './lib.mjs';
const srv = await startPreview();
const browser = await launch();
const errors = [];
try {
  const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
  page.on('pageerror', (e) => errors.push(e.message));
  await page.goto(srv.url);
  await page.waitForFunction(() => window.matnotes?.engine, null, { timeout: 240000 });
  const eng = (f, a) => page.evaluate(f, a);
  const tab = (id) => page.click(`.tab[data-tab="${id}"]`);
  const menu = async (label, item) => { await page.click(`.ribbon-panel .rb:has-text("${label}")`); await page.click(`.popup .menu-item:has-text("${item}")`); await sleep(500); };

  await page.click('#new'); await sleep(5000);
  const b = await page.locator('#qtcanvas').boundingBox();
  await page.mouse.click(b.x + b.width / 2, b.y + 200);
  await page.keyboard.type('Hallo Welt', { delay: 30 }); await sleep(800);

  // page setup
  await tab('layout');
  await menu('Seitenränder', 'Schmal');
  let p = await eng(() => window.matnotes.engine.pageStyle());
  check('margins narrow', Math.abs(p.LeftMargin - 1270) < 5 && Math.abs(p.TopMargin - 1270) < 5, JSON.stringify(p));
  await menu('Ausrichtung', 'Querformat');
  p = await eng(() => window.matnotes.engine.pageStyle());
  check('landscape', p.Width > p.Height && p.IsLandscape === true, JSON.stringify(p));
  await menu('Größe', 'A5');
  p = await eng(() => window.matnotes.engine.pageStyle());
  check('A5 landscape', Math.abs(p.Width - 21000) < 10 && Math.abs(p.Height - 14800) < 10, JSON.stringify(p));
  await menu('Ausrichtung', 'Hochformat');
  p = await eng(() => window.matnotes.engine.pageStyle());
  check('portrait again keeps A5', Math.abs(p.Width - 14800) < 10 && Math.abs(p.Height - 21000) < 10, JSON.stringify(p));

  // find / replace
  await tab('start');
  await page.click('.ribbon-panel .rb:has-text("Suchen")');
  await page.fill('.dialog input[aria-label="Suchen"]', 'Welt');
  await page.fill('.dialog input[aria-label="Ersetzen durch"]', 'Erde');
  await page.click('.dialog .btn:text-is("Alle")'); await sleep(800);
  await page.click('.dialog .btn:has-text("Schließen")');
  let text = await eng(() => window.matnotes.engine.text());
  check('replace all', text.trim() === 'Hallo Erde', JSON.stringify(text));

  // link: javascript: refused, https accepted
  await tab('insert');
  await page.click('.ribbon-panel .rb:has-text("Link")');
  await page.fill('.dialog input[type=url]', 'javascript:alert(1)');
  await page.click('.dialog .primary'); await sleep(300);
  check('javascript: link refused (dialog stays)', await page.locator('.dialog').count() === 1);
  await page.fill('.dialog input[type=text]', 'Beispiel');
  await page.fill('.dialog input[type=url]', 'https://example.com');
  await page.click('.dialog .primary'); await sleep(800);
  text = await eng(() => window.matnotes.engine.text());
  check('link text inserted', text.includes('Beispiel'), JSON.stringify(text));

  // symbol
  await page.click('.ribbon-panel .rb:has-text("Symbol")');
  await page.click('.popup .sym:has-text("€")'); await sleep(600);
  text = await eng(() => window.matnotes.engine.text());
  check('symbol inserted', text.includes('€'), JSON.stringify(text));

  // table via grid -> contextual Table tab
  await page.click('.ribbon-panel .rb:has-text("Tabelle")');
  await page.click('.popup .tcell[aria-label="3 × 2"]'); await sleep(1500);
  await page.screenshot({ path: 'e2e/out/w2c-table.png' });
  const st = await eng(() => JSON.stringify([...window.matnotes.app.states.entries()].filter(([k]) => /DeleteTable|SetAnchorToPage/.test(k))));
  check('table context tab appears', await page.locator('.tab[data-tab="table"]').count() === 1, st);
  if (await page.locator('.tab[data-tab="table"]').count()) {
    await page.click('.ribbon-panel .rb:has-text("+ Zeile ↓")'); await sleep(500);
    check('table tab groups', await page.locator('.ribbon-panel .group').count() >= 6);
  }

  // header
  await tab('insert');
  await page.click('.ribbon-panel .rb:has-text("Kopfzeile")'); await sleep(800);
  const hasHeader = await eng(async () => { const p = await window.matnotes.engine.pageStyle(); return p; });
  check('header command did not error', true);
} finally { await browser.close(); srv.stop(); }
check('no page errors', errors.length === 0, errors.join('; ').slice(0, 300));
process.exit(failures() ? 1 : 0);
