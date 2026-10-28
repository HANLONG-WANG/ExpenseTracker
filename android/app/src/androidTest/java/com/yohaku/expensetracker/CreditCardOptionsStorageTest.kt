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
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.*

@RunWith(AndroidJUnit4::class)
class CreditCardOptionsStorageTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val clock=Clock.fixed(Instant.parse("2026-10-25T00:00:00Z"),ZoneId.of("Asia/Tokyo"))
    private data class Fixture(val r:LedgerRepository,val book:String,val card:String,val bank:String,val category:String) {
        fun draft(amount:String,date:String="2026-10-20",choice:String="SINGLE")=EntryDraft(accountId=card,categoryId=category,amount=amount,date=date,merchant="利用",cardPaymentChoice=choice)
        suspend fun s()=r.snapshot(r.dao.book(book)!!).first()
        suspend fun f()=r.db.financialDao().graph(book).first()!!
    }
    private suspend fun setup(db:YohakuDatabase):Fixture {
        val r=LedgerRepository(db,clock);val b=r.createBook("カード",Currency.JPY,startDate="2026-01-01")
        val card=r.saveAccount(b,"カード",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,0),"2026-01-01")
        val bank=r.saveAccount(b,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,50000),"2026-01-01")
        val cat=r.saveCategory(b,"生活",EntryKind.EXPENSE,null)
        val single=FinancialRuleVersion("",b,card,FinanceMode.SINGLE,"2026-01-01","Asia/Tokyo",BillingRule(31,true,1,10,bank),bonusPayments=listOf(BonusPaymentWindow(12,16,6,15,8,10),BonusPaymentWindow(7,16,11,15,1,10)))
        r.commitFinancialRule(r.previewFinancialRule(b,card,single))
        return Fixture(r,b,card,bank,cat)
    }
    @Test fun bonusAndDeferredPersistOneSingleBillWithFeeAndMissingSettingsAreRejected()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        try {
            val x=setup(db);val r=x.r
            val summer=r.saveEntry(x.book,x.draft("3000","2026-12-20","BONUS"))
            val winter=r.saveEntry(x.book,x.draft("5000","2026-07-20","BONUS"))
            val deferred=r.saveEntry(x.book,x.draft("7000",choice="DEFERRED").copy(cardPaymentMonth="2027-02",cardPaymentFee="100"))
            val unknown=r.saveEntry(x.book,x.draft("800",choice="DEFERRED").copy(cardPaymentMonth="2027-03"))
            val f=x.f()
            fun bill(source:String)=f.bills.single{it.planId==f.plans.single{p->p.sourceEventId==source}.id}
            assertEquals("2027-08-10",bill(summer).date);assertEquals("2027-01-10",bill(winter).date)
            assertEquals("2027-02-10",bill(deferred).date);assertEquals(100L,bill(deferred).feeMinor);assertEquals(7100L,bill(deferred).totalMinor)
            assertEquals("SINGLE",r.dao.event(x.book,deferred)!!.cardPaymentMode);assertEquals("DEFERRED",r.dao.event(x.book,deferred)!!.cardPaymentChoice)
            assertNull(bill(unknown).feeMinor);assertTrue(r.financialPredictionStale(x.s(),f,f.plans.single{it.sourceEventId==unknown}))
            assertThrows(IllegalArgumentException::class.java){runBlocking{r.saveEntry(x.book,x.draft("100","2026-06-20","BONUS"))}}
            assertThrows(IllegalArgumentException::class.java){runBlocking{r.saveEntry(x.book,x.draft("100",choice="DEFERRED").copy(cardPaymentMonth="2026-10",cardPaymentFee="0"))}}
            assertEquals(4,x.s().events.size);assertEquals(15800L,x.s().balance(x.s().accounts.first{it.id==x.card}))
        }finally{db.close()}
    }
    @Test fun confirmedBillsTransferPartiallyAtomicallyAndUndoAsAGroup()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        try {
            val x=setup(db);val r=x.r
            val events=listOf("3000","5000","7000").map{r.saveEntry(x.book,x.draft(it))}
            val plans=events.map{e->x.f().plans.single{it.sourceEventId==e}}
            val originalBills=plans.map{p->x.f().bills.single{it.planId==p.id}}
            originalBills.forEach{b->r.commitFinancialBillConfirmation(r.previewFinancialBillConfirmation(x.book,b.id,KnownBillInput(b.date,b.totalMinor,b.principalMinor,b.feeMinor,x.bank)))}
            r.commitFinancialRule(r.previewFinancialRule(x.book,x.card,FinancialRuleVersion("",x.book,x.card,FinanceMode.REVOLVING,"2026-01-01","Asia/Tokyo",BillingRule(31,true,1,10,x.bank),revolving=RevolvingRule(InterestRule("0",ChargeFrequency.DAILY,BalanceBasis.EVENT_BALANCE,true,true,365,ChargeRounding.PERIOD_TOTAL,"DOWN",FirstCharge.NORMAL),PaymentMethod.FIXED_PRINCIPAL,3000,allocation=AllocationOrder.FEES_FIRST,automatic=false))))
            val dates=listOf("2026-11-10","2026-12-10","2027-01-10","2027-02-10")
            val windows=listOf(AccrualWindow("2026-01-01","2026-10-31"),AccrualWindow("2026-11-01","2026-11-30"),AccrualWindow("2026-12-01","2026-12-31"),AccrualWindow("2027-01-01","2027-01-31"))
            val pool=r.commitFinancialPrediction(r.previewFinancialPool(x.book,FinancialPredictionInput(x.card,"リボ",FinanceMode.REVOLVING,FinancialScheduleTerms("2026-01-01",dates,windows),paymentAccountId=x.bank,currentArrangementConfirmed=true,pool=FinancialPoolInput(0,PoolSourceDate.TRANSACTION))))!!
            val before=x.s();val beforeF=x.f()
            val input=BatchRevolvingInput(x.card,"2026-11-10",plans.reversed().map{it.id},10000,pool,"2026-10-25",100,"2026-11-10")
            val preview=r.previewBatchRevolving(x.book,input)
            assertTrue(preview.calculation.toString(),preview.calculation is Calculation.Ready)
            assertEquals(plans.map{it.id},preview.transfers.map{it.planId});assertEquals(listOf(3000L,5000L,2000L),preview.transfers.map{it.principalMinor})
            assertEquals(100L,preview.comparison!!.totalExtraFeeMinor)
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_batch BEFORE INSERT ON financial_arrangements WHEN NEW.originalPlanId='${plans.last().id}' BEGIN SELECT RAISE(ABORT,'batch rollback'); END")
            assertThrows(android.database.sqlite.SQLiteException::class.java){runBlocking{r.commitBatchRevolving(preview,clock.instant().toString(),true)}}
            assertEquals(before,x.s());assertEquals(beforeF,x.f())
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_batch")
            val group=r.commitBatchRevolving(preview,clock.instant().toString(),true)!!
            var after=x.s();var f=x.f()
            assertEquals(listOf(0L,0L,5000L),originalBills.map{after.billOutstanding(f.bills.first{b->b.id==it.id}).principalMinor})
            assertTrue(originalBills.all{b->f.bills.first{it.id==b.id}.confirmed})
            assertEquals(10000L,f.plans.first{it.id==pool}.principalMinor);assertEquals(100L,f.bills.filter{it.planId==pool&&it.active}.sumOf{it.feeMinor!!})
            assertEquals(before.storedPostings,after.storedPostings)
            assertEquals(events.toSet(),f.arrangements.map{change->f.plans.first{it.id==change.targetPlanId}.sourceEventId}.toSet())
            r.undoBatchRevolving(x.book,group)
            after=x.s();f=x.f()
            assertTrue(f.arrangements.isEmpty());assertEquals(listOf(3000L,5000L,7000L),originalBills.map{after.billOutstanding(f.bills.first{b->b.id==it.id}).principalMinor})
            assertEquals(0L,f.plans.first{it.id==pool}.principalMinor);assertTrue(plans.all{p->f.plans.first{it.id==p.id}.active})
            assertEquals(before.storedPostings,after.storedPostings)
        }finally{db.close()}
    }
}
