// Shared folders: invitations, folders with members, invite flow with key code, create, delete, leave. Mirrors FoldersDialog.kt.
import { errorText } from './errors';
import type { Cloud } from './cloud';
import { clear, h } from './dom';
import { t } from './i18n';
import { ApiException } from './sync/api';
import type { UserInfo } from './sync/engine';
import type { FolderRow } from './store';
import { confirmDialog, dialog, promptDialog } from './ui';

type Member = { userId: string; name: string; role: string; status: string };
const members = (f: FolderRow): Member[] => { try { return JSON.parse(f.members); } catch { return []; } };

function message(e: unknown): string {
  if (e instanceof ApiException && e.code === 'not_found') return t('share_err_no_user');
  if (e instanceof ApiException && e.code === 'already_member') return t('share_err_already');
  return errorText(e);
}

export function foldersDialog(cloud: Cloud): void {
  const body = h('div.folders');
  const d = dialog(t('share_title'), body, [{ label: t('close') }], { wide: true });
  const me = cloud.account.userId;
  let busy = false;
  let error = '';

  const run = async (f: () => Promise<unknown>, afterSync = false) => {
    busy = true; error = ''; render();
    try { await f(); if (afterSync) void cloud.syncNow(); } catch (e) { error = message(e); } finally { busy = false; render(); }
  };

  function render() {
    if (!document.body.contains(d.el)) { stop(); return; }
    clear(body);
    const invitations = cloud.invitations();
    const shared = cloud.engine.folders.filter((f) => f.status === 'active' && !f.deleted && !f.personal);
    body.append(h('p.note', null, t('share_hint')));

    if (invitations.length) {
      body.append(h('h3', null, t('share_invitations')));
      for (const f of invitations) {
        const owner = members(f).find((m) => m.role === 'owner')?.name ?? '';
        body.append(h('div.invite', null,
          h('div.strong', null, f.name), h('div.note', null, t('share_from', owner)),
          h('div.row-actions', null,
            h('button.btn.primary', { type: 'button', disabled: busy, onclick: () => void run(() => cloud.engine.answerInvite(f.id, true), true) }, t('share_accept')),
            h('button.btn', { type: 'button', disabled: busy, onclick: () => void run(() => cloud.engine.answerInvite(f.id, false)) }, t('share_decline')))));
      }
    }

    body.append(h('h3', null, t('share_folders')));
    if (!shared.length) body.append(h('p.note', null, t('share_none')));
    for (const f of shared) {
      const owner = f.role === 'owner';
      body.append(h('div.shared', null,
        h('div.strong', null, f.name),
        ...members(f).map((m) => {
          const suffix = m.status === 'invited' ? ` (${t('share_pending')})` : m.role === 'owner' ? ` (${t('share_owner')})` : '';
          return h('div.member', null, h('span', null, m.name + (m.userId === me ? ' ' + t('share_you') : '') + suffix),
            owner && m.role !== 'owner' ? h('button.btn', { type: 'button', disabled: busy, onclick: () => void run(() => cloud.engine.removeMember(f.id, m.userId)) }, t('share_remove')) : null);
        }),
        h('div.row-actions', null,
          owner ? h('button.btn', { type: 'button', disabled: busy, onclick: () => inviteDialog(cloud, f, () => render()) }, t('share_invite')) : null,
          owner
            ? h('button.btn.danger', { type: 'button', disabled: busy, onclick: async () => { if (await confirmDialog(t('share_delete_title', f.name), t('share_delete_msg'), t('share_delete'), true)) void run(() => cloud.engine.deleteFolder(f.id)); } }, t('share_delete'))
            : h('button.btn', { type: 'button', disabled: busy, onclick: () => void run(() => cloud.engine.removeMember(f.id, me!)) }, t('share_leave')))));
    }
    body.append(
      h('div.row-actions', null, h('button.btn.primary', { type: 'button', disabled: busy, onclick: async () => {
        const name = await promptDialog(t('share_new'), t('share_folder_name'), '', t('share_create'));
        if (name) void run(() => cloud.engine.createSharedFolder(name), true);
      } }, t('share_new'))),
      error ? h('p.error', { role: 'alert' }, error) : null,
      busy ? h('p.note', null, t('acc_working')) : null);
  }

  const stop = cloud.engine.subscribe(render);
  render();
}

/** Two steps: look the person up by e-mail, then confirm their key code before the folder key is handed over. */
function inviteDialog(cloud: Cloud, folder: FolderRow, done: () => void) {
  const email = h('input.field', { type: 'email', 'aria-label': t('acc_email'), autocomplete: 'off', spellcheck: false });
  const body = h('div.form', null, h('label.field-label', null, t('acc_email'), email));
  const err = h('p.error', { role: 'alert' });
  let user: UserInfo | null = null;
  const dlg = dialog(t('share_invite_to', folder.name), h('div', null, body, err), [
    { label: t('cancel') },
    {
      label: t('share_find'), primary: true, keepOpen: true, onClick: async function step() {
        err.textContent = '';
        const btn = dlg.el.querySelector('.primary') as HTMLButtonElement;
        btn.disabled = true;
        try {
          if (!user) {
            user = await cloud.engine.lookupUser(email.value);
            clear(body);
            body.append(h('div.strong', null, user.name), h('div.note', null, email.value.trim()), h('div.note', null, t('share_code')),
              h('code.fingerprint', null, user.fingerprint), h('p.note', null, t('share_code_hint', user.name)));
            btn.textContent = t('share_send_invite');
          } else {
            await cloud.engine.invite(folder.id, user);
            dlg.close(); done();
          }
        } catch (e) { err.textContent = message(e); } finally { btn.disabled = false; }
      },
    },
  ]);
}
