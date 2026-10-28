package com.yohaku.expensetracker

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.platform.ReminderCoordinator
import com.yohaku.expensetracker.ui.features.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReminderSettingsFlowTest {
    @get:Rule val ui=createComposeRule()
    @Test fun sixAppearancesSavePreferencesAndRetainInputAcrossThemeChange()=runBlocking {
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as YohakuApplication
        val db=Room.inMemoryDatabaseBuilder(app,YohakuDatabase::class.java).build()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val path=File(app.cacheDir,"reminder-ui-${UUID.randomUUID()}.preferences_pb")
        val prefs=AppPreferences(app,PreferenceDataStoreFactory.create(scope=scope,produceFile={path}))
        val repo=LedgerRepository(db)
        val vm=AppViewModel(repo,prefs, testReferenceRates(prefs,repo.clock),reminders=ReminderCoordinator(app,repo,prefs))
        try {
            val book=repo.createBook("通知 / お知らせ",Currency.JPY)
            repo.dao.putBook(repo.dao.book(book)!!.copy(initialized=true))
            prefs.select(book)
            ui.setContent{NativeApp(vm)}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book}
            ui.runOnIdle{vm.navigate("notification-settings")}
            var minute=15
            for((theme,language) in testLooks(this@ReminderSettingsFlowTest)) {
                repo.updateAppearance(book,theme,language)
                ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.let{it.theme==theme&&it.language==language}==true}
                val savedTime="10:${minute++}"
                ui.onNodeWithTag("field-reminder-time").performScrollTo().performTextReplacement(savedTime)
                if (visualEnabled()) InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(350,5000)
                ui.onNodeWithTag("save-reminder-settings").performScrollTo().performClick()
                ui.waitUntil(10000){!vm.busy.value&&runBlocking{prefs.reminderSettings.first()[book]?.time==savedTime}}
                assertEquals(savedTime,prefs.reminderSettings.first()[book]!!.time)
                assertFalse(prefs.reminderSettings.first()[book]!!.enabled)
                try {
                    ui.waitUntil(10000){android.view.inspector.WindowInspector.getGlobalWindowViews().none{view->view.isShown&&view.hasWindowFocus()&&androidx.core.view.ViewCompat.getRootWindowInsets(view)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true}}
                }catch(e:Throwable) {
                    println("IME_TIMEOUT $theme/$language "+android.view.inspector.WindowInspector.getGlobalWindowViews().map{view->"${view.javaClass.simpleName} shown=${view.isShown} focus=${view.hasWindowFocus()} insets=${androidx.core.view.ViewCompat.getRootWindowInsets(view)}"})
                    val image=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot();File(app.getExternalFilesDir(null),"phase-e-ime-timeout.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
                    throw e
                }
                ui.onNodeWithTag("header-settings").performScrollTo()
                ui.waitForIdle()
                if (visualEnabled()) InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(350,5000)
                val dir=File(app.getExternalFilesDir(null),"phase-e-notification-settings").apply{mkdirs()}
                if (visualEnabled()) File(dir,"$theme-$language.png").outputStream().use{ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
                ui.onNodeWithTag("save-reminder-settings").performScrollTo()
                ui.waitForIdle()
                if (visualEnabled()) InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(350,5000)
                if (visualEnabled()) File(dir,"$theme-$language-form.png").outputStream().use{ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
            }
            // Draft settings survive theme changes and only explicit save commits them.
            ui.onNodeWithTag("field-reminder-time").performScrollTo().performTextReplacement("11:45")
            repo.updateAppearance(book,"magazine","zh")
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.theme=="magazine"}
            ui.onNodeWithTag("field-reminder-time").assertTextEquals("11:45")
            assertEquals("10:${minute-1}",prefs.reminderSettings.first()[book]!!.time)
            val other=repo.createBook("跳转目标",Currency.JPY)
            repo.dao.putBook(repo.dao.book(other)!!.copy(initialized=true))
            val target=com.yohaku.expensetracker.domain.reminders.ReminderItem(other,"occurrence:test","RECURRING","2026-10-17","JPY",1200,"Rent")
            vm.openReminderBook(other,repo.json.encodeToString(com.yohaku.expensetracker.domain.reminders.ReminderItem.serializer(),target))
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==other&&vm.route.value=="recurring"}
            ui.runOnIdle{vm.back()}
            assertEquals("reminders",vm.route.value)
            ui.runOnIdle{vm.back()}
            assertEquals("home",vm.route.value)
        }finally {
            vm.viewModelScope.cancel();scope.cancel();db.close();path.delete();app.reminders.rebuildSafely(false)
        }
    }
    @Test fun permissionIsRequestedOnlyAfterSaveAndDenialStillAllowsRealEntry()=runBlocking {
        Assume.assumeTrue("Run after externally revoking notification permission",InstrumentationRegistry.getArguments().getString("notificationPermission")=="true")
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val app=instrumentation.targetContext.applicationContext as YohakuApplication
        val db=Room.inMemoryDatabaseBuilder(app,YohakuDatabase::class.java).build()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val path=File(app.cacheDir,"permission-ui-${UUID.randomUUID()}.preferences_pb")
        val prefs=AppPreferences(app,PreferenceDataStoreFactory.create(scope=scope,produceFile={path}))
        val repo=LedgerRepository(db);val coordinator=ReminderCoordinator(app,repo,prefs);val vm=AppViewModel(repo,prefs, testReferenceRates(prefs,repo.clock),reminders=coordinator)
        val automation=instrumentation.uiAutomation
        val info=automation.serviceInfo;val flags=info.flags
        suspend fun permissionChoice(id:String) {
            withTimeout(10000){while(true) {
                val node=automation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId("com.android.permissioncontroller:id/"+id)?.firstOrNull()
                if(node!=null){
                    if (visualEnabled()) automation.waitForIdle(350,5000)
                    if (visualEnabled()) {val image=automation.takeScreenshot();File(app.getExternalFilesDir(null),"phase-e-notification-$id.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()}
                    assertTrue(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK));break
                }
                delay(100)
            }}
        }
        fun tap(tag:String){ui.onNodeWithTag(tag).performScrollTo().performClick();ui.waitForIdle()}
        try {
            assertFalse("Start with POST_NOTIFICATIONS revoked",coordinator.allowed())
            val b=repo.createBook("通知权限",Currency.JPY,"magazine","zh");repo.dao.putBook(repo.dao.book(b)!!.copy(initialized=true));prefs.select(b)
            val a=repo.saveAccount(b,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
            val c=repo.saveCategory(b,"食事",EntryKind.EXPENSE,null)
            val date=java.time.LocalDate.now(repo.clock)
            repo.saveRecurrence(b,RecurrenceInput("未完成安排",a,c,Money(Currency.JPY,1200),null,false,RecurrenceFrequency.MONTHLY,date.plusDays(1).toString()))
            ui.setContent{NativeApp(vm)}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==b}
            ui.runOnIdle{vm.navigate("notification-settings")}
            info.flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;automation.serviceInfo=info
            tap("reminder-enabled")
            assertNull(prefs.reminderSettings.first()[b])
            assertFalse(coordinator.allowed())
            assertTrue(automation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId("com.android.permissioncontroller:id/permission_allow_button").isNullOrEmpty())
            tap("save-reminder-settings")
            permissionChoice("permission_deny_button")
            ui.waitUntil(10000){!vm.busy.value}
            assertTrue(prefs.reminderSettings.first()[b]!!.enabled);assertFalse(coordinator.allowed())
            assertTrue(repo.reminderItems(prefs).any{it.bookId==b&&it.kind=="RECURRING"})
            ui.runOnIdle{vm.changeEntry(b,EntryDraft(accountId=a,categoryId=c,amount="500",date=date.toString()));vm.navigate("entry")}
            tap("entry-save-button")
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.visibleEvents?.size==1&&!vm.busy.value}
            assertEquals(9500L,repo.snapshot(repo.dao.book(b)!!).first().balance(repo.dao.account(b,a)!!))
            ui.runOnIdle{vm.navigate("notification-settings")}
            tap("save-reminder-settings")
            permissionChoice("permission_allow_button")
            ui.waitUntil(10000){coordinator.allowed()&&!vm.busy.value}
            assertTrue(prefs.reminderSettings.first()[b]!!.enabled);assertEquals(1,repo.snapshot(repo.dao.book(b)!!).first().events.size)
            assertNull(vm.error.value)
        }finally {
            info.flags=flags;automation.serviceInfo=info
            vm.viewModelScope.cancel();scope.cancel();db.close();path.delete();app.reminders.rebuildSafely(false)
        }
    }

    @Test fun sixAppearancesShowActualPermissionStateAndAPersistedFutureSchedule()=runBlocking {
        val mode=InstrumentationRegistry.getArguments().getString("reminderStates")
        Assume.assumeTrue("Run with external POST_NOTIFICATIONS granted or revoked",mode in listOf("granted","denied"))
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as YohakuApplication
        val db=Room.inMemoryDatabaseBuilder(app,YohakuDatabase::class.java).build()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val path=File(app.cacheDir,"reminder-states-${UUID.randomUUID()}.preferences_pb")
        val prefs=AppPreferences(app,PreferenceDataStoreFactory.create(scope=scope,produceFile={path}))
        val repo=LedgerRepository(db);val coordinator=ReminderCoordinator(app,repo,prefs);val vm=AppViewModel(repo,prefs, testReferenceRates(prefs,repo.clock),reminders=coordinator)
        try {
            assertEquals(mode=="granted",coordinator.allowed())
            val b=repo.createBook("通知 / お知らせ",Currency.JPY)
            repo.dao.putBook(repo.dao.book(b)!!.copy(initialized=true));prefs.select(b)
            val a=repo.saveAccount(b,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
            val c=repo.saveCategory(b,"生活",EntryKind.EXPENSE,null)
            val date=java.time.LocalDate.now(repo.clock).plusDays(2).toString()
            repo.saveRecurrence(b,RecurrenceInput("家賃・翌月の住まいを整える予定",a,c,Money(Currency.JPY,1200),null,false,RecurrenceFrequency.MONTHLY,date,endDate=date))
            prefs.saveReminderSettings(b,com.yohaku.expensetracker.domain.reminders.ReminderPreferences(enabled=true,repayment=false,deadline=false,budget=false,funds=false,recurringDays=listOf(1),time="23:59"))
            coordinator.rebuild(false)
            assertEquals(1,coordinator.plan.value.count{it.item.bookId==b})
            assertTrue(prefs.savedReminderPlan().any{it.item.bookId==b})
            ui.setContent{NativeApp(vm)}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==b}
            ui.runOnIdle{vm.navigate("notification-settings")}
            for((theme,language) in testLooks(this@ReminderSettingsFlowTest)) {
                repo.updateAppearance(b,theme,language)
                ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.let{it.theme==theme&&it.language==language}==true}
                val dir=File(app.getExternalFilesDir(null),"phase-e-notification-states").apply{mkdirs()}
                fun shot(suffix:String) {
                    if (!visualEnabled()) return
                    ui.waitForIdle();val automation=InstrumentationRegistry.getInstrumentation().uiAutomation;automation.waitForIdle(350,5000)
                    val image=automation.takeScreenshot();File(dir,"$theme-$language-$mode-$suffix.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
                }
                if(mode=="denied") {ui.onNodeWithTag("reminder-permission-status").performScrollTo();shot("permission")}
                else ui.onAllNodesWithTag("reminder-permission-status").assertCountEquals(0)
                ui.onNodeWithTag("reminder-plan-row").performScrollTo().assertTextContains("家賃",substring=true).assertTextContains("1,200",substring=true)
                shot("plan")
                assertTrue(repo.snapshot(repo.dao.book(b)!!).first().events.isEmpty())
                assertNull(vm.error.value)
            }
        }finally{vm.viewModelScope.cancel();scope.cancel();db.close();path.delete();app.reminders.rebuildSafely(false)}
    }

}
