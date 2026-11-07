package com.yohaku.expensetracker.platform.desktop

import android.app.*
import android.appwidget.AppWidgetManager
import android.content.*
import androidx.room.InvalidationTracker
import com.yohaku.expensetracker.YohakuApplication
import com.yohaku.expensetracker.data.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.*

class DesktopCoordinator(private val app:YohakuApplication) {
    val bindings=DesktopBindings(app)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val requests=Channel<Unit>(Channel.CONFLATED)
    private val mutex=Mutex()
    private var stopped=false
    private val observer=object:InvalidationTracker.Observer(TABLES) {
        override fun onInvalidated(tables:Set<String>){requestRefresh()}
    }
    private val wakeup get()=PendingIntent.getBroadcast(app,0,Intent(app,YohakuWidgetProvider::class.java).setAction(ACTION_REFRESH),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private val alarms get()=app.getSystemService(AlarmManager::class.java)
    fun start() {
        scope.launch {
            app.repository.db.invalidationTracker.addObserver(observer)
            launch {app.preferences.activeBook.distinctUntilChanged().collect{requestRefresh()}}
            requestRefresh()
            for(ignored in requests) {
                delay(1000)
                while(requests.tryReceive().isSuccess) { /* 合并同一批事务 */ }
                refreshSafely()
            }
        }
    }
    fun requestRefresh(){requests.trySend(Unit)}
    suspend fun refreshSafely() {
        try {refresh()}
        catch(e:CancellationException){throw e}
        catch(e:Exception) {
            android.util.Log.e("YohakuDesktop","桌面刷新失败",e)
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,System.currentTimeMillis()+15*60*1000,wakeup)
        }
    }
    suspend fun refresh()=mutex.withLock {
        if(stopped)return@withLock
        val manager=AppWidgetManager.getInstance(app)
        val ids=manager.getAppWidgetIds(ComponentName(app,YohakuWidgetProvider::class.java))
        val books=app.repository.books.first().filter{it.deletedAt==null}
        val selected=app.preferences.activeBook.first()
        val active=books.firstOrNull{it.id==selected}?:books.firstOrNull{!it.archived}
        val bound=ids.associateWith{bindings.book(it)}
        val wanted=(bound.values.filterNotNull()+listOfNotNull(active?.id)).toSet()
        val snapshots=books.filter{it.id in wanted}.associate{it.id to app.repository.snapshot(it).first()}
        val today=LocalDate.now(app.repository.clock)
        for(id in ids) {
            val snapshot=snapshots[bound[id]]
            if(snapshot!=null)bindings.bind(id,snapshot.book.id,snapshot.book.theme,snapshot.book.language)
            val appearance=bindings.appearance(id)
            manager.updateAppWidget(id,WidgetRenderer(app).render(id,snapshot,today,appearance,manager.getAppWidgetOptions(id)))
        }
        DesktopShortcuts.sync(app,active?.id?.let{snapshots[it]})
        if(ids.isNotEmpty()||active!=null) {
            val midnight=today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).plusSeconds(5).toInstant().toEpochMilli()
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,midnight,wakeup)
        }else alarms.cancel(wakeup)
    }
    suspend fun stopForRestore() {
        stopped=true
        scope.coroutineContext[Job]!!.cancelAndJoin()
        app.repository.db.invalidationTracker.removeObserver(observer)
        mutex.withLock{alarms.cancel(wakeup)}
    }
    companion object {
        const val ACTION_REFRESH="com.yohaku.expensetracker.REFRESH_DESKTOP"
        // 预算：退款确认、手续费、独立活动、待付实例及预留使用；模板：可用性和默认付款方式。
        val TABLES=arrayOf("books","events","settlements","expense_components","budgets","occurrences","recurrences",
            "activities","event_activities","activity_reserves","reserve_uses","entry_templates","accounts","categories","merchants","book_features",
            "revisions")
    }
}

class YohakuWidgetProvider:android.appwidget.AppWidgetProvider() {
    override fun onReceive(context:Context,intent:Intent) {
        super.onReceive(context,intent)
        if(intent.action in setOf(DesktopCoordinator.ACTION_REFRESH,Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED,Intent.ACTION_TIMEZONE_CHANGED,Intent.ACTION_DATE_CHANGED,Intent.ACTION_LOCALE_CHANGED))refresh(context)
    }
    override fun onUpdate(context:Context,manager:AppWidgetManager,ids:IntArray)=refresh(context)
    override fun onAppWidgetOptionsChanged(context:Context,manager:AppWidgetManager,id:Int,options:android.os.Bundle)=refresh(context)
    override fun onDeleted(context:Context,ids:IntArray) {
        val pending=goAsync()
        CoroutineScope(Dispatchers.IO).launch {try {
            val desktop=(context.applicationContext as YohakuApplication).desktop
            desktop.bindings.delete(ids);desktop.refreshSafely()
        }finally{pending.finish()}}
    }
    private fun refresh(context:Context) {
        val pending=goAsync()
        CoroutineScope(Dispatchers.IO).launch {try{(context.applicationContext as YohakuApplication).desktop.refreshSafely()}finally{pending.finish()}}
    }
}
