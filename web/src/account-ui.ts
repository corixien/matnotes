// Account dialog (sign in, register, recover, recovery key, signed-in page, password change, delete). Mirrors AccountDialog.kt.
import { h, clear } from './dom';
import type { Cloud } from './cloud';
import { foldersDialog } from './folders-ui';
import { t } from './i18n';
import type { SyncStatus } from './sync/engine';
import { dialog, toast } from './ui';

import { errorText, type Page } from './errors';

const field = (label: string, o: { type?: string; autocomplete?: string; mono?: boolean; value?: string } = {}) => {
  const input = h('input.field' + (o.mono ? '.mono' : ''), { type: o.type ?? 'text', autocomplete: o.autocomplete, 'aria-label': label, spellcheck: false, autocapitalize: 'off', value: o.value ?? '' });
  return { input, label: h('label.field-label', null, label, input) };
};

/** Opens the account dialog; returns close(). */
export function accountDialog(cloud: Cloud): () => void {
  const body = h('div.account');
  const d = dialog(t('acc_title'), body, [], { wide: false });
  const close = () => { unsub(); d.close(); };
  const unsub = cloud.account.subscribe(() => { if (!busyPage) render(); });
  let busyPage = false; // while a flow runs (or the recovery key is shown) the page must not be re-rendered under it
  // closing via Escape / backdrop is handled by dialog(); detect removal to unsubscribe
  new MutationObserver((_, o) => { if (!document.body.contains(d.el)) { unsub(); o.disconnect(); } }).observe(document.body, { childList: true });

  const render = () => (cloud.account.loggedIn ? signedIn() : signedOut('signin'));

  function signedOut(page: Page) {
    clear(body);
    const name = field(t('acc_name'), { autocomplete: 'name' });
    const email = field(t('acc_email'), { type: 'email', autocomplete: 'username' });
    const key = field(t('acc_recovery_key'), { mono: true });
    const pw = field(page === 'recover' ? t('acc_new_password') : t('acc_password'), { type: 'password', autocomplete: page === 'signin' ? 'current-password' : 'new-password' });
    const rep = field(t('acc_password_repeat'), { type: 'password', autocomplete: 'new-password' });
    const err = h('p.error', { role: 'alert' });
    const status = h('span.working', { 'aria-live': 'polite' });
    const titleKey = page === 'signin' ? 'acc_sign_in' : page === 'register' ? 'acc_register' : 'acc_recover_title';
    const go = h('button.btn.primary', { type: 'button' }, t(titleKey));

    const run = async (f: () => Promise<void>) => {
      go.disabled = true; err.textContent = ''; status.textContent = t('acc_working'); busyPage = true;
      try { await f(); } catch (e) { err.textContent = errorText(e, page); } finally { go.disabled = false; status.textContent = ''; busyPage = false; }
    };
    const submit = () => {
      const e = email.input.value.trim(), p = pw.input.value;
      if (page === 'signin') { if (!e || !p) return void (err.textContent = t('acc_err_fields')); return void run(async () => { await cloud.account.login(e, p); busyPage = false; close(); }); }
      if (page === 'register' && !name.input.value.trim()) return void (err.textContent = t('acc_err_fields'));
      if (page === 'recover' && !key.input.value.trim()) return void (err.textContent = t('acc_err_fields'));
      if (!e || !p) return void (err.textContent = t('acc_err_fields'));
      if (p.length < 10) return void (err.textContent = t('acc_err_password_short'));
      if (p !== rep.input.value) return void (err.textContent = t('acc_err_password_mismatch'));
      if (page === 'register') return void run(async () => { busyPage = true; const rk = await cloud.account.register(name.input.value, e, p); recoveryPage(rk); });
      return void run(async () => { await cloud.account.recover(e, key.input.value, p); busyPage = false; close(); });
    };
    go.addEventListener('click', submit);
    const form = h('form.form', { onsubmit: (ev: Event) => { ev.preventDefault(); submit(); } },
      h('h2.page-title', null, t(titleKey)),
      page === 'register' ? name.label : null, email.label, page === 'recover' ? key.label : null, pw.label, page !== 'signin' ? rep.label : null,
      page === 'register' ? h('p.note', null, t('acc_privacy_note')) : null, err,
      h('div.row-actions', null, go, status),
      h('div.row-links', null,
        page === 'signin' ? h('button.btn', { type: 'button', onclick: () => signedOut('register') }, t('acc_register')) : h('button.btn', { type: 'button', onclick: () => signedOut('signin') }, t('acc_back')),
        page === 'signin' ? h('button.btn', { type: 'button', onclick: () => signedOut('recover') }, t('acc_forgot')) : null),
      h('div.row-links', null, h('button.btn', { type: 'button', onclick: close }, t('close'))));
    form.querySelector('button[type=submit]');
    body.append(form);
    (page === 'register' ? name : email).input.focus();
  }

  function recoveryPage(rk: string) {
    clear(body);
    const confirm = h('input', { type: 'checkbox', id: 'rk-confirm' });
    const next = h('button.btn.primary', { type: 'button', disabled: true, onclick: () => { busyPage = false; close(); } }, t('acc_continue'));
    confirm.addEventListener('change', () => { next.disabled = !confirm.checked; });
    body.append(
      h('h2.page-title', null, t('acc_recovery_title')),
      h('p', null, t('acc_recovery_text')),
      h('code.recovery', null, rk),
      h('button.btn', { type: 'button', onclick: async () => { try { await navigator.clipboard.writeText(rk); toast(t('acc_recovery_copied')); } catch { /* clipboard blocked */ } } }, t('acc_recovery_copy')),
      h('label.check', null, confirm, h('span', null, t('acc_recovery_confirm'))),
      h('div.row-actions', null, next));
  }

  function statusText(s: SyncStatus) {
    const time = (n: number) => new Date(n).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    switch (s.kind) {
      case 'running': return [h('p.status', null, t('sync_running'))];
      case 'done': {
        const r = s.result;
        return [h('p.status.ok', null, t('sync_done', time(s.time))), h('p.note', null, t('sync_counts', r.uploaded, r.downloaded, r.deleted)),
          r.conflicts ? h('p.warn', null, t('sync_conflicts', r.conflicts)) : null, ...r.errors.slice(0, 3).map((e) => h('p.error', null, e))];
      }
      case 'failed': return [h('p.error', null, s.offline ? t('acc_err_network') : t('sync_failed', s.message))];
      default: return [h('p.note', null, t('sync_idle'))];
    }
  }

  function signedIn() {
    clear(body);
    const me = cloud.account.state!;
    const status = h('div.sync-status', null);
    const paint = () => { clear(status); for (const n of statusText(cloud.engine.status)) if (n) status.append(n); syncBtn.disabled = cloud.engine.status.kind === 'running'; };
    const syncBtn = h('button.btn.primary', { type: 'button', onclick: () => void cloud.syncNow() }, t('sync_now'));
    const stop = cloud.engine.subscribe(paint);
    new MutationObserver((_, o) => { if (!document.body.contains(d.el)) { stop(); o.disconnect(); } }).observe(document.body, { childList: true });
    body.append(
      h('h2.page-title', null, t('acc_title')),
      h('p.name', null, me.name), h('p.note', null, t('acc_signed_in_as', me.email)),
      status,
      h('button.btn', { type: 'button', onclick: () => { close(); foldersDialog(cloud); } }, t('share_open')),
      h('div.row-actions', null, syncBtn, h('button.btn', { type: 'button', onclick: async () => { await cloud.account.logout(); close(); } }, t('acc_sign_out'))),
      h('div.row-links', null,
        h('button.btn', { type: 'button', onclick: () => passwordDialog(cloud) }, t('acc_change_password')),
        h('button.btn.danger', { type: 'button', onclick: () => deleteDialog(cloud, close) }, t('acc_delete_account'))),
      h('div.row-links', null, h('button.btn', { type: 'button', onclick: close }, t('close'))));
    paint();
  }

  render();
  return close;
}

function passwordDialog(cloud: Cloud) {
  const cur = field(t('acc_current_password'), { type: 'password', autocomplete: 'current-password' });
  const next = field(t('acc_new_password'), { type: 'password', autocomplete: 'new-password' });
  const rep = field(t('acc_password_repeat'), { type: 'password', autocomplete: 'new-password' });
  const err = h('p.error', { role: 'alert' });
  dialog(t('acc_change_password'), h('div.form', null, cur.label, next.label, rep.label, err), [
    { label: t('cancel') },
    {
      label: t('acc_save'), primary: true, onClick: async () => {
        err.textContent = '';
        if (!cur.input.value || !next.input.value) { err.textContent = t('acc_err_fields'); return false; }
        if (next.input.value.length < 10) { err.textContent = t('acc_err_password_short'); return false; }
        if (next.input.value !== rep.input.value) { err.textContent = t('acc_err_password_mismatch'); return false; }
        try { await cloud.account.changePassword(cur.input.value, next.input.value); toast(t('acc_password_changed')); } catch (e) { err.textContent = errorText(e); return false; }
      },
    },
  ]);
}

function deleteDialog(cloud: Cloud, closeAccount: () => void) {
  const pw = field(t('acc_password'), { type: 'password', autocomplete: 'current-password' });
  const err = h('p.error', { role: 'alert' });
  dialog(t('acc_delete_title'), h('div.form', null, h('p', null, t('acc_delete_msg')), pw.label, err), [
    { label: t('cancel') },
    {
      label: t('acc_delete_account'), danger: true, onClick: async () => {
        err.textContent = '';
        if (!pw.input.value) { err.textContent = t('acc_err_fields'); return false; }
        try { await cloud.account.deleteAccount(pw.input.value); closeAccount(); } catch (e) { err.textContent = errorText(e); return false; }
      },
    },
  ]);
}
