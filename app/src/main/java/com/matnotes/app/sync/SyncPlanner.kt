package com.matnotes.app.sync

/** What to do with one document that is already linked to a cloud document. */
enum class Action {
    NOTHING,
    PUSH,          // only the local copy changed: upload
    PULL,          // only the cloud copy changed: replace the local file
    CONFLICT,      // both changed: the cloud version wins at the original name, the local version is kept as a copy
    DELETE_REMOTE, // deleted on this phone, unchanged in the cloud: delete in the cloud
    DELETE_LOCAL,  // deleted in the cloud, unchanged here: delete the local file
    FORGET,        // deleted on both sides: drop the link
    DETACH,        // deleted in the cloud but edited here: keep the file and upload it again as a new document
    REDOWNLOAD,    // deleted on this phone but changed in the cloud afterwards: bring the cloud version back
}

/** The decision table of the sync, free of any I/O so it can be tested exhaustively. */
object SyncPlanner {
    fun plan(localExists: Boolean, localChanged: Boolean, remoteDeleted: Boolean, remoteChanged: Boolean): Action = when {
        !localExists -> when {
            remoteDeleted -> Action.FORGET
            !remoteChanged -> Action.DELETE_REMOTE
            else -> Action.REDOWNLOAD
        }
        remoteDeleted -> if (localChanged) Action.DETACH else Action.DELETE_LOCAL
        localChanged && remoteChanged -> Action.CONFLICT
        localChanged -> Action.PUSH
        remoteChanged -> Action.PULL
        else -> Action.NOTHING
    }
}
