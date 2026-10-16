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
class FinancialLedgerTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val clock=Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"),ZoneId.of("Asia/Tokyo"))
    @Test fun borrowingWithheldFeeAndExistingPaymentKeepOneCashDeductionAndIndependentRepayment()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        try {
            val repo=LedgerRepository(db,clock);val book=repo.createBook("借還",Currency.JPY,budgetMinor=100000)
            val loan=repo.saveAccount(book,"借入先",AccountType.LOAN,Currency.JPY,Money(Currency.JPY,0),"2026-10-01")
            val bank=repo.saveAccount(book,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,0),"2026-10-01")
            val input=FinancialBorrowingInput(loan,"固定契約",FinanceMode.FIXED_LOAN,Money(Currency.JPY,10000),"2026-10-15",bank,Money(Currency.JPY,9700),Money(Currency.JPY,300))
            val preview=repo.previewBorrowing(book,input)
            assertTrue(repo.snapshot(repo.dao.book(book)!!).first().events.isEmpty())
            assertEquals(300L,preview.expenses.single().afterMinor)
            val borrowed=repo.commitFinancialLedger(preview)!!
            assertNull(repo.commitFinancialLedger(preview))
            val contract=db.financialDao().fundingContract(book,borrowed)!!
            var s=repo.snapshot(repo.dao.book(book)!!).first()
            assertEquals(10000L,s.balance(s.accounts.first{it.id==loan}));assertEquals(9700L,s.balance(s.accounts.first{it.id==bank}))
            assertEquals(300L,s.monthExpense("2026-10"));assertTrue(s.events.none{it.kind==EntryKind.INCOME.name})
            val plan=repo.commitManualFinancialPlan(repo.previewManualFinancialPlan(book,ManualFinancialPlanInput(loan,"第1期",FinanceMode.FIXED_LOAN,listOf(KnownBillInput("2026-10-20",2100,2000,100,bank)),sourceEventId=borrowed)))!!
            val bill=db.financialDao().bills(book,plan).single()
            val category=repo.saveCategory(book,"一時的な支払",EntryKind.EXPENSE,null)
            val alreadyPaid=repo.saveEntry(book,EntryDraft(accountId=bank,categoryId=category,amount="2100",date="2026-10-20",merchant="返済"))
            val repayment=FinancialRepaymentInput(bank,loan,Money(Currency.JPY,2100),"2026-10-20",Money(Currency.JPY,2100),Money(Currency.JPY,2000),Money(Currency.JPY,100),Money(Currency.JPY,0),platformConfirmed=true,clearingDate="2026-10-20",type=RepaymentType.PARTIAL,allocations=listOf(RepaymentAllocationInput(bill.id,null,2000,100,0)),eventId=alreadyPaid)
            val review=repo.previewRepayment(book,repayment)
            assertEquals(2400L,review.expenses.single().beforeMinor);assertEquals(400L,review.expenses.single().afterMinor)
            assertTrue(review.balances.none{it.account.id==bank})
            assertEquals(alreadyPaid,repo.commitFinancialLedger(review))
            s=repo.snapshot(repo.dao.book(book)!!).first()
            assertEquals(2,s.events.size);assertEquals(7600L,s.balance(s.accounts.first{it.id==bank}));assertEquals(8000L,s.balance(s.accounts.first{it.id==loan}))
            assertEquals(8000L,s.contractRemaining(contract));assertEquals(0L,s.billOutstanding(bill).totalMinor)
            assertEquals(400L,s.monthExpense("2026-10"));assertEquals(99600L,s.budgetProjection("2026-10").remainingMinor)
            repo.deleteEntry(book,borrowed)
            s=repo.snapshot(repo.dao.book(book)!!).first()
            assertEquals(alreadyPaid,s.visibleEvents.single().id);assertTrue(s.billOutstanding(bill).settled)
            repo.restoreEntry(book,borrowed)
            s=repo.snapshot(repo.dao.book(book)!!).first()
            assertEquals(2,s.visibleEvents.size);assertEquals(7600L,s.balance(s.accounts.first{it.id==bank}));assertEquals(8000L,s.contractRemaining(contract))
            repo.deleteEntry(book,alreadyPaid)
            s=repo.snapshot(repo.dao.book(book)!!).first();assertEquals(2100L,s.billOutstanding(bill).totalMinor);assertEquals(10000L,s.contractRemaining(contract))
            repo.restoreEntry(book,alreadyPaid)
            s=repo.snapshot(repo.dao.book(book)!!).first();assertTrue(s.billOutstanding(bill).settled);assertEquals(400L,s.monthExpense("2026-10"))
            assertTrue(repo.commitFinancialBillConfirmation(repo.previewFinancialBillConfirmation(book,bill.id,KnownBillInput("2026-10-20",2000,2000,0,bank))))
            s=repo.snapshot(repo.dao.book(book)!!).first()
            assertEquals(-100L,s.billOutstanding(db.financialDao().bill(book,bill.id)!!).totalMinor)
            assertEquals(7600L,s.balance(s.accounts.first{it.id==bank}));assertEquals(400L,s.monthExpense("2026-10"))
        }finally{db.close()}
    }
    @Test fun directCrossCurrencyPaymentWaitsForPlatformClearingAndKeepsOriginalInput()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        try {
            val repo=LedgerRepository(db,clock);val book=repo.createBook("日中",Currency.JPY)
            val bank=repo.saveAccount(book,"円銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,100000),"2026-10-01")
            val loan=repo.saveAccount(book,"人民元借入",AccountType.LOAN,Currency.CNY,Money(Currency.CNY,100000),"2026-10-01")
            val opening=repo.previewBorrowing(book,FinancialBorrowingInput(loan,"既存の利用枠",FinanceMode.CREDIT_LINE,Money(Currency.CNY,100000),"2026-10-01",opening=true))
            val contractId=repo.commitFinancialLedger(opening)!!
            assertTrue(repo.snapshot(repo.dao.book(book)!!).first().events.isEmpty())
            val pending=FinancialRepaymentInput(bank,loan,Money(Currency.JPY,20000),"2026-10-15",received=Money(Currency.CNY,100000),sourceFee=Money(Currency.JPY,150),allocations=listOf(RepaymentAllocationInput(contractId=contractId)))
            val paid=repo.commitFinancialLedger(repo.previewRepayment(book,pending))!!
            var s=repo.snapshot(repo.dao.book(book)!!).first()
            assertEquals(80000L,s.balance(s.accounts.first{it.id==bank}));assertEquals(100000L,s.balance(s.accounts.first{it.id==loan}))
            assertEquals(150L,s.monthExpense("2026-10"));assertEquals(contractId,s.repaymentAllocations.single().contractId);assertNull(s.repaymentAllocations.single().principalMinor);assertEquals(100000L,s.contractRemaining(s.contracts.single()))
            val clearing=pending.copy(eventId=paid,principal=Money(Currency.CNY,99000),interest=Money(Currency.CNY,1000),fee=Money(Currency.CNY,0),feeBase=Money(Currency.JPY,199),platformConfirmed=true,clearingDate="2026-10-16",allocations=listOf(RepaymentAllocationInput(contractId=contractId,principalMinor=99000,interestMinor=1000,feeMinor=0)))
            val review=repo.previewRepayment(book,clearing)
            assertTrue(review.balances.none{it.account.id==bank})
            assertEquals(paid,repo.commitFinancialLedger(review))
            s=repo.snapshot(repo.dao.book(book)!!).first()
            assertEquals(80000L,s.balance(s.accounts.first{it.id==bank}));assertEquals(1000L,s.balance(s.accounts.first{it.id==loan}))
            assertEquals(349L,s.monthExpense("2026-10"));assertEquals(1,s.events.size)
            assertEquals(1,s.accounts.count{it.currency=="CNY"});assertTrue(s.accounts.filter{it.currency=="CNY"}.all{it.type==AccountType.LOAN.name})
            val saved=s.repayments.single();assertEquals(pending,repo.json.decodeFromString<FinancialRepaymentInput>(saved.originalInputJson))
            assertEquals(0L,s.unallocatedRepaymentPrincipal(loan))
            val other=repo.createBook("別",Currency.JPY)
            assertThrows(IllegalArgumentException::class.java){runBlocking{repo.previewRepayment(other,pending)}}
            Unit
        }finally{db.close()}
    }
    @Test fun separateFeesAndOpeningContractsDoNotAddAnotherDebtBalance()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        try {
            val repo=LedgerRepository(db,clock);val book=repo.createBook("循環",Currency.JPY)
            val loan=repo.saveAccount(book,"利用枠",AccountType.LOAN,Currency.JPY,Money(Currency.JPY,5000),"2026-10-01")
            val bank=repo.saveAccount(book,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,1000),"2026-10-01")
            repo.commitFinancialLedger(repo.previewBorrowing(book,FinancialBorrowingInput(loan,"期首",FinanceMode.CREDIT_LINE,Money(Currency.JPY,5000),"2026-10-01",opening=true)))
            val existing=repo.saveMovement(book,EntryKind.BORROWING,loan,bank,Money(Currency.JPY,10000),Money(Currency.JPY,10000),"2026-10-15")
            val input=FinancialBorrowingInput(loan,"追加借入",FinanceMode.CREDIT_LINE,Money(Currency.JPY,10000),"2026-10-15",bank,Money(Currency.JPY,10000),Money(Currency.JPY,0),separateFeeAccountId=bank,separateFee=Money(Currency.JPY,300),eventId=existing)
            val event=repo.commitFinancialLedger(repo.previewBorrowing(book,input))!!
            val s=repo.snapshot(repo.dao.book(book)!!).first()
            assertEquals(existing,event)
            assertEquals(15000L,s.balance(s.accounts.first{it.id==loan}));assertEquals(10700L,s.balance(s.accounts.first{it.id==bank}))
            assertEquals(300L,s.monthExpense("2026-10"));assertEquals(1,s.events.size);assertEquals(2,s.contracts.size)
            assertEquals(10000L,db.financialDao().fundingContract(book,event)!!.principalMinor)
        }finally{db.close()}
    }
}
