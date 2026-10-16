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
class FinancialPoolMergeFlowTest {
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
        val file=File(context.getExternalFilesDir(null),"phase-c-pool-merge/$name.png");file.parentFile!!.mkdirs()
        file.outputStream().use{ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
        if(name.endsWith("-ime")||name.endsWith("-history")) {
            val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(file.parentFile,"$name-screen.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
        }
    }
    @Test fun anExistingPoolOwnsTheMergedPrincipalFeeAndHistoryInEveryThemeAndLanguage() {
        for(theme in listOf("magazine","journal","objects").filter{InstrumentationRegistry.getArguments().getString("theme")?.let{v->v==it}?:true})for(language in listOf("zh","ja").filter{InstrumentationRegistry.getArguments().getString("language")?.let{v->v==it}?:true}) {
            fun text(zh:String,ja:String)=if(language=="ja")ja else zh
            val loan=InstrumentationRegistry.getArguments().getString("loan")=="true"
            val ids=runBlocking {
                val b=repo.createBook("并入 $theme $language",Currency.JPY,theme,language,startDate="2026-10-01")
                repo.updateBook(repo.dao.book(b)!!.copy(initialized=true))
                val bank=repo.saveAccount(b,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
                val debt=repo.saveAccount(b,"借入・カード",if(loan)AccountType.LOAN else AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,3000),"2026-10-01")
                val source=repo.commitManualFinancialPlan(repo.previewManualFinancialPlan(b,ManualFinancialPlanInput(debt,"原一笔",if(loan)FinanceMode.FIXED_LOAN else FinanceMode.SINGLE,listOf(KnownBillInput("2026-11-05",1000,1000,0,bank)),1000,complete=true,opening=true)))!!
                if(loan) {
                    repo.commitFinancialLedger(repo.previewBorrowing(b,FinancialBorrowingInput(debt,"期初固定",FinanceMode.FIXED_LOAN,Money(Currency.JPY,1000),"2026-10-01",opening=true)))
                    val c=db.financialDao().graph(b).first()!!.contracts.single()
                    repo.linkFinancialContractPlan(b,c.id,source)
                    repo.commitFinancialLedger(repo.previewBorrowing(b,FinancialBorrowingInput(debt,"期初循环",FinanceMode.CREDIT_LINE,Money(Currency.JPY,2000),"2026-10-01",opening=true)))
                }
                val mode=if(loan)FinanceMode.CREDIT_LINE else FinanceMode.REVOLVING
                repo.commitFinancialRule(repo.previewFinancialRule(b,debt,FinancialRuleVersion("",b,debt,mode,"2026-10-01","Asia/Tokyo",BillingRule(31,true,1,5,bank),revolving=RevolvingRule(InterestRule("0",ChargeFrequency.DAILY,BalanceBasis.EVENT_BALANCE,true,true,365,ChargeRounding.PERIOD_TOTAL,"DOWN",FirstCharge.NORMAL),PaymentMethod.TOTAL_PAYMENT,1000,allocation=AllocationOrder.FEES_FIRST,automatic=true),limitRecovery=if(loan)LimitRecovery.PAYMENT_DATE else null)))
                val dates=listOf("2026-11-05","2026-12-05","2027-01-05","2027-02-05")
                val windows=listOf(AccrualWindow("2026-10-01","2026-10-31"),AccrualWindow("2026-11-01","2026-11-30"),AccrualWindow("2026-12-01","2026-12-31"),AccrualWindow("2027-01-01","2027-01-31"))
                val pool=repo.commitFinancialPrediction(repo.previewFinancialPool(b,FinancialPredictionInput(debt,"原共同池",mode,FinancialScheduleTerms("2026-10-01",dates,windows),paymentAccountId=bank,currentArrangementConfirmed=true,pool=FinancialPoolInput(2000,PoolSourceDate.TRANSACTION))))!!
                vm.preferences.select(b);listOf(b,bank,debt,source,pool)
            }
            val book=ids[0];val bank=ids[1];val debt=ids[2];val source=ids[3];val pool=ids[4]
            val original=runBlocking{db.financialDao().bills(book,source).single()}
            val before=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book&&!vm.busy.value}
            ui.runOnIdle{vm.navigate("accounts")};tapTag("account-$debt");tapTag("account-financial");tapTag("analysis-tab-plans");tapTag("finance-compare-plan-$source")
            tapTag("select-trigger-"+text("新付款方式","新しい支払方式"))
            val modeText=if(loan)text("循环贷款","利用枠内の借入")else"リボ"
            ui.onNode(hasText(modeText) and hasAnyAncestor(hasTestTag("select-menu-frame"))).performClick()
            tapTag("arrangement-merge-pool")
            tapTag("select-trigger-"+text("办理费计入的付款日 / 费用0时可不选","変更手数料を含む支払日・無料は未選択可"))
            ui.onNode(hasText("2026-11-05") and hasAnyAncestor(hasTestTag("select-menu-frame"))).performClick()
            tapTag("select-trigger-"+text("原方案剩余费用如何处理","元の予定の残手数料の扱い"))
            ui.onNode(hasText(text("平台取消原费用","返済先が元の手数料を取消")) and hasAnyAncestor(hasTestTag("select-menu-frame"))).performClick()
            val feeLabel=text("另列变更费用 / 未知留空，已知无费用填0","別途変更手数料・不明は空欄、無料は0")+" · JPY"
            textField(feeLabel).performScrollTo().performTextReplacement("100")
            if(loan) {
                tapTag("arrangement-merge-open-pool")
                ui.onNodeWithTag("pool-opening").assertExists()
                tapTag("sheet-close")
                ui.waitUntil(10000){ui.onAllNodesWithTag("arrangement-preview").fetchSemanticsNodes().isNotEmpty()}
                textField(feeLabel).assertTextContains("100")
            }
            if(InstrumentationRegistry.getArguments().getString("realIme")=="true") {
                tapTag("field-$feeLabel")
                ui.waitUntil(10000){android.view.inspector.WindowInspector.getGlobalWindowViews().any{androidx.core.view.ViewCompat.getRootWindowInsets(it)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true}}
                shot("$theme-$language-merge-ime")
            }
            if(loan)textField(text("循环额度占用变动 / 未知留空，增加为正，释放为负","循環枠の利用額変動・不明は空欄、増加は正、解放は負")+" · JPY").performScrollTo().performTextReplacement("1000")
            tapTag("arrangement-preview")
            ui.waitUntil(10000){!vm.busy.value&&vm.financialArrangementReview.value?.calculation is Calculation.Ready}
            val review=vm.financialArrangementReview.value!!
            val first=review.mergedPool!!.bills.first{it.date=="2026-11-05"}
            assertEquals(900L,first.principalMinor);assertEquals(100L,first.feeMinor);assertEquals(100L,review.comparison!!.totalExtraFeeMinor)
            ui.onNodeWithTag("pool-merge-review").performScrollTo();shot("$theme-$language-merge-review")
            tapTag("arrangement-record-applied");tapTag("arrangement-platform-confirmed");tapTag("arrangement-confirm")
            ui.waitUntil(10000){!vm.busy.value&&vm.financialState.value?.arrangements?.any{it.originalPlanId==source}==true}
            val after=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()};val f=runBlocking{db.financialDao().graph(book).first()!!}
            assertEquals(before.storedPostings,after.storedPostings);assertEquals(before.expenseComponents,after.expenseComponents);assertTrue(after.events.isEmpty())
            assertEquals(3000L,after.balance(after.accounts.first{it.id==debt}));assertEquals(10000L,after.balance(after.accounts.first{it.id==bank}))
            assertEquals(original,f.bills.first{it.id==original.id});assertEquals(1,f.plans.count{it.active&&it.origin=="POOL"});assertTrue(f.plans.none{it.active&&it.origin=="POOL_MEMBER"})
            assertEquals(100L,f.bills.filter{it.active&&it.planId==pool}.sumOf{it.feeMinor!!})
            assertNull(runBlocking{db.financialDao().draft(book,FinancialDraftContext("ARRANGEMENT",debt,targetId=source).slot())})
            val change=f.arrangements.single()
            tapTag("finance-arrangement-history-${change.id}");tapTag("finance-plan-version-open-1")
            ui.onNodeWithTag("pool-merge-history").performScrollTo();shot("$theme-$language-merge-history");tapTag("sheet-close")
            assertNull(vm.error.value)
        }
    }
}
