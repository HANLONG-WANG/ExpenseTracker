package com.yohaku.expensetracker.data.repository

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.preferencesDataStore
import com.yohaku.expensetracker.domain.model.SetupDraft
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import com.yohaku.expensetracker.domain.currency.ReferenceRateCache
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.preferences by preferencesDataStore("app")
class AppPreferences(private val context: Context,private val storage:DataStore<Preferences> = context.preferences):ReferenceRateStore {
    private val rateCache = stringPreferencesKey("reference_rates_v2_ecb")
    private val automaticRates = booleanPreferencesKey("automatic_reference_rates")
    override val automaticReferenceRates=storage.data.map{it[automaticRates]?:true}
    override suspend fun automaticReferenceRatesEnabled()=automaticReferenceRates.first()
    override suspend fun setAutomaticReferenceRates(enabled:Boolean){storage.edit{it[automaticRates]=enabled}}
    override suspend fun referenceRateCache()=storage.data.first()[rateCache]?.let{json.decodeFromString<ReferenceRateCache>(it)}?:ReferenceRateCache()
    override suspend fun saveReferenceRateCache(cache:ReferenceRateCache){storage.edit{it[rateCache]=json.encodeToString(cache)}}
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
