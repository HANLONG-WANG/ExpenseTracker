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
class FinancialPoolArrangementFlowTest {
    @get:Rule val ui=createComposeRule()
    private lateinit var db:YohakuDatabase
    private lateinit var repo:LedgerRepository
    private lateinit var vm:AppViewModel
    @Before fun open() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        repo=LedgerRepository(db,Clock.fixed(Instant.parse("2026-10-25T00:00:00Z"),ZoneId.of("Asia/Tokyo")))
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
        val file=File(context.getExternalFilesDir(null),"phase-c-pool-arrangement/$name.png");file.parentFile!!.mkdirs()
        file.outputStream().use{ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
        if(name.endsWith("-ime")||name.endsWith("-history")) {
            val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(file.parentFile,"$name-screen.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
        }
    }
    @Test fun selectedPoolPrincipalAndExplicitFeeCancellationCanBeAppliedInEveryThemeAndLanguage() {
        for((theme,language) in testLooks(this@FinancialPoolArrangementFlowTest)) {
            fun text(zh:String,ja:String)=if(language=="ja")ja else zh
            val loan=InstrumentationRegistry.getArguments().getString("loan")=="true"
            val ids=runBlocking {
                val b=repo.createBook("池变更 $theme $language",Currency.JPY,theme,language,startDate="2026-10-01")
                repo.updateBook(repo.dao.book(b)!!.copy(initialized=true))
                val bank=repo.saveAccount(b,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
                val card=repo.saveAccount(b,"カード",if(loan)AccountType.LOAN else AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,3000),"2026-10-01")
                repo.commitFinancialRule(repo.previewFinancialRule(b,card,FinancialRuleVersion("",b,card,if(loan)FinanceMode.CREDIT_LINE else FinanceMode.REVOLVING,"2026-10-01","Asia/Tokyo",BillingRule(31,true,1,5,bank),revolving=RevolvingRule(InterestRule("0",ChargeFrequency.DAILY,BalanceBasis.EVENT_BALANCE,true,true,365,ChargeRounding.PERIOD_TOTAL,"DOWN",FirstCharge.NORMAL),PaymentMethod.FIXED_PRINCIPAL,1000,allocation=AllocationOrder.FEES_FIRST,automatic=true),limitRecovery=if(loan)LimitRecovery.PAYMENT_DATE else null)))
                if(loan)repo.commitFinancialRule(repo.previewFinancialRule(b,card,FinancialRuleVersion("",b,card,FinanceMode.FIXED_LOAN,"2026-10-01","Asia/Tokyo",loanMethod=LoanMethod.EQUAL_PRINCIPAL,loanInterest=InterestRule("0",ChargeFrequency.MONTHLY,BalanceBasis.OPENING_BALANCE,true,true,365,ChargeRounding.PERIOD_TOTAL,"DOWN",FirstCharge.NORMAL),allocation=AllocationOrder.FEES_FIRST,loanPrincipalRemainder=RemainderPlacement.LAST)))
                else repo.commitFinancialRule(repo.previewFinancialRule(b,card,FinancialRuleVersion("",b,card,FinanceMode.INSTALLMENT,"2026-10-01","Asia/Tokyo",BillingRule(31,true,1,5,bank),installment=InstallmentRule(listOf(2),InstallmentFeeMethod.TOTAL_AMOUNT,totalFeeMinor=100,principalRemainder=RemainderPlacement.LAST,feeRemainder=RemainderPlacement.FIRST,roundingMode="DOWN"))))
                val input=FinancialPredictionInput(card,"账户池",if(loan)FinanceMode.CREDIT_LINE else FinanceMode.REVOLVING,FinancialScheduleTerms("2026-10-01",listOf("2026-11-05","2026-12-05","2027-01-05"),listOf(AccrualWindow("2026-10-01","2026-10-31"),AccrualWindow("2026-11-01","2026-11-30"),AccrualWindow("2026-12-01","2026-12-31"))),paymentAccountId=bank,currentArrangementConfirmed=true,pool=FinancialPoolInput(3000,PoolSourceDate.TRANSACTION))
                val pool=repo.commitFinancialPrediction(repo.previewFinancialPool(b,input))!!
                val bill=db.financialDao().bills(b,pool).first()
                repo.commitFinancialBillConfirmation(repo.previewFinancialBillConfirmation(b,bill.id,KnownBillInput(bill.date,1000,1000,0,bank)))
                repo.commitFinancialPrediction(repo.previewFinancialPool(b,repo.json.decodeFromString<FinancialPredictionInput>(db.financialDao().plans(b,card).single().termsJson!!).copy(planId=pool)))
                vm.preferences.select(b);listOf(b,bank,card,pool,bill.id)
            }
            val book=ids[0];val bank=ids[1];val card=ids[2];val pool=ids[3];val billId=ids[4]
            val before=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            val original=runBlocking{db.financialDao().bill(book,billId)!!}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book&&!vm.busy.value}
            ui.runOnIdle{vm.navigate("accounts")};tapTag("account-$card");tapTag("account-financial");tapTag("analysis-tab-plans");tapTag("finance-compare-plan-$pool")
            textField(text("预测期数","予測回数")).performScrollTo().performTextReplacement("2")
            tapTag("select-trigger-"+text("原方案剩余费用如何处理","元の予定の残手数料の扱い"));ui.onNodeWithText(text("平台取消原费用","返済先が元の手数料を取消")).performClick()
            tapTag("pool-transfer-select-$billId")
            textField(text("转出本金","移す元金")+" · JPY").performScrollTo().performTextReplacement("500")
            if(InstrumentationRegistry.getArguments().getString("realIme")=="true") {
                tapTag("field-"+text("转出本金","移す元金")+" · JPY")
                ui.waitUntil(10000){android.view.inspector.WindowInspector.getGlobalWindowViews().any{androidx.core.view.ViewCompat.getRootWindowInsets(it)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true}}
                shot("$theme-$language-pool-ime")
            }
            textField(text("另列变更费用 / 未知留空，已知无费用填0","別途変更手数料・不明は空欄、無料は0")+" · JPY").performScrollTo().performTextReplacement("0")
            tapTag("arrangement-generate");tapTag("arrangement-preview")
            ui.waitUntil(10000){!vm.busy.value&&vm.financialArrangementReview.value!=null}
            assertEquals(Calculation.Incomplete(listOf("arrangement.poolFees")),vm.financialArrangementReview.value!!.calculation)
            tapTag("arrangement-back")
            textField(text("平台取消的本期费用 / 已知无费用填0","返済先が取り消す当期手数料・無料は0")+" · JPY").performScrollTo().performTextReplacement("0")
            tapTag("arrangement-preview")
            ui.waitUntil(10000){!vm.busy.value&&vm.financialArrangementReview.value?.calculation is Calculation.Ready}
            val review=vm.financialArrangementReview.value!!
            assertEquals(500L,review.target!!.principalMinor);assertEquals(if(loan)250L else 200L,review.comparison!!.currentReductionMinor)
            ui.onNodeWithTag("pool-transfer-review").performScrollTo();shot("$theme-$language-pool-review")
            tapTag("arrangement-record-applied");tapTag("arrangement-platform-confirmed");tapTag("arrangement-confirm")
            ui.waitUntil(10000){!vm.busy.value&&vm.financialState.value?.arrangements?.any{it.originalPlanId==pool}==true}
            val snapshot=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()};val f=runBlocking{db.financialDao().graph(book).first()!!}
            assertEquals(before.storedPostings,snapshot.storedPostings);assertEquals(before.expenseComponents,snapshot.expenseComponents);assertTrue(snapshot.events.isEmpty())
            assertEquals(3000L,snapshot.balance(snapshot.accounts.first{it.id==card}));assertEquals(10000L,snapshot.balance(snapshot.accounts.first{it.id==bank}))
            assertEquals(original,f.bills.first{it.id==billId});assertEquals(500L,snapshot.billOutstanding(original).principalMinor)
            assertEquals(1,f.plans.count{it.active&&it.origin=="POOL"})
            assertNull(runBlocking{db.financialDao().draft(book,FinancialDraftContext("ARRANGEMENT",card,targetId=pool).slot())})
            tapTag("finance-compare-plan-$pool");tapTag("sheet-close")
            if(loan) {
                val target=f.arrangements.single().targetPlanId
                tapTag("analysis-tab-overview");tapTag("analysis-tab-FIXED_LOAN")
                ui.onNodeWithTag("loan-mode-plan-$target").performScrollTo();shot("$theme-$language-pool-fixed-overview")
            }
            assertNull(vm.error.value)
        }
    }
}
