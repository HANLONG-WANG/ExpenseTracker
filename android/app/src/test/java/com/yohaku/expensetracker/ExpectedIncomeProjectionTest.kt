package com.yohaku.expensetracker

import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.finance.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ExpectedIncomeProjectionTest {
    @Test fun cashTimelineDeduplicatesFuturePostingsBillsReservesAndPendingClearing() {
        val fixture=CashProjectionFixture();val bank=fixture.bank.id;val bill=fixture.bill
        val recurrence=RecurrenceEntity("b","recurrence","固定費",bank,"cat","JPY",400,400,false,"MONTHLY","2026-10-18",null)
        fun occurrenceId(date:String)=java.util.UUID.nameUUIDFromBytes("b/recurrence/$date".toByteArray(Charsets.UTF_8)).toString()
        val occurrence=OccurrenceEntity("b",occurrenceId("2026-10-18"),recurrence.id,"2026-10-18","固定費",bank,"cat","JPY",400,400,false,activityId="activity")
        val event=EventEntity("b","future","EXPENSE",bank,"cat",100,"JPY",100,"2026-10-17","","")
        var s=fixture.snapshot().copy(events=listOf(event),postings=listOf(PostingEntity("b","future-posting",event.id,bank,-100,"JPY",event.date)),
        recurrences=listOf(recurrence),occurrences=listOf(occurrence),features=BookFeaturesEntity("b",true),
        activities=listOf(ActivityEntity("b","activity","シーン","2026-10-01","2026-10-31",null)),
        reserves=listOf(ActivityReserveEntity("b","reserve","activity","固定費の確保","2026-10-18",300,bank,300,"JPY",occurrence.id)),
        expectedIncomes=listOf(ExpectedIncomeEntity("b","income","入金見込み",500,"JPY","2026-10-19",bank,500,updatedAt=0)))
        s=s.copy(storedPostings=s.postings)
        val f=fixture.graph
        var timeline=s.cashTimeline(f,LocalDate.parse("2026-10-15"),LocalDate.parse("2026-10-31"))
        assertEquals(1000L,timeline.openings.first{it.account.id==bank}.actualMinor)
        assertEquals(3,timeline.steps.count{it.item.kind!="EXPECTED_INCOME"})
        var payment=timeline.steps.single{it.item.id=="bill:${bill.id}"}
        assertEquals(-300L,payment.baselineAfterMinor);assertEquals(200L,payment.includingIncomeAfterMinor)
        val risk=timeline.accountRisks().first{it.opening.account.id==bank}
        assertEquals(0L,risk.currentGapMinor);assertEquals(300L,risk.maximumGapMinor);assertEquals(0L,risk.includingIncomeGapMinor)
        assertEquals("2026-10-20",risk.firstRiskDate)
        assertEquals(0L,timeline.accountRisks().first{it.opening.account.id!=bank}.maximumGapMinor)
        val stopped=s.copy(accounts=s.accounts.map{if(it.id==bank)it.copy(active=false)else it}).cashTimeline(f,LocalDate.parse("2026-10-15"),LocalDate.parse("2026-10-31"))
        assertEquals("PAYMENT_ACCOUNT_INACTIVE",stopped.steps.single{it.item.id=="bill:${bill.id}"}.item.issue)
        assertNull(stopped.accountRisks().first{it.opening.account.id==bank}.maximumGapMinor)
        // Already debited cash awaiting platform clearing must not be requested a second time.
        s=fixture.pending(s,300)
        timeline=s.cashTimeline(f,LocalDate.parse("2026-10-15"),LocalDate.parse("2026-10-31"))
        payment=timeline.steps.single{it.item.id=="bill:${bill.id}"}
        assertEquals(-500L,payment.item.accountDeltaMinor);assertEquals(-300L,payment.baselineAfterMinor);assertEquals(800L,s.billOutstanding(bill).totalMinor)
        val unknownClearing=s.copy(repaymentAllocations=s.repaymentAllocations.map{it.copy(principalMinor=null)}).cashTimeline(f,LocalDate.parse("2026-10-15"),LocalDate.parse("2026-10-31"))
        assertNull(unknownClearing.steps.last().baselineAfterMinor);assertEquals("PENDING_CLEARING_AMOUNT",unknownClearing.steps.last().item.issue)
        val income=s.expectedIncomes.single()
        val unknown=s.copy(expectedIncomes=listOf(income.copy(currency="CNY",accountMinor=null))).cashTimeline(f,LocalDate.parse("2026-10-15"),LocalDate.parse("2026-10-31"))
        assertNull(unknown.steps.last().includingIncomeAfterMinor);assertEquals(-300L,unknown.steps.last().baselineAfterMinor)
        assertEquals(3,s.cashTimeline(f,LocalDate.parse("2026-10-15"),LocalDate.parse("2026-12-31")).steps.count{it.item.kind=="RECURRENCE"})
        s=s.copy(occurrences=s.occurrences+occurrence.copy(id=occurrenceId("2026-11-18"),scheduledDate="2026-11-18",status="CANCELLED"))
        assertEquals(2,s.cashTimeline(f,LocalDate.parse("2026-10-15"),LocalDate.parse("2026-12-31")).steps.count{it.item.kind=="RECURRENCE"})
    }
}
