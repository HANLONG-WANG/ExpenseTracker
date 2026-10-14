package com.yohaku.expensetracker.data.repository

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.preferencesDataStore
import com.yohaku.expensetracker.domain.model.SetupDraft
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.preferences by preferencesDataStore("app")
class AppPreferences(private val context: Context,private val storage:DataStore<Preferences> = context.preferences) {
    private val active = stringPreferencesKey("active_book")
    private val setup = stringPreferencesKey("first_setup")
    private val json = Json { encodeDefaults = true }
    val activeBook = storage.data.map { it[active] }
    val setupDraft = storage.data.map { prefs ->
        prefs[setup]?.let { json.decodeFromString<SetupDraft>(it) } ?: SetupDraft(
            language = if (context.resources.configuration.locales[0].language == "ja") "ja" else "zh"
        )
    }
    suspend fun select(id: String) { storage.edit { it[active] = id } }
    suspend fun saveSetup(draft: SetupDraft) { storage.edit { it[setup] = json.encodeToString(draft) } }
    suspend fun clearSetup() { storage.edit { it.remove(setup) } }
}
