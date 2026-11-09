package com.yohaku.expensetracker.data.backup

import com.yohaku.expensetracker.domain.currency.ReferenceRateCache
import com.yohaku.expensetracker.domain.model.SetupDraft
import com.yohaku.expensetracker.domain.reminders.ReminderPreferences
import com.yohaku.expensetracker.domain.reminders.ReminderDeliveryState
import kotlinx.serialization.Serializable

@Serializable data class BackupPreferences(
    val automaticReferenceRates:Boolean=true,
    val referenceRates:ReferenceRateCache=ReferenceRateCache(),
    val reminderSettings:Map<String,ReminderPreferences> = emptyMap(),
    val reminderDelivery:ReminderDeliveryState=ReminderDeliveryState(),
    val foregroundLocationBooks:Set<String> = emptySet(),
    val activeBook:String?=null,
    val setupDraft:SetupDraft?=null,
    val photoSaveOriginal:Boolean=false,
    val commandEffectReceipts:Set<String> = emptySet(),
    val hapticsEnabled:Boolean=true,
    val scanAutoCapture:Boolean=false,
    val scanAutoProcess:Boolean=true
) {
    fun validate(){reminderSettings.values.forEach{it.validate()}}
}
