package com.yohaku.expensetracker

import androidx.compose.runtime.*
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.domain.finance.*
import com.yohaku.expensetracker.ui.features.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.time.*

@RunWith(AndroidJUnit4::class)
class FinancialDraftFlowTest {
    @get:Rule val ui=createComposeRule()
    private lateinit var db:YohakuDatabase
    private lateinit var repo:LedgerRepository
    private lateinit var vm:AppViewModel
    private var mounted by mutableStateOf(true)
    private var epoch by mutableIntStateOf(0)
    @Before fun open() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        if(InstrumentationRegistry.getArguments().getString("processPhase")!="read")context.deleteDatabase("financial-draft-ui.db");db=Room.databaseBuilder(context,YohakuDatabase::class.java,"financial-draft-ui.db").build()
        repo=LedgerRepository(db,Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"),ZoneId.of("Asia/Tokyo")))
        vm=AppViewModel(repo,AppPreferences(context), testReferenceRates(AppPreferences(context),repo.clock))
        ui.setContent{if(mounted)key(epoch){NativeApp(vm)}}
    }
    @After fun close(){vm.viewModelScope.cancel();db.close();if(InstrumentationRegistry.getArguments().getString("processPhase")!="write")InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase("financial-draft-ui.db")}
    private fun tapTag(tag:String,ancestor:String?=null) {
        finishFinancialInputBeforeTap(ui,tag)
        val matcher=if(ancestor==null)hasTestTag(tag)else hasTestTag(tag) and hasAnyAncestor(hasTestTag(ancestor))
        ui.waitUntil(10000){!vm.busy.value&&ui.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()}
        val node=ui.onNode(matcher)
        if(!tag.startsWith("finance-tab-") && tag!="finance-back" && !(tag in listOf("header-book","header-settings") && ui.onAllNodesWithTag("finance-navigation").fetchSemanticsNodes().isNotEmpty()))node.scrollIfScrollable();ui.waitForIdle()
        if(ui.onAllNodesWithTag("sheet-header").fetchSemanticsNodes().isNotEmpty()) {
            val density=InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
            val header=ui.onNodeWithTag("sheet-header").getUnclippedBoundsInRoot().bottom.value
            val top=node.getUnclippedBoundsInRoot().top.value
            if(top<header+8)ui.onNodeWithTag("sheet-scroll").performSemanticsAction(SemanticsActions.ScrollBy){it(0f,-(header+8-top)*density)}
        }
        ui.waitForIdle()
        if(ui.onAllNodesWithTag("sheet-scroll").fetchSemanticsNodes().isNotEmpty()) {
            val density=InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
            val bottom=node.getUnclippedBoundsInRoot().bottom.value
            val viewport=ui.onNodeWithTag("sheet-scroll").getUnclippedBoundsInRoot().bottom.value
            if(bottom>viewport)ui.onNodeWithTag("sheet-scroll").performSemanticsAction(SemanticsActions.ScrollBy){it(0f,(bottom-viewport+8)*density)}
        }
        ui.waitForIdle();node.performClick();ui.waitForIdle()
    }
    private fun textField(label:String,ancestor:String?=null):SemanticsNodeInteraction {
        val matcher=hasTestTag("field-$label")
        return ui.onNode(if(ancestor==null)matcher else matcher and hasAnyAncestor(hasTestTag(ancestor)))
    }
    private fun shot(name:String) {
        if (!visualEnabled()) return
        ui.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(350,5000)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(context.getExternalFilesDir(null),"phase-c-drafts/$name.png");file.parentFile!!.mkdirs()
        file.outputStream().use{ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
        if(name.endsWith("-ime")||name.endsWith("-history")) {
            val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(file.parentFile,"$name-screen.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
        }
    }
    private fun restart() {
        ui.runOnIdle{mounted=false};ui.waitForIdle()
        vm.viewModelScope.cancel();db.close()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        db=Room.databaseBuilder(context,YohakuDatabase::class.java,"financial-draft-ui.db").build()
        repo=LedgerRepository(db,Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"),ZoneId.of("Asia/Tokyo")))
        vm=AppViewModel(repo,AppPreferences(context), testReferenceRates(AppPreferences(context),repo.clock))
        ui.runOnIdle{epoch++;mounted=true}
    }
    @Test fun closedAndReopenedBorrowingDraftSurvivesDatabaseAndViewModelRestartAndClearsOnlyAfterSaving() {
        for((theme,language) in testLooks(this@FinancialDraftFlowTest)) {
            fun text(zh:String,ja:String)=if(language=="ja")ja else zh
            val (book,loan,bank)=runBlocking {
                val b=repo.createBook("草稿 $theme $language",Currency.JPY,theme,language,startDate="2026-10-01")
                repo.updateBook(repo.dao.book(b)!!.copy(initialized=true))
                val a=repo.saveAccount(b,"借入先",AccountType.LOAN,Currency.JPY,Money(Currency.JPY,0),"2026-10-01")
                val bank=repo.saveAccount(b,"実資金",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
                vm.preferences.select(b);Triple(b,a,bank)
            }
            fun enterAccount() {
                ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book&&!vm.busy.value}
                ui.runOnIdle{vm.navigate("accounts")};tapTag("account-$loan");tapTag("account-financial")
            }
            fun resume(kind:String) {
                if(ui.onAllNodesWithTag("financial-draft-resume-$kind").fetchSemanticsNodes().isEmpty())tapTag("financial-drafts-open")
                tapTag("financial-draft-resume-$kind")
            }
            enterAccount();tapTag("finance-tab-manage");tapTag("finance-manage-contracts");tapTag("finance-add-borrowing")
            val name=text("合同名称","契約名");val principal=text("合同本金","契約元金")+" · JPY"
            textField(name).performScrollTo().performTextReplacement("循環の下書き")
            textField(principal).performScrollTo().performTextReplacement("3000")
            if(InstrumentationRegistry.getArguments().getString("realIme")=="true") {
                tapTag("field-$principal")
                ui.waitUntil(10000){android.view.inspector.WindowInspector.getGlobalWindowViews().any{androidx.core.view.ViewCompat.getRootWindowInsets(it)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true}}
                shot("$theme-$language-draft-ime")
            }
            tapTag("select-trigger-"+text("实际到账账户","実際の入金先口座"));ui.onNodeWithText("実資金 · JPY").performClick()
            for((label,value) in listOf(text("实际到账","実入金額") to "2900",text("预扣费用 / 不明留空","天引手数料・不明なら空欄") to "100",text("另付费用 / 未知留空","別払い手数料・不明なら空欄") to "0"))textField(label+" · JPY").performScrollTo().performTextReplacement(value)
            val ctx=FinancialDraftContext("BORROW",loan)
            ui.waitUntil(10000){runBlocking{db.financialDao().draft(book,ctx.slot())?.valuesJson?.contains("2900")==true}&&ui.onAllNodesWithText(text("正在保存草稿…","下書きを保存中…")).fetchSemanticsNodes().isEmpty()}
            tapTag("finance-flow-close");tapTag("finance-tab-overview");resume("BORROW")
            textField(principal).assertTextEquals("3000");textField(name).assertTextEquals("循環の下書き")
            restart();enterAccount();resume("BORROW")
            textField(principal).assertTextEquals("3000");textField(name).assertTextEquals("循環の下書き")
            textField(text("实际到账","実入金額")+" · JPY").assertTextEquals("2900")
            shot("$theme-$language-draft-resumed")
            tapTag("finance-borrow-preview");tapTag("finance-ledger-confirm")
            ui.waitUntil(10000){!vm.busy.value&&runBlocking{db.financialDao().draft(book,ctx.slot())==null}&&(vm.state.value as? AppState.Ready)?.snapshot?.visibleEvents?.size==1}
            tapTag("finance-tab-manage");tapTag("finance-manage-records");tapTag("finance-add-repayment")
            val paid=text("实际扣款总额","実出金総額")+" · JPY"
            textField(paid).performScrollTo().performTextReplacement("12.")
            val repay=FinancialDraftContext("REPAY",loan)
            ui.waitUntil(10000){runBlocking{db.financialDao().draft(book,repay.slot())?.valuesJson?.contains("12.")==true}}
            tapTag("finance-flow-close");tapTag("finance-tab-overview");resume("REPAY")
            textField(paid).assertTextEquals("12.");tapTag("financial-draft-discard")
            ui.waitUntil(10000){!vm.busy.value&&runBlocking{db.financialDao().drafts(book,loan).first().isEmpty()}}
            val s=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertEquals(1,s.events.size);assertEquals(3000L,s.balance(s.accounts.first{it.id==loan}));assertEquals(12900L,s.balance(s.accounts.first{it.id==bank}));assertEquals(100L,s.monthExpense("2026-10"))
            assertNull(vm.error.value)
        }
    }
    @Test fun processBoundaryPreservesOnlyDraftFields() {
        val phase=InstrumentationRegistry.getArguments().getString("processPhase")
        org.junit.Assume.assumeTrue(phase=="write"||phase=="read")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val pid=File(context.getExternalFilesDir(null),"financial-draft-probe-pid.txt")
        val (book,loan)=runBlocking {
            if(phase=="write") {
                val b=repo.createBook("进程恢复",Currency.JPY,"journal","zh",startDate="2026-10-01")
                repo.updateBook(repo.dao.book(b)!!.copy(initialized=true))
                repo.saveAccount(b,"资金",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
                val a=repo.saveAccount(b,"借款",AccountType.LOAN,Currency.JPY,Money(Currency.JPY,0),"2026-10-01")
                vm.preferences.select(b);pid.writeText(android.os.Process.myPid().toString());b to a
            }else {
                assertNotEquals(pid.readText().toInt(),android.os.Process.myPid())
                val b=repo.dao.bookOverviews("2026-10").first().single().book.id
                b to repo.dao.accounts(b).first().single{it.type==AccountType.LOAN.name}.id
            }
        }
        ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book&&!vm.busy.value}
        ui.runOnIdle{vm.navigate("accounts")};tapTag("account-$loan");tapTag("account-financial")
        if(phase=="write") {
            tapTag("finance-tab-manage");tapTag("finance-manage-contracts");tapTag("finance-add-borrowing")
            textField("合同名称").performScrollTo().performTextReplacement("进程恢复的未完成借款")
            textField("合同本金 · JPY").performScrollTo().performTextReplacement("4321")
            ui.waitUntil(10000){runBlocking{db.financialDao().draft(book,FinancialDraftContext("BORROW",loan).slot())?.valuesJson?.contains("4321")==true}}
        }else {
            tapTag("financial-drafts-open");tapTag("financial-draft-resume-BORROW")
            textField("合同名称").assertTextEquals("进程恢复的未完成借款")
            textField("合同本金 · JPY").assertTextEquals("4321")
            shot("process-restart-draft-restored")
            tapTag("financial-draft-discard")
            ui.waitUntil(10000){!vm.busy.value&&runBlocking{db.financialDao().drafts(book,loan).first().isEmpty()}}
            pid.delete()
        }
        val s=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
        assertTrue(s.events.isEmpty());assertEquals(0L,s.balance(s.accounts.first{it.id==loan}))
    }

    @Test fun closedRuleDraftCanBeKeptResumedOrDiscardedAcrossBookSwitches() {
        val (book,other,card)=runBlocking {
            val b=repo.createBook("元の帳簿",Currency.JPY,"journal","ja",startDate="2026-10-01")
            repo.updateBook(repo.dao.book(b)!!.copy(initialized=true))
            val a=repo.saveAccount(b,"カード",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,0),"2026-10-01")
            val other=repo.createBook("切替先",Currency.JPY,"objects","zh",startDate="2026-10-01")
            repo.updateBook(repo.dao.book(other)!!.copy(initialized=true));vm.preferences.select(b);Triple(b,other,a)
        }
        fun waitBook(id:String){ui.waitUntil(10000){!vm.busy.value&&(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==id}}
        fun openRuleDraft() {
            waitBook(book);ui.runOnIdle{vm.navigate("accounts")};tapTag("account-$card");tapTag("account-financial")
        }
        fun closeFlow() {
            ui.onAllNodes(hasTestTag("header-book") and hasAnyAncestor(hasTestTag("finance-flow"))).assertCountEquals(0)
            tapTag("finance-flow-close")
            ui.waitUntil(10000){vm.activeFinancialDraft.value==null}
        }
        fun switchBook(name:String,id:String) {
            tapTag("header-book")
            ui.onNodeWithText(name).performScrollTo().performClick()
            waitBook(id)
        }
        openRuleDraft();tapTag("finance-tab-manage");tapTag("finance-manage-rules");tapTag("finance-edit-SINGLE")
        textField("金融取引のタイムゾーン").performScrollTo().performTextReplacement("Asia/Shanghai")
        ui.runOnIdle{vm.appearance(book,theme="objects")}
        ui.waitUntil(10000){!vm.busy.value&&(vm.state.value as? AppState.Ready)?.snapshot?.book?.theme=="objects"}
        textField("金融取引のタイムゾーン").assertTextEquals("Asia/Shanghai")
        ui.runOnIdle{vm.appearance(book,theme="journal")};ui.waitUntil(10000){!vm.busy.value&&(vm.state.value as? AppState.Ready)?.snapshot?.book?.theme=="journal"}
        // Leaving the flow saves the draft. Cancelling book selection lets the user resume it.
        closeFlow();tapTag("header-book");tapTag("page-back");waitBook(book)
        tapTag("finance-edit-SINGLE")
        textField("金融取引のタイムゾーン").assertTextEquals("Asia/Shanghai")
        closeFlow();switchBook("切替先",other)
        val ctx=FinancialDraftContext("RULE",card,mode="SINGLE")
        assertNotNull(runBlocking{db.financialDao().draft(book,ctx.slot())})
        assertNull(runBlocking{db.financialDao().draft(other,ctx.slot())})
        switchBook("元の帳簿",book);openRuleDraft();tapTag("financial-drafts-open");tapTag("financial-draft-resume-RULE")
        textField("金融取引のタイムゾーン").assertTextEquals("Asia/Shanghai")
        ui.onNodeWithTag("finance-tab-manage").assertIsSelected()
        // A resumed rule returns to the same draft list, including a detour through limits.
        tapTag("finance-advanced");tapTag("finance-rule-open-limits");tapTag("finance-back")
        textField("金融取引のタイムゾーン").assertTextEquals("Asia/Shanghai")
        shot("journal-ja-draft-resumed-after-book-switch")
        tapTag("financial-draft-discard")
        ui.waitUntil(10000){!vm.busy.value&&vm.activeFinancialDraft.value==null}
        switchBook("切替先",other)
        assertNull(runBlocking{db.financialDao().draft(book,ctx.slot())})
        assertTrue(runBlocking{repo.snapshot(repo.dao.book(book)!!).first().events.isEmpty()})
        assertTrue(runBlocking{repo.snapshot(repo.dao.book(other)!!).first().events.isEmpty()})
    }

}
