// Wires account + sync engine into the app: when to sync (sign-in, coming back to the tab, 5 s after a save, button),
// what the home screen shows (shared folders, invitation badge).
import type { App } from './app';
import { folderActive, store } from './store';
import { Account } from './sync/account';
import { SyncApi } from './sync/api';
import { SyncEngine } from './sync/engine';

export class Cloud {
  readonly account = new Account(store, new SyncApi());
  readonly engine: SyncEngine;
  private timer = 0;
  private lastSync = 0;

  constructor(private app: App) {
    this.engine = new SyncEngine(store, this.account, (dir, name) => app.isOpen(dir, name));
    this.account.onSignedOut = async () => { await this.engine.reset(); await app.refreshHome(); };
    this.account.subscribe((s) => { if (s) void this.syncNow(); app.renderHome(); });
    this.engine.subscribe(() => app.renderHome());
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible' && this.account.loggedIn && Date.now() - this.lastSync > 60_000) void this.syncNow();
    });
  }

  async init() {
    await this.account.init();
    await this.engine.load();
    if (this.account.loggedIn) void this.syncNow();
  }

  /** Active shared folders (the personal folder is the main list). */
  sharedFolders() { return this.engine.folders.filter((f) => !f.personal && folderActive(f)).map((f) => ({ dir: f.localDir, name: f.name })); }
  invitations() { return this.engine.folders.filter((f) => f.status === 'invited' && !f.deleted); }

  async syncNow() {
    if (!this.account.loggedIn) return;
    this.lastSync = Date.now();
    await this.engine.sync();
    await this.app.refreshHome();
  }

  /** After a save: sync shortly after, so quick successive saves go out together. */
  schedule() {
    if (!this.account.loggedIn) return;
    clearTimeout(this.timer);
    this.timer = window.setTimeout(() => void this.syncNow(), 5000);
  }
}
