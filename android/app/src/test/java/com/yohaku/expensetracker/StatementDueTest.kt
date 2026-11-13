package com.yohaku.expensetracker

import com.yohaku.expensetracker.data.db.AccountEntity
import com.yohaku.expensetracker.data.db.FinancialRuleEntity
import com.yohaku.expensetracker.data.repository.statementDuePeriods
import com.yohaku.expensetracker.domain.finance.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class StatementDueTest {
    private fun d(s:String)=LocalDate.parse(s)
    private val bank=AccountEntity("b","bank","銀行","BANK","JPY",0,"2026-09-10")
    private val card=AccountEntity("b","card","カード","CREDIT","JPY",0,"2026-09-20")
    private val cash=AccountEntity("b","cash","現金","CASH","JPY",0,"2026-01-01")
    private val closing15=FinancialRuleEntity("b","r1","card","SINGLE",1,"2026-01-01",Json.encodeToString(
        FinancialRuleVersion("r1","b","card",FinanceMode.SINGLE,"2026-01-01","Asia/Tokyo",billing=BillingRule(15,true,1,27,"bank"))),0)
    private fun due(today:String,accounts:List<AccountEntity> =listOf(bank,card,cash),rules:List<FinancialRuleEntity> =listOf(closing15),
        coverage:Map<String,List<Pair<LocalDate,LocalDate>>> =emptyMap(),skipped:Set<Pair<String,String>> =emptySet())=
        statementDuePeriods(accounts,rules,coverage,skipped,d(today)).map{"${it.accountId} ${it.start}..${it.end}"}

    @Test fun partialFirstPeriodsAndEveryClosedPeriodAppearAfterTheDelay() {
        assertEquals(listOf("bank 2026-09-10..2026-09-30","bank 2026-10-01..2026-10-31","card 2026-09-20..2026-10-15"),due("2026-11-02"))
        // One day after month end the bank's October is not yet due; cash never is.
        assertEquals(listOf("bank 2026-09-10..2026-09-30","card 2026-09-20..2026-10-15"),due("2026-11-01"))
        assertEquals(emptyList<String>(),due("2026-10-01"))
    }

    @Test fun closingDayOutsideCurrentBillEndsTheDayBeforeAndMissingRuleUsesMonths() {
        val excluded=closing15.copy(json=closing15.json.replace("\"closingDayInCurrentBill\":true","\"closingDayInCurrentBill\":false"))
        assertEquals(listOf("card 2026-09-20..2026-10-14","card 2026-10-15..2026-11-14"),due("2026-11-16",listOf(card),listOf(excluded)))
        assertEquals(listOf("card 2026-09-20..2026-09-30","card 2026-10-01..2026-10-31"),due("2026-11-13",listOf(card),emptyList()))
    }

    @Test fun onlyFullyCoveredOrSkippedPeriodsDisappear() {
        val coverage=mapOf("bank" to listOf(d("2026-09-16") to d("2026-10-05"),d("2026-09-01") to d("2026-09-15")))
        assertEquals(listOf("bank 2026-10-01..2026-10-31"),due("2026-11-13",listOf(bank),coverage=coverage))
        assertEquals(emptyList<String>(),due("2026-11-13",listOf(bank),coverage=coverage,skipped=setOf("bank" to "2026-10-31")))
    }
}
