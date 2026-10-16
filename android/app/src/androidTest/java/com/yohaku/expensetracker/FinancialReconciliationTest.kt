package com.yohaku.expensetracker

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.finance.*
import com.yohaku.expensetracker.domain.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.*

@RunWith(AndroidJUnit4::class)
class FinancialReconciliationTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val clock=Clock.fixed(Instant.parse("2026-10-25T00:00:00Z"),ZoneId.of("Asia/Tokyo"))
    private data class Fixture(val b:String,val bank:String,val card:String,val cat:String,val source:String,val old:String,val target:String,val payment:String?)
    private suspend fun fixture(r:LedgerRepository,retain:Boolean=false,prepaid:Boolean=false):Fixture {
        val b=r.createBook("已办理重核",Currency.JPY,startDate="2026-10-01")
        val bank=r.saveAccount(b,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
        val card=r.saveAccount(b,"カード",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,0),"2026-10-01")
        val cat=r.saveCategory(b,"利用",EntryKind.EXPENSE,null)
        val source=r.saveEntry(b,EntryDraft(accountId=card,categoryId=cat,amount="1000",date="2026-10-01"))
        val rows=if(retain)listOf(KnownBillInput("2026-10-10",210,200,10,bank),KnownBillInput("2026-11-05",800,800,0,bank))else listOf(KnownBillInput("2026-11-05",1000,1000,0,bank))
        val old=r.commitManualFinancialPlan(r.previewManualFinancialPlan(b,ManualFinancialPlanInput(card,"原安排",FinanceMode.SINGLE,rows,1000,complete=true,sourceEventId=source)))!!
        val payment=if(prepaid)r.commitFinancialLedger(r.previewRepayment(b,FinancialRepaymentInput(bank,card,Money(Currency.JPY,100),"2026-10-20",Money(Currency.JPY,100),Money(Currency.JPY,100),Money(Currency.JPY,0),Money(Currency.JPY,0),platformConfirmed=true,clearingDate="2026-10-20",allocations=listOf(RepaymentAllocationInput(r.db.financialDao().bills(b,old).first().id,null,100,0,0)))))else null
        r.commitFinancialRule(r.previewFinancialRule(b,card,FinancialRuleVersion("",b,card,FinanceMode.INSTALLMENT,"2026-10-01","Asia/Tokyo",BillingRule(31,true,1,5,bank),installment=InstallmentRule(listOf(2),InstallmentFeeMethod.TOTAL_AMOUNT,totalFeeMinor=40,principalRemainder=RemainderPlacement.LAST,feeRemainder=RemainderPlacement.FIRST,roundingMode="DOWN"))))
        val target=r.commitFinancialArrangement(r.previewFinancialArrangement(b,FinancialArrangementInput(old,"两期",FinanceMode.INSTALLMENT,FinancialScheduleTerms("2026-10-25",listOf("2026-11-05","2026-12-05")),bank,OriginalFeeTreatment.RETAIN,0)),clock.instant().toString(),true)!!
        return Fixture(b,bank,card,cat,source,old,target,payment)
    }
    private suspend fun input(r:LedgerRepository,x:Fixture,principal:Long)=ArrangementReconciliationInput(x.target,"2026-10-25",principal,r.db.financialDao().bills(x.b,x.old).map{ReconciledBillObligation(it.id,0,0)},emptyList())
    @Test fun sourceCorrectionAndVersion22UpgradePreserveAppliedHistoryAndRollBackFailedReconciliation()=runBlocking {
        val name="reconciliation-upgrade.db";context.deleteDatabase(name)
        var db=Room.databaseBuilder(context,YohakuDatabase::class.java,name).build()
        try {
            var r=LedgerRepository(db,clock);val x=fixture(r)
            val applied=db.financialDao().graph(x.b).first()!!.arrangements.single();val originalBill=db.financialDao().bills(x.b,x.old).single()
            val versions=db.financialDao().planRevisions(x.b,x.target).first().map{row->
                val legacy=JSONObject(row.json);legacy.getJSONObject("plan").remove("reconciliationJson")
                row.copy(json=legacy.toString())
            };db.close()
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use{old->
                restoreFixtureTable(old,"room-v22-schema.json","financial_plans")
                versions.forEach{row->old.execSQL("UPDATE financial_plan_revisions SET json=? WHERE bookId=? AND planId=? AND version=?",arrayOf<Any>(row.json,row.bookId,row.planId,row.version))}
                val schema=JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("room-v22-schema.json").bufferedReader().readText()).getJSONObject("database")
                old.execSQL("UPDATE room_master_table SET identity_hash=? WHERE id=42",arrayOf(schema.getString("identityHash")));old.version=22
            }
            db=Room.databaseBuilder(context,YohakuDatabase::class.java,name).addMigrations(MIGRATION_22_23,com.yohaku.expensetracker.data.db.MIGRATION_23_24,com.yohaku.expensetracker.data.db.MIGRATION_24_25).build();r=LedgerRepository(db,clock)
            assertEquals(applied,db.financialDao().graph(x.b).first()!!.arrangements.single());assertEquals(versions,db.financialDao().planRevisions(x.b,x.target).first())
            r.saveEntry(x.b,EntryDraft(id=x.source,accountId=x.card,categoryId=x.cat,amount="1200",date="2026-10-02"))
            val partial=r.previewArrangementReconciliation(x.b,input(r,x,1000))
            assertEquals(200L,partial.record!!.unassignedOriginalMinor);r.commitArrangementReconciliation(partial,true)
            assertEquals(200L,r.snapshot(r.dao.book(x.b)!!).first().financialAccountSummary(db.financialDao().graph(x.b).first()!!,x.card,"2026-10-25").reconciliationRemainders.single().principalMinor)
            val before=r.snapshot(r.dao.book(x.b)!!).first();val finance=db.financialDao().graph(x.b).first()!!
            assertFalse(r.financialPredictionStale(before,finance,finance.plans.first{it.id==x.target}))
            val review=r.previewArrangementReconciliation(x.b,input(r,x,1200))
            assertTrue(review.calculation.toString(),review.calculation is Calculation.Ready)
            assertTrue(review.prediction?.calculation.toString(),review.prediction?.calculation is Calculation.Ready)
            assertEquals(listOf(600L,600L),review.prediction!!.bills.map{it.principalMinor})
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_reconciliation BEFORE INSERT ON financial_plan_revisions WHEN NEW.reason='ARRANGEMENT_RECONCILED' BEGIN SELECT RAISE(ABORT,'rollback'); END")
            assertThrows(android.database.sqlite.SQLiteException::class.java){runBlocking{r.commitArrangementReconciliation(review,true)}}
            assertEquals(finance,db.financialDao().graph(x.b).first());assertEquals(before,r.snapshot(r.dao.book(x.b)!!).first())
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_reconciliation")
            assertEquals(x.target,r.commitArrangementReconciliation(review,true));assertNull(r.commitArrangementReconciliation(review,true))
            db.close();db=Room.databaseBuilder(context,YohakuDatabase::class.java,name).build();r=LedgerRepository(db,clock)
            val f=db.financialDao().graph(x.b).first()!!;val s=r.snapshot(r.dao.book(x.b)!!).first()
            assertEquals(applied,f.arrangements.single());assertEquals(originalBill,db.financialDao().bills(x.b,x.old).single())
            assertEquals(1200L,s.balance(s.accounts.first{it.id==x.card}));assertEquals(10000L,s.balance(s.accounts.first{it.id==x.bank}));assertEquals(before.storedPostings,s.storedPostings)
            assertTrue(s.financialAccountSummary(f,x.card,"2026-10-25").reconciliationRemainders.isEmpty())
            assertFalse(r.financialPredictionStale(s,f,f.plans.first{it.id==x.target}));assertEquals(1200L,f.plans.first{it.id==x.target}.principalMinor)
            assertTrue(db.financialDao().planRevisions(x.b,x.target).first().any{it.reason=="ARRANGEMENT_RECONCILED"})
            r.deleteEntry(x.b,x.source)
            val zero=input(r,x,0).copy(bills=input(r,x,0).bills+db.financialDao().bills(x.b,x.target).map{ReconciledBillObligation(it.id,0,20)})
            val cancelled=r.previewArrangementReconciliation(x.b,zero)
            assertTrue(cancelled.calculation.toString(),cancelled.calculation is Calculation.Ready);r.commitArrangementReconciliation(cancelled,true)
            var current=r.snapshot(r.dao.book(x.b)!!).first();var graph=db.financialDao().graph(x.b).first()!!
            assertEquals(0L,current.balance(current.accounts.first{it.id==x.card}));assertEquals(40L,graph.bills.filter{it.planId==x.target}.sumOf{current.billOutstanding(it).totalMinor})
            assertFalse(r.financialPredictionStale(current,graph,graph.plans.first{it.id==x.target}))
            r.restoreEntry(x.b,x.source)
            val restored=r.previewArrangementReconciliation(x.b,input(r,x,1200))
            assertTrue(restored.calculation.toString(),restored.calculation is Calculation.Ready);r.commitArrangementReconciliation(restored,true)
            current=r.snapshot(r.dao.book(x.b)!!).first();graph=db.financialDao().graph(x.b).first()!!
            assertEquals(1200L,current.balance(current.accounts.first{it.id==x.card}));assertEquals(listOf(620L,620L),graph.bills.filter{it.planId==x.target}.map{current.billOutstanding(it).totalMinor})
            assertFalse(r.financialPredictionStale(current,graph,graph.plans.first{it.id==x.target}));assertEquals(applied,graph.arrangements.single())
        }finally{db.close();context.deleteDatabase(name)}
    }
    @Test fun deletingAndRestoringAnEarlierClearingCanBeReconciledWithoutAnotherCashPosting()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        try {
            val r=LedgerRepository(db,clock);val x=fixture(r,prepaid=true);val original=db.financialDao().bills(x.b,x.old).single()
            r.deleteEntry(x.b,x.payment!!)
            val first=r.previewArrangementReconciliation(x.b,input(r,x,1000))
            assertTrue(first.calculation.toString(),first.calculation is Calculation.Ready);r.commitArrangementReconciliation(first,true)
            r.restoreEntry(x.b,x.payment)
            var f=db.financialDao().graph(x.b).first()!!;var s=r.snapshot(r.dao.book(x.b)!!).first()
            assertTrue(r.financialPredictionStale(s,f,f.plans.first{it.id==x.target}))
            val before=s.storedPostings
            val second=r.previewArrangementReconciliation(x.b,input(r,x,900))
            assertTrue(second.calculation.toString(),second.calculation is Calculation.Ready);r.commitArrangementReconciliation(second,true)
            f=db.financialDao().graph(x.b).first()!!;s=r.snapshot(r.dao.book(x.b)!!).first()
            assertEquals(before,s.storedPostings);assertEquals(9900L,s.balance(s.accounts.first{it.id==x.bank}));assertEquals(900L,s.balance(s.accounts.first{it.id==x.card}))
            assertEquals(0L,s.billOutstanding(original).principalMinor);assertEquals(listOf(450L,450L),db.financialDao().bills(x.b,x.target).map{it.principalMinor})
            assertFalse(r.financialPredictionStale(s,f,f.plans.first{it.id==x.target}));assertEquals(2,s.visibleEvents.size)
        }finally{db.close()}
    }
    @Test fun refundsCanBeSplitBetweenRetainedAndNewPrincipalThenEditedAndDeleted()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        try {
            val r=LedgerRepository(db,clock);val x=fixture(r,retain=true)
            val bills=db.financialDao().bills(x.b,x.old);val past=bills.first{it.date=="2026-10-10"};val future=bills.first{it.id!=past.id}
            val refund=r.saveRefund(x.b,RefundInput(x.source,Money(Currency.JPY,100),x.card,Money(Currency.JPY,100),Money(Currency.JPY,100),"2026-10-27",true))
            fun reviewInput(targetRefund:Long)=ArrangementReconciliationInput(x.target,"2026-10-25",800,listOf(ReconciledBillObligation(past.id,150,10),ReconciledBillObligation(future.id,0,0)),listOf(ReconciledRefundShare(refund,targetRefund,listOf(ReconciledRefundBill(past.id,50)))))
            var review=r.previewArrangementReconciliation(x.b,reviewInput(50));assertTrue(review.calculation.toString(),review.calculation is Calculation.Ready)
            assertEquals(listOf(400L,350L),review.prediction!!.bills.map{it.principalMinor});r.commitArrangementReconciliation(review,true)
            r.saveRefund(x.b,RefundInput(x.source,Money(Currency.JPY,150),x.card,Money(Currency.JPY,150),Money(Currency.JPY,150),"2026-10-27",true,id=refund))
            review=r.previewArrangementReconciliation(x.b,reviewInput(100));assertTrue(review.calculation.toString(),review.calculation is Calculation.Ready);r.commitArrangementReconciliation(review,true)
            var s=r.snapshot(r.dao.book(x.b)!!).first();var f=db.financialDao().graph(x.b).first()!!
            assertEquals(850L,s.balance(s.accounts.first{it.id==x.card}));assertEquals(150L,s.billOutstanding(past).principalMinor)
            assertEquals(listOf(400L,300L),db.financialDao().bills(x.b,x.target).map{it.principalMinor});assertFalse(r.financialPredictionStale(s,f,f.plans.first{it.id==x.target}))
            assertEquals(100L,limitPayments(s,f,x.card).single{it.eventId==refund}.installmentMinor)
            r.deleteEntry(x.b,refund)
            val last=r.previewArrangementReconciliation(x.b,reviewInput(0).copy(bills=listOf(ReconciledBillObligation(past.id,200,10),ReconciledBillObligation(future.id,0,0)),refunds=emptyList()))
            assertTrue(last.calculation.toString(),last.calculation is Calculation.Ready);r.commitArrangementReconciliation(last,true)
            s=r.snapshot(r.dao.book(x.b)!!).first();f=db.financialDao().graph(x.b).first()!!
            assertEquals(1000L,s.balance(s.accounts.first{it.id==x.card}));assertEquals(200L,s.billOutstanding(past).principalMinor)
            assertEquals(listOf(400L,400L),db.financialDao().bills(x.b,x.target).map{it.principalMinor});assertFalse(r.financialPredictionStale(s,f,f.plans.first{it.id==x.target}))
            assertEquals(bills,db.financialDao().bills(x.b,x.old));assertEquals(1,s.visibleEvents.size);assertEquals(10000L,s.balance(s.accounts.first{it.id==x.bank}))
        }finally{db.close()}
    }
    @Test fun laterClearingCanMoveFromOriginalToNewObligationWithoutPayingAgain()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        try {
            val r=LedgerRepository(db,clock);val x=fixture(r,prepaid=true)
            val old=db.financialDao().bills(x.b,x.old).single();val first=db.financialDao().bills(x.b,x.target).first()
            r.commitFinancialLedger(r.previewRepayment(x.b,FinancialRepaymentInput(x.bank,x.card,Money(Currency.JPY,100),"2026-10-20",Money(Currency.JPY,100),Money(Currency.JPY,100),Money(Currency.JPY,0),Money(Currency.JPY,0),platformConfirmed=true,clearingDate="2026-10-27",allocations=listOf(RepaymentAllocationInput(old.id,null,100,0,0)),eventId=x.payment)))
            val before=r.snapshot(r.dao.book(x.b)!!).first();val allocation=before.repaymentAllocations.single()
            val review=r.previewArrangementReconciliation(x.b,ArrangementReconciliationInput(x.target,"2026-10-25",900,
                listOf(ReconciledBillObligation(old.id,100,0),ReconciledBillObligation(first.id,350,20)),emptyList(),
                listOf(ReconciledClearingMove(allocation.id,first.id,100,0,0))))
            assertTrue(review.calculation.toString(),review.calculation is Calculation.Ready)
            r.commitArrangementReconciliation(review,true)
            val after=r.snapshot(r.dao.book(x.b)!!).first();val f=db.financialDao().graph(x.b).first()!!
            assertEquals(before.storedPostings,after.storedPostings);assertEquals(before.expenseComponents,after.expenseComponents)
            assertEquals(100L,after.billOutstanding(old).principalMinor);assertEquals(350L,after.billOutstanding(f.bills.first{it.id==first.id}).principalMinor)
            assertEquals(first.id,after.repaymentAllocations.single().billId)
            assertEquals(9900L,after.balance(after.accounts.first{it.id==x.bank}));assertEquals(900L,after.balance(after.accounts.first{it.id==x.card}))
            assertFalse(r.financialPredictionStale(after,f,f.plans.first{it.id==x.target}))
        }finally{db.close()}
    }
}
