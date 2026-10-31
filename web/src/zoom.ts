// Zoom of the document view: pinch (two fingers), Ctrl + mouse wheel, Ctrl + / - / 0 and the buttons. The browser's own page zoom is
// blocked while a document is open (it resizes the engine's canvas and breaks its layout). Changes are applied at most every 80 ms:
// every zoom step re-lays out and repaints the whole page.
import { h } from './dom';
import type { Engine } from './engine';

export const MIN_ZOOM = 20, MAX_ZOOM = 500;
const STEPS = [20, 30, 40, 50, 60, 75, 90, 100, 110, 125, 150, 175, 200, 300, 400, 500];

export class ZoomControl {
  readonly bar = h('div.zoombar', { role: 'group', 'aria-label': 'Zoom' });
  private label = h('button.zoom-label', { type: 'button', title: '100 %', onclick: () => this.set(100) }, '100 %');
  private pct = 100;
  private sentPct = 0;
  private timer = 0;
  private pinch: { d0: number; pct0: number } | null = null;

  constructor(private stage: HTMLElement, private engine: () => Engine | null, private active: () => boolean) {
    const btn = (text: string, title: string, f: () => void) => h('button.icon-btn', { type: 'button', 'aria-label': title, title, onclick: f }, text);
    this.bar.append(btn('−', 'Zoom −', () => this.step(-1)), this.label, btn('+', 'Zoom +', () => this.step(1)), btn('↔', 'Page width', () => void this.fit()));

    stage.addEventListener('wheel', (e) => {
      if (!e.ctrlKey || !this.active()) return;
      e.preventDefault(); e.stopPropagation(); // also stops the browser's page zoom
      this.set(this.pct * Math.exp(-e.deltaY * (e.deltaMode ? 0.05 : 0.0025)));
    }, { passive: false, capture: true });

    const dist = (t: TouchList) => Math.hypot(t[0].clientX - t[1].clientX, t[0].clientY - t[1].clientY);
    stage.addEventListener('touchstart', (e) => {
      if (e.touches.length === 2 && this.active()) { this.pinch = { d0: dist(e.touches), pct0: this.pct }; e.preventDefault(); e.stopPropagation(); }
    }, { passive: false, capture: true });
    stage.addEventListener('touchmove', (e) => {
      if (!this.pinch || e.touches.length !== 2) return;
      e.preventDefault(); e.stopPropagation(); // the engine must not scroll while two fingers zoom
      this.set(this.pinch.pct0 * (dist(e.touches) / this.pinch.d0));
    }, { passive: false, capture: true });
    const end = (e: TouchEvent) => { if (this.pinch) { if (e.touches.length < 2) this.pinch = null; e.stopPropagation(); } };
    stage.addEventListener('touchend', end, { capture: true });
    stage.addEventListener('touchcancel', end, { capture: true });

    addEventListener('keydown', (e) => {
      if (!(e.ctrlKey || e.metaKey) || !this.active()) return;
      if (e.key === '+' || e.key === '=') { e.preventDefault(); this.step(1); }
      else if (e.key === '-') { e.preventDefault(); this.step(-1); }
      else if (e.key === '0') { e.preventDefault(); this.set(100); }
    }, true);
  }

  /** Reads the view's current zoom (after a document was opened). */
  async sync() { const e = this.engine(); if (!e) return; this.show((await e.zoom()).value); }

  async fit() { const e = this.engine(); if (e) this.show((await e.zoom({ fit: true })).value); }

  step(dir: 1 | -1) {
    const next = dir > 0 ? STEPS.find((s) => s > this.pct + 0.5) : [...STEPS].reverse().find((s) => s < this.pct - 0.5);
    this.set(next ?? (dir > 0 ? MAX_ZOOM : MIN_ZOOM));
  }

  set(pct: number) {
    this.show(Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, pct)));
    if (this.timer) return;
    this.timer = window.setTimeout(() => {
      this.timer = 0;
      const p = Math.round(this.pct);
      if (p !== this.sentPct) { this.sentPct = p; void this.engine()?.zoom({ value: p }); }
    }, 80);
  }

  private show(pct: number) {
    this.pct = pct;
    this.label.textContent = `${Math.round(pct)} %`;
  }
}
