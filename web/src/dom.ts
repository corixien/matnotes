type Child = Node | string | null | undefined | false;
type Attrs = Record<string, any>;

/** Tiny hyperscript: h('div#id.card', { onclick }, 'text', child). Class names after dots; `on*` keys become listeners. */
export function h(spec: string, attrs?: Attrs | null, ...kids: Child[]): any {
  const m = /^([a-z0-9]*)((?:[.#][^.#]+)*)$/i.exec(spec);
  if (!m) throw new Error('bad element spec ' + spec);
  const el = document.createElement(m[1] || 'div');
  const cls: string[] = [];
  for (const part of m[2].match(/[.#][^.#]+/g) ?? []) (part[0] === '#' ? (el.id = part.slice(1)) : cls.push(part.slice(1)));
  if (cls.length) el.className = cls.join(' ');
  for (const [k, v] of Object.entries(attrs ?? {})) {
    if (v === undefined || v === null || v === false) continue;
    if (k.startsWith('on')) el.addEventListener(k.slice(2).toLowerCase(), v);
    else if (k === 'style' && typeof v === 'object') Object.assign(el.style, v);
    else if (k in el && k !== 'list') (el as any)[k] = v;
    else el.setAttribute(k, v === true ? '' : String(v));
  }
  for (const c of kids) if (c) el.append(c);
  return el;
}

/** append() that skips null/false entries (a bare `null` would become the text "null"). */
export const add = (el: Element, ...kids: Child[]) => { for (const k of kids) if (k) el.append(k); return el; };
export const $ = <T extends HTMLElement = HTMLElement>(sel: string, root: ParentNode = document) => root.querySelector(sel) as T | null;
export const clear = (el: Element) => { while (el.firstChild) el.removeChild(el.firstChild); };
