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
class FinancialPredictionTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val clock=Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"),ZoneId.of("Asia/Tokyo"))
    private fun rule(book:String,card:String,bank:String)=FinancialRuleVersion("",book,card,FinanceMode.INSTALLMENT,"2026-10-01","Asia/Tokyo",billing=BillingRule(31,true,1,27,bank),
        installment=InstallmentRule(listOf(3),InstallmentFeeMethod.PER_HUNDRED_TABLE,feeTable=listOf(InstallmentFee(3,"2.01")),principalRemainder=RemainderPlacement.LAST,feeRemainder=RemainderPlacement.FIRST,roundingMode="DOWN"))
    @Test fun purchasePredictionReopensAndRecalculatesWithoutChangingConfirmedFactsOrBalances()=runBlocking {
        val name="prediction-verification.db";context.deleteDatabase(name)
        var db=Room.databaseBuilder(context,YohakuDatabase::class.java,name).build()
        try {
            var repo=LedgerRepository(db,clock)
            val book=repo.createBook("金融",Currency.JPY)
            val bank=repo.saveAccount(book,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,20000),"2026-10-01")
            val card=repo.saveAccount(book,"カード",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,0),"2026-10-01")
            val category=repo.saveCategory(book,"買物",EntryKind.EXPENSE,null)
            val event=repo.saveEntry(book,EntryDraft(accountId=card,categoryId=category,amount="10000",date="2026-10-15"))
            val firstRule=repo.commitFinancialRule(repo.previewFinancialRule(book,card,rule(book,card,bank)))!!
            val input=FinancialPredictionInput(card,"購入分割",FinanceMode.INSTALLMENT,FinancialScheduleTerms("2026-10-15",listOf("2026-11-27","2026-12-27","2027-01-27")),sourceEventId=event,currentArrangementConfirmed=true)
            val preview=repo.previewFinancialPrediction(book,input)
            assertTrue(preview.calculation is Calculation.Ready)
            assertEquals("2026-10-31",(preview.calculation as Calculation.Ready).value.first().basis.closingDate)
            assertEquals(listOf(3333L,3333L,3334L),preview.bills.map{it.principalMinor})
            assertTrue(db.financialDao().plans(book,card).isEmpty())
            val plan=repo.commitFinancialPrediction(preview)!!
            assertNull(repo.commitFinancialPrediction(preview))
            val original=db.financialDao().bills(book,plan)
            val first=original.first()
            assertTrue(repo.commitFinancialBillConfirmation(repo.previewFinancialBillConfirmation(book,first.id,KnownBillInput(first.date,3567,3500,67,bank))))
            val confirmed=db.financialDao().bill(book,first.id)!!
            repo.validateFinancialRule(book,first.id)
            assertEquals("DIFFERENT",db.financialDao().graph(book).first()!!.ruleValidationStatus(firstRule))
            val next=rule(book,card,bank).copy(installment=rule(book,card,bank).installment!!.copy(feeTable=listOf(InstallmentFee(3,"3.00"))))
            repo.commitFinancialRule(repo.previewFinancialRule(book,card,next))
            assertEquals(confirmed,db.financialDao().bill(book,first.id))
            assertTrue(repo.financialPredictionStale(repo.snapshot(repo.dao.book(book)!!).first(),db.financialDao().graph(book).first()!!,db.financialDao().plan(book,plan)!!))
            db.close();db=Room.databaseBuilder(context,YohakuDatabase::class.java,name).build();repo=LedgerRepository(db,clock)
            val review=repo.previewFinancialPrediction(book,input.copy(planId=plan))
            assertEquals(listOf(3500L,3333L,3167L),review.bills.map{it.principalMinor})
            assertEquals(listOf(67L,100L,100L),review.bills.map{it.feeMinor})
            assertEquals(original.map{it.id},review.bills.map{it.id})
            assertEquals(plan,repo.commitFinancialPrediction(review))
            assertEquals(confirmed,db.financialDao().bill(book,first.id))
            var snapshot=repo.snapshot(repo.dao.book(book)!!).first()
            assertEquals(10000L,snapshot.balance(snapshot.accounts.first{it.id==card}))
            assertEquals(20000L,snapshot.balance(snapshot.accounts.first{it.id==bank}))
            assertEquals(1,snapshot.events.size);assertEquals(10000L,snapshot.monthExpense("2026-10"))
            assertFalse(repo.financialPredictionStale(snapshot,db.financialDao().graph(book).first()!!,db.financialDao().plan(book,plan)!!))
            // A refund to cash changes consumption but does not repay the original card.
            repo.saveRefund(book,RefundInput(event,Money(Currency.JPY,2000),bank,Money(Currency.JPY,2000),Money(Currency.JPY,2000),"2026-10-16",true))
            snapshot=repo.snapshot(repo.dao.book(book)!!).first()
            assertEquals(10000L,snapshot.financialSourcePrincipal(event,card)!!.minor)
            repo.saveRefund(book,RefundInput(event,Money(Currency.JPY,1000),card,Money(Currency.JPY,1000),Money(Currency.JPY,1000),"2026-10-17",true))
            snapshot=repo.snapshot(repo.dao.book(book)!!).first()
            assertEquals(9000L,snapshot.financialSourcePrincipal(event,card)!!.minor)
            assertTrue(repo.financialPredictionStale(snapshot,db.financialDao().graph(book).first()!!,db.financialDao().plan(book,plan)!!))
        }finally{db.close();context.deleteDatabase(name)}
    }

    @Test fun actualClearingRecalculatesFutureInterestAndRetainsBillFactsAcrossReopenAndDeleteRestore()=runBlocking {
        val name="actual-prediction-verification.db";context.deleteDatabase(name)
        var db=Room.databaseBuilder(context,YohakuDatabase::class.java,name).build()
        val today=Clock.fixed(Instant.parse("2026-06-10T00:00:00Z"),ZoneId.of("Asia/Tokyo"))
        try {
            var repo=LedgerRepository(db,today)
            val book=repo.createBook("返済反映",Currency.JPY)
            val bank=repo.saveAccount(book,"支払銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,200000),"2026-04-01")
            val card=repo.saveAccount(book,"カード",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,100000),"2026-04-01")
            val rule=rule(book,card,bank).copy(mode=FinanceMode.REVOLVING,effectiveFrom="2026-04-01",revolving=RevolvingRule(
                InterestRule("0.18",ChargeFrequency.DAILY,BalanceBasis.EVENT_BALANCE,true,true,365,ChargeRounding.PERIOD_TOTAL,"DOWN",FirstCharge.NORMAL),PaymentMethod.FIXED_PRINCIPAL,10000,allocation=AllocationOrder.FEES_FIRST,automatic=false))
            repo.commitFinancialRule(repo.previewFinancialRule(book,card,rule))
            val input=FinancialPredictionInput(card,"既存リボ",FinanceMode.REVOLVING,FinancialScheduleTerms("2026-04-01",listOf("2026-05-27","2026-06-27","2026-07-27"),listOf(AccrualWindow("2026-04-01","2026-04-30"),AccrualWindow("2026-05-01","2026-05-31"),AccrualWindow("2026-06-01","2026-06-30"))),openingPrincipalMinor=100000,paymentAccountId=bank,currentArrangementConfirmed=true)
            val plan=repo.commitFinancialPrediction(repo.previewFinancialPrediction(book,input))!!
            val bills=db.financialDao().bills(book,plan)
            val first=bills.first()
            repo.commitFinancialBillConfirmation(repo.previewFinancialBillConfirmation(book,first.id,KnownBillInput(first.date,21479,20000,1479,bank)))
            val confirmed=db.financialDao().bill(book,first.id)!!
            val payment=repo.commitFinancialLedger(repo.previewRepayment(book,FinancialRepaymentInput(bank,card,Money(Currency.JPY,5100),"2026-06-05",received=Money(Currency.JPY,5100),principal=Money(Currency.JPY,5000),interest=Money(Currency.JPY,100),fee=Money(Currency.JPY,0),platformConfirmed=true,clearingDate="2026-06-05",type=RepaymentType.PARTIAL,allocations=listOf(RepaymentAllocationInput(first.id,null,5000,100,0)))))!!
            var graph=db.financialDao().graph(book).first()!!
            var snapshot=repo.snapshot(repo.dao.book(book)!!).first()
            assertFalse(repo.financialPredictionStale(snapshot,graph,graph.plans.single()))
            val review=repo.previewFinancialPrediction(book,input.copy(planId=plan))
            assertEquals(listOf(1479L,1528L,1395L),review.bills.map{it.feeMinor})
            assertEquals(confirmed,review.bills.first());assertEquals(bills.map{it.id},review.bills.map{it.id})
            assertEquals(75000L,(review.calculation as Calculation.Ready).value.last().basis.remainingPrincipalMinor)
            assertEquals(plan,repo.commitFinancialPrediction(review))
            graph=db.financialDao().graph(book).first()!!
            assertFalse(repo.financialPredictionStale(snapshot,graph,graph.plans.single()))
            val saved=repo.json.decodeFromString<FinancialPredictionInput>(graph.plans.single().termsJson!!)
            assertEquals(3,saved.projections.size);assertEquals(payment,saved.facts!!.repayments.single().eventId)
            assertEquals(0L,saved.projections.first().basis.assumedPrincipalMinor)
            assertEquals(10000L,saved.projections[1].basis.assumedPrincipalMinor)
            assertEquals(16379L,snapshot.billOutstanding(confirmed).totalMinor)
            db.close();db=Room.databaseBuilder(context,YohakuDatabase::class.java,name).build();repo=LedgerRepository(db,today)
            graph=db.financialDao().graph(book).first()!!;snapshot=repo.snapshot(repo.dao.book(book)!!).first()
            assertFalse(repo.financialPredictionStale(snapshot,graph,graph.plans.single()))
            assertEquals(194900L,snapshot.balance(snapshot.accounts.first{it.id==bank}));assertEquals(95000L,snapshot.balance(snapshot.accounts.first{it.id==card}));assertEquals(100L,snapshot.monthExpense("2026-06"));assertEquals(1,snapshot.events.size)
            repo.deleteEntry(book,payment)
            snapshot=repo.snapshot(repo.dao.book(book)!!).first();graph=db.financialDao().graph(book).first()!!
            assertTrue(repo.financialPredictionStale(snapshot,graph,graph.plans.single()))
            val deleted=repo.previewFinancialPrediction(book,input.copy(planId=plan))
            assertEquals(1459L,deleted.bills.last().feeMinor);assertEquals(confirmed,deleted.bills.first())
            repo.restoreEntry(book,payment)
            assertNull(repo.commitFinancialPrediction(deleted))
            val restored=repo.previewFinancialPrediction(book,input.copy(planId=plan))
            assertEquals(1395L,restored.bills.last().feeMinor);assertEquals(plan,repo.commitFinancialPrediction(restored))
            snapshot=repo.snapshot(repo.dao.book(book)!!).first()
            assertEquals(194900L,snapshot.balance(snapshot.accounts.first{it.id==bank}));assertEquals(1,snapshot.events.size)
            val second=db.financialDao().bills(book,plan)[1]
            repo.commitFinancialLedger(repo.previewRepayment(book,FinancialRepaymentInput(bank,card,Money(Currency.JPY,1000),"2026-06-10",allocations=listOf(RepaymentAllocationInput(billId=second.id)))))
            val pending=repo.previewFinancialPrediction(book,input.copy(planId=plan))
            assertEquals(second,pending.bills[1]);assertEquals(1,pending.retainedOther.size)
            assertEquals(75000L,(pending.calculation as Calculation.Ready).value.last().basis.remainingPrincipalMinor)
            assertEquals(plan,repo.commitFinancialPrediction(pending))
            assertEquals(95000L,repo.snapshot(repo.dao.book(book)!!).first().balance(snapshot.accounts.first{it.id==card}))
            repo.commitFinancialBillConfirmation(repo.previewFinancialBillConfirmation(book,first.id,KnownBillInput(first.date,21479,paymentAccountId=bank)))
            assertTrue(repo.previewFinancialPrediction(book,input.copy(planId=plan)).calculation is Calculation.Incomplete)
            repo.commitFinancialLedger(repo.previewRepayment(book,FinancialRepaymentInput(bank,card,Money(Currency.JPY,21479),"2026-06-05",Money(Currency.JPY,21479),Money(Currency.JPY,20000),Money(Currency.JPY,1479),Money(Currency.JPY,0),platformConfirmed=true,clearingDate="2026-06-05",allocations=listOf(RepaymentAllocationInput(first.id,null,20000,1479,0)),eventId=payment)))
            val fullyPaid=repo.previewFinancialPrediction(book,input.copy(planId=plan))
            assertTrue(fullyPaid.calculation is Calculation.Ready)
            assertEquals(60000L,(fullyPaid.calculation as Calculation.Ready).value.last().basis.remainingPrincipalMinor)
            assertNull(fullyPaid.bills.first().principalMinor);assertNull(fullyPaid.bills.first().feeMinor)
            assertEquals(plan,repo.commitFinancialPrediction(fullyPaid))
            snapshot=repo.snapshot(repo.dao.book(book)!!).first()
            assertEquals(177521L,snapshot.balance(snapshot.accounts.first{it.id==bank}));assertEquals(80000L,snapshot.balance(snapshot.accounts.first{it.id==card}));assertEquals(1479L,snapshot.monthExpense("2026-06"))
        }finally{db.close();context.deleteDatabase(name)}
    }

    @Test fun paymentsBeforeTheFirstBorrowingPlanAndRefundsKeepTheirRealPrincipalDates()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        val today=Clock.fixed(Instant.parse("2026-04-20T00:00:00Z"),ZoneId.of("Asia/Tokyo"))
        try {
            val repo=LedgerRepository(db,today);val book=repo.createBook("初回予測",Currency.JPY,startDate="2026-04-01")
            val loan=repo.saveAccount(book,"固定ローン",AccountType.LOAN,Currency.JPY,Money(Currency.JPY,0),"2026-04-01")
            val bank=repo.saveAccount(book,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,0),"2026-04-01")
            val borrowed=repo.commitFinancialLedger(repo.previewBorrowing(book,FinancialBorrowingInput(loan,"実契約",FinanceMode.FIXED_LOAN,Money(Currency.JPY,100000),"2026-04-01",bank,Money(Currency.JPY,100000),Money(Currency.JPY,0))))!!
            val contract=db.financialDao().fundingContract(book,borrowed)!!
            repo.commitFinancialLedger(repo.previewRepayment(book,FinancialRepaymentInput(bank,loan,Money(Currency.JPY,20000),"2026-04-16",Money(Currency.JPY,20000),Money(Currency.JPY,20000),Money(Currency.JPY,0),Money(Currency.JPY,0),platformConfirmed=true,clearingDate="2026-04-16",type=RepaymentType.EARLY,allocations=listOf(RepaymentAllocationInput(contractId=contract.id,principalMinor=20000,interestMinor=0,feeMinor=0)))))
            val daily=InterestRule("0.18",ChargeFrequency.DAILY,BalanceBasis.EVENT_BALANCE,false,true,365,ChargeRounding.PERIOD_TOTAL,"DOWN",FirstCharge.NORMAL)
            repo.commitFinancialRule(repo.previewFinancialRule(book,loan,rule(book,loan,bank).copy(mode=FinanceMode.FIXED_LOAN,effectiveFrom="2026-04-01",loanMethod=LoanMethod.EQUAL_PRINCIPAL,loanInterest=daily,loanPrincipalRemainder=RemainderPlacement.LAST,allocation=AllocationOrder.FEES_FIRST)))
            val input=FinancialPredictionInput(loan,"実契約の予定",FinanceMode.FIXED_LOAN,FinancialScheduleTerms("2026-04-01",listOf("2026-04-30","2026-05-30")),sourceEventId=borrowed,paymentAccountId=bank,currentArrangementConfirmed=true)
            val borrowing=repo.previewFinancialPrediction(book,input)
            assertEquals(listOf(50000L,30000L),borrowing.bills.map{it.principalMinor});assertEquals(listOf(1282L,443L),borrowing.bills.map{it.feeMinor})
            val plan=repo.commitFinancialPrediction(borrowing)!!
            var snapshot=repo.snapshot(repo.dao.book(book)!!).first();var graph=db.financialDao().graph(book).first()!!
            assertFalse(repo.financialPredictionStale(snapshot,graph,graph.plans.first{it.id==plan}))
            assertEquals(80000L,snapshot.balance(snapshot.accounts.first{it.id==loan}));assertEquals(0L,snapshot.monthExpense("2026-04"))
            val corrected=repo.previewBorrowing(book,FinancialBorrowingInput(loan,"実契約",FinanceMode.FIXED_LOAN,Money(Currency.JPY,120000),"2026-04-01",bank,Money(Currency.JPY,120000),Money(Currency.JPY,0),eventId=borrowed,contractId=contract.id))
            assertTrue(corrected.planImpacts.single().updatesPrediction)
            assertEquals(listOf(1568L,591L),corrected.planImpacts.single().nextBills.map{it.feeMinor})
            assertEquals(borrowed,repo.commitFinancialLedger(corrected))
            snapshot=repo.snapshot(repo.dao.book(book)!!).first();graph=db.financialDao().graph(book).first()!!
            assertEquals(100000L,snapshot.balance(snapshot.accounts.first{it.id==loan}));assertEquals(100000L,snapshot.balance(snapshot.accounts.first{it.id==bank}))
            assertEquals(120000L,graph.plans.first{it.id==plan}.principalMinor);assertFalse(repo.financialPredictionStale(snapshot,graph,graph.plans.first{it.id==plan}))
            assertEquals("BORROWING",db.financialDao().planRevisions(book,plan).first().first().reason)
            val card=repo.saveAccount(book,"返金カード",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,0),"2026-04-01")
            val category=repo.saveCategory(book,"買物",EntryKind.EXPENSE,null)
            val purchase=repo.saveEntry(book,EntryDraft(accountId=card,categoryId=category,amount="100000",date="2026-04-01"))
            repo.saveRefund(book,RefundInput(purchase,Money(Currency.JPY,50000),card,Money(Currency.JPY,50000),Money(Currency.JPY,50000),"2026-04-16",true))
            repo.commitFinancialRule(repo.previewFinancialRule(book,card,rule(book,card,bank).copy(mode=FinanceMode.REVOLVING,effectiveFrom="2026-04-01",revolving=RevolvingRule(daily.copy(includeStart=true),PaymentMethod.FIXED_PRINCIPAL,10000,allocation=AllocationOrder.FEES_FIRST,automatic=false))))
            val refund=repo.previewFinancialPrediction(book,FinancialPredictionInput(card,"実返金を反映",FinanceMode.REVOLVING,FinancialScheduleTerms("2026-04-01",listOf("2026-05-27","2026-06-27"),listOf(AccrualWindow("2026-04-01","2026-04-30"),AccrualWindow("2026-05-01","2026-05-31"))),sourceEventId=purchase,currentArrangementConfirmed=true))
            assertEquals(50000L,refund.plan.principalMinor);assertEquals(100000L,refund.originalPrincipalMinor)
            assertEquals(listOf(1109L,739L),refund.bills.map{it.feeMinor})
            assertEquals("2026-04-16",(refund.calculation as Calculation.Ready).value.first().basis.principalFacts.single().date)
            val refundPlan=repo.commitFinancialPrediction(refund)!!
            snapshot=repo.snapshot(repo.dao.book(book)!!).first();graph=db.financialDao().graph(book).first()!!
            assertFalse(repo.financialPredictionStale(snapshot,graph,graph.plans.first{it.id==refundPlan}))
            assertEquals(50000L,snapshot.monthExpense("2026-04"));assertEquals(4,snapshot.events.size)
        }finally{db.close()}
    }

    @Test fun manualKnownAmountGainsItsFirstPredictionWithoutLosingUnknownBreakdown()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        try {
            val repo=LedgerRepository(db,clock)
            val book=repo.createBook("金融",Currency.JPY)
            val bank=repo.saveAccount(book,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,20000),"2026-10-01")
            val card=repo.saveAccount(book,"カード",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
            val r=repo.commitFinancialRule(repo.previewFinancialRule(book,card,rule(book,card,bank).copy(mode=FinanceMode.SINGLE)))!!
            val plan=repo.commitManualFinancialPlan(repo.previewManualFinancialPlan(book,ManualFinancialPlanInput(card,"既知",FinanceMode.SINGLE,listOf(KnownBillInput("2026-11-27",10100,paymentAccountId=bank)),principalMinor=10000,opening=true)))!!
            val bill=db.financialDao().bills(book,plan).single()
            val input=FinancialPredictionInput(card,"既知",FinanceMode.SINGLE,FinancialScheduleTerms("2026-10-01",listOf("2026-11-27")),openingPrincipalMinor=10000,planId=plan)
            val review=repo.previewFinancialPrediction(book,input)
            assertTrue(review.calculation is Calculation.Ready)
            assertEquals(10100L,review.bills.single().totalMinor)
            assertEquals(10000L,review.bills.single().predictedTotalMinor)
            assertNull(review.bills.single().principalMinor);assertNull(review.bills.single().feeMinor)
            repo.commitFinancialPrediction(review)
            val saved=db.financialDao().bill(book,bill.id)!!
            assertEquals(bill.confirmedAt,saved.confirmedAt);assertEquals(bill.confirmedDate,saved.confirmedDate)
            assertNull(saved.confirmedPrincipalMinor);assertNull(saved.confirmedFeeMinor)
            repo.validateFinancialRule(book,bill.id)
            assertEquals("DIFFERENT",db.financialDao().graph(book).first()!!.ruleValidationStatus(r))
            assertTrue(repo.snapshot(repo.dao.book(book)!!).first().events.isEmpty())
        }finally{db.close()}
    }

    @Test fun versionTwelveUpgradePreservesConfirmedBillAndDefaultsPredictionMetadata()=runBlocking {
        val name="upgrade-prediction-verification.db";context.deleteDatabase(name)
        val schema=JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("room-v12-schema.json").bufferedReader().readText()).getJSONObject("database")
        val old=SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name),null)
        try {
            val entities=schema.getJSONArray("entities")
            for(i in 0 until entities.length()) {
                val e=entities.getJSONObject(i)
                old.execSQL(e.getString("createSql").replace("\${TABLE_NAME}",e.getString("tableName")))
                val indices=e.optJSONArray("indices")
                for(j in 0 until (indices?.length()?:0))old.execSQL(indices!!.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",e.getString("tableName")))
            }
            val setup=schema.getJSONArray("setupQueries");for(i in 0 until setup.length())old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO books (id,name,currency,startDate,theme,language,initialized,budgetMinor) VALUES ('b','金融','JPY','2026-10-01','journal','ja',1,NULL)")
            old.execSQL("INSERT INTO accounts VALUES ('b','a','銀行','BANK','JPY',20000,'2026-10-01',1)")
            old.execSQL("INSERT INTO accounts VALUES ('b','c','カード','CREDIT','JPY',10000,'2026-10-01',1)")
            old.execSQL("INSERT INTO financial_plans (bookId,id,accountId,name,mode,origin,currency,principalMinor,complete,createdAt,revision,active) VALUES ('b','p','c','既知','SINGLE','OPENING','JPY',10000,0,1,1,1)")
            old.execSQL("INSERT INTO financial_bills (bookId,id,planId,number,paymentAccountId,confirmedDate,confirmedTotalMinor,confirmedAt,revision) VALUES ('b','bill','p',1,'a','2026-11-27',10100,1,1)")
            old.execSQL("DROP TABLE IF EXISTS financial_drafts");restoreFixtureDatabase(old,12)
        }finally{old.close()}
        val db=Room.databaseBuilder(context,YohakuDatabase::class.java,name).addMigrations(*ALL_MIGRATIONS).build()
        try {
            val graph=db.financialDao().graph("b").first()!!
            assertNull(graph.plans.single().termsJson)
            val bill=graph.bills.single()
            assertEquals(10100L,bill.totalMinor);assertTrue(bill.confirmed&&bill.active)
            assertNull(bill.principalMinor);assertNull(bill.predictedTotalMinor)
        }finally{db.close();context.deleteDatabase(name)}
    }
}
