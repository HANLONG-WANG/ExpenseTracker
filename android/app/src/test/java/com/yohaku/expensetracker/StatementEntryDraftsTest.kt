package com.yohaku.expensetracker

import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.files.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class StatementEntryDraftsTest {
    private val date="2026-11-02"
    private val book=BookEntity("b","book","JPY","2026-11-01")
    private val bank=AccountEntity("b","bank","bank","BANK","JPY",10000,"2026-11-01")
    private fun row(amount:String="15",direction:String="out")=StatementRow("row",date,direction,FileMoney("JPY",amount),"fee")

    @Test fun actualTransferSelectsPostingIncludingSeparateFeePaidBySourceOrThirdAccount() {
        for(payer in listOf("bank","third")) {
            val event=EventEntity("b","transfer","TRANSFER","bank",null,1000,"JPY",0,date,"","",createdAt=0)
            val postings=listOf(PostingEntity("b","transfer:from",event.id,"bank",-1000,"JPY",date,false),
                PostingEntity("b","transfer:to",event.id,"target",1000,"JPY",date,false),
                PostingEntity("b","transfer:fee",event.id,payer,-10,"JPY",date,false))
            val s=BookSnapshot(book,listOf(bank,bank.copy(id="target"),bank.copy(id="third")),emptyList(),listOf(event),emptyList(),null,
                storedPostings=postings,transfers=listOf(TransferEntity("b",event.id,"target",1000,"JPY","JPY",date,date,false,false,1000,"JPY","",date)),
                expenseComponents=listOf(ExpenseComponentEntity("b","transfer:fee",event.id,payer,10,"JPY",10,date,false,"SEPARATE",10,"JPY")))
            val d=s.statementActualDraft(StatementActualSeed(event.id,payer,row(),date,"transfer:fee"))
            assertEquals("1000",d.amount);assertEquals("1000",d.toAmount);assertEquals("15",d.feeAmount)
            assertEquals("15",d.feeAccountAmount);assertEquals("15",d.feeBaseAmount);assertTrue(d.feeConfirmed);assertFalse(d.fromConfirmed)
            if(payer=="bank")assertThrows(IllegalArgumentException::class.java){s.statementActualDraft(StatementActualSeed(event.id,payer,row(),date))}
            val outgoing=s.statementActualDraft(StatementActualSeed(event.id,"bank",row("1200"),date,"transfer:from"))
            assertEquals("1200",outgoing.amount);assertEquals("10",outgoing.feeAmount);assertFalse(outgoing.feeConfirmed)
            val incoming=s.statementActualDraft(StatementActualSeed(event.id,"target",row("990","in"),date,"transfer:to"))
            assertEquals("990",incoming.toAmount);assertTrue(incoming.toConfirmed);assertEquals("1000",incoming.amount)
        }
    }

    @Test fun supplementChecksDerivedSourceMoneyAndRetainsForeignOriginalFacts() {
        val s=BookSnapshot(book,listOf(bank),emptyList(),emptyList(),emptyList(),null)
        val seed=StatementSupplementSeed("b","bank",row("1000"),EntryKind.EXPENSE,date,date)
        val draft=s.statementSupplementDraft(seed)
        s.requireStatementSupplementFacts(seed,draft)
        assertThrows(IllegalArgumentException::class.java){s.requireStatementSupplementFacts(seed,draft.copy(amount="2000",accountAmount="2000"))}
        val foreign=seed.copy(row=seed.row.copy(original=FileMoney("CNY","50")))
        val fx=s.statementSupplementDraft(foreign)
        assertTrue(fx.settlementConfirmed);assertEquals("1000",fx.accountAmount)
        s.requireStatementSupplementFacts(foreign,fx)
        assertThrows(IllegalArgumentException::class.java){s.requireStatementSupplementFacts(foreign,fx.copy(amount="60"))}
    }
}
