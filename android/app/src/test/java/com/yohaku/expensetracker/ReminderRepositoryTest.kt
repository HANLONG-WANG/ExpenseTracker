package com.yohaku.expensetracker

import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.reminders.ReminderItem
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ReminderRepositoryTest {
    @Test fun repaymentRemindersUseGroupedOutstandingAndLocalizedAccountTitles() {
        val fixture=CashProjectionFixture(1000)
        val s=fixture.snapshot()
        val first=s.importantPayments(fixture.graph,s.cashTimeline(fixture.graph,
            LocalDate.parse("2026-10-15"),LocalDate.parse("2026-11-30"))).single()
        val second=first.copy(item=first.item.copy(id="bill:second",amountMinor=0,issue="PENDING_CLEARING"),outstandingMinor=400)
        val otherPayer=first.copy(item=first.item.copy(id="bill:other",accountId="payer"))
        val recurring=first.copy(item=first.item.copy(id="occurrence:one",kind="RECURRENCE",sourceId="recurrence",name="Rent"))
        val cardForecast=recurring.copy(item=recurring.item.copy(id="card-occurrence:one",kind="CARD_RECURRENCE"))
        val payments=listOf(first,second,otherPayer,recurring,cardForecast)
        for(language in listOf("zh","ja")) {
            val snapshot=s.copy(book=s.book.copy(language=language))
            val reminders=snapshot.paymentReminders(payments)
            val repayments=reminders.filter{it.kind=="REPAYMENT"}
            assertEquals(2,repayments.size)
            val grouped=repayments.first{it.key=="repayment:card:2026-10-20:bank:JPY"}
            assertEquals(1400L,grouped.amountMinor)
            assertEquals(if(language=="ja")"請求2件"else"2笔账单",grouped.title)
            assertEquals(fixture.card.name,grouped.account);assertEquals("card",grouped.targetAccountId)
            assertEquals("2026-10-20",grouped.date);assertEquals("JPY",grouped.currency)
            assertTrue(grouped.estimated)
            assertEquals(first.item.name,repayments.last().title);assertFalse(repayments.last().estimated)
            assertEquals(ReminderItem(s.book.id,"occurrence:one","RECURRING","2026-10-20","JPY",1000,"Rent",fixture.bank.name),
                reminders.single{it.kind=="RECURRING"})
        }
        assertTrue(s.paymentReminders(listOf(first.copy(item=first.item.copy(estimated=true)))).single().estimated)
    }
}
