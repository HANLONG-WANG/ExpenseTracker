package com.yohaku.expensetracker

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.domain.finance.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransferTransactionsTest {
    private lateinit var db:YohakuDatabase
    private lateinit var repo:LedgerRepository
    private lateinit var book:String
    private lateinit var source:String
    @Before fun open()=runBlocking {
        db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,YohakuDatabase::class.java).build()
        repo=LedgerRepository(db,java.time.Clock.fixed(java.time.Instant.parse("2026-10-20T00:00:00Z"),java.time.ZoneOffset.UTC));book=repo.createBook("换汇",Currency.JPY)
        source=repo.saveAccount(book,"银行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,100000),"2026-09-01")
    }
    @After fun close(){db.close()}
    private suspend fun snapshot()=repo.snapshot(repo.dao.book(book)!!).first()
    private suspend fun save(d:EntryDraft)=repo.commitEntryReview(book,repo.previewEntry(book,d))!!
    private suspend fun balance(id:String)=snapshot().let{s->s.balance(s.accounts.first{it.id==id})}
    @Test fun plannedTransitAndActualArrivalReplaceEachSideAndRetainPredictions()=runBlocking {
        val target=repo.saveAccount(book,"人民币",AccountType.BANK,Currency.CNY,Money(Currency.CNY,0),"2026-09-01")
        val d=EntryDraft(kind=EntryKind.TRANSFER,accountId=source,toAccountId=target,amount="30000",toAmount="1462.50",date="2026-09-30",toDate="2026-10-02")
        val id=save(d)
        assertEquals(100000L,balance(source));assertEquals(0L,balance(target))
        save(d.copy(id=id,fromConfirmed=true,feeAmount="750"))
        assertEquals(70000L,balance(source));assertEquals(0L,balance(target))
        assertEquals(1,snapshot().inTransitTransfers().size)
        assertEquals(750L,snapshot().monthExpense("2026-09"))
        save(d.copy(id=id,fromConfirmed=true,toConfirmed=true,toAmount="1450.00",feeAmount="750"))
        assertEquals(70000L,balance(source));assertEquals(145000L,balance(target))
        assertEquals(146250L,snapshot().transfers.single().expectedToMinor)
        assertTrue(snapshot().inTransitTransfers().isEmpty())
        assertEquals(0L,snapshot().monthExpense("2026-10"))
        val review=repo.previewEntry(book,d.copy(id=id,fromConfirmed=true,toConfirmed=true,toAmount="1450.00",feeAmount="750"))
        assertNotNull(repo.commitEntryReview(book,review));assertNull(repo.commitEntryReview(book,review))
        assertEquals(2,snapshot().postings.size);assertEquals(1,snapshot().events.size)
        assertEquals(70000L,balance(source));assertEquals(145000L,balance(target))
    }
    @Test fun deductedArrivalFeeAndSeparateFeeCountExpenseOnceWithoutDoubleBalanceDeduction()=runBlocking {
        val target=repo.saveAccount(book,"现金",AccountType.CASH,Currency.JPY,Money(Currency.JPY,0),"2026-09-01")
        val credit=repo.saveAccount(book,"手续费卡",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,1000),"2026-09-01")
        val d=EntryDraft(kind=EntryKind.TRANSFER,accountId=source,toAccountId=target,amount="10000",toAmount="9400",date="2026-09-30",toDate="2026-10-02",fromConfirmed=true,
            feeAmount="600",feePosition=TransferFeePosition.DEDUCTED_TO)
        val id=save(d)
        assertEquals(0L,snapshot().monthExpense("2026-10"))
        save(d.copy(id=id,toConfirmed=true))
        assertEquals(9400L,balance(target));assertEquals(90000L,balance(source))
        assertEquals(0L,snapshot().monthExpense("2026-09"));assertEquals(600L,snapshot().monthExpense("2026-10"))
        save(d.copy(id=id,toConfirmed=true,feePosition=TransferFeePosition.SEPARATE,feeAccountId=credit,feeDate="2026-10-03",feeConfirmed=true))
        assertEquals(9400L,balance(target));assertEquals(90000L,balance(source));assertEquals(1600L,balance(credit))
        assertEquals(600L,snapshot().monthExpense("2026-10"));assertEquals(3,snapshot().postings.size)
        val impact=snapshot().removalImpact(id)
        assertEquals("2026-10",impact.month);assertEquals(600L,impact.expenseBefore);assertEquals(0L,impact.expenseAfter)
        repo.deleteEntry(book,id);assertEquals(100000L,balance(source));assertEquals(0L,balance(target));assertEquals(1000L,balance(credit))
        repo.restoreEntry(book,id);assertEquals(90000L,balance(source));assertEquals(9400L,balance(target));assertEquals(1600L,balance(credit))
    }
    @Test fun arrivalCanBeConfirmedFirstAndForeignFeeKeepsBothAmountsAndHistory()=runBlocking {
        val target=repo.saveAccount(book,"人民币",AccountType.BANK,Currency.CNY,Money(Currency.CNY,0),"2026-09-01")
        val d=EntryDraft(kind=EntryKind.TRANSFER,accountId=source,toAccountId=target,amount="30000",toAmount="1450.00",date="2026-09-30",toDate="2026-10-02",toConfirmed=true,
            feeAmount="5.00",feeCurrency=Currency.USD,feeAccountAmount="750")
        val id=save(d)
        assertEquals(100000L,balance(source));assertEquals(145000L,balance(target));assertEquals(0L,snapshot().monthExpense("2026-09"))
        save(d.copy(id=id,fromConfirmed=true))
        assertEquals(750L,snapshot().monthExpense("2026-09"));assertEquals(70000L,balance(source))
        assertEquals(500L,snapshot().expenseComponents.single().originalMinor)
        assertEquals("USD",snapshot().expenseComponents.single().originalCurrency)
        save(d.copy(id=id,fromConfirmed=true,feeAmount=""))
        assertTrue(snapshot().expenseComponents.isEmpty());assertEquals(0L,snapshot().monthExpense("2026-09"))
        val revision=repo.dao.revisions(book,id).first().first()
        val before=repo.json.decodeFromString<LedgerDetails>(revision.beforeDetailsJson!!)
        val after=repo.json.decodeFromString<LedgerDetails>(revision.afterDetailsJson!!)
        assertEquals(750L,before.components.single().amountMinor);assertTrue(after.components.isEmpty())
        assertEquals(2,snapshot().postings.size)
    }
    @Test fun creditTopUpIncreasesDebtPredictsFullSourceAndUsesCreditLimit()=runBlocking<Unit> {
        val card=repo.saveAccount(book,"信用卡",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,0),"2026-09-01")
        val ic=repo.saveAccount(book,"交通卡",AccountType.IC,Currency.JPY,Money(Currency.JPY,0),"2026-09-01")
        val rule=FinancialRuleVersion("",book,card,FinanceMode.SINGLE,"2026-09-01","Asia/Tokyo",BillingRule(31,true,1,10,source))
        repo.commitFinancialRule(repo.previewFinancialRule(book,card,rule))
        val limit=CreditLimitInput(card,10000,null,null,CreditLimitSharing.TOTAL_ONLY,null,"2026-10-01","2026-10-01",0,null,null,LimitRecovery.PAYMENT_DATE,CreditLimitUsageBasis.PRINCIPAL_ONLY,"Asia/Tokyo",CreditLimitUsageStart.TRANSACTION_DATE)
        assertTrue(repo.commitCreditLimit(repo.previewCreditLimit(book,limit)))
        val d=EntryDraft(kind=EntryKind.TRANSFER,accountId=card,toAccountId=ic,amount="2100",toAmount="2000",date="2026-10-20",fromConfirmed=true,toConfirmed=true,feeAmount="100",cardPaymentChoice="SINGLE")
        val id=save(d);val s=snapshot();val finance=db.financialDao().graph(book).first()!!
        assertEquals(2100L,balance(card));assertEquals(2000L,balance(ic));assertEquals(100000L,balance(source))
        assertEquals(2100L,s.postings.single{it.accountId==card}.deltaMinor);assertEquals(100L,s.monthExpense("2026-10"))
        val plan=finance.plans.single{it.sourceEventId==id&&it.active};assertEquals("CARD_ENTRY",plan.origin);assertEquals(2100L,plan.principalMinor)
        assertEquals(2100L,finance.bills.single{it.planId==plan.id&&it.active}.principalMinor)
        assertEquals(2100L,accountCreditLimit(s,finance,db.creditLimitDao().graph(book).first()!!,card,"2026-10-20").total.usedMinor)
        assertEquals("out",s.reconciliationPostings().single{it.accountId==card}.direction)
        val timeline=s.cashTimeline(finance,java.time.LocalDate.parse("2026-10-20"),java.time.LocalDate.parse("2026-11-30"))
        assertTrue(timeline.steps.any{it.item.kind=="BILL"&&it.item.accountId==source&&it.item.accountDeltaMinor==-2100L})
        save(d.copy(id=id,amount="3100",toAmount="3000"))
        val updated=db.financialDao().graph(book).first()!!;assertEquals(3100L,updated.plans.single{it.active&&it.sourceEventId==id}.principalMinor)
        val loan=repo.saveAccount(book,"贷款",AccountType.LOAN,Currency.JPY,Money(Currency.JPY,0),"2026-09-01")
        assertThrows(IllegalArgumentException::class.java){runBlocking{repo.previewEntry(book,d.copy(accountId=loan))}}
        assertThrows(IllegalArgumentException::class.java){runBlocking{repo.previewEntry(book,d.copy(accountId=source,toAccountId=card))}}
    }
    @Test fun editedSaveTimeDrivesAccountMemoryAndReviewUsesNetBalanceChange()=runBlocking<Unit> {
        val other=repo.saveAccount(book,"现金",AccountType.CASH,Currency.JPY,Money(Currency.JPY,0),"2026-09-01")
        val category=repo.saveCategory(book,"餐饮",EntryKind.EXPENSE,null)
        val first=EntryDraft(accountId=other,categoryId=category,amount="100",date="2026-10-01")
        val id=repo.saveEntry(book,first)
        repo.saveEntry(book,first.copy(accountId=source,date="2026-10-20"))
        val later=LedgerRepository(db,java.time.Clock.fixed(java.time.Instant.parse("2026-10-21T00:00:00Z"),java.time.ZoneOffset.UTC))
        later.saveEntry(book,first.copy(id=id,amount="200"))
        assertEquals(other,snapshot().defaultEntryAccount(EntryKind.EXPENSE))
        val decreasing=repo.previewEntry(book,first.copy(id=id,amount="300")).negativeBalanceChanges().single()
        assertEquals(-200L,decreasing.before);assertEquals(-300L,decreasing.after)
        assertTrue(repo.previewEntry(book,first.copy(id=id,amount="50")).negativeBalanceChanges().isEmpty())
        assertTrue(snapshot().eventSaveTimes.getValue(id)>repo.clock.millis())
    }
}
