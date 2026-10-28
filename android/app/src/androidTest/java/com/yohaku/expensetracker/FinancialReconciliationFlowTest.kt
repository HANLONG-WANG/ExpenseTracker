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
class FinancialReconciliationFlowTest {
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
        val file=File(context.getExternalFilesDir(null),"phase-c-reconciliation/$name.png");file.parentFile!!.mkdirs()
        file.outputStream().use{ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
        if(name.endsWith("-ime")||name.endsWith("-history")) {
            val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(file.parentFile,"$name-screen.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
        }
    }
    @Test fun aCorrectedSourceCanBeReconciledAndItsAppliedHistoryRemainsInEveryThemeAndLanguage() {
        for((theme,language) in testLooks(this@FinancialReconciliationFlowTest)) {
            fun text(zh:String,ja:String)=if(language=="ja")ja else zh
            val refundCase=InstrumentationRegistry.getArguments().getString("refund")=="true"
            val merged=InstrumentationRegistry.getArguments().getString("merged")=="true"
            val poolSource=InstrumentationRegistry.getArguments().getString("poolSource")=="true"
            val unallocated=InstrumentationRegistry.getArguments().getString("unallocated")=="true"
            val retained=InstrumentationRegistry.getArguments().getString("retained")=="true"
            val waive=InstrumentationRegistry.getArguments().getString("waive")=="true"
            val futureFee=InstrumentationRegistry.getArguments().getString("futureFee")=="true"
            val ids=runBlocking {
                val b=repo.createBook("重核 $theme $language",Currency.JPY,theme,language,startDate="2026-10-01")
                repo.updateBook(repo.dao.book(b)!!.copy(initialized=true))
                val bank=repo.saveAccount(b,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
                val card=repo.saveAccount(b,"カード",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,0),"2026-10-01")
                val cat=repo.saveCategory(b,"利用",EntryKind.EXPENSE,null)
                val source=repo.saveEntry(b,EntryDraft(accountId=card,categoryId=cat,amount="1000",date="2026-10-01",cardPaymentChoice=if(poolSource)"REVOLVING"else null))
                val rows=if(refundCase)listOf(KnownBillInput("2026-10-10",210,200,10,bank),KnownBillInput("2026-11-05",800,800,0,bank))else listOf(KnownBillInput("2026-11-05",1000,1000,0,bank))
                val old=if(poolSource) {
                    repo.commitFinancialRule(repo.previewFinancialRule(b,card,FinancialRuleVersion("",b,card,FinanceMode.REVOLVING,"2026-10-01","Asia/Tokyo",BillingRule(31,true,1,5,bank),revolving=RevolvingRule(InterestRule("0",ChargeFrequency.DAILY,BalanceBasis.EVENT_BALANCE,true,true,365,ChargeRounding.PERIOD_TOTAL,"DOWN",FirstCharge.NORMAL),PaymentMethod.FIXED_PRINCIPAL,1000,allocation=AllocationOrder.FEES_FIRST,automatic=true))))
                    val pool=repo.commitFinancialPrediction(repo.previewFinancialPool(b,FinancialPredictionInput(card,"原账户池",FinanceMode.REVOLVING,FinancialScheduleTerms("2026-10-01",listOf("2026-11-05","2026-12-05"),listOf(AccrualWindow("2026-10-01","2026-10-31"),AccrualWindow("2026-11-01","2026-11-30"))),paymentAccountId=bank,currentArrangementConfirmed=true,pool=FinancialPoolInput(0,PoolSourceDate.TRANSACTION))))!!
                    val first=db.financialDao().bills(b,pool).first()
                    repo.commitFinancialBillConfirmation(repo.previewFinancialBillConfirmation(b,first.id,KnownBillInput(first.date,1000,1000,0,bank)))
                    repo.commitFinancialPrediction(repo.previewFinancialPool(b,repo.json.decodeFromString<FinancialPredictionInput>(db.financialDao().plans(b,card).first{it.id==pool}.termsJson!!)))
                    pool
                }else repo.commitManualFinancialPlan(repo.previewManualFinancialPlan(b,ManualFinancialPlanInput(card,"原安排",FinanceMode.SINGLE,rows,1000,complete=true,sourceEventId=source)))!!
                repo.commitFinancialRule(repo.previewFinancialRule(b,card,FinancialRuleVersion("",b,card,FinanceMode.INSTALLMENT,"2026-10-01","Asia/Tokyo",BillingRule(31,true,1,5,bank),installment=InstallmentRule(listOf(2),InstallmentFeeMethod.TOTAL_AMOUNT,totalFeeMinor=40,principalRemainder=RemainderPlacement.LAST,feeRemainder=RemainderPlacement.FIRST,roundingMode="DOWN"))))
                val pool=if(merged) {
                    repo.commitFinancialRule(repo.previewFinancialRule(b,card,FinancialRuleVersion("",b,card,FinanceMode.REVOLVING,"2026-10-01","Asia/Tokyo",BillingRule(31,true,1,5,bank),revolving=RevolvingRule(InterestRule("0",ChargeFrequency.DAILY,BalanceBasis.EVENT_BALANCE,true,true,365,ChargeRounding.PERIOD_TOTAL,"DOWN",FirstCharge.NORMAL),PaymentMethod.TOTAL_PAYMENT,600,allocation=AllocationOrder.FEES_FIRST,automatic=true))))
                    repo.commitFinancialPrediction(repo.previewFinancialPool(b,FinancialPredictionInput(card,"共同付款池",FinanceMode.REVOLVING,
                        FinancialScheduleTerms("2026-10-01",listOf("2026-11-05","2026-12-05","2027-01-05"),listOf(AccrualWindow("2026-10-01","2026-10-31"),AccrualWindow("2026-11-01","2026-11-30"),AccrualWindow("2026-12-01","2026-12-31"))),paymentAccountId=bank,currentArrangementConfirmed=true,pool=FinancialPoolInput(0,PoolSourceDate.TRANSACTION))))!!
                }else null
                if(retained)repo.commitFinancialRule(repo.previewFinancialRule(b,card,FinancialRuleVersion("",b,card,FinanceMode.REVOLVING,"2026-10-01","Asia/Tokyo",BillingRule(31,true,1,5,bank),revolving=RevolvingRule(InterestRule("0",ChargeFrequency.DAILY,BalanceBasis.EVENT_BALANCE,true,true,365,ChargeRounding.PERIOD_TOTAL,"DOWN",FirstCharge.NORMAL),PaymentMethod.TOTAL_PAYMENT,600,allocation=AllocationOrder.FEES_FIRST,automatic=true))))
                val target=repo.commitFinancialArrangement(repo.previewFinancialArrangement(b,FinancialArrangementInput(old,"已办理两期",if(merged||retained)FinanceMode.REVOLVING else FinanceMode.INSTALLMENT,FinancialScheduleTerms("2026-10-25",listOf("2026-11-05","2026-12-05"),if(retained)listOf(AccrualWindow("2026-10-25","2026-10-31"),AccrualWindow("2026-11-01","2026-11-30"))else emptyList()),bank,OriginalFeeTreatment.RETAIN,if(retained)100 else 0,mergePoolId=pool,poolSelection=if(poolSource)PoolArrangementSelection(listOf(PoolBillTransfer(db.financialDao().bills(b,old).first{it.confirmed}.id,500)))else null)),repo.clock.instant().toString(),true)!!
                if(retained&&!futureFee) {
                    val first=db.financialDao().bills(b,target).first()
                    repo.commitFinancialBillConfirmation(repo.previewFinancialBillConfirmation(b,first.id,KnownBillInput(first.date,600,500,100,bank)))
                }
                val clearing=if(unallocated)repo.commitFinancialLedger(repo.previewRepayment(b,FinancialRepaymentInput(bank,card,Money(Currency.JPY,100),"2026-10-20",Money(Currency.JPY,100),Money(Currency.JPY,100),Money(Currency.JPY,0),Money(Currency.JPY,0),platformConfirmed=true,clearingDate="2026-10-20")))!!else ""
                val refund=if(refundCase&&!retained)repo.saveRefund(b,RefundInput(source,Money(Currency.JPY,100),card,Money(Currency.JPY,100),Money(Currency.JPY,100),"2026-10-27",true))else if(!retained) {
                    repo.saveEntry(b,EntryDraft(id=source,accountId=card,categoryId=cat,amount="1200",date="2026-10-02",cardPaymentChoice=if(poolSource)"REVOLVING"else null));""
                }else ""
                vm.preferences.select(b);listOf(b,bank,card,old,target,refund,clearing,source,cat)
            }
            val book=ids[0];val bank=ids[1];val card=ids[2];val old=ids[3];val target=ids[4];var refund=ids[5]
            var before=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            val original=runBlocking{db.financialDao().bills(book,old)}
            val applied=runBlocking{db.financialDao().graph(book).first()!!.arrangements.single()}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book&&!vm.busy.value}
            ui.runOnIdle{vm.navigate("accounts")};tapTag("account-$card");tapTag("account-financial");tapTag("analysis-tab-plans")
            if(retained) {
                tapTag("finance-pool-open")
                textField(text("期初属于循环的本金 / 没有请填0","循環分の期首元金・なければ0")).performScrollTo().performTextReplacement("0")
                tapTag("select-trigger-"+text("新增本金起算日期","追加元金の開始日"));ui.onNodeWithText(text("交易 / 借入发生日","取引・借入日")).performClick()
                textField(text("预测期数","予測回数")).performScrollTo().performTextReplacement("3")
                tapTag("date-field-1");ui.onNodeWithContentDescription("2026-10-31").performScrollTo().performClick()
                tapTag("date-field-2");ui.onNodeWithContentDescription(text("下一月","次の月")).performScrollTo().performClick();ui.onNodeWithContentDescription("2026-11-05").performScrollTo().performClick()
                tapTag("pool-generate");tapTag("pool-adopt")
                if(futureFee) {
                    val label=text("办理费共同付款日","手数料の共同支払日")
                    tapTag("select-trigger-$label","pool-adopted-fee-${applied.id}");tapTag("select-option-$label-2")
                }else {
                    tapTag("pool-preview")
                    ui.onNodeWithTag("pool-confirm").assertIsNotEnabled();shot("$theme-$language-reconciliation-adoption-needs-inclusion")
                    tapTag("pool-back")
                    val first=runBlocking{db.financialDao().bills(book,target).first{it.number==1}}
                    tapTag("pool-adopted-fee-bill-${first.id}")
                    if(waive) {
                        ui.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("finance-actual-fee"))).performScrollTo().performTextReplacement("0")
                        ui.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("finance-actual-total"))).performScrollTo().performTextReplacement("500")
                        tapTag("finance-bill-preview");tapTag("finance-bill-confirm")
                    }else tapTag("sheet-close")
                    textField(text("预测期数","予測回数")).assertTextContains("3")
                    if(waive)ui.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("pool-adopted-fee-waived-${applied.id}"))).performScrollTo().performTextReplacement("100")
                    else tapTag("pool-adopted-fee-included-${applied.id}")
                }
                ui.onNodeWithTag("pool-adopted-fee-${applied.id}").performScrollTo();shot("$theme-$language-reconciliation-adoption-form")
                tapTag("pool-preview");shot("$theme-$language-reconciliation-adoption")
                tapTag("pool-confirm")
                ui.waitUntil(10000){!vm.busy.value&&vm.financialState.value?.plans?.firstOrNull{it.id==target}?.origin=="POOL_RETAINED"}
                runBlocking {
                    if(refundCase)refund=repo.saveRefund(book,RefundInput(ids[7],Money(Currency.JPY,100),card,Money(Currency.JPY,100),Money(Currency.JPY,100),"2026-10-27",true))
                    else repo.saveEntry(book,EntryDraft(id=ids[7],accountId=card,categoryId=ids[8],amount="1200",date="2026-10-02",cardPaymentChoice="SINGLE"))
                    before=repo.snapshot(repo.dao.book(book)!!).first()
                }
                ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.visibleEvents==before.visibleEvents}
            }
            tapTag("finance-reconcile-$target")
            fun replace(tag:String,value:String){ui.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(tag))).performScrollTo().performTextReplacement(value)}
            val transferred=if(poolSource)if(unallocated)"600"else if(refundCase)"500"else"700" else if(refundCase)"800"else"1200"
            replace("reconciliation-principal",transferred)
            if(unallocated)replace("reconciliation-pool-share-${ids[6]}","100")
            if(refundCase) {
                val past=if(poolSource)original.first{it.confirmed}else original.first{it.date=="2026-10-10"}
                replace("reconciliation-bill-principal-${past.id}",if(poolSource)"450"else"150")
                replace("reconciliation-refund-target-$refund","50")
                replace("reconciliation-refund-bill-$refund-${past.id}","50")
                tapTag("reconciliation-open-bill-${past.id}");tapTag("sheet-close")
                textField(text("生效日转入的本金 / 非今日剩余","適用日に移す元金・今日の残額ではありません")+" · JPY","reconciliation-principal").assertTextContains(transferred)
            }
            if(InstrumentationRegistry.getArguments().getString("realIme")=="true") {
                tapTag("field-"+text("生效日转入的本金 / 非今日剩余","適用日に移す元金・今日の残額ではありません")+" · JPY","reconciliation-principal")
                ui.waitUntil(10000){android.view.inspector.WindowInspector.getGlobalWindowViews().any{androidx.core.view.ViewCompat.getRootWindowInsets(it)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true}}
                shot("$theme-$language-reconciliation-ime")
            }
            tapTag("reconciliation-preview")
            ui.waitUntil(10000){!vm.busy.value&&ui.onAllNodesWithTag("reconciliation-confirmed").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithTag("reconciliation-review").performScrollTo();shot("$theme-$language-reconciliation-review")
            if(merged||poolSource||retained) {
                ui.onNodeWithText(text("原池全部共同付款","プール全体の支払"),substring=true).performScrollTo()
                shot("$theme-$language-reconciliation-shared")
            }
            tapTag("reconciliation-confirmed");tapTag("reconciliation-save")
            ui.waitUntil(10000){!vm.busy.value&&vm.financialState.value?.plans?.firstOrNull{it.id==target}?.reconciliationJson!=null}
            val after=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()};val f=runBlocking{db.financialDao().graph(book).first()!!}
            val plan=f.plans.first{it.id==target}
            assertEquals(before.storedPostings,after.storedPostings);assertEquals(before.expenseComponents,after.expenseComponents);assertEquals(before.visibleEvents,after.visibleEvents)
            assertEquals(applied,f.arrangements.single());assertEquals(if(poolSource)original.filter{it.confirmed}else original,f.bills.filter{it.planId==old&&(!poolSource||it.confirmed)})
            assertFalse(repo.financialPredictionStale(after,f,plan))
            assertEquals(if(poolSource)if(unallocated)600L else if(refundCase)450L else 700L else if(refundCase)750L else 1200L,plan.principalMinor)
            if(merged||retained) {
                if(merged){assertFalse(plan.active);assertTrue(f.bills.none{it.planId==target})}else assertEquals(!futureFee,plan.active)
                val pool=f.reconciliationPool(plan)!!
                val common=f.bills.filter{it.active&&(it.planId==pool.id||retained&&it.planId==target)}
                assertEquals(if(refundCase)750L else 1200L,common.sumOf{after.billOutstanding(it).principalMinor!!})
                if(retained) {
                    assertEquals(if(waive)0L else 100L,common.sumOf{after.billOutstanding(it).feeMinor!!})
                    assertEquals(listOf(PoolAdoptedFee(applied.id,if(futureFee)"2026-12-05"else"2026-11-05",!waive&&!futureFee,if(waive)100 else 0)),poolInput(pool)!!.adoptedFees)
                }
                assertFalse(repo.financialPredictionStale(after,f,pool))
            }else if(poolSource) {
                assertEquals(if(refundCase)450L else 500L,f.bills.filter{it.planId==old&&it.active}.sumOf{after.billOutstanding(it).principalMinor!!})
                assertEquals(if(unallocated)listOf(300L,300L)else if(refundCase)listOf(250L,200L)else listOf(350L,350L),f.bills.filter{it.planId==target&&it.active}.map{it.principalMinor})
                assertFalse(repo.financialPredictionStale(after,f,f.plans.first{it.id==old}))
            }else assertEquals(if(refundCase)listOf(400L,350L)else listOf(600L,600L),f.bills.filter{it.planId==target&&it.active}.map{it.principalMinor})
            assertEquals((if(refundCase)900L else 1200L)-(if(unallocated)100L else 0L),after.balance(after.accounts.first{it.id==card}));assertEquals(if(unallocated)9900L else 10000L,after.balance(after.accounts.first{it.id==bank}))
            assertEquals((if(refundCase)2 else 1)+(if(unallocated)1 else 0),after.visibleEvents.size)
            assertNull(runBlocking{db.financialDao().draft(book,FinancialDraftContext("ARRANGEMENT_REVIEW",card,targetId=target).slot())})
            tapTag(if(merged||futureFee)"finance-arrangement-history-${applied.id}"else"finance-plan-history-$target");tapTag("finance-plan-version-open-${plan.revision}")
            ui.onNodeWithTag("reconciliation-history").performScrollTo();shot("$theme-$language-reconciliation-history");tapTag("sheet-close")
            assertNull(vm.error.value)
        }
    }
}
