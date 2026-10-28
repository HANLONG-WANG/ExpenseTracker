package com.yohaku.expensetracker

import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.finance.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

internal class CashProjectionFixture(val principal:Long=800, val due:String="2026-10-20", bankOpening:Long=1000, payerOpening:Long=100000) {
    val book=BookEntity("b","資金","JPY","2026-10-01")
    val bank=AccountEntity("b","bank","引落","BANK","JPY",bankOpening,"2026-10-01")
    val payer=AccountEntity("b","payer","他口座","BANK","JPY",payerOpening,"2026-10-01")
    val card=AccountEntity("b","card","カード","CREDIT","JPY",principal,"2026-10-01")
    val plan=FinancialPlanEntity("b","plan",card.id,"カード支払","SINGLE","OPENING",null,"JPY",principal,true,0)
    val bill=FinancialBillEntity("b","bill",plan.id,1,bank.id,null,null,null,null,null,null,due,principal,0,principal,0)
    val graph=FinancialGraph(book,emptyList(),listOf(plan),listOf(bill),emptyList(),emptyList(),emptyList(),emptyList(),emptyList(),emptyList(),emptyList())
    fun snapshot()=BookSnapshot(book,listOf(bank,payer,card),listOf(CategoryEntity("b","cat","費用","EXPENSE")),emptyList(),emptyList(),null)
    fun pending(s:BookSnapshot,amount:Long,paymentAccount:String=bank.id):BookSnapshot {
        val event=EventEntity("b","payment","REPAYMENT",paymentAccount,null,amount,"JPY",0,"2026-10-15","","")
        return s.copy(events=s.events+event,postings=s.postings+PostingEntity("b","debit",event.id,paymentAccount,-amount,"JPY",event.date),
            repayments=listOf(FinancialRepaymentEntity("b",event.id,card.id,amount,amount,0,0,0,null,false,"NORMAL",null,"",null,null)),
            repaymentAllocations=listOf(FinancialRepaymentAllocationEntity("b","allocation",event.id,bill.id,null,amount,0,0)))
    }
}
