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
class FinancialReconciliationChainFlowTest {
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
        val file=File(context.getExternalFilesDir(null),"phase-c-chain-manual/$name.png");file.parentFile!!.mkdirs()
        file.outputStream().use{ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
        if(name.endsWith("-ime")||name.endsWith("-history")) {
            val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(file.parentFile,"$name-screen.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
        }
    }
    @Test fun upstreamReviewReturnsToTheUnknownManualDraftWithoutMixingInputs() {
        for((theme,language) in testLooks(this@FinancialReconciliationChainFlowTest)) {
            fun text(zh:String,ja:String)=if(language=="ja")ja else zh
            val resolve=InstrumentationRegistry.getArguments().getString("resolve")=="true"
            val cancelParentFee=InstrumentationRegistry.getArguments().getString("cancelParentFee")=="true"
            val ids=runBlocking {
                val b=repo.createBook("連続重核 $theme $language",Currency.JPY,theme,language,startDate="2026-10-01");repo.updateBook(repo.dao.book(b)!!.copy(initialized=true))
                val bank=repo.saveAccount(b,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
                val card=repo.saveAccount(b,"カード",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,0),"2026-10-01")
                val cat=repo.saveCategory(b,"利用",EntryKind.EXPENSE,null)
                val source=repo.saveEntry(b,EntryDraft(accountId=card,categoryId=cat,amount="1000",date="2026-10-01",cardPaymentChoice="SINGLE"))
                val root=repo.commitManualFinancialPlan(repo.previewManualFinancialPlan(b,ManualFinancialPlanInput(card,"元の一括",FinanceMode.SINGLE,listOf(KnownBillInput("2026-11-05",1000,1000,0,bank)),1000,complete=true,sourceEventId=source)))!!
                repo.commitFinancialRule(repo.previewFinancialRule(b,card,FinancialRuleVersion("",b,card,FinanceMode.INSTALLMENT,"2026-10-01","Asia/Tokyo",BillingRule(31,true,1,5,bank),installment=InstallmentRule(listOf(2),InstallmentFeeMethod.TOTAL_AMOUNT,totalFeeMinor=40,principalRemainder=RemainderPlacement.LAST,feeRemainder=RemainderPlacement.FIRST,roundingMode="DOWN"))))
                val dates=listOf("2026-11-05","2026-12-05")
                val parent=repo.commitFinancialArrangement(repo.previewFinancialArrangement(b,FinancialArrangementInput(root,"二回分割",FinanceMode.INSTALLMENT,FinancialScheduleTerms("2026-10-25",dates),bank,OriginalFeeTreatment.RETAIN,0)),repo.clock.instant().toString(),true)!!
                val child=repo.commitFinancialArrangement(repo.previewFinancialArrangement(b,FinancialArrangementInput(parent,"手動の次の予定",FinanceMode.REVOLVING,FinancialScheduleTerms("2026-10-27",dates),bank,OriginalFeeTreatment.RETAIN,0,manualPeriods=listOf(KnownBillInput(dates[0],600),KnownBillInput(dates[1],500)))),repo.clock.instant().toString(),true)!!
                repo.saveEntry(b,EntryDraft(id=source,accountId=card,categoryId=cat,amount="1200",date="2026-10-02",cardPaymentChoice="SINGLE"))
                vm.preferences.select(b);listOf(b,bank,card,root,parent,child)
            }
            val book=ids[0];val bank=ids[1];val card=ids[2];val root=ids[3];val parent=ids[4];val child=ids[5]
            val before=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()};val graph=runBlocking{db.financialDao().graph(book).first()!!}
            val first=graph.bills.first{it.planId==child&&it.number==1};val rootBill=graph.bills.first{it.planId==root}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book&&!vm.busy.value}
            ui.runOnIdle{vm.navigate("accounts")};tapTag("account-$card");tapTag("account-financial");tapTag("analysis-tab-plans");tapTag("finance-reconcile-$child")
            fun replace(tag:String,value:String){ui.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(tag))).performScrollTo().performTextReplacement(value)}
            fun principal()=textField(text("生效日转入的本金 / 非今日剩余","適用日に移す元金・今日の残額ではありません")+" · JPY","reconciliation-principal")
            replace("reconciliation-principal","1200")
            if(InstrumentationRegistry.getArguments().getString("resume")=="true") {
                tapTag("sheet-close");tapTag("analysis-tab-overview");tapTag("financial-drafts-open");tapTag("financial-draft-resume-ARRANGEMENT_REVIEW")
                principal().assertTextContains("1200")
            }
            if(InstrumentationRegistry.getArguments().getString("realIme")=="true") {
                tapTag("field-"+text("生效日转入的本金 / 非今日剩余","適用日に移す元金・今日の残額ではありません")+" · JPY","reconciliation-principal")
                ui.waitUntil(10000){android.view.inspector.WindowInspector.getGlobalWindowViews().any{androidx.core.view.ViewCompat.getRootWindowInsets(it)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true}}
                shot("$theme-$language-chain-ime")
            }
            tapTag("reconciliation-preview");ui.waitUntil(10000){!vm.busy.value&&ui.onAllNodesWithTag("reconciliation-back").fetchSemanticsNodes().isNotEmpty()};ui.onNodeWithTag("reconciliation-save").assertDoesNotExist();shot("$theme-$language-chain-parent-required");tapTag("reconciliation-back")
            if(resolve) {
                tapTag("reconciliation-open-bill-${first.id}")
                replace("finance-actual-principal","500");replace("finance-actual-fee","100")
                tapTag("finance-bill-preview");tapTag("finance-bill-confirm")
                ui.waitUntil(10000){!vm.busy.value&&ui.onAllNodesWithTag("reconciliation-bills-refreshed").fetchSemanticsNodes().isNotEmpty()}
                principal().assertTextContains("1200")
                textField(text("仍待付本金 / 不明可留空","未払元金・不明は空欄")+" · JPY","reconciliation-bill-principal-${first.id}").assertTextContains("500")
            }
            ui.onNodeWithTag("reconciliation-parent-$parent").performScrollTo()
            ui.onNodeWithTag("sheet-scroll").performSemanticsAction(SemanticsActions.ScrollBy){it(0f,-300f*InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density)}
            shot("$theme-$language-chain-parent-link")
            tapTag("reconciliation-parent-$parent")
            ui.waitUntil(10000){ui.onAllNodesWithTag("reconciliation-bill-${rootBill.id}").fetchSemanticsNodes().isNotEmpty()}
            principal().assertTextContains("1000");replace("reconciliation-principal","1200")
            val previousBill=graph.bills.first{it.planId==parent&&it.number==1}
            if(cancelParentFee)replace("reconciliation-bill-fee-${previousBill.id}","10")
            tapTag("reconciliation-preview");ui.waitUntil(10000){ui.onAllNodesWithTag("reconciliation-confirmed").fetchSemanticsNodes().isNotEmpty()}
            shot("$theme-$language-chain-parent-review");tapTag("reconciliation-confirmed");tapTag("reconciliation-save")
            ui.waitUntil(10000){!vm.busy.value&&ui.onAllNodesWithTag("reconciliation-parent-$parent").fetchSemanticsNodes().isNotEmpty()}
            principal().assertTextContains("1200")
            if(cancelParentFee)textField(text("仍待付费用 / 不明可留空","未払手数料・不明は空欄")+" · JPY","reconciliation-bill-fee-${previousBill.id}").assertTextContains("10")
            tapTag("reconciliation-preview");ui.waitUntil(10000){ui.onAllNodesWithTag("reconciliation-confirmed").fetchSemanticsNodes().isNotEmpty()}
            ui.onAllNodesWithText(text("未知 / 未知","不明 / 不明")).assertCountEquals(if(resolve)1 else 2)
            shot("$theme-$language-chain-manual-review")
            ui.onAllNodesWithText(text("未知 / 未知","不明 / 不明")).onLast().performScrollTo()
            ui.onNodeWithTag("sheet-scroll").performSemanticsAction(SemanticsActions.ScrollBy){it(0f,140f*InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density)}
            shot("$theme-$language-chain-unknown")
            tapTag("reconciliation-confirmed");tapTag("reconciliation-save")
            ui.waitUntil(10000){!vm.busy.value&&vm.financialState.value?.plans?.firstOrNull{it.id==child}?.reconciliationJson!=null}
            val after=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()};val f=runBlocking{db.financialDao().graph(book).first()!!};val plan=f.plans.first{it.id==child}
            assertEquals(before.storedPostings,after.storedPostings);assertEquals(before.visibleEvents,after.visibleEvents);assertEquals(before.expenseComponents,after.expenseComponents)
            assertEquals(graph.arrangements,f.arrangements);assertEquals(rootBill,f.bills.first{it.id==rootBill.id})
            assertFalse(plan.complete);assertEquals(1200L,plan.principalMinor);assertFalse(repo.financialPredictionStale(after,f,plan))
            assertNull(repo.arrangementBaselineIssue(after,f,f.plans.first{it.id==parent}))
            val bills=f.bills.filter{it.planId==child};assertEquals(listOf(600L,500L),bills.map{it.totalMinor})
            assertNull(bills.last().principalMinor);assertNull(bills.last().feeMinor)
            if(resolve){assertEquals(500L,bills.first().principalMinor);assertEquals(100L,bills.first().feeMinor)}else{assertNull(bills.first().principalMinor);assertNull(bills.first().feeMinor)}
            if(cancelParentFee)assertEquals(10L,after.billOutstanding(f.bills.first{it.id==previousBill.id}).feeMinor)
            assertEquals(1200L,after.balance(after.accounts.first{it.id==card}));assertEquals(10000L,after.balance(after.accounts.first{it.id==bank}));assertEquals(1,after.visibleEvents.size)
            for(target in listOf(parent,child))assertNull(runBlocking{db.financialDao().draft(book,FinancialDraftContext("ARRANGEMENT_REVIEW",card,targetId=target).slot())})
            tapTag("analysis-tab-plans");tapTag("finance-plan-history-$child");tapTag("finance-plan-version-open-${plan.revision}");ui.onNodeWithTag("reconciliation-history").performScrollTo();shot("$theme-$language-chain-history");tapTag("sheet-close")
            assertNull(vm.error.value)
        }
    }
}
