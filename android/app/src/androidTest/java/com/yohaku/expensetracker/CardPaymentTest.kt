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
class CardPaymentTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val clock=Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"),ZoneId.of("Asia/Tokyo"))
    private data class Setup(val repo:LedgerRepository,val book:String,val card:String,val bank:String,val category:String) {
        fun draft(amount:String="1000",date:String="2026-10-15",choice:String?="SINGLE",periods:String="")=EntryDraft(accountId=card,categoryId=category,amount=amount,date=date,merchant="買物",cardPaymentChoice=choice,cardPaymentPeriods=periods)
        suspend fun snapshot()=repo.snapshot(repo.dao.book(book)!!).first()
    }
    private suspend fun setup(db:YohakuDatabase):Setup {
        val r=LedgerRepository(db,clock);val b=r.createBook("カード",Currency.JPY)
        val card=r.saveAccount(b,"カード",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,0),"2026-10-01")
        val bank=r.saveAccount(b,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,20000),"2026-10-01")
        val cat=r.saveCategory(b,"生活",EntryKind.EXPENSE,null)
        val v=Setup(r,b,card,bank,cat)
        r.commitFinancialRule(r.previewFinancialRule(b,card,rule(v,FinanceMode.SINGLE)))
        r.commitFinancialRule(r.previewFinancialRule(b,card,rule(v,FinanceMode.INSTALLMENT)))
        return v
    }
    private fun rule(v:Setup,mode:FinanceMode,automatic:Boolean?=null,effective:String="2026-10-01")=FinancialRuleVersion("",v.book,v.card,mode,effective,"Asia/Tokyo",BillingRule(20,true,1,5,v.bank),installment=if(mode==FinanceMode.INSTALLMENT)InstallmentRule(listOf(2,3),InstallmentFeeMethod.TOTAL_AMOUNT,totalFeeMinor=60,principalRemainder=RemainderPlacement.LAST,feeRemainder=RemainderPlacement.LAST,roundingMode="HALF_UP")else null,revolving=if(mode==FinanceMode.REVOLVING)RevolvingRule(automatic=automatic)else null)
    @Test fun sourceDatesAndPaymentModesCreateOneObligationWithoutDuplicatingPurchaseAndAggregateByDebitMonth()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        try {
            val v=setup(db);val r=v.repo
            val one=r.saveEntry(v.book,v.draft("1000","2026-10-20"))
            val two=r.saveEntry(v.book,v.draft("2000","2026-10-21"))
            val split=r.saveEntry(v.book,v.draft("3000",choice="INSTALLMENT",periods="3"))
            val unknown=r.saveEntry(v.book,v.draft("400",choice=null))
            val f=db.financialDao().graph(v.book).first()!!;val s=v.snapshot();val summary=s.financialAccountSummary(f,v.card,"2026-10-15")
            assertEquals(4,s.events.size);assertEquals(6400L,s.balance(s.accounts.first{it.id==v.card}));assertEquals(6400L,s.monthExpense("2026-10"));assertEquals(20000L,s.balance(s.accounts.first{it.id==v.bank}))
            assertEquals("2026-11-05",f.bills.single{it.planId==f.plans.single{p->p.sourceEventId==one}.id}.date)
            assertEquals("2026-12-05",f.bills.single{it.planId==f.plans.single{p->p.sourceEventId==two}.id}.date)
            assertEquals(2020L,summary.rows.filter{it.bill.date.startsWith("2026-11")}.statementTotals().totalMinor)
            assertEquals(20L,summary.rows.filter{it.bill.date.startsWith("2026-11")}.statementTotals().feeMinor)
            assertEquals(3020L,summary.rows.filter{it.bill.date.startsWith("2026-12")}.statementTotals().totalMinor)
            assertEquals(listOf(unknown),summary.unplanned.map{it.id});assertEquals(3,f.bills.count{it.planId==f.plans.single{p->p.sourceEventId==split}.id})
            assertEquals(3,f.plans.size);assertTrue(f.plans.all{it.origin=="CARD_ENTRY"})
        }finally{db.close()}
    }
    @Test fun automaticChoiceRetainsPurchaseRuleAndRechecksRelevantRuleWhenPreviewIsSaved()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        try {
            val v=setup(db);val r=v.repo
            val first=r.commitFinancialRule(r.previewFinancialRule(v.book,v.card,rule(v,FinanceMode.REVOLVING,false)))!!
            val draft=v.draft(choice="ACCOUNT_RULE")
            val event=r.saveEntry(v.book,draft)
            assertEquals("SINGLE",r.dao.event(v.book,event)!!.cardPaymentMode);assertEquals(first,r.dao.event(v.book,event)!!.cardPaymentRuleId)
            val pending=r.previewEntry(v.book,draft.copy(amount="800"))
            val second=r.commitFinancialRule(r.previewFinancialRule(v.book,v.card,rule(v,FinanceMode.REVOLVING,true)))!!
            assertNull(r.commitEntryReview(v.book,pending))
            r.saveEntry(v.book,draft.copy(id=event,note="補足"))
            assertEquals(first,r.dao.event(v.book,event)!!.cardPaymentRuleId);assertEquals("SINGLE",r.dao.event(v.book,event)!!.cardPaymentMode)
            r.saveEntry(v.book,draft.copy(id=event,cardPaymentRecheck=true))
            assertEquals(second,r.dao.event(v.book,event)!!.cardPaymentRuleId);assertEquals("REVOLVING",r.dao.event(v.book,event)!!.cardPaymentMode)
            val next=r.saveEntry(v.book,draft)
            assertEquals(second,r.dao.event(v.book,next)!!.cardPaymentRuleId);assertEquals("REVOLVING",r.dao.event(v.book,next)!!.cardPaymentMode)
            val explicit=r.saveEntry(v.book,v.draft(choice="INSTALLMENT",periods="2"))
            assertEquals("INSTALLMENT",r.dao.event(v.book,explicit)!!.cardPaymentMode)
            val unsupported=r.saveEntry(v.book,v.draft(choice="INSTALLMENT",periods="4"))
            assertTrue(db.financialDao().plans(v.book,v.card).none{it.active&&it.sourceEventId==unsupported})
            assertTrue(v.snapshot().financialAccountSummary(db.financialDao().graph(v.book).first()!!,v.card,"2026-10-15").unplanned.any{it.id==next})
            r.saveDraft(v.book,draft.copy(cardPaymentChoice="INSTALLMENT",cardPaymentPeriods="3"))
            assertEquals("3",v.snapshot().draft!!.cardPaymentPeriods)
        }finally{db.close()}
    }
    @Test fun correctingOnlyLocalPredictionsPreservesVersionsAndPendingPaymentsDoNotRequireTheSameCashTwice()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        try {
            val v=setup(db);val r=v.repo;val d=v.draft("3000")
            val source=r.saveEntry(v.book,d)
            val original=db.financialDao().plans(v.book,v.card).single()
            r.saveEntry(v.book,d.copy(id=source,cardPaymentChoice="INSTALLMENT",cardPaymentPeriods="3"))
            val f=db.financialDao().graph(v.book).first()!!
            assertFalse(f.plans.first{it.id==original.id}.active);assertEquals(2,db.financialDao().planRevisions(v.book,original.id).first().size)
            val current=f.plans.single{it.active};val bill=f.bills.first{it.planId==current.id&&it.number==1}
            r.commitFinancialLedger(r.previewRepayment(v.book,FinancialRepaymentInput(v.bank,v.card,Money(Currency.JPY,500),"2026-10-15",Money(Currency.JPY,500),Money(Currency.JPY,480),Money(Currency.JPY,0),Money(Currency.JPY,20),platformConfirmed=false,allocations=listOf(RepaymentAllocationInput(bill.id,null,480,0,20)))))
            var s=v.snapshot();var summary=s.financialAccountSummary(db.financialDao().graph(v.book).first()!!,v.card,"2026-10-15")
            val row=summary.rows.single{it.bill.id==bill.id}
            assertEquals(1020L,row.outstanding.totalMinor);assertEquals(500L,row.pendingTotalMinor);assertEquals(520L,row.cashRequiredMinor)
            r.commitFinancialBillConfirmation(r.previewFinancialBillConfirmation(v.book,bill.id,KnownBillInput("2026-12-05",1020,1000,20,v.bank)))
            r.saveEntry(v.book,d.copy(id=source,cardPaymentChoice="INSTALLMENT",cardPaymentPeriods="3",amount="3300"))
            assertEquals("2026-12-05",db.financialDao().bill(v.book,bill.id)!!.date)
            s=v.snapshot();summary=s.financialAccountSummary(db.financialDao().graph(v.book).first()!!,v.card,"2026-10-15")
            assertEquals(2040L,summary.rows.filter{it.bill.date.startsWith("2026-12")}.statementTotals().totalMinor)
            assertEquals(3300L,s.balance(s.accounts.first{it.id==v.card}));assertEquals(19500L,s.balance(s.accounts.first{it.id==v.bank}));assertEquals(2,s.events.size)
            assertTrue(r.financialPredictionStale(s,db.financialDao().graph(v.book).first()!!,current))
        }finally{db.close()}
    }
    @Test fun versionNineteenUpgradeKeepsUnknownOldChoicesAndSourcePredictionFailureRollsBackThePurchase()=runBlocking {
        val name="card-payment-upgrade.db";context.deleteDatabase(name)
        var db=Room.databaseBuilder(context,YohakuDatabase::class.java,name).build()
        try {
            var v=setup(db);val oldEvent=v.repo.saveEntry(v.book,v.draft(choice=null))
            db.close();val old=SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE)
            try{restoreFixtureTable(old,"room-v19-schema.json","events");val schema=JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("room-v19-schema.json").bufferedReader().readText()).getJSONObject("database");old.execSQL("UPDATE room_master_table SET identity_hash=? WHERE id=42",arrayOf(schema.getString("identityHash")));old.execSQL("DROP TABLE IF EXISTS financial_drafts");restoreFixtureTable(old,"room-v19-schema.json","financial_plans");old.version=19}finally{old.close()}
            db=Room.databaseBuilder(context,YohakuDatabase::class.java,name).addMigrations(MIGRATION_19_20,MIGRATION_20_21,MIGRATION_21_22,MIGRATION_22_23,com.yohaku.expensetracker.data.db.MIGRATION_23_24,com.yohaku.expensetracker.data.db.MIGRATION_24_25).build();v=v.copy(repo=LedgerRepository(db,clock))
            assertNull(v.repo.dao.event(v.book,oldEvent)!!.cardPaymentMode)
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_card_plan BEFORE INSERT ON financial_plan_revisions BEGIN SELECT RAISE(ABORT,'atomic source plan'); END")
            try{v.repo.saveEntry(v.book,v.draft());fail("transaction must fail")}catch(_:android.database.sqlite.SQLiteException){}
            assertEquals(1,v.snapshot().events.size);assertTrue(db.financialDao().plans(v.book,v.card).isEmpty())
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_card_plan")
            val saved=v.repo.saveEntry(v.book,v.draft(choice="INSTALLMENT",periods="3"))
            db.close();db=Room.databaseBuilder(context,YohakuDatabase::class.java,name).build();v=v.copy(repo=LedgerRepository(db,clock))
            assertEquals(3,v.repo.dao.event(v.book,saved)!!.cardPaymentPeriods);assertEquals(2,v.snapshot().events.size);assertEquals(3,db.financialDao().graph(v.book).first()!!.bills.size)
        }finally{db.close();context.deleteDatabase(name)}
    }
}
