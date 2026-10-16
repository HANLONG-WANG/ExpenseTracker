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
class FinancialPoolFlowTest {
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
        try{ui.waitUntil(10000){!vm.busy.value&&ui.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()}}
        catch(failure:ComposeTimeoutException){
            shot("timeout-$tag")
            File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"phase-c-pool/timeout.txt").writeText(ui.onAllNodes(isRoot(),useUnmergedTree=true).printToString())
            File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"phase-c-pool/threads.txt").writeText(Thread.getAllStackTraces().entries.joinToString("\n\n"){(thread,stack)->thread.name+"\n"+stack.joinToString("\n")})
            throw failure
        }
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
        val file=File(context.getExternalFilesDir(null),"phase-c-pool/$name.png");file.parentFile!!.mkdirs()
        file.outputStream().use{ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
        if(name.endsWith("-ime")||name.endsWith("-history")) {
            val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(file.parentFile,"$name-screen.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
        }
    }
    @Test fun pooledAccountAdoptionPreviewAndNewSpendingKeepOneCourseInEveryThemeAndLanguage() {
        for(theme in listOf("magazine","journal","objects").filter{InstrumentationRegistry.getArguments().getString("theme")?.let{v->v==it}?:true})for(language in listOf("zh","ja").filter{InstrumentationRegistry.getArguments().getString("language")?.let{v->v==it}?:true}) {
            val loan=InstrumentationRegistry.getArguments().getString("loan")=="true"
            fun text(zh:String,ja:String)=if(language=="ja")ja else zh
            val (book,card,source)=runBlocking {
                val b=repo.createBook("残高プール $theme $language",Currency.JPY,theme,language,startDate="2026-10-01")
                repo.updateBook(repo.dao.book(b)!!.copy(initialized=true))
                val bank=repo.saveAccount(b,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,200000),"2026-10-01")
                val a=repo.saveAccount(b,if(loan)"循環ローン"else"循環カード",if(loan)AccountType.LOAN else AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,if(loan)0 else 100000),"2026-10-01")
                val cat=repo.saveCategory(b,"買物",EntryKind.EXPENSE,null)
                repo.commitFinancialRule(repo.previewFinancialRule(b,a,FinancialRuleVersion("",b,a,if(loan)FinanceMode.CREDIT_LINE else FinanceMode.REVOLVING,if(loan)"2026-10-15"else"2026-10-01","Asia/Tokyo",if(loan)null else BillingRule(31,true,1,5,bank),revolving=RevolvingRule(InterestRule("0.18",ChargeFrequency.DAILY,BalanceBasis.EVENT_BALANCE,true,true,365,ChargeRounding.PERIOD_TOTAL,"HALF_UP",FirstCharge.NORMAL),PaymentMethod.FIXED_PRINCIPAL,if(loan)1000 else 10000,allocation=AllocationOrder.FEES_FIRST,automatic=true),limitRecovery=if(loan)LimitRecovery.PAYMENT_DATE else null)))
                val e=if(loan)repo.commitFinancialLedger(repo.previewBorrowing(b,FinancialBorrowingInput(a,"循环1",FinanceMode.CREDIT_LINE,Money(Currency.JPY,3000),"2026-10-15",bank,Money(Currency.JPY,3000))))!!else repo.saveEntry(b,EntryDraft(accountId=a,categoryId=cat,amount="50000",date="2026-10-16",merchant="買物1",cardPaymentChoice="REVOLVING"))
                if(!loan)repo.commitManualFinancialPlan(repo.previewManualFinancialPlan(b,ManualFinancialPlanInput(a,"期初の既知請求",FinanceMode.REVOLVING,listOf(KnownBillInput("2026-11-05",11200,10000,1200,bank)),principalMinor=100000,opening=true)))
                vm.preferences.select(b);Triple(b,a,e)
            }
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book&&!vm.busy.value}
            ui.runOnIdle{vm.navigate("accounts")}
            tapTag("account-$card");tapTag("account-financial");tapTag("analysis-tab-plans");tapTag("finance-pool-open")
            val opening=textField(text("期初属于循环的本金 / 没有请填0","循環分の期首元金・なければ0"));opening.performScrollTo()
            if(InstrumentationRegistry.getArguments().getString("realIme")=="true")tapTag("field-"+text("期初属于循环的本金 / 没有请填0","循環分の期首元金・なければ0"))
            opening.performTextReplacement(if(loan)"0"else"100000")
            if(InstrumentationRegistry.getArguments().getString("realIme")=="true") {
                ui.waitUntil(10000){android.view.inspector.WindowInspector.getGlobalWindowViews().any{androidx.core.view.ViewCompat.getRootWindowInsets(it)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true}}
                shot("$theme-$language-pool-ime")
            }
            tapTag("select-trigger-"+text("新增本金起算日期","追加元金の開始日"));ui.onNodeWithText(text("交易 / 借入发生日","取引・借入日")).performClick()
            if(loan) {
                tapTag("select-trigger-"+text("实际扣款账户 / 可留空","実引落口座・未定は空欄"));ui.onNodeWithText("銀行").performClick()
                tapTag("date-field-0");ui.onNodeWithContentDescription("2026-10-15").performScrollTo().performClick()
            }
            textField(text("预测期数","予測回数")).performScrollTo().performTextReplacement("2")
            tapTag("date-field-1")
            ui.onNodeWithContentDescription("2026-10-30").performScrollTo().performClick()
            tapTag("date-field-2")
            ui.onNodeWithContentDescription(text("下一月","次の月")).performScrollTo().performClick()
            ui.onNodeWithContentDescription("2026-11-05").performScrollTo().performClick()
            tapTag("pool-generate");if(!loan)tapTag("pool-adopt");tapTag("pool-preview")
            ui.onNode(hasText(if(loan)"¥1,024"else"¥11,200") and hasAnyAncestor(hasTestTag("pool-period-1"))).assertExists()
            ui.onNode(hasText(if(loan)"¥1,033"else"¥12,165") and hasAnyAncestor(hasTestTag("pool-period-2"))).assertExists()
            shot("$theme-$language-pool-review")
            runBlocking{repo.saveAccount(book,"循環更新",if(loan)AccountType.LOAN else AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,if(loan)0 else 100000),"2026-10-01",card)}
            tapTag("pool-confirm")
            ui.waitUntil(10000){!vm.busy.value&&ui.onAllNodesWithTag("pool-preview").fetchSemanticsNodes().isNotEmpty()}
            assertTrue(runBlocking{db.financialDao().plans(book,card).none{it.origin=="POOL"}})
            opening.assertTextEquals(if(loan)"0"else"100000")
            tapTag("pool-preview");tapTag("pool-confirm")
            ui.waitUntil(10000){!vm.busy.value&&vm.financialState.value?.plans?.any{it.origin=="POOL"}==true}
            val pool=vm.financialState.value!!.plans.single{it.origin=="POOL"}
            val initial=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertEquals(if(loan)3000L else 150000L,initial.balance(initial.accounts.first{it.id==card}));assertEquals(if(loan)203000L else 200000L,initial.balance(initial.accounts.first{it.name=="銀行"}));assertEquals(1,initial.events.size)
            runBlocking{if(loan)repo.commitFinancialLedger(repo.previewBorrowing(book,FinancialBorrowingInput(card,"循环2",FinanceMode.CREDIT_LINE,Money(Currency.JPY,2000),"2026-10-20",initial.accounts.first{it.name=="銀行"}.id,Money(Currency.JPY,2000))))else repo.saveEntry(book,EntryDraft(accountId=card,categoryId=initial.categories.single{it.name=="買物"}.id,amount="20000",date="2026-10-28",merchant="買物2",cardPaymentChoice="REVOLVING"))}
            ui.waitUntil(10000){vm.financialState.value?.plans?.firstOrNull{it.id==pool.id}?.revision==2&&(vm.state.value as? AppState.Ready)?.snapshot?.visibleEvents?.size==2}
            tapTag("finance-plan-history-${pool.id}");tapTag("finance-plan-version-open-2");shot("$theme-$language-pool-history")
            tapTag("sheet-close");tapTag("analysis-tab-overview");tapTag("finance-next-statement")
            ui.onNodeWithTag("finance-statement-amount").assertTextEquals(if(loan)"¥1,035"else"¥11,200")
            tapTag("finance-statement-next")
            ui.onNodeWithTag("finance-statement-amount").assertTextEquals(if(loan)"¥1,064"else"¥12,471")
            ui.onNodeWithTag("statement-source-$source").assertExists()
            ui.onNodeWithTag("finance-statement-total").performScrollTo();shot("$theme-$language-pool-statement")
            val final=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertEquals(if(loan)5000L else 170000L,final.balance(final.accounts.first{it.id==card}));assertEquals(if(loan)205000L else 200000L,final.balance(final.accounts.first{it.name=="銀行"}));assertEquals(if(loan)0L else 70000L,final.monthExpense("2026-10"));assertEquals(2,final.events.size)
            assertNull(vm.error.value)
        }
    }
}
