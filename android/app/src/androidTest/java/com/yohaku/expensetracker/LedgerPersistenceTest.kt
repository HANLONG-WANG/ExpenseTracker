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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class LedgerPersistenceTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db:YohakuDatabase
    private lateinit var repo:LedgerRepository
    private val databaseName="phase-a-verification.db"
    @Before fun open() {context.deleteDatabase(databaseName);db=Room.databaseBuilder(context,YohakuDatabase::class.java,databaseName).build();repo=LedgerRepository(db,Clock.fixed(Instant.parse("2026-10-13T00:00:00Z"),ZoneOffset.UTC))}
    @After fun close() {db.close();context.deleteDatabase(databaseName)}
    private suspend fun snapshot(id:String)=repo.snapshot(repo.dao.book(id)!!).first()
    private suspend fun book():String=repo.createBook("日常生活",Currency.JPY)
    @Test fun editDeleteRestoreAndReopenKeepOneEvent()=runBlocking {
        val id=book();assertTrue(snapshot(id).categories.isEmpty());assertTrue(snapshot(id).events.isEmpty())
        repo.saveAccount(id,"现金",AccountType.CASH,Currency.JPY,Money(Currency.JPY,10000),"2026-10-13")
        repo.saveCategory(id,"餐饮",EntryKind.EXPENSE,null)
        val account=snapshot(id).accounts.single();val category=snapshot(id).categories.single()
        val draft=EntryDraft(accountId=account.id,categoryId=category.id,amount="680",date="2026-10-13",merchant="喫茶と余白")
        val eventId=repo.saveEntry(id,draft);assertEquals(9320L,snapshot(id).balance(account))
        repo.saveEntry(id,draft.copy(id=eventId,amount="800"));assertEquals(9200L,snapshot(id).balance(account));assertEquals(1,snapshot(id).events.size)
        val impact=snapshot(id).removalImpact(eventId)
        assertEquals(9200L,impact.balances.single().before);assertEquals(10000L,impact.balances.single().after)
        assertEquals(800L,impact.expenseBefore);assertEquals(0L,impact.expenseAfter)
        repo.deleteEntry(id,eventId);assertEquals(10000L,snapshot(id).balance(account));assertTrue(snapshot(id).visibleEvents.isEmpty())
        val restored=snapshot(id).restorationImpact(eventId)
        assertEquals(10000L,restored.balances.single().before);assertEquals(9200L,restored.balances.single().after)
        assertEquals(0L,restored.expenseBefore);assertEquals(800L,restored.expenseAfter)
        repo.restoreEntry(id,eventId);assertEquals(9200L,snapshot(id).balance(account))
        repo.saveDraft(id,draft.copy(amount="777"))
        db.close();db=Room.databaseBuilder(context,YohakuDatabase::class.java,databaseName).build();repo=LedgerRepository(db)
        assertEquals(eventId,snapshot(id).events.single().id);assertEquals("777",snapshot(id).draft!!.amount);assertEquals(9200L,snapshot(id).balance(account))
    }
    @Test fun booksAndCategoryKindsCannotCross()=runBlocking {
        val a=book();val b=book()
        repo.saveAccount(a,"现金",AccountType.CASH,Currency.JPY,Money(Currency.JPY,1000),"2026-10-13")
        repo.saveCategory(b,"餐饮",EntryKind.EXPENSE,null)
        val wrong=EntryDraft(accountId=snapshot(a).accounts.single().id,categoryId=snapshot(b).categories.single().id,amount="100")
        try{repo.saveEntry(a,wrong);fail("Cross-book category accepted")}catch(_:IllegalStateException){}
        assertTrue(snapshot(a).events.isEmpty());assertTrue(snapshot(b).accounts.isEmpty())
        repo.saveCategory(a,"餐饮",EntryKind.EXPENSE,null);val parent=snapshot(a).categories.single()
        repo.saveCategory(a,"咖啡",EntryKind.EXPENSE,parent.id)
        val child=snapshot(a).categories.first{it.parentId!=null}
        try{repo.saveCategory(a,"第三层",EntryKind.EXPENSE,child.id);fail("Third level accepted")}catch(_:IllegalArgumentException){}
        assertEquals("餐饮 / 咖啡",snapshot(a).path(child.id))
    }
    @Test fun movementsAndOpeningHistoryDoNotBecomeConsumption()=runBlocking {
        val b=book()
        repo.saveAccount(b,"银行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-13")
        repo.saveAccount(b,"信用卡",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,4000),"2026-10-13")
        val accounts=snapshot(b).accounts;val bank=accounts.first{it.type=="BANK"};val card=accounts.first{it.type=="CREDIT"}
        repo.saveMovement(b,EntryKind.REPAYMENT,bank.id,card.id,Money(Currency.JPY,1000),Money(Currency.JPY,1000),"2026-10-13")
        assertEquals(9000L,snapshot(b).balance(bank));assertEquals(3000L,snapshot(b).balance(card))
        repo.saveCategory(b,"餐饮",EntryKind.EXPENSE,null)
        repo.saveEntry(b,EntryDraft(accountId=bank.id,categoryId=snapshot(b).categories.single().id,amount="500",date="2026-10-12",historicalIncluded=true))
        assertEquals(9000L,snapshot(b).balance(bank));assertEquals(1,snapshot(b).postings.count{it.accountId==bank.id})
    }
    @Test fun incomeCannotReduceCreditOrLoanDebt()=runBlocking {
        val b=book()
        val category=repo.saveCategory(b,"收入",EntryKind.INCOME,null)
        for(type in listOf(AccountType.CREDIT,AccountType.LOAN)){
            val account=repo.saveAccount(b,type.name,type,Currency.JPY,Money(Currency.JPY,4000),"2026-10-13")
            try{repo.saveEntry(b,EntryDraft(kind=EntryKind.INCOME,accountId=account,categoryId=category,amount="1000",date="2026-10-13"));fail("Income reduced debt")}
            catch(e:IllegalArgumentException){assertTrue(e.message!!.contains("收入请选择资金账户"))}
            assertEquals(4000L,snapshot(b).balance(snapshot(b).accounts.first{it.id==account}))
        }
        assertTrue(snapshot(b).events.isEmpty())
    }
    @Test fun expiredTrashIsPurgedWithoutRemovingOtherBooks()=runBlocking {
        val id=book()
        repo.saveAccount(id,"现金",AccountType.CASH,Currency.JPY,Money(Currency.JPY,10000),"2026-10-13")
        repo.saveCategory(id,"餐饮",EntryKind.EXPENSE,null)
        val original=snapshot(id)
        val event=repo.saveEntry(id,EntryDraft(accountId=original.accounts.single().id,categoryId=original.categories.single().id,amount="500",date="2026-10-13"))
        repo.deleteEntry(id,event)
        val other=book()
        repo.saveAccount(other,"银行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,20000),"2026-10-13")
        repo=LedgerRepository(db,Clock.fixed(Instant.parse("2026-11-13T00:00:00Z"),ZoneOffset.UTC))
        try{repo.restoreEntry(id,event);fail("Expired event restored")}catch(_:IllegalArgumentException){}
        repo.purgeExpired()
        assertNull(repo.dao.event(id,event))
        assertTrue(snapshot(id).postings.isEmpty())
        assertEquals(20000L,snapshot(other).balance(snapshot(other).accounts.single()))
    }

    @Test fun unusedAccountCurrencyCanChangeButAllHistoryKeepsItLocked()=runBlocking {
        val b=book()
        val a=repo.saveAccount(b,"外币",AccountType.BANK,Currency.JPY,Money(Currency.JPY,1000),"2026-10-13")
        assertEquals(a,repo.saveAccount(b,"外币",AccountType.BANK,Currency.CNY,Money(Currency.CNY,1234),"2026-10-13",a))
        assertEquals("CNY",snapshot(b).accounts.single().currency)
        assertEquals(1234L,snapshot(b).balance(snapshot(b).accounts.single()))
        assertTrue(snapshot(b).events.isEmpty())
        val category=repo.saveCategory(b,"历史消费",EntryKind.EXPENSE,null)
        val historical=repo.saveEntry(b,EntryDraft(accountId=a,categoryId=category,amount="1.00",baseAmount="20",date="2026-10-12",historicalIncluded=true))
        assertTrue(snapshot(b).storedPostings.isEmpty())
        repo.deleteEntry(b,historical)
        try{repo.saveAccount(b,"外币",AccountType.BANK,Currency.USD,Money(Currency.USD,1234),"2026-10-13",a);fail("Recycled historical record lost currency")}
        catch(e:IllegalArgumentException){assertTrue(e.message!!.contains("已有交易"))}
        val destination=repo.saveAccount(b,"银行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,1000),"2026-10-13")
        repo.saveMovement(b,EntryKind.TRANSFER,a,destination,Money(Currency.CNY,100),Money(Currency.JPY,20),"2026-10-13")
        try{repo.saveAccount(b,"银行",AccountType.BANK,Currency.USD,Money(Currency.USD,1000),"2026-10-13",destination);fail("Transfer destination lost currency")}
        catch(e:IllegalArgumentException){assertTrue(e.message!!.contains("已有交易"))}
        assertEquals("CNY",snapshot(b).accounts.first{it.id==a}.currency)
        assertEquals("JPY",snapshot(b).accounts.first{it.id==destination}.currency)
        repo.restoreEntry(b,historical)
        assertEquals("CNY",snapshot(b).visibleEvents.first{it.id==historical}.currency)
    }

    @Test fun sameDayRecentEntriesFollowCreationInsteadOfCategoryIds()=runBlocking {
        val id=book()
        repo.saveAccount(id,"现金",AccountType.CASH,Currency.JPY,Money(Currency.JPY,10000),"2026-10-13")
        repo.saveCategory(id,"餐饮",EntryKind.EXPENSE,null)
        repo.saveCategory(id,"交通",EntryKind.EXPENSE,null)
        val initial=snapshot(id)
        val first=repo.saveEntry(id,EntryDraft(accountId=initial.accounts.single().id,categoryId=initial.categories[0].id,amount="500",date="2026-10-13"))
        repo=LedgerRepository(db,Clock.fixed(Instant.parse("2026-10-13T00:00:01Z"),ZoneOffset.UTC))
        val second=repo.saveEntry(id,EntryDraft(accountId=initial.accounts.single().id,categoryId=initial.categories[1].id,amount="200",date="2026-10-13"))
        assertEquals(listOf(second,first),snapshot(id).visibleEvents.map{it.id})
        repo=LedgerRepository(db,Clock.fixed(Instant.parse("2026-10-13T00:00:02Z"),ZoneOffset.UTC))
        repo.saveEntry(id,EntryDraft(id=first,accountId=initial.accounts.single().id,categoryId=initial.categories[0].id,amount="600",date="2026-10-13"))
        assertEquals(listOf(second,first),snapshot(id).visibleEvents.map{it.id})
        assertEquals(9200L,snapshot(id).balance(initial.accounts.single()))
        assertEquals(listOf(initial.categories[0].id,initial.categories[1].id),repo.dao.recentCategoryIds(id,"EXPENSE").first())
    }

}
