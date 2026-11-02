package com.yohaku.expensetracker

import android.app.NotificationManager
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.command.monthlyReviewBundle
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.domain.reminders.*
import com.yohaku.expensetracker.platform.ReminderCoordinator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.*
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReminderSystemTest {
    private val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as YohakuApplication
    private suspend fun fixture(block:suspend (LedgerRepository,AppPreferences,ReminderCoordinator,String,String)->Unit) {
        val db=Room.inMemoryDatabaseBuilder(app,YohakuDatabase::class.java).build()
        val storeScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val file=File(app.cacheDir,"reminder-${UUID.randomUUID()}.preferences_pb")
        val prefs=AppPreferences(app,PreferenceDataStoreFactory.create(scope=storeScope,produceFile={file}))
        val now=ZonedDateTime.now().withHour(12).withMinute(0).withSecond(0)
        val repo=LedgerRepository(db,Clock.fixed(now.toInstant(),now.zone))
        val ids=mutableListOf<String>()
        try {
            suspend fun seed(name:String,currency:Currency):String {
                val b=repo.createBook(name,currency,startDate=LocalDate.now(repo.clock).toString())
                ids+=b
                repo.dao.putBook(repo.dao.book(b)!!.copy(initialized=true))
                val a=repo.saveAccount(b,"Bank-$name",AccountType.BANK,currency,Money(currency,100000),LocalDate.now(repo.clock).toString())
                val c=repo.saveCategory(b,"Bills",EntryKind.EXPENSE,null)
                val tomorrow=LocalDate.now(repo.clock).plusDays(1).toString()
                repo.saveRecurrence(b,RecurrenceInput("Rent-$name",a,c,Money(currency,1200),Money(currency,1200),false,RecurrenceFrequency.MONTHLY,tomorrow,endDate=tomorrow))
                prefs.saveReminderSettings(b,ReminderPreferences(enabled=true,time="00:00"))
                return b
            }
            val a=seed("Alpha",Currency.JPY);val b=seed("Beta",Currency.CNY)
            block(repo,prefs,ReminderCoordinator(app,repo,prefs),a,b)
        }finally {
            ids.forEach{app.getSystemService(NotificationManager::class.java).cancel("book:$it",ReminderCoordinator.NOTIFICATION_ID)}
            storeScope.cancel();db.close();file.delete()
            app.reminders.rebuildSafely(false)
        }
    }
    @Test fun monthlyReviewUsesFirstDayPreviousMonthAndPersistedDedup()=runBlocking<Unit> {
        val db=Room.inMemoryDatabaseBuilder(app,YohakuDatabase::class.java).build()
        val storeScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val file=File(app.cacheDir,"monthly-reminder-${UUID.randomUUID()}.preferences_pb")
        val prefs=AppPreferences(app,PreferenceDataStoreFactory.create(scope=storeScope,produceFile={file}))
        val zone=ZoneId.systemDefault()
        var time=LocalDate.of(2026,11,1).atTime(8,0).atZone(zone).toInstant()
        val clock=object:Clock(){override fun getZone()=zone;override fun withZone(zone:ZoneId):Clock=Clock.fixed(time,zone);override fun instant()=time}
        val repo=LedgerRepository(db,clock)
        val coordinator=ReminderCoordinator(app,repo,prefs)
        try {
            val book=repo.createBook("月评",Currency.JPY,startDate="2026-10-01")
            repo.updateBook(repo.dao.book(book)!!.copy(initialized=true))
            coordinator.rebuild(false)
            val slot=coordinator.plan.value.single{it.item.kind=="MONTHLY_REVIEW"}
            assertEquals("2026-10",slot.item.targetMonth);assertEquals("2026-11-01",slot.item.date)
            assertEquals(LocalDate.of(2026,11,1).atTime(9,0).atZone(zone).toInstant().toEpochMilli(),slot.at)
            val items=repo.reminderItems(prefs)
            assertTrue(planReminders(items,emptyMap(),ReminderDeliveryState(),time,zone).due.isEmpty())
            time=time.plusSeconds(7200)
            val due=planReminders(items,emptyMap(),ReminderDeliveryState(),time,zone).due
            assertEquals(1,due.count{it.item.kind=="MONTHLY_REVIEW"})
            prefs.saveReminderRuntime(recordReminderDelivery(ReminderDeliveryState(),due,time,zone),emptyList())
            ReminderCoordinator(app,repo,prefs).rebuild(false)
            coordinator.rebuild(false);assertTrue(coordinator.plan.value.none{it.item.kind=="MONTHLY_REVIEW"})
            // A fresh state still never catches up the monthly notification on day 2.
            time=LocalDate.of(2026,11,2).atTime(10,0).atZone(zone).toInstant()
            assertTrue(planReminders(repo.reminderItems(prefs),emptyMap(),ReminderDeliveryState(),time,zone).slots.none{it.item.kind=="MONTHLY_REVIEW"})
            time=LocalDate.of(2026,12,1).atTime(10,0).atZone(zone).toInstant()
            coordinator.rebuild(false);assertEquals("2026-11",coordinator.plan.value.single{it.item.kind=="MONTHLY_REVIEW"}.item.targetMonth)
            prefs.saveReminderSettings(book,ReminderPreferences(monthlyReview=false))
            coordinator.rebuild(false);assertTrue(coordinator.plan.value.none{it.item.kind=="MONTHLY_REVIEW"})
            prefs.saveReminderSettings(book,ReminderPreferences())
            val snapshot=repo.monthlyReviewBundle(book,"2026-11")
            db.monthlyDao().insert(com.yohaku.expensetracker.data.command.MonthlyReviewEntity(book,"2026-11","submitted",repo.json.encodeToString(com.yohaku.expensetracker.data.command.MonthlyReviewBundle.serializer(),snapshot),time.toEpochMilli()))
            coordinator.rebuild(false);assertTrue(repo.reminderItems(prefs).none{it.kind=="MONTHLY_REVIEW"});assertTrue(coordinator.plan.value.none{it.item.kind=="MONTHLY_REVIEW"})
        }finally{storeScope.cancel();db.close();file.delete();app.reminders.rebuildSafely(false)}
    }

    @Test fun actualDeliveryModificationCancellationAndPersistentDedup()=runBlocking {
        org.junit.Assume.assumeTrue("Requires system=true", testOption("system"))
        fixture{repo,prefs,coordinator,a,b->
            assertTrue("Run with POST_NOTIFICATIONS granted",coordinator.allowed())
            coordinator.rebuild(true)
            val manager=app.getSystemService(NotificationManager::class.java)
            var notifications=manager.activeNotifications.filter{it.tag in listOf("book:$a","book:$b")}
            assertEquals(2,notifications.size)
            val extras=notifications.first{it.tag=="book:$a"}.notification.extras
            val text=extras.getCharSequence("android.bigText")?.toString()?:extras.getCharSequenceArray("android.textLines")!!.joinToString()
            assertTrue(text.startsWith("JPY ¥1,200"));assertFalse(text.contains("Beta"))
            if (visualEnabled()) {
            val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
            automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            automation.waitForIdle(350,5000)
            val image=automation.takeScreenshot()
            File(app.getExternalFilesDir(null),"phase-e-system-notification-amounts.png").outputStream().use{image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};image.recycle()
            automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            }
            val sent=prefs.reminderDelivery()
            assertEquals(2,sent.sent.size)
            // New coordinator instance reads persistent delivery state, rather than an in-memory flag.
            ReminderCoordinator(app,repo,prefs).rebuild(true)
            assertEquals(sent,prefs.reminderDelivery())
            assertEquals(coordinator.plan.value,prefs.savedReminderPlan())
            val recurrence=repo.dao.recurrences(a).single()
            repo.dao.putRecurrence(recurrence.copy(name="Changed",amountMinor=1500,baseMinor=1500))
            repo.ensureOccurrences(a,recurrence.anchorDate.take(7))
            coordinator.rebuild(false)
            assertFalse(manager.activeNotifications.any{it.tag=="book:$a"})
            val occurrence=repo.dao.occurrences(a).single()
            repo.cancelOccurrence(a,occurrence.id)
            coordinator.rebuild(false)
            assertTrue(coordinator.plan.value.none{it.item.bookId==a})
            assertTrue(manager.activeNotifications.any{it.tag=="book:$b"})
            assertEquals(0,repo.snapshot(repo.dao.book(a)!!).first().events.size)
        }
    }
    @Test fun deniedPermissionRetainsFactsAndPendingDelivery()=runBlocking {
        org.junit.Assume.assumeTrue("Requires system=true", testOption("system"))
        fixture{repo,prefs,coordinator,a,b->
            assertFalse("Run with POST_NOTIFICATIONS denied",coordinator.allowed())
            coordinator.rebuild(true)
            assertTrue(prefs.reminderDelivery().sent.isEmpty())
            assertEquals(2,coordinator.plan.value.size)
            assertTrue(app.getSystemService(NotificationManager::class.java).activeNotifications.none{it.tag in listOf("book:$a","book:$b")})
            assertEquals(1,repo.dao.occurrences(a).size)
            assertTrue(repo.snapshot(repo.dao.book(a)!!).first().events.isEmpty())
        }
    }
    @Test fun archiveKeepsRealNotificationsTrashCancelsAndRestoreRebuildsFutureSlots()=runBlocking {
        org.junit.Assume.assumeTrue("Requires system=true", testOption("system"))
        fixture{repo,prefs,coordinator,a,b->
            assertTrue("Run with POST_NOTIFICATIONS granted",coordinator.allowed())
            prefs.saveReminderSettings(a,ReminderPreferences(enabled=true,recurringDays=listOf(1,0),time="00:00"))
            coordinator.rebuild(true)
            val manager=app.getSystemService(NotificationManager::class.java)
            assertTrue(manager.activeNotifications.any{it.tag=="book:$a"})
            suspend fun apply(operation:BookOperation){assertTrue(repo.commitBookOperation(repo.previewBookOperation(a,operation)));coordinator.rebuild(false)}
            apply(BookOperation.ARCHIVE)
            assertTrue(manager.activeNotifications.any{it.tag=="book:$a"})
            assertTrue(coordinator.plan.value.any{it.item.bookId==a})
            apply(BookOperation.TRASH)
            assertFalse(manager.activeNotifications.any{it.tag=="book:$a"})
            assertTrue(coordinator.plan.value.none{it.item.bookId==a})
            assertTrue(manager.activeNotifications.any{it.tag=="book:$b"})
            apply(BookOperation.RESTORE)
            assertTrue(repo.dao.book(a)!!.archived)
            assertTrue(coordinator.plan.value.any{it.item.bookId==a})
            assertTrue(manager.activeNotifications.any{it.tag=="book:$b"})
        }
    }

    @Test fun deletingActivityWithdrawsItsRealReminderAndKeepsTheOtherBookNotification()=runBlocking {
        org.junit.Assume.assumeTrue("Requires system=true", testOption("system"))
        fixture{repo,_,coordinator,a,b->
            assertTrue("Run with POST_NOTIFICATIONS granted",coordinator.allowed())
            repo.setActivityMode(a,true)
            val tomorrow=LocalDate.now(repo.clock).plusDays(1).toString()
            val act=repo.saveActivity(a,"Trip",LocalDate.now(repo.clock).toString(),tomorrow,null,true)
            val rule=repo.dao.recurrences(a).single()
            repo.dao.putRecurrence(rule.copy(activityId=act));repo.ensureOccurrences(a,tomorrow.take(7))
            coordinator.rebuild(true)
            val manager=app.getSystemService(NotificationManager::class.java)
            assertTrue(manager.activeNotifications.any{it.tag=="book:$a"})
            assertTrue(manager.activeNotifications.any{it.tag=="book:$b"})
            assertTrue(repo.commitActivityDeletion(repo.previewActivityDeletion(a,act)));coordinator.rebuild(false)
            assertFalse(manager.activeNotifications.any{it.tag=="book:$a"})
            assertTrue(manager.activeNotifications.any{it.tag=="book:$b"})
            assertTrue(coordinator.plan.value.none{it.item.bookId==a})
            assertTrue(repo.snapshot(repo.dao.book(a)!!).first().events.isEmpty())
        }
    }

}
