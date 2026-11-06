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
import com.yohaku.expensetracker.domain.finance.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.features.*
import com.yohaku.expensetracker.ui.haptics.Haptic
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.time.*

internal data class BatchHistoryFixture(val book:String,val bank:String,val card:String,val pool:String,val sources:List<String>)
internal suspend fun batchHistoryFixture(repo:LedgerRepository,theme:String,language:String):BatchHistoryFixture {
    val book=repo.createBook("办理历史 $theme $language",Currency.JPY,theme,language,startDate="2026-11-01")
    repo.updateBook(repo.dao.book(book)!!.copy(initialized=true))
    val bank=repo.saveAccount(book,"Bank",AccountType.BANK,Currency.JPY,Money(Currency.JPY,50000),"2026-11-01")
    val card=repo.saveAccount(book,"Card",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,0),"2026-11-01")
    val category=repo.saveCategory(book,"生活",EntryKind.EXPENSE,null)
    repo.commitFinancialRule(repo.previewFinancialRule(book,card,FinancialRuleVersion("",book,card,FinanceMode.SINGLE,"2026-11-01","Asia/Tokyo",BillingRule(30,true,1,10,bank))))
    for((name,amount) in listOf("Grocery" to "6800","Train" to "9600"))repo.saveEntry(book,EntryDraft(accountId=card,categoryId=category,amount=amount,date="2026-11-01",merchant=name,cardPaymentChoice="SINGLE"))
    val sourceGraph=repo.db.financialDao().graph(book).first()!!
    sourceGraph.bills.forEach{b->repo.commitFinancialBillConfirmation(repo.previewFinancialBillConfirmation(book,b.id,KnownBillInput(b.date,b.totalMinor,b.principalMinor,b.feeMinor,bank)))}
    repo.commitFinancialRule(repo.previewFinancialRule(book,card,FinancialRuleVersion("",book,card,FinanceMode.REVOLVING,"2026-11-01","Asia/Tokyo",BillingRule(30,true,1,10,bank),revolving=RevolvingRule(InterestRule("0",ChargeFrequency.DAILY,BalanceBasis.EVENT_BALANCE,true,true,365,ChargeRounding.PERIOD_TOTAL,"DOWN",FirstCharge.NORMAL),PaymentMethod.FIXED_PRINCIPAL,6000,allocation=AllocationOrder.FEES_FIRST,automatic=false))))
    val dates=(0..3).map{LocalDate.parse("2026-12-10").plusMonths(it.toLong()).toString()}
    val windows=(0..3).map{val d=LocalDate.parse("2026-11-01").plusMonths(it.toLong());AccrualWindow(d.toString(),d.withDayOfMonth(d.lengthOfMonth()).toString())}
    val pool=repo.commitFinancialPrediction(repo.previewFinancialPool(book,FinancialPredictionInput(card,"Monthly",FinanceMode.REVOLVING,FinancialScheduleTerms("2026-11-01",dates,windows),paymentAccountId=bank,currentArrangementConfirmed=true,pool=FinancialPoolInput(0,PoolSourceDate.TRANSACTION))))!!
    return BatchHistoryFixture(book,bank,card,pool,sourceGraph.plans.map{it.id})
}

@RunWith(AndroidJUnit4::class)
class FinancialBatchRevolvingFlowTest {
    @get:Rule val ui=createComposeRule()
    private lateinit var db:YohakuDatabase
    private lateinit var repo:LedgerRepository
    private lateinit var vm:AppViewModel
    private val haptics=RecordingHapticOutput()
    private var hapticsPreviouslyEnabled=true
    private val clock=Clock.fixed(Instant.parse("2026-11-04T01:59:42.123456789Z"),ZoneId.of("Asia/Tokyo"))
    @Before fun open() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        repo=LedgerRepository(db,clock)
        vm=AppViewModel(repo,AppPreferences(context),testReferenceRates(AppPreferences(context),repo.clock))
        hapticsPreviouslyEnabled=runBlocking{vm.preferences.hapticsEnabled.first()}
        runBlocking{vm.preferences.setHapticsEnabled(true)}
        ui.setContent{NativeApp(vm,haptics=haptics)}
    }
    @After fun close(){runBlocking{vm.preferences.setHapticsEnabled(hapticsPreviouslyEnabled)};vm.viewModelScope.cancel();db.close()}
    private fun tap(tag:String) {
        finishFinancialInputBeforeTap(ui,tag)
        ui.waitUntil(10000){!vm.busy.value&&ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}
        val node=ui.onNodeWithTag(tag)
        if(!tag.startsWith("finance-tab-")&&tag!="finance-back")node.scrollIfScrollable()
        ui.waitForIdle();node.performClick();ui.waitForIdle()
    }
    private fun align(tag:String) {
        ui.onNodeWithTag(tag).performScrollTo();ui.waitForIdle()
        val scroll=if(ui.onAllNodesWithTag("sheet-scroll").fetchSemanticsNodes().isNotEmpty())ui.onNodeWithTag("sheet-scroll") else ui.onNodeWithTag("page-scroll")
        val offset=ui.onNodeWithTag(tag).getUnclippedBoundsInRoot().top.value-scroll.getUnclippedBoundsInRoot().top.value-12
        val density=InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        scroll.performSemanticsAction(SemanticsActions.ScrollBy){it(0f,offset*density)};ui.waitForIdle()
    }
    private fun shot(name:String) {
        if(!visualEnabled())return
        ui.waitForIdle();InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(350,5000)
        val file=File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"phase-c-batch-revolving/$name.png")
        file.parentFile!!.mkdirs();file.outputStream().use{ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    private fun enter(x:BatchHistoryFixture) {
        runBlocking{vm.preferences.select(x.book)}
        ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==x.book&&!vm.busy.value}
        ui.runOnIdle{vm.navigate("accounts")};tap("account-${x.card}");tap("account-financial")
    }
    private fun convert(x:BatchHistoryFixture,prefix:String):String {
        tap("finance-next-batch-revolving")
        ui.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("batch-revolving-fee"))).performScrollTo().performTextReplacement("0")
        tap("batch-revolving-preview");tap("batch-revolving-record")
        val time=if(prefix.endsWith("-ja"))"2026年11月4日 10:59・Asia/Tokyo"else"2026年11月4日 10:59 · Asia/Tokyo"
        ui.onNodeWithTag("finance-time-applied").assert(hasAnyDescendant(hasText(time)))
        // Merely opening/closing the time picker must preserve the exact original instant.
        tap("date-field-0");tap("date-field-0")
        shot("$prefix-financial-time-confirm")
        tap("batch-revolving-platform");tap("batch-revolving-confirm")
        ui.waitUntil(10000){!vm.busy.value&&vm.financialState.value?.arrangements?.size==2}
        val history=runBlocking{repo.financialBatchHistory(x.book,x.card)}.single()
        assertEquals(clock.instant().toString(),history.archive.arrangements.first().appliedAt)
        return history.archive.undoGroupId
    }
    @Test fun conversionHistoryCancellationAndRestorationKeepOriginalFactsAcrossLooks() {
        for((theme,language) in testLooks(this)) {
            val prefix="$theme-$language"
            val x=runBlocking{batchHistoryFixture(repo,theme,language)}
            val original=runBlocking{db.financialDao().graph(x.book).first()!!}
            val cash=runBlocking{repo.snapshot(repo.dao.book(x.book)!!).first()}.storedPostings
            enter(x);shot("$prefix-finance-overview-terms")
            val group=convert(x,prefix)
            ui.onNodeWithTag("finance-batch-open-$group").assertDoesNotExist()
            tap("finance-tab-plans");shot("$prefix-finance-plans-terms")
            openFinancialPlan(ui,vm,x.pool);tap("finance-plan-history-${x.pool}")
            ui.waitUntil(10000){ui.onAllNodesWithTag("finance-batch-history-$group").fetchSemanticsNodes().isNotEmpty()}
            tap("sheet-close")
            tap("finance-tab-manage");shot("$prefix-finance-manage-terms");tap("finance-manage-history")
            ui.waitUntil(10000){ui.onAllNodesWithTag("finance-batch-history-$group").fetchSemanticsNodes().isNotEmpty()}
            ui.onAllNodesWithTag("finance-batch-open-$group").assertCountEquals(1)
            ui.onNodeWithText("Grocery · Train").assertExists()
            shot("$prefix-batch-history");tap("finance-batch-open-$group")
            ui.onNodeWithTag("finance-batch-undo-summary").assert(hasAnyDescendant(hasText("Grocery · ¥6,800")))
            ui.onNodeWithTag("finance-batch-undo-summary").assert(hasAnyDescendant(hasText("Train · ¥9,600")))
            ui.onNodeWithTag("finance-batch-undo-summary").assert(hasAnyDescendant(hasText("¥16,400")))
            ui.onAllNodesWithText(group,substring=true).assertCountEquals(0)
            val facts=batchRestorationFacts(runBlocking{repo.financialBatchHistory(x.book,x.card)}.single())
            assertEquals(listOf("Grocery","Train"),facts.transfers.map{it.name})
            assertEquals(listOf("Grocery","Train","Monthly"),facts.restore.map{it.name})
            shot("$prefix-batch-undo-summary")
            val beforeCancel=runBlocking{db.financialDao().graph(x.book).first()!!}
            tap("finance-batch-undo-cancel")
            assertEquals(beforeCancel,runBlocking{db.financialDao().graph(x.book).first()!!})
            tap("finance-batch-open-$group");haptics.clear();tap("batch-revolving-undo-confirm")
            ui.waitUntil(10000){haptics.calls.isNotEmpty()};assertEquals(listOf(Haptic.Restored),haptics.calls.map{it.second})
            ui.waitUntil(10000){!vm.busy.value&&vm.financialState.value?.arrangements?.isEmpty()==true&&ui.onAllNodesWithText(if(language=="ja")"取消済み・元の予定を復元。手続き履歴は保持します。"else"已撤销，原安排已恢复；办理历史保留。").fetchSemanticsNodes().isNotEmpty()}
            val restored=runBlocking{db.financialDao().graph(x.book).first()!!}
            for(bill in original.bills)assertEquals(bill.totalMinor,restored.bills.first{it.id==bill.id}.totalMinor)
            assertEquals(0L,restored.plans.first{it.id==x.pool}.principalMinor)
            assertTrue(restored.plans.filter{it.id in x.sources}.all{it.active})
            assertEquals(cash,runBlocking{repo.snapshot(repo.dao.book(x.book)!!).first()}.storedPostings)
            assertTrue(runBlocking{repo.financialBatchHistory(x.book,x.card)}.single().undone)
            shot("$prefix-batch-undone-history")
            tap("finance-batch-open-$group")
            ui.onNodeWithTag("batch-revolving-undo-confirm").assertDoesNotExist()
            tap("finance-batch-undo-cancel")
        }
    }
    @Test fun subsequentPaymentBlocksUndoAndLinksTheCurrentArrangementAcrossLooks() {
        for((theme,language) in testLooks(this)) {
            val x=runBlocking{batchHistoryFixture(repo,theme,language)}
            val group=runBlocking {
                val review=repo.previewBatchRevolving(x.book,BatchRevolvingInput(x.card,"2026-12-10",x.sources,16400,x.pool,"2026-11-04",0))
                val id=repo.commitBatchRevolving(review,clock.instant().toString(),true)!!
                val bill=db.financialDao().bills(x.book,x.pool).first{it.active}
                repo.commitFinancialLedger(repo.previewRepayment(x.book,FinancialRepaymentInput(x.bank,x.card,Money(Currency.JPY,100),"2026-11-04",Money(Currency.JPY,100),Money(Currency.JPY,100),Money(Currency.JPY,0),Money(Currency.JPY,0),platformConfirmed=true,clearingDate="2026-11-04",allocations=listOf(RepaymentAllocationInput(bill.id,principalMinor=100,interestMinor=0,feeMinor=0)))))
                id
            }
            enter(x);tap("finance-tab-manage");tap("finance-manage-history");tap("finance-batch-open-$group")
            val before=runBlocking{db.financialDao().graph(x.book).first()!!}
            haptics.clear();tap("batch-revolving-undo-confirm")
            ui.waitUntil(10000){haptics.calls.isNotEmpty()};assertEquals(listOf(Haptic.Warning),haptics.calls.map{it.second})
            ui.waitUntil(10000){ui.onAllNodesWithTag("finance-batch-undo-blocked").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithTag("finance-batch-undo-blocked").assertTextContains(if(language=="ja")"支払・予定の変更"else"付款或安排变化",substring=true)
            assertEquals(before,runBlocking{db.financialDao().graph(x.book).first()!!})
            align("finance-batch-undo-blocked");shot("$theme-$language-batch-undo-blocked")
            tap("finance-batch-current");ui.onNodeWithTag("finance-plan-detail-${x.pool}").assertExists()
        }
    }
}
