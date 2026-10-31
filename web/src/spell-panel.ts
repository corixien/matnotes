// Spelling check in place of the ribbon: detects the language, lets the user change it, then walks through the mistakes.
// Mirrors SpellPanel.kt (phases Detecting -> Setup -> Checking -> Review -> Done).
import { clear, h } from './dom';
import type { Engine } from './engine';
import { lang as uiLang, t } from './i18n';
import type { SpellClient } from './spell-client';
import { displayName, forTag, languages, type SpellLang, type SpellProblem } from './spelling';
import { dialog } from './ui';

type Phase = 'detecting' | 'setup' | 'checking' | 'review' | 'done';

export interface SpellEnv {
  engine: Engine;
  spell: SpellClient;
  /** Document language tag (from `.uno:LanguageStatus`, e.g. "de-DE") or null. */
  docTag(): string | null;
  close(): void;
}

/** Whole-word, case-sensitive regular expression search (see AGENTS.md: AlgorithmType2 is a short, TransliterateFlags 0). */
const wordSearch = (word: string, repl: string, command: number) => [
  { n: 'SearchItem.SearchString', v: '\\b' + word + '\\b' },
  { n: 'SearchItem.ReplaceString', v: repl },
  { n: 'SearchItem.Command', v: command },
  { n: 'SearchItem.AlgorithmType2', v: 2, t: 'short' as const },
  { n: 'SearchItem.TransliterateFlags', v: 0 },
];

export function spellPanel(env: SpellEnv): HTMLElement {
  const el = h('div.spell');
  let phase: Phase = 'detecting';
  let lang: SpellLang | null = null;
  let detected = false;
  let text = '';
  let problems: SpellProblem[] = [];
  let total = 0, idx = 0;
  let error: string | null = null;

  const findWord = (w: string) => env.engine.dispatch('.uno:ExecuteSearch', wordSearch(w, '', 0));
  const replaceWord = (from: string, to: string) => env.engine.dispatch('.uno:ExecuteSearch', wordSearch(from, to, 3));

  async function begin() {
    try {
      text = (await env.engine.save('txt').then((b) => new TextDecoder().decode(b))).replace(/^﻿/, '');
      lang = await env.spell.detect(text, env.docTag());
      detected = true;
    } catch (e) {
      error = t('spell_text_error'); lang = forTag('en') ?? null;
    }
    phase = 'setup'; render();
  }

  async function start() {
    if (!lang) return;
    phase = 'checking'; error = null; render();
    try {
      problems = await env.spell.check(lang, text);
      total = problems.length; idx = 0;
      phase = problems.length ? 'review' : 'done';
      if (phase === 'review') findWord(problems[0].word);
    } catch (e) { console.error('spell check failed:', (e as Error).stack || (e as Error).message); error = t('spell_error'); phase = 'setup'; }
    render();
  }

  function drop(i: number) {
    problems.splice(i, 1);
    if (!problems.length) phase = 'done'; else { idx = Math.min(i, problems.length - 1); findWord(problems[idx].word); }
    render();
  }

  function pick() {
    const list = h('div.lang-list');
    const d = dialog(t('spell_pick_language'), list, [{ label: t('cancel') }]);
    const all = [...languages].sort((a, b) => displayName(a, uiLang).localeCompare(displayName(b, uiLang), uiLang));
    for (const l of all) {
      list.append(h('button.menu-item' + (l === lang ? '.on' : ''), { type: 'button', onclick: () => { lang = l; detected = false; phase = 'setup'; d.close(); render(); } }, displayName(l, uiLang)));
    }
  }

  const langRow = (showDetected: boolean) => h('button.lang-row', { type: 'button', onclick: pick },
    h('span.small', null, t('spell_language')), h('span.big', null, lang ? displayName(lang, uiLang) : '–'),
    showDetected && detected ? h('span.small.accent', null, t('spell_detected')) : null);

  function render() {
    clear(el);
    el.append(h('div.spell-head', null, h('strong', null, t('spell_title')), h('button.btn', { type: 'button', onclick: env.close }, t('close'))));
    const body = h('div.spell-body');
    el.append(body);
    if (phase === 'detecting' || phase === 'checking') {
      body.append(h('p.working', { role: 'status' }, t(phase === 'detecting' ? 'spell_detecting' : 'spell_checking')));
    } else if (phase === 'setup') {
      body.append(langRow(true), error ? h('p.error', null, error) : null,
        h('button.btn.primary', { type: 'button', disabled: !lang || error === t('spell_text_error'), onclick: () => void start() }, t('spell_start')));
    } else if (phase === 'done') {
      body.append(langRow(false), h('p.strong', null, t(total === 0 ? 'spell_none' : 'spell_done')),
        h('button.btn.primary', { type: 'button', onclick: () => void start() }, t('spell_start')));
    } else {
      const p = problems[idx];
      body.append(langRow(false),
        h('p.note', null, t('spell_word_of', idx + 1, problems.length) + '  ·  ' + t('spell_times', p.count)),
        h('p.misspelled', null, p.word),
        p.suggestions.length
          ? h('div.suggestions', null, ...p.suggestions.map((s) => h('button.btn.suggestion', { type: 'button', onclick: () => { replaceWord(p.word, s); drop(idx); } }, s)))
          : h('p.note', null, t('spell_no_suggestions')),
        h('div.row-actions', null,
          h('button.btn', { type: 'button', disabled: idx === 0, onclick: () => { idx--; findWord(problems[idx].word); render(); } }, '◀'),
          h('button.btn', { type: 'button', onclick: () => { if (idx < problems.length - 1) { idx++; findWord(problems[idx].word); } else phase = 'done'; render(); } }, t('spell_ignore')),
          h('button.btn', { type: 'button', onclick: () => { void env.spell.addUserWord(p.word); drop(idx); } }, t('spell_add'))));
    }
  }

  render();
  void begin();
  return el;
}
