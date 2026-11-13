package com.yohaku.expensetracker.data.repository

import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.domain.finance.*
import com.yohaku.expensetracker.domain.model.AccountType
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.YearMonth

/** One closed statement period of an account; [card] periods end at the billing rule's closing boundary. */
data class StatementDuePeriod(val accountId:String,val start:LocalDate,val end:LocalDate,val card:Boolean)

/** Days after a period ends before it counts as settled enough to reconcile. */
const val STATEMENT_DUE_DELAY_DAYS=2L

/**
 * Closed periods of bank accounts and credit cards that no imported statement fully covers and that the user has not
 * skipped. Periods start at the account's opening date, so a first partial period is included and nothing before the
 * ledger began is asked for. Cards follow the billing rule in effect at each period start; without a complete rule a
 * card falls back to calendar months like a bank account.
 */
fun statementDuePeriods(accounts:List<AccountEntity>,rules:List<FinancialRuleEntity>,coverage:Map<String,List<Pair<LocalDate,LocalDate>>>,
    skipped:Set<Pair<String,String>>,today:LocalDate):List<StatementDuePeriod> = accounts
    .filter{it.active&&it.type in setOf(AccountType.BANK.name,AccountType.CREDIT.name)}
    .flatMap{account->
        val card=account.type==AccountType.CREDIT.name
        val ranges=coverage[account.id].orEmpty().sortedBy{it.first}
        buildList {
            var start=LocalDate.parse(account.openingDate)
            while(true) {
                val end=if(card)cardPeriodEnd(rules,account.id,start)else YearMonth.from(start).atEndOfMonth()
                if(today<end.plusDays(STATEMENT_DUE_DELAY_DAYS))break
                if(!covered(ranges,start,end)&&(account.id to end.toString()) !in skipped)add(StatementDuePeriod(account.id,start,end,card))
                start=end.plusDays(1)
            }
        }
    }

private fun cardPeriodEnd(rules:List<FinancialRuleEntity>,accountId:String,start:LocalDate):LocalDate {
    // Every card mode carries the same closing day; SINGLE is the card's base rule.
    val billing=listOf(FinanceMode.SINGLE,FinanceMode.REVOLVING,FinanceMode.INSTALLMENT).firstNotNullOfOrNull{mode->
        rules.cardRule(accountId,start.toString(),mode.name)?.let{Json.decodeFromString<FinancialRuleVersion>(it.json).billing}?.takeIf{it.issues().isEmpty()}
    }?:return YearMonth.from(start).atEndOfMonth()
    val closing=billingDates(start,billing).closingDate
    return if(billing.closingDayInCurrentBill==true)closing else closing.minusDays(1)
}

/** Imported statement ranges may overlap or adjoin; together they must leave no day of the period uncovered. */
private fun covered(ranges:List<Pair<LocalDate,LocalDate>>,start:LocalDate,end:LocalDate):Boolean {
    var next=start
    for((from,to) in ranges) {
        if(from>next)break
        if(to>=next)next=to.plusDays(1)
        if(next>end)return true
    }
    return false
}
