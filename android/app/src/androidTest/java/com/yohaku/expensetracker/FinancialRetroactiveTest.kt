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
class FinancialRetroactiveTest {
    @Test fun laterClearingIsExplicitlyRetargetedWithoutAnotherPaymentAndRollsBackWithTheArrangement()=runBlocking{exercise(false)}
    @Test fun principalCanMoveWhileTheActualFeeStaysOnItsOriginalBill()=runBlocking{exercise(true)}
    @Test fun keepingTheOriginalClearingPreservesItsRevisionAndDeletingItReopensTheRetainedObligation()=runBlocking{exercise(true,true)}
    private suspend fun exercise(retainFee:Boolean,keepOriginal:Boolean=false) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file="retroactive-$retainFee-$keepOriginal.db";context.deleteDatabase(file)
        var db=Room.databaseBuilder(context,YohakuDatabase::class.java,file).build()
        try {
            val clock=Clock.fixed(Instant.parse("2026-10-25T00:00:00Z"),ZoneId.of("Asia/Tokyo"))
            var r=LedgerRepository(db,clock)
            val b=r.createBook("追溯",Currency.JPY)
            val bank=r.saveAccount(b,"银行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
            val card=r.saveAccount(b,"卡",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
            val old=r.commitManualFinancialPlan(r.previewManualFinancialPlan(b,ManualFinancialPlanInput(card,"原义务",FinanceMode.SINGLE,listOf(KnownBillInput("2026-11-05",10100,10000,100,bank)),principalMinor=10000,opening=true)))!!
            val bill=db.financialDao().bills(b,old).single()
            val payment=r.commitFinancialLedger(r.previewRepayment(b,FinancialRepaymentInput(bank,card,Money(Currency.JPY,2050),"2026-10-20",Money(Currency.JPY,2050),Money(Currency.JPY,2000),Money(Currency.JPY,0),Money(Currency.JPY,50),platformConfirmed=true,clearingDate="2026-10-20",allocations=listOf(RepaymentAllocationInput(bill.id,null,2000,0,50)))))!!
            val rule=FinancialRuleVersion("",b,card,FinanceMode.INSTALLMENT,"2026-10-01","Asia/Tokyo",BillingRule(31,true,1,5,bank),installment=InstallmentRule(listOf(2),InstallmentFeeMethod.TOTAL_AMOUNT,totalFeeMinor=100,principalRemainder=RemainderPlacement.LAST,feeRemainder=RemainderPlacement.FIRST,roundingMode="DOWN"))
            r.commitFinancialRule(r.previewFinancialRule(b,card,rule))
            val row=db.financialDao().repaymentAllocations(b,payment).single()
            val input=FinancialArrangementInput(old,"追溯两期",FinanceMode.INSTALLMENT,FinancialScheduleTerms("2026-10-15",listOf("2026-11-05","2026-12-05")),bank,OriginalFeeTreatment.CANCELLED_BY_PLATFORM,0)
            assertEquals(Calculation.Incomplete(listOf("arrangement.laterClearing")),r.previewFinancialArrangement(b,input).calculation)
            val moves=if(keepOriginal)listOf(ArrangementClearingMove(row.id,null,2000,0,50))else if(retainFee)listOf(ArrangementClearingMove(row.id,1,2000,0,0),ArrangementClearingMove(row.id,null,0,0,50))else listOf(ArrangementClearingMove(row.id,1,2000,0,50))
            assertTrue(r.previewFinancialArrangement(b,input.copy(clearingMoves=moves.map{it.copy(principalMinor=1999)})).calculation is Calculation.Incomplete)
            val review=r.previewFinancialArrangement(b,input.copy(clearingMoves=moves))
            assertTrue(review.calculation.toString(),review.calculation is Calculation.Ready)
            assertEquals(if(keepOriginal)8000L else 10000L,review.target!!.principalMinor)
            assertEquals(if(keepOriginal)listOf(4000L,4000L)else listOf(5000L,5000L),review.bills.map{it.principalMinor})
            assertEquals(0L,review.comparison!!.proposedRemainingPrincipalMinor)
            val before=r.snapshot(r.dao.book(b)!!).first();val originalEvent=r.dao.event(b,payment)!!
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_retro BEFORE INSERT ON financial_plan_revisions WHEN NEW.reason='ARRANGEMENT_APPLIED' BEGIN SELECT RAISE(ABORT,'retro atomic'); END")
            try{r.commitFinancialArrangement(review,"2026-10-15T00:00:00Z",true);fail("rollback")}catch(_:android.database.sqlite.SQLiteException){}
            assertEquals(originalEvent,r.dao.event(b,payment));assertEquals(row,db.financialDao().repaymentAllocations(b,payment).single());assertEquals(1,db.financialDao().graph(b).first()!!.plans.size)
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_retro")
            val target=r.commitFinancialArrangement(review,"2026-10-15T00:00:00Z",true)!!
            var s=r.snapshot(r.dao.book(b)!!).first();var f=db.financialDao().graph(b).first()!!
            assertEquals(before.storedPostings,s.storedPostings);assertEquals(before.expenseComponents,s.expenseComponents);assertEquals(1,s.events.size)
            assertEquals(8000L,s.balance(s.accounts.first{it.id==card}));assertEquals(7950L,s.balance(s.accounts.first{it.id==bank}));assertEquals(50L,s.monthExpense("2026-10"))
            assertEquals(originalEvent.revision+if(keepOriginal)0 else 1,r.dao.event(b,payment)!!.revision)
            val newBill=f.bills.first{it.planId==target&&it.number==1}
            assertEquals(!keepOriginal,s.repaymentAllocations.any{it.billId==newBill.id});assertEquals(if(keepOriginal)4050L else if(retainFee)3050L else 3000L,s.billOutstanding(newBill).totalMinor)
            assertEquals(bill,f.bills.first{it.id==bill.id});assertEquals(0L,s.billOutstanding(bill).totalMinor)
            assertFalse(r.financialPredictionStale(s,f,f.plans.first{it.id==target}))
            assertEquals(0L,s.financialAccountSummary(f,card,"2026-10-25").unassignedOpeningMinor)
            val overlapping=r.previewFinancialPool(b,FinancialPredictionInput(card,"不能重复分配期初",FinanceMode.REVOLVING,FinancialScheduleTerms("2026-10-01",listOf("2026-11-05"),listOf(AccrualWindow("2026-10-01","2026-10-31"))),paymentAccountId=bank,pool=FinancialPoolInput(1,PoolSourceDate.TRANSACTION)))
            assertTrue((overlapping.calculation as Calculation.Incomplete).fields.contains("pool.opening"))
            val saved=r.json.decodeFromString<FinancialPredictionInput>(f.plans.first{it.id==target}.termsJson!!)
            assertNotNull(r.commitFinancialPrediction(r.previewFinancialPrediction(b,saved.copy(planId=target))))
            r.deleteEntry(b,payment);s=r.snapshot(r.dao.book(b)!!).first();f=db.financialDao().graph(b).first()!!
            assertEquals(!keepOriginal,r.financialPredictionStale(s,f,f.plans.first{it.id==target}))
            if(keepOriginal){assertTrue(f.plans.first{it.id==old}.active);assertEquals(2050L,s.billOutstanding(bill).totalMinor)}
            assertNotNull(r.commitFinancialPrediction(r.previewFinancialPrediction(b,saved.copy(planId=target))))
            r.restoreEntry(b,payment)
            assertNotNull(r.commitFinancialPrediction(r.previewFinancialPrediction(b,saved.copy(planId=target))))
            db.close();db=Room.databaseBuilder(context,YohakuDatabase::class.java,file).build();r=LedgerRepository(db,clock)
            s=r.snapshot(r.dao.book(b)!!).first();f=db.financialDao().graph(b).first()!!
            assertFalse(r.financialPredictionStale(s,f,f.plans.first{it.id==target}))
            assertEquals(1,s.visibleEvents.size);assertEquals(2000L,s.repaymentAllocations.sumOf{it.principalMinor!!})
            assertEquals(7950L,s.balance(s.accounts.first{it.id==bank}));assertEquals(8000L,s.balance(s.accounts.first{it.id==card}))
        }finally{db.close();context.deleteDatabase(file)}
    }
}
