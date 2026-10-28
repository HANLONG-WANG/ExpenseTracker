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
import com.yohaku.expensetracker.domain.currency.ReferenceRateCache
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
class ReferenceRateFlowTest {
    @get:Rule val ui=createComposeRule()
    private lateinit var db:YohakuDatabase
    private lateinit var repo:LedgerRepository
    private lateinit var vm:AppViewModel
    private var quoted="0.04"
    private var offline=false
    private lateinit var rates:ReferenceRates
    private val requests=java.util.concurrent.CopyOnWriteArrayList<Triple<Currency,Currency,String>>()
    @Before fun open() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        repo=LedgerRepository(db,Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"),ZoneId.of("Asia/Tokyo")))
        rates=ReferenceRates(TestReferenceRateStore(),ReferenceRateTransport{base,quote,date->requests+=Triple(base,quote,date);if(offline)throw java.io.IOException("offline");val value=if(base==Currency.JPY&&quote==Currency.CNY||base==Currency.USD&&quote==Currency.JPY)quoted else "25";val observed=if(date<="2026-10-11")"2026-10-09"else"2026-10-14";"""{"base":"${base.name}","quote":"${quote.name}","date":"$observed","rate":$value}"""},repo.clock)
        vm=AppViewModel(repo,AppPreferences(context),rates)
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
        val file=File(context.getExternalFilesDir(null),"phase-c-rates/$name.png");file.parentFile!!.mkdirs()
        file.outputStream().use{ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
        if(name.endsWith("-ime")||name.endsWith("-history")) {
            val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(file.parentFile,"$name-screen.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
        }
    }
    @Test fun referenceRefreshNeverOverwritesActualAmountsAndStoredEvidenceInEveryThemeAndLanguage() {
        for((theme,language) in testLooks(this@ReferenceRateFlowTest)) {
            fun text(zh:String,ja:String)=if(language=="ja")ja else zh
            quoted="0.04";offline=false
            runBlocking{rates.store.saveReferenceRateCache(com.yohaku.expensetracker.domain.currency.ReferenceRateCache())}
            val ids=runBlocking {
                val b=repo.createBook("レート $theme $language",Currency.JPY,theme,language,startDate="2026-10-01")
                repo.updateBook(repo.dao.book(b)!!.copy(initialized=true))
                val bank=repo.saveAccount(b,"JPY銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,100000),"2026-10-01")
                val cny=repo.saveAccount(b,"CNY銀行",AccountType.BANK,Currency.CNY,Money(Currency.CNY,0),"2026-10-01")
                vm.preferences.select(b);listOf(b,bank,cny)
            }
            val (book,bank,cny)=ids
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book&&!vm.busy.value}
            val snapshot=(vm.state.value as AppState.Ready).snapshot!!
            ui.runOnIdle{vm.navigate("accounts");vm.startTransfer(snapshot,bank)}
            val amountLabel=text("实际扣款总额","実際の出金総額")+" · JPY"
            val field=textField(amountLabel);field.performScrollTo()
            if(InstrumentationRegistry.getArguments().getString("realIme")=="true")tapTag("field-$amountLabel")
            field.performTextReplacement("10000")
            ui.waitUntil(10000){vm.entry.value?.toAmount=="400.00"}
            if(InstrumentationRegistry.getArguments().getString("realIme")=="true") {
                ui.waitUntil(10000){android.view.inspector.WindowInspector.getGlobalWindowViews().any{androidx.core.view.ViewCompat.getRootWindowInsets(it)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true}}
                shot("$theme-$language-rate-ime")
            }
            tapTag("select-trigger-"+text("资金状态","資金の状態"));ui.onNodeWithText(text("已转出，已到账","出金・入金済")).performClick()
            textField(text("实际到账净额","実際の入金純額")+" · CNY").performScrollTo().performTextReplacement("395")
            quoted="0.05";tapTag("rate-refresh-JPY-CNY")
            ui.waitUntil(10000){ui.onAllNodesWithText("1 JPY = 0.05 CNY").fetchSemanticsNodes().isNotEmpty()}
            assertEquals("10000",vm.entry.value!!.amount);assertEquals("395",vm.entry.value!!.toAmount)
            assertEquals("0.04",vm.entry.value!!.referenceRates.single().rate)
            ui.onNodeWithTag("rate-use-JPY-CNY").assertIsNotEnabled()
            shot("$theme-$language-rate-confirmed")
            offline=true;tapTag("rate-refresh-JPY-CNY")
            ui.waitUntil(10000){ui.onAllNodesWithText(text("网络不可用","ネットワーク未接続"),substring=true).fetchSemanticsNodes().isNotEmpty()}
            assertEquals("395",vm.entry.value!!.toAmount);shot("$theme-$language-rate-offline")
            fun tapText(label:String){ui.onNode(hasText(label) and hasClickAction()).performScrollTo().performClick();ui.waitForIdle()}
            tapText(text("保存这笔转账","この振替を保存"))
            ui.waitUntil(10000){vm.entryReview.value!=null&&!vm.busy.value}
            tapText(text("确认并保存","確認して保存"))
            ui.waitUntil(10000){vm.entry.value==null&&!vm.busy.value}
            val saved=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertEquals(90000L,saved.balance(saved.accounts.first{it.id==bank}));assertEquals(39500L,saved.balance(saved.accounts.first{it.id==cny}));assertEquals(1,saved.events.size)
            assertEquals(40000L,saved.transfers.single().expectedToMinor)
            assertEquals("0.04",repo.json.decodeFromString<List<com.yohaku.expensetracker.domain.currency.ReferenceRateSnapshot>>(saved.settlements.single().referenceRatesJson!!).single().rate)
            offline=false;ui.runOnIdle{vm.navigate("accounts")}
            ui.waitUntil(10000){ui.onAllNodesWithText("≈ ¥99,875").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithText("≈ ¥99,875").performScrollTo()
            ui.onNodeWithTag("page-scroll").performSemanticsAction(SemanticsActions.ScrollBy){it(0f,900f)}
            if (visualEnabled()) ui.waitUntil(5000){vm.notice.value==null};ui.waitForIdle()
            shot("$theme-$language-rate-assets")
            assertNull(vm.error.value)
        }
    }
    @Test fun foreignExpenseKeepsManualAmountAndHistoricalRateWhenReferenceRefreshes() {
        for((theme,language) in testLooks(this@ReferenceRateFlowTest)) {
            fun text(zh:String,ja:String)=if(language=="ja")ja else zh
            quoted="25";offline=false
            runBlocking{rates.store.saveReferenceRateCache(com.yohaku.expensetracker.domain.currency.ReferenceRateCache())}
            val ids=runBlocking {
                val b=repo.createBook("外貨 $theme $language",Currency.JPY,theme,language,startDate="2026-10-01")
                repo.updateBook(repo.dao.book(b)!!.copy(initialized=true))
                val bank=repo.saveAccount(b,"JPY銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
                val category=repo.saveCategory(b,"買物",EntryKind.EXPENSE,null)
                vm.preferences.select(b);listOf(b,bank,category)
            }
            val (book,bank,category)=ids
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book&&!vm.busy.value}
            val snapshot=(vm.state.value as AppState.Ready).snapshot!!
            ui.runOnIdle{vm.startEntry(snapshot);vm.changeEntry(book,vm.entry.value!!.copy(categoryId=category));vm.navigate("entry")}
            val currencyLabel=text("消费 / 收付原币","取引の原通貨")
            tapTag("select-trigger-$currencyLabel");tapTag("select-option-$currencyLabel-2")
            val originalLabel=text("原币金额","原通貨額")+" · USD"
            if(InstrumentationRegistry.getArguments().getString("realIme")=="true")tapTag("field-$originalLabel")
            textField(originalLabel).performScrollTo().performTextReplacement("100")
            ui.waitUntil(10000){vm.entry.value?.accountAmount=="2500"}
            if(InstrumentationRegistry.getArguments().getString("realIme")=="true") {
                ui.waitUntil(10000){android.view.inspector.WindowInspector.getGlobalWindowViews().any{androidx.core.view.ViewCompat.getRootWindowInsets(it)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true}}
                shot("$theme-$language-rate-entry-ime")
            }
            textField(text("金额","金額")+" · JPY").performScrollTo().performTextReplacement("2400")
            quoted="30";tapTag("rate-refresh-USD-JPY")
            ui.waitUntil(10000){ui.onAllNodesWithText("1 USD = 30 JPY").fetchSemanticsNodes().isNotEmpty()}
            textField(originalLabel).performScrollTo().performTextReplacement("120")
            assertEquals("2400",vm.entry.value!!.accountAmount)
            val status=text("入账金额状态","計上額の状態")
            tapTag("select-trigger-$status");tapTag("select-option-$status-0")
            ui.onNodeWithTag("rate-use-USD-JPY").assertIsNotEnabled()
            shot("$theme-$language-rate-entry-confirmed")
            tapTag("entry-save-button")
            ui.waitUntil(10000){vm.entry.value==null&&!vm.busy.value}
            val saved=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertEquals(7600L,saved.balance(saved.accounts.first{it.id==bank}));assertEquals(2400L,saved.monthExpense("2026-10"));assertEquals(1,saved.events.size)
            assertEquals(2500L,saved.settlements.single().expectedAccountMinor)
            assertEquals("25",repo.json.decodeFromString<List<com.yohaku.expensetracker.domain.currency.ReferenceRateSnapshot>>(saved.settlements.single().referenceRatesJson!!).single().rate)
            assertNull(vm.error.value)
        }
    }

    @Test fun settingsQueryUsesSelectedCurrenciesAndDateWhileAutomaticOffStillAllowsRefresh() {
        val args=InstrumentationRegistry.getArguments()
        for((theme,language) in testLooks(this@ReferenceRateFlowTest)) {
            fun text(zh:String,ja:String)=if(language=="ja")ja else zh
            ui.runOnIdle{vm.navigate("home")};ui.waitForIdle()
            val book=runBlocking {
                rates.store.saveReferenceRateCache(ReferenceRateCache());rates.store.setAutomaticReferenceRates(true)
                requests.clear();offline=false;quoted="0.04"
                val b=repo.createBook("汇率设置",Currency.JPY,theme,language,startDate="2026-10-01")
                repo.updateBook(repo.dao.book(b)!!.copy(initialized=true))
                repo.saveAccount(b,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,1000),"2026-10-01")
                vm.preferences.select(b);b
            }
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book&&!vm.busy.value}
            ui.runOnIdle{vm.navigate("settings")};tapTag("settings-reference-rates")
            ui.waitUntil(10000){requests.size==1&&ui.onAllNodesWithText("1 JPY = 0.04 CNY").fetchSemanticsNodes().isNotEmpty()}
            assertEquals(Triple(Currency.JPY,Currency.CNY,"2026-10-15"),requests.single())
            ui.onNodeWithTag("rates-automatic").assertIsOn();tapTag("rates-automatic")
            ui.waitUntil(10000){!runBlocking{rates.store.automaticReferenceRatesEnabled()}}
            tapTag("select-trigger-"+text("从币种","元の通貨"));ui.onNode(hasText("USD") and hasAnyAncestor(hasTestTag("select-menu-frame"))).performScrollTo().performClick()
            tapTag("select-trigger-"+text("到币种","換算先の通貨"));ui.onNode(hasText("JPY") and hasAnyAncestor(hasTestTag("select-menu-frame"))).performScrollTo().performClick()
            tapTag("date-field-0");ui.onAllNodesWithTag("shared-calendar").assertCountEquals(1)
            ui.onNodeWithTag("shared-calendar").performScrollTo();shot("$theme-$language-settings-calendar")
            ui.onNodeWithContentDescription("2026-10-11").performScrollTo().performClick()
            ui.onNodeWithTag("date-field-0").assert(hasText("2026-10-11",substring=true))
            ui.waitForIdle();assertEquals(1,requests.size)
            quoted="150";tapTag("rate-refresh-USD-JPY")
            ui.waitUntil(10000){requests.size==2&&ui.onAllNodesWithText("1 USD = 150 JPY").fetchSemanticsNodes().isNotEmpty()}
            assertEquals(Triple(Currency.USD,Currency.JPY,"2026-10-11"),requests.last())
            ui.onNodeWithText(text("所选日期暂无当日值，使用最近可用日期：","選択日の値がなく、直近の利用可能日：")+"2026-10-09").assertExists()
            ui.onNodeWithText("1 USD = 150 JPY").performScrollTo();shot("$theme-$language-settings-query")
            offline=true;tapTag("rate-refresh-USD-JPY")
            ui.waitUntil(10000){requests.size==3&&ui.onAllNodes(hasText(text("更新失败，保留最近缓存；请核对日期或手填。","更新できません。直近のキャッシュを保持します。日付を確認するか手入力してください。"),substring=true)).fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithText("1 USD = 150 JPY").assertExists();shot("$theme-$language-settings-offline")
            tapTag("rates-automatic");ui.waitUntil(10000){runBlocking{rates.store.automaticReferenceRatesEnabled()}}
            ui.runOnIdle{vm.back()};tapTag("settings-reference-rates")
            ui.onNodeWithTag("rates-automatic").assertIsOn()
            val snapshot=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertTrue(snapshot.events.isEmpty());assertTrue(snapshot.postings.isEmpty());assertEquals(1000L,snapshot.balance(snapshot.accounts.single()))
            assertNull(vm.error.value)
        }
    }

}
