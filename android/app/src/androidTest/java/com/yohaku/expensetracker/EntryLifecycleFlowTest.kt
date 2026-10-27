package com.yohaku.expensetracker

import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsActions
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
import com.yohaku.expensetracker.ui.features.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class EntryLifecycleFlowTest {
    @get:Rule val ui=createComposeRule()
    private fun tap(tag:String) {
        ui.waitUntil(10000){ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}
        val node=ui.onNodeWithTag(tag).performScrollTo();ui.waitForIdle()
        if(ui.onAllNodesWithTag("sheet-header").fetchSemanticsNodes().isNotEmpty()) {
            val density=InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
            val top=node.getUnclippedBoundsInRoot().top.value
            val header=ui.onNodeWithTag("sheet-header").getUnclippedBoundsInRoot().bottom.value
            if(top<header+8)ui.onNodeWithTag("sheet-scroll").performSemanticsAction(SemanticsActions.ScrollBy){it(0f,-(header+8-top)*density)}
        }
        ui.waitForIdle();node.performClick();ui.waitForIdle()
    }
    private fun shot(name:String) {
        ui.waitForIdle()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(350,5000)
        val context=instrumentation.targetContext
        val file=File(context.getExternalFilesDir(null),"phase-e-entry-lifecycle/$name.png");file.parentFile!!.mkdirs();writeManagementVisualBounds(ui,file)
        val image=instrumentation.uiAutomation.takeScreenshot()
        file.outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
        image.recycle()
    }
    @Test fun groupedTrashRestorePermanentPurgeAndEmptyAcrossAppearances()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val path=File(context.cacheDir,"entry-ui-${UUID.randomUUID()}.preferences_pb")
        val prefs=AppPreferences(context,PreferenceDataStoreFactory.create(scope=scope,produceFile={path}))
        val repo=LedgerRepository(db);val vm=AppViewModel(repo,prefs)
        fun current()=(vm.state.value as? AppState.Ready)?.snapshot
        try {
            ui.setContent{NativeApp(vm)}
            for(theme in listOf("magazine","journal","objects"))for(language in listOf("zh","ja")) {
                val b=repo.createBook("生活 / 暮らし",Currency.JPY,theme,language)
                repo.dao.putBook(repo.dao.book(b)!!.copy(initialized=true))
                val bank=repo.saveAccount(b,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
                val cash=repo.saveAccount(b,"現金",AccountType.CASH,Currency.JPY,Money(Currency.JPY,0),"2026-10-01")
                val c=repo.saveCategory(b,"食事",EntryKind.EXPENSE,null)
                val event=repo.saveEntry(b,EntryDraft(accountId=bank,categoryId=c,amount="1000",date="2026-10-14",merchant="喫茶 原名"))
                repo.saveRefund(b,RefundInput(event,Money(Currency.JPY,200),bank,Money(Currency.JPY,200),Money(Currency.JPY,200),"2026-10-15",true))
                val transfer=repo.saveMovement(b,EntryKind.TRANSFER,bank,cash,Money(Currency.JPY,500),Money(Currency.JPY,500),"2026-10-16")
                prefs.select(b);ui.waitUntil(10000){current()?.book?.id==b}
                ui.runOnIdle{vm.navigate("ledger")}
                ui.revealOnPage("event-$event");tap("event-$event");tap("record-trash-preview")
                ui.waitUntil(10000){ui.onAllNodesWithTag("confirm-entry-lifecycle").fetchSemanticsNodes().isNotEmpty()}
                shot("$theme-$language-trash");tap("confirm-entry-lifecycle")
                ui.waitUntil(10000){current()?.visibleEvents?.size==1&&!vm.busy.value}
                ui.runOnIdle{vm.navigate("trash")}
                tap("restore-$event");shot("$theme-$language-restore");tap("confirm-entry-lifecycle")
                ui.waitUntil(10000){current()?.visibleEvents?.size==3&&!vm.busy.value}
                ui.runOnIdle{vm.navigate("ledger")}
                ui.revealOnPage("event-$transfer");tap("event-$transfer");tap("record-trash-preview");tap("confirm-entry-lifecycle")
                ui.waitUntil(10000){current()?.visibleEvents?.size==2&&!vm.busy.value}
                ui.revealOnPage("event-$event");tap("event-$event");tap("record-trash-preview");tap("confirm-entry-lifecycle")
                ui.waitUntil(10000){current()?.visibleEvents?.isEmpty()==true&&!vm.busy.value}
                ui.runOnIdle{vm.navigate("trash")}
                tap("purge-$event");shot("$theme-$language-purge");tap("confirm-entry-lifecycle")
                ui.waitUntil(10000){current()?.events?.size==1&&!vm.busy.value}
                tap("empty-entry-trash");tap("confirm-entry-lifecycle")
                ui.waitUntil(10000){current()?.events?.isEmpty()==true&&!vm.busy.value}
                assertEquals(10000L,current()!!.balance(current()!!.accounts.first{it.id==bank}));assertTrue(current()!!.storedPostings.isEmpty());assertNull(vm.error.value)
            }
        }finally{vm.viewModelScope.cancel();scope.cancel();db.close();path.delete()}
    }
}
