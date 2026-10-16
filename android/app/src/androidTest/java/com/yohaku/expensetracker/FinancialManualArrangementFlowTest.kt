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
class FinancialManualArrangementFlowTest {
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
        val file=File(context.getExternalFilesDir(null),"phase-c-manual-arrangement/$name.png");file.parentFile!!.mkdirs()
        file.outputStream().use{ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
        if(name.endsWith("-ime")||name.endsWith("-history")) {
            val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(file.parentFile,"$name-screen.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
        }
    }
    @Test fun platformScheduleCanBeEnteredComparedAppliedAndViewedWithoutCalculationRules() {
        for(theme in listOf("magazine","journal","objects").filter{InstrumentationRegistry.getArguments().getString("theme")?.let{v->v==it}?:true})for(language in listOf("zh","ja").filter{InstrumentationRegistry.getArguments().getString("language")?.let{v->v==it}?:true}) {
            fun text(zh:String,ja:String)=if(language=="ja")ja else zh
            val ids=runBlocking {
                val b=repo.createBook("手动 $theme $language",Currency.JPY,theme,language,startDate="2026-10-01")
                repo.updateBook(repo.dao.book(b)!!.copy(initialized=true))
                val bank=repo.saveAccount(b,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
                val card=repo.saveAccount(b,"カード",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,1000),"2026-10-01")
                val old=repo.commitManualFinancialPlan(repo.previewManualFinancialPlan(b,ManualFinancialPlanInput(card,"原一括",FinanceMode.SINGLE,listOf(KnownBillInput("2026-11-05",1000,1000,0,bank)),1000,complete=true,opening=true)))!!
                vm.preferences.select(b);listOf(b,bank,card,old)
            }
            val book=ids[0];val bank=ids[1];val card=ids[2];val old=ids[3]
            val original=runBlocking{db.financialDao().bills(book,old).single()}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book&&!vm.busy.value}
            ui.runOnIdle{vm.navigate("accounts")};tapTag("account-$card");tapTag("account-financial");tapTag("analysis-tab-plans");tapTag("finance-compare-plan-$old")
            tapTag("arrangement-manual")
            val count=text("预测期数","予測回数")
            textField(count).performScrollTo().performTextReplacement("2")
            tapTag("select-trigger-"+text("原方案剩余费用如何处理","元の予定の残手数料の扱い"))
            ui.onNode(hasText(text("平台取消原费用","返済先が元の手数料を取消")) and hasAnyAncestor(hasTestTag("select-menu-frame"))).performClick()
            val fee=text("另列变更费用 / 未知留空，已知无费用填0","別途変更手数料・不明は空欄、無料は0")+" · JPY"
            textField(fee).performScrollTo().performTextReplacement("100")
            tapTag("arrangement-generate")
            for(i in 0..1)ui.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("arrangement-manual-total-$i"))).performScrollTo().performTextReplacement("520")
            tapTag("arrangement-preview")
            ui.waitUntil(10000){!vm.busy.value&&vm.financialArrangementReview.value?.calculation is Calculation.Ready}
            val partial=vm.financialArrangementReview.value!!
            assertFalse(partial.target!!.complete);assertTrue(partial.bills.all{it.principalMinor==null&&it.feeMinor==null})
            val title=text("新方案","新プラン")
            ui.onNodeWithTag("arrangement-table-scroll-$title").performScrollTo()
            ui.onNodeWithTag("arrangement-cell-$title-0-0").assertTextEquals(text("未知","不明"))
            ui.onNodeWithTag("arrangement-cell-$title-0-2").assertTextEquals("¥620")
            ui.onNodeWithTag("arrangement-cell-$title-0-3").assertTextEquals(text("未知","不明"))
            shot("$theme-$language-manual-partial-table")
            ui.onNodeWithTag("arrangement-table-scroll-$title").performTouchInput{swipeLeft()};ui.waitForIdle()
            ui.onNodeWithTag("arrangement-cell-$title-0-3").assertIsDisplayed()
            shot("$theme-$language-manual-partial-table-end")
            tapTag("arrangement-back");tapTag("arrangement-manual-complete")
            for(i in 0..1) {
                ui.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("arrangement-manual-principal-$i"))).performScrollTo().performTextReplacement("500")
                ui.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("arrangement-manual-fee-$i"))).performScrollTo().performTextReplacement("20")
            }
            if(InstrumentationRegistry.getArguments().getString("realIme")=="true") {
                tapTag("field-"+text("本期费用 / 未知留空","当期手数料・不明は空欄")+" · JPY","arrangement-manual-fee-1")
                ui.waitUntil(10000){android.view.inspector.WindowInspector.getGlobalWindowViews().any{androidx.core.view.ViewCompat.getRootWindowInsets(it)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true}}
                shot("$theme-$language-manual-ime")
            }
            tapTag("arrangement-preview")
            ui.waitUntil(10000){!vm.busy.value&&vm.financialArrangementReview.value?.calculation is Calculation.Ready}
            val review=vm.financialArrangementReview.value!!
            assertEquals(listOf(620L,520L),review.bills.map{it.totalMinor});assertEquals(140L,review.comparison!!.totalExtraFeeMinor)
            ui.onNodeWithTag("arrangement-manual-review").performScrollTo();shot("$theme-$language-manual-review")
            tapTag("arrangement-record-applied");tapTag("arrangement-platform-confirmed");tapTag("arrangement-confirm")
            ui.waitUntil(10000){!vm.busy.value&&vm.financialState.value?.arrangements?.any{it.originalPlanId==old}==true}
            val f=runBlocking{db.financialDao().graph(book).first()!!};val s=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            val change=f.arrangements.single();val target=f.plans.first{it.id==change.targetPlanId}
            assertTrue(target.complete);assertNull(target.termsJson);assertTrue(f.rules.isEmpty());assertEquals(original,f.bills.first{it.id==original.id})
            assertEquals(10000L,s.balance(s.accounts.first{it.id==bank}));assertEquals(1000L,s.balance(s.accounts.first{it.id==card}));assertTrue(s.events.isEmpty());assertEquals(0L,s.monthExpense("2026-10"))
            tapTag("finance-plan-history-${target.id}");tapTag("finance-plan-version-open-1")
            ui.onAllNodes(hasText("¥620") and hasAnyAncestor(hasTestTag("finance-plan-version-1"))).onFirst().performScrollTo()
            shot("$theme-$language-manual-history");tapTag("sheet-close")
            assertNull(vm.error.value)
        }
    }
}
