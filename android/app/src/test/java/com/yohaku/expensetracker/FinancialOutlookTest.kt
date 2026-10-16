package com.yohaku.expensetracker

import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import org.junit.Assert.*
import org.junit.Test

class FinancialOutlookTest {
    private val bank=AccountEntity("b","bank","引落","BANK","JPY",100,"2026-10-01")
    private fun item(id:String,date:String,delta:Long?,incoming:Boolean=false)=CashTimelineItem(id,"BILL",id,date,id,bank.id,"JPY",delta?.let{kotlin.math.abs(it)},incoming,delta)
    @Test fun preparationGapAndFutureMaximumAreSeparateAndIncomeDoesNotMaskBaseline() {
        val timeline=CashTimeline("2026-10-15","2026-11-30",listOf(CashAccountOpening(bank,100),CashAccountOpening(bank.copy(id="other"),10000)),listOf(
            CashTimelineStep(item("one","2026-11-05",-250),-150,450),
            CashTimelineStep(item("two","2026-11-05",-50),-200,400),
            CashTimelineStep(item("later","2026-11-20",-300),-500,100)))
        val risk=timeline.accountRisks().first()
        assertEquals(300L,risk.nextNeedMinor);assertEquals(200L,risk.currentGapMinor)
        assertEquals(500L,risk.maximumGapMinor);assertEquals(0L,risk.includingIncomeGapMinor)
        assertEquals("2026-11-05",risk.firstRiskDate)
        assertEquals(0L,timeline.accountRisks().last().maximumGapMinor)
    }
    @Test fun laterUnknownDoesNotEraseAnEarlierKnownDeficitOrBecomeZero() {
        val timeline=CashTimeline("2026-10-15","2026-11-30",listOf(CashAccountOpening(bank,100)),listOf(
            CashTimelineStep(item("known","2026-11-05",-250),-150,-150),
            CashTimelineStep(item("unknown","2026-11-20",null),null,null)))
        val risk=timeline.accountRisks().single()
        assertEquals(150L,risk.currentGapMinor);assertNull(risk.maximumGapMinor)
        assertEquals("2026-11-05",risk.firstRiskDate);assertEquals("2026-11-20",risk.incompleteDate)
    }
}
