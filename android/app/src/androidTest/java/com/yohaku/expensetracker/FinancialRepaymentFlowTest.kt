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
class FinancialRepaymentFlowTest {
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
        if(!name.endsWith("-ime"))androidx.test.espresso.Espresso.closeSoftKeyboard()
        ui.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(350,5000)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(context.getExternalFilesDir(null),"phase-c-repayment/$name.png");file.parentFile!!.mkdirs()
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    private fun showSection(tag:String) {
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        val node=ui.onNodeWithTag(tag)
        node.scrollIfScrollable();ui.waitForIdle()
        val header=ui.onNodeWithTag("finance-flow-scroll").getUnclippedBoundsInRoot().top.value
        val y=node.getUnclippedBoundsInRoot().top.value
        val density=InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        ui.onNodeWithTag("finance-flow-scroll").performSemanticsAction(SemanticsActions.ScrollBy){it(0f,(y-header-12)*density)}
        ui.waitForIdle()
    }
    private fun top() {ui.onNodeWithTag("finance-flow-scroll").performSemanticsAction(SemanticsActions.ScrollBy){it(0f,-100000f)};ui.waitForIdle()}
    @Test fun statementSingleTargetPendingAndConfirmedPaymentsKeepTheirReturnAndKnownAmounts() {
        for((theme,language) in testLooks(this)) {
            fun text(zh:String,ja:String)=if(language=="ja")ja else zh
            val ids=runBlocking {
                val b=repo.createBook("就地还款 $theme $language",Currency.JPY,theme,language,startDate="2026-10-01")
                repo.updateBook(repo.dao.book(b)!!.copy(initialized=true))
                val bank=repo.saveAccount(b,"支払銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,50000),"2026-10-01")
                val card=repo.saveAccount(b,"カード",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,12000),"2026-10-01")
                val plan=repo.commitManualFinancialPlan(repo.previewManualFinancialPlan(b,ManualFinancialPlanInput(card,"本期账单・当期請求",FinanceMode.INSTALLMENT,listOf(
                    KnownBillInput("2026-10-20",10000,10000,0,bank),KnownBillInput("2026-10-25",2100,2000,100,bank)),principalMinor=12000,opening=true)))!!
                val bills=db.financialDao().bills(b,plan).sortedBy{it.number}
                vm.preferences.select(b);listOf(b,bank,card,bills[0].id,bills[1].id)
            }
            val(book,bank,card,first,second)=ids
            fun snapshot()=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book&&!vm.busy.value}
            ui.runOnIdle{vm.navigate("accounts")};tapTag("account-$card");tapTag("account-financial");tapTag("finance-tab-statement")
            ui.onNodeWithTag("statement-amount-status-$first").assertTextEquals(text("金额已确认","金額確定"))
            ui.onNodeWithTag("statement-payment-status-$first").assertTextEquals(text("未付款","未払い"))
            ui.onNodeWithTag("statement-repay-$first").performScrollTo();shot("$theme-$language-statement-repayment-actions")
            tapTag("statement-repay-$first")
            val paid=textField(text("实际扣款总额","実出金総額")+" · JPY")
            paid.assertTextEquals("10000");ui.onNodeWithTag("finance-repay-cleared").assertIsOff()
            textField(text("平台实际收到","返済先の実受取額")+" · JPY").assertDoesNotExist()
            ui.onNodeWithTag("finance-repay-allocation-0").assertDoesNotExist()
            paid.performTextReplacement("10000.0")
            textField(text("平台实际收到","返済先の実受取額")+" · JPY").assertDoesNotExist()
            paid.performTextReplacement("5000")
            textField(text("清偿本金","返済元金")+" · JPY").assertTextEquals("")
            if(visualEnabled())showSection("finance-repay-details");shot("$theme-$language-repayment-partial-expanded")
            paid.performScrollTo().performTextReplacement("10000")
            textField(text("清偿本金","返済元金")+" · JPY").assertTextEquals("10000")
            textField(text("实付利息","実支払利息")+" · JPY").assertTextEquals("0")
            textField(text("平台费用","返済先手数料")+" · JPY").assertTextEquals("0")
            tapTag("finance-repay-details")
            ui.onNodeWithTag("finance-repay-allocation-summary").assert(hasAnyDescendant(hasText("¥10,000 / ¥0 / ¥0",substring=true)))
            top();shot("$theme-$language-repayment-basic")
            if(visualEnabled())showSection("finance-repay-allocation-summary");shot("$theme-$language-repayment-allocation-summary")
            if(visualEnabled()) {
                paid.performScrollTo().performClick()
                ui.waitUntil(10000){android.view.inspector.WindowInspector.getGlobalWindowViews().any{androidx.core.view.ViewCompat.getRootWindowInsets(it)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true}}
                shot("$theme-$language-repayment-ime")
                InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                ui.waitForIdle()
            }
            tapTag("finance-repay-preview")
            ui.waitUntil(10000){vm.financialLedgerReview.value!=null&&!vm.busy.value}
            val pending=vm.financialLedgerReview.value!!.repaymentInput!!
            assertFalse(pending.platformConfirmed);assertNull(pending.received);assertNull(pending.sourceFee)
            assertEquals(RepaymentAllocationInput(first,null,10000,0,0),pending.allocations.single())
            top();shot("$theme-$language-repayment-pending-review")
            tapTag("finance-flow-back");paid.assertTextEquals("10000");ui.onNodeWithTag("finance-repay-cleared").assertIsOff()
            tapTag("finance-repay-preview");tapTag("finance-ledger-confirm")
            ui.waitUntil(10000){vm.financialLedgerReview.value==null&&!vm.busy.value&&snapshot().repayments.size==1}
            val payment=snapshot().repayments.single().eventId
            assertEquals(40000L,snapshot().balance(snapshot().accounts.first{it.id==bank}));assertEquals(12000L,snapshot().balance(snapshot().accounts.first{it.id==card}))
            ui.onNodeWithTag("finance-tab-statement").assertIsSelected()
            ui.onNodeWithTag("statement-payment-status-$first").assertTextEquals(text("已付款，待平台确认","支払済み・返済先の確認待ち"))
            ui.onNodeWithTag("statement-repay-$first").assertDoesNotExist()
            ui.onNodeWithTag("statement-payment-$first-$payment").performScrollTo();shot("$theme-$language-statement-pending-payment")
            tapTag("statement-payment-$first-$payment")
            textField(text("清偿本金","返済元金")+" · JPY").assertTextEquals("10000")
            ui.onNodeWithTag("finance-repay-allocation-0").assertDoesNotExist()
            ui.onNodeWithTag("finance-repay-allocation-summary").assert(hasAnyDescendant(hasText("¥10,000 / ¥0 / ¥0",substring=true)))
            tapTag("finance-repay-cleared")
            textField(text("平台实际收到","返済先の実受取額")+" · JPY").performScrollTo().performTextReplacement("10000")
            textField(text("总扣款内含付款端费用","実出金総額に含む出金側手数料")+" · JPY").performScrollTo().performTextReplacement("0")
            if(visualEnabled())showSection("finance-repay-details");shot("$theme-$language-repayment-details")
            tapTag("finance-repay-preview");ui.waitUntil(10000){vm.financialLedgerReview.value!=null&&!vm.busy.value}
            assertTrue(vm.financialLedgerReview.value!!.balances.none{it.account.id==bank})
            top();shot("$theme-$language-repayment-confirmed-review")
            if(visualEnabled())showSection("finance-repay-review-targets");shot("$theme-$language-repayment-target-review")
            tapTag("finance-ledger-confirm")
            ui.waitUntil(10000){!vm.busy.value&&vm.financialLedgerReview.value==null&&snapshot().repayments.single().platformConfirmed}
            ui.onNodeWithTag("statement-payment-status-$first").assertTextEquals(text("已结清","精算済み"))
            assertEquals(1,snapshot().events.size);assertEquals(2000L,snapshot().balance(snapshot().accounts.first{it.id==card}))
            // Merged nonzero fees stay unsplit; changing the amount opens composition without proportional guesses.
            tapTag("statement-repay-$second");paid.assertTextEquals("2100")
            textField(text("实付利息","実支払利息")+" · JPY").assertTextEquals("")
            textField(text("平台费用","返済先手数料")+" · JPY").assertTextEquals("")
            paid.performScrollTo().performTextReplacement("1100")
            textField(text("清偿本金","返済元金")+" · JPY").assertTextEquals("")
            for((label,value) in listOf(text("清偿本金","返済元金") to "1000",text("实付利息","実支払利息") to "100",text("平台费用","返済先手数料") to "0"))textField(label+" · JPY").performScrollTo().performTextReplacement(value)
            tapTag("finance-repay-preview");tapTag("finance-ledger-confirm")
            ui.waitUntil(10000){!vm.busy.value&&vm.financialLedgerReview.value==null&&snapshot().repayments.size==2}
            tapTag("statement-repay-$second");paid.assertTextEquals("1000")
            ui.onNodeWithTag("finance-repay-cleared").assertIsOff()
            tapTag("financial-draft-discard")
            ui.onNodeWithTag("finance-tab-statement").assertIsSelected();assertNull(vm.error.value)
            tapTag("finance-tab-manage");tapTag("finance-manage-records");tapTag("finance-add-repayment")
            paid.performTextReplacement("800")
            textField(text("清偿本金","返済元金")+" · JPY").assertDoesNotExist()
            ui.onNodeWithTag("finance-repay-allocation-summary").assert(hasAnyDescendant(hasText(text("未知 / 未知 / 未知","不明 / 不明 / 不明"),substring=true)))
            if(visualEnabled())showSection("finance-repay-allocation-summary");shot("$theme-$language-repayment-unknown-collapsed")
            tapTag("financial-draft-discard")
        }
    }

    @Test fun existingCrossCurrencyPaymentRetainsFeesTransferAndMultipleAllocationsWhenEdited() {
        for((theme,language) in testLooks(this)) {
            fun text(zh:String,ja:String)=if(language=="ja")ja else zh
            val ids=runBlocking {
                val b=repo.createBook("已有付款 $theme $language",Currency.JPY,theme,language,startDate="2026-10-01")
                repo.updateBook(repo.dao.book(b)!!.copy(initialized=true))
                val reserve=repo.saveAccount(b,"予備資金",AccountType.BANK,Currency.JPY,Money(Currency.JPY,100000),"2026-10-01")
                val bank=repo.saveAccount(b,"支払銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,0),"2026-10-01")
                val loan=repo.saveAccount(b,"人民元借入",AccountType.LOAN,Currency.CNY,Money(Currency.CNY,100000),"2026-10-01")
                val transfer=repo.commitEntryReview(b,repo.previewEntry(b,EntryDraft(kind=EntryKind.TRANSFER,accountId=reserve,toAccountId=bank,amount="30000",toAmount="30000",date="2026-10-10",toDate="2026-10-10",fromConfirmed=true,toConfirmed=true)))!!
                val category=repo.saveCategory(b,"仮払",EntryKind.EXPENSE,null)
                val existing=repo.saveEntry(b,EntryDraft(accountId=bank,categoryId=category,amount="20100",date="2026-10-15",merchant="既存返済"))
                val plan=repo.commitManualFinancialPlan(repo.previewManualFinancialPlan(b,ManualFinancialPlanInput(loan,"二期契約",FinanceMode.FIXED_LOAN,listOf(KnownBillInput("2026-10-20",50500,50000,500,bank),KnownBillInput("2026-11-20",50500,50000,500,bank)),principalMinor=100000,opening=true)))!!
                vm.preferences.select(b);listOf(b,bank,loan,transfer,existing,plan)
            }
            val(book,bank,loan,transfer,existing)=ids;val plan=ids[5]
            fun snapshot()=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book&&!vm.busy.value}
            ui.runOnIdle{vm.navigate("accounts")};tapTag("account-$loan");tapTag("account-financial");tapTag("finance-tab-manage");tapTag("finance-manage-records");tapTag("finance-add-repayment")
            tapTag("finance-repay-link-existing");tapTag("select-trigger-"+text("付款来源","支払の記録"))
            ui.onNode(hasText("既存返済",substring=true) and hasAnyAncestor(hasTestTag("select-menu-frame"))).performScrollTo().performClick()
            textField(text("实际扣款总额","実出金総額")+" · JPY").assertTextEquals("20100")
            tapTag("select-trigger-"+text("关联先前换汇 / 转账","先行する両替・振替に関連付け"))
            ui.onNode(hasText("2026-10-10 · "+transfer.take(8)) and hasAnyAncestor(hasTestTag("select-menu-frame"))).performScrollTo().performClick()
            tapTag("finance-repay-cleared")
            for((label,value) in listOf(text("平台实际收到","返済先の実受取額") to "1010.00",text("清偿本金","返済元金") to "1000.00",text("实付利息","実支払利息") to "6.00",text("平台费用","返済先手数料") to "4.00"))textField(label+" · CNY").performScrollTo().performTextReplacement(value)
            textField(text("利息及平台费用统计合计","利息・返済先手数料の集計合計")+" · JPY").performScrollTo().performTextReplacement("200")
            textField(text("总扣款内含付款端费用","実出金総額に含む出金側手数料")+" · JPY").performScrollTo().performTextReplacement("100")
            tapTag("finance-repay-multiple-targets")
            val bills=vm.financialState.value!!.bills.filter{it.planId==plan}.sortedBy{it.number}
            for(i in 0..1) {
                tapTag("finance-repay-add-allocation")
                val ancestor="finance-repay-allocation-$i"
                tapTag("select-trigger-"+text("目标账单","対象の請求"),ancestor)
                ui.onNode(hasText("二期契約 · ${i+1} · ${bills[i].date}") and hasAnyAncestor(hasTestTag("select-menu-frame"))).performScrollTo().performClick()
                for((label,value) in listOf(text("分配本金","充当元金") to "500.00",text("分配利息","充当利息") to "3.00",text("分配费用","充当手数料") to "2.00"))textField(label,ancestor).performScrollTo().performTextReplacement(value)
            }
            tapTag("finance-repay-preview");ui.waitUntil(10000){vm.financialLedgerReview.value!=null&&!vm.busy.value}
            val review=vm.financialLedgerReview.value!!
            assertTrue(review.balances.none{it.account.id==bank});assertEquals(transfer,review.repaymentInput!!.linkedTransferEventId);assertEquals(2,review.repaymentInput!!.allocations.size)
            tapTag("finance-ledger-confirm")
            ui.waitUntil(10000){!vm.busy.value&&vm.financialLedgerReview.value==null&&snapshot().repayments.isNotEmpty()}
            assertEquals(9900L,snapshot().balance(snapshot().accounts.first{it.id==bank}));assertEquals(0L,snapshot().balance(snapshot().accounts.first{it.id==loan}));assertEquals(2,snapshot().events.size)
            tapTag("finance-edit-repayment-$existing")
            textField(text("实际扣款总额","実出金総額")+" · JPY").assertTextEquals("20100")
            textField(text("平台实际收到","返済先の実受取額")+" · CNY").assertTextEquals("1010.00")
            textField(text("利息及平台费用统计合计","利息・返済先手数料の集計合計")+" · JPY").assertTextEquals("200")
            textField(text("总扣款内含付款端费用","実出金総額に含む出金側手数料")+" · JPY").assertTextEquals("100")
            textField(text("分配本金","充当元金"),"finance-repay-allocation-1").assertTextEquals("500.00")
            ui.onNodeWithTag("select-trigger-"+text("关联先前换汇 / 转账","先行する両替・振替に関連付け")).assert(hasText("2026-10-10 · "+transfer.take(8)))
            ui.onNodeWithTag("finance-repay-allocation-summary").assert(hasAnyDescendant(hasText("CN¥1,000.00 / CN¥6.00 / CN¥4.00",substring=true)))
            if(visualEnabled()) {
                showSection("finance-repay-allocation-summary");shot("$theme-$language-repayment-existing-summary")
                if(visualEnabled())showSection("finance-repay-details");shot("$theme-$language-repayment-existing-details")
                if(visualEnabled())showSection("finance-repay-link-existing");shot("$theme-$language-repayment-existing-links")
                if(visualEnabled())showSection("finance-repay-allocation-1");shot("$theme-$language-repayment-existing-multiple")
            }
            // Collapsing sections never deletes their original values.
            tapTag("finance-repay-details");tapTag("finance-repay-link-existing");tapTag("finance-repay-multiple-targets")
            tapTag("finance-repay-preview");ui.waitUntil(10000){vm.financialLedgerReview.value!=null&&!vm.busy.value}
            assertEquals(review.repaymentInput!!.copy(sourceFeeBase=Money(Currency.JPY,100)),vm.financialLedgerReview.value!!.repaymentInput)
            tapTag("finance-ledger-confirm");ui.waitUntil(10000){!vm.busy.value&&vm.financialLedgerReview.value==null}
            assertEquals(2,snapshot().events.size);assertEquals(9900L,snapshot().balance(snapshot().accounts.first{it.id==bank}));assertNull(vm.error.value)
        }
    }
}
