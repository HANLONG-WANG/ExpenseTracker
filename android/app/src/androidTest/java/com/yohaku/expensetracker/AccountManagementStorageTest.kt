package com.yohaku.expensetracker

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.finance.*
import com.yohaku.expensetracker.domain.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.*

@RunWith(AndroidJUnit4::class)
class AccountManagementStorageTest {
    private lateinit var db:YohakuDatabase
    private lateinit var r:LedgerRepository
    @Before fun open(){db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,YohakuDatabase::class.java).build();r=LedgerRepository(db,Clock.fixed(Instant.parse("2026-10-16T03:00:00Z"),ZoneOffset.UTC))}
    @After fun close(){db.close()}
    private suspend fun account(b:String,name:String="銀行",type:AccountType=AccountType.BANK,opening:Long=10000)=r.saveAccount(b,name,type,Currency.JPY,Money(Currency.JPY,opening),"2026-10-01")
    private suspend fun snapshot(b:String)=r.snapshot(r.dao.book(b)!!).first()
    private suspend fun apply(b:String,a:String,op:AccountOperation){assertTrue(r.commitAccountOperation(r.previewAccountOperation(b,a,op)))}
    @Test fun stoppedDebtRetainsBillsAndCanStillBeRepaid()=runBlocking {
        val b=r.createBook("生活",Currency.JPY);val bank=account(b);val card=account(b,"カード",AccountType.CREDIT,1000)
        val plan=r.commitManualFinancialPlan(r.previewManualFinancialPlan(b,ManualFinancialPlanInput(card,"今期",FinanceMode.SINGLE,listOf(KnownBillInput("2026-10-17",1000,1000,0,bank)),1000,complete=true,opening=true)))!!
        val before=snapshot(b);val bills=db.financialDao().bills(b,plan)
        val review=r.previewAccountOperation(b,card,AccountOperation.DEACTIVATE)
        assertEquals(1000L,review.balanceMinor);assertEquals(1000L,review.knownOutstandingMinor);assertTrue(review.pendingCount>0)
        assertTrue(r.commitAccountOperation(review))
        val stopped=snapshot(b);assertFalse(stopped.accounts.first{it.id==card}.active)
        assertEquals(before.events,stopped.events);assertEquals(before.postings,stopped.postings);assertEquals(bills,db.financialDao().bills(b,plan))
        assertTrue(stopped.entryAccounts(EntryKind.EXPENSE).none{it.id==card})
        val repay=FinancialRepaymentInput(bank,card,Money(Currency.JPY,1000),"2026-10-16",Money(Currency.JPY,1000),Money(Currency.JPY,1000),Money(Currency.JPY,0),Money(Currency.JPY,0),platformConfirmed=true,clearingDate="2026-10-16",allocations=listOf(RepaymentAllocationInput(billId=bills.single().id,principalMinor=1000,interestMinor=0,feeMinor=0)))
        assertNotNull(r.commitFinancialLedger(r.previewRepayment(b,repay)))
        val paid=snapshot(b);assertEquals(0L,paid.balance(paid.accounts.first{it.id==card}));assertEquals(9000L,paid.balance(paid.accounts.first{it.id==bank}))
        assertTrue(r.previewAccountOperation(b,card,AccountOperation.DELETE).usage.inUse)
        apply(b,card,AccountOperation.ACTIVATE);assertTrue(snapshot(b).accounts.first{it.id==card}.active)
    }
    @Test fun oldEntriesRemainEditableButNewEntriesCannotUseStoppedAccount()=runBlocking {
        val b=r.createBook("履歴",Currency.JPY);val a=account(b);val c=r.saveCategory(b,"食事",EntryKind.EXPENSE,null)
        val d=EntryDraft(accountId=a,categoryId=c,amount="500",date="2026-10-16")
        val id=r.saveEntry(b,d);apply(b,a,AccountOperation.DEACTIVATE)
        assertEquals(id,r.saveEntry(b,d.copy(id=id,amount="600")))
        val s=snapshot(b);assertEquals(1,s.events.size);assertEquals(1,s.postings.size);assertEquals(9400L,s.balance(s.accounts.single()))
        try{r.saveEntry(b,d);fail("new use must fail")}catch(_:IllegalArgumentException){}
        assertTrue(s.entryAccounts(EntryKind.EXPENSE,a).any{it.id==a})
        r.deleteEntry(b,id)
        assertTrue(r.accountUsage(b,a).inUse) // Trash is still a restorable reference.
        try{r.commitAccountOperation(r.previewAccountOperation(b,a,AccountOperation.DELETE));fail("referenced delete")}catch(_:IllegalArgumentException){}
    }
    @Test fun embeddedFinancialAndTransferDraftReferencesBlockDeletionAndCurrencyChanges()=runBlocking {
        val b=r.createBook("草稿",Currency.JPY);val a=account(b);val debt=account(b,"ローン",AccountType.LOAN,0)
        val ctx=FinancialDraftContext("REPAY",debt)
        val values=buildJsonObject{put("encoded",buildJsonObject{put("source",a);put("paid","100.")}.toString())}.toString()
        r.storeFinancialDraft(ctx,b,values,"basis")
        assertTrue(r.accountUsage(b,a).draftKeys.any{it.startsWith("finance:")})
        try{r.saveAccount(b,"銀行",AccountType.BANK,Currency.CNY,Money(Currency.CNY,10000),"2026-10-01",a);fail("draft changes currency")}catch(_:IllegalArgumentException){}
        db.financialDao().clearDraft(b,ctx.slot())
        r.saveDraft(b,EntryDraft(kind=EntryKind.TRANSFER,accountId=debt,toAccountId=a))
        assertTrue(r.accountUsage(b,a).inUse)
        r.clearDraft(b);assertFalse(r.accountUsage(b,a).inUse)
        val review=r.previewAccountOperation(b,a,AccountOperation.DELETE)
        r.saveDraft(b,EntryDraft(feeAccountId=a))
        assertFalse(r.commitAccountOperation(review));assertNotNull(r.dao.account(b,a))
    }
    @Test fun deletingUnreferencedOpeningIsExplicitScopedAndCreatesNoLedgerAdjustment()=runBlocking {
        val b=r.createBook("削除",Currency.JPY);val other=r.createBook("保持",Currency.JPY)
        val a=account(b);account(other);val before=snapshot(other)
        val review=r.previewAccountOperation(b,a,AccountOperation.DELETE)
        assertEquals(10000L,review.account.openingMinor);assertFalse(review.usage.inUse);assertNotNull(r.dao.account(b,a))
        assertTrue(r.commitAccountOperation(review));assertNull(r.dao.account(b,a));assertTrue(snapshot(b).events.isEmpty());assertEquals(before,snapshot(other))
        db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use{assertFalse(it.moveToFirst())}
    }
}
