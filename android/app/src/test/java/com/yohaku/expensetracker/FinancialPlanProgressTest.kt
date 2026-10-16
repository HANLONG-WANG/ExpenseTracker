package com.yohaku.expensetracker

import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import org.junit.Assert.*
import org.junit.Test

class FinancialPlanProgressTest {
    private val book=BookEntity("b","帳簿","JPY","2026-10-01")
    private val account=AccountEntity("b","card","カード","CREDIT","JPY",1000,"2026-10-01")
    private val plan=FinancialPlanEntity("b","plan",account.id,"分割","INSTALLMENT","OPENING",null,"JPY",1000,true,0)
    private fun bill(id:String,n:Int,date:String,p:Long?,fee:Long?,total:Long)=FinancialBillEntity("b",id,plan.id,n,null,null,null,null,null,null,null,date,p,fee,total,0)
    private fun graph(bills:List<FinancialBillEntity>)=FinancialGraph(book,emptyList(),listOf(plan),bills,emptyList(),emptyList(),emptyList(),emptyList(),emptyList(),emptyList(),emptyList())
    private fun snapshot()=BookSnapshot(book,listOf(account),emptyList(),emptyList(),emptyList(),null)
    @Test fun remainingPeriodsAndFeesUseOnlyLiveUnpaidObligationsWithoutChangingAccountDebt() {
        val f=graph(listOf(bill("paid",1,"2026-10-05",0,0,0),bill("next",2,"2026-11-05",300,10,310),bill("last",3,"2026-12-05",500,20,520),bill("old",4,"2027-01-05",999,999,1998).copy(active=false)))
        val p=snapshot().financialPlanProgress(f,plan,"2026-11-10")
        assertEquals(3,p.listedPeriods);assertEquals(2,p.unpaidPeriods);assertEquals(1,p.overduePeriods)
        assertEquals(800L,p.principalMinor);assertEquals(30L,p.feeMinor);assertEquals(830L,p.totalMinor);assertEquals(830L,p.cashRequiredMinor)
        assertEquals("2026-12-05",p.lastDate);assertEquals(1000L,snapshot().balance(account))
        val stale=snapshot().financialPlanProgress(f,plan,"2026-11-10",true)
        assertTrue(stale.stale)
    }
    @Test fun unknownSplitsAndOverpaymentDoNotBecomeZeroOrOffsetAnotherUnpaidPeriod() {
        val f=graph(listOf(bill("overpaid",1,"2026-10-05",100,0,100),bill("unknown",2,"2026-11-05",null,null,600)))
        val event=EventEntity("b","paid","REPAYMENT",account.id,null,200,"JPY",0,"2026-10-10","","")
        val payment=FinancialRepaymentEntity("b",event.id,account.id,200,200,0,0,0,"2026-10-10",true,"NORMAL",null,"",null,null)
        val row=FinancialRepaymentAllocationEntity("b","allocation",event.id,"overpaid",null,200,0,0)
        val s=snapshot().copy(events=listOf(event),repayments=listOf(payment),repaymentAllocations=listOf(row))
        val p=s.financialPlanProgress(f,plan.copy(complete=false),"2026-10-25")
        assertEquals(600L,p.totalMinor);assertEquals(1,p.unpaidPeriods);assertNull(p.principalMinor);assertNull(p.feeMinor)
        assertTrue(p.needsReconciliation);assertFalse(p.complete)
    }
}
