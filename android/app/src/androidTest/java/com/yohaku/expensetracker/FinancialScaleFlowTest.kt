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
class FinancialScaleFlowTest {
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
        val file=File(context.getExternalFilesDir(null),"phase-c-scale/$name.png");file.parentFile!!.mkdirs()
        file.outputStream().use{ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
        if(name.endsWith("-ime")||name.endsWith("-history")) {
            val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(file.parentFile,"$name-screen.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
        }
    }
    @Test fun fortySinglePurchasesStayInStatementsAndKeepTheirActions() {
        for((theme,language) in testLooks(this@FinancialScaleFlowTest)) {
            fun text(zh:String,ja:String)=if(language=="ja")ja else zh
            val ids=runBlocking {
                val b=repo.createBook("规模账单 $theme $language",Currency.JPY,theme,language,startDate="2026-10-01")
                repo.updateBook(repo.dao.book(b)!!.copy(initialized=true))
                val bank=repo.saveAccount(b,"支払銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,200000),"2026-10-01")
                val card=repo.saveAccount(b,"PayPay",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,81000),"2026-10-01")
                val category=repo.saveCategory(b,"買物",EntryKind.EXPENSE,null)
                repo.commitFinancialRule(repo.previewFinancialRule(b,card,FinancialRuleVersion("",b,card,FinanceMode.SINGLE,"2026-10-01","Asia/Tokyo",BillingRule(31,true,1,5,bank))))
                repeat(40){i->repo.saveEntry(b,EntryDraft(accountId=card,categoryId=category,amount="1000",date="2026-10-10",merchant="Shop %02d".format(i+1),cardPaymentChoice="SINGLE"))}
                val six=repo.commitManualFinancialPlan(repo.previewManualFinancialPlan(b,ManualFinancialPlanInput(card,"6期の分割",FinanceMode.INSTALLMENT,(0..5).map{KnownBillInput(LocalDate.of(2026,11,5).plusMonths(it.toLong()).toString(),10000,10000,0,bank)},60000,complete=true,opening=true)))!!
                repo.commitFinancialRule(repo.previewFinancialRule(b,card,FinancialRuleVersion("",b,card,FinanceMode.REVOLVING,"2026-10-01","Asia/Tokyo",BillingRule(31,true,1,5,bank),revolving=RevolvingRule(InterestRule("0.18",ChargeFrequency.DAILY,BalanceBasis.EVENT_BALANCE,true,true,365,ChargeRounding.PERIOD_TOTAL,"HALF_UP",FirstCharge.NORMAL),PaymentMethod.FIXED_PRINCIPAL,10000,allocation=AllocationOrder.FEES_FIRST,automatic=true))))
                // OPENING singles follow the same display rule as card-entry singles.
                val opening=repo.commitManualFinancialPlan(repo.previewManualFinancialPlan(b,ManualFinancialPlanInput(card,"期初一次付",FinanceMode.SINGLE,listOf(KnownBillInput("2026-12-05",1000,1000,0,bank)),1000,opening=true)))!!
                val pool=repo.commitFinancialPrediction(repo.previewFinancialPrediction(b,FinancialPredictionInput(card,"共同リボ",FinanceMode.REVOLVING,FinancialScheduleTerms("2026-10-01",listOf("2026-11-05","2026-12-05"),listOf(AccrualWindow("2026-10-01","2026-10-31"),AccrualWindow("2026-11-01","2026-11-30"))),paymentAccountId=bank,currentArrangementConfirmed=true,pool=FinancialPoolInput(20000,PoolSourceDate.TRANSACTION))))!!
                vm.preferences.select(b);listOf(b,card,six,pool,opening)
            }
            val(book,card,six,pool,opening)=ids
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book&&vm.financialState.value?.plans?.size==43&&!vm.busy.value}
            val graph=vm.financialState.value!!
            val singles=graph.plans.filter{it.mode=="SINGLE"}
            val bill=graph.bills.first{b->singles.any{it.id==b.planId&&it.name.contains("Shop 01")}}
            val plan=graph.plans.first{it.id==bill.planId}
            ui.runOnIdle{vm.navigate("accounts")};tapTag("account-$card");tapTag("account-financial")
            ui.onNodeWithTag("finance-task-title").assertDoesNotExist()
            ui.onAllNodesWithTag("finance-overview-compare").assertCountEquals(1)
            ui.onNodeWithTag("finance-overview-compare").performScrollTo();shot("$theme-$language-overview-payment")
            tapTag("finance-overview-compare")
            ui.onAllNodes(SemanticsMatcher("compare candidate"){it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag){""}.startsWith("finance-compare-candidate-")}).assertCountEquals(42)
            shot("$theme-$language-compare-picker")
            tapTag("finance-compare-candidate-${plan.id}");ui.onNodeWithTag("finance-flow").assertExists();tapTag("finance-flow-close")
            tapTag("finance-tab-plans");ui.onNodeWithTag("finance-task-title").assertDoesNotExist()
            ui.onAllNodes(SemanticsMatcher("plan row"){it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag){""}.startsWith("finance-plan-row-")}).assertCountEquals(2)
            ui.onNodeWithTag("finance-plan-row-$six").assertExists();ui.onNodeWithTag("finance-plan-row-$pool").assertExists()
            singles.forEach{ui.onNodeWithTag("finance-plan-row-${it.id}").assertDoesNotExist()}
            shot("$theme-$language-plans")
            tapTag("finance-tab-overview");tapTag("finance-next-statement")
            ui.onNodeWithTag("finance-task-title").assertDoesNotExist()
            ui.onAllNodes(SemanticsMatcher("statement row"){it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag){""}.startsWith("statement-bill-")}).assertCountEquals(42)
            ui.onNodeWithText(text("相关消费","対象の利用明細")).assertDoesNotExist()
            ui.onNodeWithTag("statement-date-2026-11-05").performScrollTo();shot("$theme-$language-statement-summary")
            tapTag("statement-repay-date-2026-11-05")
            ui.onAllNodes(SemanticsMatcher("allocation"){it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag){""}.matches(Regex("finance-repay-allocation-[0-9]+"))}).assertCountEquals(42)
            ui.onNodeWithTag("finance-repay-cleared").assertIsOff()
            tapTag("finance-flow-close")
            tapTag("finance-statement-next");tapTag("statement-repay-date-2026-12-05")
            ui.onAllNodes(SemanticsMatcher("allocation"){it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag){""}.matches(Regex("finance-repay-allocation-[0-9]+"))}).assertCountEquals(3)
            tapTag("financial-draft-discard")
            tapTag("finance-statement-previous");tapTag("statement-repay-date-2026-11-05")
            ui.onAllNodes(SemanticsMatcher("allocation"){it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag){""}.matches(Regex("finance-repay-allocation-[0-9]+"))}).assertCountEquals(42)
            tapTag("financial-draft-discard")
            val firstRow=ui.onNodeWithTag("statement-bill-${graph.bills.first{it.planId==six&&it.number==1}.id}")
            firstRow.performScrollTo();ui.waitForIdle()
            val viewport=ui.onNodeWithTag("page-scroll")
            val density=InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
            val offset=(firstRow.getUnclippedBoundsInRoot().top-viewport.getUnclippedBoundsInRoot().top).value*density
            viewport.performSemanticsAction(SemanticsActions.ScrollBy){it(0f,offset)}
            shot("$theme-$language-statement-rows")
            tapTag("statement-bill-${bill.id}")
            ui.onNodeWithTag("finance-tab-statement").assertIsSelected()
            ui.onNodeWithTag("finance-bill-${bill.id}").assertExists();shot("$theme-$language-period-facts")
            tapTag("finance-confirm-bill-${bill.id}");ui.onNodeWithTag("finance-bill-preview").assertExists();tapTag("financial-draft-discard")
            tapTag("finance-pay-bill-${bill.id}");ui.onNodeWithTag("finance-repay-preview").assertExists();tapTag("financial-draft-discard")
            tapTag("finance-period-source-${plan.sourceEventId}");tapTag("sheet-close")
            tapTag("finance-compare-plan-${plan.id}");ui.onNodeWithTag("finance-flow").assertExists();tapTag("finance-flow-close")
            tapTag("finance-plan-history-${plan.id}");ui.onNodeWithTag("finance-plan-version-open-1").assertExists();tapTag("sheet-close")
            ui.onNodeWithTag("finance-confirm-bill-${bill.id}").performScrollTo();shot("$theme-$language-period-actions")
            tapTag("finance-period-plan-${plan.id}")
            ui.onNodeWithTag("finance-plan-detail-${plan.id}").assertExists()
            tapTag("finance-plan-period-${bill.id}");tapTag("finance-period-plan-${plan.id}")
            ui.onNodeWithTag("finance-plan-detail-${plan.id}").assertExists()
            tapTag("finance-back");ui.onNodeWithTag("statement-bill-${bill.id}").assertExists()
            tapTag("finance-tab-manage");ui.onNodeWithTag("finance-task-title").assertDoesNotExist();shot("$theme-$language-manage")
            tapTag("finance-manage-history");ui.onNodeWithTag("finance-task-title").assertTextEquals(text("办理与版本历史","手続き・バージョン履歴"))
            ui.onNodeWithTag("finance-plan-history-$six").assertExists();ui.onNodeWithTag("finance-plan-history-$pool").assertExists()
            singles.forEach{ui.onNodeWithTag("finance-plan-history-${it.id}").assertDoesNotExist()}
            shot("$theme-$language-history")
            assertNull(vm.error.value)
        }
    }
}
