import './style.css';
import { App } from './app';
import { Cloud } from './cloud';
import { accountDialog } from './account-ui';

const app = new App(document.getElementById('app')!);
const cloud = new Cloud(app);
app.hooks = {
  onProfile: () => accountDialog(cloud),
  onStored: () => cloud.schedule(),
  badge: () => cloud.invitations().length,
  folders: () => cloud.sharedFolders(),
};
void cloud.init();
(window as any).matnotesApp = app;
(window as any).matnotesCloud = cloud;
