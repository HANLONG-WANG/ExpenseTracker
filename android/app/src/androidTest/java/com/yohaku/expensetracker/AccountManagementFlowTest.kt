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
class AccountManagementFlowTest {
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
        val identity=ui.onNodeWithTag("account-operation-name").getUnclippedBoundsInRoot()
        val header=ui.onNodeWithTag("sheet-header").getUnclippedBoundsInRoot()
        assertTrue("Preview identity must be below the sticky header",identity.top>=header.bottom)
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(350,5000)
        val context=instrumentation.targetContext
        val file=File(context.getExternalFilesDir(null),"phase-e-account-management/$name.png");file.parentFile!!.mkdirs();writeManagementVisualBounds(ui,file)
        val image=instrumentation.uiAutomation.takeScreenshot()
        file.outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
        image.recycle()
    }
    @Test fun stopResumeAndDeletionEligibilityAcrossAppearances()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val path=File(context.cacheDir,"account-ui-${UUID.randomUUID()}.preferences_pb")
        val prefs=AppPreferences(context,PreferenceDataStoreFactory.create(scope=scope,produceFile={path}))
        val repo=LedgerRepository(db);val vm=AppViewModel(repo,prefs)
        fun current()=(vm.state.value as? AppState.Ready)?.snapshot
        try {
            ui.setContent{NativeApp(vm)}
            for(theme in listOf("magazine","journal","objects"))for(language in listOf("zh","ja")) {
                val b=repo.createBook("生活 / 暮らし",Currency.JPY,theme,language)
                repo.dao.putBook(repo.dao.book(b)!!.copy(initialized=true))
                val a=repo.saveAccount(b,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
                val empty=repo.saveAccount(b,"整理用",AccountType.CASH,Currency.JPY,Money(Currency.JPY,300),"2026-10-01")
                val c=repo.saveCategory(b,"食事",EntryKind.EXPENSE,null)
                repo.saveEntry(b,EntryDraft(accountId=a,categoryId=c,amount="500",date="2026-10-16"))
                prefs.select(b)
                ui.waitUntil(10000){current()?.book?.id==b}
                ui.runOnIdle{vm.navigate("accounts")}
                tap("account-$a");ui.revealOnPage("account-manage");tap("account-manage")
                ui.waitUntil(10000){ui.onAllNodesWithTag("confirm-account-operation").fetchSemanticsNodes().isNotEmpty()}
                shot("$theme-$language-stop")
                tap("confirm-account-operation")
                ui.waitUntil(10000){current()?.accounts?.firstOrNull{it.id==a}?.active==false&&!vm.busy.value}
                ui.revealOnPage("account-manage");tap("account-manage");tap("account-delete-preview")
                ui.onNodeWithTag("confirm-account-operation").assertIsNotEnabled()
                shot("$theme-$language-delete-blocked")
                tap("account-operation-back");tap("confirm-account-operation")
                ui.waitUntil(10000){current()?.accounts?.firstOrNull{it.id==a}?.active==true&&!vm.busy.value}
                ui.runOnIdle{vm.navigate("accounts")}
                tap("account-$empty");ui.revealOnPage("account-manage");tap("account-manage");tap("account-delete-preview")
                ui.onNodeWithTag("confirm-account-operation").assertIsEnabled()
                shot("$theme-$language-delete-empty")
                tap("confirm-account-operation")
                ui.waitUntil(10000){current()?.accounts?.none{it.id==empty}==true&&!vm.busy.value}
                assertNull(repo.dao.account(b,empty));assertEquals(1,current()!!.events.size);assertNull(vm.error.value)
            }
        }finally{vm.viewModelScope.cancel();scope.cancel();db.close();path.delete()}
    }
}
