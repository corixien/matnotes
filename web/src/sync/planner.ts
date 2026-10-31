/** What to do with one document that is already linked to a cloud document (port of SyncPlanner.kt). */
export type Action =
  | 'NOTHING'
  | 'PUSH'          // only the local copy changed: upload
  | 'PULL'          // only the cloud copy changed: replace the local file
  | 'CONFLICT'      // both changed: the cloud version wins at the original name, the local version is kept as a copy
  | 'DELETE_REMOTE' // deleted here, unchanged in the cloud: delete in the cloud
  | 'DELETE_LOCAL'  // deleted in the cloud, unchanged here: delete the local file
  | 'FORGET'        // deleted on both sides: drop the link
  | 'DETACH'        // deleted in the cloud but edited here: keep the file and upload it again as a new document
  | 'REDOWNLOAD';   // deleted here but changed in the cloud afterwards: bring the cloud version back

/** The decision table of the sync, free of any I/O so it can be tested exhaustively. */
export function plan(localExists: boolean, localChanged: boolean, remoteDeleted: boolean, remoteChanged: boolean): Action {
  if (!localExists) {
    if (remoteDeleted) return 'FORGET';
    return remoteChanged ? 'REDOWNLOAD' : 'DELETE_REMOTE';
  }
  if (remoteDeleted) return localChanged ? 'DETACH' : 'DELETE_LOCAL';
  if (localChanged && remoteChanged) return 'CONFLICT';
  if (localChanged) return 'PUSH';
  if (remoteChanged) return 'PULL';
  return 'NOTHING';
}
