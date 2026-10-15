package com.yohaku.expensetracker

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
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
class AnalysisFlowTest {
    @get:Rule val ui=createComposeRule()
    private lateinit var restoration:StateRestorationTester
    private lateinit var db:YohakuDatabase
    private lateinit var repo:LedgerRepository
    private lateinit var vm:AppViewModel
    @Before fun open() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        repo=LedgerRepository(db,Clock.fixed(Instant.parse("2026-10-14T00:00:00Z"),ZoneOffset.UTC))
        vm=AppViewModel(repo,AppPreferences(context));restoration=StateRestorationTester(ui);restoration.setContent{NativeApp(vm)}
    }
    @After fun close(){vm.viewModelScope.cancel();db.close()}
    private fun tap(text:String) {
        ui.waitUntil(10000){ui.onAllNodes(hasText(text) and hasClickAction()).fetchSemanticsNodes().isNotEmpty()&&!vm.busy.value}
        ui.onNode(hasText(text) and hasClickAction()).performScrollTo().performClick()
    }
    private fun sheetTap(tag:String)=sheetTapNode(ui.onNodeWithTag(tag),tag)
    private fun sheetTapNode(target:SemanticsNodeInteraction,tag:String="target") {
        target.performScrollTo()
        val header=ui.onNodeWithTag("sheet-header").fetchSemanticsNode().boundsInRoot
        val bounds=target.fetchSemanticsNode().boundsInRoot
        val overlap=header.bottom+12f-bounds.top
        if(overlap>0)ui.onNodeWithTag("sheet-scroll").performSemanticsAction(SemanticsActions.ScrollBy){it(0f,-overlap)}
        ui.waitForIdle()
        val visible=target.fetchSemanticsNode().boundsInRoot
        assertTrue("$tag must be below the sticky header",visible.top>=header.bottom)
        target.performClick()
    }
    private fun shot(name:String,sheet:Boolean,reset:Boolean=true) {
        if(reset)ui.onNodeWithTag(if(sheet)"sheet-scroll"else"page-scroll").performSemanticsAction(SemanticsActions.ScrollBy){it(0f,-100000f)}
        ui.waitForIdle()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(context.getExternalFilesDir(null),"phase-b-p12/$name.png");file.parentFile!!.mkdirs()
        file.outputStream().use{ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(100,5000)
        val screen=automation.takeScreenshot()
        File(file.parentFile,"$name-screen.png").outputStream().use{screen.compress(Bitmap.CompressFormat.PNG,100,it)};screen.recycle()
    }
    @Test fun chooseOrderPinDrillIntoRefundsAndSaveMerchantLocationInEveryThemeAndLanguage() {
        for(theme in listOf("magazine","journal","objects").filter{InstrumentationRegistry.getArguments().getString("theme")?.let{requested->it==requested}?:true})for(language in listOf("zh","ja").filter{InstrumentationRegistry.getArguments().getString("language")?.let{requested->it==requested}?:true}) {
            fun text(zh:String,ja:String)=if(language=="ja")ja else zh
            val values=runBlocking {
                val book=repo.createBook("分析 $theme $language",Currency.JPY,startDate="2026-09-01",theme=theme,language=language)
                repo.updateBook(repo.dao.book(book)!!.copy(initialized=true))
                val account=repo.saveAccount(book,"银行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,200000),"2026-09-01")
                val parent=repo.saveCategory(book,"生活",EntryKind.EXPENSE,null);val child=repo.saveCategory(book,"咖啡",EntryKind.EXPENSE,parent)
                repo.saveCategory(book,"零用途",EntryKind.EXPENSE,parent)
                val merchant=repo.saveMerchant(book,"喫茶店")
                val purchase=repo.saveEntry(book,EntryDraft(accountId=account,categoryId=child,amount="5000",date="2026-10-02",merchant="喫茶店",merchantId=merchant))
                repo.saveEntry(book,EntryDraft(accountId=account,categoryId=parent,amount="2000",date="2026-10-01",merchant="日常"))
                repo.saveRefund(book,RefundInput(purchase,Money(Currency.JPY,1000),account,Money(Currency.JPY,1000),Money(Currency.JPY,1000),"2026-11-02",true))
                vm.preferences.select(book);Triple(book,parent,merchant)
            }
            val (book,parent,merchant)=values
            println("Analysis UI combination: $theme $language book=$book")
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book}
            ui.runOnIdle{vm.navigate("analysis")}
            ui.onNodeWithTag("analysis-category-$parent").assertDoesNotExist()
            ui.onNodeWithTag("analysis-tab-budget").performScrollTo().performClick()
            ui.onNodeWithText(text("预算按完整自然月展示，消费筛选不会改写月预算。","予算は暦月全体を表示。支出の絞込は月予算を変更しません。")).performScrollTo().assertExists()
            ui.onNodeWithTag("analysis-tab-future").performScrollTo().performClick()
            ui.onNodeWithText(text("这个期间没有待付固定安排。","この期間の未払い予定はありません。")).assertExists()
            ui.onNodeWithTag("analysis-tab-spend").performScrollTo().performClick()
            ui.onNodeWithTag("lead-amount").assertDoesNotExist()
            shot("$theme-$language-first-choice",false)
            ui.onNodeWithTag("analysis-choice-CATEGORIES").performScrollTo()
            shot("$theme-$language-choice-cards",false,false)
            ui.onNodeWithTag("analysis-choice-CATEGORIES").performScrollTo().performClick()
            ui.onNodeWithTag("analysis-module-CATEGORIES").assertIsOn()
            ui.onNodeWithTag("analysis-preview-TREND").performScrollTo().performClick()
            ui.onNodeWithTag("analysis-trend-chart").performScrollTo().assertExists()
            assertNull((vm.state.value as AppState.Ready).snapshot!!.analysisPreferences)
            shot("$theme-$language-custom-preview",true,false)
            ui.onNodeWithTag("sheet-close").performClick()
            assertNull((vm.state.value as AppState.Ready).snapshot!!.analysisPreferences)
            ui.onNodeWithTag("lead-amount").assertDoesNotExist()
            tap(text("选择分析模块","分析項目を選ぶ"))
            AnalysisModule.entries.forEach{module->ui.onNodeWithTag("analysis-module-${module.name}").performScrollTo().performClick();ui.onNodeWithTag("analysis-module-${module.name}").assertIsOn()}
            sheetTap("analysis-up-MERCHANTS")
            AnalysisModule.entries.forEach{ui.onNodeWithTag("analysis-module-${it.name}").assertIsOn()}
            sheetTap("analysis-pin-TREND")
            restoration.emulateSavedInstanceStateRestore()
            ui.onNodeWithTag("analysis-module-MERCHANTS").assertIsOn()
            ui.onNodeWithTag("analysis-up-MERCHANTS").assertIsNotEnabled()
            ui.onNodeWithTag("analysis-pin-TREND").assertIsOn()
            ui.onNodeWithTag("analysis-order-first").assertTextEquals(text("趋势","推移"))
            assertNull((vm.state.value as AppState.Ready).snapshot!!.analysisPreferences)
            shot("$theme-$language-custom-selected",true)
            ui.onNodeWithTag("analysis-order-summary").performScrollTo()
            shot("$theme-$language-custom-order",true,false)
            ui.onNodeWithTag("analysis-preferences-save").performScrollTo().performClick()
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.analysisPreferences!=null&&!vm.busy.value}
            val selected=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertEquals(AnalysisModule.MERCHANTS,repo.analysisModules(selected).first());assertEquals(setOf(AnalysisModule.TREND),repo.analysisPins(selected))
            shot("$theme-$language-analysis",false)
            ui.waitUntil(10000){ui.onAllNodesWithTag("sheet-close").fetchSemanticsNodes().isEmpty()}
            ui.onNodeWithTag("analysis-trend-chart").performScrollTo()
            ui.onNodeWithTag("trend-selected-value").assertTextEquals("2026-10 · ¥6,000")
            shot("$theme-$language-trend",false,false)
            ui.onNodeWithTag("trend-point-2026-10").performTouchInput{click()}
            ui.waitUntil(10000){ui.onAllNodesWithTag("sheet-close").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithText(text("已到账退款 · 按原消费归属","入金済み返金・元の支出に帰属"),substring=true).performScrollTo().assertExists()
            ui.onNodeWithTag("sheet-close").performClick()
            ui.onNodeWithTag("select-trigger-"+text("趋势粒度","推移の単位")).performScrollTo().performClick()
            ui.onNodeWithTag("select-option-"+text("趋势粒度","推移の単位")+"-1").performScrollTo().performClick()
            ui.onNodeWithTag("analysis-trend-chart").performScrollTo()
            ui.onNodeWithTag("trend-point-2026-10-01").assertContentDescriptionEquals("2026-10-01 · ¥2,000")
            ui.onNodeWithTag("trend-point-2026-10-02").assertContentDescriptionEquals("2026-10-02 · ¥4,000")
            shot("$theme-$language-daily-trend",false,false)

            ui.onNodeWithTag("analysis-merchant-list").performScrollTo()
            if(theme=="objects")ui.onNodeWithTag("analysis-merchants-total").assertTextEquals("¥6,000")
            if(theme=="journal") {
                val layouts=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                ui.onNodeWithTag("analysis-merchant-index-0",useUnmergedTree=true).performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(layouts)}
                assertEquals("Merchant numbering remains on one line",1,layouts.single().lineCount)
            }
            shot("$theme-$language-merchants-module",false,false)
            ui.onNodeWithTag("analysis-merchant-id:$merchant").performClick()
            ui.onNodeWithText(text("已到账退款 · 按原消费归属","入金済み返金・元の支出に帰属"),substring=true).performScrollTo().assertExists()
            ui.onNodeWithTag("sheet-close").performClick()
            ui.onNodeWithTag("analysis-category-list").performScrollTo()
            shot("$theme-$language-categories-module",false,false)
            ui.onNodeWithTag("analysis-category-$parent").performScrollTo().performClick()
            ui.onNodeWithTag("analysis-detail-net").assertTextEquals("¥6,000")
            sheetTap("analysis-child-UNSPECIFIED")
            ui.onNodeWithTag("analysis-detail-net").assertTextEquals("¥2,000")
            shot("$theme-$language-category-unspecified",true)
            sheetTap("analysis-child-back")
            val emptyId=selected.categories.single{it.parentId==parent&&it.name=="零用途"}.id
            sheetTap("analysis-child-$emptyId")
            ui.onNodeWithTag("analysis-detail-net").assertTextEquals("¥0")
            ui.onNodeWithText(text("此区间没有记录","この期間の記録はありません")).assertExists()
            sheetTap("analysis-child-back")
            val childId=selected.categories.single{it.parentId==parent&&it.name=="咖啡"}.id
            sheetTap("analysis-child-$childId")
            ui.onNodeWithTag("analysis-detail-net").assertTextEquals("¥4,000")
            ui.onNodeWithTag("sheet-title").assertTextEquals("生活 / 咖啡")
            ui.onNodeWithText(text("已到账退款 · 按原消费归属","入金済み返金・元の支出に帰属"),substring=true).performScrollTo().assertExists()
            shot("$theme-$language-category-child",true)
            sheetTap("analysis-child-back")
            ui.onNodeWithTag("analysis-detail-net").assertTextEquals("¥6,000")
            ui.waitUntil(10000){ui.onAllNodesWithText(text("已到账退款 · 按原消费归属","入金済み返金・元の支出に帰属"),substring=true).fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithText(text("已到账退款 · 按原消费归属","入金済み返金・元の支出に帰属"),substring=true).performScrollTo().assertExists()
            shot("$theme-$language-category",true)
            ui.onNodeWithTag("sheet-close").performClick()
            ui.onNodeWithTag("analysis-filter").performScrollTo().performClick()
            ui.onNodeWithTag("select-trigger-"+text("商户","お店")).performScrollTo().performClick()
            val merchantIndex=selected.visibleEvents.filter{it.kind==EntryKind.EXPENSE.name||it.kind==EntryKind.TRANSFER.name}.map{selected.merchantKey(it)}.distinct().indexOf("id:"+merchant)+1
            ui.onNodeWithTag("select-option-"+text("商户","お店")+"-$merchantIndex").performScrollTo().performClick()
            ui.onNodeWithTag("analysis-filter-apply").performScrollTo().performClick()
            ui.waitUntil(10000){ui.onAllNodesWithText("¥4,000").fetchSemanticsNodes().size>=2&&!vm.busy.value}
            assertEquals(3,runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}.events.size)
            ui.runOnIdle{vm.navigate("merchants")}
            ui.onNodeWithTag("merchant-open-$merchant").performScrollTo().performClick()
            tap(text("＋ 添加地点","＋ 場所を追加"))
            ui.onNodeWithTag("field-"+text("地点名称","場所の名前")).performScrollTo().performTextReplacement("入口")
            ui.onNodeWithTag("field-"+text("纬度 / 可留空","緯度・空欄可")).performScrollTo().performTextReplacement("35.0")
            ui.onNodeWithTag("field-"+text("经度 / 可留空","経度・空欄可")).performScrollTo().performTextReplacement("139.0")
            tap(text("保存地点","場所を保存"))
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.merchantLocations?.size==1&&!vm.busy.value}
            val after=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertEquals(3,after.events.size);assertEquals(194000L,after.balance(after.accounts.single()));assertEquals(6000L,after.analysis(AnalysisFilter("2026-10-01","2026-10-31")).total)
            assertEquals(merchant,after.merchantCandidates(35.0,139.0).single().merchant.id)
        }
    }
    @Test fun activityCardsSeparateWholeSceneBudgetFromFilteredNetAndIncludeIndependentExpenses() {
        for(theme in listOf("magazine","journal","objects"))for(language in listOf("zh","ja")) {
            fun text(zh:String,ja:String)=if(language=="ja")ja else zh
            val ids=runBlocking {
                val book=repo.createBook("活動分析 $theme $language",Currency.JPY,startDate="2026-09-01",budgetMinor=100000,theme=theme,language=language)
                repo.updateBook(repo.dao.book(book)!!.copy(initialized=true))
                val account=repo.saveAccount(book,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,200000),"2026-09-01")
                val category=repo.saveCategory(book,"旅行",EntryKind.EXPENSE,null)
                repo.setActivityMode(book,true)
                val activity=repo.saveActivity(book,"秋の旅","2026-09-30","2026-10-04",Money(Currency.JPY,8000),excludeMonthly=true)
                val empty=repo.saveActivity(book,"次の旅","2026-11-01","2026-11-02",null)
                val outside=repo.saveEntry(book,EntryDraft(accountId=account,categoryId=category,amount="3000",date="2026-09-30",merchant="前月の店",activityId=activity))
                val purchase=repo.saveEntry(book,EntryDraft(accountId=account,categoryId=category,amount="5000",date="2026-10-02",merchant="旅途店",activityId=activity))
                val daily=repo.saveEntry(book,EntryDraft(accountId=account,categoryId=category,amount="2000",date="2026-10-01",merchant="日常小店"))
                val refund=repo.saveRefund(book,RefundInput(purchase,Money(Currency.JPY,1000),account,Money(Currency.JPY,1000),Money(Currency.JPY,1000),"2026-11-02",true))
                repo.saveAnalysisPreferences(book,listOf(AnalysisModule.ACTIVITIES),emptySet())
                vm.preferences.select(book);listOf(book,activity,empty,purchase,outside,refund,daily)
            }
            val book=ids[0];val activity=ids[1];val empty=ids[2];val purchase=ids[3];val outside=ids[4];val refund=ids[5];val daily=ids[6]
            println("Analysis activity UI: $theme $language")
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book}
            ui.runOnIdle{vm.navigate("analysis")}
            ui.onNodeWithTag("analysis-activity-$empty").assertExists()
            ui.onNodeWithTag("analysis-activity-period-$empty").assertTextContains("¥0",substring=true)
            ui.onNodeWithTag("analysis-activity-period-$activity").assertTextContains("¥4,000",substring=true)
            ui.onNodeWithTag("analysis-activity-period-daily").assertTextContains("¥2,000",substring=true)
            ui.onNodeWithTag("analysis-activity-$activity").performScrollTo()
            shot("$theme-$language-activity-module",false,false)
            ui.onNodeWithTag("analysis-activity-period-$activity").performScrollTo().performClick()
            ui.onNodeWithTag("event-$purchase").assertExists()
            ui.onNodeWithTag("event-$refund").assertExists()
            ui.onNodeWithTag("event-$outside").assertDoesNotExist()
            ui.onNodeWithTag("event-$daily").assertDoesNotExist()
            shot("$theme-$language-activity-period-detail",true)
            ui.onNodeWithTag("sheet-close").performClick()
            ui.onNodeWithTag("analysis-activity-period-daily").performScrollTo().performClick()
            ui.onNodeWithTag("event-$daily").assertExists()
            ui.onNodeWithTag("event-$purchase").assertDoesNotExist()
            ui.onNodeWithTag("sheet-close").performClick()
            ui.onNodeWithTag("analysis-activity-open-$activity").performScrollTo().performClick()
            ui.onNodeWithText(text("返回全部活动","すべてのシーンへ")).assertExists()
            ui.onNode(hasText("¥7,000") and hasAnyAncestor(hasTestTag("activity-cover"))).assertExists()
            val after=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertEquals(7000L,after.activitySpent(activity))
            assertEquals(6000L,after.analysis(AnalysisFilter("2026-10-01","2026-10-31")).total)
            assertEquals(2000L,after.budgetProjection("2026-10").spentMinor)
            assertEquals(4,after.events.size);assertEquals(191000L,after.balance(after.accounts.single()))
        }
    }

    @Test fun planningTabsShowBudgetReservesTransitAndMaterializeNewFilterMonthsWithoutPosting() {
        for(theme in listOf("magazine","journal","objects"))for(language in listOf("zh","ja")) {
            fun text(zh:String,ja:String)=if(language=="ja")ja else zh
            val ids=runBlocking {
                val book=repo.createBook("予定分析 $theme $language",Currency.JPY,startDate="2026-10-01",budgetMinor=100000,theme=theme,language=language)
                repo.updateBook(repo.dao.book(book)!!.copy(initialized=true))
                val account=repo.saveAccount(book,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,200000),"2026-10-01")
                val target=repo.saveAccount(book,"受取口座",AccountType.BANK,Currency.JPY,Money(Currency.JPY,0),"2026-10-01")
                val parent=repo.saveCategory(book,"生活",EntryKind.EXPENSE,null);val child=repo.saveCategory(book,"固定費",EntryKind.EXPENSE,parent)
                repo.commitBudget(repo.previewBudget(book,"2026-10",parent,Money(Currency.JPY,50000)))
                repo.commitBudget(repo.previewBudget(book,"2026-10",child,Money(Currency.JPY,20000)))
                repo.setActivityMode(book,true)
                val scene=repo.saveActivity(book,"秋の旅","2026-10-01","2026-10-31",Money(Currency.JPY,40000))
                val excluded=repo.saveActivity(book,"独立の旅","2026-10-01","2026-10-31",Money(Currency.JPY,20000),excludeMonthly=true)
                repo.saveEntry(book,EntryDraft(accountId=account,categoryId=child,amount="10000",date="2026-10-02"))
                repo.saveEntry(book,EntryDraft(accountId=account,categoryId=child,amount="6000",date="2026-10-03",activityId=excluded))
                val reserve=repo.previewReserve(book,ReserveInput(scene,"旅途资金","2026-10-20",Money(Currency.JPY,10000)));repo.commitReserve(reserve)
                repo.commitReserve(repo.previewReserve(book,ReserveInput(excluded,"独立资金","2026-10-21",Money(Currency.JPY,15000))))
                repo.saveRecurrence(book,RecurrenceInput("固定安排",account,child,Money(Currency.JPY,20000),null,false,RecurrenceFrequency.MONTHLY,"2026-10-20"))
                repo.saveRecurrence(book,RecurrenceInput("未知安排",account,child,null,null,true,RecurrenceFrequency.MONTHLY,"2026-11-20"))
                val transfer=repo.commitEntryReview(book,repo.previewEntry(book,EntryDraft(kind=EntryKind.TRANSFER,accountId=account,toAccountId=target,amount="10000",toAmount="10000",date="2026-10-14",toDate="2026-11-02",fromConfirmed=true,merchant="未来转账")))!!
                vm.preferences.select(book);listOf(book,parent,reserve.reserve.id,transfer,scene)
            }
            val book=ids[0];val parent=ids[1];val reserve=ids[2];val transfer=ids[3];val scene=ids[4]
            println("Analysis planning UI: $theme $language")
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book}
            ui.runOnIdle{vm.navigate("analysis")}
            ui.onNodeWithTag("analysis-tab-budget").performScrollTo().performClick()
            ui.waitUntil(10000){!vm.busy.value}
            ui.onNode(hasText("¥60,000") and hasAnyAncestor(hasTestTag("budget-equation"))).assertExists()
            ui.onNodeWithTag("independent-budget-note").assertExists()
            shot("$theme-$language-budget-tab",false)
            ui.onNodeWithTag("budget-equation").performScrollTo()
            shot("$theme-$language-budget-equation",false,false)
            ui.onNodeWithTag("budget-category-$parent").performScrollTo()
            shot("$theme-$language-budget-categories",false,false)
            ui.onNodeWithTag("budget-category-$parent").performClick()
            ui.onNodeWithTag("lead-amount").assertTextContains("¥40,000",substring=true)
            ui.onNodeWithTag("analysis-tab-future").performScrollTo().performClick()
            ui.waitUntil(10000){!vm.busy.value}
            val current=(vm.state.value as AppState.Ready).snapshot!!
            val occurrence=current.pendingOccurrences().single()
            ui.onNodeWithTag("future-occurrence-${occurrence.id}").assertExists()
            ui.onNodeWithTag("future-occurrence-${occurrence.id}").performScrollTo()
            shot("$theme-$language-future-timeline",false,false)
            ui.onNodeWithTag("future-fund-reserve-$reserve").performScrollTo()
            shot("$theme-$language-future-funds",false,false)
            ui.onNodeWithTag("future-fund-transfer-$transfer").performScrollTo()
            shot("$theme-$language-future-transit",false,false)
            ui.onNodeWithTag("future-transfer-open-$transfer").performScrollTo().performClick()
            ui.onNodeWithTag("record-edit-button").assertExists()
            ui.onNodeWithTag("sheet-close").performClick()
            ui.onNodeWithTag("analysis-filter").performScrollTo().performClick()
            ui.onNodeWithTag("date-field-1").performScrollTo().performClick()
            ui.onNodeWithContentDescription(text("下一月","次の月")).performScrollTo().performClick()
            ui.onNode(hasText("30") and hasClickAction()).performScrollTo().performClick()
            sheetTap("analysis-filter-apply")
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.occurrences?.count{it.scheduledDate.startsWith("2026-11")}==2&&!vm.busy.value}
            val next=(vm.state.value as AppState.Ready).snapshot!!
            val unknown=next.pendingOccurrences().single{it.name=="未知安排"}
            ui.onNodeWithTag("future-occurrence-${unknown.id}").performScrollTo()
            shot("$theme-$language-future-new-month",false,false)
            ui.onNodeWithTag("analysis-tab-budget").performScrollTo().performClick()
            ui.onNodeWithTag("analysis-budget-month").performScrollTo().performClick()
            ui.onNode(hasText("2026-11") and hasClickAction()).performClick()
            ui.waitUntil(10000){!vm.busy.value}
            assertTrue(ui.onAllNodes(hasText(text("未设置 / 待补齐","未設定・未補足")) and hasAnyAncestor(hasTestTag("budget-equation"))).fetchSemanticsNodes().isNotEmpty())
            val after=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertEquals(3,after.events.size);assertEquals(174000L,after.balance(after.accounts.first{it.name=="銀行"}));assertEquals(0L,after.balance(after.accounts.first{it.name=="受取口座"}))
            assertEquals(3,after.pendingOccurrences().size);assertEquals(25000L,after.budgetProjection("2026-10").fundReserveMinor)
            assertEquals(60000L,after.budgetProjection("2026-10").availableMinor)
            assertNull(after.budgetProjection("2026-11").availableMinor)
            ui.onNodeWithTag("analysis-budget-manage").performScrollTo().performClick()
            ui.onNodeWithText("2026-11").assertExists()
        }
    }

    @Test fun comparisonDrillsUseEqualProgressPeriodsAndRecoverTheSelectedPeriod() {
        for(theme in listOf("magazine","journal","objects"))for(language in listOf("zh","ja")) {
            fun text(zh:String,ja:String)=if(language=="ja")ja else zh
            val ids=runBlocking {
                val book=repo.createBook("比較 $theme $language",Currency.JPY,startDate="2026-09-01",theme=theme,language=language)
                repo.updateBook(repo.dao.book(book)!!.copy(initialized=true))
                val account=repo.saveAccount(book,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,200000),"2026-09-01")
                val parent=repo.saveCategory(book,"生活",EntryKind.EXPENSE,null);val child=repo.saveCategory(book,"珈琲",EntryKind.EXPENSE,parent)
                val merchant=repo.saveMerchant(book,"喫茶店")
                repo.setActivityMode(book,true)
                val activity=repo.saveActivity(book,"長い旅","2026-09-01","2026-10-31",Money(Currency.JPY,30000),excludeMonthly=true)
                val previous=repo.saveEntry(book,EntryDraft(accountId=account,categoryId=child,amount="3000",date="2026-09-02",merchant="喫茶店",merchantId=merchant,activityId=activity))
                val current=repo.saveEntry(book,EntryDraft(accountId=account,categoryId=child,amount="5000",date="2026-10-02",merchant="喫茶店",merchantId=merchant,activityId=activity))
                val later=repo.saveEntry(book,EntryDraft(accountId=account,categoryId=child,amount="9000",date="2026-10-20",merchant="喫茶店",merchantId=merchant,activityId=activity))
                val refund=repo.saveRefund(book,RefundInput(current,Money(Currency.JPY,1000),account,Money(Currency.JPY,1000),Money(Currency.JPY,1000),"2026-11-02",true))
                repo.saveAnalysisPreferences(book,listOf(AnalysisModule.COMPARISON),emptySet())
                vm.preferences.select(book);listOf(book,parent,merchant,activity,previous,current,later,refund)
            }
            val book=ids[0];val parent=ids[1];val merchant=ids[2];val activity=ids[3];val previous=ids[4];val current=ids[5];val later=ids[6];val refund=ids[7]
            println("Analysis comparison UI: $theme $language")
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book}
            ui.runOnIdle{vm.navigate("analysis")}
            ui.onNodeWithTag("comparison-current-period").assertTextEquals("2026-10-01 — 2026-10-14")
            ui.onNodeWithTag("comparison-previous-period").assertTextContains("2026-09-01 — 2026-09-14",substring=true)
            ui.onNodeWithTag("comparison-change-category:$parent").assertTextEquals("¥1,000")
            ui.onNodeWithTag("comparison-cause-category:$parent").performScrollTo()
            shot("$theme-$language-comparison",false,false)
            ui.onNodeWithTag("comparison-previous-category:$parent").performScrollTo().performClick()
            ui.onNodeWithTag("analysis-detail-net").assertTextEquals("¥3,000")
            restoration.emulateSavedInstanceStateRestore()
            ui.onNodeWithTag("event-$previous").assertExists()
            ui.onNodeWithTag("event-$current").assertDoesNotExist()
            ui.onNodeWithTag("event-$later").assertDoesNotExist()
            shot("$theme-$language-comparison-previous",true)
            ui.onNodeWithTag("sheet-close").performClick()
            for(key in listOf("category:$parent","merchant:id:$merchant","activity:$activity")) {
                ui.onNodeWithTag("comparison-current-$key").performScrollTo().performClick()
                ui.onNodeWithTag("analysis-detail-net").assertTextEquals("¥4,000")
                ui.onNodeWithTag("event-$current").assertExists()
                ui.onNodeWithTag("event-$refund").assertExists()
                ui.onNodeWithTag("event-$previous").assertDoesNotExist()
                ui.onNodeWithTag("event-$later").assertDoesNotExist()
                ui.onNodeWithTag("sheet-close").performClick()
            }
            val after=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertEquals(4,after.events.size);assertEquals(184000L,after.balance(after.accounts.single()))
            assertEquals(13000L,after.analysis(AnalysisFilter("2026-10-01","2026-10-31")).total)
            assertEquals(0L,after.budgetProjection("2026-10").spentMinor)
        }
    }

    @Test fun historicalFiltersCancelSafelyRecoverAndDriveEveryModulePreviewWithoutSavingPreferences() {
        for(theme in listOf("magazine","journal","objects"))for(language in listOf("zh","ja")) {
            fun text(zh:String,ja:String)=if(language=="ja")ja else zh
            val ids=runBlocking {
                val book=repo.createBook("期間プレビュー $theme $language",Currency.JPY,startDate="2026-09-01",theme=theme,language=language)
                repo.updateBook(repo.dao.book(book)!!.copy(initialized=true))
                val account=repo.saveAccount(book,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,200000),"2026-09-01")
                val category=repo.saveCategory(book,"历史用途",EntryKind.EXPENSE,null);val other=repo.saveCategory(book,"当月用途",EntryKind.EXPENSE,null)
                val merchant=repo.saveMerchant(book,"历史店");val currentMerchant=repo.saveMerchant(book,"当月店")
                repo.setActivityMode(book,true)
                val activity=repo.saveActivity(book,"历史旅","2026-09-01","2026-09-30",Money(Currency.JPY,10000))
                val currentActivity=repo.saveActivity(book,"当月旅","2026-10-01","2026-10-31",Money(Currency.JPY,10000))
                repo.saveEntry(book,EntryDraft(accountId=account,categoryId=category,amount="1234",date="2026-09-02",merchant="历史店",merchantId=merchant,activityId=activity))
                repo.saveEntry(book,EntryDraft(accountId=account,categoryId=other,amount="5678",date="2026-10-02",merchant="当月店",merchantId=currentMerchant,activityId=currentActivity))
                vm.preferences.select(book);listOf(book,category,merchant,activity)
            }
            val book=ids[0];val category=ids[1];val merchant=ids[2];val activity=ids[3]
            println("Analysis historical preview UI: $theme $language")
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book}
            ui.runOnIdle{vm.navigate("analysis")}
            val snapshot=(vm.state.value as AppState.Ready).snapshot!!
            fun fillHistoricalFilter() {
                ui.onNodeWithTag("analysis-filter").performScrollTo().performClick()
                for((owner,day) in listOf(0 to "1",1 to "30")) {
                    sheetTap("date-field-$owner")
                    sheetTapNode(ui.onNodeWithContentDescription(text("上一月","前の月")))
                    sheetTapNode(ui.onNode(hasText(day) and hasClickAction()))
                }
                val categoryIndex=snapshot.categories.filter{it.kind==EntryKind.EXPENSE.name}.indexOfFirst{it.id==category}+1
                val merchantIndex=snapshot.visibleEvents.filter{it.kind==EntryKind.EXPENSE.name||it.kind==EntryKind.TRANSFER.name}.map{snapshot.merchantKey(it)}.distinct().indexOf("id:$merchant")+1
                val activityIndex=snapshot.activities.indexOfFirst{it.id==activity}+2
                for((label,index) in listOf(text("分类","カテゴリ") to categoryIndex,text("商户","お店") to merchantIndex,text("活动","シーン") to activityIndex)) {
                    sheetTap("select-trigger-$label")
                    ui.onNodeWithTag("select-option-$label-$index").performClick()
                }
            }
            fillHistoricalFilter()
            restoration.emulateSavedInstanceStateRestore()
            ui.onNodeWithTag("select-trigger-"+text("分类","カテゴリ")).assertTextContains("历史用途",substring=true)
            ui.onNodeWithTag("select-trigger-"+text("商户","お店")).assertTextContains("历史店",substring=true)
            ui.onNodeWithTag("select-trigger-"+text("活动","シーン")).assertTextContains("历史旅",substring=true)
            ui.onNodeWithTag("sheet-close").performClick()
            ui.onNodeWithText("2026-10-01 — 2026-10-31").assertExists()
            fillHistoricalFilter()
            sheetTap("analysis-filter-apply")
            ui.waitUntil(10000){ui.onAllNodesWithTag("sheet-close").fetchSemanticsNodes().isEmpty()&&!vm.busy.value}
            ui.onNodeWithText("2026-09-01 — 2026-09-30").assertExists()
            ui.onNodeWithTag("analysis-choice-CATEGORIES").performScrollTo().performClick()
            sheetTap("analysis-preview-CATEGORIES")
            ui.onNodeWithTag("analysis-preview-period").assertTextEquals("2026-09-01 — 2026-09-30")
            ui.onNodeWithTag("analysis-preview-context").assertTextEquals("历史用途 · 历史店 · 历史旅")
            ui.onNode(hasText("¥1,234") and hasAnyAncestor(hasTestTag("analysis-preview-content"))).assertExists()
            ui.onNodeWithTag("analysis-category-$category").assertHasNoClickAction()
            ui.onNodeWithTag("analysis-preview-content").performScrollTo()
            shot("$theme-$language-historical-category-preview",true,false)
            sheetTap("analysis-preview-MERCHANTS")
            ui.onNodeWithTag("analysis-merchant-id:$merchant").assertHasNoClickAction()
            sheetTap("analysis-preview-TREND")
            restoration.emulateSavedInstanceStateRestore()
            ui.onNodeWithTag("analysis-preview-period").assertTextEquals("2026-09-01 — 2026-09-30")
            ui.onNodeWithTag("trend-point-2026-09").assertContentDescriptionEquals("2026-09 · ¥1,234")
            ui.onNodeWithTag("trend-point-2026-10").assertDoesNotExist()
            ui.onNodeWithTag("analysis-preview-content").performScrollTo()
            shot("$theme-$language-historical-trend-preview",true,false)
            sheetTap("analysis-preview-ACTIVITIES")
            ui.onNodeWithTag("analysis-activity-period-$activity").assertTextContains("¥1,234",substring=true).assertHasNoClickAction()
            ui.onNodeWithTag("analysis-activity-open-$activity").assertDoesNotExist()
            sheetTap("analysis-preview-COMPARISON")
            ui.onNodeWithTag("comparison-current-period").assertTextEquals("2026-09-01 — 2026-09-30")
            ui.onNodeWithTag("comparison-current-category:$category").assertDoesNotExist()
            ui.onNodeWithTag("sheet-close").performClick()
            assertNull((vm.state.value as AppState.Ready).snapshot!!.analysisPreferences)
            val after=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertEquals(2,after.events.size);assertEquals(193088L,after.balance(after.accounts.single()))
        }
    }

}
