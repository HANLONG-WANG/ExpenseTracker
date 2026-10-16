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
class FinancialProgressFlowTest {
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
        val file=File(context.getExternalFilesDir(null),"phase-c-progress/$name.png");file.parentFile!!.mkdirs()
        file.outputStream().use{ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
        if(name.endsWith("-ime")||name.endsWith("-history")) {
            val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(file.parentFile,"$name-screen.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
        }
    }
    @Test fun remainingObligationsAndBothLoanModesKeepActualAmountsSeparateInEveryThemeAndLanguage() {
        for(theme in listOf("magazine","journal","objects").filter{InstrumentationRegistry.getArguments().getString("theme")?.let{v->v==it}?:true})for(language in listOf("zh","ja").filter{InstrumentationRegistry.getArguments().getString("language")?.let{v->v==it}?:true}) {
            fun text(zh:String,ja:String)=if(language=="ja")ja else zh
            val ids=runBlocking {
                val b=repo.createBook("借款 $theme $language",Currency.JPY,theme,language,startDate="2026-10-01")
                repo.updateBook(repo.dao.book(b)!!.copy(initialized=true))
                val bank=repo.saveAccount(b,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
                repo.saveAccount(b,"他口座",AccountType.BANK,Currency.JPY,Money(Currency.JPY,100),"2026-10-01")
                val loan=repo.saveAccount(b,"ローン",AccountType.LOAN,Currency.JPY,Money(Currency.JPY,1000),"2026-10-01")
                repo.commitFinancialLedger(repo.previewBorrowing(b,FinancialBorrowingInput(loan,"期首固定",FinanceMode.FIXED_LOAN,Money(Currency.JPY,1000),"2026-10-01",opening=true)))
                val fixed=db.financialDao().graph(b).first()!!.contracts.single()
                val plan=repo.commitManualFinancialPlan(repo.previewManualFinancialPlan(b,ManualFinancialPlanInput(loan,"固定二期",FinanceMode.FIXED_LOAN,listOf(KnownBillInput("2026-11-05",520,500,20,bank),KnownBillInput("2026-12-05",520,500,20,bank)),1000,complete=true,opening=true)))!!
                repo.linkFinancialContractPlan(b,fixed.id,plan)
                val bill=db.financialDao().bills(b,plan).first()
                repo.commitFinancialLedger(repo.previewRepayment(b,FinancialRepaymentInput(bank,loan,Money(Currency.JPY,210),"2026-10-20",Money(Currency.JPY,210),Money(Currency.JPY,200),Money(Currency.JPY,0),Money(Currency.JPY,10),clearingDate="2026-10-20",platformConfirmed=true,allocations=listOf(RepaymentAllocationInput(bill.id,fixed.id,200,0,10)))))
                repo.commitFinancialLedger(repo.previewRepayment(b,FinancialRepaymentInput(bank,loan,Money(Currency.JPY,50),"2026-10-25",Money(Currency.JPY,50),Money(Currency.JPY,50),Money(Currency.JPY,0),Money(Currency.JPY,0),platformConfirmed=false,allocations=listOf(RepaymentAllocationInput(bill.id,fixed.id,50,0,0)))))
                val borrowing=repo.commitFinancialLedger(repo.previewBorrowing(b,FinancialBorrowingInput(loan,"循環追加",FinanceMode.CREDIT_LINE,Money(Currency.JPY,300),"2026-10-24",bank,Money(Currency.JPY,290),Money(Currency.JPY,10),separateFee=Money(Currency.JPY,0))))!!
                val line=db.financialDao().graph(b).first()!!.contracts.single{it.fundingEventId==borrowing}
                val linePlan=repo.commitManualFinancialPlan(repo.previewManualFinancialPlan(b,ManualFinancialPlanInput(loan,"循環一部",FinanceMode.CREDIT_LINE,listOf(KnownBillInput("2026-11-10",100,90,10,bank)),300,sourceEventId=borrowing)))!!
                vm.preferences.select(b);listOf(b,bank,loan,plan,fixed.id,line.id,linePlan)
            }
            val book=ids[0];val bank=ids[1];val loan=ids[2];val plan=ids[3];val fixed=ids[4];val line=ids[5];val linePlan=ids[6]
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book&&!vm.busy.value}
            ui.runOnIdle{vm.navigate("accounts")};tapTag("account-$loan");tapTag("account-financial")
            if(theme=="magazine"&&language=="zh") {
                val other=runBlocking{repo.snapshot(repo.dao.book(book)!!).first().accounts.single{it.name=="他口座"}.id}
                runBlocking{repo.saveDraft(book,EntryDraft(kind=EntryKind.INCOME,accountId=other,amount="42",date="2026-10-25",note="keep"))}
                ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.draft?.note=="keep"}
                tapTag("finance-fund-transfer-$bank")
                ui.onNodeWithText("继续这笔草稿").performClick()
                ui.waitUntil(10000){vm.entry.value?.note=="keep"};assertEquals("42",vm.entry.value!!.amount)
                ui.runOnIdle{vm.leaveEntry(book,true,"rules")}
                ui.waitUntil(10000){vm.route.value=="rules"&&!vm.busy.value&&(vm.state.value as? AppState.Ready)?.snapshot?.draft?.note=="keep"}
                tapTag("finance-fund-transfer-$bank")
                ui.onNodeWithText("放弃草稿，记新的一笔").performClick()
                ui.waitUntil(10000){vm.entry.value?.kind==EntryKind.TRANSFER}
                assertEquals(other,vm.entry.value!!.accountId);assertEquals(bank,vm.entry.value!!.toAccountId);assertEquals("",vm.entry.value!!.amount)
                ui.runOnIdle{vm.leaveEntry(book,false,"rules")}
                ui.waitUntil(10000){vm.route.value=="rules"&&!vm.busy.value&&(vm.state.value as? AppState.Ready)?.snapshot?.draft==null}
            }
            ui.onNodeWithTag("loan-mode-contract-$fixed").performScrollTo();shot("$theme-$language-fixed")
            tapTag("analysis-tab-CREDIT_LINE")
            ui.onNodeWithTag("loan-mode-contract-$line").performScrollTo();shot("$theme-$language-revolving")
            tapTag("loan-mode-plans")
            ui.onNodeWithTag("finance-progress-$plan").performScrollTo()
            for(amount in listOf("¥800","¥30","¥830","¥50","¥780"))ui.onNode(hasText(amount) and hasAnyAncestor(hasTestTag("finance-progress-$plan"))).assertExists()
            shot("$theme-$language-progress")
            ui.onNodeWithTag("finance-progress-$linePlan").performScrollTo()
            ui.onNode(hasText(text("只录入了部分安排。以上不是全部剩余期数或全部未来费用。","一部の予定のみ入力済み。全残回数・全将来費用ではありません。")) and hasAnyAncestor(hasTestTag("finance-progress-$linePlan"))).assertExists()
            tapTag("analysis-tab-movements")
            ui.onNodeWithTag("finance-contract-$line").performScrollTo()
            for(amount in listOf("¥290","¥10","¥0"))ui.onNode(hasText(amount,substring=true) and hasAnyAncestor(hasTestTag("finance-contract-$line"))).assertExists()
            shot("$theme-$language-contract")
            val snapshot=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertEquals(3,snapshot.events.size);assertEquals(1100L,snapshot.balance(snapshot.accounts.first{it.id==loan}));assertEquals(10030L,snapshot.balance(snapshot.accounts.first{it.id==bank}));assertEquals(20L,snapshot.monthExpense("2026-10"))
            assertNull(vm.error.value)
        }
    }
}
