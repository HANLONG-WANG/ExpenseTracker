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
class FinancialRuleModesFlowTest {
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
        val file=File(context.getExternalFilesDir(null),"phase-c-rule-modes/$name.png");file.parentFile!!.mkdirs()
        file.outputStream().use{ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
        if(name.endsWith("-ime")||name.endsWith("-history")) {
            val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(file.parentFile,"$name-screen.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
        }
    }
    /** Configure through the native form; fixtures only establish existing debt and a platform bill. */
    @Test fun configureAllModesAndFixedLoanMethodsWithoutChangingKnownFacts() {
        val args=InstrumentationRegistry.getArguments()
        val cases=listOf("SINGLE","INSTALLMENT","REVOLVING","CREDIT_LINE","EQUAL_PRINCIPAL","ANNUITY","INTEREST_ONLY","MANUAL","SPECIAL")
        for(theme in listOf("magazine","journal","objects").filter{args.getString("theme")?.let{v->v==it}?:true})
        for(language in listOf("zh","ja").filter{args.getString("language")?.let{v->v==it}?:true})
        for(case in cases.filter{args.getString("modeCase")?.let{v->v==it}?:true}) {
            fun text(zh:String,ja:String)=if(language=="ja")ja else zh
            val mode=when(case){"EQUAL_PRINCIPAL","ANNUITY","INTEREST_ONLY","MANUAL"->FinanceMode.FIXED_LOAN;"SPECIAL"->FinanceMode.CREDIT_LINE;else->FinanceMode.valueOf(case)}
            val (book,bank,account,bill)=runBlocking {
                val b=repo.createBook("$case $theme $language",Currency.JPY,theme,language,startDate="2026-10-01")
                repo.updateBook(repo.dao.book(b)!!.copy(initialized=true))
                val bank=repo.saveAccount(b,"支払口座",AccountType.BANK,Currency.JPY,Money(Currency.JPY,20000),"2026-10-01")
                val a=repo.saveAccount(b,"既存の債務",if(mode in listOf(FinanceMode.FIXED_LOAN,FinanceMode.CREDIT_LINE))AccountType.LOAN else AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,1000),"2026-10-01")
                val plan=repo.commitManualFinancialPlan(repo.previewManualFinancialPlan(b,ManualFinancialPlanInput(a,"既知請求",mode,listOf(KnownBillInput("2026-11-15",1000,null,null,bank)))))!!
                val known=db.financialDao().bills(b,plan).single()
                vm.preferences.select(b);listOf(b,bank,a,known.id)
            }
            val originalBill=runBlocking{db.financialDao().bill(book,bill)!!}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book&&!vm.busy.value}
            ui.runOnIdle{vm.navigate("accounts")}
            tapTag("account-$account");tapTag("account-financial");tapTag("analysis-tab-rules");tapTag("finance-edit-${mode.name}")
            fun field(key:String)=ui.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("finance-field-$key")))
            fun fill(key:String,value:String){field(key).performScrollTo().performTextReplacement(value)}
            fun choice(key:String,index:Int) {
                val trigger=ui.onNode(hasClickAction() and hasAnyAncestor(hasTestTag("finance-choice-$key")))
                trigger.performScrollTo()
                val tag=trigger.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.TestTag]
                tapTag(tag)
                tapTag("select-option-${tag.removePrefix("select-trigger-")}-$index")
            }
            if(mode in listOf(FinanceMode.SINGLE,FinanceMode.INSTALLMENT,FinanceMode.REVOLVING)) {
                fill("closing","31");fill("payday","15");choice("closingIncluded",1);fill("monthOffset","1")
                tapTag("select-trigger-"+text("扣款账户","引落口座"));ui.onNodeWithText("支払口座 · JPY").performScrollTo().performClick()
            }
            if(mode==FinanceMode.INSTALLMENT) {
                fill("periods","3");choice("feeMethod",InstallmentFeeMethod.TOTAL_AMOUNT.ordinal+1);fill("totalFee","30")
            }
            if(mode==FinanceMode.FIXED_LOAN)choice("loanMethod",LoanMethod.valueOf(case).ordinal+1)
            if(mode==FinanceMode.FIXED_LOAN)ui.onNodeWithText(text("计息截止日","計算終了日")).assertDoesNotExist()
            val interest=mode in listOf(FinanceMode.REVOLVING,FinanceMode.CREDIT_LINE)||mode==FinanceMode.FIXED_LOAN&&case!="MANUAL"
            if(interest) {
                fill("apr","12");choice("frequency",ChargeFrequency.MONTHLY.ordinal+1)
                if(mode in listOf(FinanceMode.REVOLVING,FinanceMode.CREDIT_LINE)) {
                    choice("paymentMethod",(if(mode==FinanceMode.REVOLVING)PaymentMethod.FIXED_PRINCIPAL else PaymentMethod.TOTAL_PAYMENT).ordinal+1)
                    fill("payment","200")
                    if(mode==FinanceMode.REVOLVING)choice("automatic",2)else ui.onAllNodesWithTag("finance-choice-automatic").assertCountEquals(0)
                }
            }
            tapTag("finance-advanced")
            if(interest) {
                choice("balance",BalanceBasis.OPENING_BALANCE.ordinal+1);choice("first",FirstCharge.NORMAL.ordinal+1);choice("roundAt",ChargeRounding.PERIOD_TOTAL.ordinal+1)
            }
            if(mode!=FinanceMode.SINGLE)choice("rounding",1)
            if(mode==FinanceMode.INSTALLMENT||case=="EQUAL_PRINCIPAL")choice("principalRemainder",RemainderPlacement.LAST.ordinal+1)
            if(mode==FinanceMode.INSTALLMENT)choice("feeRemainder",RemainderPlacement.FIRST.ordinal+1)
            if(mode in listOf(FinanceMode.REVOLVING,FinanceMode.CREDIT_LINE,FinanceMode.FIXED_LOAN))choice("allocation",AllocationOrder.FEES_FIRST.ordinal+1)
            ui.onAllNodesWithTag("finance-choice-recovery").assertCountEquals(0)
            if(case=="CREDIT_LINE")ui.onNodeWithTag("finance-rule-recovery-unset").assertExists()
            if(case=="SPECIAL")fill("unsupported",text("平台特殊算法，使用已知计划","返済先独自の計算・既知プランを使用"))
            fill("trialAmount","1000")
            if(mode in listOf(FinanceMode.INSTALLMENT,FinanceMode.FIXED_LOAN))fill("trialCount","3")
            if(mode!=FinanceMode.SINGLE) {
                val label=text("首次付款日","初回支払日")
                val trigger=ui.onNode(hasTestTag(when(mode){FinanceMode.INSTALLMENT->"date-field-0";FinanceMode.FIXED_LOAN->"date-field-1";else->"date-field-2"}) and hasText(label))
                trigger.performScrollTo().performClick()
                ui.onNodeWithContentDescription(text("下一月","次の月")).performScrollTo().performClick()
                ui.onNodeWithContentDescription("2026-11-15").performScrollTo().performClick()
            }
            if(args.getString("realIme")=="true") {
                val input=field("trialAmount");input.performScrollTo().performClick();input.performTextReplacement("1000")
                ui.waitUntil(10000){android.view.inspector.WindowInspector.getGlobalWindowViews().any{androidx.core.view.ViewCompat.getRootWindowInsets(it)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true}}
                if(case in listOf("ANNUITY","CREDIT_LINE"))shot("$theme-$language-$case-ime")
            }
            tapTag("finance-calculate")
            ui.waitUntil(10000){!vm.busy.value}
            assertNull("$case: ${vm.error.value}",vm.error.value)
            val principals=when(case){"SINGLE"->listOf(1000L);"INSTALLMENT","EQUAL_PRINCIPAL"->listOf(333L,333L,334L);"REVOLVING"->listOf(200L);"CREDIT_LINE"->listOf(190L);"ANNUITY"->listOf(330L,334L,336L);"INTEREST_ONLY"->listOf(0L,0L,1000L);else->emptyList()}
            val fees=when(case){"SINGLE"->listOf(0L);"INSTALLMENT","INTEREST_ONLY"->listOf(10L,10L,10L);"EQUAL_PRINCIPAL","ANNUITY"->listOf(10L,6L,3L);"REVOLVING","CREDIT_LINE"->listOf(10L);else->emptyList()}
            if(principals.isNotEmpty()) {
                ui.waitUntil(10000){ui.onAllNodesWithTag("finance-trial-period-${principals.size}").fetchSemanticsNodes().isNotEmpty()}
                for(i in principals.indices) {
                    val row=ui.onNodeWithTag("finance-trial-period-${i+1}")
                    row.assert(hasAnyDescendant(hasText(Money(Currency.JPY,principals[i]).display())))
                    row.assert(hasAnyDescendant(hasText(Money(Currency.JPY,fees[i]).display())))
                }
                ui.onNodeWithTag("finance-trial-period-1").performScrollTo();shot("$theme-$language-$case-calculation")
            }else {
                ui.onNode(hasText(if(case=="MANUAL")text("手动模式请填写平台逐期计划。","手動方式では返済先の各回の予定を入力してください。")else text("平台特殊算法，使用已知计划","返済先独自の計算・既知プランを使用")) and !hasSetTextAction()).assertExists()
                ui.onAllNodes(hasTestTag("finance-trial-period-1")).assertCountEquals(0)
            }
            if(case=="CREDIT_LINE") {
                // The calculation above succeeds while availability is still unknown.
                tapTag("finance-rule-open-limits");tapTag("limit-configure")
                ui.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("limit-total"))).performScrollTo().performTextReplacement("10000")
                ui.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("limit-used"))).performScrollTo().performTextReplacement("1000")
                for((label,option) in listOf(
                    text("占用计算依据","使用額の計算根拠") to text("只按本金预计占用","元金のみで使用額を予測"),
                    text("开始占用的时间","使用開始日") to text("从交易日占用","取引日から使用"),
                    text("本金归还后的额度恢复","元金返済後の枠回復") to text("逐笔确认额度恢复","枠回復を個別に確認"))) {
                    tapTag("select-trigger-$label");ui.onNodeWithText(option).performScrollTo().performClick()
                }
                tapTag("limit-preview");tapTag("limit-confirm")
                ui.waitUntil(10000){!vm.busy.value&&vm.creditLimitState.value?.configs?.count{it.accountId==account}==1}
                tapTag("finance-return-rule")
                field("apr").assertTextEquals("12");field("trialAmount").assertTextEquals("1000")
                assertNull(vm.financialRuleReview.value)
                if(ui.onAllNodesWithTag("finance-rule-recovery-current").fetchSemanticsNodes().isEmpty())tapTag("finance-advanced")
                ui.onNodeWithTag("finance-rule-recovery-current").assertTextEquals(text("逐笔确认额度恢复","枠回復を個別に確認"))
                ui.onNodeWithTag("finance-rule-recovery-current").performScrollTo();shot("$theme-$language-$case-shared-recovery")
            }
            tapTag("finance-rule-preview")
            ui.waitUntil(10000){vm.financialRuleReview.value!=null&&!vm.busy.value}
            val review=vm.financialRuleReview.value!!
            if(case=="SPECIAL")assertTrue(review.readiness is RuleReadiness.Unsupported)else assertEquals(RuleReadiness.Complete,review.readiness)
            assertEquals(mode,review.rule.mode);assertNull(review.rule.limitRecovery)
            assertEquals(listOf(originalBill),review.retainedConfirmedBills)
            shot("$theme-$language-$case-review")
            tapTag("finance-rule-confirm")
            ui.waitUntil(10000){vm.financialRuleReview.value==null&&!vm.busy.value}
            val saved=runBlocking{db.financialDao().rules(book,account).single()}
            val rule=repo.json.decodeFromString<FinancialRuleVersion>(saved.json)
            assertEquals(review.rule.copy(id=rule.id),rule)
            tapTag("finance-edit-${mode.name}")
            if(interest)field("apr").assertTextEquals("12")
            if(case=="SPECIAL"){tapTag("finance-advanced");field("unsupported").assertTextEquals(text("平台特殊算法，使用已知计划","返済先独自の計算・既知プランを使用"))}
            tapTag("finance-rule-cancel")
            runBlocking {
                val snapshot=repo.snapshot(repo.dao.book(book)!!).first()
                assertTrue(snapshot.events.isEmpty());assertTrue(snapshot.postings.isEmpty())
                assertEquals(20000L,snapshot.balance(snapshot.accounts.first{it.id==bank}))
                assertEquals(1000L,snapshot.balance(snapshot.accounts.first{it.id==account}))
                assertEquals(originalBill,db.financialDao().bill(book,bill))
                if(case=="CREDIT_LINE") {
                    val limits=db.creditLimitDao().graph(book).first()!!
                    assertEquals(1,limits.configs.count{it.accountId==account});assertEquals("MANUAL",limits.configs.single{it.accountId==account}.recovery)
                    assertEquals(9000L,accountCreditLimit(snapshot,db.financialDao().graph(book).first()!!,limits,account,"2026-10-15").effectiveTotalMinor)
                }
                assertNull(originalBill.principalMinor);assertNull(originalBill.feeMinor)
            }
        }
    }
}
