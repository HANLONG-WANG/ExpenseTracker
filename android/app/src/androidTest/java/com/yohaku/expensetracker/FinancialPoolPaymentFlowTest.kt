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
class FinancialPoolPaymentFlowTest {
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
        val file=File(context.getExternalFilesDir(null),"phase-c-pool-payment/$name.png");file.parentFile!!.mkdirs()
        file.outputStream().use{ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
        if(name.endsWith("-ime")||name.endsWith("-history")) {
            val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(file.parentFile,"$name-screen.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
        }
    }
    @Test fun aMonthlyPaymentChangeKeepsOnePoolAndCanBeRecheckedInEveryThemeAndLanguage() {
        for(theme in listOf("magazine","journal","objects").filter{InstrumentationRegistry.getArguments().getString("theme")?.let{v->v==it}?:true})for(language in listOf("zh","ja").filter{InstrumentationRegistry.getArguments().getString("language")?.let{v->v==it}?:true}) {
            fun text(zh:String,ja:String)=if(language=="ja")ja else zh
            val loan=InstrumentationRegistry.getArguments().getString("loan")=="true"
            val course=InstrumentationRegistry.getArguments().getString("tiers")=="true"
            val ids=runBlocking {
                val b=repo.createBook("月付款 $theme $language",Currency.JPY,theme,language,startDate="2026-10-01")
                repo.updateBook(repo.dao.book(b)!!.copy(initialized=true))
                val bank=repo.saveAccount(b,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
                val card=repo.saveAccount(b,"借入・カード",if(loan)AccountType.LOAN else AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,3000),"2026-10-01")
                val mode=if(loan)FinanceMode.CREDIT_LINE else FinanceMode.REVOLVING
                if(loan)repo.commitFinancialLedger(repo.previewBorrowing(b,FinancialBorrowingInput(card,"期初循环",mode,Money(Currency.JPY,3000),"2026-10-01",opening=true)))
                repo.commitFinancialRule(repo.previewFinancialRule(b,card,FinancialRuleVersion("",b,card,mode,"2026-10-01","Asia/Tokyo",BillingRule(31,true,1,5,bank),revolving=RevolvingRule(InterestRule("0",ChargeFrequency.DAILY,BalanceBasis.EVENT_BALANCE,true,true,365,ChargeRounding.PERIOD_TOTAL,"DOWN",FirstCharge.NORMAL),PaymentMethod.FIXED_PRINCIPAL,1000,allocation=AllocationOrder.FEES_FIRST,automatic=true),limitRecovery=if(loan)LimitRecovery.PAYMENT_DATE else null)))
                val dates=listOf("2026-11-05","2026-12-05","2027-01-05","2027-02-05","2027-03-05")
                val windows=(0..4).map{n->val d=LocalDate.parse("2026-10-01").plusMonths(n.toLong());AccrualWindow(d.toString(),d.withDayOfMonth(d.lengthOfMonth()).toString())}
                val pool=repo.commitFinancialPrediction(repo.previewFinancialPool(b,FinancialPredictionInput(card,"原共同池",mode,FinancialScheduleTerms("2026-10-01",dates,windows),paymentAccountId=bank,currentArrangementConfirmed=true,pool=FinancialPoolInput(3000,PoolSourceDate.TRANSACTION))))!!
                val first=db.financialDao().bills(b,pool).first()
                repo.commitFinancialBillConfirmation(repo.previewFinancialBillConfirmation(b,first.id,KnownBillInput(first.date,1000,1000,0,bank)))
                val saved=repo.json.decodeFromString<FinancialPredictionInput>(db.financialDao().graph(b).first()!!.plans.first{it.id==pool}.termsJson!!).copy(planId=pool)
                repo.commitFinancialPrediction(repo.previewFinancialPool(b,saved))
                vm.preferences.select(b);listOf(b,bank,card,pool,first.id)
            }
            val book=ids[0];val bank=ids[1];val card=ids[2];val pool=ids[3]
            val original=runBlocking{db.financialDao().bill(book,ids[4])!!}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book&&!vm.busy.value}
            ui.runOnIdle{vm.navigate("accounts")};tapTag("account-$card");tapTag("account-financial");tapTag("analysis-tab-plans");tapTag("finance-pool-payment-$pool")
            tapTag("select-trigger-"+text("从哪次付款起采用","適用する最初の支払日"))
            ui.onNode(hasText("2026-12-05") and hasAnyAncestor(hasTestTag("select-menu-frame"))).performClick()
            tapTag("select-trigger-"+text("新的月付款方式","新しい月支払方式"))
            ui.onNode(hasText(if(course)text("余额阶梯","残高別コース")else text("含费用总额","手数料込み総額")) and hasAnyAncestor(hasTestTag("select-menu-frame"))).performClick()
            fun replace(tag:String,value:String){ui.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(tag))).performScrollTo().performTextReplacement(value)}
            if(course) {
                tapTag("select-trigger-"+text("阶梯金额包含费用吗","残高別の支払額は手数料込みか"))
                ui.onNode(hasText(text("含费用总金额","手数料込みの総額")) and hasAnyAncestor(hasTestTag("select-menu-frame"))).performClick()
                tapTag("select-trigger-"+text("阶梯余额判定日","残高の判定日"))
                ui.onNode(hasText(text("付款日还本前","支払日の元金返済前")) and hasAnyAncestor(hasTestTag("select-menu-frame"))).performClick()
                tapTag("pool-payment-add-tier");replace("pool-payment-tier-bound-0","1000");replace("pool-payment-tier-amount-0","400")
                tapTag("pool-payment-add-tier");replace("pool-payment-tier-amount-1","800")
            }else replace("pool-payment-amount","600")
            replace("pool-payment-fee","100")
            tapTag("select-trigger-"+text("办理费付款日 / 无费用可不选","手数料支払日・無料は未選択"))
            ui.onNode(hasText("2026-12-05") and hasAnyAncestor(hasTestTag("select-menu-frame"))).performClick()
            if(loan) {
                tapTag("pool-payment-review-original");ui.onNodeWithTag("pool-opening").assertExists();tapTag("sheet-close")
                textField(text("办理费 / 未知留空，无费用填0","変更手数料・不明は空欄、無料は0")+" · JPY","pool-payment-fee").assertTextContains("100")
            }
            if(InstrumentationRegistry.getArguments().getString("realIme")=="true") {
                tapTag("field-"+text("办理费 / 未知留空，无费用填0","変更手数料・不明は空欄、無料は0")+" · JPY","pool-payment-fee")
                ui.waitUntil(10000){android.view.inspector.WindowInspector.getGlobalWindowViews().any{androidx.core.view.ViewCompat.getRootWindowInsets(it)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true}}
                shot("$theme-$language-payment-ime")
            }
            if(loan) {
                ui.onNodeWithTag("select-trigger-"+text("办理费付款日 / 无费用可不选","手数料支払日・無料は未選択")).performScrollTo()
                shot("$theme-$language-payment-fee-date")
            }
            tapTag("pool-payment-preview")
            ui.waitUntil(10000){!vm.busy.value&&ui.onAllNodesWithTag("pool-payment-record").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithTag("pool-payment-comparison").performScrollTo();shot("$theme-$language-payment-review")
            tapTag("pool-payment-record");tapTag("pool-payment-platform-confirmed");tapTag("pool-payment-confirm")
            ui.waitUntil(10000){!vm.busy.value&&vm.financialState.value?.plans?.firstOrNull{it.id==pool}?.termsJson?.contains("paymentChanges")==true}
            val f=runBlocking{db.financialDao().graph(book).first()!!};val s=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            val plan=f.plans.single();val saved=repo.json.decodeFromString<FinancialPredictionInput>(plan.termsJson!!)
            assertEquals(pool,plan.id);assertEquals(1,saved.pool!!.paymentChanges.size);assertEquals(1,f.rules.size);assertTrue(f.arrangements.isEmpty());assertTrue(f.billAdjustments.isEmpty())
            assertEquals(original,f.bills.first{it.id==original.id})
            assertEquals(if(course)listOf(1000L,800L,800L,400L,100L)else listOf(1000L,600L,600L,600L,300L),f.bills.filter{it.active}.sortedBy{it.date}.map{it.totalMinor})
            assertEquals(100L,f.bills.filter{it.active}.sumOf{it.feeMinor!!})
            assertEquals(10000L,s.balance(s.accounts.first{it.id==bank}));assertEquals(3000L,s.balance(s.accounts.first{it.id==card}));assertTrue(s.events.isEmpty());assertEquals(0L,s.monthExpense("2026-10"))
            assertNull(runBlocking{db.financialDao().draft(book,FinancialDraftContext("POOL_PAYMENT",card,targetId=pool).slot())})
            if(loan) {
                val bill=f.bills.first{it.active&&it.date=="2026-12-05"}
                tapTag("prediction-basis-${bill.id}")
                ui.onNodeWithTag("pool-payment-basis-${bill.id}").performScrollTo();shot("$theme-$language-payment-basis")
                ui.onNodeWithTag("prediction-additional-fee-${bill.id}").assertExists()
                ui.onNodeWithTag("prediction-additional-fee-${bill.id}").performScrollTo();shot("$theme-$language-payment-charge")
            }
            tapTag("finance-plan-history-$pool");tapTag("finance-plan-version-open-${plan.revision}")
            ui.onNodeWithTag("pool-payment-history-summary").performScrollTo();shot("$theme-$language-payment-history");tapTag("sheet-close")
            if(loan)runBlocking {
                val bill=f.bills.first{it.active&&it.date=="2026-12-05"}
                repo.commitFinancialLedger(repo.previewRepayment(book,FinancialRepaymentInput(bank,card,Money(Currency.JPY,200),"2026-12-02",Money(Currency.JPY,200),Money(Currency.JPY,100),Money(Currency.JPY,0),Money(Currency.JPY,100),platformConfirmed=true,clearingDate="2026-12-02",allocations=listOf(RepaymentAllocationInput(bill.id,f.contracts.single().id,100,0,100)))))
            }
            // A native recheck preserves the applied payment setting and its fee coverage evidence.
            tapTag("finance-pool-open");tapTag("pool-preview");tapTag("pool-confirm")
            ui.waitUntil(10000){!vm.busy.value&&ui.onAllNodesWithTag("pool-confirm").fetchSemanticsNodes().isEmpty()}
            val after=runBlocking{db.financialDao().graph(book).first()!!}
            assertEquals(saved.pool.paymentChanges,repo.json.decodeFromString<FinancialPredictionInput>(after.plans.single().termsJson!!).pool!!.paymentChanges)
            assertEquals(100L,after.bills.filter{it.active}.sumOf{it.feeMinor!!});assertNull(vm.error.value)
            if(loan)runBlocking {
                val actual=repo.snapshot(repo.dao.book(book)!!).first()
                assertEquals(9800L,actual.balance(actual.accounts.first{it.id==bank}));assertEquals(2900L,actual.balance(actual.accounts.first{it.id==card}))
                assertEquals(100L,actual.monthExpense("2026-12"));assertEquals(1,actual.events.size)
            }
        }
    }
}
