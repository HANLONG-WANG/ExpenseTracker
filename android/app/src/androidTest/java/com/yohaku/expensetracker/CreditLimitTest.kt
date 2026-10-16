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
class CreditLimitTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val clock=Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"),ZoneId.of("Asia/Tokyo"))
    private fun config(account:String,used:Long=0,recovery:LimitRecovery=LimitRecovery.PAYMENT_DATE)=CreditLimitInput(account,10000,null,null,CreditLimitSharing.TOTAL_ONLY,null,"2026-10-15","2026-10-15",used,null,null,recovery,CreditLimitUsageBasis.PRINCIPAL_ONLY,"Asia/Tokyo",CreditLimitUsageStart.TRANSACTION_DATE)
    private suspend fun value(repo:LedgerRepository,book:String,account:String):AccountCreditLimit {
        val s=repo.snapshot(repo.dao.book(book)!!).first()
        return accountCreditLimit(s,repo.db.financialDao().graph(book).first()!!,repo.db.creditLimitDao().graph(book).first()!!,account,"2026-10-15")
    }
    @Test fun repaymentRestoresOnlyPrincipalAndActualPartialRecoveryOverridesTheRuleWithoutMovingCash()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        try {
            val repo=LedgerRepository(db,clock);val b=repo.createBook("枠",Currency.JPY)
            val card=repo.saveAccount(b,"カード",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,4000),"2026-10-01")
            val bank=repo.saveAccount(b,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
            assertTrue(repo.commitCreditLimit(repo.previewCreditLimit(b,config(card,4000,LimitRecovery.PLATFORM_CONFIRMATION))))
            val input=FinancialRepaymentInput(bank,card,Money(Currency.JPY,1100),"2026-10-15",Money(Currency.JPY,1100),Money(Currency.JPY,1000),Money(Currency.JPY,0),Money(Currency.JPY,100),platformConfirmed=false)
            val paid=repo.commitFinancialLedger(repo.previewRepayment(b,input))!!
            assertEquals(6000L,value(repo,b,card).total.availableMinor)
            val recovery=repo.previewCreditLimitRecovery(b,CreditLimitRecoveryInput(card,paid,"2026-10-15",400,null,null))
            assertEquals(6400L,recovery.estimate!!.total.availableMinor);assertTrue(repo.commitCreditLimit(recovery))
            assertEquals(6400L,value(repo,b,card).total.availableMinor)
            val before=repo.snapshot(repo.dao.book(b)!!).first()
            assertEquals(8900L,before.balance(before.accounts.first{it.id==bank}));assertEquals(4000L,before.balance(before.accounts.first{it.id==card}))
            assertEquals(0L,before.monthExpense("2026-10"))
            try{repo.previewCreditLimitRecovery(b,CreditLimitRecoveryInput(card,paid,"2026-10-15",701,null,null));fail("recovery exceeds principal")}catch(_:IllegalArgumentException){}
            repo.commitFinancialLedger(repo.previewRepayment(b,input.copy(eventId=paid,platformConfirmed=true,clearingDate="2026-10-15")))
            assertNull(value(repo,b,card).total.availableMinor)
            val changed=repo.previewRepayment(b,input.copy(eventId=paid,principal=Money(Currency.JPY,900),fee=Money(Currency.JPY,200),platformConfirmed=true,clearingDate="2026-10-15"))
            assertNull(value(repo,b,card).total.availableMinor)
            repo.commitFinancialLedger(changed)
            assertNull(value(repo,b,card).total.availableMinor)
            repo.voidCreditLimitRecovery(b,recovery.recovery!!.id)
            assertEquals(6900L,value(repo,b,card).total.availableMinor)
            val after=repo.snapshot(repo.dao.book(b)!!).first()
            assertEquals(8900L,after.balance(after.accounts.first{it.id==bank}));assertEquals(3100L,after.balance(after.accounts.first{it.id==card}));assertEquals(1,after.events.size);assertEquals(200L,after.monthExpense("2026-10"))
            assertNotNull(db.creditLimitDao().graph(b).first()!!.recoveries.single().voidedAt)
        }finally{db.close()}
    }
    @Test fun anchorsDetectDeletedAndBackdatedFactsWhileMissingModesOnlyInvalidateSubLimitsAndStalePreviewsCannotCommit()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        try {
            val repo=LedgerRepository(db,clock);val b=repo.createBook("基準",Currency.JPY)
            val card=repo.saveAccount(b,"カード",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,0),"2026-10-01")
            val category=repo.saveCategory(b,"生活",EntryKind.EXPENSE,null)
            val old=repo.saveEntry(b,EntryDraft(accountId=card,categoryId=category,amount="1000",date="2026-10-15"))
            assertTrue(repo.commitCreditLimit(repo.previewCreditLimit(b,config(card,1000).copy(sharing=CreditLimitSharing.SEPARATE_CAPS,installmentMinor=6000,revolvingMinor=5000,usedInstallmentMinor=0,usedRevolvingMinor=0))))
            val stale=repo.previewCreditLimit(b,config(card,1000))
            val additional=repo.saveEntry(b,EntryDraft(accountId=card,categoryId=category,amount="600",date="2026-10-15"))
            assertFalse(repo.commitCreditLimit(stale))
            val v=value(repo,b,card)
            assertEquals(8400L,v.total.availableMinor);assertNull(v.installment.availableMinor);assertTrue("limit.unknownMode" in v.issues)
            repo.deleteEntry(b,old);assertNull(value(repo,b,card).total.availableMinor);assertTrue("limit.basisChanged" in value(repo,b,card).issues)
            repo.restoreEntry(b,old);assertEquals(8400L,value(repo,b,card).total.availableMinor)
            repo.commitCreditLimit(repo.previewCreditLimit(b,config(card,1600)))
            repo.saveEntry(b,EntryDraft(accountId=card,categoryId=category,amount="200",date="2026-10-14"))
            assertTrue("limit.backdated" in value(repo,b,card).issues)
            assertEquals(2,db.creditLimitDao().graph(b).first()!!.configs.size)
            assertTrue(repo.snapshot(repo.dao.book(b)!!).first().visibleEvents.any{it.id==additional})
        }finally{db.close()}
    }
    @Test fun sharedCapacityIsIndependentAndCurrencyIsolatedWithAtomicVersionHistory()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        try {
            val repo=LedgerRepository(db,clock);val b=repo.createBook("共通",Currency.JPY)
            suspend fun card(name:String,c:Currency=Currency.JPY)=repo.saveAccount(b,name,AccountType.CREDIT,c,Money(c,0),"2026-10-01")
            val a=card("A");val other=card("B");val foreign=card("外貨",Currency.CNY)
            for(id in listOf(a,other))repo.commitCreditLimit(repo.previewCreditLimit(b,config(id)))
            try{repo.previewCreditLimitGroup(b,CreditLimitGroupInput("異通貨",Currency.JPY,12000,3000,"2026-10-15",listOf(a,foreign)));fail("mixed currency")}catch(_:IllegalArgumentException){}
            val group=repo.previewCreditLimitGroup(b,CreditLimitGroupInput("共通枠",Currency.JPY,12000,3000,"2026-10-15",listOf(a,other)))
            assertTrue(repo.commitCreditLimit(group))
            val id=group.group!!.id
            val category=repo.saveCategory(b,"生活",EntryKind.EXPENSE,null)
            repo.saveEntry(b,EntryDraft(accountId=a,categoryId=category,amount="2000",date="2026-10-15"))
            assertEquals(8000L,value(repo,b,a).total.availableMinor);assertEquals(7000L,value(repo,b,a).effectiveTotalMinor);assertEquals(7000L,value(repo,b,other).effectiveTotalMinor)
            val observation=repo.previewCreditLimitObservation(b,null,id,"TOTAL",6800,"2026-10-15T00:00:00Z")
            repo.commitCreditLimit(observation)
            val update=repo.previewCreditLimitGroup(b,CreditLimitGroupInput("更新",Currency.JPY,15000,5000,"2026-10-15",listOf(a,other),id))
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_limit_revision BEFORE INSERT ON credit_limit_group_revisions WHEN NEW.version=2 BEGIN SELECT RAISE(ABORT,'rollback check'); END")
            try{repo.commitCreditLimit(update);fail("expected transaction failure")}catch(_:android.database.sqlite.SQLiteException){}
            assertEquals("共通枠",db.creditLimitDao().graph(b).first()!!.groups.single().name);assertEquals(1,db.creditLimitDao().groupRevisions(b,id).first().size)
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_limit_revision")
            assertTrue(repo.commitCreditLimit(update));assertEquals(2,db.creditLimitDao().groupRevisions(b,id).first().size)
            assertEquals(6800L,db.creditLimitDao().graph(b).first()!!.observations.single().availableMinor)
            repo.commitCreditLimit(repo.previewCreditLimit(b,config(a,2000)))
            assertNull(value(repo,b,a).effectiveTotalMinor)
            assertTrue(repo.snapshot(repo.dao.book(b)!!).first().events.size==1)
        }finally{db.close()}
    }
    @Test fun installmentAndRevolvingShareAnExplicitSubpoolInsteadOfAddingSeparateLimits()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        try {
            val repo=LedgerRepository(db,clock);val b=repo.createBook("子枠",Currency.JPY)
            val a=repo.saveAccount(b,"カード",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,0),"2026-10-01")
            val bank=repo.saveAccount(b,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
            repo.commitCreditLimit(repo.previewCreditLimit(b,config(a).copy(sharing=CreditLimitSharing.SHARED_SUBPOOL,combinedMinor=5000,usedInstallmentMinor=0,usedRevolvingMinor=0)))
            val category=repo.saveCategory(b,"生活",EntryKind.EXPENSE,null)
            for((mode,amount) in listOf(FinanceMode.INSTALLMENT to 2000L,FinanceMode.REVOLVING to 1000L)) {
                val source=repo.saveEntry(b,EntryDraft(accountId=a,categoryId=category,amount=amount.toString(),date="2026-10-15"))
                repo.commitManualFinancialPlan(repo.previewManualFinancialPlan(b,ManualFinancialPlanInput(a,mode.name,mode,listOf(KnownBillInput("2026-10-25",amount,amount,0,bank)),sourceEventId=source)))
            }
            val estimate=value(repo,b,a)
            assertEquals(7000L,estimate.total.availableMinor);assertEquals(3000L,estimate.total.usedMinor)
            assertEquals(2000L,estimate.installment.availableMinor);assertEquals(2000L,estimate.revolving.availableMinor);assertTrue(estimate.issues.isEmpty())
            val s=repo.snapshot(repo.dao.book(b)!!).first();assertEquals(3000L,s.balance(s.accounts.first{it.id==a}));assertEquals(2,s.events.size)
        }finally{db.close()}
    }
    @Test fun onlyCircularBorrowingUsesTheLineAndPaymentDateRecoveryCanPrecedePlatformClearing()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        try {
            val repo=LedgerRepository(db,clock);val b=repo.createBook("循環",Currency.JPY)
            val loan=repo.saveAccount(b,"借入",AccountType.LOAN,Currency.JPY,Money(Currency.JPY,0),"2026-10-01")
            val bank=repo.saveAccount(b,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
            repo.commitCreditLimit(repo.previewCreditLimit(b,config(loan).copy(sharing=null)))
            val line=repo.previewBorrowing(b,FinancialBorrowingInput(loan,"循環借入",FinanceMode.CREDIT_LINE,Money(Currency.JPY,3000),"2026-10-15",bank,Money(Currency.JPY,3000)))
            assertEquals(10000L,line.limitImpacts.single().before.effectiveTotalMinor);assertEquals(7000L,line.limitImpacts.single().after.effectiveTotalMinor)
            val event=repo.commitFinancialLedger(line)!!
            repo.commitFinancialLedger(repo.previewBorrowing(b,FinancialBorrowingInput(loan,"固定借入",FinanceMode.FIXED_LOAN,Money(Currency.JPY,5000),"2026-10-15",bank,Money(Currency.JPY,5000))))
            assertEquals(7000L,value(repo,b,loan).effectiveTotalMinor)
            val contract=db.financialDao().fundingContract(b,event)!!
            val repay=repo.previewRepayment(b,FinancialRepaymentInput(bank,loan,Money(Currency.JPY,1100),"2026-10-15",Money(Currency.JPY,1100),Money(Currency.JPY,1000),Money(Currency.JPY,0),Money(Currency.JPY,100),platformConfirmed=false,allocations=listOf(RepaymentAllocationInput(null,contract.id,1000,0,100))))
            assertEquals(8000L,repay.limitImpacts.single().after.effectiveTotalMinor)
            repo.commitFinancialLedger(repay)
            assertEquals(8000L,value(repo,b,loan).effectiveTotalMinor)
            val state=repo.snapshot(repo.dao.book(b)!!).first()
            assertEquals(8000L,state.balance(state.accounts.first{it.id==loan}));assertEquals(16900L,state.balance(state.accounts.first{it.id==bank}))
        }finally{db.close()}
    }
    @Test fun versionEighteenMigratesWithoutChangingLedgerAndPersistsLimitEvidenceAcrossReopening()=runBlocking {
        val name="limits-upgrade.db";context.deleteDatabase(name)
        var db=Room.databaseBuilder(context,YohakuDatabase::class.java,name).build()
        try {
            var repo=LedgerRepository(db,clock);val b=repo.createBook("移行",Currency.JPY)
            val a=repo.saveAccount(b,"カード",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,4000),"2026-10-01")
            db.close();val old=SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE)
            try{dropCreditLimitFixtureTables(old);val schema=JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("room-v18-schema.json").bufferedReader().readText()).getJSONObject("database");old.execSQL("UPDATE room_master_table SET identity_hash=? WHERE id=42",arrayOf(schema.getString("identityHash")));old.execSQL("DROP TABLE IF EXISTS financial_drafts");old.version=18}finally{old.close()}
            db=Room.databaseBuilder(context,YohakuDatabase::class.java,name).addMigrations(MIGRATION_18_19,MIGRATION_19_20,MIGRATION_20_21,MIGRATION_21_22,MIGRATION_22_23,com.yohaku.expensetracker.data.db.MIGRATION_23_24,com.yohaku.expensetracker.data.db.MIGRATION_24_25).build();repo=LedgerRepository(db,clock)
            assertTrue(db.creditLimitDao().graph(b).first()!!.configs.isEmpty())
            repo.commitCreditLimit(repo.previewCreditLimit(b,config(a,4000)))
            repo.commitCreditLimit(repo.previewCreditLimitObservation(b,a,null,"TOTAL",6100,clock.instant().toString()))
            db.close();db=Room.databaseBuilder(context,YohakuDatabase::class.java,name).build();repo=LedgerRepository(db,clock)
            assertEquals(6000L,value(repo,b,a).total.availableMinor);assertEquals(6100L,db.creditLimitDao().graph(b).first()!!.observations.single().availableMinor)
            val s=repo.snapshot(repo.dao.book(b)!!).first();assertEquals(4000L,s.balance(s.accounts.first{it.id==a}));assertTrue(s.events.isEmpty())
        }finally{db.close();context.deleteDatabase(name)}
    }
    @Test fun historicalRuleRecoveryCannotOverrideTheOperationalLimitConfiguration()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        try {
            val repo=LedgerRepository(db,clock);val b=repo.createBook("回復条件",Currency.JPY)
            val card=repo.saveAccount(b,"カード",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,1000),"2026-10-01")
            val bank=repo.saveAccount(b,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
            val legacy=FinancialRuleVersion("",b,card,FinanceMode.REVOLVING,"2026-10-01","Asia/Tokyo",billing=BillingRule(31,true,1,27,bank),
                revolving=RevolvingRule(InterestRule("0.12",ChargeFrequency.MONTHLY,BalanceBasis.OPENING_BALANCE,rounding=ChargeRounding.PERIOD_TOTAL,roundingMode="DOWN",firstCharge=FirstCharge.NORMAL),PaymentMethod.TOTAL_PAYMENT,200,allocation=AllocationOrder.FEES_FIRST,automatic=false),limitRecovery=LimitRecovery.PAYMENT_DATE)
            repo.commitFinancialRule(repo.previewFinancialRule(b,card,legacy))
            val oldRule=db.financialDao().rules(b,card).single()
            assertNull(value(repo,b,card).effectiveTotalMinor)
            val input=config(card,1000,LimitRecovery.PLATFORM_CONFIRMATION).copy(effectiveFrom="2026-10-01",basisDate="2026-10-01")
            assertTrue(repo.commitCreditLimit(repo.previewCreditLimit(b,input)))
            val repayment=FinancialRepaymentInput(bank,card,Money(Currency.JPY,100),"2026-10-15",Money(Currency.JPY,100),Money(Currency.JPY,100),Money(Currency.JPY,0),Money(Currency.JPY,0),platformConfirmed=false)
            val event=repo.commitFinancialLedger(repo.previewRepayment(b,repayment))!!
            assertEquals(9000L,value(repo,b,card).effectiveTotalMinor)
            repo.commitFinancialRule(repo.previewFinancialRule(b,card,legacy.copy(limitRecovery=null)))
            assertEquals(oldRule,db.financialDao().rules(b,card).first{it.id==oldRule.id})
            assertEquals(1,db.creditLimitDao().graph(b).first()!!.configs.size)
            assertEquals(9000L,value(repo,b,card).effectiveTotalMinor)
            val before=repo.snapshot(repo.dao.book(b)!!).first()
            assertTrue(repo.commitCreditLimit(repo.previewCreditLimit(b,input.copy(recovery=LimitRecovery.PAYMENT_DATE))))
            assertEquals(9100L,value(repo,b,card).effectiveTotalMinor)
            val after=repo.snapshot(repo.dao.book(b)!!).first()
            assertEquals(before.events,after.events);assertEquals(before.postings,after.postings)
            assertEquals(9900L,after.balance(after.accounts.first{it.id==bank}));assertEquals(1000L,after.balance(after.accounts.first{it.id==card}))
            repo.commitFinancialLedger(repo.previewRepayment(b,repayment.copy(eventId=event,platformConfirmed=true,clearingDate="2026-10-15")))
            assertEquals(9100L,value(repo,b,card).effectiveTotalMinor)
            val confirmed=repo.snapshot(repo.dao.book(b)!!).first()
            assertEquals(900L,confirmed.balance(confirmed.accounts.first{it.id==card}));assertEquals(1,confirmed.events.size)
        }finally{db.close()}
    }

}
