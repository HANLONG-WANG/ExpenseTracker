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
class FinancialFlowTest {
    @get:Rule val ui=createComposeRule()
    private lateinit var db:YohakuDatabase
    private lateinit var repo:LedgerRepository
    private lateinit var vm:AppViewModel
    @Before fun open() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        repo=LedgerRepository(db,Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"),ZoneId.of("Asia/Tokyo")))
        vm=AppViewModel(repo,AppPreferences(context), testReferenceRates(AppPreferences(context),repo.clock))
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
        if (!visualEnabled()) return
        ui.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(350,5000)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(context.getExternalFilesDir(null),"phase-c-rules/$name.png");file.parentFile!!.mkdirs()
        file.outputStream().use{ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
        if(name.endsWith("-ime")||name.endsWith("-history")) {
            val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(file.parentFile,"$name-screen.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
        }
    }
    @Test fun compareAndRecordAnAppliedArrangementWithoutCreatingCashInEveryThemeAndLanguage() {
        for((theme,language) in testLooks(this@FinancialFlowTest)) {
            fun text(zh:String,ja:String)=if(language=="ja")ja else zh
            val ids=runBlocking {
                val b=repo.createBook("支払変更 $theme $language",Currency.JPY,theme,language,startDate="2026-10-01")
                repo.updateBook(repo.dao.book(b)!!.copy(initialized=true))
                val bank=repo.saveAccount(b,"支払銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,7000),"2026-10-01")
                val card=repo.saveAccount(b,"カード",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,0),"2026-10-01")
                val category=repo.saveCategory(b,"買物",EntryKind.EXPENSE,null)
                val source=repo.saveEntry(b,EntryDraft(accountId=card,categoryId=category,amount="10000",date="2026-10-15"))
                val rule=FinancialRuleVersion("",b,card,FinanceMode.SINGLE,"2026-10-01","Asia/Tokyo",billing=BillingRule(31,true,1,27,bank))
                repo.commitFinancialRule(repo.previewFinancialRule(b,card,rule))
                val original=repo.commitFinancialPrediction(repo.previewFinancialPrediction(b,FinancialPredictionInput(card,"一括の買物",FinanceMode.SINGLE,FinancialScheduleTerms("2026-10-15",listOf("2026-11-27")),sourceEventId=source,paymentAccountId=bank,currentArrangementConfirmed=true)))!!
                val bill=db.financialDao().bills(b,original).single()
                repo.commitFinancialBillConfirmation(repo.previewFinancialBillConfirmation(b,bill.id,KnownBillInput(bill.date,10000,10000,0,bank)))
                repo.commitFinancialRule(repo.previewFinancialRule(b,card,rule.copy(mode=FinanceMode.INSTALLMENT,installment=InstallmentRule(listOf(3),InstallmentFeeMethod.TOTAL_AMOUNT,totalFeeMinor=201,principalRemainder=RemainderPlacement.LAST,feeRemainder=RemainderPlacement.FIRST,roundingMode="DOWN"),deadlines=listOf(ChangeDeadline(bill.id,FinanceMode.INSTALLMENT,"2026-10-20","15:00","Asia/Tokyo")))))
                vm.preferences.select(b);listOf(b,bank,card,original,bill.id)
            }
            val (book,bank,card,original,billId)=ids
            val originalBill=runBlocking{db.financialDao().bill(book,billId)!!}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book&&!vm.busy.value}
            ui.runOnIdle{vm.navigate("accounts")}
            tapTag("account-$card");tapTag("account-financial");tapTag("analysis-tab-plans");tapTag("finance-compare-plan-$original")
            textField(text("预测期数","予測回数")).performScrollTo().performTextReplacement("3")
            tapTag("select-trigger-"+text("原方案剩余费用如何处理","元の予定の残手数料の扱い"));ui.onNodeWithText(text("保留原费用","元の手数料を保持")).performClick()
            val feeLabel=text("另列变更费用 / 未知留空，已知无费用填0","別途変更手数料・不明は空欄、無料は0")+" · JPY"
            val field=textField(feeLabel)
            field.performScrollTo()
            if(InstrumentationRegistry.getArguments().getString("realIme")=="true")tapTag("field-$feeLabel")
            field.performTextReplacement("0")
            if(InstrumentationRegistry.getArguments().getString("realIme")=="true") {
                ui.waitUntil(10000){android.view.inspector.WindowInspector.getGlobalWindowViews().any{androidx.core.view.ViewCompat.getRootWindowInsets(it)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true}}
                shot("$theme-$language-arrangement-ime")
            }
            tapTag("arrangement-generate");tapTag("arrangement-preview")
            ui.waitUntil(10000){vm.financialArrangementReview.value!=null&&!vm.busy.value}
            val review=vm.financialArrangementReview.value!!
            assertTrue(review.calculation.toString(),review.calculation is Calculation.Ready)
            assertEquals(6600L,review.comparison!!.currentReductionMinor);assertEquals(201L,review.comparison!!.totalExtraFeeMinor)
            shot("$theme-$language-arrangement-comparison")
            val proposedTitle=text("新方案","新プラン")
            val table=ui.onNodeWithTag("arrangement-table-scroll-$proposedTitle")
            table.performScrollTo();ui.waitForIdle()
            for((i,remaining) in listOf(6667L,3334L,0L).withIndex()) {
                ui.onNodeWithTag("arrangement-cell-$proposedTitle-$i-3").assertTextEquals(Money(Currency.JPY,remaining).display())
            }
            val cells=ui.onAllNodes(hasAnyAncestor(hasTestTag("arrangement-table-$proposedTitle")) and hasText("",substring=true))
            for(i in cells.fetchSemanticsNodes().indices)cells[i].performSemanticsAction(SemanticsActions.GetTextLayoutResult){action->
                val layouts=mutableListOf<androidx.compose.ui.text.TextLayoutResult>();action(layouts)
                assertTrue("table dates and amounts must stay on one line",layouts.all{it.lineCount==1})
            }
            table.performSemanticsAction(SemanticsActions.ScrollBy){it(-10000f,0f)}
            shot("$theme-$language-arrangement-table-start")
            table.performTouchInput{swipeLeft()};ui.waitForIdle()
            ui.onNodeWithTag("arrangement-cell-$proposedTitle-0-3").assertIsDisplayed()
            shot("$theme-$language-arrangement-table-end")
            ui.onNodeWithTag("arrangement-funding-$bank").performScrollTo();ui.waitForIdle()
            ui.onAllNodes(hasText("¥3,000 → ¥3,201") and hasAnyAncestor(hasTestTag("arrangement-funding-$bank"))).assertCountEquals(2)
            shot("$theme-$language-arrangement-funding")
            tapTag("arrangement-back")
            textField(text("预测期数","予測回数")).assertTextEquals("3");field.assertTextEquals("0")
            assertTrue(runBlocking{db.financialDao().graph(book).first()!!.arrangements.isEmpty()})
            tapTag("arrangement-preview");tapTag("arrangement-record-applied")
            ui.onNodeWithTag("arrangement-confirm").assertIsNotEnabled()
            tapTag("arrangement-platform-confirmed")
            runBlocking{repo.saveAccount(book,"支払銀行 更新",AccountType.BANK,Currency.JPY,Money(Currency.JPY,7000),"2026-10-01",bank)}
            tapTag("arrangement-confirm")
            ui.waitUntil(10000){vm.financialReviewRefreshed.value&&!vm.busy.value}
            assertTrue(runBlocking{db.financialDao().graph(book).first()!!.arrangements.isEmpty()})
            tapTag("arrangement-record-applied");tapTag("arrangement-platform-confirmed")
            shot("$theme-$language-arrangement-confirm")
            tapTag("arrangement-confirm")
            ui.waitUntil(10000){vm.financialArrangementReview.value==null&&!vm.busy.value&&vm.financialState.value?.arrangements?.size==1}
            val graph=vm.financialState.value!!;val target=graph.arrangements.single().targetPlanId
            val snapshot=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertEquals(7000L,snapshot.balance(snapshot.accounts.first{it.id==bank}));assertEquals(10000L,snapshot.balance(snapshot.accounts.first{it.id==card}));assertEquals(10000L,snapshot.monthExpense("2026-10"));assertEquals(1,snapshot.events.size)
            assertEquals(originalBill,runBlocking{db.financialDao().bill(book,billId)})
            assertEquals(0L,snapshot.billOutstanding(originalBill).totalMinor)
            assertEquals(listOf(3400L,3400L,3401L),graph.bills.filter{it.planId==target}.map{it.totalMinor})
            assertFalse(repo.financialPredictionStale(snapshot,graph,graph.plans.first{it.id==target}))
            shot("$theme-$language-arrangement-saved")
            tapTag("finance-plan-history-$target")
            shot("$theme-$language-arrangement-history-open")
            try {tapTag("finance-plan-version-open-1")}catch(e:Exception){shot("$theme-$language-arrangement-history-failure");throw e}
            shot("$theme-$language-arrangement-history")
            tapTag("sheet-close");assertNull(vm.error.value)
        }
    }

    @Test fun repaymentPreviewCommitsLinkedPredictionsAndKeepsVersionHistoryInEveryThemeAndLanguage() {
        for((theme,language) in testLooks(this@FinancialFlowTest)) {
            fun text(zh:String,ja:String)=if(language=="ja")ja else zh
            val ids=runBlocking {
                val b=repo.createBook("連動 $theme $language",Currency.JPY,theme,language,startDate="2026-09-01")
                repo.updateBook(repo.dao.book(b)!!.copy(initialized=true))
                val bank=repo.saveAccount(b,"支払銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,200000),"2026-09-01")
                val card=repo.saveAccount(b,"既存カード",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,100000),"2026-09-01")
                repo.commitFinancialRule(repo.previewFinancialRule(b,card,FinancialRuleVersion("",b,card,FinanceMode.REVOLVING,"2026-09-01","Asia/Tokyo",billing=BillingRule(30,true,1,10,bank),revolving=RevolvingRule(InterestRule("0.18",ChargeFrequency.DAILY,BalanceBasis.EVENT_BALANCE,true,true,365,ChargeRounding.PERIOD_TOTAL,"DOWN",FirstCharge.NORMAL),PaymentMethod.FIXED_PRINCIPAL,10000,allocation=AllocationOrder.FEES_FIRST,automatic=false))))
                val input=FinancialPredictionInput(card,"実返済と予測",FinanceMode.REVOLVING,FinancialScheduleTerms("2026-09-01",listOf("2026-10-10","2026-11-10","2026-12-10"),listOf(AccrualWindow("2026-09-01","2026-09-30"),AccrualWindow("2026-10-01","2026-10-31"),AccrualWindow("2026-11-01","2026-11-30"))),openingPrincipalMinor=100000,paymentAccountId=bank,currentArrangementConfirmed=true)
                val plan=repo.commitFinancialPrediction(repo.previewFinancialPrediction(b,input))!!
                val first=db.financialDao().bills(b,plan).first()
                repo.commitFinancialBillConfirmation(repo.previewFinancialBillConfirmation(b,first.id,KnownBillInput(first.date,21479,20000,1479,bank)))
                vm.preferences.select(b);listOf(b,bank,card,plan,first.id)
            }
            val (book,bank,card,plan,first)=ids
            val original=runBlocking{db.financialDao().bills(book,plan)}
            val versions=runBlocking{db.financialDao().planRevisions(book,plan).first()}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book&&!vm.busy.value}
            ui.runOnIdle{vm.navigate("accounts")}
            tapTag("account-$card");tapTag("account-financial");tapTag("analysis-tab-plans");tapTag("finance-pay-bill-$first")
            val paid=textField(text("实际扣款总额","実出金総額")+" · JPY")
            paid.performScrollTo()
            if(InstrumentationRegistry.getArguments().getString("realIme")=="true")paid.performClick()
            paid.performTextReplacement("5100")
            if(InstrumentationRegistry.getArguments().getString("realIme")=="true") {
                ui.waitUntil(10000){android.view.inspector.WindowInspector.getGlobalWindowViews().any{androidx.core.view.ViewCompat.getRootWindowInsets(it)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true}}
                shot("$theme-$language-linked-plan-ime")
            }
            tapTag("finance-repay-cleared")
            for((label,value) in listOf(text("平台实际收到","返済先の実受取額") to "5100",text("清偿本金","返済元金") to "5000",text("实付利息","実支払利息") to "100",text("平台费用","返済先手数料") to "0"))textField(label+" · JPY").performScrollTo().performTextReplacement(value)
            for((label,value) in listOf(text("分配本金","充当元金") to "5000",text("分配利息","充当利息") to "100",text("分配费用","充当手数料") to "0"))textField(label,"finance-repay-allocation-0").performScrollTo().performTextReplacement(value)
            tapTag("finance-repay-preview")
            ui.waitUntil(10000){vm.financialLedgerReview.value!=null&&!vm.busy.value}
            val impact=vm.financialLedgerReview.value!!.planImpacts.single()
            assertTrue(impact.updatesPrediction);assertEquals(listOf(1479L,1486L,1301L),impact.nextBills.map{it.feeMinor});assertEquals(original.first(),impact.nextBills.first())
            ui.onNodeWithTag("finance-plan-impact-$plan").performScrollTo();shot("$theme-$language-linked-plan-summary")
            tapTag("finance-ledger-back")
            textField(text("实际扣款总额","実出金総額")+" · JPY").assertTextEquals("5100")
            assertTrue(runBlocking{repo.snapshot(repo.dao.book(book)!!).first().events.isEmpty()});assertEquals(original,runBlocking{db.financialDao().bills(book,plan)})
            tapTag("finance-repay-preview");tapTag("finance-plan-impact-detail-$plan")
            ui.onNodeWithTag("finance-plan-impact-period-$plan-3").performScrollTo();shot("$theme-$language-linked-plan-period")
            runBlocking{repo.saveAccount(book,"支払銀行 更新",AccountType.BANK,Currency.JPY,Money(Currency.JPY,200000),"2026-09-01",bank)}
            tapTag("finance-ledger-confirm")
            ui.waitUntil(10000){vm.financialReviewRefreshed.value&&!vm.busy.value}
            assertTrue(runBlocking{repo.snapshot(repo.dao.book(book)!!).first().events.isEmpty()});assertEquals(versions,runBlocking{db.financialDao().planRevisions(book,plan).first()})
            tapTag("finance-ledger-confirm")
            ui.waitUntil(10000){vm.financialLedgerReview.value==null&&!vm.busy.value&&vm.financialState.value?.plans?.firstOrNull{it.id==plan}?.revision==3}
            val snapshot=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertEquals(194900L,snapshot.balance(snapshot.accounts.first{it.id==bank}));assertEquals(95000L,snapshot.balance(snapshot.accounts.first{it.id==card}));assertEquals(100L,snapshot.monthExpense("2026-10"));assertEquals(1,snapshot.events.size)
            assertEquals(original.first(),runBlocking{db.financialDao().bill(book,first)})
            ui.onNodeWithTag("prediction-stale-$plan").assertDoesNotExist()
            tapTag("finance-plan-history-$plan");tapTag("finance-plan-version-open-3")
            ui.onNode(hasText("¥16,379") and hasAnyAncestor(hasTestTag("finance-plan-history-outstanding-3-$first"))).assertExists()
            ui.onNodeWithTag("finance-plan-version-3").performScrollTo();shot("$theme-$language-linked-plan-history")
            tapTag("finance-plan-version-open-2")
            ui.onNode(hasText("¥21,479") and hasAnyAncestor(hasTestTag("finance-plan-history-outstanding-2-$first"))).assertExists()
            ui.onNodeWithTag("finance-plan-version-2").performScrollTo();shot("$theme-$language-linked-plan-history-before")
            assertEquals(versions,runBlocking{db.financialDao().planRevisions(book,plan).first().drop(1)})
            tapTag("sheet-close");assertNull(vm.error.value)
        }
    }

    @Test fun actualClearingRecalculatesFuturePeriodsInEveryThemeAndLanguage() {
        for((theme,language) in testLooks(this@FinancialFlowTest)) {
            fun text(zh:String,ja:String)=if(language=="ja")ja else zh
            val ids=runBlocking {
                val b=repo.createBook("再計算 $theme $language",Currency.JPY,theme,language,startDate="2026-09-01")
                repo.updateBook(repo.dao.book(b)!!.copy(initialized=true))
                val bank=repo.saveAccount(b,"支払銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,200000),"2026-09-01")
                val card=repo.saveAccount(b,"既存カード",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,100000),"2026-09-01")
                repo.commitFinancialRule(repo.previewFinancialRule(b,card,FinancialRuleVersion("",b,card,FinanceMode.REVOLVING,"2026-09-01","Asia/Tokyo",billing=BillingRule(30,true,1,10,bank),revolving=RevolvingRule(InterestRule("0.18",ChargeFrequency.DAILY,BalanceBasis.EVENT_BALANCE,true,true,365,ChargeRounding.PERIOD_TOTAL,"DOWN",FirstCharge.NORMAL),PaymentMethod.FIXED_PRINCIPAL,10000,allocation=AllocationOrder.FEES_FIRST,automatic=false))))
                val input=FinancialPredictionInput(card,"実返済を反映",FinanceMode.REVOLVING,FinancialScheduleTerms("2026-09-01",listOf("2026-10-10","2026-11-10","2026-12-10"),listOf(AccrualWindow("2026-09-01","2026-09-30"),AccrualWindow("2026-10-01","2026-10-31"),AccrualWindow("2026-11-01","2026-11-30"))),openingPrincipalMinor=100000,paymentAccountId=bank,currentArrangementConfirmed=true)
                val plan=repo.commitFinancialPrediction(repo.previewFinancialPrediction(b,input))!!
                val first=db.financialDao().bills(b,plan).first()
                repo.commitFinancialBillConfirmation(repo.previewFinancialBillConfirmation(b,first.id,KnownBillInput(first.date,21479,20000,1479,bank)))
                repo.commitFinancialLedger(repo.previewRepayment(b,FinancialRepaymentInput(bank,card,Money(Currency.JPY,5100),"2026-10-12",Money(Currency.JPY,5100),Money(Currency.JPY,5000),Money(Currency.JPY,100),Money(Currency.JPY,0),platformConfirmed=true,clearingDate="2026-10-12",type=RepaymentType.PARTIAL,allocations=listOf(RepaymentAllocationInput(first.id,null,5000,100,0)))))
                vm.preferences.select(b);listOf(b,bank,card,plan,first.id)
            }
            val (book,bank,card,plan,first)=ids
            val confirmed=runBlocking{db.financialDao().bill(book,first)!!}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book&&!vm.busy.value}
            ui.runOnIdle{vm.navigate("accounts")}
            tapTag("account-$card");tapTag("account-financial");tapTag("analysis-tab-plans")
            ui.onNodeWithTag("prediction-stale-$plan").assertDoesNotExist()
            tapTag("finance-recalculate-$plan")
            if(InstrumentationRegistry.getArguments().getString("realIme")=="true") {
                val field=textField(text("计划名称","プラン名"))
                field.performScrollTo().performClick().performTextReplacement("実返済を反映")
                ui.waitUntil(10000){android.view.inspector.WindowInspector.getGlobalWindowViews().any{androidx.core.view.ViewCompat.getRootWindowInsets(it)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true}}
                shot("$theme-$language-reconciled-ime")
            }
            tapTag("prediction-preview")
            ui.waitUntil(10000){vm.financialPredictionReview.value!=null&&!vm.busy.value}
            val review=vm.financialPredictionReview.value!!
            assertTrue(review.calculation is Calculation.Ready)
            assertEquals(listOf(1479L,1479L,1301L),review.bills.map{it.feeMinor})
            assertEquals(confirmed,review.bills.first())
            shot("$theme-$language-reconciled-review-top")
            tapTag("prediction-basis-"+review.bills.last().id,"prediction-review-period-3")
            ui.onNodeWithTag("prediction-review-period-3").performScrollTo()
            shot("$theme-$language-reconciled-basis")
            tapTag("prediction-confirm")
            ui.waitUntil(10000){vm.financialPredictionReview.value==null&&!vm.busy.value&&vm.financialState.value?.plans?.firstOrNull{it.id==plan}?.revision==review.plan.revision}
            ui.onNodeWithTag("prediction-stale-$plan").assertDoesNotExist()
            val snapshot=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertEquals(194900L,snapshot.balance(snapshot.accounts.first{it.id==bank}));assertEquals(95000L,snapshot.balance(snapshot.accounts.first{it.id==card}));assertEquals(100L,snapshot.monthExpense("2026-10"));assertEquals(1,snapshot.events.size)
            assertEquals(confirmed,runBlocking{db.financialDao().bill(book,first)})
            assertNull(vm.error.value)
        }
    }

    @Test fun actualBorrowingAndPendingPartialRepaymentInEveryThemeAndLanguage() {
        for((theme,language) in testLooks(this@FinancialFlowTest)) {
            fun text(zh:String,ja:String)=if(language=="ja")ja else zh
            val (book,bank,loan)=runBlocking {
                val b=repo.createBook("借還 $theme $language",Currency.JPY,theme,language,startDate="2026-10-01")
                repo.updateBook(repo.dao.book(b)!!.copy(initialized=true))
                val bank=repo.saveAccount(b,"実資金",AccountType.BANK,Currency.JPY,Money(Currency.JPY,0),"2026-10-01")
                val loan=repo.saveAccount(b,"借入先",AccountType.LOAN,Currency.JPY,Money(Currency.JPY,0),"2026-10-01")
                vm.preferences.select(b);Triple(b,bank,loan)
            }
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book&&!vm.busy.value}
            ui.runOnIdle{vm.navigate("accounts")}
            tapTag("account-$loan");tapTag("account-financial");tapTag("analysis-tab-movements");tapTag("finance-add-borrowing")
            textField(text("合同名称","契約名")).performScrollTo().performTextReplacement("実契約")
            textField(text("合同本金","契約元金")+" · JPY").performScrollTo().performTextReplacement("10000")
            tapTag("select-trigger-"+text("实际到账账户","実際の入金先口座"));ui.onNodeWithText("実資金 · JPY").performClick()
            textField(text("实际到账","実入金額")+" · JPY").performScrollTo().performTextReplacement("9700")
            textField(text("预扣费用 / 不明留空","天引手数料・不明なら空欄")+" · JPY").performScrollTo().performTextReplacement("300")
            tapTag("finance-borrow-preview")
            ui.waitUntil(10000){vm.financialLedgerReview.value!=null&&!vm.busy.value}
            ui.onNodeWithText("実契約").assertIsDisplayed()
            shot("$theme-$language-borrow-review")
            tapTag("finance-ledger-back")
            textField(text("合同本金","契約元金")+" · JPY").assertTextEquals("10000")
            tapTag("finance-borrow-preview");tapTag("finance-ledger-confirm")
            ui.waitUntil(10000){vm.financialState.value?.contracts?.any{it.accountId==loan}==true&&!vm.busy.value}
            val contract=vm.financialState.value!!.contracts.first{it.accountId==loan}
            tapTag("finance-repay-contract-${contract.id}")
            tapTag("select-trigger-"+text("实际付款账户","実際の支払口座"));ui.onNodeWithText("実資金 · JPY").performClick()
            val paidField=textField(text("实际扣款总额","実出金総額")+" · JPY")
            paidField.performScrollTo()
            if(InstrumentationRegistry.getArguments().getString("realIme")=="true")paidField.performClick()
            paidField.performTextReplacement("2100")
            if(InstrumentationRegistry.getArguments().getString("realIme")=="true") {
                ui.waitUntil(10000){android.view.inspector.WindowInspector.getGlobalWindowViews().any{androidx.core.view.ViewCompat.getRootWindowInsets(it)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true}}
                shot("$theme-$language-repayment-ime")
            }
            tapTag("finance-repay-preview");tapTag("finance-ledger-confirm")
            ui.waitUntil(10000){vm.financialState.value?.repayments?.any{it.debtAccountId==loan}==true&&!vm.busy.value}
            val payment=vm.financialState.value!!.repayments.first{it.debtAccountId==loan}
            var snapshot=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertEquals(7600L,snapshot.balance(snapshot.accounts.first{it.id==bank}));assertEquals(10000L,snapshot.balance(snapshot.accounts.first{it.id==loan}))
            assertEquals(10000L,snapshot.contractRemaining(contract));assertNull(snapshot.repaymentAllocations.single().principalMinor)
            tapTag("finance-edit-repayment-${payment.eventId}")
            tapTag("finance-repay-cleared")
            for((label,value) in listOf(text("平台实际收到","返済先の実受取額") to "2100",text("清偿本金","返済元金") to "2000",text("实付利息","実支払利息") to "100",text("平台费用","返済先手数料") to "0"))textField(label+" · JPY").performScrollTo().performTextReplacement(value)
            for((label,value) in listOf(text("分配本金","充当元金") to "2000",text("分配利息","充当利息") to "100",text("分配费用","充当手数料") to "0"))textField(label,"finance-repay-allocation-0").performScrollTo().performTextReplacement(value)
            tapTag("finance-repay-preview")
            ui.waitUntil(10000){vm.financialLedgerReview.value!=null&&!vm.busy.value}
            shot("$theme-$language-partial-review")
            tapTag("finance-ledger-confirm")
            ui.waitUntil(10000){vm.financialState.value?.repayments?.firstOrNull{it.eventId==payment.eventId}?.platformConfirmed==true&&!vm.busy.value}
            snapshot=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertEquals(7600L,snapshot.balance(snapshot.accounts.first{it.id==bank}));assertEquals(8000L,snapshot.balance(snapshot.accounts.first{it.id==loan}));assertEquals(8000L,snapshot.contractRemaining(contract));assertEquals(400L,snapshot.monthExpense("2026-10"));assertEquals(2,snapshot.events.size)
            assertNull(vm.error.value)
            ui.runOnIdle{vm.navigate("ledger")}
            ui.revealOnPage("event-${payment.eventId}");tapTag("event-${payment.eventId}")
            ui.waitUntil(10000){ui.onAllNodesWithTag("revision-history").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithTag("revision-history").performScrollTo()
            shot("$theme-$language-repayment-history")
            tapTag("record-edit-button")
            textField(text("实际扣款总额","実出金総額")+" · JPY").assertTextEquals("2100")
            textField(text("清偿本金","返済元金")+" · JPY").assertTextEquals("2000")
            tapTag("sheet-close")
            assertNull(vm.error.value)
        }
    }
    @Test fun rulesAndKnownPlansSaveCancelAndCorrectWithoutCreatingPaymentsInEveryThemeAndLanguage() {
        for((theme,language) in testLooks(this@FinancialFlowTest)) {
            fun text(zh:String,ja:String)=if(language=="ja")ja else zh
            val (book,bank,card)=runBlocking {
                val b=repo.createBook("金融 $theme $language",Currency.JPY,theme,language,startDate="2026-10-01")
                repo.updateBook(repo.dao.book(b)!!.copy(initialized=true))
                val bank=repo.saveAccount(b,"支払銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,20000),"2026-10-01")
                val card=repo.saveAccount(b,"手動カード",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
                vm.preferences.select(b);Triple(b,bank,card)
            }
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book&&!vm.busy.value}
            ui.runOnIdle{vm.navigate("accounts")}
            tapTag("account-$card");tapTag("account-financial");tapTag("analysis-tab-rules")
            tapTag("finance-edit-SINGLE")
            val closing=text("締め日（1—31）","締日（1〜31）")
            textField(closing).performScrollTo().performTextReplacement("30")
            tapTag("header-settings");tapTag("settings-language");tapTag("page-back")
            val otherLanguage=if(language=="zh")"ja"else "zh"
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.language==otherLanguage&&!vm.busy.value}
            tapTag("analysis-tab-rules");tapTag("finance-edit-SINGLE")
            textField(if(language=="zh")"締日（1〜31）"else "締め日（1—31）").assertTextEquals("30")
            tapTag("header-settings");tapTag("settings-language");tapTag("page-back")
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.language==language&&!vm.busy.value}
            tapTag("analysis-tab-rules");tapTag("finance-edit-SINGLE")
            tapTag("finance-rule-preview")
            ui.onNodeWithTag("finance-rule-incomplete").assertExists()
            ui.waitUntil(10000){vm.financialRuleReview.value!=null&&!vm.busy.value}
            shot("$theme-$language-incomplete-review")
            assertTrue(runBlocking{db.financialDao().rules(book,card).isEmpty()})
            tapTag("finance-review-back")
            textField(closing).assertTextEquals("30")
            tapTag("finance-rule-preview")
            runBlocking{repo.saveAccount(book,"支払銀行 更新",AccountType.BANK,Currency.JPY,Money(Currency.JPY,20000),"2026-10-01",bank)}
            tapTag("finance-rule-confirm")
            ui.waitUntil(10000){vm.financialReviewRefreshed.value&&!vm.busy.value}
            ui.onNodeWithTag("finance-review-refreshed").assertExists()
            assertTrue(runBlocking{db.financialDao().rules(book,card).isEmpty()})
            tapTag("finance-rule-confirm")
            ui.waitUntil(10000){vm.financialRuleReview.value==null&&vm.financialState.value?.rules?.count{it.accountId==card}==1}
            tapTag("analysis-tab-plans");tapTag("finance-add-plan")
            textField(text("计划名称","プラン名")).performScrollTo().performTextReplacement("手動の支払予定")
            textField(text("已知合计","既知の合計"),"finance-known-period-0").performScrollTo().performTextReplacement("3500")
            tapTag("finance-plan-add-period")
            textField(text("已知合计","既知の合計"),"finance-known-period-1").performScrollTo().performTextReplacement("3000")
            tapTag("finance-plan-preview")
            ui.waitUntil(10000){vm.manualFinancialPlanReview.value!=null&&!vm.busy.value}
            shot("$theme-$language-known-plan-review")
            assertTrue(runBlocking{db.financialDao().plans(book,card).isEmpty()})
            tapTag("finance-review-back")
            textField(text("已知合计","既知の合計"),"finance-known-period-1").assertTextEquals("3000")
            tapTag("finance-plan-preview");tapTag("finance-plan-confirm")
            ui.waitUntil(10000){vm.manualFinancialPlanReview.value==null&&vm.financialState.value?.plans?.count{it.accountId==card}==1}
            val plan=runBlocking{db.financialDao().plans(book,card).single()}
            val bill=runBlocking{db.financialDao().bills(book,plan.id).first()}
            assertNull(bill.principalMinor);assertNull(bill.feeMinor)
            tapTag("finance-confirm-bill-${bill.id}")
            textField(text("已知合计","既知の合計")).performScrollTo().performTextReplacement("3600")
            tapTag("finance-bill-preview")
            try {
                ui.waitUntil(10000){vm.error.value!=null||vm.financialBillReview.value!=null&&!vm.busy.value}
                assertNull(vm.error.value)
            }catch(e:Throwable){shot("$theme-$language-failed-bill-preview");throw e}
            shot("$theme-$language-bill-confirmation")
            assertEquals(3500L,runBlocking{db.financialDao().bill(book,bill.id)!!.totalMinor})
            tapTag("finance-review-back")
            textField(text("已知合计","既知の合計")).assertTextEquals("3600")
            tapTag("finance-bill-preview");tapTag("finance-bill-confirm")
            ui.waitUntil(10000){vm.financialBillReview.value==null&&!vm.busy.value}
            runBlocking {
                val saved=db.financialDao().bills(book,plan.id)
                assertEquals(listOf(3600L,3000L),saved.map{it.totalMinor})
                assertEquals(2,saved.first().revision)
                val snapshot=repo.snapshot(repo.dao.book(book)!!).first()
                assertTrue(snapshot.events.isEmpty());assertTrue(snapshot.postings.isEmpty())
                assertEquals(20000L,snapshot.balance(snapshot.accounts.first{it.id==bank}))
                assertEquals(10000L,snapshot.balance(snapshot.accounts.first{it.id==card}))
            }
        }
    }
    @Test fun completeInstallmentRuleCalculatesAndSavesAnExplicitBillDeadline() {
        val (book,card,bill)=runBlocking {
            val b=repo.createBook("分割条件",Currency.JPY,"journal","ja",startDate="2026-10-01")
            repo.updateBook(repo.dao.book(b)!!.copy(initialized=true))
            val bank=repo.saveAccount(b,"支払口座",AccountType.BANK,Currency.JPY,Money(Currency.JPY,20000),"2026-10-01")
            val card=repo.saveAccount(b,"カード",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
            val plan=repo.commitManualFinancialPlan(repo.previewManualFinancialPlan(b,ManualFinancialPlanInput(card,"既知請求",FinanceMode.SINGLE,listOf(KnownBillInput("2026-11-27",10000,10000,0,bank)))))!!
            val bill=db.financialDao().bills(b,plan).single()
            val rule=FinancialRuleVersion("",b,card,FinanceMode.INSTALLMENT,"2026-10-01","Asia/Tokyo",billing=BillingRule(31,true,1,27,bank),
                installment=InstallmentRule(listOf(3),InstallmentFeeMethod.PER_HUNDRED_TABLE,feeTable=listOf(InstallmentFee(3,"2.01")),principalRemainder=RemainderPlacement.LAST,feeRemainder=RemainderPlacement.FIRST,roundingMode="DOWN"))
            repo.commitFinancialRule(repo.previewFinancialRule(b,card,rule))
            vm.preferences.select(b);Triple(b,card,bill)
        }
        ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book&&!vm.busy.value}
        ui.runOnIdle{vm.navigate("accounts")}
        tapTag("account-$card");tapTag("account-financial");tapTag("analysis-tab-rules");tapTag("finance-edit-INSTALLMENT")
        textField("回数:100通貨単位当たり手数料、カンマ区切り").performScrollTo().performTextReplacement("3:3.00")
        textField("検算する元金 · JPY").performScrollTo().performTextReplacement("10000")
        textField("回数").performScrollTo().performTextReplacement("3")
        tapTag("finance-calculate")
        ui.waitUntil(10000){ui.onAllNodesWithTag("finance-trial-period-3").fetchSemanticsNodes().isNotEmpty()&&!vm.busy.value}
        ui.onNodeWithTag("finance-trial-period-1").assert(hasAnyDescendant(hasText("¥3,333")))
        ui.onNodeWithTag("finance-trial-period-3").assert(hasAnyDescendant(hasText("¥3,334")))
        ui.onNodeWithTag("finance-trial-period-1").assert(hasAnyDescendant(hasText("¥100")))
        ui.onNodeWithTag("finance-trial-period-1").performScrollTo();shot("journal-ja-installment-trial")
        tapTag("finance-advanced")
        tapTag("select-trigger-変更対象の請求")
        ui.onNodeWithText("既知請求 · 第1回 · 2026-11-27").performScrollTo().performClick()
        tapTag("finance-deadline-apply")
        tapTag("finance-rule-preview")
        ui.waitUntil(10000){vm.financialRuleReview.value!=null&&!vm.busy.value}
        ui.onNodeWithTag("finance-rule-complete").assertExists()
        tapTag("finance-rule-confirm")
        ui.waitUntil(10000){vm.financialRuleReview.value==null&&!vm.busy.value}
        runBlocking {
            val saved=db.financialDao().rules(book,card).maxBy{it.version}
            val rule=repo.json.decodeFromString<FinancialRuleVersion>(saved.json)
            assertEquals(2,saved.version)
            assertEquals("3.00",rule.installment!!.feeTable.single().feePerHundred)
            assertEquals(listOf(ChangeDeadline(bill.id,FinanceMode.INSTALLMENT,"2026-10-15","09:00","Asia/Tokyo")),rule.deadlines)
            assertEquals(1,db.financialDao().plans(book,card).size)
            assertEquals(bill,db.financialDao().bill(book,bill.id))
            assertTrue(repo.snapshot(repo.dao.book(book)!!).first().events.isEmpty())
        }
    }

    @Test fun predictionFromPurchaseCreatesBillsAndConfirmsRulesInEveryThemeAndLanguage() {
        for((theme,language) in testLooks(this@FinancialFlowTest)) {
            fun text(zh:String,ja:String)=if(language=="ja")ja else zh
            val ids=runBlocking {
                val b=repo.createBook("予測 $theme $language",Currency.JPY,theme,language,startDate="2026-10-01")
                repo.updateBook(repo.dao.book(b)!!.copy(initialized=true))
                val bank=repo.saveAccount(b,"支払口座",AccountType.BANK,Currency.JPY,Money(Currency.JPY,20000),"2026-10-01")
                val card=repo.saveAccount(b,"カード",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,0),"2026-10-01")
                val category=repo.saveCategory(b,"買物",EntryKind.EXPENSE,null)
                repo.saveEntry(b,EntryDraft(accountId=card,categoryId=category,amount="10000",date="2026-10-15",merchant="実店舗"))
                val rule=FinancialRuleVersion("",b,card,FinanceMode.INSTALLMENT,"2026-10-01","Asia/Tokyo",billing=BillingRule(31,true,1,27,bank),
                    installment=InstallmentRule(listOf(3),InstallmentFeeMethod.PER_HUNDRED_TABLE,feeTable=listOf(InstallmentFee(3,"2.01")),principalRemainder=RemainderPlacement.LAST,feeRemainder=RemainderPlacement.FIRST,roundingMode="DOWN"))
                val ruleId=repo.commitFinancialRule(repo.previewFinancialRule(b,card,rule))!!
                vm.preferences.select(b);listOf(b,bank,card,ruleId)
            }
            val (book,bank,card,ruleId)=ids
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book&&!vm.busy.value}
            ui.runOnIdle{vm.navigate("accounts")}
            tapTag("account-$card");tapTag("account-financial");tapTag("analysis-tab-plans");tapTag("finance-add-prediction")
            textField(text("计划名称","プラン名")).performScrollTo().performTextReplacement("実利用の分割")
            tapTag("select-trigger-"+text("计算模式","計算方式"))
            ui.onNodeWithText(text("分期","分割払い")).performScrollTo().performClick()
            tapTag("select-trigger-"+text("本金来源","元金の対象"))
            ui.onNodeWithText(text("已有消费 / 借入","既存の支出・借入")).performScrollTo().performClick()
            tapTag("select-trigger-"+text("关联记录","関連する記録"))
            ui.onNodeWithText("2026-10-15 · 実店舗 · ¥10,000").performScrollTo().performClick()
            textField(text("预测期数 / 不代表必定结清","予測回数・完済を保証するものではありません")).performScrollTo().performTextReplacement("3")
            val periodField=textField(text("起于原合同第几期","元の契約の何回目からか"))
            periodField.performScrollTo()
            if(InstrumentationRegistry.getArguments().getString("realIme")=="true")periodField.performClick()
            periodField.performTextReplacement("1")
            if(InstrumentationRegistry.getArguments().getString("realIme")=="true") {
                ui.waitUntil(10000){android.view.inspector.WindowInspector.getGlobalWindowViews().any{androidx.core.view.ViewCompat.getRootWindowInsets(it)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true}}
                shot("$theme-$language-prediction-ime")
            }
            tapTag("prediction-generate")
            ui.waitUntil(10000){ui.onAllNodesWithTag("prediction-period-2").fetchSemanticsNodes().isNotEmpty()&&!vm.busy.value}
            tapTag("prediction-current-arrangement")
            tapTag("prediction-preview")
            ui.waitUntil(10000){vm.financialPredictionReview.value!=null&&!vm.busy.value}
            ui.onNodeWithTag("prediction-review-period-1").assert(hasAnyDescendant(hasText("¥3,333")))
            shot("$theme-$language-real-prediction-review")
            assertTrue(runBlocking{db.financialDao().plans(book,card).isEmpty()})
            tapTag("prediction-review-back")
            textField(text("预测期数 / 不代表必定结清","予測回数・完済を保証するものではありません")).assertTextEquals("3")
            tapTag("prediction-preview");tapTag("prediction-confirm")
            ui.waitUntil(10000){vm.financialPredictionReview.value==null&&vm.financialState.value?.plans?.count{it.accountId==card}==1}
            val plan=runBlocking{db.financialDao().plans(book,card).single()}
            val bills=runBlocking{db.financialDao().bills(book,plan.id)}
            assertEquals(listOf("2026-11-27","2026-12-27","2027-01-27"),bills.map{it.date})
            assertEquals(listOf(3333L,3333L,3334L),bills.map{it.principalMinor})
            val first=bills.first()
            tapTag("finance-confirm-bill-${first.id}")
            textField(text("已知合计","既知の合計")).performScrollTo().performTextReplacement("3500")
            textField(text("其中利息与费用 / 不明留空","うち利息・手数料・不明なら空欄")).performScrollTo().performTextReplacement("167")
            tapTag("finance-bill-preview");tapTag("finance-bill-confirm")
            ui.waitUntil(10000){vm.financialBillReview.value==null&&!vm.busy.value}
            tapTag("finance-validate-bill-${first.id}")
            ui.waitUntil(10000){vm.financialState.value?.ruleValidationStatus(ruleId)=="DIFFERENT"&&!vm.busy.value}
            tapTag("analysis-tab-rules")
            ui.onNodeWithTag("finance-validation-$ruleId").assertTextEquals(text("与实际账单存在差异","実際の請求と差異あり"))
            runBlocking {
                val snapshot=repo.snapshot(repo.dao.book(book)!!).first()
                assertEquals(1,snapshot.events.size)
                assertEquals(10000L,snapshot.balance(snapshot.accounts.first{it.id==card}))
                assertEquals(20000L,snapshot.balance(snapshot.accounts.first{it.id==bank}))
                val actual=db.financialDao().bill(book,first.id)!!
                assertEquals(3500L,actual.totalMinor);assertEquals(3400L,actual.predictedTotalMinor)
            }
        }
    }

}
