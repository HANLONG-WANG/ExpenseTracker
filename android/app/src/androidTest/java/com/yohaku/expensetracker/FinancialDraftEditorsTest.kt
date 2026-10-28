package com.yohaku.expensetracker

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.finance.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.features.*
import com.yohaku.expensetracker.ui.theme.YohakuTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FinancialDraftEditorsTest {
    @get:Rule val ui=createComposeRule()
    @Test fun everyFinancialEditorRestoresItsOwnUnfinishedInputAndDiscardDoesNotChangeFormalFacts() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        val repo=LedgerRepository(db);val vm=AppViewModel(repo,AppPreferences(context), testReferenceRates(AppPreferences(context),repo.clock))
        try {
            val (s,f,l)=runBlocking {
                val b=repo.createBook("下書きの全フォーム",Currency.JPY,"journal","ja")
                val bank=repo.saveAccount(b,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
                repo.saveAccount(b,"ローン",AccountType.LOAN,Currency.JPY,Money(Currency.JPY,0),"2026-10-01")
                val card=repo.saveAccount(b,"カード",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
                repo.commitManualFinancialPlan(repo.previewManualFinancialPlan(b,ManualFinancialPlanInput(card,"既知",FinanceMode.SINGLE,listOf(KnownBillInput("2026-11-05",10000,10000,0,bank)),principalMinor=10000,opening=true)))
                repo.commitExpectedIncome(repo.previewExpectedIncome(b,ExpectedIncomeInput("予定",Money(Currency.JPY,1000),"2026-10-30",bank)))
                Triple(repo.snapshot(repo.dao.book(b)!!).first(),db.financialDao().graph(b).first()!!,db.creditLimitDao().graph(b).first()!!)
            }
            val a=s.accounts.first{it.type==AccountType.CREDIT.name};val loan=s.accounts.first{it.type==AccountType.LOAN.name};val plan=f.plans.single();val bill=f.bills.single();val income=s.expectedIncomes.single()
            var screen by mutableStateOf<String?>(null)
            val close={screen=null;Unit}
            ui.setContent{YohakuTheme("journal","ja"){Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                when(screen) {
                    "RULE"->FinancialRuleEditor(s,a,FinanceMode.SINGLE,null,vm,close)
                    "MANUAL"->ManualFinancialPlanEditor(s,a,vm,close)
                    "PREDICTION"->FinancialPredictionEditor(s,a,f,null,vm,close)
                    "POOL"->FinancialPoolEditor(s,a,f,null,vm,close)
                    "ARRANGEMENT"->FinancialArrangementEditor(s,a,f,plan,vm,close)
                    "BORROW"->FinancialBorrowingEditor(s,loan,vm,close)
                    "REPAY"->FinancialRepaymentEditor(s,a,f,vm,close)
                    "BILL"->FinancialBillConfirmationEditor(s,a.id,bill,Currency.JPY,vm,close)
                    "LIMIT_CONFIG"->CreditLimitConfigEditor(s,a,null,vm,close)
                    "LIMIT_GROUP"->CreditLimitGroupEditor(s,a,null,l,vm,close)
                    "LIMIT_FACT"->CreditLimitFactEditor(s,a,l,"observation",null,vm,close)
                    "EXPECTED"->ExpectedIncomeEditor(s,null,false,vm,close)
                    "ARRIVAL"->ExpectedIncomeEditor(s,income,true,vm,close)
                }
            }}}
            val cases=listOf(
                "RULE" to "金融取引のタイムゾーン", "MANUAL" to "プラン名", "PREDICTION" to "プラン名", "POOL" to "プラン名", "ARRANGEMENT" to "新プラン名", "BORROW" to "契約名", "REPAY" to "実出金総額 · JPY", "BILL" to "既知の合計", "LIMIT_CONFIG" to "総枠・不明は空欄", "LIMIT_GROUP" to "共通枠の名前", "LIMIT_FACT" to "返済先の利用可能額", "EXPECTED" to "収入予定名", "ARRIVAL" to "実入金額 · JPY")
            for((kind,label) in cases) {
                ui.runOnIdle{screen=kind}
                val field=ui.onNodeWithTag("field-$label")
                ui.waitUntil(10000){ui.onAllNodesWithTag("field-$label").fetchSemanticsNodes().isNotEmpty()}
                field.performScrollTo().performTextReplacement("1234.")
                ui.runOnIdle{screen=null};ui.waitForIdle();ui.runOnIdle{screen=kind}
                ui.waitUntil(10000){ui.onAllNodesWithTag("field-$label").fetchSemanticsNodes().isNotEmpty()}
                field.assertTextEquals("1234.")
                ui.onNodeWithTag("financial-draft-discard").performScrollTo().performClick()
                ui.waitUntil(10000){screen==null&&!vm.busy.value}
            }
            assertTrue(runBlocking{db.financialDao().drafts(s.book.id,a.id).first().isEmpty()})
            assertTrue(runBlocking{db.financialDao().drafts(s.book.id,null).first().isEmpty()})
            val after=runBlocking{repo.snapshot(repo.dao.book(s.book.id)!!).first()}
            assertTrue(after.events.isEmpty());assertEquals(10000L,after.balance(a));assertEquals(1,after.expectedIncomes.size)
        }finally{vm.viewModelScope.cancel();db.close()}
    }
}
