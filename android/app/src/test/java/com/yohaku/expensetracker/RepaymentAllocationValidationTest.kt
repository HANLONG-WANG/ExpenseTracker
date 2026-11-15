package com.yohaku.expensetracker

import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class RepaymentAllocationValidationTest {
    private val x=CashProjectionFixture(principal=1000)
    private fun allocation(id:String,bill:String?=x.bill.id,contract:String?=null,principal:Long?=1000)=
        FinancialRepaymentAllocationEntity("b",id,id,bill,contract,principal,0,0)
    private fun contract(principal:Long)=DebtContractEntity("b","contract",x.card.id,"契約","FIXED_LOAN","JPY",principal,"2026-10-01",null,null,null,null,null,null,null,null,0)
    private fun restored(rows:List<FinancialRepaymentAllocationEntity>):BookSnapshot {
        val base=x.pending(x.snapshot(),1000)
        val event=base.events.single();val repayment=base.repayments.single()
        return base.copy(events=rows.map{event.copy(id=it.eventId)},repayments=rows.map{repayment.copy(eventId=it.eventId,platformConfirmed=true,clearingDate="2026-10-15")},repaymentAllocations=rows)
    }
    @Test fun pendingKnownComponentsAndCashBoundAllAllocationsIncludingPartialRows() {
        val rows=listOf(allocation("a"),allocation("b",bill="other"))
        assertFalse(repaymentAllocationsWithinAmounts(rows,1000,null,null,null,null))
        assertFalse(repaymentAllocationsWithinAmounts(rows,null,null,null,1000,null))
        assertFalse(repaymentAllocationsWithinAmounts(rows,null,null,null,null,1000))
        assertFalse(repaymentAllocationsWithinAmounts(listOf(rows[0].copy(principalMinor=null,interestMinor=20)),null,10,null,null,null))
        assertFalse(repaymentAllocationsWithinAmounts(listOf(rows[0].copy(feeMinor=20)),null,null,10,null,null))
        assertTrue(repaymentAllocationsWithinAmounts(listOf(rows[0].copy(principalMinor=600),rows[1].copy(principalMinor=400)),1000,0,0,1000,1000))
        assertTrue(repaymentAllocationsWithinAmounts(listOf(rows[0].copy(principalMinor=null)),null,null,null,null,1000))
    }
    @Test fun restorationChecksBillAndContractAgainstOtherLivePaymentsButExcludesItself() {
        val a=allocation("a");val b=allocation("b")
        val s=restored(listOf(a,b))
        assertThrows(IllegalArgumentException::class.java){s.validateRepaymentAllocationCapacity(x.graph,"a",listOf(a),Json)}
        s.copy(events=s.events.filter{it.id=="a"}).validateRepaymentAllocationCapacity(x.graph,"a",listOf(a),Json)
        val c=contract(1000);val ca=a.copy(billId=null,contractId=c.id);val cb=b.copy(billId=null,contractId=c.id)
        val contracted=restored(listOf(ca,cb)).copy(contracts=listOf(c));val f=x.graph.copy(contracts=listOf(c))
        assertThrows(IllegalArgumentException::class.java){contracted.validateRepaymentAllocationCapacity(f,"a",listOf(ca),Json)}
        contracted.copy(events=contracted.events.filter{it.id=="a"}).validateRepaymentAllocationCapacity(f,"a",listOf(ca),Json)
    }
    @Test fun restorationAlsoChecksTransferredPoolPrincipalWhenBillAndContractHaveRoom() {
        val c=contract(3000)
        val pool=x.plan.copy(origin="POOL",mode="REVOLVING")
        val retained=pool.copy(id="retained",origin="POOL_RETAINED")
        val baseline=FinancialArrangementBaseline(1000,"2026-10-01",emptyList(),c.id,emptyList(),emptyList(),emptyList(),0)
        val arrangement=FinancialArrangementEntity("b","move","old",retained.id,"2026-10-01","2026-10-01T00:00:00Z",1000,0,"RETAIN",false,Json.encodeToString(baseline),"{}","{}",0)
        val bill=x.bill.copy(confirmedPrincipalMinor=3000,confirmedTotalMinor=3000)
        val f=x.graph.copy(plans=listOf(pool,retained),bills=listOf(bill),contracts=listOf(c),arrangements=listOf(arrangement))
        val a=allocation("a",contract=c.id);val b=allocation("b",contract=c.id)
        val s=restored(listOf(a,b)).copy(contracts=listOf(c))
        val failure=assertThrows(IllegalArgumentException::class.java){s.validateRepaymentAllocationCapacity(f,"a",listOf(a),Json)}
        assertTrue(failure.message!!.contains("池"))
        s.copy(events=s.events.filter{it.id=="a"}).validateRepaymentAllocationCapacity(f,"a",listOf(a),Json)
    }
}
