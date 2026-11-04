package com.yohaku.expensetracker

import com.yohaku.expensetracker.data.db.AccountEntity
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.finance.*
import org.junit.Assert.*
import org.junit.Test

class FinancialPoolOpeningTest {
    @Test fun dateOrEitherDirectionOfAmountChangeRequiresExplicitPoolReview() {
        val account=AccountEntity("b","loan","プロミス","LOAN","JPY",28310,"2026-11-01")
        val facts=FinancialPoolFacts(account.openingDate,account.openingMinor,emptyList(),emptyList(),emptyList(),emptyList(),emptyList())
        val input=FinancialPredictionInput(account.id,"pool",FinanceMode.CREDIT_LINE,FinancialScheduleTerms(account.openingDate,emptyList()),pool=FinancialPoolInput(account.openingMinor,PoolSourceDate.TRANSACTION,facts=facts))
        assertFalse(input.poolOpeningChanged(account))
        for(next in listOf(account.copy(openingDate="2026-10-31"),account.copy(openingMinor=28000),account.copy(openingMinor=30000))) {
            assertTrue(input.poolOpeningChanged(next))
            val accepted=input.copy(terms=input.terms.copy(agreementDate=next.openingDate),pool=input.pool!!.copy(openingMinor=next.openingMinor,facts=facts.copy(openingDate=next.openingDate,accountOpeningMinor=next.openingMinor)))
            assertFalse(accepted.poolOpeningChanged(next))
        }
    }
}
