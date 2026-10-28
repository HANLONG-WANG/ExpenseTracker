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
class FinancialRetroactiveFlowTest {
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
        val file=File(context.getExternalFilesDir(null),"phase-c-retro/$name.png");file.parentFile!!.mkdirs()
        file.outputStream().use{ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
        if(name.endsWith("-ime")||name.endsWith("-history")) {
            val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(file.parentFile,"$name-screen.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
        }
    }
    @Test fun retrospectiveClearingCanBeAssignedToANewPeriodWithoutAnotherCashPostingInEveryThemeAndLanguage() {
        for((theme,language) in testLooks(this@FinancialRetroactiveFlowTest)) {
            fun text(zh:String,ja:String)=if(language=="ja")ja else zh
            val split=InstrumentationRegistry.getArguments().getString("splitFees")=="true"
            val (book,card,old,payment)=runBlocking {
                val b=repo.createBook("追溯 $theme $language",Currency.JPY,theme,language,startDate="2026-10-01")
                repo.updateBook(repo.dao.book(b)!!.copy(initialized=true))
                val bank=repo.saveAccount(b,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
                val card=repo.saveAccount(b,"カード",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
                val old=repo.commitManualFinancialPlan(repo.previewManualFinancialPlan(b,ManualFinancialPlanInput(card,"原義務",FinanceMode.SINGLE,listOf(KnownBillInput("2026-11-05",10100,10000,100,bank)),principalMinor=10000,opening=true)))!!
                val bill=db.financialDao().bills(b,old).single()
                val payment=repo.commitFinancialLedger(repo.previewRepayment(b,FinancialRepaymentInput(bank,card,Money(Currency.JPY,2050),"2026-10-20",Money(Currency.JPY,2050),Money(Currency.JPY,2000),Money(Currency.JPY,0),Money(Currency.JPY,50),platformConfirmed=true,clearingDate="2026-10-20",allocations=listOf(RepaymentAllocationInput(bill.id,null,2000,0,50)))))!!
                repo.commitFinancialRule(repo.previewFinancialRule(b,card,FinancialRuleVersion("",b,card,FinanceMode.INSTALLMENT,"2026-10-01","Asia/Tokyo",BillingRule(31,true,1,5,bank),installment=InstallmentRule(listOf(2),InstallmentFeeMethod.TOTAL_AMOUNT,totalFeeMinor=100,principalRemainder=RemainderPlacement.LAST,feeRemainder=RemainderPlacement.FIRST,roundingMode="DOWN"))))
                vm.preferences.select(b);listOf(b,card,old,payment)
            }
            val originalBill=runBlocking{db.financialDao().bills(book,old).single()}
            val before=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            val originalRow=before.repaymentAllocations.single()
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book&&!vm.busy.value}
            ui.runOnIdle{vm.navigate("accounts")};tapTag("account-$card");tapTag("account-financial");tapTag("analysis-tab-plans");tapTag("finance-compare-plan-$old")
            tapTag("date-field-0");ui.onNodeWithContentDescription("2026-10-15").performScrollTo().performClick()
            textField(text("预测期数","予測回数")).performScrollTo().performTextReplacement("2")
            val feeLabel=text("另列变更费用 / 未知留空，已知无费用填0","別途変更手数料・不明は空欄、無料は0")+" · JPY"
            textField(feeLabel).performScrollTo().performTextReplacement("0")
            tapTag("select-trigger-"+text("原方案剩余费用如何处理","元の予定の残手数料の扱い"));ui.onNodeWithText(text("平台取消原费用","返済先が元の手数料を取消")).performClick()
            tapTag("arrangement-generate");tapTag("arrangement-preview")
            ui.waitUntil(10000){!vm.busy.value&&vm.financialArrangementReview.value!=null}
            assertEquals(Calculation.Incomplete(listOf("arrangement.laterClearing")),vm.financialArrangementReview.value!!.calculation)
            tapTag("arrangement-back");tapTag("arrangement-clearing-add-${originalRow.id}")
            tapTag("select-trigger-"+text("清偿归属","返済の充当先"));ui.onNodeWithText(text("新方案第1期","新プラン第1回")).performClick()
            if(InstrumentationRegistry.getArguments().getString("realIme")=="true") {
                tapTag("field-"+text("分配本金","充当元金"))
                ui.waitUntil(10000){android.view.inspector.WindowInspector.getGlobalWindowViews().any{androidx.core.view.ViewCompat.getRootWindowInsets(it)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true}}
                shot("$theme-$language-retro-ime")
            }
            if(split) {
                textField(text("分配费用","充当手数料"),"arrangement-clearing-fee-0").performScrollTo().performTextReplacement("0")
                tapTag("arrangement-clearing-add-${originalRow.id}")
                tapTag("select-trigger-"+text("清偿归属","返済の充当先"),"arrangement-clearing-destination-1");ui.onNodeWithText(text("保留原账单","元の請求に保持")).performClick()
                textField(text("分配费用","充当手数料"),"arrangement-clearing-fee-1").performScrollTo().performTextReplacement("50")
            }
            tapTag("arrangement-preview")
            ui.waitUntil(10000){!vm.busy.value&&vm.financialArrangementReview.value!=null}
            val review=vm.financialArrangementReview.value!!;assertTrue(review.calculation.toString(),review.calculation is Calculation.Ready)
            assertEquals(10000L,review.target!!.principalMinor);assertEquals(0L,review.comparison!!.proposedRemainingPrincipalMinor)
            ui.onAllNodes(hasText(if(split)"¥3,050"else"¥3,000") and hasAnyAncestor(hasTestTag("arrangement-row-"+text("新方案","新プラン")+"-0"))).assertCountEquals(if(split)1 else 2)
            ui.onNodeWithTag("arrangement-clearing-review-${originalRow.id}").performScrollTo();shot("$theme-$language-retro-clearing-review")
            tapTag("arrangement-record-applied");tapTag("arrangement-platform-confirmed");tapTag("arrangement-confirm")
            ui.waitUntil(10000){!vm.busy.value&&vm.financialState.value?.arrangements?.any{it.originalPlanId==old}==true}
            val after=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()};val f=runBlocking{db.financialDao().graph(book).first()!!}
            val target=f.arrangements.single{it.originalPlanId==old}.targetPlanId
            assertEquals(before.storedPostings,after.storedPostings);assertEquals(before.expenseComponents,after.expenseComponents);assertEquals(1,after.events.size);assertEquals(50L,after.monthExpense("2026-10"));assertEquals(originalBill,f.bills.first{it.id==originalBill.id})
            assertEquals(if(split)3050L else 3000L,after.billOutstanding(f.bills.first{it.planId==target&&it.number==1}).totalMinor)
            assertEquals(2,after.events.single{it.id==payment}.revision)
            assertNull(runBlocking{db.financialDao().draft(book,FinancialDraftContext("ARRANGEMENT",card,targetId=old).slot())})
            tapTag("finance-plan-history-$target");tapTag("finance-plan-version-open-1");shot("$theme-$language-retro-history");tapTag("sheet-close")
            assertNull(vm.error.value)
        }
    }
}
