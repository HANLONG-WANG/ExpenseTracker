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
class BudgetRecurrenceFlowTest {
    @get:Rule val ui=createComposeRule()
    private lateinit var db:YohakuDatabase
    private lateinit var repo:LedgerRepository
    private lateinit var vm:AppViewModel
    private lateinit var restoration:StateRestorationTester
    @Before fun open() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        repo=LedgerRepository(db,Clock.fixed(Instant.parse("2026-10-14T00:00:00Z"),ZoneOffset.UTC))
        vm=AppViewModel(repo,AppPreferences(context));restoration=StateRestorationTester(ui);restoration.setContent{NativeApp(vm)}
    }
    @After fun close(){vm.viewModelScope.cancel();db.close()}
    private fun tap(text:String) {
        ui.waitUntil(10000){ui.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()&&!vm.busy.value}
        ui.onNodeWithText(text).performScrollTo().performClick()
    }
    private fun shot(name:String,sheet:Boolean,reset:Boolean=true) {
        if(reset)ui.onNodeWithTag(if(sheet)"sheet-scroll"else"page-scroll").performSemanticsAction(SemanticsActions.ScrollBy){it(0f,-100000f)}
        if(name.endsWith("-budget")||name.endsWith("-category-limits"))ui.waitUntil(10000){vm.notice.value==null}
        ui.waitForIdle()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(context.getExternalFilesDir(null),"phase-b-p10/$name.png");file.parentFile!!.mkdirs()
        file.outputStream().use{ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
        if(name.endsWith("-budget")||name.endsWith("-category-limits")||name.contains("recurring")||name.contains("rule-editor")||name.endsWith("budget-review")||name.contains("budget-category-layout")||name.contains("planning-overview")) {
            val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
            automation.waitForIdle(100,5000)
            val screen=automation.takeScreenshot()
            File(file.parentFile,"$name-screen.png").outputStream().use{screen.compress(Bitmap.CompressFormat.PNG,100,it)};screen.recycle()
        }
    }
    @Test fun homeAndRecurringOverviewUseTheSameBudgetAndFutureTasks() {
        for(theme in listOf("magazine","journal","objects"))for(language in listOf("zh","ja")) {
            fun text(zh:String,ja:String)=if(language=="ja")ja else zh
            val book=runBlocking {
                val book=repo.createBook("日常 $theme $language",Currency.JPY,theme,language,startDate="2026-10-01",budgetMinor=100000)
                repo.updateBook(repo.dao.book(book)!!.copy(initialized=true))
                val account=repo.saveAccount(book,"支払銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,200000),"2026-10-01")
                val category=repo.saveCategory(book,"生活",EntryKind.EXPENSE,null)
                repo.setActivityMode(book,true)
                val activity=repo.saveActivity(book,"独立小旅行","2026-10-01","2026-10-31",Money(Currency.JPY,20000),true)
                repo.saveEntry(book,EntryDraft(accountId=account,categoryId=category,amount="30000",date="2026-10-02"))
                repo.saveEntry(book,EntryDraft(accountId=account,categoryId=category,amount="20000",date="2026-10-03",activityId=activity))
                repo.saveRecurrence(book,RecurrenceInput("毎月の支払",account,category,Money(Currency.JPY,20000),null,false,RecurrenceFrequency.MONTHLY,"2026-10-14"))
                vm.preferences.select(book);book
            }
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book&&!vm.busy.value}
            ui.runOnIdle{vm.navigate("home")}
            ui.onNodeWithTag("cover-available").assertTextContains("50,000",substring=true)
            ui.onNodeWithTag("home-spend-context").assert(hasAnyDescendant(hasText("¥50,000")))
            ui.onNodeWithTag("home-spend-context").assert(hasAnyDescendant(hasText("¥20,000")))
            shot("$theme-$language-planning-overview-home",false)
            ui.onNodeWithTag("home-fixed-reserve").performScrollTo()
            ui.onNodeWithTag("home-fixed-reserve").assert(hasAnyDescendant(hasText("¥20,000")))
            shot("$theme-$language-planning-overview-home-reserve",false,false)
            ui.onNodeWithTag("home-daily-stats").performScrollTo()
            ui.onNodeWithTag("home-daily-value-0").assertTextEquals("18")
            ui.onNodeWithTag("home-daily-value-1").assertTextEquals("¥2,777")
            ui.onNodeWithTag("home-daily-value-2").assertTextEquals("¥0")
            if(theme=="objects") {
                val a=ui.onNodeWithTag("home-daily-cell-1").getUnclippedBoundsInRoot()
                val b=ui.onNodeWithTag("home-daily-cell-2").getUnclippedBoundsInRoot()
                assertEquals(a.top.value,b.top.value,1f)
                assertEquals((a.bottom-a.top).value,(b.bottom-b.top).value,1f)
            }
            shot("$theme-$language-planning-overview-home-daily",false,false)
            tap(text("确认 / 管理周期账单","定期支払を確認・管理"))
            ui.onNodeWithTag("recurring-reserve-summary").assert(hasAnyDescendant(hasText("¥20,000")))
            val selector=ui.onNodeWithTag("recurring-month").getUnclippedBoundsInRoot()
            val summary=ui.onNodeWithTag("recurring-reserve-summary").getUnclippedBoundsInRoot()
            assertEquals(15f,(summary.top-selector.bottom).value,1f)
            ui.onNodeWithTag("recurring-reserve-summary").performScrollTo()
            shot("$theme-$language-planning-overview-recurring",false,false)
            ui.onNodeWithTag("month-next").performScrollTo().performClick()
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.occurrences?.any{it.scheduledDate=="2026-11-14"}==true&&!vm.busy.value}
            ui.onNodeWithTag("recurring-month").assert(hasAnyDescendant(hasText("2026-11")))
            ui.onNodeWithTag("recurring-reserve-summary").assert(hasAnyDescendant(hasText("¥20,000")))
            ui.onNodeWithTag("recurring-reserve-summary").performScrollTo()
            shot("$theme-$language-planning-overview-recurring-future",false,false)
            val after=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertEquals(2,after.events.size);assertEquals(150000L,after.balance(after.accounts.single()))
            assertEquals(70000L,after.budgetProjection("2026-10").remainingMinor);assertEquals(50000L,after.budgetProjection("2026-10").availableMinor)
            assertEquals(80000L,after.budgetProjection("2026-11").availableMinor);assertEquals(2,after.occurrences.size)
        }
    }
    @Test fun budgetCategoryLayoutsKeepParentChildFactsAndEditing() {
        val estimated=InstrumentationRegistry.getArguments().getString("estimatedLayout")=="true"
        fun assertSpacing(adjustTag:String) {
            val equation=ui.onNodeWithTag("budget-equation").getUnclippedBoundsInRoot()
            val adjust=ui.onNodeWithTag(adjustTag).getUnclippedBoundsInRoot()
            val independent=ui.onNodeWithTag("independent-budget-note").getUnclippedBoundsInRoot()
            if(estimated) {
                val note=ui.onNodeWithTag("budget-estimate-note").getUnclippedBoundsInRoot()
                assertEquals(20f,(note.top-equation.bottom).value,1f)
                assertEquals(35f,(adjust.top-note.bottom).value,1f)
            }else assertEquals(35f,(adjust.top-equation.bottom).value,1f)
            assertEquals(23f,(independent.top-adjust.bottom).value,1f)
        }
        for(theme in listOf("magazine","journal","objects"))for(language in listOf("zh","ja")) {
            fun text(zh:String,ja:String)=if(language=="ja")ja else zh
            val ids=runBlocking {
                val book=repo.createBook("分类预算 $theme $language",Currency.JPY,theme,language,startDate="2026-10-01",budgetMinor=200000)
                repo.updateBook(repo.dao.book(book)!!.copy(initialized=true))
                val account=repo.saveAccount(book,"支払銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,300000),"2026-10-01")
                val parent=repo.saveCategory(book,"日常生活",EntryKind.EXPENSE,null)
                val child=repo.saveCategory(book,"食事と買い物",EntryKind.EXPENSE,parent)
                val other=repo.saveCategory(book,"交通",EntryKind.EXPENSE,null)
                val last=repo.saveCategory(book,"休日",EntryKind.EXPENSE,null)
                for((category,amount)in listOf(parent to 120000L,child to 60000L,other to 20000L))assertTrue(repo.commitBudget(repo.previewBudget(book,"2026-10",category,Money(Currency.JPY,amount))))
                repo.saveEntry(book,EntryDraft(accountId=account,categoryId=child,amount=if(estimated)"20"else"3000",originalCurrency=if(estimated)Currency.USD else null,accountAmount=if(estimated)"3000"else"",settlementConfirmed=!estimated,date="2026-10-02"))
                vm.preferences.select(book);listOf(book,parent,child,last)
            }
            val book=ids[0];val parent=ids[1];val child=ids[2]
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book&&!vm.busy.value}
            ui.runOnIdle{vm.navigate("budget")}
            assertSpacing("budget-edit-total")
            shot("$theme-$language-budget-category-layout-top",false)
            ui.onNodeWithTag("budget-equation").performScrollTo()
            shot("$theme-$language-budget-category-layout-equation",false,false)
            val parentNode=ui.onNodeWithTag("budget-category-$parent")
            parentNode.performScrollTo().assertTextContains("¥117,000",substring=true)
            ui.onNodeWithTag("budget-category-$child").assertTextContains("¥57,000",substring=true)
            if(theme=="objects"&&InstrumentationRegistry.getArguments().getString("largeLayout")!="true") {
                val a=parentNode.getUnclippedBoundsInRoot();val b=ui.onNodeWithTag("budget-category-$child").getUnclippedBoundsInRoot()
                assertEquals(a.top.value,b.top.value,1f);assertTrue(b.left>a.left);assertEquals((a.bottom-a.top).value,(b.bottom-b.top).value,1f)
            }
            shot("$theme-$language-budget-category-layout",false,false)
            tap(text("管理分类","カテゴリ管理"))
            ui.waitUntil(10000){vm.route.value=="categories"}
            ui.runOnIdle{vm.navigate("budget")}
            ui.onNodeWithTag("budget-category-${ids[3]}").performScrollTo().assertTextContains(text("未设分类预算","カテゴリ予算なし"),substring=true)
            shot("$theme-$language-budget-category-layout-tail",false,false)
            ui.onNodeWithText(text("收入到账不会自动增加预算。未单独设置的后续月份沿用最近一次设置。","入金しても予算は自動で増えません。未設定の後続月は直近の設定を引き継ぎます。")).performScrollTo()
            shot("$theme-$language-budget-category-layout-reserves",false,false)
            ui.onNodeWithTag("budget-category-$child").performScrollTo().performClick()
            tap(text("预览预算调整","予算変更を確認"))
            ui.onNodeWithTag("budget-review-panel").assert(hasAnyDescendant(hasText("¥60,000 → ¥60,000")))
            tap(text("返回继续编辑","戻って編集"))
            ui.onNodeWithTag("field-"+text("预算金额 / 留空取消设置","予算額・空欄で未設定")+" · JPY").assertTextEquals("60000")
            val after=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertEquals(3000L,after.budgetProjection("2026-10",parent).spentMinor)
            assertEquals(3000L,after.budgetProjection("2026-10",child).spentMinor)
            assertEquals(3000L,after.budgetProjection("2026-10").spentMinor);assertEquals(1,after.events.size)
            ui.runOnIdle{vm.navigate("analysis")}
            ui.onNodeWithTag("analysis-tab-budget").performScrollTo().performClick()
            assertSpacing("analysis-budget-adjust")
            ui.onNodeWithTag("analysis-budget-adjust").performScrollTo()
            shot("$theme-$language-budget-category-layout-analysis-adjust",false,false)
            ui.onNodeWithTag("analysis-budget-adjust").performClick()
            ui.waitUntil(10000){vm.route.value=="budget"&&vm.openBudgetMonth.value==null}
            ui.onNodeWithTag("budget-edit-total").assertExists()
            ui.runOnIdle{vm.navigate("analysis")}
            ui.onNodeWithTag("analysis-tab-budget").performScrollTo().performClick()
            ui.onNodeWithTag("budget-category-$parent").performScrollTo().assertTextContains("¥117,000",substring=true)
            shot("$theme-$language-budget-category-layout-analysis",false,false)
            ui.onNodeWithTag("budget-category-${ids[3]}").performScrollTo()
            shot("$theme-$language-budget-category-layout-analysis-tail",false,false)
            ui.onNodeWithTag("analysis-budget-manage").performScrollTo().assertExists()
            shot("$theme-$language-budget-category-layout-analysis-end",false,false)
            ui.onNodeWithTag("budget-category-$child").performScrollTo().performClick()
            ui.onNodeWithTag("lead-amount").assertTextContains("¥57,000",substring=true)
            ui.runOnIdle{vm.navigate("home")}
        }
    }
    @Test fun estimatedSettlementReleasesOnlyTheOriginalOccurrenceReserve() {
        for(theme in listOf("magazine","journal","objects"))for(language in listOf("zh","ja")) {
            fun text(zh:String,ja:String)=if(language=="ja")ja else zh
            val (book,occurrence)=runBlocking {
                val book=repo.createBook("预计入账 $theme $language",Currency.JPY,theme,language,startDate="2026-10-01",budgetMinor=100000)
                repo.updateBook(repo.dao.book(book)!!.copy(initialized=true))
                val account=repo.saveAccount(book,"支払銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,50000),"2026-10-01")
                val category=repo.saveCategory(book,"生活",EntryKind.EXPENSE,null)
                repo.saveRecurrence(book,RecurrenceInput("外币账单",account,category,Money(Currency.JPY,2000),null,true,RecurrenceFrequency.MONTHLY,"2026-10-01"))
                vm.preferences.select(book);book to repo.snapshot(repo.dao.book(book)!!).first().pendingOccurrences().single()
            }
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book&&!vm.busy.value}
            ui.runOnIdle{vm.navigate("recurring")}
            ui.onNodeWithTag("confirm-occurrence-${occurrence.id}").performScrollTo().performClick()
            val original=text("消费 / 收付原币","取引の原通貨")
            sheetTarget(ui.onNodeWithTag("select-trigger-$original")).performClick()
            sheetTarget(ui.onNodeWithTag("select-option-$original-${Currency.entries.indexOf(Currency.USD)}")).performClick()
            sheetTarget(ui.onNodeWithTag("field-"+text("原币金额","原通貨額")+" · USD")).performClick().performTextReplacement("20")
            sheetTarget(ui.onNodeWithTag("field-"+text("金额","金額")+" · JPY")).performClick().performTextReplacement("2300")
            if(InstrumentationRegistry.getArguments().getString("realIme")=="true")ui.waitUntil(10000){android.view.inspector.WindowInspector.getGlobalWindowViews().any{androidx.core.view.ViewCompat.getRootWindowInsets(it)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true}}
            sheetTarget(ui.onNodeWithTag("entry-save-button")).assert(hasText(text("记录消费并解除预留","支出を記録して予定を解除")))
            shot("$theme-$language-recurring-estimated-payment-footer",true,false)
            ui.onNodeWithTag("entry-save-button").performClick()
            tap(text("确认并保存","確認して保存"))
            ui.waitUntil(10000){vm.entry.value==null&&!vm.busy.value}
            val pending=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertEquals(1,pending.events.size);assertEquals(50000L,pending.balance(pending.accounts.single()))
            assertEquals(2300L,pending.monthExpense("2026-10"));assertEquals(0L,pending.budgetProjection("2026-10").reserveMinor)
            assertEquals(97700L,pending.budgetProjection("2026-10").availableMinor);assertTrue(pending.pendingOccurrences().isEmpty())
            assertFalse(pending.settlements.single().confirmed);assertEquals(occurrence.id,pending.occurrences.single().id)
            ui.runOnIdle{vm.navigate("recurring")}
            ui.onNodeWithTag("occurrence-settlement-pending-${occurrence.id}").performScrollTo().assertExists()
            shot("$theme-$language-recurring-estimated-recorded",false,false)
            ui.onNodeWithTag("confirm-occurrence-${occurrence.id}").assertDoesNotExist()
            tap(text("查看已存记录","保存済みの記録を見る"))
            tap(text("确认实际入账金额","実計上額を確認"))
            ui.waitUntil(10000){vm.entry.value?.id==pending.events.single().id}
            sheetTarget(ui.onNodeWithTag("field-"+text("金额","金額")+" · JPY")).performClick().performTextReplacement("2400")
            sheetTarget(ui.onNodeWithTag("entry-save-button")).performClick()
            tap(text("确认并保存","確認して保存"))
            ui.waitUntil(10000){vm.entry.value==null&&!vm.busy.value}
            val actual=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertEquals(pending.events.single().id,actual.events.single().id);assertEquals(1,actual.events.size)
            assertEquals(47600L,actual.balance(actual.accounts.single()));assertEquals(2400L,actual.monthExpense("2026-10"))
            assertEquals(0L,actual.budgetProjection("2026-10").reserveMinor);assertEquals(97600L,actual.budgetProjection("2026-10").availableMinor)
            assertTrue(actual.settlements.single().confirmed);assertEquals(2300L,actual.settlements.single().expectedAccountMinor)
            assertEquals(occurrence.id,actual.occurrences.single().id);assertEquals(2000L,actual.occurrences.single().amountMinor)
        }
    }
    @Test fun paymentFormDirectExistingLinkKeepsDraftOnCancelAndClearsOnConfirm() {
        for(theme in listOf("magazine","journal","objects"))for(language in listOf("zh","ja")) {
            fun text(zh:String,ja:String)=if(language=="ja")ja else zh
            val (book,occurrence,event)=runBlocking {
                val book=repo.createBook("关联 $theme $language",Currency.JPY,theme,language,startDate="2026-10-01",budgetMinor=100000)
                repo.updateBook(repo.dao.book(book)!!.copy(initialized=true))
                val account=repo.saveAccount(book,"支払銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,50000),"2026-10-01")
                val category=repo.saveCategory(book,"生活",EntryKind.EXPENSE,null)
                val event=repo.saveEntry(book,EntryDraft(accountId=account,categoryId=category,amount="3000",date="2026-10-02",merchant="已存付款"))
                repo.saveRecurrence(book,RecurrenceInput("月初账单",account,category,Money(Currency.JPY,2000),null,true,RecurrenceFrequency.MONTHLY,"2026-10-01"))
                val occurrence=repo.snapshot(repo.dao.book(book)!!).first().pendingOccurrences().single()
                vm.preferences.select(book);Triple(book,occurrence,event)
            }
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book&&!vm.busy.value}
            ui.runOnIdle{vm.navigate("recurring")}
            ui.onNodeWithTag("confirm-occurrence-${occurrence.id}").performScrollTo().performClick()
            ui.waitUntil(10000){vm.entry.value?.occurrenceId==occurrence.id}
            val amount=ui.onNodeWithTag("field-"+text("金额","金額")+" · JPY")
            amount.performScrollTo().performClick().performTextReplacement("2300")
            if(InstrumentationRegistry.getArguments().getString("realIme")=="true")ui.waitUntil(10000){android.view.inspector.WindowInspector.getGlobalWindowViews().any{androidx.core.view.ViewCompat.getRootWindowInsets(it)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true}}
            val selectLabel=text("关联已记流水，或新增一次","既存記録に関連付け、または新規")
            sheetTarget(ui.onNodeWithTag("select-trigger-$selectLabel")).performClick()
            sheetTarget(ui.onNodeWithTag("select-option-$selectLabel-1")).performClick()
            ui.waitUntil(10000){vm.entry.value?.occurrenceLinkEventId==event&&!vm.busy.value}
            ui.onNodeWithTag("entry-save-button").performScrollTo()
            shot("$theme-$language-recurring-payment-direct-link",true,false)
            ui.onNodeWithTag("entry-save-button").performClick()
            ui.waitUntil(10000){vm.occurrenceLinkReview.value!=null&&!vm.busy.value}
            ui.onNodeWithTag("occurrence-link-review-panel").assert(hasAnyDescendant(hasText("2026-10-02")))
            ui.onNodeWithTag("occurrence-link-review-panel").assert(hasAnyDescendant(hasText("¥3,000")))
            tap(text("返回付款表单","支払フォームに戻る"))
            assertEquals(event,vm.entry.value!!.occurrenceLinkEventId);assertEquals("2300",vm.entry.value!!.amount)
            assertEquals(2000L,runBlocking{repo.snapshot(repo.dao.book(book)!!).first().budgetProjection("2026-10").reserveMinor})
            restoration.emulateSavedInstanceStateRestore()
            ui.onNodeWithTag("entry-save-button").performScrollTo().performClick()
            ui.waitUntil(10000){vm.occurrenceLinkReview.value!=null&&!vm.busy.value}
            tap(text("确认关联已有记录","既存記録との関連を確定"))
            ui.waitUntil(10000){vm.entry.value==null&&vm.occurrenceLinkReview.value==null&&!vm.busy.value}
            val after=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertEquals(1,after.events.size);assertEquals(event,after.events.single().id);assertEquals(1,after.postings.size)
            assertEquals(47000L,after.balance(after.accounts.single()));assertEquals(0L,after.budgetProjection("2026-10").reserveMinor)
            assertEquals(event,after.occurrences.single().eventId);assertEquals(occurrence.id,after.occurrences.single().id)
            assertNull(after.draft)
        }
    }
    @Test fun scheduledAndActualPaymentContextAndFooterInEveryThemeAndLanguage() {
        for(theme in listOf("magazine","journal","objects"))for(language in listOf("zh","ja")) {
            fun text(zh:String,ja:String)=if(language=="ja")ja else zh
            val (book,occurrence)=runBlocking {
                val book=repo.createBook("付款 $theme $language",Currency.JPY,theme,language,startDate="2026-10-01",budgetMinor=100000)
                repo.updateBook(repo.dao.book(book)!!.copy(initialized=true))
                val account=repo.saveAccount(book,"支払銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,50000),"2026-10-01")
                val category=repo.saveCategory(book,"生活",EntryKind.EXPENSE,null)
                repo.saveRecurrence(book,RecurrenceInput("月初账单",account,category,Money(Currency.JPY,2000),null,true,RecurrenceFrequency.MONTHLY,"2026-10-01"))
                val occurrence=repo.snapshot(repo.dao.book(book)!!).first().pendingOccurrences().single()
                vm.preferences.select(book)
                book to occurrence
            }
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book&&!vm.busy.value}
            ui.runOnIdle{vm.navigate("recurring")}
            ui.onNodeWithTag("confirm-occurrence-${occurrence.id}").performScrollTo().performClick()
            ui.waitUntil(10000){vm.entry.value?.occurrenceId==occurrence.id}
            ui.onNodeWithTag("occurrence-payment-context").assert(hasAnyDescendant(hasText("2026-10-01")))
            ui.onNodeWithTag("occurrence-payment-context").assert(hasAnyDescendant(hasText("¥2,000")))
            shot("$theme-$language-recurring-payment-context",true)
            val amount=ui.onNodeWithTag("field-"+text("金额","金額")+" · JPY")
            amount.performScrollTo().performClick().performTextReplacement("2300")
            if(InstrumentationRegistry.getArguments().getString("realIme")=="true")ui.waitUntil(10000){android.view.inspector.WindowInspector.getGlobalWindowViews().any{androidx.core.view.ViewCompat.getRootWindowInsets(it)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true}}
            ui.onNodeWithText(text("实际付款日期","実際の支払日")).performScrollTo().assertExists()
            assertEquals("2026-10-14",vm.entry.value!!.date)
            ui.onNodeWithTag("entry-save-button").performScrollTo()
            ui.onNodeWithTag("occurrence-payment-note").assertExists()
            shot("$theme-$language-recurring-payment-footer",true,false)
            ui.onNodeWithTag("entry-save-button").performClick()
            ui.waitUntil(10000){vm.entryReview.value!=null&&!vm.busy.value}
            assertEquals(0,runBlocking{repo.snapshot(repo.dao.book(book)!!).first().events.size})
            tap(text("返回表单继续编辑","フォームに戻って編集"))
            ui.onNodeWithTag("field-"+text("金额","金額")+" · JPY").assertTextEquals("2300")
            ui.onNodeWithTag("entry-save-button").performScrollTo().performClick()
            tap(text("确认并保存","確認して保存"))
            ui.waitUntil(10000){vm.entry.value==null&&!vm.busy.value}
            val after=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertEquals(1,after.events.size);assertEquals("2026-10-14",after.events.single().date)
            assertEquals(47700L,after.balance(after.accounts.single()));assertEquals(0L,after.budgetProjection("2026-10").reserveMinor)
            assertEquals(occurrence.id,after.occurrences.single().id);assertEquals(2000L,after.occurrences.single().amountMinor)
            assertEquals("COMPLETED",after.occurrences.single().status)
        }
    }
    @Test fun budgetRuleConfirmationAndExistingRecordLinkInEveryThemeAndLanguage() {
        for(theme in listOf("magazine","journal","objects").filter{InstrumentationRegistry.getArguments().getString("theme")?.let{wanted->it==wanted}?:true})for(language in listOf("zh","ja").filter{InstrumentationRegistry.getArguments().getString("language")?.let{wanted->it==wanted}?:true}) {
            fun text(zh:String,ja:String)=if(language=="ja")ja else zh
            val values=runBlocking {
                val book=repo.createBook("日常 $theme $language",Currency.JPY,startDate="2026-10-01",budgetMinor=100000,theme=theme,language=language)
                repo.updateBook(repo.dao.book(book)!!.copy(initialized=true))
                val account=repo.saveAccount(book,"银行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,200000),"2026-10-01")
                val category=repo.saveCategory(book,"生活",EntryKind.EXPENSE,null)
                val event=repo.saveEntry(book,EntryDraft(accountId=account,categoryId=category,amount="30000",date="2026-10-02",merchant="已存支出"))
                vm.preferences.select(book)
                Triple(book,account,event)
            }
            val (book,account,existing)=values
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book}
            ui.runOnIdle{vm.navigate("home")}
            tap(text("预算与分类限额","予算とカテゴリの上限"))
            ui.onNodeWithTag("budget-edit-total").performScrollTo().performClick()
            sheetTarget(ui.onNodeWithTag("field-"+text("预算金额 / 留空取消设置","予算額・空欄で未設定")+" · JPY")).performClick().performTextReplacement("120000")
            if(InstrumentationRegistry.getArguments().getString("realIme")=="true")ui.waitUntil(10000){android.view.inspector.WindowInspector.getGlobalWindowViews().any{androidx.core.view.ViewCompat.getRootWindowInsets(it)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true}}
            tap(text("预览预算调整","予算変更を確認"))
            ui.waitUntil(10000){vm.budgetReview.value!=null&&!vm.busy.value}
            ui.onNodeWithTag("budget-review-panel").assertExists()
            shot("$theme-$language-budget-review",true)
            sheetTarget(ui.onNode(hasText(text("返回继续编辑","戻って編集")) and hasClickAction())).performClick()
            ui.waitUntil(10000){vm.budgetReview.value==null&&!vm.busy.value}
            ui.onNodeWithTag("field-"+text("预算金额 / 留空取消设置","予算額・空欄で未設定")+" · JPY").assertTextEquals("120000")
            assertEquals(100000L,runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}.budgetLimit("2026-10"))
            restoration.emulateSavedInstanceStateRestore()
            ui.onNodeWithTag("field-"+text("预算金额 / 留空取消设置","予算額・空欄で未設定")+" · JPY").assertTextEquals("120000")
            tap(text("预览预算调整","予算変更を確認"))
            tap(text("确认预算调整","予算変更を確定"))
            ui.waitUntil(10000){vm.budgetReview.value==null&&!vm.busy.value}
            assertEquals(120000L,runBlocking{repo.dao.bookOverviews("2026-10").first()}.last().monthlyBudgetMinor)
            ui.runOnIdle{vm.navigate("books")}
            ui.onNodeWithTag("book-edit-$book").performScrollTo().performClick()
            ui.onNodeWithTag("field-"+text("月预算 / 可稍后设置","月予算・後から設定可")).performScrollTo().assertTextEquals("120000").performTextReplacement("110000")
            val renamed="改名 $theme $language"
            ui.onNodeWithTag("field-"+text("账本名称","帳簿名")).performScrollTo().performTextReplacement(renamed)
            tap(text("保存账本","帳簿を保存"))
            tap(text("确认保存账本与预算","帳簿と予算を保存"))
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.name==renamed&&!vm.busy.value}
            ui.runOnIdle{vm.navigate("budget")}
            tap(text("查看固定支出预留","固定費の予定を見る"))
            ui.onNodeWithTag("recurrence-add").performScrollTo().performClick()
            ui.onNodeWithTag("field-"+text("名称","名称")).performScrollTo().performTextReplacement("房租")
            ui.onNodeWithTag("field-"+text("金额 / 未定可留空","金額・未定なら空欄")+" · JPY").performScrollTo().performTextReplacement("20000")
            tap(text("保存周期规则","定期ルールを保存"))
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.recurrences?.size==1&&!vm.busy.value}
            val planned=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertEquals(1,planned.visibleEvents.size)
            assertEquals(60000L,planned.budgetProjection("2026-10").availableMinor)
            val occurrence=planned.pendingOccurrences().single()
            shot("$theme-$language-recurring",false)
            ui.onNodeWithTag("occurrence-${occurrence.id}").performScrollTo()
            shot("$theme-$language-recurring-card",false,false)
            ui.onNodeWithTag("confirm-occurrence-${occurrence.id}").performScrollTo().performClick()
            ui.waitUntil(10000){vm.entry.value?.occurrenceId==occurrence.id}
            ui.onNodeWithTag("field-"+text("金额","金額")+" · JPY").performScrollTo().performTextReplacement("21000")
            tap(text("确认付款并解除预留","支払を確認して予定を解除"))
            ui.waitUntil(10000){vm.entryReview.value!=null&&!vm.busy.value}
            ui.onNodeWithTag("impact-reserve").assertExists()
            ui.onNodeWithText(text("原周期预计","元の定期見込み")).assertExists()
            shot("$theme-$language-payment-review",true)
            tap(text("确认并保存","確認して保存"))
            ui.waitUntil(10000){vm.entry.value==null&&!vm.busy.value}
            val paid=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertEquals(2,paid.visibleEvents.size);assertEquals(0L,paid.budgetProjection("2026-10").reserveMinor)
            assertEquals(59000L,paid.budgetProjection("2026-10").availableMinor)
            assertEquals(149000L,paid.balance(paid.accounts.first{it.id==account}))
            ui.runOnIdle{vm.navigate("recurring")}
            ui.onNodeWithTag("month-next").performClick()
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.occurrences?.any{it.scheduledDate.startsWith("2026-11")}==true&&!vm.busy.value}
            tap(text("关联已有支出","既存の支出に関連付け"))
            ui.onNodeWithTag("occurrence-link-context").assertExists()
            ui.onNodeWithTag("occurrence-link-context").assert(hasAnyDescendant(hasText("2026-11-14")))
            restoration.emulateSavedInstanceStateRestore()
            ui.onNodeWithTag("occurrence-link-context").assertExists();ui.onNodeWithTag("occurrence-link-context").assert(hasAnyDescendant(hasText("2026-11-14")))
            ui.onNodeWithTag("event-$existing").performScrollTo().assert(hasText("银行")).assert(hasText("−¥30,000"))
            shot("$theme-$language-recurring-link-selection",true)
            sheetTarget(ui.onNodeWithTag("event-$existing"))
            shot("$theme-$language-recurring-link-selection-row",true,false)
            ui.onNodeWithTag("event-$existing").performClick()
            ui.waitUntil(10000){vm.occurrenceLinkReview.value!=null&&!vm.busy.value}
            ui.onNodeWithTag("occurrence-link-review-panel").assertExists()
            shot("$theme-$language-recurring-link-review",true)
            sheetTarget(ui.onNode(hasText(text("返回选择","選択に戻る")) and hasClickAction())).performClick()
            ui.waitUntil(10000){vm.occurrenceLinkReview.value==null&&!vm.busy.value}
            val cancelledLink=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertEquals(2,cancelledLink.events.size);assertEquals(149000L,cancelledLink.balance(cancelledLink.accounts.first{it.id==account}));assertEquals(20000L,cancelledLink.budgetProjection("2026-11").reserveMinor)
            ui.onNodeWithTag("event-$existing").performScrollTo().performClick()
            tap(text("确认关联已有记录","既存記録との関連を確定"))
            ui.waitUntil(10000){vm.occurrenceLinkReview.value==null&&!vm.busy.value}
            val linked=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertEquals(2,linked.visibleEvents.size);assertEquals(149000L,linked.balance(linked.accounts.first{it.id==account}))
            assertEquals(110000L,linked.budgetProjection("2026-11").availableMinor)
            val parent=linked.categories.single().id
            val child=runBlocking {
                val id=repo.saveCategory(book,"专项",EntryKind.EXPENSE,parent)
                assertTrue(repo.commitBudget(repo.previewBudget(book,"2026-10",parent,Money(Currency.JPY,50000))))
                id
            }
            ui.runOnIdle{vm.navigate("budget")}
            ui.waitUntil(10000){ui.onAllNodesWithTag("budget-category-$child").fetchSemanticsNodes().isNotEmpty()&&!vm.busy.value}
            ui.onNodeWithTag("budget-category-$child").assertTextContains(text("未设分类预算","カテゴリ予算なし"),substring=true)
            ui.onNodeWithTag("budget-category-$child").performScrollTo().performClick()
            sheetTarget(ui.onNodeWithTag("field-"+text("预算金额 / 留空取消设置","予算額・空欄で未設定")+" · JPY")).performClick().performTextReplacement("60000")
            if(InstrumentationRegistry.getArguments().getString("realIme")=="true")ui.waitUntil(10000){android.view.inspector.WindowInspector.getGlobalWindowViews().any{androidx.core.view.ViewCompat.getRootWindowInsets(it)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true}}
            tap(text("预览预算调整","予算変更を確認"))
            val warning=text("子预算合计超过父预算。保留输入，父预算仍覆盖所有子级。","子予算合計が親予算を超えています。入力を保持し、親はすべての子を含みます。")
            ui.waitUntil(10000){!vm.busy.value&&ui.onAllNodesWithText(warning).fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithText(warning).performScrollTo().assertExists()
            shot("$theme-$language-category-budget-review",true)
            sheetTarget(ui.onNode(hasText(text("返回继续编辑","戻って編集")) and hasClickAction())).performClick()
            ui.waitUntil(10000){vm.budgetReview.value==null&&!vm.busy.value}
            ui.onNodeWithTag("field-"+text("预算金额 / 留空取消设置","予算額・空欄で未設定")+" · JPY").assertTextEquals("60000")
            assertNull(runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}.budgetLimit("2026-10",child))
            tap(text("预览预算调整","予算変更を確認"))
            ui.waitUntil(10000){!vm.busy.value&&ui.onAllNodesWithText(warning).fetchSemanticsNodes().isNotEmpty()}
            tap(text("确认预算调整","予算変更を確定"))
            ui.waitUntil(10000){vm.budgetReview.value==null&&!vm.busy.value}
            val limited=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertEquals(50000L,limited.budgetLimit("2026-10",parent));assertEquals(60000L,limited.budgetLimit("2026-10",child))
            assertEquals(51000L,limited.budgetProjection("2026-10",parent).spentMinor)
            assertEquals(2,limited.events.size);assertEquals(149000L,limited.balance(limited.accounts.first{it.id==account}))
            ui.onNodeWithTag("budget-category-$child").assertTextContains("¥60,000",substring=true)
            shot("$theme-$language-budget",false)
            ui.onNodeWithTag("budget-category-$parent").performScrollTo()
            shot("$theme-$language-category-limits",false,false)

        }
    }
    private fun sheetTarget(node:SemanticsNodeInteraction):SemanticsNodeInteraction {
        ui.waitUntil(10000){!vm.busy.value};node.performScrollTo();ui.waitForIdle()
        val density=InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val header=ui.onNodeWithTag("sheet-header").getUnclippedBoundsInRoot().bottom.value
        val top=node.getUnclippedBoundsInRoot().top.value
        if(top<header+8)ui.onNodeWithTag("sheet-scroll").performSemanticsAction(SemanticsActions.ScrollBy){it(0f,-(header+8-top)*density)}
        ui.waitForIdle();val bottom=node.getUnclippedBoundsInRoot().bottom.value
        val viewport=ui.onNodeWithTag("sheet-scroll").getUnclippedBoundsInRoot().bottom.value
        if(bottom>viewport)ui.onNodeWithTag("sheet-scroll").performSemanticsAction(SemanticsActions.ScrollBy){it(0f,(bottom-viewport+8)*density)}
        ui.waitForIdle();return node
    }
    @Test fun recurringKnownUnknownPaidAndCancelledCardsKeepPaymentFactsWhenEditingAnInactiveRule(){
        for(theme in listOf("magazine","journal","objects").filter{InstrumentationRegistry.getArguments().getString("theme")?.let{wanted->it==wanted}?:true})for(language in listOf("zh","ja").filter{InstrumentationRegistry.getArguments().getString("language")?.let{wanted->it==wanted}?:true}) {
            fun text(zh:String,ja:String)=if(language=="ja")ja else zh
            val ids=runBlocking {
                val book=repo.createBook("周期 $theme $language",Currency.JPY,startDate="2026-10-01",budgetMinor=100000,theme=theme,language=language)
                repo.updateBook(repo.dao.book(book)!!.copy(initialized=true))
                val account=repo.saveAccount(book,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,50000),"2026-10-01")
                val category=repo.saveCategory(book,"生活",EntryKind.EXPENSE,null)
                val known=repo.saveRecurrence(book,RecurrenceInput("已知费用",account,category,Money(Currency.JPY,2000),null,false,RecurrenceFrequency.MONTHLY,"2026-10-14"))
                val unknown=repo.saveRecurrence(book,RecurrenceInput("金额未定",account,category,null,null,true,RecurrenceFrequency.MONTHLY,"2026-10-14"))
                val paid=repo.saveRecurrence(book,RecurrenceInput("已付账单",account,category,Money(Currency.JPY,3000),null,true,RecurrenceFrequency.MONTHLY,"2026-10-14"))
                val before=repo.snapshot(repo.dao.book(book)!!).first()
                val paidOccurrence=before.occurrences.single{it.recurrenceId==paid}
                repo.saveEntry(book,EntryDraft(accountId=account,categoryId=category,amount="3000",date="2026-10-14",occurrenceId=paidOccurrence.id))
                vm.preferences.select(book)
                listOf(book,before.occurrences.single{it.recurrenceId==known}.id,before.occurrences.single{it.recurrenceId==unknown}.id,paidOccurrence.id,paid)
            }
            val book=ids[0];val known=ids[1];val unknown=ids[2];val paid=ids[3];val rule=ids[4]
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book}
            ui.runOnIdle{vm.navigate("recurring")}
            ui.onNodeWithTag("occurrence-status-$known").assertTextEquals(text("金额已确认 · 待付款","金額確定・未払い"))
            ui.onNodeWithTag("occurrence-status-$unknown").assertTextEquals(text("金额未定","金額未定"))
            ui.onNodeWithTag("occurrence-status-$paid").assertTextEquals(text("已记账","記録済み"))
            ui.onNodeWithTag("confirm-occurrence-$paid").assertDoesNotExist()
            ui.onNodeWithTag("cancel-occurrence-$unknown").performScrollTo().performClick()
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.occurrences?.single{it.id==unknown}?.status=="CANCELLED"&&!vm.busy.value}
            ui.onNodeWithTag("occurrence-status-$unknown").assertTextEquals(text("已取消","取消済み"))
            ui.onNodeWithTag("confirm-occurrence-$unknown").assertDoesNotExist()
            ui.onNodeWithTag("edit-occurrence-$paid").performScrollTo().performClick()
            val amount=sheetTarget(ui.onNodeWithTag("field-"+text("金额 / 未定可留空","金額・未定なら空欄")+" · JPY"))
            amount.performClick();amount.performTextReplacement("4000")
            if(InstrumentationRegistry.getArguments().getString("realIme")=="true")ui.waitUntil(10000){android.view.inspector.WindowInspector.getGlobalWindowViews().any{androidx.core.view.ViewCompat.getRootWindowInsets(it)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true}}
            sheetTarget(ui.onNodeWithTag("toggle-"+text("启用此周期规则","この定期ルールを有効にする"))).performClick()
            shot("$theme-$language-rule-editor",true,false)
            sheetTarget(ui.onNodeWithTag("recurrence-save")).performClick()
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.recurrences?.single{it.id==rule}?.active==false&&!vm.busy.value}
            val after=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertEquals(1,after.events.size);assertEquals(47000L,after.balance(after.accounts.single()))
            assertEquals(3000L,after.occurrences.single{it.id==paid}.amountMinor)
            assertEquals("COMPLETED",after.occurrences.single{it.id==paid}.status)
            assertEquals(4000L,after.recurrences.single{it.id==rule}.amountMinor)
            assertEquals(2000L,after.budgetProjection("2026-10").reserveMinor)
            assertEquals(95000L,after.budgetProjection("2026-10").availableMinor)
            ui.runOnIdle{vm.navigate("home");vm.navigate("recurring")}
            ui.onNodeWithTag("occurrence-status-$paid").assertTextEquals(text("已记账","記録済み"))
            ui.waitUntil(10000){vm.notice.value==null}
            ui.onNodeWithTag("occurrence-$paid").performScrollTo();shot("$theme-$language-recurring-paid",false,false)
        }
    }

    @Test fun unknownRuleBecomesConfirmedWithoutAutomaticPaymentAndKeepsItsEndDate(){
        for(theme in listOf("magazine","journal","objects"))for(language in listOf("zh","ja")) {
            fun text(zh:String,ja:String)=if(language=="ja")ja else zh
            val book=runBlocking {
                val id=repo.createBook("未定规则 $theme $language",Currency.JPY,startDate="2026-10-01",budgetMinor=50000,theme=theme,language=language)
                repo.updateBook(repo.dao.book(id)!!.copy(initialized=true))
                repo.saveAccount(id,"支払銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
                repo.saveCategory(id,"生活",EntryKind.EXPENSE,null);vm.preferences.select(id);id
            }
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book}
            ui.runOnIdle{vm.navigate("recurring")}
            ui.onNodeWithTag("recurrence-add").performScrollTo().performClick()
            val name=sheetTarget(ui.onNodeWithTag("field-名称"));name.performClick();name.performTextReplacement("未定の支払")
            if(InstrumentationRegistry.getArguments().getString("realIme")=="true")ui.waitUntil(10000){android.view.inspector.WindowInspector.getGlobalWindowViews().any{androidx.core.view.ViewCompat.getRootWindowInsets(it)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true}}
            ui.onNodeWithTag("recurrence-unknown-amount").assertExists()
            ui.onNodeWithTag("toggle-"+text("金额为预计","金額は見込み")).assertDoesNotExist()
            val frequency=text("频率","頻度")
            sheetTarget(ui.onNodeWithTag("select-trigger-$frequency")).performClick()
            sheetTarget(ui.onNodeWithTag("select-option-$frequency-1")).performClick()
            shot("$theme-$language-rule-editor-unknown",true)
            sheetTarget(ui.onNodeWithTag("recurrence-save")).performClick()
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.recurrences?.size==1&&!vm.busy.value}
            val before=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            val rule=before.recurrences.single();assertEquals("WEEKLY",rule.frequency);assertNull(rule.amountMinor);assertTrue(rule.estimated)
            assertEquals(3,before.pendingOccurrences().size);assertNull(before.budgetProjection("2026-10").reserveMinor);assertTrue(before.events.isEmpty())
            val occurrence=before.occurrences.first()
            ui.onNodeWithTag("edit-occurrence-${occurrence.id}").performScrollTo().performClick()
            val amount=sheetTarget(ui.onNodeWithTag("field-"+text("金额 / 未定可留空","金額・未定なら空欄")+" · JPY"));amount.performClick();amount.performTextReplacement("2000")
            if(InstrumentationRegistry.getArguments().getString("realIme")=="true")ui.waitUntil(10000){android.view.inspector.WindowInspector.getGlobalWindowViews().any{androidx.core.view.ViewCompat.getRootWindowInsets(it)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true}}
            ui.onNodeWithTag("recurrence-unknown-amount").assertDoesNotExist()
            sheetTarget(ui.onNodeWithTag("toggle-"+text("金额为预计","金額は見込み"))).performClick()
            sheetTarget(ui.onNodeWithTag("toggle-"+text("设置结束日期","終了日を設定"))).performClick()
            sheetTarget(ui.onNodeWithTag("date-field-1")).performClick();ui.onAllNodesWithTag("shared-calendar").assertCountEquals(1)
            sheetTarget(ui.onNode(hasContentDescription("2026-10-28") and hasClickAction())).performClick()
            shot("$theme-$language-rule-editor-confirmed",true)
            sheetTarget(ui.onNodeWithTag("recurrence-save"))
            shot("$theme-$language-rule-editor-footer",true,false)
            sheetTarget(ui.onNodeWithTag("recurrence-save")).performClick()
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.recurrences?.singleOrNull()?.amountMinor==2000L&&!vm.busy.value}
            runBlocking{repo.ensureOccurrences(book,"2026-11")}
            val after=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertEquals(rule.id,after.recurrences.single().id);assertEquals("2026-10-28",after.recurrences.single().endDate);assertFalse(after.recurrences.single().estimated)
            assertEquals(before.occurrences.map{it.id}.toSet(),after.occurrences.map{it.id}.toSet());assertEquals(3,after.pendingOccurrences().size)
            assertEquals(6000L,after.budgetProjection("2026-10").reserveMinor);assertEquals(44000L,after.budgetProjection("2026-10").availableMinor)
            assertTrue(after.events.isEmpty());assertEquals(10000L,after.balance(after.accounts.single()))
        }
    }

    @Test fun stoppedRuleReactivatesPendingTasksButKeepsPaidAndUserCancelledStates(){
        for(theme in listOf("magazine","journal","objects"))for(language in listOf("zh","ja")) {
            fun text(zh:String,ja:String)=if(language=="ja")ja else zh
            val ids=runBlocking {
                val book=repo.createBook("再启用 $theme $language",Currency.JPY,startDate="2026-10-01",budgetMinor=100000,theme=theme,language=language);repo.updateBook(repo.dao.book(book)!!.copy(initialized=true))
                val account=repo.saveAccount(book,"支払銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,50000),"2026-10-01")
                val category=repo.saveCategory(book,"生活",EntryKind.EXPENSE,null)
                val rule=repo.saveRecurrence(book,RecurrenceInput("毎週の支払",account,category,Money(Currency.JPY,2000),null,false,RecurrenceFrequency.WEEKLY,"2026-10-14"));repo.ensureOccurrences(book,"2026-11")
                val snapshot=repo.snapshot(repo.dao.book(book)!!).first()
                val paid=snapshot.occurrences.single{it.scheduledDate=="2026-10-14"}.id
                val cancelled=snapshot.occurrences.single{it.scheduledDate=="2026-10-21"}.id
                val future=snapshot.occurrences.single{it.scheduledDate=="2026-10-28"}.id
                repo.saveEntry(book,EntryDraft(accountId=account,categoryId=category,amount="3000",date="2026-10-14",occurrenceId=paid))
                vm.preferences.select(book);listOf(book,account,rule,paid,cancelled,future)
            }
            val book=ids[0];val account=ids[1];val rule=ids[2];val paid=ids[3];val cancelled=ids[4];val future=ids[5]
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book}
            ui.runOnIdle{vm.navigate("recurring")}
            ui.onNodeWithText(text("预留不是已花；确认后才成为记账。","予定は支出済みではありません。確認して記録。")).assertExists()
            ui.onNodeWithTag("recurring-reserve-summary").assert(hasAnyDescendant(hasText("¥4,000")))
            shot("$theme-$language-recurring-main",false)
            ui.onNodeWithTag("cancel-occurrence-$cancelled").performScrollTo().performClick()
            ui.waitUntil(10000){!vm.busy.value&&(vm.state.value as? AppState.Ready)?.snapshot?.occurrences?.single{it.id==cancelled}?.status=="CANCELLED"}
            ui.onNodeWithTag("edit-occurrence-$future").performScrollTo().performClick()
            val amount=sheetTarget(ui.onNodeWithTag("field-"+text("金额 / 未定可留空","金額・未定なら空欄")+" · JPY"));amount.performClick();amount.performTextReplacement("4000")
            if(InstrumentationRegistry.getArguments().getString("realIme")=="true")ui.waitUntil(10000){android.view.inspector.WindowInspector.getGlobalWindowViews().any{androidx.core.view.ViewCompat.getRootWindowInsets(it)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true}}
            sheetTarget(ui.onNodeWithTag("toggle-"+text("启用此周期规则","この定期ルールを有効にする"))).performClick()
            sheetTarget(ui.onNodeWithTag("recurrence-save")).performClick()
            ui.waitUntil(10000){!vm.busy.value&&(vm.state.value as? AppState.Ready)?.snapshot?.recurrences?.single{it.id==rule}?.active==false}
            ui.onNodeWithTag("occurrence-status-$future").assertTextEquals(text("规则已撤下","ルール対象外"));ui.onNodeWithTag("confirm-occurrence-$future").assertDoesNotExist()
            ui.onNodeWithTag("restore-occurrence-$cancelled").assertDoesNotExist()
            ui.onNodeWithTag("occurrence-$future").performScrollTo();shot("$theme-$language-recurring-reactivation-stopped",false,false)
            ui.onNodeWithTag("edit-occurrence-$future").performScrollTo().performClick()
            sheetTarget(ui.onNodeWithTag("toggle-"+text("启用此周期规则","この定期ルールを有効にする"))).performClick()
            sheetTarget(ui.onNodeWithTag("recurrence-save")).performClick()
            ui.waitUntil(10000){!vm.busy.value&&(vm.state.value as? AppState.Ready)?.snapshot?.occurrences?.single{it.id==future}?.status=="PENDING"}
            val resumed=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertEquals(4000L,resumed.budgetProjection("2026-10").reserveMinor);assertEquals(16000L,resumed.budgetProjection("2026-11").reserveMinor)
            assertEquals("CANCELLED",resumed.occurrences.single{it.id==cancelled}.status);assertEquals("COMPLETED",resumed.occurrences.single{it.id==paid}.status);assertEquals(2000L,resumed.occurrences.single{it.id==paid}.amountMinor)
            assertEquals(47000L,resumed.balance(resumed.accounts.single{it.id==account}));assertEquals(1,resumed.events.size)
            ui.onNodeWithTag("occurrence-status-$future").assertTextEquals(text("金额已确认 · 待付款","金額確定・未払い"))
            ui.onNodeWithTag("restore-occurrence-$cancelled").performScrollTo();shot("$theme-$language-recurring-reactivation-manual",false,false)
            ui.onNodeWithTag("restore-occurrence-$cancelled").performClick()
            ui.waitUntil(10000){!vm.busy.value&&(vm.state.value as? AppState.Ready)?.snapshot?.occurrences?.single{it.id==cancelled}?.status=="PENDING"}
            val restored=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            assertEquals(8000L,restored.budgetProjection("2026-10").reserveMinor);assertEquals(89000L,restored.budgetProjection("2026-10").availableMinor)
            assertEquals(47000L,restored.balance(restored.accounts.single{it.id==account}));assertEquals(1,restored.events.size)
            ui.onNodeWithTag("recurrence-rule-$rule").performScrollTo();shot("$theme-$language-recurring-rule-list",false,false)
        }
    }

}
