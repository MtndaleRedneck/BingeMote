package com.bingemote

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/** 1.9 settings migrations on existing installs: the removed toggles leave no
 * stale keys, and the retired cancel-style setting is removed. */
class StoreMigrationTest {
    @get:Rule val failureDump = FailureDumpRule()

    @Test
    fun removedTogglesAndCancelStyleAreCleared() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        // Hermetic: other tests exercise the cancel-style rig extra, which
        // persists a real value; the default check needs a clean file.
        prefs.edit().clear().commit()
        prefs.edit().putBoolean("seekOnTop", false).putBoolean("autoReconnect", false).putString("cancelStyle", "ring").commit()
        Store(ctx) // construction runs the migration
        assertFalse("stale seekOnTop key survived", prefs.contains("seekOnTop"))
        assertFalse("stale autoReconnect key survived", prefs.contains("autoReconnect"))
        assertFalse("stale cancelStyle key survived", prefs.contains("cancelStyle"))
    }
}
