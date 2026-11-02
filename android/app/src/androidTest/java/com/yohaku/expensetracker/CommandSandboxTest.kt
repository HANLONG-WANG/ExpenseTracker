package com.yohaku.expensetracker

import android.util.Log
import androidx.room.InvalidationTracker
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.data.db.YohakuDatabase
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.csv.CsvParser
import com.yohaku.expensetracker.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.system.measureNanoTime

@RunWith(AndroidJUnit4::class)
class CommandSandboxTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName="command-sandbox-verification.db"
    private lateinit var db:YohakuDatabase
    private lateinit var repo:LedgerRepository
    private lateinit var mediaRoot:File
    private val clock=Clock.fixed(Instant.parse("2026-10-26T03:00:00Z"),ZoneOffset.UTC)

    @Before fun open() {
        context.deleteDatabase(databaseName)
        db=Room.databaseBuilder(context,YohakuDatabase::class.java,databaseName).build()
        mediaRoot=File(context.cacheDir,"command-sandbox-media").apply{deleteRecursively();check(mkdirs())}
        repo=LedgerRepository(db,clock,mediaRoot)
    }

    @After fun close() {db.close();context.deleteDatabase(databaseName);mediaRoot.deleteRecursively()}

    private class SandboxRollback:RuntimeException()

    // Only our signal is caught, outside the outermost transaction; assertions and cancellation escape.
    private suspend fun rollback(block:suspend ()->Unit) {
        try {db.withTransaction {block();throw SandboxRollback()}}
        catch(_:SandboxRollback) { }
    }

    private suspend fun snapshot(bookId:String)=repo.snapshot(requireNotNull(repo.dao.book(bookId))).first()

    private suspend fun createAccountAndEntry(bookId:String,categoryId:String):Pair<String,String> {
        val accountId=repo.saveAccount(bookId,"试运行账户",AccountType.CASH,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
        val review=repo.previewEntry(bookId,EntryDraft(accountId=accountId,categoryId=categoryId,amount="500",date="2026-10-26"))
        assertEquals(accountId,review.snapshot.accounts.single().id)
        val eventId=requireNotNull(repo.commitEntryReview(bookId,review))
        return accountId to eventId
    }

    /** Covers both read-your-writes (including DAO Flow.first) and nested withTransaction rollback. */
    @Test fun laterStepsReadEarlierWritesAndNestedTransactionsRollBack()=runBlocking {
        withTimeout(15000) {
            val book=repo.createBook("事务试运行",Currency.JPY)
            val category=repo.saveCategory(book,"餐饮",EntryKind.EXPENSE,null)
            val before=repo.dao.graph(book).first()
            var accountId=""
            var eventId=""
            rollback {
                val created=createAccountAndEntry(book,category)
                accountId=created.first;eventId=created.second
                val inside=snapshot(book)
                assertEquals(accountId,inside.accounts.single().id)
                assertEquals(eventId,inside.events.single().id)
                assertEquals(9500L,inside.balance(inside.accounts.single()))
                val graph=requireNotNull(repo.dao.graph(book).first())
                assertEquals(eventId,graph.settlements.single().eventId)
                assertEquals(eventId,graph.postings.single().eventId)
                assertEquals(eventId,graph.savedRevisions.single().eventId)
            }
            assertNull(repo.dao.account(book,accountId))
            assertNull(repo.dao.event(book,eventId))
            assertEquals(before,repo.dao.graph(book).first())
        }
    }

    @Test fun rollbackDoesNotInvalidateButRealCommitDoes()=runBlocking {
        withTimeout(15000) {
            val book=repo.createBook("失效通知",Currency.JPY)
            val category=repo.saveCategory(book,"餐饮",EntryKind.EXPENSE,null)
            val invalidations=Channel<Set<String>>(Channel.UNLIMITED)
            // Setup only wrote books/categories, so no pending setup writes touch these tables.
            // Observe raw invalidations, as ReminderCoordinator does, without Flow deduplication.
            val observer=object:InvalidationTracker.Observer(arrayOf("accounts","events","postings","settlements","revisions","drafts")) {
                override fun onInvalidated(tables:Set<String>) {invalidations.trySend(tables)}
            }
            db.invalidationTracker.addObserver(observer)
            try {
                rollback {createAccountAndEntry(book,category)}
                assertNull("Rollback emitted an invalidation",withTimeoutOrNull(500){invalidations.receive()})
                assertTrue(snapshot(book).accounts.isEmpty())
                assertTrue(snapshot(book).events.isEmpty())

                db.withTransaction {createAccountAndEntry(book,category)}
                val changed=withTimeout(5000){invalidations.receive()}
                assertTrue("Observer did not see the committed account", "accounts" in changed)
                assertTrue("Observer did not see the committed entry", "events" in changed)
            } finally {
                db.invalidationTracker.removeObserver(observer)
                invalidations.close()
            }
        }
    }

    @Test fun bookCoreAndConcurrentMediaCleanupCompleteWithGlobalLockOrder()=runBlocking {
        withTimeout(15000) {
            val book=repo.createBook("归档锁顺序",Currency.JPY)
            val review=repo.previewBookOperation(book,BookOperation.ARCHIVE)
            val orphan=File(mediaRoot,"orphan.part").apply{writeText("unfinished import")}
            val transactionReady=CompletableDeferred<Unit>()
            val cleanupQueued=CompletableDeferred<Unit>()
            val archive=async(Dispatchers.IO) {
                repo.mediaWrites.withLock {
                    db.withTransaction {
                        transactionReady.complete(Unit)
                        cleanupQueued.await()
                        repo.commitBookOperationInTransaction(review)
                    }
                }
            }
            transactionReady.await()
            // Starts outside the transaction context and runs until the held media lock suspends it.
            val cleanup=async(start=CoroutineStart.UNDISPATCHED) {repo.collectUnusedMedia()}
            assertFalse("Cleanup must wait for mediaWrites",cleanup.isCompleted)
            cleanupQueued.complete(Unit)
            assertTrue(archive.await())
            cleanup.await()
            assertTrue(requireNotNull(repo.dao.book(book)).archived)
            assertFalse("Cleanup did not reach the filesystem",orphan.exists())
        }
    }

    @Test fun fiftyCategoryEditsOnLargeLedgerReportRollbackTiming()=runBlocking {
        // Same 3,700-row / roughly three-year CSV scale as LargeLedgerTest. No perf flag or time assertion.
        val expense=listOf("食費","交通費","日用品","外食","娯楽","衣服","医療","通信","家賃","水道光熱","交際費","書籍","美容","旅行","家具","雑費")
        val income=listOf("給料","臨時収入","退款","送金")
        val random=java.util.Random(7)
        val rows=(0 until 3700).map{i->
            val date=LocalDate.of(2023,8,17).plusDays((i*1167L)/3700)
            if(i%40==0)"收入,$date,${income[i/40%4]},${random.nextInt(200000)+1000},架空 $i"
            else "支出,$date,${expense[random.nextInt(expense.size)]},${random.nextInt(5000)+100},${if(i%4==0)"" else "架空の店 ${i%300}"}"
        }
        val bytes=("账簿,日期,类别,金额,备注\n"+rows.joinToString("\n")).toByteArray()
        val book=repo.createBook("大量记录试运行",Currency.JPY,startDate="2026-10-01",budgetMinor=150000)
        repo.updateBook(requireNotNull(repo.dao.book(book)).copy(initialized=true))
        val cash=repo.saveAccount(book,"现金",AccountType.CASH,Currency.JPY,Money(Currency.JPY,52000),"2026-10-27")
        val document=CsvParser.read(bytes)
        val settings=CsvParser.guess(document).copy(accountId=cash)
        val preview=repo.csvPreview(book,document,settings,emptyMap())
        repo.importCsv(book,"fictional.csv",CsvParser.hash(bytes),document,settings,emptyMap(),preview)
        val category=repo.saveCategory(book,"批量调整",EntryKind.EXPENSE,null)
        val before=requireNotNull(repo.dao.graph(book).first())
        assertEquals(3700,before.events.size)
        val targets=before.events.filter{it.kind==EntryKind.EXPENSE.name}.sortedWith(compareBy({it.date},{it.id})).take(50)
        assertEquals(50,targets.size)
        val drafts=targets.map{event->
            // These CSV fixtures are all JPY, with no media, transfers or financial links.
            EntryDraft(id=event.id,accountId=event.accountId,categoryId=category,amount=event.amountMinor.toString(),
                date=event.date,merchant=event.merchant,note=event.note,historicalIncluded=event.historicalIncluded,
                originalCurrency=Currency.JPY)
        }
        val elapsed=measureNanoTime {
            repo.mediaWrites.withLock {
                rollback {
                    drafts.forEachIndexed{index,draft->
                        val review=repo.previewEntry(book,draft)
                        assertEquals(targets[index].copy(categoryId=category,revision=targets[index].revision+1),review.change.event)
                        assertEquals(targets[index].id,repo.commitEntryReview(book,review))
                    }
                }
            }
        }
        val totalMs=elapsed/1_000_000.0
        Log.i("CommandSandbox","records=3700 steps=50 totalMs=$totalMs averageStepMs=${totalMs/50} (preview + commit + outer rollback; excludes fixture import)")
        assertEquals(before,repo.dao.graph(book).first())
    }
}
