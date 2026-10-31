import { DOMParser as XDOMParser, XMLSerializer as XSerializer } from '@xmldom/xmldom';
import { strToU8, unzipSync, zipSync } from 'fflate';
import { beforeAll, describe, expect, it } from 'vitest';
import { applyEdge, type BorderLine, MARK, patchDocx, patchXml } from './tablepatch';

beforeAll(() => { (globalThis as any).DOMParser = XDOMParser; (globalThis as any).XMLSerializer = XSerializer; });

const W = 'xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"';
const line: BorderLine = { style: 'single', size: 4, color: '000000' };
// two tables; the marker sits in the second one. LibreOffice style: borders live in the cells (tcBorders), none on the table.
const cell = (text: string, borders = '') => `<w:tc><w:tcPr><w:tcW w:w="100" w:type="dxa"/>${borders}</w:tcPr><w:p><w:r><w:t>${text}</w:t></w:r></w:p></w:tc>`;
const xml = (mark: string) => `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document ${W}><w:body>
<w:tbl><w:tblPr><w:tblW w:w="0" w:type="auto"/></w:tblPr><w:tr>${cell('first')}</w:tr></w:tbl>
<w:tbl><w:tblPr><w:tblStyle w:val="x"/><w:tblW w:w="0" w:type="auto"/><w:tblLook w:val="0"/></w:tblPr>
<w:tr>${cell('a' + mark, '<w:tcBorders><w:top w:val="single" w:sz="4" w:color="FF0000"/></w:tcBorders>')}${cell('b')}</w:tr><w:tr>${cell('c')}${cell('d')}</w:tr></w:tbl>
</w:body></w:document>`;

describe('edge logic (same toggles as the app)', () => {
  it('all toggles on and off', () => {
    const on = applyEdge({}, 'all', line);
    expect(on.top).toEqual(line);
    expect(applyEdge(on, 'all', line).top).toBeNull();
  });
  it('outside / inside / single edges', () => {
    const o = applyEdge({}, 'outside', line);
    expect([o.top, o.left, o.bottom, o.right].every(Boolean)).toBe(true);
    expect(o.insideH).toBeNull();
    expect(applyEdge(o, 'outside', line).top).toBeNull();
    const i = applyEdge(o, 'inside', line);
    expect(i.insideH && i.insideV && i.top).toBeTruthy();
    expect(applyEdge(i, 'top', line).top).toBeNull();
    expect(applyEdge(i, 'none', line)).toEqual({ top: null, left: null, bottom: null, right: null, insideH: null, insideV: null });
  });
});

describe('patchXml', () => {
  it('writes tblBorders into the marked table only, in schema order, and removes the marker and cell borders', () => {
    const out = patchXml(xml(MARK), { edge: 'all', line: { style: 'double', size: 12, color: 'C00000' } })!;
    expect(out).not.toContain(MARK);
    const tables = out.split('<w:tbl>');
    expect(tables[1]).not.toContain('tblBorders'); // first table untouched
    expect(tables[2]).toContain('<w:tblBorders>');
    expect(tables[2]).toContain('w:val="double"');
    expect(tables[2]).toContain('w:color="C00000"');
    expect(tables[2]).not.toContain('tcBorders');
    // tblBorders comes after tblW and before tblLook
    expect(tables[2].indexOf('tblW')).toBeLessThan(tables[2].indexOf('tblBorders'));
    expect(tables[2].indexOf('tblBorders')).toBeLessThan(tables[2].indexOf('tblLook'));
    expect(out.startsWith('<?xml')).toBe(true);
  });
  it('starts from the existing cell borders (LibreOffice export) when toggling', () => {
    // the only existing border is the red top line of cell a: toggling "top" removes it
    const out = patchXml(xml(MARK), { edge: 'top', line })!;
    const b = /<w:tblBorders>[\s\S]*?<\/w:tblBorders>/.exec(out)![0];
    expect(b).toMatch(/<w:top w:val="nil"/);
  });
  it('a look replaces all edges', () => {
    const out = patchXml(xml(MARK), { line, edges: { top: line, bottom: line } })!;
    const b = /<w:tblBorders>[\s\S]*?<\/w:tblBorders>/.exec(out)![0];
    expect(b).toMatch(/<w:top w:val="single"/);
    expect(b).toMatch(/<w:left w:val="nil"/);
  });
  it('returns null without a marker or when the marker is outside a table', () => {
    expect(patchXml(xml(''), { edge: 'all', line })).toBeNull();
    expect(patchXml(`<w:document ${W}><w:body><w:p><w:r><w:t>${MARK}</w:t></w:r></w:p></w:body></w:document>`, { edge: 'all', line })).toBeNull();
  });
});

describe('patchDocx', () => {
  it('rewrites document.xml and keeps the other parts', () => {
    const zip = zipSync({ '[Content_Types].xml': strToU8('<Types/>'), 'word/document.xml': strToU8(xml(MARK)), 'word/styles.xml': strToU8('<s/>') });
    const out = unzipSync(patchDocx(zip, { edge: 'outside', line })!);
    expect(Object.keys(out).sort()).toEqual(['[Content_Types].xml', 'word/document.xml', 'word/styles.xml']);
    expect(new TextDecoder().decode(out['word/styles.xml'])).toBe('<s/>');
    expect(new TextDecoder().decode(out['word/document.xml'])).toContain('tblBorders');
    expect(patchDocx(zipSync({ 'a.txt': strToU8('x') }), { edge: 'all', line })).toBeNull();
  });
});
