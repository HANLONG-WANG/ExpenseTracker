package com.yohaku.expensetracker.data.repository

import android.content.ContextWrapper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.yohaku.expensetracker.data.command.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CommandScanPreferencesTest {
    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        private val lock = Mutex()
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            lock.withLock { transform(data.value).also { data.value = it } }
    }

    @Test fun readApplyUndoAndConflictsUseAtomicPreferenceReceipts() = runBlocking {
        val storage = MemoryStore()
        val prefs = AppPreferences(ContextWrapper(null), storage)
        for ((key, initial) in listOf("scanAutoCapture" to false, "scanAutoProcess" to true)) {
            val before = JsonPrimitive(initial); val after = JsonPrimitive(!initial)
            assertEquals(before, prefs.commandValues("book").getValue(key))
            prefs.applyCommandPreference("apply-$key", "book", key, before, after)
            assertEquals(after, prefs.commandValues("another-book").getValue(key))
            assertTrue(prefs.commandPreferenceApplied("apply-$key"))
            // Undo consumes the same recorded final -> initial values as the command inverse.
            prefs.applyCommandPreference("undo-$key", "book", key, after, before)
            assertEquals(before, prefs.commandValues("book").getValue(key))
            assertTrue(runCatching { prefs.applyCommandPreference("conflict-$key", "book", key, after, before) }.exceptionOrNull() is IllegalArgumentException)
            assertFalse(prefs.commandPreferenceApplied("conflict-$key"))
            // A recreated preferences owner must not replay an applied effect over later user edits.
            val restarted = AppPreferences(ContextWrapper(null), storage)
            restarted.applyCommandPreference("apply-$key", "book", key, before, after)
            assertEquals(before, restarted.commandValues("book").getValue(key))
        }
        assertFalse(prefs.scanAutoCapture.first()); assertTrue(prefs.scanAutoProcess.first())
    }

    @Test fun commandsRequireBooleanAndFoldUndoToOriginalValue() {
        val registry = CommandRegistry()
        for (key in listOf("scanAutoCapture", "scanAutoProcess")) {
            val adapter = registry.adapter("preferences.$key")
            assertEquals("post-commit", adapter.capability.mode)
            adapter.validate(buildJsonObject { put("enabled", true) })
            assertTrue(runCatching { adapter.validate(buildJsonObject {}) }.isFailure)
            assertTrue(runCatching { adapter.validate(buildJsonObject { put("enabled", "yes") }) }.isFailure)
            fun restore(id: String, before: Boolean, after: Boolean) = CommandStep(id, "local.restorePreference", 1,
                encoded(RestorePreferenceInput(key, JsonPrimitive(before), JsonPrimitive(after))).jsonObject)
            val reverse = preferenceInverseSteps(listOf(restore("last", false, true), restore("first", true, false)))
            assertEquals(1, reverse.size)
            val input = commandJson.decodeFromJsonElement<RestorePreferenceInput>(reverse.single().input)
            assertEquals(JsonPrimitive(false), input.before); assertEquals(JsonPrimitive(false), input.after)
        }
    }
}
