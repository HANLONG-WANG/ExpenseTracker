package com.yohaku.expensetracker

import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.room.withTransaction
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.data.repository.*
import java.time.Instant
import com.yohaku.expensetracker.domain.finance.*
import com.yohaku.expensetracker.domain.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/** Real MainActivity, system bars, persistent Room and application ViewModel. */
@RunWith(AndroidJUnit4::class)
class FinancialProductionOverviewTest {
    @get:Rule val ui=createEmptyComposeRule()
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as YohakuApplication
    private val repo get()=app.repository
    private var book:String?=null
    private var previous:String?=null
    private val additionalBooks=mutableListOf<String>()
    @Before fun rememberSelection(){DeviceClock.fixedInstant=Instant.parse("2026-10-16T03:00:00Z");previous=runBlocking{app.preferences.activeBook.first()}}
    @After fun cleanFixture(){DeviceClock.fixedInstant=null;runBlocking {
        previous?.let{app.preferences.select(it)}
        (listOfNotNull(book)+additionalBooks).forEach{id->repo.db.withTransaction {
            for(table in listOf("financial_plan_revisions","financial_bills","financial_plans","financial_rules","credit_limit_configs","expected_incomes","budgets","financial_drafts","occurrences","recurrences","categories","accounts"))repo.db.openHelper.writableDatabase.execSQL("DELETE FROM $table WHERE bookId=?",arrayOf(id))
            repo.db.openHelper.writableDatabase.execSQL("DELETE FROM books WHERE id=?",arrayOf(id))
        }}
    }
    }
    private fun tap(tag:String) {
        ui.waitUntil(10000){ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}
        val node=ui.onNodeWithTag(tag)
        if(tag!="nav-accounts")node.performScrollTo()
        node.performClick();ui.waitForIdle()
    }
    private fun center(node:SemanticsNodeInteraction) {
        node.performScrollTo();ui.waitForIdle()
        val bounds=node.getUnclippedBoundsInRoot()
        val viewport=ui.onNodeWithTag("page-scroll").getUnclippedBoundsInRoot()
        val delta=((bounds.top+bounds.bottom-viewport.top-viewport.bottom)/2).value
        val density=InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        ui.onNodeWithTag("page-scroll").performSemanticsAction(SemanticsActions.ScrollBy){it(0f,delta*density)}
        ui.waitForIdle()
    }
    private fun shot(name:String) {
        if (!visualEnabled()) return
        ui.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(350,5000)
        val file=File(app.getExternalFilesDir(null),"phase-c-production-overview/$name.png");file.parentFile!!.mkdirs()
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    @Test fun actualActivityShowsThemedDebtCoversAndAnHonestLoanSchedule() {
        val args=InstrumentationRegistry.getArguments()
        val unknown=args.getString("unknown")=="true"
        val ids=runBlocking {
            val b=repo.createBook("日常生活",Currency.JPY,"magazine","zh",startDate="2026-10-01");book=b
            repo.updateBook(repo.dao.book(b)!!.copy(initialized=true))
            val bank=repo.saveAccount(b,"人民币生活账户",AccountType.BANK,Currency.CNY,Money.parse("3000",Currency.CNY),"2026-10-01")
            val cardBank=repo.saveAccount(b,"引落銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,18400),"2026-10-01")
            val card=repo.saveAccount(b,"三井住友",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,92000),"2026-10-01")
            val loan=repo.saveAccount(b,"国内借款 · 我的分期",AccountType.LOAN,Currency.CNY,Money.parse("18000",Currency.CNY),"2026-10-01")
            val cardPlan=repo.commitManualFinancialPlan(repo.previewManualFinancialPlan(b,ManualFinancialPlanInput(card,"本期账单",FinanceMode.SINGLE,listOf(KnownBillInput("2026-10-26",32000,30000,2000,cardBank)),principalMinor=30000,opening=true)))!!
            val plan=repo.commitManualFinancialPlan(repo.previewManualFinancialPlan(b,ManualFinancialPlanInput(loan,"固定计划",FinanceMode.FIXED_LOAN,listOf(
                KnownBillInput("2026-10-25",850000,if(unknown)null else 800000,50000,bank),KnownBillInput("2026-11-25",530000,500000,30000,bank),KnownBillInput("2026-12-25",520000,500000,20000,bank)),principalMinor=1800000,complete=!unknown,opening=true)))!!
            repo.commitExpectedIncome(repo.previewExpectedIncome(b,ExpectedIncomeInput("給与見込み",Money(Currency.JPY,15500),"2026-10-25",cardBank)))
            repo.commitCreditLimit(repo.previewCreditLimit(b,CreditLimitInput(card,300000,100000,150000,CreditLimitSharing.SEPARATE_CAPS,null,"2026-10-01","2026-10-01",92000,0,0,LimitRecovery.PLATFORM_CONFIRMATION,CreditLimitUsageBasis.PRINCIPAL_ONLY,"Asia/Tokyo",CreditLimitUsageStart.TRANSACTION_DATE)))
            val cardBill=repo.db.financialDao().bills(b,cardPlan).single()
            for(mode in listOf(FinanceMode.INSTALLMENT,FinanceMode.REVOLVING))repo.commitFinancialRule(repo.previewFinancialRule(b,card,FinancialRuleVersion("",b,card,mode,"2026-10-01","Asia/Tokyo",deadlines=listOf(ChangeDeadline(cardBill.id,mode,"2026-10-20","18:00","Asia/Tokyo")))))
            val bills=repo.db.financialDao().bills(b,plan)
            listOf(b,bank,card,loan,plan,bills.first().id,cardPlan,cardBank)
        }
        val (b,bank,card,loan,plan)=ids;val first=ids[5]
        val facts=runBlocking{repo.snapshot(repo.dao.book(b)!!).first()}
        val savedBills=runBlocking{repo.db.financialDao().bills(b,plan)}
        for((theme,language) in testLooks(this@FinancialProductionOverviewTest)) {
            runBlocking{repo.updateAppearance(b,theme,language);app.preferences.select(b)}
            ActivityScenario.launch(MainActivity::class.java).use {
                tap("nav-accounts");tap("account-$card");tap("account-financial")
                ui.onNodeWithTag("lead-title").assertTextEquals("三井住友")
                ui.onNodeWithTag("lead-amount").assert(hasText("¥32,000",substring=true))
                ui.onNodeWithTag("analysis-tab-plans").assert(hasText(if(language=="ja")"分割/リボ"else"分期/リボ",substring=true))
                ui.waitUntil(10000){ui.onAllNodes(hasText("¥208,000") and hasAnyAncestor(hasTestTag("finance-card-funding"))).fetchSemanticsNodes().isNotEmpty()}
                val funding=ui.onNodeWithTag("finance-funding-${ids[7]}")
                funding.performScrollTo()
                for(value in listOf("¥18,400","¥13,600","¥-13,600","≈ ¥1,900"))funding.assert(hasAnyDescendant(hasText(value)))
                val actual=ui.onNode(hasText("¥18,400") and hasAnyAncestor(hasTestTag("finance-funding-${ids[7]}")))
                actual.performScrollTo();actual.assertIsDisplayed();shot("$theme-$language-credit-funding-start")
                val estimated=ui.onNode(hasText("≈ ¥1,900") and hasAnyAncestor(hasTestTag("finance-funding-${ids[7]}")))
                estimated.performScrollTo();estimated.assertIsDisplayed()
                shot("$theme-$language-credit-funding")
                ui.onNodeWithTag("finance-card-funding").assert(hasAnyDescendant(hasText("¥208,000")))
                ui.onNodeWithTag("finance-card-funding").assert(hasAnyDescendant(hasText("¥100,000 / ¥150,000")))
                ui.onAllNodes(hasText("2026-10-20 18:00 · Asia/Tokyo") and hasAnyAncestor(hasTestTag("finance-card-funding"))).assertCountEquals(2)
                ui.onNodeWithTag("page-scroll").performSemanticsAction(SemanticsActions.ScrollBy){it(0f,-100000f)}
                shot("$theme-$language-credit-cover")
                tap("analysis-tab-statement")
                ui.onNodeWithTag("lead-title").assertTextEquals("三井住友")
                ui.onNodeWithTag("finance-statement-amount").assertTextEquals("¥32,000")
                ui.onNodeWithTag("finance-statement-total").assert(hasAnyDescendant(hasText("¥30,000")))
                ui.onNodeWithTag("finance-statement-total").assert(hasAnyDescendant(hasText("¥2,000")))
                ui.onNodeWithTag("page-scroll").performSemanticsAction(SemanticsActions.ScrollBy){it(0f,-100000f)}
                shot("$theme-$language-credit-statement-cover")
                val statementAmount=ui.onNodeWithTag("finance-statement-amount")
                center(statementAmount);statementAmount.assertIsDisplayed();shot("$theme-$language-credit-statement-amount")
                val statementFee=ui.onNode(hasText("¥2,000") and hasAnyAncestor(hasTestTag("finance-statement-total")))
                center(statementFee);statementFee.assertIsDisplayed();shot("$theme-$language-credit-statement")
                tap("finance-statement-next");ui.onNodeWithTag("finance-statement-amount").assertTextEquals("—")
                tap("finance-statement-previous");ui.onNodeWithTag("finance-statement-amount").assertTextEquals("¥32,000")
                tap("analysis-tab-plans");ui.onNodeWithTag("lead-title").assertTextEquals("三井住友")
                ui.onNodeWithTag("finance-progress-${ids[6]}").performScrollTo();shot("$theme-$language-credit-plans")
                tap("analysis-tab-rules");ui.onNodeWithTag("lead-title").assertTextEquals("三井住友")
                ui.onNodeWithTag("finance-mode-INSTALLMENT").performScrollTo()
                ui.onAllNodesWithTag("finance-rule-incomplete").assertCountEquals(2)
                shot("$theme-$language-credit-rules")
                tap("analysis-tab-limits")
                ui.waitUntil(10000){ui.onAllNodesWithTag("limit-estimate").fetchSemanticsNodes().isNotEmpty()}
                ui.onNodeWithTag("limit-estimate").assert(hasAnyDescendant(hasText("¥208,000")))
                ui.onNodeWithTag("limit-estimate").performScrollTo();shot("$theme-$language-credit-limits")
                tap("analysis-tab-movements");tap("finance-add-repayment")
                shot("$theme-$language-repayment-open")
                tap("select-trigger-"+(if(language=="ja")"実際の支払口座"else"实际付款账户"))
                shot("$theme-$language-repayment-accounts")
                ui.onNode(hasText("人民币生活账户 · CNY") and hasAnyAncestor(hasTestTag("select-menu-frame"))).performScrollTo().performClick()
                val paid=ui.onNodeWithTag("field-"+(if(language=="ja")"実出金総額"else"实际扣款总额")+" · CNY")
                paid.performScrollTo();paid.assertTextEquals("")
                ui.onNodeWithTag("field-"+(if(language=="ja")"返済先の実受取額"else"平台实际收到")+" · JPY").assertTextEquals("")
                ui.onNodeWithTag("finance-repay-cleared").assertIsOff()
                shot("$theme-$language-repayment-currencies")
                tap("date-field-0");ui.onAllNodesWithTag("shared-calendar").assertCountEquals(1)
                tap("date-field-1");ui.onAllNodesWithTag("shared-calendar").assertCountEquals(1)
                ui.onNodeWithTag("shared-calendar").performScrollTo();shot("$theme-$language-repayment-calendar")
                tap("calendar-done")
                if(args.getString("realIme")=="true") {
                    paid.performScrollTo().performClick()
                    ui.waitUntil(10000){android.view.inspector.WindowInspector.getGlobalWindowViews().any{androidx.core.view.ViewCompat.getRootWindowInsets(it)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true}}
                    shot("$theme-$language-repayment-ime")
                    InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                }
                tap("sheet-close")
                tap("nav-accounts");tap("account-$loan");tap("account-financial")
                ui.onNodeWithTag("lead-title").assertTextEquals("国内借款 · 我的分期")
                ui.onNodeWithTag("lead-amount").assert(hasText(Money.parse("18000",Currency.CNY).display(),substring=true))
                shot("$theme-$language-loan-cover")
                val row=ui.onNodeWithTag("loan-schedule-bill-$first")
                ui.onNodeWithTag("loan-schedule-scroll-$plan").performScrollTo();ui.waitForIdle()
                if(unknown)ui.onAllNodes(hasText(if(language=="ja")"不明"else"未知") and hasAnyAncestor(hasTestTag("loan-schedule-bill-$first"))).assertCountEquals(2)
                else {
                    row.assert(hasAnyDescendant(hasText(Money.parse("10000",Currency.CNY).display())))
                    row.assert(hasAnyDescendant(hasText(Money.parse("8000",Currency.CNY).display())))
                }
                val primary=if(unknown)listOf("2026.10",if(language=="ja")"不明"else"未知")else listOf("2026.10",Money.parse("8000",Currency.CNY).display(),Money.parse("500",Currency.CNY).display(),Money.parse("10000",Currency.CNY).display())
                for(value in primary) {
                    val matches=ui.onAllNodes(hasText(value) and hasAnyAncestor(hasTestTag("loan-schedule-bill-$first")))
                    for(i in matches.fetchSemanticsNodes().indices)matches[i].performSemanticsAction(SemanticsActions.GetTextLayoutResult){action->
                        val results=mutableListOf<androidx.compose.ui.text.TextLayoutResult>();action(results)
                        assertEquals("$theme $language $value must remain on one line",1,results.single().lineCount)
                    }
                }
                ui.onNodeWithTag("loan-schedule-scroll-$plan").performSemanticsAction(SemanticsActions.ScrollBy){it(-10000f,0f)}
                shot("$theme-$language-loan-schedule"+if(unknown)"-unknown"else"")
                if(!unknown) {
                    val last=ui.onNode(hasText(Money.parse("10000",Currency.CNY).display()) and hasAnyAncestor(hasTestTag("loan-schedule-bill-$first")))
                    ui.onNodeWithTag("loan-schedule-scroll-$plan").performTouchInput{swipeLeft()};ui.waitForIdle()
                    last.assertIsDisplayed()
                    shot("$theme-$language-loan-schedule-end")
                }
                tap("loan-schedule-open")
                ui.onNodeWithTag("finance-progress-$plan").assertExists()
                tap("analysis-tab-overview")
            }
            val now=runBlocking{repo.snapshot(repo.dao.book(b)!!).first()}
            assertEquals(facts.events,now.events);assertEquals(facts.postings,now.postings)
            assertEquals(18400L,now.balance(now.accounts.first{it.id==ids[7]}));assertEquals(facts.expectedIncomes,now.expectedIncomes)
            assertEquals(1800000L,now.balance(now.accounts.first{it.id==loan}));assertEquals(300000L,now.balance(now.accounts.first{it.id==bank}))
            assertEquals(savedBills,runBlocking{repo.db.financialDao().bills(b,plan)})
        }
    }
    @Test fun actualForecastBeginsWithThemedCoverAndBudgetBeforeAccountRisks() {
        val args=InstrumentationRegistry.getArguments()
        val ids=runBlocking {
            val b=repo.createBook("未来资金",Currency.JPY,"magazine","zh",startDate="2026-10-01");book=b
            repo.updateBook(repo.dao.book(b)!!.copy(initialized=true,budgetMinor=1000))
            val bank=repo.saveAccount(b,"引落銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,100),"2026-10-01")
            val card=repo.saveAccount(b,"カード",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,300),"2026-10-01")
            val plan=repo.commitManualFinancialPlan(repo.previewManualFinancialPlan(b,ManualFinancialPlanInput(card,"本期账单",FinanceMode.SINGLE,
                listOf(KnownBillInput("2026-10-25",1500,300,1200,bank)),300,complete=true,opening=true)))!!
            repo.commitExpectedIncome(repo.previewExpectedIncome(b,ExpectedIncomeInput("給与見込み",Money(Currency.JPY,1600),"2026-10-24",bank)))
            listOf(b,bank,card,plan)
        }
        val (b,bank,card,plan)=ids
        val original=runBlocking{repo.snapshot(repo.dao.book(b)!!).first()}
        val bills=runBlocking{repo.db.financialDao().bills(b,plan)}
        for((theme,language) in testLooks(this@FinancialProductionOverviewTest)) {
            runBlocking{repo.updateAppearance(b,theme,language);app.preferences.select(b)}
            ActivityScenario.launch(MainActivity::class.java).use {
                tap("nav-accounts");tap("accounts-forecast")
                ui.waitUntil(10000){ui.onAllNodesWithTag("forecast-budget").fetchSemanticsNodes().isNotEmpty()}
                ui.onNodeWithTag("lead-title").assertTextEquals(if(language=="ja")"少し先の、負担を見よう。"else"提前一点，看见压力。")
                ui.onNodeWithTag("forecast-budget-amount").assertTextEquals("¥200")
                val lead=ui.onNodeWithTag("lead-title").getUnclippedBoundsInRoot()
                val budget=ui.onNodeWithTag("forecast-budget").getUnclippedBoundsInRoot()
                assertTrue("cover precedes the budget",lead.top<budget.top)
                assertTrue("budget precedes account risk",budget.top<ui.onNodeWithTag("cash-risk-$bank").getUnclippedBoundsInRoot().top)
                ui.onNodeWithTag("page-scroll").performSemanticsAction(SemanticsActions.ScrollBy){it(0f,-100000f)}
                shot("$theme-$language-forecast-cover")
                ui.onNodeWithTag("forecast-budget-amount").performScrollTo();ui.onNodeWithTag("forecast-budget-amount").assertIsDisplayed()
                shot("$theme-$language-forecast-budget")
                tap("forecast-budget-details")
                ui.onNodeWithTag("forecast-budget").assert(hasAnyDescendant(hasText("¥1,200")))
                shot("$theme-$language-forecast-basis")
                val risk=ui.onNodeWithTag("cash-risk-$bank");risk.performScrollTo()
                risk.assert(hasAnyDescendant(hasText("¥1,400")));risk.assert(hasAnyDescendant(hasText("≈ ¥0")))
                shot("$theme-$language-forecast-accounts")
                val timeline=ui.onNodeWithTag("cash-bill:"+bills.single().id)
                timeline.performScrollTo()
                timeline.assert(hasAnyDescendant(hasText("¥-1,400",substring=true)))
                timeline.assert(hasAnyDescendant(hasText("≈ ¥200",substring=true)))
                shot("$theme-$language-forecast-timeline")
            }
            val now=runBlocking{repo.snapshot(repo.dao.book(b)!!).first()}
            assertEquals(original.events,now.events);assertEquals(original.postings,now.postings);assertEquals(original.expectedIncomes,now.expectedIncomes)
            assertEquals(100L,now.balance(now.accounts.first{it.id==bank}));assertEquals(300L,now.balance(now.accounts.first{it.id==card}))
            assertEquals(bills,runBlocking{repo.db.financialDao().bills(b,plan)})
        }
    }

    @Test fun importantItemsLinkRealObligationsRisksAndEmptyBooksWithoutRecordingMoney() {
        val args=InstrumentationRegistry.getArguments()
        val ids=runBlocking {
            val b=repo.createBook("重要事项核对",Currency.JPY,"magazine","zh",startDate="2026-10-01");book=b
            repo.updateBook(repo.dao.book(b)!!.copy(initialized=true,budgetMinor=1000))
            val bank=repo.saveAccount(b,"引落銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,100),"2026-10-01")
            repo.saveAccount(b,"他口座",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
            val card=repo.saveAccount(b,"カード",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,300),"2026-10-01")
            val category=repo.saveCategory(b,"固定費",EntryKind.EXPENSE,null)
            val recurrence=repo.saveRecurrence(b,RecurrenceInput("通信費",bank,category,Money(Currency.JPY,400),Money(Currency.JPY,400),false,RecurrenceFrequency.MONTHLY,"2026-10-18",endDate="2026-10-31"))
            val occurrence=repo.dao.occurrences(b).first{it.recurrenceId==recurrence}.id
            val plan=repo.commitManualFinancialPlan(repo.previewManualFinancialPlan(b,ManualFinancialPlanInput(card,"本期账单",FinanceMode.SINGLE,
                listOf(KnownBillInput("2026-10-25",1500,300,1200,bank)),300,complete=true,opening=true)))!!
            val bill=repo.db.financialDao().bills(b,plan).single().id
            val rule=FinancialRuleVersion("",b,card,FinanceMode.INSTALLMENT,"2026-10-01","Asia/Tokyo",
                deadlines=listOf(ChangeDeadline(bill,FinanceMode.INSTALLMENT,"2026-10-19","18:00","Asia/Tokyo")))
            repo.commitFinancialRule(repo.previewFinancialRule(b,card,rule))
            repo.commitFinancialRule(repo.previewFinancialRule(b,card,rule.copy(deadlines=rule.deadlines.map{it.copy(date="2026-10-20")})))
            repo.commitExpectedIncome(repo.previewExpectedIncome(b,ExpectedIncomeInput("給与見込み",Money(Currency.JPY,2000),"2026-10-17",bank)))
            listOf(b,bank,card,bill,occurrence)
        }
        val (b,bank,card,bill,occurrence)=ids
        val original=runBlocking{repo.snapshot(repo.dao.book(b)!!).first()}
        fun backToImportant() {
            InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            ui.waitUntil(10000){ui.onAllNodesWithTag("important-payment-bill:$bill").fetchSemanticsNodes().isNotEmpty()}
        }
        for((theme,language) in testLooks(this@FinancialProductionOverviewTest)) {
            runBlocking{if(repo.dao.occurrence(b,occurrence)!!.status=="CANCELLED")repo.restoreOccurrence(b,occurrence);repo.updateAppearance(b,theme,language);app.preferences.select(b)}
            ActivityScenario.launch(MainActivity::class.java).use {
                tap("home-important")
                ui.waitUntil(10000){ui.onAllNodesWithTag("important-deadline-$bill-INSTALLMENT").fetchSemanticsNodes().isNotEmpty()}
                ui.onAllNodesWithTag("important-deadline-$bill-INSTALLMENT").assertCountEquals(1)
                ui.onNodeWithTag("important-deadline-$bill-INSTALLMENT").assert(hasText("2026-10-20 18:00",substring=true))
                ui.onNodeWithTag("important-payment-bill:$bill").assert(hasText("¥1,500"))
                ui.onNodeWithTag("important-budget").assert(hasText("¥600"))
                ui.onNodeWithTag("important-risk-$bank").assert(hasText("¥1,800"))
                ui.onNodeWithTag("important-risk-$bank").assert(hasText("≈ ¥0",substring=true))
                ui.onNodeWithTag("important-payment-occurrence:$occurrence").assertExists()
                ui.onNodeWithTag("page-scroll").performSemanticsAction(SemanticsActions.ScrollBy){it(0f,-100000f)}
                shot("$theme-$language-important-cover")
                center(ui.onNodeWithTag("important-deadline-$bill-INSTALLMENT"));shot("$theme-$language-important-deadline")
                tap("important-deadline-$bill-INSTALLMENT")
                ui.onNodeWithTag("statement-bill-$bill").assertExists();backToImportant()
                tap("important-payment-bill:$bill");ui.onNodeWithTag("statement-bill-$bill").assertExists();backToImportant()
                tap("important-payment-occurrence:$occurrence")
                tap("cancel-occurrence-$occurrence")
                ui.waitUntil(10000){runBlocking{repo.dao.occurrence(b,occurrence)?.status=="CANCELLED"}}
                backToImportant()
                ui.onAllNodesWithTag("important-payment-occurrence:$occurrence").assertCountEquals(0)
                ui.onNodeWithTag("important-budget").assert(hasText("¥200"))
                center(ui.onNodeWithTag("important-risk-$bank"));shot("$theme-$language-important-risks")
                tap("important-risk-$bank");ui.onNodeWithTag("risk-transfer-$bank").assertExists();tap("sheet-close")
                tap("important-budget");ui.onNodeWithTag("forecast-budget-amount").assertTextEquals("¥200");backToImportant()
                ui.onNodeWithTag("nav-accounts").performClick();tap("accounts-important")
                ui.onNodeWithTag("important-payment-bill:$bill").assertExists()
                val empty=runBlocking {
                    val id=repo.createBook("空白核对",Currency.JPY,theme,language,startDate="2026-10-01");additionalBooks+=id
                    repo.updateBook(repo.dao.book(id)!!.copy(initialized=true));app.preferences.select(id);id
                }
                ui.waitUntil(10000){ui.onAllNodesWithText("空白核对").fetchSemanticsNodes().isNotEmpty()}
                ui.onNodeWithTag("nav-home").performClick();tap("home-important")
                ui.waitUntil(10000){ui.onAllNodesWithTag("important-empty").fetchSemanticsNodes().isNotEmpty()}
                ui.onAllNodesWithTag("important-payment-bill:$bill").assertCountEquals(0)
                ui.onAllNodesWithTag("important-risk-$bank").assertCountEquals(0)
                shot("$theme-$language-important-empty")
                assertTrue(runBlocking{repo.snapshot(repo.dao.book(empty)!!).first().events.isEmpty()})
            }
            val now=runBlocking{repo.snapshot(repo.dao.book(b)!!).first()}
            assertEquals(original.events,now.events);assertEquals(original.postings,now.postings);assertEquals(original.expectedIncomes,now.expectedIncomes)
            assertEquals(100L,now.balance(now.accounts.first{it.id==bank}));assertEquals(300L,now.balance(now.accounts.first{it.id==card}))
        }
    }

}
