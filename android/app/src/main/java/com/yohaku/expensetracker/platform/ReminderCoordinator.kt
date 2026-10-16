package com.yohaku.expensetracker.platform

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.room.InvalidationTracker
import com.yohaku.expensetracker.MainActivity
import com.yohaku.expensetracker.R
import com.yohaku.expensetracker.YohakuApplication
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.Currency
import com.yohaku.expensetracker.domain.model.Money
import com.yohaku.expensetracker.domain.reminders.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.*

/** A single replaceable inexact wake-up derives all per-book notifications from current facts. */
class ReminderCoordinator(private val app:YohakuApplication,private val repository:LedgerRepository=app.repository,private val preferences:AppPreferences=app.preferences) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val mutex=Mutex()
    private val requests=Channel<Unit>(Channel.CONFLATED)
    val plan=MutableStateFlow<List<ReminderSlot>>(emptyList())
    val failure=MutableStateFlow<String?>(null)
    private val manager get()=app.getSystemService(NotificationManager::class.java)
    private val alarms get()=app.getSystemService(AlarmManager::class.java)
    private val wakeup get()=PendingIntent.getBroadcast(app,0,Intent(app,ReminderReceiver::class.java).setAction(ACTION_REBUILD),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun allowed()=(Build.VERSION.SDK_INT<33||app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)&&NotificationManagerCompat.from(app).areNotificationsEnabled()&&manager.getNotificationChannel(CHANNEL)?.importance!=NotificationManager.IMPORTANCE_NONE
    fun start() {
        manager.createNotificationChannel(NotificationChannel(CHANNEL,app.getString(R.string.important_notification_channel),NotificationManager.IMPORTANCE_DEFAULT))
        scope.launch {
            // Observe actual committed writes, including writes outside AppViewModel.
            val tables=repository.db.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table','android_metadata')").use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
            repository.db.invalidationTracker.addObserver(object:InvalidationTracker.Observer(tables.toTypedArray()) {
                override fun onInvalidated(tables:Set<String>){requests.trySend(Unit)}
            })
            launch {preferences.reminderSettings.collect{requests.send(Unit)}}
            launch {preferences.reminderRateChanges.collect{requests.send(Unit)}}
            requests.send(Unit)
            for(ignored in requests)rebuildSafely(false)
        }
    }
    suspend fun rebuildSafely(deliver:Boolean) {
        try {rebuild(deliver);failure.value=null}
        catch(e:CancellationException){throw e}
        catch(e:Exception) {
            failure.value=e.message?:"提醒重建失败 / 通知の再構築に失敗しました"
            android.util.Log.e("YohakuReminders","Cannot rebuild reminders",e)
            // Persisted ledger writes are never rolled back because a system service failed.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,System.currentTimeMillis()+15*60*1000,wakeup)
        }
    }
    suspend fun rebuild(deliver:Boolean)=mutex.withLock {
        val repo=repository;val settings=preferences.reminderSettings.first()
        val now=repo.clock.instant();val zone=ZoneId.systemDefault()
        val items=repo.reminderItems(preferences)
        var state=preferences.reminderDelivery()
        var current=planReminders(items,settings,state,now,zone)
        state=state.copy(risks=state.risks.filterKeys{it in current.activeRiskKeys})
        val books=repo.books.first().filter{it.deletedAt==null}.associateBy{it.id}
        // Cancel displayed summaries whose facts changed, or whose book/settings disappeared.
        for(notification in manager.activeNotifications) {
            val id=notification.tag?.removePrefix("book:")?:continue
            val prior=notification.notification.extras.getStringArray("yohaku.items")?.toList().orEmpty()
            val live=items.filter{it.bookId==id&&settings[id]?.allows(it.kind)==true}.map{repo.json.encodeToString(ReminderItem.serializer(),it)}
            if(!allowed()||id !in books||prior.any{it !in live})manager.cancel(notification.tag,NOTIFICATION_ID)
        }
        if(deliver&&allowed()) {
            for((bookId,due) in current.due.groupBy{it.item.bookId}) {
                val book=books[bookId]?:continue
                val ja=book.language=="ja"
                val rows=due.distinctBy{it.item.key}.map{slot->val i=slot.item
                    val amount=i.amountMinor?.let{Money(Currency.valueOf(i.currency),it).display()}?:if(ja)"金額未定"else"金额未定"
                    "${if(i.estimated)if(ja)"見込み "else"预计 "else ""}${i.currency} $amount · ${i.date} · ${i.account} · ${i.title}"
                }
                val open=Intent(app,MainActivity::class.java).setAction(ACTION_OPEN).setData(Uri.Builder().scheme("yohaku").authority("reminders").appendPath(bookId).build()).putExtra(EXTRA_BOOK,bookId).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                due.distinctBy{it.item.key}.singleOrNull()?.let{open.putExtra(EXTRA_ITEM,repo.json.encodeToString(ReminderItem.serializer(),it.item))}
                val content=PendingIntent.getActivity(app,0,open,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val notification=NotificationCompat.Builder(app,CHANNEL).setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("${book.name} · "+if(ja)"大事な予定"else"重要事项")
                    .setContentText(rows.first()).setStyle(if(rows.size==1)NotificationCompat.BigTextStyle().bigText(rows.first())else NotificationCompat.InboxStyle().also{style->rows.forEach{style.addLine(it)}})
                    .setContentIntent(content).setAutoCancel(true).setOnlyAlertOnce(false)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .addExtras(android.os.Bundle().apply{putStringArray("yohaku.items",due.map{repo.json.encodeToString(ReminderItem.serializer(),it.item)}.toTypedArray())}).build()
                manager.notify("book:$bookId",NOTIFICATION_ID,notification)
                state=recordReminderDelivery(state,due,now,zone)
            }
            current=planReminders(items,settings,state,now,zone)
        }
        preferences.saveReminderRuntime(state,current.slots)
        plan.value=current.slots
        // Midnight re-evaluation rolls forecasts and recurrence instances forward without a service.
        if(settings.any{(id,value)->value.enabled&&books[id]?.initialized==true}) {
            val midnight=now.atZone(zone).toLocalDate().plusDays(1).atTime(0,5).atZone(zone).toInstant().toEpochMilli()
            val next=if(allowed())minOf(midnight,current.slots.minOfOrNull{it.at}?:midnight)else midnight
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,maxOf(now.toEpochMilli()+1000,next),wakeup)
        } else alarms.cancel(wakeup)
    }
    companion object {
        const val CHANNEL="important-v1"
        const val NOTIFICATION_ID=21
        const val ACTION_REBUILD="com.yohaku.expensetracker.REBUILD_REMINDERS"
        const val ACTION_OPEN="com.yohaku.expensetracker.OPEN_REMINDERS"
        const val EXTRA_ITEM="reminder_item"
        const val EXTRA_BOOK="reminder_book"
    }
}

class ReminderReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        android.util.Log.i("YohakuReminders","System rebuild: ${intent.action}")
        val pending=goAsync()
        val app=context.applicationContext as YohakuApplication
        CoroutineScope(Dispatchers.IO).launch {
            try {app.reminders.rebuildSafely(true)}finally{pending.finish()}
        }
    }
}
