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
import kotlinx.serialization.json.*

private val Context.preferences by preferencesDataStore("app")
// Stage-6 queued effects and undo snapshots predate this field; decode its documented default.
internal fun completeCommandPreference(key:String,value:JsonElement):JsonElement=
    if(key=="reminders"&&value is JsonObject&&"monthlyReview" !in value)JsonObject(value+("monthlyReview" to JsonPrimitive(true)))else value

class AppPreferences(private val context: Context,private val storage:DataStore<Preferences> = context.preferences):ReferenceRateStore {
    private val hapticsKey=booleanPreferencesKey("haptics_enabled")
    val hapticsEnabled=storage.data.map{it[hapticsKey]?:true}.distinctUntilChanged()
    suspend fun setHapticsEnabled(value:Boolean){storage.edit{it[hapticsKey]=value}}
    private val photoOriginalKey=booleanPreferencesKey("photo_save_original")
    val photoSaveOriginal=storage.data.map{it[photoOriginalKey]?:false}.distinctUntilChanged()
    suspend fun setPhotoSaveOriginal(value:Boolean){storage.edit{it[photoOriginalKey]=value}}
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
    // A preference write and its effect receipt must commit in the SAME DataStore edit. A Room
    // completion marker alone would replay a preference after a crash and overwrite later edits.
    private val commandReceipts=stringSetPreferencesKey("command_effect_receipts_v1")
    private fun commandValue(p:Preferences,bookId:String,key:String):JsonElement=when(key) {
        "activeBook"->p[active]?.let(::JsonPrimitive)?:JsonNull
        "automaticRates"->JsonPrimitive(p[automaticRates]?:true)
        "photoOriginal"->JsonPrimitive(p[photoOriginalKey]?:false)
        "haptics"->JsonPrimitive(p[hapticsKey]?:true)
        "location"->JsonPrimitive(bookId in (p[locationBooksKey]?:emptySet()))
        "reminders"->json.encodeToJsonElement(p[reminderSettingsKey]?.let{json.decodeFromString<Map<String,com.yohaku.expensetracker.domain.reminders.ReminderPreferences>>(it)}?.get(bookId)?:com.yohaku.expensetracker.domain.reminders.ReminderPreferences())
        else->error("Unknown preference: $key")
    }
    suspend fun commandValues(bookId:String):Map<String,JsonElement> {
        val p=storage.data.first()
        return listOf("activeBook","automaticRates","photoOriginal","haptics","location","reminders").associateWith{commandValue(p,bookId,it)}
    }
    suspend fun commandPreferenceApplied(effectId:String)=effectId in (storage.data.first()[commandReceipts]?:emptySet())
    suspend fun applyCommandPreference(effectId:String,bookId:String,key:String,before:JsonElement,after:JsonElement) {
        if(key=="reminders")json.decodeFromJsonElement<com.yohaku.expensetracker.domain.reminders.ReminderPreferences>(after).validate()
        storage.edit { p->
            val receipts=p[commandReceipts]?:emptySet()
            if(effectId !in receipts) {
                require(commandValue(p,bookId,key)==completeCommandPreference(key,before)){"偏好在确认后已变化，请恢复预览中的原值后重试 / 確定後に設定が変わりました。元の値に戻して再試行してください"}
                when(key) {
                    "activeBook"->if(after==JsonNull)p.remove(active)else p[active]=after.jsonPrimitive.content
                    "automaticRates"->p[automaticRates]=after.jsonPrimitive.boolean
                    "photoOriginal"->p[photoOriginalKey]=after.jsonPrimitive.boolean
                    "haptics"->p[hapticsKey]=after.jsonPrimitive.boolean
                    "location"->{val books=p[locationBooksKey]?:emptySet();p[locationBooksKey]=if(after.jsonPrimitive.boolean)books+bookId else books-bookId}
                    "reminders"->{val old=p[reminderSettingsKey]?.let{json.decodeFromString<Map<String,com.yohaku.expensetracker.domain.reminders.ReminderPreferences>>(it)}?:emptyMap();p[reminderSettingsKey]=json.encodeToString(old+(bookId to json.decodeFromJsonElement<com.yohaku.expensetracker.domain.reminders.ReminderPreferences>(after)))}
                    else->error("Unknown preference: $key")
                }
                p[commandReceipts]=receipts+effectId
            }
        }
    }
    suspend fun backup():com.yohaku.expensetracker.data.backup.BackupPreferences {
        val p=storage.data.first()
        return com.yohaku.expensetracker.data.backup.BackupPreferences(
            p[automaticRates]?:true,
            p[rateCache]?.let{json.decodeFromString<ReferenceRateCache>(it)}?:ReferenceRateCache(),
            p[reminderSettingsKey]?.let{json.decodeFromString(it)}?:emptyMap(),
            p[reminderDeliveryKey]?.let{json.decodeFromString(it)}?:com.yohaku.expensetracker.domain.reminders.ReminderDeliveryState(),
            p[locationBooksKey]?:emptySet(),p[active],p[setup]?.let{json.decodeFromString<SetupDraft>(it)},p[photoOriginalKey]?:false,p[commandReceipts]?:emptySet(),hapticsEnabled=p[hapticsKey]?:true
        )
    }
    suspend fun restoreBackup(value:com.yohaku.expensetracker.data.backup.BackupPreferences) {
        value.validate()
        storage.edit { p ->
            p.clear()
            p[commandReceipts]=value.commandEffectReceipts
            p[automaticRates]=value.automaticReferenceRates
            p[photoOriginalKey]=value.photoSaveOriginal
            p[hapticsKey]=value.hapticsEnabled
            p[rateCache]=json.encodeToString(value.referenceRates)
            p[reminderSettingsKey]=json.encodeToString(value.reminderSettings)
            p[reminderDeliveryKey]=json.encodeToString(value.reminderDelivery)
            p[locationBooksKey]=value.foregroundLocationBooks
            value.activeBook?.let{p[active]=it}
            value.setupDraft?.let{p[setup]=json.encodeToString(it)}
            // Alarm times are device-local derived state and are rebuilt after process restart.
        }
    }
}
