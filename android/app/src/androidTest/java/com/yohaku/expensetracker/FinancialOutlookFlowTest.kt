package com.yohaku.expensetracker

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
class FinancialOutlookFlowTest {
    @get:Rule val ui=createComposeRule()
    private lateinit var db:YohakuDatabase
    private lateinit var repo:LedgerRepository
    private lateinit var vm:AppViewModel
    @Before fun open() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        repo=LedgerRepository(db,Clock.fixed(Instant.parse("2026-10-25T00:00:00Z"),ZoneId.of("Asia/Tokyo")))
        vm=AppViewModel(repo,AppPreferences(context))
        ui.setContent{NativeApp(vm)}
    }
    @After fun close(){vm.viewModelScope.cancel();db.close()}
    private fun tapTag(tag:String,ancestor:String?=null) {
        val matcher=if(ancestor==null)hasTestTag(tag)else hasTestTag(tag) and hasAnyAncestor(hasTestTag(ancestor))
        ui.waitUntil(10000){!vm.busy.value&&ui.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()}
        val node=ui.onNode(matcher)
        node.performScrollTo();ui.waitForIdle()
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
        ui.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(350,5000)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(context.getExternalFilesDir(null),"phase-c-outlook/$name.png");file.parentFile!!.mkdirs()
        file.outputStream().use{ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
        if(name.endsWith("-ime")||name.endsWith("-history")) {
            val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(file.parentFile,"$name-screen.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
        }
    }
    @Test fun riskCalendarAndIncomeActionsUseTheSameAccountsInEveryThemeAndLanguage() {
        for(theme in listOf("magazine","journal","objects").filter{InstrumentationRegistry.getArguments().getString("theme")?.let{v->v==it}?:true})for(language in listOf("zh","ja").filter{InstrumentationRegistry.getArguments().getString("language")?.let{v->v==it}?:true}) {
            fun text(zh:String,ja:String)=if(language=="ja")ja else zh
            val ids=runBlocking {
                val b=repo.createBook("資金 $theme $language",Currency.JPY,theme,language,startDate="2026-10-01")
                repo.updateBook(repo.dao.book(b)!!.copy(initialized=true))
                val bank=repo.saveAccount(b,"引落銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,100),"2026-10-01")
                val other=repo.saveAccount(b,"他口座",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
                val card=repo.saveAccount(b,"カード",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,500),"2026-10-01")
                val plan=repo.commitManualFinancialPlan(repo.previewManualFinancialPlan(b,ManualFinancialPlanInput(card,"翌月カード",FinanceMode.SINGLE,listOf(KnownBillInput("2026-11-05",500,500,0,bank)),principalMinor=500,opening=true)))!!
                val bill=db.financialDao().bills(b,plan).single().id
                val income=repo.commitExpectedIncome(repo.previewExpectedIncome(b,ExpectedIncomeInput("給与見込み",Money(Currency.JPY,600),"2026-10-31",bank)))!!
                vm.preferences.select(b);listOf(b,bank,other,card,bill,income)
            }
            val book=ids[0];val bank=ids[1];val other=ids[2];val bill=ids[4];val income=ids[5]
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book&&!vm.busy.value}
            ui.runOnIdle{vm.navigate("home")}
            ui.waitUntil(10000){ui.onAllNodesWithTag("cash-risk-$bank").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithTag("cash-risk-$bank").performScrollTo();shot("$theme-$language-home-risk")
            tapTag("cash-risk-open-$bank");shot("$theme-$language-actions")
            tapTag("risk-transfer-$bank")
            ui.waitUntil(10000){vm.entry.value?.kind==EntryKind.TRANSFER}
            assertEquals(other,vm.entry.value!!.accountId);assertEquals(bank,vm.entry.value!!.toAccountId)
            ui.runOnIdle{vm.leaveEntry(book,false,"home")}
            ui.waitUntil(10000){!vm.busy.value&&(vm.state.value as? AppState.Ready)?.snapshot?.draft==null}
            runBlocking{repo.saveDraft(book,EntryDraft(kind=EntryKind.INCOME,accountId=other,amount="42",date="2026-10-25",note="unfinished"))}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.draft?.note=="unfinished"}
            tapTag("cash-risk-open-$bank");tapTag("risk-actual-income-$bank")
            ui.onNodeWithText(text("继续这笔草稿","下書きを続ける")).performClick()
            ui.waitUntil(10000){vm.entry.value?.note=="unfinished"}
            assertEquals(other,vm.entry.value!!.accountId);assertEquals("42",vm.entry.value!!.amount)
            ui.runOnIdle{vm.leaveEntry(book,false,"home")}
            ui.waitUntil(10000){!vm.busy.value&&(vm.state.value as? AppState.Ready)?.snapshot?.draft==null}
            tapTag("cash-risk-open-$bank");tapTag("risk-actual-income-$bank")
            ui.waitUntil(10000){vm.entry.value?.kind==EntryKind.INCOME}
            assertEquals(bank,vm.entry.value!!.accountId);assertEquals("",vm.entry.value!!.amount)
            ui.runOnIdle{vm.leaveEntry(book,false,"home")}
            ui.waitUntil(10000){!vm.busy.value&&(vm.state.value as? AppState.Ready)?.snapshot?.draft==null}
            tapTag("cash-risk-open-$bank");tapTag("risk-expected-income-$bank")
            ui.onNodeWithTag("select-trigger-"+text("到账账户","入金口座")).assert(hasText("引落銀行 · JPY"))
            if(InstrumentationRegistry.getArguments().getString("realIme")=="true") {
                tapTag("field-"+text("预计收入名称","収入予定名"))
                ui.waitUntil(10000){android.view.inspector.WindowInspector.getGlobalWindowViews().any{androidx.core.view.ViewCompat.getRootWindowInsets(it)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true}}
                shot("$theme-$language-expected-ime")
                InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                ui.waitForIdle()
            }
            tapTag("sheet-close")
            ui.runOnIdle{vm.navigate("accounts")};tapTag("accounts-calendar")
            tapTag("payment-calendar-next");ui.onNodeWithTag("payment-calendar-month").assertTextEquals("2026-11")
            ui.onNodeWithTag("outlook-item-bill:$bill").performScrollTo();shot("$theme-$language-calendar")
            tapTag("outlook-item-bill:$bill")
            ui.waitUntil(10000){vm.route.value=="rules"}
            ui.onNodeWithTag("finance-statement-month").assert(hasAnyDescendant(hasText("2026-11")))
            ui.onNodeWithTag("statement-bill-$bill").assertExists()
            ui.runOnIdle{vm.back()};tapTag("payment-calendar-prev")
            tapTag("outlook-item-income:$income");tapTag("outlook-income-edit")
            textField(text("预计收入名称","収入予定名")).assertTextEquals("給与見込み")
            tapTag("sheet-close")
            tapTag("calendar-open-forecast")
            ui.waitUntil(10000){vm.route.value=="forecast"&&ui.onAllNodesWithTag("cash-risk-$bank").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithTag("cash-risk-$bank").performScrollTo();shot("$theme-$language-forecast")
            val snapshot=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertTrue(snapshot.events.isEmpty());assertEquals(100L,snapshot.balance(snapshot.accounts.first{it.id==bank}));assertEquals(600L,snapshot.expectedIncomes.single().amountMinor)
            tapTag("cash-risk-open-$bank");tapTag("risk-transfer-$bank")
            ui.waitUntil(10000){vm.entry.value?.toAccountId==bank}
            textField(text("实际扣款总额","実際の出金総額")+" · JPY").performScrollTo().performTextReplacement("400")
            tapTag("select-trigger-"+text("资金状态","資金の状態"))
            ui.onNodeWithText(text("已转出，已到账","出金・入金済")).performScrollTo().performClick()
            ui.onNode(hasText(text("保存这笔转账","この振替を保存")) and hasClickAction()).performScrollTo().performClick()
            ui.waitUntil(10000){vm.entryReview.value!=null&&!vm.busy.value}
            ui.onNode(hasText(text("确认并保存","確認して保存")) and hasClickAction()).performScrollTo().performClick()
            ui.waitUntil(10000){vm.entry.value==null&&!vm.busy.value&&vm.route.value=="ledger"}
            ui.onNodeWithTag("nav-accounts").performClick();tapTag("accounts-forecast")
            ui.waitUntil(10000){ui.onAllNodesWithTag("forecast-budget").fetchSemanticsNodes().isNotEmpty()&&ui.onAllNodesWithTag("cash-risk-$bank").fetchSemanticsNodes().isEmpty()}
            val resolved=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertEquals(500L,resolved.balance(resolved.accounts.first{it.id==bank}));assertEquals(9600L,resolved.balance(resolved.accounts.first{it.id==other}))
            assertEquals(1,resolved.events.size);assertEquals("TRANSFER",resolved.events.single().kind)
            assertEquals("PENDING",resolved.expectedIncomes.single().status);assertEquals(600L,resolved.expectedIncomes.single().amountMinor)
            val risk=resolved.cashTimeline(runBlocking{db.financialDao().graph(book).first()!!},LocalDate.parse("2026-10-25"),LocalDate.parse("2026-11-30")).accountRisks().first{it.opening.account.id==bank}
            assertEquals(0L,risk.currentGapMinor);assertEquals(0L,risk.maximumGapMinor);assertEquals(0L,risk.includingIncomeGapMinor)
            ui.onNodeWithTag("page-scroll").performSemanticsAction(SemanticsActions.ScrollBy){it(0f,-100000f)}
            shot("$theme-$language-risk-resolved")
            assertNull(vm.error.value)
        }
    }
    @Test fun budgetStatesKeepActualOverrunPredictionAndUnknownSeparate() {
        val args=InstrumentationRegistry.getArguments()
        for(theme in listOf("magazine","journal","objects").filter{args.getString("theme")?.let{v->v==it}?:true})
        for(language in listOf("zh","ja").filter{args.getString("language")?.let{v->v==it}?:true}) {
            val ids=runBlocking {
                val b=repo.createBook("预算状态",Currency.JPY,theme,language,startDate="2026-10-24")
                repo.updateBook(repo.dao.book(b)!!.copy(initialized=true,budgetMinor=500))
                val bank=repo.saveAccount(b,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-24")
                val card=repo.saveAccount(b,"カード",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,500),"2026-10-24")
                val category=repo.saveCategory(b,"日常",EntryKind.EXPENSE,null)
                repo.saveEntry(b,EntryDraft(accountId=bank,categoryId=category,amount="100",date="2026-10-24"))
                vm.preferences.select(b);listOf(b,bank,card,category)
            }
            val (b,bank,card,category)=ids
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==b&&!vm.busy.value}
            ui.runOnIdle{vm.navigate("forecast")}
            fun check(state:String,amount:String) {
                ui.waitUntil(10000){ui.onAllNodes(hasTestTag("forecast-budget-amount") and hasText(amount)).fetchSemanticsNodes().isNotEmpty()&&ui.onAllNodes(hasTestTag("forecast-budget-status") and hasText(state)).fetchSemanticsNodes().isNotEmpty()}
                ui.onNodeWithTag("forecast-budget-status").assertTextEquals(state)
                ui.onNodeWithTag("forecast-budget-low-confidence").assertExists()
                ui.onNodeWithTag("forecast-budget-amount").performScrollTo()
                shot("$theme-$language-budget-"+when(amount){"¥0"->"normal";"¥100"->if(state.contains("已经")||state.contains("発生"))"actual"else"projected";else->"unknown"})
            }
            check(if(language=="ja")"現在のペースでは予算内"else"按目前节奏仍有余量","¥0")
            runBlocking{repo.updateBook(repo.dao.book(b)!!.copy(budgetMinor=300))}
            check(if(language=="ja")"月末に予算超過の見込み"else"月底可能超预算","¥100")
            runBlocking{repo.saveEntry(b,EntryDraft(accountId=bank,categoryId=category,amount="300",date="2026-10-25"))}
            check(if(language=="ja")"予算超過が発生"else"已经超预算","¥100")
            ui.onNodeWithTag("forecast-budget").assert(hasAnyDescendant(hasText("¥1,300")))
            runBlocking {
                repo.updateBook(repo.dao.book(b)!!.copy(budgetMinor=500))
                repo.commitManualFinancialPlan(repo.previewManualFinancialPlan(b,ManualFinancialPlanInput(card,"未拆分费用",FinanceMode.SINGLE,
                    listOf(KnownBillInput("2026-10-28",500,null,null,bank)),500,opening=true)))
            }
            val configuration=InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration
            val compact=configuration.screenWidthDp<=360||configuration.fontScale>1.1f
            check(if(compact)if(language=="ja")"情報不足・要確認"else"资料待补齐"else if(language=="ja")"情報不足でリスク未確定"else"资料不足，风险未确定",
                if(compact)if(language=="ja")"不明"else"未知"else if(language=="ja")"不明・情報未補足"else"未知 / 资料待补齐")
            val snapshot=runBlocking{repo.snapshot(repo.dao.book(b)!!).first()}
            assertEquals(2,snapshot.events.size);assertEquals(400L,snapshot.monthExpense("2026-10"))
            assertEquals(9600L,snapshot.balance(snapshot.accounts.first{it.id==bank}))
        }
    }

}
