package com.matnotes.app.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncPlannerTest {
    private fun plan(exists: Boolean, localChanged: Boolean, remoteDeleted: Boolean, remoteChanged: Boolean) =
        SyncPlanner.plan(exists, localChanged, remoteDeleted, remoteChanged)

    @Test fun fileExistsCloudAlive() {
        assertEquals(Action.NOTHING, plan(true, false, false, false))
        assertEquals(Action.PUSH, plan(true, true, false, false))
        assertEquals(Action.PULL, plan(true, false, false, true))
        assertEquals(Action.CONFLICT, plan(true, true, false, true))
    }

    @Test fun cloudDeleted() {
        assertEquals(Action.DELETE_LOCAL, plan(true, false, true, true))
        assertEquals(Action.DELETE_LOCAL, plan(true, false, true, false))
        assertEquals(Action.DETACH, plan(true, true, true, true), "edited here, deleted there: nothing may be lost")
        assertEquals(Action.DETACH, plan(true, true, true, false))
    }

    @Test fun deletedOnThisPhone() {
        assertEquals(Action.DELETE_REMOTE, plan(false, false, false, false))
        assertEquals(Action.REDOWNLOAD, plan(false, false, false, true), "the cloud copy changed after the delete: keep the newer content")
        assertEquals(Action.FORGET, plan(false, false, true, true))
        assertEquals(Action.FORGET, plan(false, false, true, false))
    }

    private fun assertEquals(expected: Action, actual: Action, message: String) = org.junit.Assert.assertEquals(message, expected, actual)
}
