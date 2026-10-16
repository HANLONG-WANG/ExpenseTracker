package com.yohaku.expensetracker.data.repository

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.preferencesDataStore
import com.yohaku.expensetracker.domain.model.SetupDraft
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
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
    private val reminderSettingsKey = stringPreferencesKey("reminder_settings_v1")
    private val reminderDeliveryKey = stringPreferencesKey("reminder_delivery_v1")
    private val reminderPlanKey = stringPreferencesKey("reminder_plan_v1")
    val reminderRateChanges=storage.data.map{it[rateCache]}.distinctUntilChanged()
    val reminderSettings=storage.data.map{it[reminderSettingsKey]?.let{raw->json.decodeFromString<Map<String,com.yohaku.expensetracker.domain.reminders.ReminderPreferences>>(raw)}?:emptyMap()}.distinctUntilChanged()
    suspend fun saveReminderSettings(bookId:String,value:com.yohaku.expensetracker.domain.reminders.ReminderPreferences) {
        value.validate()
        storage.edit { prefs ->
            val old=prefs[reminderSettingsKey]?.let{json.decodeFromString<Map<String,com.yohaku.expensetracker.domain.reminders.ReminderPreferences>>(it)}?:emptyMap()
            prefs[reminderSettingsKey]=json.encodeToString(old+(bookId to value))
        }
    }
    suspend fun reminderDelivery()=storage.data.first()[reminderDeliveryKey]?.let{json.decodeFromString<com.yohaku.expensetracker.domain.reminders.ReminderDeliveryState>(it)}?:com.yohaku.expensetracker.domain.reminders.ReminderDeliveryState()
    suspend fun saveReminderRuntime(value:com.yohaku.expensetracker.domain.reminders.ReminderDeliveryState,plan:List<com.yohaku.expensetracker.domain.reminders.ReminderSlot>) {
        storage.edit{it[reminderDeliveryKey]=json.encodeToString(value);it[reminderPlanKey]=json.encodeToString(plan)}
    }
    suspend fun savedReminderPlan()=storage.data.first()[reminderPlanKey]?.let{json.decodeFromString<List<com.yohaku.expensetracker.domain.reminders.ReminderSlot>>(it)}?:emptyList()
    private val locationBooksKey = stringSetPreferencesKey("foreground_location_books")
    val foregroundLocationBooks=storage.data.map{it[locationBooksKey]?:emptySet()}.distinctUntilChanged()
    suspend fun setForegroundLocation(bookId:String,enabled:Boolean) {
        storage.edit{prefs->val books=prefs[locationBooksKey]?:emptySet();prefs[locationBooksKey]=if(enabled)books+bookId else books-bookId}
    }
    private val active = stringPreferencesKey("active_book")
    private val setup = stringPreferencesKey("first_setup")
    private val json = Json { encodeDefaults = true }
    val activeBook = storage.data.map { it[active] }
    val setupDraft = storage.data.map { prefs ->
        prefs[setup]?.let { json.decodeFromString<SetupDraft>(it) } ?: SetupDraft(
            language = if (context.resources.configuration.locales[0].language == "ja") "ja" else "zh"
        )
    }
    suspend fun clearSelection(){storage.edit{it.remove(active)}}
    suspend fun pruneBookPreferences(retained:Set<String>) {
        storage.edit { prefs ->
            val settings=prefs[reminderSettingsKey]?.let{json.decodeFromString<Map<String,com.yohaku.expensetracker.domain.reminders.ReminderPreferences>>(it)}
            if(settings!=null)prefs[reminderSettingsKey]=json.encodeToString(settings.filterKeys{it in retained})
            prefs[locationBooksKey]?.let{prefs[locationBooksKey]=it.intersect(retained)}
            if(prefs[active]?.let{it !in retained}==true)prefs.remove(active)
        }
    }
    suspend fun select(id: String) { storage.edit { it[active] = id } }
    suspend fun saveSetup(draft: SetupDraft) { storage.edit { it[setup] = json.encodeToString(draft) } }
    suspend fun clearSetup() { storage.edit { it.remove(setup) } }
}
