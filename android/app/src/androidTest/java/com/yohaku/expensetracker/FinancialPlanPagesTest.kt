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
class FinancialPlanPagesTest {
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
        val file=File(context.getExternalFilesDir(null),"phase-c-plans/$name.png");file.parentFile!!.mkdirs()
        file.outputStream().use{ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
        if(name.endsWith("-ime")||name.endsWith("-history")) {
            val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(file.parentFile,"$name-screen.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
        }
    }
    @Test fun renamedPurchaseSourceIsCurrentAndConfirmedBillsAndTitlesStayIndependent() {
        for((theme,language) in testLooks(this@FinancialPlanPagesTest)) {
            val values=runBlocking {
                val book=repo.createBook("商户来源",Currency.JPY,theme,language,startDate="2026-10-01")
                repo.updateBook(repo.dao.book(book)!!.copy(initialized=true))
                val bank=repo.saveAccount(book,"支払銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,20000),"2026-10-01")
                val card=repo.saveAccount(book,"カード",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,0),"2026-10-01")
                val category=repo.saveCategory(book,"食事",EntryKind.EXPENSE,null)
                repo.commitFinancialRule(repo.previewFinancialRule(book,card,FinancialRuleVersion("",book,card,FinanceMode.INSTALLMENT,"2026-10-01","Asia/Tokyo",BillingRule(20,true,1,5,bank),installment=InstallmentRule(listOf(3),InstallmentFeeMethod.TOTAL_AMOUNT,totalFeeMinor=60,principalRemainder=RemainderPlacement.LAST,feeRemainder=RemainderPlacement.LAST,roundingMode="HALF_UP"))))
                val merchant=repo.saveMerchant(book,"朝の喫茶")
                val event=repo.saveEntry(book,EntryDraft(accountId=card,categoryId=category,amount="1200",date="2026-10-15",merchant="朝の喫茶",cardPaymentChoice="INSTALLMENT",cardPaymentPeriods="3"))
                var f=db.financialDao().graph(book).first()!!
                val plan=f.plans.single{it.sourceEventId==event}
                assertEquals("CARD_ENTRY",plan.origin)
                repo.commitFinancialBillConfirmation(repo.previewFinancialBillConfirmation(book,f.bills.minBy{it.number}.id,KnownBillInput("2026-11-05",420,400,20,bank)))
                repo.commitManualFinancialPlan(repo.previewManualFinancialPlan(book,ManualFinancialPlanInput(card,"独立の予定",FinanceMode.SINGLE,listOf(KnownBillInput("2026-12-05",100,100,0,bank)),100,opening=true)))
                f=db.financialDao().graph(book).first()!!
                val before=repo.snapshot(repo.dao.book(book)!!).first()
                val stale=repo.financialPredictionStale(before,f,plan)
                repo.saveMerchant(book,"夜の喫茶",id=merchant)
                val after=repo.snapshot(repo.dao.book(book)!!).first()
                assertEquals(f,db.financialDao().graph(book).first())
                assertEquals(stale,repo.financialPredictionStale(after,f,plan))
                vm.preferences.select(book)
                listOf(book,card,plan.id,f.bills.filter{it.planId==plan.id}.minBy{it.number}.id)
            }
            val (book,card,plan,bill)=values
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book&&!vm.busy.value}
            ui.runOnIdle{vm.navigate("accounts")};tapTag("account-$card");tapTag("account-financial");tapTag("finance-tab-plans")
            ui.onNodeWithText("夜の喫茶",substring=true).performScrollTo().assertExists()
            shot("$theme-$language-merchant-source-list")
            tapTag("finance-plan-row-$plan")
            ui.onNodeWithText("夜の喫茶",substring=true).assertExists()
            shot("$theme-$language-merchant-source-detail")
            tapTag("finance-plan-period-$bill")
            ui.onNodeWithText("夜の喫茶",substring=true).performScrollTo().assertExists()
            shot("$theme-$language-merchant-source-period")
            assertNull(vm.error.value)
        }
    }

    @Test fun summariesPeriodsAndContractCreationReturnWithoutDuplicatingPlans() {
        for((theme,language) in testLooks(this@FinancialPlanPagesTest)) {
            fun text(zh:String,ja:String)=if(language=="ja")ja else zh
            val ids=runBlocking {
                val b=repo.createBook("计划分层 $theme $language",Currency.JPY,theme,language,startDate="2026-10-01")
                repo.updateBook(repo.dao.book(b)!!.copy(initialized=true))
                val bank=repo.saveAccount(b,"支払銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,200000),"2026-10-01")
                val card=repo.saveAccount(b,"カード",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,200000),"2026-10-01")
                val loan=repo.saveAccount(b,"ローン",AccountType.LOAN,Currency.JPY,Money(Currency.JPY,1000),"2026-10-01")
                val six=repo.commitManualFinancialPlan(repo.previewManualFinancialPlan(b,ManualFinancialPlanInput(card,"6期の分割",FinanceMode.INSTALLMENT,(0..5).map{KnownBillInput(LocalDate.of(2026,11,5).plusMonths(it.toLong()).toString(),10100,10000,100,bank)},60000,complete=true,opening=true)))!!
                repo.commitFinancialRule(repo.previewFinancialRule(b,card,FinancialRuleVersion("",b,card,FinanceMode.REVOLVING,"2026-10-01","Asia/Tokyo",BillingRule(31,true,1,5,bank),revolving=RevolvingRule(InterestRule("0.18",ChargeFrequency.DAILY,BalanceBasis.EVENT_BALANCE,true,true,365,ChargeRounding.PERIOD_TOTAL,"HALF_UP",FirstCharge.NORMAL),PaymentMethod.FIXED_PRINCIPAL,10000,allocation=AllocationOrder.FEES_FIRST,automatic=true))))
                val dates=(0..13).map{LocalDate.of(2026,11,5).plusMonths(it.toLong()).toString()}
                val windows=(0..13).map{val start=LocalDate.of(2026,10,1).plusMonths(it.toLong());AccrualWindow(start.toString(),start.withDayOfMonth(start.lengthOfMonth()).toString())}
                val pool=repo.commitFinancialPrediction(repo.previewFinancialPrediction(b,FinancialPredictionInput(card,"14期のリボ",FinanceMode.REVOLVING,FinancialScheduleTerms("2026-10-01",dates,windows),paymentAccountId=bank,currentArrangementConfirmed=true,pool=FinancialPoolInput(140000,PoolSourceDate.TRANSACTION))))!!
                repo.commitFinancialLedger(repo.previewBorrowing(b,FinancialBorrowingInput(loan,"期首固定契約",FinanceMode.FIXED_LOAN,Money(Currency.JPY,1000),"2026-10-01",opening=true)))
                val contract=db.financialDao().graph(b).first()!!.contracts.single().id
                vm.preferences.select(b);listOf(b,bank,card,loan,six,pool,contract)
            }
            val book=ids[0];val bank=ids[1];val card=ids[2];val loan=ids[3];val six=ids[4];val pool=ids[5];val contract=ids[6]
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book&&!vm.busy.value}
            ui.runOnIdle{vm.navigate("accounts")};tapTag("account-$card");tapTag("account-financial");tapTag("finance-tab-plans")
            ui.onAllNodes(hasTestTag("finance-plan-row-$six") or hasTestTag("finance-plan-row-$pool")).assertCountEquals(2)
            vm.financialState.value!!.bills.filter{it.planId in listOf(six,pool)}.forEach{ui.onNodeWithTag("finance-bill-${it.id}").assertDoesNotExist();ui.onNodeWithTag("finance-plan-period-${it.id}").assertDoesNotExist()}
            assertEquals(6,vm.financialState.value!!.bills.count{it.planId==six&&it.active});assertEquals(14,vm.financialState.value!!.bills.count{it.planId==pool&&it.active})
            shot("$theme-$language-card-plans-list")
            tapTag("finance-add-arrangement");shot("$theme-$language-add-arrangement");tapTag("sheet-close")
            ui.onNodeWithTag("finance-plan-row-$pool").performScrollTo();ui.waitForIdle()
            val before=ui.onNodeWithTag("finance-plan-row-$pool").getUnclippedBoundsInRoot().top
            tapTag("finance-plan-row-$pool")
            ui.onNodeWithTag("finance-plan-detail-$pool").assertExists();shot("$theme-$language-plan-detail-periods")
            val first=vm.financialState.value!!.bills.filter{it.planId==pool&&it.active}.minBy{it.number}
            tapTag("finance-plan-period-${first.id}");shot("$theme-$language-plan-period-detail");tapTag("prediction-basis-${first.id}")
            val basis=ui.onNodeWithTag("prediction-basis-detail-${first.id}")
            basis.performScrollTo();ui.waitForIdle()
            val viewport=ui.onNodeWithTag("page-scroll")
            val density=InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
            val delta=(basis.getUnclippedBoundsInRoot().top-viewport.getUnclippedBoundsInRoot().top).value*density
            viewport.performSemanticsAction(SemanticsActions.ScrollBy){it(0f,delta)}
            shot("$theme-$language-plan-period-basis")
            tapTag("finance-back");ui.onNodeWithTag("finance-plan-detail-$pool").assertExists()
            tapTag("finance-back");assertEquals(before,ui.onNodeWithTag("finance-plan-row-$pool").getUnclippedBoundsInRoot().top)
            // A statement period returns to its month, while the plan's own back path returns to the list.
            tapTag("finance-tab-statement");tapTag("finance-statement-next");tapTag("statement-bill-${first.id}");tapTag("finance-back")
            ui.onNodeWithTag("finance-tab-statement").assertIsSelected();ui.onNodeWithTag("statement-bill-${first.id}").assertExists()
            ui.runOnIdle{vm.navigate("accounts")};tapTag("account-$loan");tapTag("account-financial");tapTag("finance-tab-plans")
            ui.onNodeWithTag("finance-add-plan").assertDoesNotExist();ui.onNodeWithTag("finance-pool-open").assertDoesNotExist();shot("$theme-$language-plans-empty")
            // Both a different mode and a different source must be absent from candidates.
            runBlocking {
                repo.commitManualFinancialPlan(repo.previewManualFinancialPlan(book,ManualFinancialPlanInput(loan,"別方式",FinanceMode.CREDIT_LINE,listOf(KnownBillInput("2026-11-05",100,100,0,bank)),100)))
                val event=repo.commitFinancialLedger(repo.previewBorrowing(book,FinancialBorrowingInput(loan,"別の借入",FinanceMode.FIXED_LOAN,Money(Currency.JPY,200),"2026-10-15",bank,Money(Currency.JPY,200))))!!
                repo.commitManualFinancialPlan(repo.previewManualFinancialPlan(book,ManualFinancialPlanInput(loan,"別対象",FinanceMode.FIXED_LOAN,listOf(KnownBillInput("2026-11-05",200,200,0,bank)),200,sourceEventId=event)))
            }
            ui.waitUntil(10000){vm.financialState.value?.plans?.count{it.accountId==loan}==2}
            tapTag("finance-tab-manage");tapTag("finance-manage-contracts");tapTag("finance-link-contract-$contract")
            ui.onNodeWithTag("finance-contract-link-empty").assertExists();shot("$theme-$language-contract-link-empty")
            tapTag("finance-contract-create-plan");tapTag("finance-add-plan")
            textField(text("计划名称","プラン名")).assertTextEquals("期首固定契約")
            textField(text("已知合计","既知の合計"),"finance-known-period-0").performScrollTo().performTextReplacement("1000")
            textField(text("其中本金 / 不明留空","うち元金・不明なら空欄"),"finance-known-period-0").performScrollTo().performTextReplacement("1000")
            textField(text("其中利息与费用 / 不明留空","うち利息・手数料・不明なら空欄"),"finance-known-period-0").performScrollTo().performTextReplacement("0")
            tapTag("finance-flow-close");ui.onNodeWithTag("finance-contract-link-empty").assertExists();tapTag("sheet-close")
            tapTag("finance-back");tapTag("finance-manage-drafts");tapTag("financial-draft-resume-MANUAL")
            textField(text("已知合计","既知の合計"),"finance-known-period-0").assertTextEquals("1000")
            tapTag("finance-plan-preview");tapTag("finance-plan-confirm")
            ui.waitUntil(10000){vm.financialState.value?.plans?.count{it.accountId==loan}==3&&!vm.busy.value}
            val newPlan=vm.financialState.value!!.plans.single{it.accountId==loan&&it.name=="期首固定契約"}
            ui.waitUntil(10000){vm.financialState.value?.bills?.count{it.planId==newPlan.id&&it.active}==1}
            assertEquals("OPENING",newPlan.origin);assertEquals("FIXED_LOAN",newPlan.mode);assertNull(newPlan.sourceEventId)
            assertTrue(vm.financialState.value!!.contractPlans.none{it.contractId==contract})
            ui.onNodeWithTag("finance-task-title").assertTextEquals(text("借入与合同","借入・契約"))
            ui.onNodeWithTag("finance-contract-candidate-${newPlan.id}").assertExists();shot("$theme-$language-contract-link-created")
            tapTag("sheet-close")
            tapTag("finance-tab-manage");tapTag("finance-manage-contracts");tapTag("finance-link-contract-$contract")
            tapTag("finance-contract-candidate-${newPlan.id}")
            ui.waitUntil(10000){vm.financialState.value?.contractPlans?.any{it.contractId==contract&&it.planId==newPlan.id}==true&&!vm.busy.value}
            assertEquals(3,vm.financialState.value!!.plans.count{it.accountId==loan})
            tapTag("finance-tab-plans");shot("$theme-$language-loan-plans-list")
            assertNull(vm.error.value)
        }
    }
}
