// Word-style ribbon: tabs, groups, buttons, combos, colour/table/shape pickers. Same tabs, groups and `.uno:` commands as
// app/src/main/java/com/matnotes/app/Ribbon.kt. Items are data; `render` builds the DOM and `refresh` applies command state.
import { h, clear } from './dom';
import type { CommandState, Engine, UnoArg } from './engine';
import { t } from './i18n';
import { popup } from './ui';

export type Context = 'text' | 'table' | 'picture';
export interface RibbonHost {
  engine: Engine;
  state(id: string): CommandState | undefined;
  /** App level actions that are not a single UNO command (dialogs, files). */
  act(name: string, arg?: string): void;
}

type Item =
  | { k: 'btn'; icon: string; title: string; cmd?: string; act?: string; args?: UnoArg[]; on?: string; big?: boolean; label?: string; cls?: string }
  | { k: 'font' } | { k: 'size' } | { k: 'styles' }
  | { k: 'color'; cmd: string; prop: string; highlight?: boolean; none?: boolean; title: string }
  | { k: 'tablegrid' } | { k: 'shapes' } | { k: 'symbols' } | { k: 'spacing' } | { k: 'borders' } | { k: 'tablelooks' }
  | { k: 'menu'; icon: string; label: string; entries: [string, string, string?][] } // [text or i18n key, act, arg]
  | { k: 'stack'; rows: Item[][] };
type Group = { title: string; items: Item[] };
type Tab = { id: string; title: string; groups: Group[]; context?: Context };

const btn = (icon: string, title: string, cmd: string, o: Partial<Extract<Item, { k: 'btn' }>> = {}): Item => ({ k: 'btn', icon, title, cmd, ...o });
const big = (icon: string, label: string, cmd: string, o: Partial<Extract<Item, { k: 'btn' }>> = {}): Item => ({ k: 'btn', icon, title: label, label, cmd, big: true, ...o });
const act = (icon: string, label: string, name: string, o: Partial<Extract<Item, { k: 'btn' }>> = {}): Item => ({ k: 'btn', icon, title: label, label, act: name, big: true, ...o });

export const PALETTE = [0x000000, 0x7f7f7f, 0xffffff, 0xc00000, 0xff0000, 0xffc000, 0xffff00, 0x92d050, 0x00b050, 0x00b0f0, 0x0070c0, 0x002060, 0x7030a0];
export const WORD_FONTS = ['Calibri', 'Cambria', 'Arial', 'Arial Narrow', 'Times New Roman', 'Courier New'];
export const FREE_FONTS = ['Gentium Book Basic', 'DejaVu Sans', 'DejaVu Sans Condensed', 'DejaVu Serif', 'DejaVu Sans Mono', 'Comic Neue', 'Anton'];
export const SIZES = [8, 9, 10, 11, 12, 14, 16, 18, 20, 24, 28, 36, 48, 72];
export const STYLES: [string, string][] = [['style_text', 'Standard'], ['style_title', 'Title'], ['style_h1', 'Heading 1'], ['style_h2', 'Heading 2'], ['style_h3', 'Heading 3']];
export const SYMBOLS = ['©', '®', '™', '€', '£', '¥', '°', '±', '×', '÷', '≠', '≤', '≥', '∞', '→', '←', '↑', '↓', '§', '¶', '•', '…', '–', '—', '«', '»', '„', '“', 'α', 'β', 'γ', 'δ', 'π', 'Ω', 'µ', '√', '½', '¼', '¾', '✓'];
export const SHAPE_GROUPS: [string, [string, string][]][] = [
  ['shapes_basic', [['shape_rectangle', 'BasicShapes.rectangle'], ['shape_rounded', 'BasicShapes.round-rectangle'], ['shape_ellipse', 'BasicShapes.ellipse'], ['shape_triangle', 'BasicShapes.isosceles-triangle'], ['shape_diamond', 'BasicShapes.diamond'], ['shape_hexagon', 'BasicShapes.hexagon'], ['shape_cross', 'BasicShapes.cross']]],
  ['shapes_arrows', [['shape_arrow_right', 'ArrowShapes.right-arrow'], ['shape_arrow_left', 'ArrowShapes.left-arrow'], ['shape_arrow_up', 'ArrowShapes.up-arrow'], ['shape_arrow_down', 'ArrowShapes.down-arrow'], ['shape_arrow_both', 'ArrowShapes.left-right-arrow']]],
  ['shapes_stars', [['shape_star4', 'StarShapes.star4'], ['shape_star5', 'StarShapes.star5'], ['shape_star6', 'StarShapes.star6'], ['shape_heart', 'SymbolShapes.heart'], ['shape_sun', 'SymbolShapes.sun'], ['shape_smiley', 'SymbolShapes.smiley'], ['shape_cloud', 'SymbolShapes.cloud']]],
  ['shapes_flow', [['shape_process', 'FlowChartShapes.flowchart-process'], ['shape_decision', 'FlowChartShapes.flowchart-decision'], ['shape_terminator', 'FlowChartShapes.flowchart-terminator'], ['shape_data', 'FlowChartShapes.flowchart-data'], ['shape_document', 'FlowChartShapes.flowchart-document']]],
  ['shapes_callouts', [['shape_callout', 'CalloutShapes.round-rectangular-callout'], ['shape_callout_round', 'CalloutShapes.round-callout']]],
];
const SHAPE_GLYPH: Record<string, string> = { rectangle: '▭', 'round-rectangle': '▢', ellipse: '◯', 'isosceles-triangle': '△', diamond: '◇', hexagon: '⬡', cross: '✚', 'right-arrow': '➡', 'left-arrow': '⬅', 'up-arrow': '⬆', 'down-arrow': '⬇', 'left-right-arrow': '⬌', star4: '✦', star5: '★', star6: '✶', heart: '♥', sun: '☀', smiley: '☺', cloud: '☁' };

const TABS: Tab[] = [
  {
    id: 'start', title: 'tab_start', groups: [
      { title: 'g_undo', items: [
        { k: 'stack', rows: [
          [btn('↶', 'Undo', '.uno:Undo'), btn('↷', 'Redo', '.uno:Redo')],
          [btn('✂', 'cut', '.uno:Cut'), btn('⧉', 'copy', '.uno:Copy'), btn('📋', 'paste', '.uno:Paste')],
        ] },
        big('🖌', 'format_painter', '.uno:FormatPaintbrush'),
      ] },
      { title: 'g_font', items: [{ k: 'stack', rows: [
        [{ k: 'font' }, { k: 'size' }, btn('A⁺', '+', '.uno:Grow'), btn('A⁻', '−', '.uno:Shrink')],
        [btn('B', 'Bold', '.uno:Bold', { on: '.uno:Bold', cls: 'b' }), btn('I', 'Italic', '.uno:Italic', { on: '.uno:Italic', cls: 'i' }),
          btn('U', 'Underline', '.uno:Underline', { on: '.uno:Underline', cls: 'u' }), btn('ab', 'Strikeout', '.uno:Strikeout', { on: '.uno:Strikeout', cls: 's' }),
          btn('x₂', 'Subscript', '.uno:SubScript', { on: '.uno:SubScript' }), btn('x²', 'Superscript', '.uno:SuperScript', { on: '.uno:SuperScript' }),
          { k: 'color', cmd: '.uno:BackColor', prop: 'BackColor', highlight: true, none: true, title: 'Highlight' },
          { k: 'color', cmd: '.uno:Color', prop: 'Color', title: 'Font color' },
          btn('⌫', 'Clear formatting', '.uno:ResetAttributes')],
      ] }] },
      { title: 'g_paragraph', items: [{ k: 'stack', rows: [
        [btn('•≡', 'Bullets', '.uno:DefaultBullet', { on: '.uno:DefaultBullet' }), btn('1≡', 'Numbering', '.uno:DefaultNumbering', { on: '.uno:DefaultNumbering' }),
          btn('⇤', 'Decrease indent', '.uno:DecrementIndent'), btn('⇥', 'Increase indent', '.uno:IncrementIndent')],
        [btn('⯇', 'Left', '.uno:LeftPara', { on: '.uno:LeftPara' }), btn('≡', 'Center', '.uno:CenterPara', { on: '.uno:CenterPara' }),
          btn('⯈', 'Right', '.uno:RightPara', { on: '.uno:RightPara' }), btn('☰', 'Justify', '.uno:JustifyPara', { on: '.uno:JustifyPara' }),
          btn('¶', 'Formatting marks', '.uno:ControlCodes', { on: '.uno:ControlCodes' }), { k: 'spacing' },
          { k: 'color', cmd: '.uno:BackgroundColor', prop: 'BackgroundColor', highlight: true, none: true, title: 'Shading' }],
      ] }] },
      { title: 'g_styles', items: [{ k: 'styles' }] },
      { title: 'g_editing', items: [act('🔍', 'find', 'find'), big('▤', 'select_all', '.uno:SelectAll')] },
    ],
  },
  {
    id: 'insert', title: 'tab_insert', groups: [
      { title: 'g_pages', items: [big('⤓', 'insert_pagebreak', '.uno:InsertPagebreak')] },
      { title: 'g_tables', items: [{ k: 'tablegrid' }, { k: 'stack', rows: [
        [btn('+≡', 'table_row_add', '.uno:InsertRowsAfter'), btn('+⫼', 'table_col_add', '.uno:InsertColumnsAfter')],
        [btn('−≡', 'table_row_del', '.uno:DeleteRows'), btn('−⫼', 'table_col_del', '.uno:DeleteColumns')],
      ] }, big('⊞', 'merge_cells', '.uno:MergeCells')] },
      { title: 'g_illustrations', items: [act('🖼', 'insert_picture', 'picture'), { k: 'shapes' }, act('A▭', 'text_box', 'shape', { cls: '' })] },
      { title: 'g_symbols', items: [{ k: 'symbols' }] },
      { title: 'g_links', items: [act('🔗', 'link', 'link')] },
      { title: 'g_headfoot', items: [act('▔', 'header', 'header'), act('▁', 'footer', 'footer'), big('#', 'page_number', '.uno:InsertPageNumberField')] },
      { title: 'g_text', items: [big('📅', 'date_time', '.uno:InsertDateField')] },
    ],
  },
  {
    id: 'layout', title: 'tab_layout', groups: [
      { title: 'g_page_setup', items: [
        { k: 'menu', icon: '▥', label: 'margins', entries: [['margins_normal', 'margins', 'normal'], ['margins_narrow', 'margins', 'narrow'], ['margins_moderate', 'margins', 'moderate'], ['margins_wide', 'margins', 'wide'], ['margins_custom', 'margins', 'custom']] },
        { k: 'menu', icon: '⟳', label: 'orientation', entries: [['portrait', 'orientation', 'portrait'], ['landscape', 'orientation', 'landscape']] },
        { k: 'menu', icon: '📄', label: 'page_size', entries: ['A3', 'A4', 'A5', 'B5', 'Letter', 'Legal'].map((n) => [n, 'pagesize', n] as [string, string, string]) },
      ] },
      { title: 'g_indent_spacing', items: [{ k: 'stack', rows: [
        [btn('⇤', 'indent', '.uno:DecrementIndent'), btn('⇥', 'indent', '.uno:IncrementIndent')],
        [btn('−', 'para_spacing', '.uno:ParaspaceDecrease'), btn('+', 'para_spacing', '.uno:ParaspaceIncrease'), { k: 'spacing' }],
      ] }] },
      { title: 'g_pages', items: [big('⤓', 'insert_pagebreak', '.uno:InsertPagebreak'), big('⫼', 'column_break', '.uno:InsertColumnBreak')] },
    ],
  },
  { id: 'refs', title: 'tab_refs', groups: [{ title: 'g_footnotes', items: [big('¹', 'footnote', '.uno:InsertFootnote'), big('¹¹', 'endnote', '.uno:InsertEndnote')] }] },
  {
    id: 'review', title: 'tab_review', groups: [
      { title: 'g_proofing', items: [act('✔', 'spell_check', 'spell'), act('123', 'word_count', 'wordcount')] },
      { title: 'g_changes', items: [big('✎', 'track_changes', '.uno:TrackChanges', { on: '.uno:TrackChanges' }), big('✓', 'accept_all', '.uno:AcceptAllTrackedChanges'), big('✗', 'reject_all', '.uno:RejectAllTrackedChanges')] },
    ],
  },
  {
    id: 'view', title: 'tab_view', groups: [
      { title: 'g_show', items: [big('¶', 'formatting_marks', '.uno:ControlCodes', { on: '.uno:ControlCodes' })] },
      { title: 'g_zoom', items: [act('＋', 'zoom_in', 'zoomin'), act('－', 'zoom_out', 'zoomout'), act('↔', 'zoom_fit', 'zoomfit')] },
    ],
  },
  {
    id: 'table', title: 'tab_table', context: 'table', groups: [
      { title: 'g_rows_cols', items: [big('⤒≡', 'tbl_row_above', '.uno:InsertRowsBefore'), big('⤓≡', 'tbl_row_below', '.uno:InsertRowsAfter'), big('⇤⫼', 'tbl_col_left', '.uno:InsertColumnsBefore'), big('⇥⫼', 'tbl_col_right', '.uno:InsertColumnsAfter')] },
      { title: 'g_cells', items: [big('⊞', 'merge_cells', '.uno:MergeCells'), act('⫼', 'tbl_split_cols', 'splitcols'), act('≡', 'tbl_split_rows', 'splitrows'), big('▦', 'tbl_select_table', '.uno:SelectTable'),
        { k: 'color', cmd: '.uno:BackgroundColor', prop: 'BackgroundColor', highlight: true, none: false, title: 'tbl_cell_color' }] },
      { title: 'g_cell_size', items: [big('↔', 'tbl_autofit', '.uno:SetOptimalColumnWidth'), big('↕', 'tbl_fit_rows', '.uno:SetOptimalRowHeight'), big('⫼', 'tbl_even_cols', '.uno:DistributeColumns'), big('≡', 'tbl_even_rows', '.uno:DistributeRows')] },
      { title: 'g_cell_align', items: [big('⤒', 'tbl_valign_top', '.uno:CellVertTop'), big('↕', 'tbl_valign_center', '.uno:CellVertCenter'), big('⤓', 'tbl_valign_bottom', '.uno:CellVertBottom')] },
      { title: 'g_table_borders', items: [{ k: 'borders' }] },
      { title: 'g_table_styles', items: [{ k: 'tablelooks' }] },
      { title: 'g_table_delete', items: [big('−≡', 'tbl_delete_row', '.uno:DeleteRows'), big('−⫼', 'tbl_delete_col', '.uno:DeleteColumns'), big('🗑', 'tbl_delete_table', '.uno:DeleteTable')] },
    ],
  },
  {
    id: 'picture', title: 'tab_picture', context: 'picture', groups: [
      { title: 'g_wrap', items: [big('▤', 'wrap_inline', '.uno:SetAnchorToCharAsChar'), big('▥', 'wrap_square', '.uno:WrapOn'), big('◈', 'wrap_tight', '.uno:WrapContour'), big('▬', 'wrap_topbottom', '.uno:WrapOff'), big('⬒', 'wrap_front', '.uno:WrapThrough'), big('⬓', 'wrap_behind', '.uno:SetObjectToBackground')] },
      { title: 'g_arrange', items: [big('⬒', 'bring_front', '.uno:BringToFront'), big('⬓', 'send_back', '.uno:SendToBack'),
        { k: 'stack', rows: [[btn('↺', 'rotate', '.uno:RotateLeft'), btn('↻', 'rotate', '.uno:RotateRight')], [btn('⇋', 'flip', '.uno:FlipHorizontal'), btn('⇅', 'flip', '.uno:FlipVertical')]] }] },
      { title: 'g_anchor', items: [big('⚓', 'anchor_para', '.uno:SetAnchorToPara'), big('▭', 'anchor_page', '.uno:SetAnchorToPage')] },
      { title: 'g_shape_style', items: [{ k: 'color', cmd: '.uno:FillColor', prop: 'FillColor', highlight: true, none: false, title: 'shape_fill' }, { k: 'color', cmd: '.uno:XLineColor', prop: 'XLineColor', none: false, title: 'shape_outline' }] },
      { title: 'g_edit_picture', items: [big('🗑', 'delete', '.uno:Delete')] },
    ],
  },
];

/** Every UNO command the ribbon shows state for (the host registers listeners for these). */
export function watchedCommands(): string[] {
  const ids = new Set<string>(['.uno:Undo', '.uno:Redo', '.uno:Cut', '.uno:Copy', '.uno:Paste', '.uno:CharFontName', '.uno:FontHeight', '.uno:StyleApply', '.uno:DeleteTable', '.uno:SetAnchorToPage', '.uno:RotateLeft', '.uno:Delete', '.uno:EditDoc', '.uno:Zoom', '.uno:LanguageStatus', '.uno:SpellOnline']);
  const walk = (it: Item) => { if (it.k === 'btn') { if (it.on) ids.add(it.on); if (it.cmd) ids.add(it.cmd); } else if (it.k === 'stack') it.rows.flat().forEach(walk); };
  TABS.forEach((tab) => tab.groups.forEach((g) => g.items.forEach(walk)));
  return [...ids];
}

/** All `.uno:` commands used by buttons; e2e probes them against the engine to catch typos. */
export function allCommands(): string[] {
  const ids = new Set<string>();
  const walk = (it: Item) => { if (it.k === 'btn' && it.cmd) ids.add(it.cmd); else if (it.k === 'color') ids.add(it.cmd); else if (it.k === 'stack') it.rows.flat().forEach(walk); };
  TABS.forEach((tab) => tab.groups.forEach((g) => g.items.forEach(walk)));
  return [...ids];
}

const hex = (c: number) => '#' + c.toString(16).padStart(6, '0');
const label = (key: string) => { const s = t(key); return s === key ? key : s; };

export class Ribbon {
  readonly el = h('div.ribbon');
  private tabsEl = h('div.ribbon-tabs', { role: 'tablist' });
  private panel = h('div.ribbon-panel', { role: 'tabpanel' });
  private tab = 'start';
  private context: Context = 'text';
  private refreshers: Array<() => void> = [];
  collapsed = false;

  constructor(private host: RibbonHost, private compact: boolean) {
    this.el.append(this.tabsEl, this.panel);
    this.build();
  }

  setContext(c: Context) {
    if (c === this.context) return;
    this.context = c;
    // contextual tabs appear when their object is selected (like Word) and the view follows
    if (c !== 'text') this.tab = c; else if (this.tab === 'table' || this.tab === 'picture') this.tab = 'start';
    this.build();
  }

  private visibleTabs() { return TABS.filter((tab) => !tab.context || tab.context === this.context); }

  private build() {
    clear(this.tabsEl);
    for (const tab of this.visibleTabs()) {
      this.tabsEl.append(h('button.tab' + (tab.id === this.tab ? '.active' : '') + (tab.context ? '.contextual' : ''), {
        type: 'button', role: 'tab', 'aria-selected': tab.id === this.tab, 'data-tab': tab.id,
        onclick: () => { this.tab = tab.id; this.build(); },
      }, label(tab.title)));
    }
    clear(this.panel);
    this.refreshers = [];
    const tab = TABS.find((x) => x.id === this.tab) ?? TABS[0];
    for (const g of tab.groups) {
      this.panel.append(h('div.group', null, h('div.group-items', null, ...g.items.map((i) => this.item(i))), h('div.group-title', null, label(g.title))));
    }
    this.refresh();
  }

  refresh() { this.refreshers.forEach((r) => r()); }

  private on(id: string) { return this.host.state(id)?.value === true; }
  private enabled(cmd?: string) { return !cmd || this.host.state(cmd)?.enabled !== false; }

  private item(it: Item): HTMLElement {
    const e = this.host.engine;
    switch (it.k) {
      case 'stack': return h('div.stack', null, ...it.rows.map((row) => h('div.row', null, ...row.map((i) => this.item(i)))));
      case 'btn': {
        const b = h('button.rb' + (it.big ? '.big' : '') + (it.cls ? '.' + it.cls : ''), {
          type: 'button', title: label(it.title), 'aria-label': label(it.title),
          onclick: () => { if (it.cmd) e.dispatch(it.cmd, it.args); else if (it.act) this.host.act(it.act); },
        }, h('span.ic', null, it.icon), it.big ? h('span.lb', null, label(it.label ?? it.title)) : null);
        // keep keyboard focus (and the caret) in the document: buttons must not steal it
        b.addEventListener('mousedown', (ev: Event) => ev.preventDefault());
        this.refreshers.push(() => {
          if (it.on) { const on = this.on(it.on); b.classList.toggle('on', on); b.setAttribute('aria-pressed', String(on)); }
          if (it.cmd) b.disabled = !this.enabled(it.cmd);
        });
        return b;
      }
      case 'font': {
        const sel = h('select.combo.font', { 'aria-label': t('g_font'), title: t('g_font') });
        const add = (name: string, group?: string) => { const o = h('option', { value: name }, name); (group ? sel.querySelector(`optgroup[label="${group}"]`)! : sel).append(o); };
        sel.append(h('optgroup', { label: 'Word' }), h('optgroup', { label: 'Free' }));
        WORD_FONTS.forEach((f) => add(f, 'Word')); FREE_FONTS.forEach((f) => add(f, 'Free'));
        sel.addEventListener('change', () => { e.dispatch('.uno:CharFontName', [{ n: 'CharFontName.FamilyName', v: sel.value }]); });
        this.refreshers.push(() => {
          const v = this.host.state('.uno:CharFontName')?.value;
          if (typeof v === 'string' && v) { if (![...sel.options].some((o) => o.value === v)) sel.append(h('option', { value: v }, v)); sel.value = v; }
        });
        return sel;
      }
      case 'size': {
        const sel = h('select.combo.size', { 'aria-label': 'Size', title: 'Size' });
        SIZES.forEach((s) => sel.append(h('option', { value: String(s) }, String(s))));
        sel.addEventListener('change', () => { e.dispatch('.uno:FontHeight', [{ n: 'FontHeight.Height', v: Number(sel.value), t: 'float' }]); });
        this.refreshers.push(() => {
          const v = this.host.state('.uno:FontHeight')?.value;
          if (typeof v === 'number' && v > 0) { const s = String(Math.round(v)); if (![...sel.options].some((o) => o.value === s)) sel.append(h('option', { value: s }, s)); sel.value = s; }
        });
        return sel;
      }
      case 'styles': {
        const row = h('div.styles');
        for (const [key, name] of STYLES) {
          row.append(h('button.style-card', { type: 'button', onclick: () => e.dispatch('.uno:StyleApply', [{ n: 'Style', v: name }, { n: 'FamilyName', v: 'ParagraphStyles' }]), onmousedown: (ev: Event) => ev.preventDefault() }, h('span', { class: 'sample ' + name.replace(' ', '') }, label(key))));
        }
        return row;
      }
      case 'color': {
        const bar = h('span.swatch-bar', { style: { background: it.highlight ? '#ffc000' : '#c00000' } });
        const b = h('button.rb.color', { type: 'button', title: label(it.title), 'aria-label': label(it.title), onmousedown: (ev: Event) => ev.preventDefault() }, h('span.ic', null, it.highlight ? '🖍' : 'A'), bar);
        b.addEventListener('click', () => {
          const pop = h('div.palette');
          const pick = (c: number) => { bar.style.background = c < 0 ? 'transparent' : hex(c); e.dispatch(it.cmd, [{ n: it.prop, v: c }]); close(); };
          if (it.none) pop.append(h('button.swatch.none', { type: 'button', title: t('no_color'), onclick: () => pick(-1) }, '∅'));
          for (const c of PALETTE) pop.append(h('button.swatch', { type: 'button', style: { background: hex(c) }, 'aria-label': hex(c), onclick: () => pick(c) }));
          const close = popup(b, pop);
        });
        return b;
      }
      case 'tablegrid': return this.tableGrid();
      case 'shapes': return this.shapes();
      case 'symbols': {
        const b = h('button.rb.big', { type: 'button', onmousedown: (ev: Event) => ev.preventDefault() }, h('span.ic', null, 'Ω'), h('span.lb', null, t('symbol')));
        b.addEventListener('click', () => {
          const grid = h('div.symbols');
          const close = popup(b, grid);
          for (const ch of SYMBOLS) grid.append(h('button.sym', { type: 'button', onclick: () => { close(); this.host.act('type', ch); } }, ch));
        });
        return b;
      }
      case 'spacing': {
        const b = h('button.rb', { type: 'button', title: t('line_spacing'), 'aria-label': t('line_spacing'), onmousedown: (ev: Event) => ev.preventDefault() }, h('span.ic', null, '↕≡'));
        b.addEventListener('click', () => {
          const list = h('div.menu');
          const close = popup(b, list);
          for (const [lab, cmd] of [['1.0', '.uno:SpacePara1'], ['1.5', '.uno:SpacePara15'], ['2.0', '.uno:SpacePara2']]) list.append(h('button.menu-item', { type: 'button', onclick: () => { close(); e.dispatch(cmd); } }, lab));
        });
        return b;
      }
      case 'menu': return this.menuButton(it.icon, label(it.label), (list, close) => {
        for (const [text, action, arg] of it.entries) list.append(h('button.menu-item', { type: 'button', onclick: () => { close(); this.host.act(action, arg); } }, label(text)));
      });
      case 'borders': return this.borders();
      case 'tablelooks': return this.looks();
    }
  }

  private menuButton(icon: string, text: string, fill: (list: HTMLElement, close: () => void) => void): HTMLElement {
    const b = h('button.rb.big', { type: 'button', onmousedown: (ev: Event) => ev.preventDefault() }, h('span.ic', null, icon), h('span.lb', null, text));
    b.addEventListener('click', () => { const list = h('div.menu'); const close = popup(b, list); fill(list, close); });
    return b;
  }

  private tableGrid(): HTMLElement {
    return this.menuButton('▦', t('insert_table'), (list, close) => {
      const N = 8;
      const info = h('div.grid-info', null, '1 × 1');
      const grid = h('div.tgrid', { style: { gridTemplateColumns: `repeat(${N}, 1fr)` } });
      const cells: HTMLElement[] = [];
      const mark = (c: number, r: number) => { cells.forEach((x, i) => x.classList.toggle('hot', i % N <= c && Math.floor(i / N) <= r)); info.textContent = `${c + 1} × ${r + 1}`; };
      for (let i = 0; i < N * N; i++) {
        const c = i % N, r = Math.floor(i / N);
        const cell = h('button.tcell', { type: 'button', 'aria-label': `${c + 1} × ${r + 1}`, onpointerenter: () => mark(c, r), onfocus: () => mark(c, r), onclick: () => { close(); this.host.engine.dispatch('.uno:InsertTable', [{ n: 'Columns', v: c + 1 }, { n: 'Rows', v: r + 1 }]); } });
        cells.push(cell); grid.append(cell);
      }
      list.append(grid, info);
    });
  }

  private shapes(): HTMLElement {
    return this.menuButton('◇', t('shapes'), (list, close) => {
      for (const [title, items] of SHAPE_GROUPS) {
        list.append(h('div.menu-title', null, t(title)));
        const row = h('div.shape-row');
        for (const [name, id] of items) row.append(h('button.shape', { type: 'button', title: t(name), 'aria-label': t(name), onclick: () => { close(); this.host.act('shape', id); } }, SHAPE_GLYPH[id.split('.')[1]] ?? '◇'));
        list.append(row);
      }
    });
  }

  private borders(): HTMLElement {
    return this.menuButton('▦', t('g_table_borders'), (list, close) => {
      for (const [key, preset] of [['b_all', 'all'], ['b_outside', 'outside'], ['b_inside', 'inside'], ['b_none', 'none'], ['b_top', 'top'], ['b_bottom', 'bottom'], ['b_left', 'left'], ['b_right', 'right'], ['b_inside_h', 'inside_h'], ['b_inside_v', 'inside_v']]) {
        list.append(h('button.menu-item', { type: 'button', onclick: () => { close(); this.host.act('border', preset); } }, t(key)));
      }
      list.append(h('div.menu-title', null, t('b_style')));
      for (const [key, v] of [['ls_single', 'single'], ['ls_dashed', 'dashed'], ['ls_dotted', 'dotted'], ['ls_double', 'double']]) list.append(h('button.menu-item', { type: 'button', onclick: () => { close(); this.host.act('borderstyle', v); } }, t(key)));
      list.append(h('div.menu-title', null, t('b_width')));
      for (const [lab, sz] of [['0,5 pt', 4], ['1 pt', 8], ['1,5 pt', 12], ['2,25 pt', 18], ['3 pt', 24]] as const) list.append(h('button.menu-item', { type: 'button', onclick: () => { close(); this.host.act('borderwidth', String(sz)); } }, lab));
      list.append(h('div.menu-title', null, t('b_color')));
      const row = h('div.palette');
      for (const c of ['000000', '595959', 'A6A6A6', 'C00000', 'ED7D31', 'FFC000', '70AD47', '2F5597', '7030A0']) row.append(h('button.swatch', { type: 'button', style: { background: '#' + c }, 'aria-label': '#' + c, onclick: () => { close(); this.host.act('bordercolor', c); } }));
      list.append(row);
    });
  }

  private looks(): HTMLElement {
    return this.menuButton('🎨', t('g_table_styles'), (list, close) => {
      for (const key of ['tl_grid', 'tl_grid_gray', 'tl_blue', 'tl_green', 'tl_orange', 'tl_striped', 'tl_minimal', 'tl_dark']) {
        list.append(h('button.menu-item', { type: 'button', onclick: () => { close(); this.host.act('look', key); } }, t(key)));
      }
    });
  }
}
