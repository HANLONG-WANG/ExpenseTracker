package com.yohaku.expensetracker

import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.finance.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
    @Test fun onlyCurrentActivePoolSharesResolveUnallocatedPrincipal() {
        val x=CashProjectionFixture()
        val s=x.pending(x.snapshot(),1540).let{it.copy(repayments=it.repayments.map{p->p.copy(platformConfirmed=true,clearingDate="2026-11-02")},repaymentAllocations=emptyList())}
        val facts=FinancialPoolFacts("2026-10-01",800,emptyList(),emptyList(),emptyList(),emptyList(),emptyList())
        fun graph(share:Long,active:Boolean=true)=x.graph.copy(plans=listOf(x.plan.copy(origin="POOL",active=active,termsJson=Json.encodeToString(FinancialPredictionInput(x.card.id,"pool",FinanceMode.REVOLVING,FinancialScheduleTerms("2026-10-01",emptyList()),pool=FinancialPoolInput(800,PoolSourceDate.TRANSACTION,mapOf("payment" to share),facts=facts))))))
        assertEquals(1540L,s.unallocatedRepaymentPrincipal(x.card.id))
        assertEquals(0L,s.repaymentPrincipalNeedingAllocation(graph(1540),x.card.id,emptySet()))
        assertEquals(540L,s.repaymentPrincipalNeedingAllocation(graph(1000),x.card.id,emptySet()))
        assertEquals(1540L,s.repaymentPrincipalNeedingAllocation(graph(1540,false),x.card.id,emptySet()))
        assertEquals(1540L,s.repaymentPrincipalNeedingAllocation(graph(1540),x.card.id,setOf(x.plan.id)))
        val timeline=CashTimeline("2026-11-04","2027-01-31",emptyList(),emptyList())
        assertFalse(s.financialAttention(graph(1540),timeline.asOf,timeline,emptySet()).any{it.reason=="UNALLOCATED_CLEARING"})
        assertTrue(s.financialAttention(graph(1540),timeline.asOf,timeline,setOf(x.plan.id)).any{it.reason=="UNALLOCATED_CLEARING"})
    }
}
