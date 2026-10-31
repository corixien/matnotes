// W6a: table borders and looks (UNO table API), split cells, zoom commands.
import { writeFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { startPreview, launch, check, failures, sleep } from './lib.mjs';
const srv = await startPreview();
const browser = await launch();
const errors = [];
try {
  const page = await browser.newPage({ viewport: { width: 1280, height: 900 } });
  page.on('pageerror', (e) => errors.push(e.message));
  await page.goto(srv.url);
  await page.waitForFunction(() => window.matnotes?.engine, null, { timeout: 240000 });
  const docXml = async () => { // document.xml of the exported docx (zetajs cannot read util.Color values, the file shows the real colours)
    const bytes = await page.evaluate(async () => Array.from(await window.matnotes.engine.save('docx')));
    writeFileSync('e2e/out/w6a.docx', Buffer.from(bytes));
    return spawnSync('unzip', ['-p', 'e2e/out/w6a.docx', 'word/document.xml'], { encoding: 'utf8', maxBuffer: 1 << 26 }).stdout;
  };
  const info = () => page.evaluate(() => window.matnotes.engine.tableInfo());
  await page.click('#new'); await sleep(5000);
  const b = await page.locator('#qtcanvas').boundingBox();
  await page.mouse.click(b.x + b.width / 2, b.y + 200);
  await page.click('.tab[data-tab="insert"]');
  await page.click('.ribbon-panel .rb:has-text("Tabelle")');
  await page.click('.popup .tcell[aria-label="2 × 4"]'); await sleep(1500);
  await page.keyboard.type('Kopf', { delay: 30 }); await sleep(500);
  await page.waitForSelector('.tab[data-tab="table"]');
  let t = await info();
  check('table found at the caret', t && t.rows === 4, JSON.stringify(t));

  await page.click('.tab[data-tab="table"]');
  // a border change reloads the document: put the caret back into the table (it is the first thing on the page)
  const intoTable = async () => {
    await page.mouse.click(b.x + b.width / 2, b.y + 600); await page.keyboard.press('Control+Home'); await sleep(800);
    await page.waitForSelector('.tab[data-tab="table"]'); await page.click('.tab[data-tab="table"]');
  };
  const menu = async (btn, item) => {
    await page.click(`.ribbon-panel .rb:has-text("${btn}")`); await page.click(`.popup .menu-item:text-is("${item}")`); await sleep(3000);
    await intoTable();
  };

  // borders are read back from the exported docx (cell or table level, whatever LibreOffice writes)
  const borders = async () => await page.evaluate(() => window.matnotesApp.docTableEdges());
  const visible = (l) => !!l;
  await menu('Rahmen', 'Keine');
  let bd = await borders();
  check('border preset none', !visible(bd.top) && !visible(bd.insideH) && !visible(bd.left), JSON.stringify(bd));
  await menu('Rahmen', 'Außen');
  bd = await borders();
  check('border preset outside', visible(bd.top) && !visible(bd.insideH), JSON.stringify(bd));
  await menu('Rahmen', 'Doppelt');
  await menu('Rahmen', 'Alle');
  bd = await borders();
  check('border preset all with double style', visible(bd.top) && visible(bd.insideH) && bd.top.style === 'double', JSON.stringify(bd));
  await menu('Rahmen', 'Durchgehend');
  await page.click('.ribbon-panel .rb:has-text("Rahmen")'); await page.click('.popup .swatch[aria-label="#C00000"]'); await sleep(300);
  await menu('Rahmen', 'Alle');
  bd = await borders();
  check('border colour', bd.top.color.toUpperCase() === 'C00000' && bd.top.style === 'single', JSON.stringify(bd));

  await menu('Tabellenformat', 'Blau');
  t = await info();
  const xml = await docXml();
  check('look blue: header fill in the docx', /w:fill="2F5597"/i.test(xml) && !t.a1.transparent);
  check('look blue: banded row in the docx', /w:fill="DAE3F3"/i.test(xml) && !t.a3.transparent && t.a2.transparent, JSON.stringify(t));
  check('look blue: border colour in the docx', /w:color="8EAADB"/i.test(xml));
  await page.screenshot({ path: 'e2e/out/w6a-table-blue.png' });
  await menu('Tabellenformat', 'Minimal');
  t = await info();
  bd = await borders();
  check('look minimal clears fills and inner borders', t.a1.transparent && t.a3.transparent && !visible(bd.insideH) && visible(bd.top), JSON.stringify(bd) + JSON.stringify(t));
} finally { await browser.close(); srv.stop(); }
check('no page errors', errors.length === 0, errors.join('; ').slice(0, 300));
process.exit(failures() ? 1 : 0);
