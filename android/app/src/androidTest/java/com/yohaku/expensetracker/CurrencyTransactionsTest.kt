package com.yohaku.expensetracker

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CurrencyTransactionsTest {
    private lateinit var db:YohakuDatabase
    private lateinit var repo:LedgerRepository
    private lateinit var book:String
    private lateinit var card:String
    private lateinit var category:String
    @Before fun open()=runBlocking {
        db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,YohakuDatabase::class.java).build()
        repo=LedgerRepository(db)
        book=repo.createBook("外币",Currency.JPY)
        card=repo.saveAccount(book,"卡",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,0),"2026-09-01")
        category=repo.saveCategory(book,"购物",EntryKind.EXPENSE,null)
    }
    @After fun close(){db.close()}
    private suspend fun snapshot()=repo.snapshot(repo.dao.book(book)!!).first()
    private suspend fun purchase(confirmed:Boolean=true)=repo.saveEntry(book,EntryDraft(accountId=card,categoryId=category,amount="100",originalCurrency=Currency.USD,
        accountAmount="15000",settlementConfirmed=confirmed,date="2026-09-10"))
    @Test fun actualConfirmationReplacesPostingAndKeepsPrediction()=runBlocking {
        val event=purchase(false)
        assertEquals(0L,snapshot().balance(snapshot().accounts.single()))
        val input=SettlementConfirmation(event,1,Money(Currency.JPY,15100),Money(Currency.JPY,15100),"2026-09-12")
        val preview=repo.previewSettlement(book,input)
        assertEquals(15000L,preview.impact.expenseBefore);assertEquals(15100L,preview.impact.expenseAfter)
        assertEquals(0L,preview.impact.balances.single().before)
        repo.confirmSettlement(book,input)
        assertEquals(15100L,snapshot().balance(snapshot().accounts.single()))
        assertEquals(15000L,snapshot().settlements.single().expectedAccountMinor)
        assertEquals(10000L,snapshot().events.single().amountMinor)
        try{repo.confirmSettlement(book,input);fail("Stale preview accepted")}catch(_:IllegalArgumentException){}
        repo.confirmSettlement(book,input.copy(revision=2,accountAmount=Money(Currency.JPY,15000),baseAmount=Money(Currency.JPY,15000)))
        assertEquals(1,snapshot().postings.size)
        assertEquals(15000L,snapshot().balance(snapshot().accounts.single()))
    }
    @Test fun splitForeignRefundUsesOriginalMonthAndActualCreditAmount()=runBlocking {
        val event=purchase()
        val first=RefundInput(event,Money(Currency.USD,4000),card,Money(Currency.JPY,5920),Money(Currency.JPY,5920),"2026-10-02",false)
        val refund=repo.saveRefund(book,first)
        assertEquals(15000L,snapshot().monthExpense("2026-09"))
        assertEquals(15000L,snapshot().balance(snapshot().accounts.single()))
        repo.saveRefund(book,first.copy(id=refund,confirmed=true))
        repo.saveRefund(book,first.copy(originalAmount=Money(Currency.USD,6000),accountAmount=Money(Currency.JPY,8880),baseAmount=Money(Currency.JPY,8880),confirmed=true))
        assertEquals(200L,snapshot().monthExpense("2026-09"))
        assertEquals(0L,snapshot().monthExpense("2026-10"))
        assertEquals(200L,snapshot().balance(snapshot().accounts.single()))
        try{repo.saveRefund(book,first.copy(originalAmount=Money(Currency.USD,1)));fail("Over-refund accepted")}catch(_:IllegalArgumentException){}
        val impact=snapshot().removalImpact(event)
        assertEquals(200L,impact.expenseBefore);assertEquals(0L,impact.expenseAfter)
        assertEquals(0L,impact.balances.single().after)
        repo.deleteEntry(book,event);assertTrue(snapshot().visibleEvents.isEmpty())
        repo.restoreEntry(book,event)
        assertEquals(3,snapshot().visibleEvents.size);assertEquals(200L,snapshot().monthExpense("2026-09"))
    }
    @Test fun threeCurrenciesAndBooksKeepTheirOwnAmounts()=runBlocking {
        val bank=repo.saveAccount(book,"人民币",AccountType.BANK,Currency.CNY,Money(Currency.CNY,100000),"2026-09-01")
        val event=repo.saveEntry(book,EntryDraft(accountId=bank,categoryId=category,amount="20",originalCurrency=Currency.USD,accountAmount="145.50",baseAmount="2900",date="2026-09-10"))
        assertEquals(85450L,snapshot().balance(snapshot().accounts.first{it.id==bank}))
        assertEquals(2900L,snapshot().monthExpense("2026-09"))
        val other=repo.createBook("別",Currency.CNY)
        try{repo.saveRefund(other,RefundInput(event,Money(Currency.USD,100),bank,Money(Currency.CNY,700),Money(Currency.CNY,700),"2026-10-01",true));fail("Cross-book refund accepted")}catch(_:IllegalStateException){}
        assertEquals(1,snapshot().visibleEvents.size)
        repo.saveEntry(book,EntryDraft(id=event,accountId=card,categoryId=category,amount="20",originalCurrency=Currency.USD,accountAmount="3000",date="2026-09-10"))
        val settlement=snapshot().settlements.single()
        assertEquals("JPY",settlement.accountCurrency)
        assertEquals(Money(Currency.CNY,14550),settlement.expectedMoney())
    }
    @Test fun refundReviewDoesNotWriteAndCannotCommitAfterBalancesChange()=runBlocking {
        val original=purchase()
        val draft=EntryDraft(kind=EntryKind.REFUND,originalEventId=original,accountId=card,amount="100",accountAmount="14800",date="2026-10-02")
        val review=repo.previewEntry(book,draft)
        assertEquals(1,snapshot().events.size)
        assertEquals(15000L,review.impacts.single().expenseBefore)
        assertEquals(200L,review.impacts.single().expenseAfter)
        val account=snapshot().accounts.single()
        repo.saveAccount(book,account.name,AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,100),account.openingDate,account.id)
        assertNull(repo.commitEntryReview(book,review))
        assertEquals(1,snapshot().events.size)
        val refreshed=repo.previewEntry(book,draft)
        assertEquals(15100L,refreshed.impacts.single().balances.single().before)
        assertEquals(300L,refreshed.impacts.single().balances.single().after)
        assertNotNull(repo.commitEntryReview(book,refreshed))
        assertEquals(200L,snapshot().monthExpense("2026-09"))
        assertEquals(300L,snapshot().balance(snapshot().accounts.single()))
    }

}
