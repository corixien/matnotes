// Popups, modal dialogs, toasts. One popup / one dialog at a time.
import { h, clear } from './dom';
import { t } from './i18n';

let closePopup: (() => void) | null = null;

/** Shows `content` next to `anchor`; closes on outside click, Escape or when `close()` is called. */
export function popup(anchor: HTMLElement, content: HTMLElement): () => void {
  closePopup?.();
  const box = h('div.popup', { role: 'menu' }, content);
  document.body.append(box);
  const place = () => {
    const r = anchor.getBoundingClientRect();
    const bw = box.offsetWidth, bh = box.offsetHeight;
    const below = r.bottom + bh + 8 < innerHeight;
    box.style.left = Math.max(8, Math.min(r.left, innerWidth - bw - 8)) + 'px';
    box.style.top = (below ? r.bottom + 4 : Math.max(8, r.top - bh - 4)) + 'px';
    box.style.maxHeight = Math.max(160, (below ? innerHeight - r.bottom : r.top) - 16) + 'px';
  };
  place();
  const onDown = (e: Event) => { if (!box.contains(e.target as Node) && !anchor.contains(e.target as Node)) close(); };
  const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') close(); };
  const close = () => {
    box.remove();
    document.removeEventListener('pointerdown', onDown, true);
    document.removeEventListener('keydown', onKey, true);
    if (closePopup === close) closePopup = null;
  };
  setTimeout(() => document.addEventListener('pointerdown', onDown, true));
  document.addEventListener('keydown', onKey, true);
  closePopup = close;
  return close;
}

export type DialogButton = { label: string; primary?: boolean; danger?: boolean; keepOpen?: boolean; onClick?: () => void | boolean | Promise<void | boolean> };

/** Modal dialog. Returns `close`. A button whose handler returns `false` keeps the dialog open. */
export function dialog(title: string, body: HTMLElement | string, buttons: DialogButton[], opts: { wide?: boolean } = {}): { close: () => void; el: HTMLElement } {
  const prev = document.activeElement as HTMLElement | null;
  const back = h('div.backdrop');
  const row = h('div.dialog-buttons');
  const box = h('div.dialog' + (opts.wide ? '.wide' : ''), { role: 'dialog', 'aria-modal': 'true', 'aria-label': title },
    h('h2', null, title), typeof body === 'string' ? h('p', null, body) : body, row);
  back.append(box);
  const close = () => { back.remove(); document.removeEventListener('keydown', onKey, true); prev?.focus?.(); };
  const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') { e.stopPropagation(); close(); } };
  for (const b of buttons) {
    const btn = h('button.btn' + (b.primary ? '.primary' : '') + (b.danger ? '.danger' : ''), {
      type: 'button',
      onclick: async () => {
        const r = await b.onClick?.();
        if (r !== false && !b.keepOpen) close();
      },
    }, b.label);
    row.append(btn);
  }
  document.addEventListener('keydown', onKey, true);
  document.body.append(back);
  (box.querySelector('input,select,textarea') as HTMLElement | null)?.focus();
  return { close, el: box };
}

export const confirmDialog = (title: string, msg: string, okLabel: string, danger = false): Promise<boolean> =>
  new Promise((resolve) => {
    dialog(title, msg, [
      { label: t('cancel'), onClick: () => resolve(false) },
      { label: okLabel, primary: !danger, danger, onClick: () => resolve(true) },
    ]);
  });

/** Single-line prompt; resolves to the trimmed text or null. */
export const promptDialog = (title: string, label: string, value = '', okLabel = t('save')): Promise<string | null> =>
  new Promise((resolve) => {
    const input = h('input.field', { type: 'text', value, 'aria-label': label, spellcheck: false });
    const d = dialog(title, h('label.field-label', null, label, input), [
      { label: t('cancel'), onClick: () => resolve(null) },
      { label: okLabel, primary: true, onClick: () => { const v = input.value.trim(); if (!v) return false; resolve(v); } },
    ]);
    input.addEventListener('keydown', (e: KeyboardEvent) => { if (e.key === 'Enter') (d.el.querySelector('.primary') as HTMLElement).click(); });
    input.select();
  });

let toastTimer = 0;
export function toast(msg: string, ms = 3500) {
  document.querySelector('.toast')?.remove();
  const el = h('div.toast', { role: 'status' }, msg);
  document.body.append(el);
  clearTimeout(toastTimer);
  toastTimer = window.setTimeout(() => el.remove(), ms);
}

export { clear };
