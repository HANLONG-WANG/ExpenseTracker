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
class CardPaymentFlowTest {
    @get:Rule val ui=createComposeRule()
    private lateinit var db:YohakuDatabase
    private lateinit var repo:LedgerRepository
    private lateinit var vm:AppViewModel
    @Before fun open() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        repo=LedgerRepository(db,Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"),ZoneId.of("Asia/Tokyo")))
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
        val file=File(context.getExternalFilesDir(null),"phase-c-card/$name.png");file.parentFile!!.mkdirs()
        file.outputStream().use{ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
        if(name.endsWith("-ime")||name.endsWith("-history")) {
            val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(file.parentFile,"$name-screen.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
        }
    }
    @Test fun cardPaymentEntryOverviewAndDebitMonthStatementsInEveryThemeAndLanguage() {
        for(theme in listOf("magazine","journal","objects").filter{InstrumentationRegistry.getArguments().getString("theme")?.let{v->v==it}?:true})for(language in listOf("zh","ja").filter{InstrumentationRegistry.getArguments().getString("language")?.let{v->v==it}?:true}) {
            fun text(zh:String,ja:String)=if(language=="ja")ja else zh
            val (book,card,category)=runBlocking {
                val b=repo.createBook("支払月 $theme $language",Currency.JPY,theme,language,startDate="2026-10-01")
                repo.updateBook(repo.dao.book(b)!!.copy(initialized=true))
                val bank=repo.saveAccount(b,"引落銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,700),"2026-10-01")
                repo.saveAccount(b,"別の銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,5000),"2026-10-01")
                val a=repo.saveAccount(b,"手動カード",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,0),"2026-10-01")
                val cat=repo.saveCategory(b,"買物",EntryKind.EXPENSE,null)
                for(mode in listOf(FinanceMode.SINGLE,FinanceMode.INSTALLMENT))repo.commitFinancialRule(repo.previewFinancialRule(b,a,FinancialRuleVersion("",b,a,mode,"2026-10-01","Asia/Tokyo",BillingRule(20,true,1,5,bank),installment=if(mode==FinanceMode.INSTALLMENT)InstallmentRule(listOf(3),InstallmentFeeMethod.TOTAL_AMOUNT,totalFeeMinor=60,principalRemainder=RemainderPlacement.LAST,feeRemainder=RemainderPlacement.LAST,roundingMode="HALF_UP")else null)))
                repo.saveEntry(b,EntryDraft(accountId=a,categoryId=cat,amount="2000",date="2026-10-21",merchant="翌々月の利用",cardPaymentChoice="SINGLE"))
                repo.saveEntry(b,EntryDraft(accountId=a,categoryId=cat,amount="400",date="2026-10-15",merchant="未補完の利用"))
                repo.commitExpectedIncome(repo.previewExpectedIncome(b,ExpectedIncomeInput("収入予定",Money(Currency.JPY,500),"2026-11-01",bank)))
                vm.preferences.select(b);Triple(b,a,cat)
            }
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book&&!vm.busy.value}
            val snapshot=(vm.state.value as AppState.Ready).snapshot!!
            ui.runOnIdle{vm.startEntry(snapshot);vm.changeEntry(book,vm.entry.value!!.copy(accountId=card,categoryId=category));vm.navigate("entry")}
            textField(text("金额","金額")+" · JPY").performScrollTo().performTextReplacement("3000")
            tapTag("select-trigger-"+text("平台当前付款方式","現在の支払方法"))
            ui.onNodeWithText(text("分期","分割払い")).performClick()
            val periods=textField(text("分期次数","分割回数"));periods.performScrollTo()
            if(InstrumentationRegistry.getArguments().getString("realIme")=="true")tapTag("field-"+text("分期次数","分割回数"))
            periods.performTextReplacement("3")
            if(InstrumentationRegistry.getArguments().getString("realIme")=="true") {
                ui.waitUntil(10000){android.view.inspector.WindowInspector.getGlobalWindowViews().any{androidx.core.view.ViewCompat.getRootWindowInsets(it)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true}}
                shot("$theme-$language-payment-ime")
            }
            tapTag("entry-save-button")
            ui.waitUntil(10000){vm.entry.value==null&&!vm.busy.value}
            val saved=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            val event=saved.visibleEvents.single{it.cardPaymentMode=="INSTALLMENT"}
            assertEquals(3,event.cardPaymentPeriods);assertEquals(3,saved.events.size);assertEquals(5400L,saved.balance(saved.accounts.first{it.id==card}));assertEquals(5400L,saved.monthExpense("2026-10"))
            val pending=runBlocking {
                val bank=saved.accounts.single{it.name=="引落銀行"}.id
                val plan=db.financialDao().plans(book,card).single{it.sourceEventId==event.id&&it.active}
                val bill=db.financialDao().bills(book,plan.id).single{it.number==1}
                repo.commitFinancialLedger(repo.previewRepayment(book,FinancialRepaymentInput(bank,card,Money(Currency.JPY,500),"2026-10-15",Money(Currency.JPY,500),Money(Currency.JPY,480),Money(Currency.JPY,0),Money(Currency.JPY,20),platformConfirmed=false,allocations=listOf(RepaymentAllocationInput(bill.id,null,480,0,20)))))!!
            }
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.repayments?.any{it.eventId==pending}==true}
            ui.runOnIdle{vm.navigate("accounts")}
            tapTag("account-$card");tapTag("account-financial")
            ui.waitUntil(10000){ui.onAllNodesWithTag("finance-overview-next").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithTag("finance-overview-amount").assertTextEquals("¥1,020")
            ui.onNodeWithTag("finance-overview-next").performScrollTo();ui.waitUntil(5000){vm.notice.value==null};shot("$theme-$language-card-overview")
            ui.onNodeWithTag("finance-pending-$pending").assertExists()
            ui.onNode(hasText("¥520") and hasAnyAncestor(hasTestTag("finance-overview-next"))).assertExists()
            tapTag("finance-unplanned-open")
            ui.onNodeWithTag("finance-add-prediction").assertExists()
            tapTag("analysis-tab-overview")
            val bank=saved.accounts.single{it.name=="引落銀行"}
            ui.onNodeWithTag("finance-funding-${bank.id}").performScrollTo();shot("$theme-$language-card-funding")
            tapTag("finance-next-statement")
            ui.onNodeWithTag("finance-statement-amount").assertTextEquals("¥1,020")
            ui.onNodeWithTag("finance-statement-total").performScrollTo();shot("$theme-$language-card-statement")
            val later=saved.events.single{it.merchant=="翌々月の利用"}
            ui.onNodeWithTag("statement-source-${later.id}").assertDoesNotExist()
            tapTag("finance-statement-next")
            ui.onNodeWithTag("finance-statement-amount").assertTextEquals("¥3,020")
            ui.onNodeWithTag("statement-source-${later.id}").assertExists()
            ui.onNodeWithTag("finance-statement-total").performScrollTo();shot("$theme-$language-card-next-month")
            tapTag("statement-source-${event.id}")
            tapTag("sheet-close")
            ui.runOnIdle{vm.editEntry(saved,event)}
            ui.onNodeWithTag("select-trigger-"+text("平台当前付款方式","現在の支払方法")).assertExists()
            textField(text("分期次数","分割回数")).assertTextEquals("3")
            tapTag("entry-cancel-edit")
            val final=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertEquals(4,final.events.size);assertEquals(200L,final.balance(final.accounts.single{it.name=="引落銀行"}));assertEquals(5400L,final.monthExpense("2026-10"))
            assertNull(vm.error.value)
        }
    }
}
