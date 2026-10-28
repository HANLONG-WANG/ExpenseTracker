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
class ActivityManagementFlowTest {
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
        if (!visualEnabled()) return
        ui.waitForIdle()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(350,5000)
        val context=instrumentation.targetContext
        val file=File(context.getExternalFilesDir(null),"phase-e-activity-management/$name.png");file.parentFile!!.mkdirs();writeManagementVisualBounds(ui,file)
        val image=instrumentation.uiAutomation.takeScreenshot()
        file.outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
        image.recycle()
    }
    @Test fun deletionPreviewAndOriginalHistoryAcrossAppearances()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val path=File(context.cacheDir,"activity-ui-${UUID.randomUUID()}.preferences_pb")
        val prefs=AppPreferences(context,PreferenceDataStoreFactory.create(scope=scope,produceFile={path}))
        val repo=LedgerRepository(db);val vm=AppViewModel(repo,prefs, testReferenceRates(prefs,repo.clock))
        fun current()=(vm.state.value as? AppState.Ready)?.snapshot
        try {
            ui.setContent{NativeApp(vm)}
            for((theme,language) in testLooks(this@ActivityManagementFlowTest)) {
                val b=repo.createBook("生活 / 暮らし",Currency.JPY,theme,language)
                repo.dao.putBook(repo.dao.book(b)!!.copy(initialized=true))
                val a=repo.saveAccount(b,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,20000),"2026-10-01")
                val c=repo.saveCategory(b,"食事",EntryKind.EXPENSE,null)
                repo.dao.putBudget(BudgetEntity(b,"2026-10",c,c,8000))
                repo.setActivityMode(b,true)
                val act=repo.saveActivity(b,"秋の旅","2026-10-01","2026-11-30",Money(Currency.JPY,20000),true)
                val friend=repo.saveFriend(b,"友人");repo.addParticipant(b,act,friend)
                repo.saveEntry(b,EntryDraft(accountId=a,categoryId=c,amount="6000",date="2026-10-14",activityId=act,treatEnabled=true,treatFriends=listOf(friend)))
                repo.saveEntry(b,EntryDraft(accountId=a,categoryId=c,amount="3000",date="2026-11-01",activityId=act))
                assertTrue(repo.commitReserve(repo.previewReserve(b,ReserveInput(act,"交通","2026-10-20",Money(Currency.JPY,2000)))))
                prefs.select(b);ui.waitUntil(10000){current()?.book?.id==b}
                ui.runOnIdle{vm.openActivity.value=act;vm.navigate("life")}
                tap("activity-delete-preview")
                ui.waitUntil(10000){ui.onAllNodesWithTag("confirm-activity-deletion").fetchSemanticsNodes().isNotEmpty()}
                shot("$theme-$language-preview")
                assertNull(repo.dao.activity(b,act)!!.deletedAt)
                tap("activity-category-impact")
                ui.onNodeWithTag("confirm-activity-deletion").performScrollTo();shot("$theme-$language-confirm")
                tap("confirm-activity-deletion")
                ui.waitUntil(10000){current()?.activities?.firstOrNull{it.id==act}?.deletedAt!=null&&!vm.busy.value}
                tap("deleted-activity-$act");shot("$theme-$language-history")
                assertEquals(2,current()!!.events.size);assertEquals(11000L,current()!!.balance(current()!!.accounts.single()))
                assertEquals(6000L,current()!!.budgetProjection("2026-10").spentMinor);assertEquals(3000L,current()!!.friendBalances(act).single().iGive)
                ui.onAllNodesWithTag("activity-delete-preview").assertCountEquals(0)
                assertNull(vm.error.value)
            }
        }finally{vm.viewModelScope.cancel();scope.cancel();db.close();path.delete()}
    }
}
