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
class ExpectedIncomeFlowTest {
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
        val file=File(context.getExternalFilesDir(null),"phase-c-funds/$name.png");file.parentFile!!.mkdirs()
        file.outputStream().use{ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
        if(name.endsWith("-ime")||name.endsWith("-history")) {
            val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(file.parentFile,"$name-screen.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
        }
    }
    @Test fun expectedIncomeStaysSeparateUntilActualArrivalInEveryThemeAndLanguage() {
        for((theme,language) in testLooks(this@ExpectedIncomeFlowTest)) {
            fun text(zh:String,ja:String)=if(language=="ja")ja else zh
            val ids=runBlocking {
                val b=repo.createBook("資金 $theme $language",Currency.JPY,theme,language,startDate="2026-10-01",budgetMinor=10000)
                repo.updateBook(repo.dao.book(b)!!.copy(initialized=true))
                val bank=repo.saveAccount(b,"引落銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,1000),"2026-10-01")
                repo.saveAccount(b,"別の銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,100000),"2026-10-01")
                val card=repo.saveAccount(b,"カード",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,800),"2026-10-01")
                val expense=repo.saveCategory(b,"固定費",EntryKind.EXPENSE,null);val income=repo.saveCategory(b,"給与",EntryKind.INCOME,null)
                val plan=repo.commitManualFinancialPlan(repo.previewManualFinancialPlan(b,ManualFinancialPlanInput(card,"カード支払",FinanceMode.SINGLE,listOf(KnownBillInput("2026-10-20",800,800,0,bank)),principalMinor=800,opening=true)))!!
                val bill=db.financialDao().bills(b,plan).single().id
                repo.saveRecurrence(b,RecurrenceInput("固定費",bank,expense,Money(Currency.JPY,400),Money(Currency.JPY,400),false,RecurrenceFrequency.MONTHLY,"2026-10-18"))
                repo.ensureOccurrences(b,"2026-10")
                vm.preferences.select(b);listOf(b,bank,bill,income)
            }
            val (book,bank,bill,category)=ids
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book&&!vm.busy.value}
            ui.runOnIdle{vm.navigate("analysis")};tapTag("analysis-tab-future");tapTag("expected-add")
            textField(text("预计收入名称","収入予定名")).performScrollTo().performTextReplacement("給与見込み")
            val label=text("预计金额","予定金額")+" · JPY"
            val field=textField(label);field.performScrollTo()
            if(InstrumentationRegistry.getArguments().getString("realIme")=="true")tapTag("field-$label")
            field.performTextReplacement("500")
            if(InstrumentationRegistry.getArguments().getString("realIme")=="true") {
                ui.waitUntil(10000){android.view.inspector.WindowInspector.getGlobalWindowViews().any{androidx.core.view.ViewCompat.getRootWindowInsets(it)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true}}
                shot("$theme-$language-income-ime")
            }
            tapTag("expected-preview");tapTag("expected-back")
            field.assertTextEquals("500")
            assertTrue(runBlocking{repo.snapshot(repo.dao.book(book)!!).first().expectedIncomes.isEmpty()})
            tapTag("expected-preview");tapTag("expected-confirm")
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.expectedIncomes?.size==1&&!vm.busy.value}
            var snapshot=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()};val id=snapshot.expectedIncomes.single().id
            assertEquals(1000L,snapshot.balance(snapshot.accounts.first{it.id==bank}));assertTrue(snapshot.events.isEmpty());assertEquals(10000L,snapshot.budgetLimit("2026-10"))
            ui.waitUntil(10000){ui.onAllNodesWithTag("cash-gap-bill:$bill").fetchSemanticsNodes().isNotEmpty()}
            val row=ui.onNodeWithTag("cash-bill:$bill");row.performScrollTo();ui.waitForIdle()
            ui.onNode(hasText(text("含预计收入情景：","収入見込みあり：")+"≈ ¥300") and hasAnyAncestor(hasTestTag("cash-bill:$bill"))).assertExists()
            shot("$theme-$language-income-scenarios")
            tapTag("expected-arrive-$id")
            textField(text("实际到账金额","実入金額")+" · JPY").performScrollTo().performTextReplacement("450")
            tapTag("select-trigger-"+text("收入分类","収入カテゴリ"));ui.onNodeWithText("給与").performClick()
            tapTag("expected-arrival-preview")
            // Concurrent changes must return to preserved inputs and require a new review.
            runBlocking{repo.saveAccount(book,"引落銀行 更新",AccountType.BANK,Currency.JPY,Money(Currency.JPY,1000),"2026-10-01",bank)}
            tapTag("expected-confirm")
            ui.waitUntil(10000){ui.onAllNodesWithTag("expected-arrival-preview").fetchSemanticsNodes().isNotEmpty()&&!vm.busy.value}
            textField(text("实际到账金额","実入金額")+" · JPY").assertTextEquals("450")
            assertTrue(runBlocking{repo.snapshot(repo.dao.book(book)!!).first().events.isEmpty()})
            tapTag("expected-arrival-preview");shot("$theme-$language-income-arrival");tapTag("expected-confirm")
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.expectedIncomes?.singleOrNull()?.status=="ARRIVED"&&!vm.busy.value}
            snapshot=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertEquals(1450L,snapshot.balance(snapshot.accounts.first{it.id==bank}));assertEquals(1,snapshot.events.size);assertEquals(450L,snapshot.events.single().baseMinor);assertEquals(0L,snapshot.monthExpense("2026-10"));assertEquals(10000L,snapshot.budgetLimit("2026-10"))
            ui.waitUntil(10000){ui.onAllNodesWithTag("cash-bill:$bill").fetchSemanticsNodes().isNotEmpty()&&ui.onAllNodesWithTag("cash-gap-bill:$bill").fetchSemanticsNodes().isEmpty()}
            ui.onNodeWithTag("cash-bill:$bill").performScrollTo();shot("$theme-$language-income-arrived")
            tapTag("cash-open-bill:$bill")
            ui.onNodeWithTag("finance-bill-$bill").assertExists()
            assertNull(vm.error.value)
        }
    }
}
