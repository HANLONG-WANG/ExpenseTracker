package com.yohaku.expensetracker

import com.yohaku.expensetracker.data.db.PostingEntity
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.Currency
import com.yohaku.expensetracker.domain.model.Money
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class PendingRepaymentPaymentsTest {
    @Test fun paidAwaitingClearingDoesNotReduceDebtButReducesBillFunding() {
        val x=CashProjectionFixture()
        val paid=x.pending(x.snapshot(),300)
        assertEquals(listOf(Money(Currency.JPY,300)),paid.pendingRepaymentPaidAmounts(x.card.id,"2026-10-15"))
        assertEquals(800L,paid.balance(x.card))
        assertEquals(700L,paid.cashBalanceAt(x.bank,"2026-10-15"))
        val row=paid.financialAccountSummary(x.graph,x.card.id,"2026-10-15").rows.single()
        assertEquals(800L,row.outstanding.totalMinor)
        assertEquals(300L,row.pendingTotalMinor)
        assertEquals(500L,row.cashRequiredMinor)
        val timeline=paid.cashTimeline(x.graph,LocalDate.parse("2026-10-15"),LocalDate.parse("2026-10-31"))
        assertEquals(-500L,timeline.steps.single{it.item.id=="bill:${x.bill.id}"}.item.accountDeltaMinor)
        val cleared=paid.copy(repayments=paid.repayments.map{it.copy(platformConfirmed=true,clearingDate="2026-10-15")},
            postings=paid.postings+PostingEntity("b","cleared","payment",x.card.id,-300,"JPY","2026-10-15"))
        assertTrue(cleared.pendingRepaymentPaidAmounts(x.card.id,"2026-10-15").isEmpty())
        assertEquals(500L,cleared.balance(x.card))
    }

    @Test fun unallocatedPaymentsRemainVisibleInSourceCurrenciesAndFollowRecordLifecycle() {
        val x=CashProjectionFixture()
        val paid=x.pending(x.snapshot(),300)
        val foreign=paid.events.single().copy(id="foreign",accountId=x.payer.id,currency="CNY",amountMinor=1500)
        val mixed=paid.copy(accounts=paid.accounts.map{if(it.id==x.payer.id)it.copy(currency="CNY")else it},
            events=paid.events+foreign,repaymentAllocations=emptyList(),
            postings=paid.postings+PostingEntity("b","foreign-debit",foreign.id,x.payer.id,-1500,"CNY",foreign.date),
            repayments=paid.repayments+paid.repayments.single().copy(eventId=foreign.id,principalMinor=null,receivedMinor=null))
        assertEquals(listOf(Money(Currency.JPY,300),Money(Currency.CNY,1500)),mixed.pendingRepaymentPaidAmounts(x.card.id,"2026-10-15"))
        assertTrue(mixed.pendingRepaymentPaidAmounts(x.bank.id,"2026-10-15").isEmpty())
        assertTrue(mixed.pendingRepaymentPaidAmounts(x.card.id,"2026-10-14").isEmpty())
        val deleted=mixed.copy(events=mixed.events.map{it.copy(deletedAt=1L)})
        assertTrue(deleted.pendingRepaymentPaidAmounts(x.card.id,"2026-10-15").isEmpty())
    }
}
