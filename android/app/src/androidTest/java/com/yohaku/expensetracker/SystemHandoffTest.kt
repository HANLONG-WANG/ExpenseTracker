package com.yohaku.expensetracker

import android.app.NotificationManager
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Bitmap
import androidx.work.*
import java.util.concurrent.TimeUnit
import java.util.UUID
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.domain.finance.*
import com.yohaku.expensetracker.domain.reminders.ReminderPreferences
import com.yohaku.expensetracker.platform.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.time.*
import java.time.format.DateTimeFormatter

/** Run phases separately, with real alarm/JobScheduler/reboot/package replacement between instrumentation runs. */
@RunWith(AndroidJUnit4::class)
class SystemHandoffTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=instrumentation.targetContext.applicationContext as YohakuApplication
    private val stateFile get()=File(app.filesDir,"system-handoff-test.json")
    private fun state()=JSONObject(stateFile.readText())
    @Before fun requireExternalSystemPhases() {
        Assume.assumeTrue("Requires external alarm/reboot/timezone/click phases",InstrumentationRegistry.getArguments().getString("systemHandoff")=="true")
    }
    @Test fun seedRealSystemSchedule()=runBlocking {
        assertFalse("Clean the prior dedicated fixture first",stateFile.exists())
        val r=app.repository;val prefs=app.preferences
        val previous=prefs.activeBook.first()
        val targetCase=InstrumentationRegistry.getArguments().getString("targetCase")?:"recurring"
        val deadlineCase=targetCase=="deadline"
        val at=if(targetCase!="recurring")ZonedDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0)else ZonedDateTime.now().plusMinutes(1).withSecond(0).withNano(0)
        val suffix=r.id().take(8);val name="ALARM-$suffix"
        val b=r.createBook(name,Currency.JPY,"journal","zh");r.dao.putBook(r.dao.book(b)!!.copy(initialized=true))
        val control=r.createBook("CONTROL-$suffix",Currency.JPY,"magazine","zh");r.dao.putBook(r.dao.book(control)!!.copy(initialized=true));prefs.select(control)
        val bank=r.saveAccount(b,"通知銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,if(targetCase=="funds")0 else 10000),LocalDate.now(r.clock).toString())
        val c=r.saveCategory(b,"生活",EntryKind.EXPENSE,null)
        val tomorrow=at.toLocalDate().plusDays(1).toString()
        var expectedMonth=tomorrow.take(7)
        if(targetCase in listOf("deadline","repayment")) {
            val card=r.saveAccount(b,"締切カード",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,1000),LocalDate.now(r.clock).toString())
            val billDate=if(deadlineCase)LocalDate.now(r.clock).plusMonths(1).withDayOfMonth(20).toString()else tomorrow;expectedMonth=billDate.take(7)
            val plan=r.commitManualFinancialPlan(r.previewManualFinancialPlan(b,ManualFinancialPlanInput(card,"跨月账单",FinanceMode.SINGLE,listOf(KnownBillInput(billDate,1000,1000,0,bank)),1000,complete=true,opening=true)))!!
            val bill=r.db.financialDao().bills(b,plan).single()
            val rule=FinancialRuleVersion("",b,card,FinanceMode.INSTALLMENT,LocalDate.now(r.clock).toString(),"Asia/Tokyo",deadlines=listOf(ChangeDeadline(bill.id,FinanceMode.INSTALLMENT,tomorrow,"18:00","Asia/Tokyo")))
            if(deadlineCase)r.commitFinancialRule(r.previewFinancialRule(b,card,rule))
        } else if(targetCase=="budget") {
            assertTrue(r.commitBudget(r.previewBudget(b,LocalDate.now(r.clock).toString().take(7),null,Money(Currency.JPY,100))))
            r.saveEntry(b,EntryDraft(accountId=bank,categoryId=c,amount="200",date=LocalDate.now(r.clock).toString()))
        } else {
            r.saveRecurrence(b,RecurrenceInput("SYSTEM-RENT-$suffix",bank,c,Money(Currency.JPY,1200),null,false,RecurrenceFrequency.MONTHLY,tomorrow,endDate=tomorrow))
            if(targetCase=="multiple")r.saveRecurrence(b,RecurrenceInput("SYSTEM-WATER-$suffix",bank,c,Money(Currency.JPY,300),null,false,RecurrenceFrequency.MONTHLY,tomorrow,endDate=tomorrow))
        }
        val draft=EntryDraft(accountId=bank,categoryId=c,amount="100",date=LocalDate.now(r.clock).toString())
        r.saveDraft(b,draft);val blob=r.importMedia(b,draft.mediaSession,"expired.bin","application/octet-stream"){byteArrayOf(1,2,3).inputStream()}
        val event=r.saveEntry(b,draft.copy(attachmentIds=listOf(blob.id)));r.deleteEntry(b,event)
        r.dao.putEvent(r.dao.event(b,event)!!.copy(deletedAt=r.clock.millis()-Duration.ofDays(31).toMillis()))
        prefs.saveReminderSettings(b,ReminderPreferences(enabled=true,repayment=targetCase=="repayment",deadline=deadlineCase,recurring=targetCase in listOf("recurring","multiple"),budget=targetCase=="budget",funds=targetCase=="funds",time=at.format(DateTimeFormatter.ofPattern("HH:mm")),repaymentDays=listOf(1),deadlineDays=listOf(1),recurringDays=if(targetCase=="recurring")listOf(1,0)else listOf(1)))
        app.reminders.rebuild(false)
        assertTrue(app.reminders.plan.value.any{it.item.bookId==b&&it.at==at.toInstant().toEpochMilli()})
        if(deadlineCase)assertEquals(expectedMonth,r.reminderItems(prefs).single{it.bookId==b&&it.kind=="DEADLINE"}.targetMonth)
        val saved=JSONObject().put("targetCase",targetCase).put("visibleCount",if(targetCase=="budget")1 else 0).put("expectedText",when(targetCase){"deadline","repayment"->expectedMonth;"budget"->"提前一点";"funds"->"核对资金安排";"multiple"->"重要的事";else->"先为日常"}).put("expectedMonth",expectedMonth).put("book",b).put("control",control).put("name",name).put("event",event).put("file",r.mediaFile(blob).absolutePath).put("at",at.toInstant().toEpochMilli()).put("tomorrow",tomorrow)
        if(previous!=null)saved.put("previous",previous)
        val work=OneTimeWorkRequestBuilder<LocalMaintenanceWorker>().setInitialDelay(10,TimeUnit.SECONDS).build()
        saved.put("maintenanceWork",work.id.toString())
        stateFile.writeText(saved.toString())
        WorkManager.getInstance(app).enqueue(work).result.get()
        assertTrue(r.mediaFile(blob).exists())
        println("SYSTEM_HANDOFF at=${at.toInstant()} book=$b")
    }
    @Test fun verifyAlarmDeliveredAndActualBackgroundMaintenanceRan()=runBlocking {
        val s=state();val b=s.getString("book")
        val notifications=app.getSystemService(NotificationManager::class.java).activeNotifications
        assertTrue("Actual alarm must have posted the persisted book",notifications.any{it.tag=="book:$b"})
        assertTrue(app.preferences.reminderDelivery().sent.isNotEmpty())
        assertNull("Only the actual background Worker should purge this expired fixture",app.repository.dao.event(b,s.getString("event")))
        assertFalse(File(s.getString("file")).exists())
        val work=WorkManager.getInstance(app).getWorkInfosForUniqueWork(LocalMaintenanceWorker.UNIQUE_NAME).get()
        assertEquals(1,work.size);assertTrue(work.single().state in listOf(androidx.work.WorkInfo.State.ENQUEUED,androidx.work.WorkInfo.State.RUNNING))
        assertEquals(WorkInfo.State.SUCCEEDED,WorkManager.getInstance(app).getWorkInfoById(UUID.fromString(s.getString("maintenanceWork"))).get()!!.state)
        assertEquals(s.getString("control"),app.preferences.activeBook.first())
    }
    private fun textNode(root:AccessibilityNodeInfo?,query:String):AccessibilityNodeInfo? {
        if(root==null)return null
        if(root.text?.contains(query)==true)return root
        for(i in 0 until root.childCount)textNode(root.getChild(i),query)?.let{return it}
        return null
    }
    @Test fun actualNotificationDrawerClickOpensItsBookAndTarget()=runBlocking {
        val s=state();val automation=instrumentation.uiAutomation
        // This click phase explicitly posts a fresh real notification after the earlier alarm phase was verified.
        app.preferences.select(s.getString("control"))
        val delivery=app.preferences.reminderDelivery()
        app.preferences.saveReminderRuntime(delivery.copy(sent=delivery.sent.filterKeys{!it.startsWith(s.getString("book")+"/")},risks=delivery.risks.filterKeys{!it.startsWith(s.getString("book")+"/")}),app.preferences.savedReminderPlan())
        app.reminders.rebuild(true)
        automation.serviceInfo=automation.serviceInfo.apply{flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
        automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        app.startActivity(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertNotNull("Main Activity accessibility root: ${automation.rootInActiveWindow}",withTimeoutOrNull(10000){while(automation.rootInActiveWindow?.packageName?.toString()!=app.packageName)delay(100);true})
        automation.waitForIdle(350,5000)
        assertEquals(s.getString("control"),app.preferences.activeBook.first())
        assertTrue(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS))
        val ready=File(app.filesDir,"system-notification-click-ready");ready.writeText(s.getString("name"))
        try {
            // External driver/user taps the observed real card; do not replace that click with PendingIntent.send().
            withTimeout(40000){while(app.preferences.activeBook.first()!=s.getString("book"))delay(100)}
            withTimeout(10000){while(textNode(automation.rootInActiveWindow,s.getString("expectedText"))==null)delay(100)}
        }finally{ready.delete()}
        assertEquals("Opening a notification must not create an entry",s.getInt("visibleCount"),app.repository.snapshot(app.repository.dao.book(s.getString("book"))!!).first().visibleEvents.size)
        automation.waitForIdle(350,5000)
        val image=automation.takeScreenshot();File(app.getExternalFilesDir(null),"phase-e-system-notification-${s.optString("targetCase","recurring")}-click.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
    }
    @Test fun verifyPersistentPlanAfterRealSystemEvent()=runBlocking {
        val s=state();val b=s.getString("book")
        assertNotNull(app.repository.dao.book(b))
        assertEquals(ZoneId.systemDefault(),app.repository.clock.zone)
        withTimeout(10000){while(app.preferences.savedReminderPlan().none{it.item.bookId==b})delay(100)}
        assertTrue(app.preferences.reminderDelivery().sent.isNotEmpty())
        assertEquals(0,app.repository.dao.eventCount(b))
        assertTrue("Delivered/clicked slot must not be posted again",app.getSystemService(NotificationManager::class.java).activeNotifications.none{it.tag=="book:$b"})
        println("SYSTEM_PLAN zone=${app.repository.clock.zone} slots=${app.preferences.savedReminderPlan().filter{it.item.bookId==b}.map{Instant.ofEpochMilli(it.at)}}")
    }
    @Test fun observeRealTimezoneChangeInTheSameProcess()=runBlocking {
        val target=ZoneId.of(InstrumentationRegistry.getArguments().getString("targetZone")?:"UTC")
        val clock=app.repository.clock;val before=clock.zone
        assertNotEquals(before,target)
        val ready=File(app.filesDir,"system-timezone-ready");ready.writeText(before.id)
        try {
            withTimeout(40000){while(clock.zone!=target)delay(100)}
            assertSame(clock,app.repository.clock)
            assertEquals(Instant.now().atZone(target).toLocalDate(),LocalDate.now(clock))
            println("SAME_PROCESS_TIMEZONE $before -> ${clock.zone}")
        }finally{ready.delete()}
    }
    @Test fun cleanupOnlyTheDedicatedSystemFixture()=runBlocking {
        if(!stateFile.exists())return@runBlocking
        val s=state();val r=app.repository
        for(key in listOf("book","control")) {
            val b=s.getString(key);val book=r.dao.storedBook(b)?:continue
            require(book.name.startsWith("ALARM-")||book.name.startsWith("CONTROL-"))
            if(book.deletedAt==null)assertTrue(r.commitBookOperation(r.previewBookOperation(b,BookOperation.TRASH)))
            assertTrue(r.commitBookOperation(r.previewBookOperation(b,BookOperation.PURGE)))
        }
        r.collectUnusedMedia();val previous=s.optString("previous")
        if(previous.isNotBlank()&&r.dao.book(previous)!=null)app.preferences.select(previous)else app.preferences.clearSelection()
        app.preferences.pruneBookPreferences(r.books.first().map{it.id}.toSet());app.reminders.rebuild(false);stateFile.delete()
    }
}
