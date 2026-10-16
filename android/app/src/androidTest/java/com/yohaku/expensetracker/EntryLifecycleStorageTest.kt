package com.yohaku.expensetracker

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.files.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.finance.*
import com.yohaku.expensetracker.domain.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.time.*
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class EntryLifecycleStorageTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private class MutableClock:Clock(){var now=Instant.parse("2026-10-16T03:00:00Z");override fun instant()=now;override fun getZone():ZoneId=ZoneOffset.UTC;override fun withZone(zone:ZoneId):Clock=Clock.fixed(now,zone)}
    private lateinit var db:YohakuDatabase;private lateinit var r:LedgerRepository;private lateinit var clock:MutableClock;private lateinit var root:File
    private lateinit var book:String;private lateinit var bank:String;private lateinit var category:String
    @Before fun open()=runBlocking {
        db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build();clock=MutableClock();root=File(context.cacheDir,"entry-${UUID.randomUUID()}").apply{mkdirs()};r=LedgerRepository(db,clock,root)
        seed()
    }
    private suspend fun seed(){book=r.createBook("生活",Currency.JPY);bank=r.saveAccount(book,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01");category=r.saveCategory(book,"食事",EntryKind.EXPENSE,null)}
    @After fun close(){db.close();root.deleteRecursively()}
    private suspend fun snapshot()=r.snapshot(r.dao.book(book)!!).first()
    private fun draft(amount:String="1000")=EntryDraft(accountId=bank,categoryId=category,amount=amount,date="2026-10-14")
    private suspend fun apply(id:String?,op:EntryLifecycleOperation){assertTrue(r.commitEntryLifecycle(r.previewEntryLifecycle(book,id,op)))}
    private suspend fun refund(id:String,n:Long)=r.saveRefund(book,RefundInput(id,Money(Currency.JPY,n),bank,Money(Currency.JPY,n),Money(Currency.JPY,n),"2026-10-15",true))
    @Test fun deletionBatchSeparatesIndependentlyTrashedRefundsEvenWithTheSameClock()=runBlocking {
        val original=r.saveEntry(book,draft());val independent=refund(original,100);r.deleteEntry(book,independent)
        val grouped=refund(original,200);r.deleteEntry(book,original)
        assertNotEquals(r.dao.event(book,independent)!!.deletionGroupId,r.dao.event(book,original)!!.deletionGroupId)
        val review=r.previewEntryLifecycle(book,original,EntryLifecycleOperation.RESTORE)
        assertEquals(setOf(original,grouped),review.events.map{it.id}.toSet())
        assertTrue(r.commitEntryLifecycle(review));assertNotNull(r.dao.event(book,independent)!!.deletedAt)
        assertEquals(9200L,snapshot().balance(snapshot().accounts.single()))
        r.restoreEntry(book,independent);assertEquals(9300L,snapshot().balance(snapshot().accounts.single()))
        r.deleteEntry(book,original);apply(original,EntryLifecycleOperation.PURGE)
        assertTrue(snapshot().events.isEmpty());assertNull(r.dao.event(book,grouped));assertNull(r.dao.event(book,independent))
    }
    @Test fun manualStatementDifferenceNeverReactivatesMerelyBecauseOriginalPostingWasRestored()=runBlocking {
        val event=r.saveEntry(book,draft())
        val source=StatementFile("yohaku.statement",1,r.id(),book,bank,"JPY","2026-10-01","2026-10-31","posted_date",true,"銀行",emptyList(),listOf(StatementRow(r.id(),"2026-10-14","out",FileMoney("JPY","950"),"原文")))
        r.saveStatement(book,r.previewStatement(book,FileProtocols.json.encodeToString(source)))
        val posting=snapshot().reconciliationPostings().single()
        r.acceptStatementDifference(r.previewStatementDifference(book,source.file_id,source.rows.single().row_id,posting.postingId,"確認済み"))
        assertEquals(1,r.previewEntryLifecycle(book,event,EntryLifecycleOperation.TRASH).statementCount)
        r.deleteEntry(book,event);r.restoreEntry(book,event)
        val rows=db.fileWorkflowDao().decisions(book).first();assertNotNull(rows.single().invalidatedAt)
        assertFalse(snapshot().reviewStatements(db.fileWorkflowDao().statements(book).first(),rows).single().decisions.single().active)
        r.acceptStatementDifference(r.previewStatementDifference(book,source.file_id,source.rows.single().row_id,posting.postingId,"再確認"))
        assertNull(db.fileWorkflowDao().decisions(book).first().single().invalidatedAt)
    }
    @Test fun permanentBorrowingRemovalPreservesIndependentRepaymentContractAndConfirmedBillWithoutOpeningReclassification()=runBlocking {
        val loan=r.saveAccount(book,"ローン",AccountType.LOAN,Currency.JPY,Money(Currency.JPY,0),"2026-10-01")
        val borrowed=r.commitFinancialLedger(r.previewBorrowing(book,FinancialBorrowingInput(loan,"契約",FinanceMode.FIXED_LOAN,Money(Currency.JPY,3000),"2026-10-15",bank,Money(Currency.JPY,3000))))!!
        val contract=db.financialDao().fundingContract(book,borrowed)!!
        val plan=r.commitManualFinancialPlan(r.previewManualFinancialPlan(book,ManualFinancialPlanInput(loan,"請求",FinanceMode.FIXED_LOAN,listOf(KnownBillInput("2026-10-20",3000,3000,0,bank)),sourceEventId=borrowed)))!!
        val bill=db.financialDao().bills(book,plan).single()
        val payment=r.commitFinancialLedger(r.previewRepayment(book,FinancialRepaymentInput(bank,loan,Money(Currency.JPY,1000),"2026-10-16",Money(Currency.JPY,1000),Money(Currency.JPY,1000),Money(Currency.JPY,0),Money(Currency.JPY,0),platformConfirmed=true,clearingDate="2026-10-16",allocations=listOf(RepaymentAllocationInput(bill.id,null,1000,0,0)))))!!
        r.deleteEntry(book,borrowed);apply(borrowed,EntryLifecycleOperation.PURGE)
        val s=snapshot();assertNull(r.dao.event(book,borrowed));assertEquals(payment,s.events.single().id)
        assertEquals(contract,db.financialDao().fundingContract(book,borrowed));assertEquals(bill,db.financialDao().bills(book,plan).single());assertEquals(2000L,s.billOutstanding(bill).totalMinor)
        assertEquals(9000L,s.balance(s.accounts.first{it.id==bank}));assertEquals(-1000L,s.balance(s.accounts.first{it.id==loan}))
        db.openHelper.readableDatabase.query("SELECT amountMinor,merchant,note,purgedAt FROM events WHERE bookId=? AND id=?",arrayOf(book,borrowed)).use{assertTrue(it.moveToFirst());assertEquals(0L,it.getLong(0));assertEquals("",it.getString(1));assertEquals("",it.getString(2));assertFalse(it.isNull(3))}
        apply(payment,EntryLifecycleOperation.TRASH);apply(null,EntryLifecycleOperation.EMPTY)
        assertTrue(snapshot().repayments.isEmpty());assertEquals(3000L,snapshot().billOutstanding(bill).totalMinor)
        db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use{assertFalse(it.moveToFirst())}
    }
    @Test fun everyFinancialModeKeepsConfirmedDebtAndIndependentPaymentWhenItsSourceIsPermanentlyRemoved()=runBlocking {
        for(mode in FinanceMode.entries) {
            val b=r.createBook(mode.name,Currency.JPY)
            val cash=r.saveAccount(b,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
            val card=mode in listOf(FinanceMode.SINGLE,FinanceMode.INSTALLMENT,FinanceMode.REVOLVING)
            val debt=r.saveAccount(b,"債務",if(card)AccountType.CREDIT else AccountType.LOAN,Currency.JPY,Money(Currency.JPY,0),"2026-10-01")
            val c=r.saveCategory(b,"生活",EntryKind.EXPENSE,null)
            val source=if(card)r.saveEntry(b,EntryDraft(accountId=debt,categoryId=c,amount="3000",date="2026-10-14",merchant="元の支出"))
                else r.commitFinancialLedger(r.previewBorrowing(b,FinancialBorrowingInput(debt,"契約",mode,Money(Currency.JPY,3000),"2026-10-14",cash,Money(Currency.JPY,3000))))!!
            val zero=InterestRule("0",ChargeFrequency.DAILY,BalanceBasis.EVENT_BALANCE,true,true,365,ChargeRounding.PERIOD_TOTAL,"DOWN",FirstCharge.NORMAL)
            val rule=FinancialRuleVersion("",b,debt,mode,"2026-10-01","Asia/Tokyo",billing=BillingRule(31,true,1,27,cash),
                installment=InstallmentRule(listOf(2),InstallmentFeeMethod.TOTAL_AMOUNT,totalFeeMinor=0,principalRemainder=RemainderPlacement.LAST,feeRemainder=RemainderPlacement.LAST,roundingMode="DOWN"),
                revolving=RevolvingRule(zero,PaymentMethod.FIXED_PRINCIPAL,1500,allocation=AllocationOrder.FEES_FIRST,automatic=false),
                loanMethod=LoanMethod.EQUAL_PRINCIPAL,loanInterest=zero,allocation=AllocationOrder.FEES_FIRST,loanPrincipalRemainder=RemainderPlacement.LAST)
            assertTrue(rule.readiness() is RuleReadiness.Complete)
            r.commitFinancialRule(r.previewFinancialRule(b,debt,rule))
            val dates=if(mode==FinanceMode.SINGLE)listOf("2026-11-27")else listOf("2026-11-27","2026-12-27")
            val input=FinancialPredictionInput(debt,"来源计划",mode,FinancialScheduleTerms("2026-10-14",dates,listOf(AccrualWindow("2026-10-14","2026-10-31"),AccrualWindow("2026-11-01","2026-11-30")).take(dates.size)),sourceEventId=source,paymentAccountId=cash,currentArrangementConfirmed=true)
            val review=r.previewFinancialPrediction(b,input)
            assertTrue("$mode: ${review.calculation}",review.calculation is Calculation.Ready)
            assertEquals(if(mode==FinanceMode.SINGLE)listOf(3000L)else listOf(1500L,1500L),review.bills.map{it.principalMinor})
            val plan=r.commitFinancialPrediction(review)!!
            val first=db.financialDao().bills(b,plan).first()
            assertTrue(r.commitFinancialBillConfirmation(r.previewFinancialBillConfirmation(b,first.id,KnownBillInput(first.date,first.totalMinor!!,first.principalMinor,0,cash))))
            val payment=r.commitFinancialLedger(r.previewRepayment(b,FinancialRepaymentInput(cash,debt,Money(Currency.JPY,500),"2026-10-16",Money(Currency.JPY,500),Money(Currency.JPY,500),Money(Currency.JPY,0),Money(Currency.JPY,0),platformConfirmed=true,clearingDate="2026-10-16",allocations=listOf(RepaymentAllocationInput(first.id,null,500,0,0)))))!!
            val confirmed=db.financialDao().bill(b,first.id)!!
            r.deleteEntry(b,source);r.restoreEntry(b,source)
            var snapshot=r.snapshot(r.dao.book(b)!!).first();var graph=db.financialDao().graph(b).first()!!
            assertFalse("Restored $mode source must be current",r.financialPredictionStale(snapshot,graph,graph.plans.single()))
            r.deleteEntry(b,source)
            assertTrue(r.commitEntryLifecycle(r.previewEntryLifecycle(b,source,EntryLifecycleOperation.PURGE)))
            snapshot=r.snapshot(r.dao.book(b)!!).first();graph=db.financialDao().graph(b).first()!!
            assertNull(r.dao.event(b,source));assertEquals(listOf(payment),snapshot.events.map{it.id})
            assertEquals(9500L,snapshot.balance(snapshot.accounts.first{it.id==cash}));assertEquals(-500L,snapshot.balance(snapshot.accounts.first{it.id==debt}))
            assertEquals(source,graph.plans.single().sourceEventId);assertNotEquals("OPENING",graph.plans.single().origin)
            assertEquals(confirmed,db.financialDao().bill(b,first.id));assertEquals(confirmed.totalMinor!!-500,snapshot.billOutstanding(confirmed).totalMinor)
            assertTrue("Removed $mode source must require review",r.financialPredictionStale(snapshot,graph,graph.plans.single()))
            val timeline=snapshot.cashTimeline(graph,LocalDate.of(2026,10,16),LocalDate.of(2026,12,31),setOf(plan))
            val rows=timeline.steps.map{it.item}.filter{it.kind=="BILL"}
            assertEquals(-(confirmed.totalMinor!!-500),rows.single{it.id=="bill:${first.id}"}.accountDeltaMinor)
            assertTrue(rows.filter{it.id!="bill:${first.id}"}.all{it.issue=="STALE_PLAN"&&it.accountDeltaMinor==null})
            try {r.previewFinancialPrediction(b,input.copy(planId=plan,openingPrincipalMinor=3000));fail("Purged $mode source became an opening prediction")}catch(_:IllegalArgumentException){}
        }
        db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use{assertFalse(it.moveToFirst())}
    }
    @Test fun transferPurgesBothEndsAndLastMediaReferenceWhileLeavingAnotherBookAndRejectingStaleScope()=runBlocking {
        val cash=r.saveAccount(book,"現金",AccountType.CASH,Currency.JPY,Money(Currency.JPY,0),"2026-10-01")
        val transfer=r.saveMovement(book,EntryKind.TRANSFER,bank,cash,Money(Currency.JPY,500),Money(Currency.JPY,500),"2026-10-16")
        val d=draft();r.saveDraft(book,d)
        val file=r.importMedia(book,d.mediaSession,"original.bin","application/octet-stream"){byteArrayOf(1,2,3).inputStream()}
        val event=r.saveEntry(book,d.copy(attachmentIds=listOf(file.id)))
        val other=r.createBook("保持",Currency.JPY);val otherAccount=r.saveAccount(other,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,5000),"2026-10-01");val otherCategory=r.saveCategory(other,"食事",EntryKind.EXPENSE,null)
        val otherEvent=r.saveEntry(other,EntryDraft(accountId=otherAccount,categoryId=otherCategory,amount="100",date="2026-10-16"))
        val shared=file.copy(bookId=other,id=r.id());r.dao.putFile(shared);r.dao.putAttachments(listOf(AttachmentLinkEntity(other,r.id(),otherEvent,shared.id)))
        val independent=r.snapshot(r.dao.book(other)!!).first()
        r.deleteEntry(book,transfer);r.deleteEntry(book,event)
        val stale=r.previewEntryLifecycle(book,null,EntryLifecycleOperation.EMPTY)
        r.saveDraft(book,draft().copy(id=event));assertFalse(r.commitEntryLifecycle(stale))
        apply(null,EntryLifecycleOperation.EMPTY);r.collectUnusedMedia()
        assertTrue("remaining events: ${snapshot().events}",snapshot().events.isEmpty());assertTrue("remaining postings: ${snapshot().storedPostings}",snapshot().storedPostings.isEmpty());assertNull(snapshot().draft);assertTrue("shared path: ${r.mediaFile(shared)}",r.mediaFile(shared).exists());assertEquals(independent,r.snapshot(r.dao.book(other)!!).first())
        r.deleteEntry(other,otherEvent);assertTrue(r.commitEntryLifecycle(r.previewEntryLifecycle(other,otherEvent,EntryLifecycleOperation.PURGE)));r.collectUnusedMedia();assertFalse(r.mediaFile(file).exists())
    }
    @Test fun injectedThirtyDayDeadlineActuallyRemovesLinkedRowsAndFiles()=runBlocking {
        val d=draft();r.saveDraft(book,d);val file=r.importMedia(book,d.mediaSession,"receipt.bin","application/octet-stream"){byteArrayOf(7).inputStream()};val event=r.saveEntry(book,d.copy(attachmentIds=listOf(file.id)))
        r.deleteEntry(book,event);clock.now=clock.now.plus(Duration.ofDays(30))
        try{r.restoreEntry(book,event);fail("expired restore")}catch(_:IllegalArgumentException){}
        r.purgeExpired();r.collectUnusedMedia();assertNull(r.dao.event(book,event));assertTrue(snapshot().attachments.isEmpty());assertFalse(r.mediaFile(file).exists())
    }
    @Test fun version27MigrationPreservesEventsAndExistingReviewEvidence()=runBlocking {
        val name="entry-migration-${UUID.randomUUID()}.db";db.close();db=Room.databaseBuilder(context,YohakuDatabase::class.java,name).build();r=LedgerRepository(db,clock,root)
        try {
            seed();val event=r.saveEntry(book,draft());val before=snapshot();db.close()
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use{old->
                restoreEntryLifecycleFixture(old)
                val schema=JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("room-v27-schema.json").bufferedReader().readText()).getJSONObject("database")
                old.execSQL("UPDATE room_master_table SET identity_hash=? WHERE id=42",arrayOf(schema.getString("identityHash")));old.version=27
            }
            db=Room.databaseBuilder(context,YohakuDatabase::class.java,name).addMigrations(MIGRATION_27_28).build();r=LedgerRepository(db,clock,root)
            assertEquals(before,snapshot());assertNull(r.dao.event(book,event)!!.purgedAt)
        }finally{db.close();context.deleteDatabase(name)}
    }
}
