package com.yohaku.expensetracker

import android.util.Log
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.data.command.*
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.csv.CsvParser
import com.yohaku.expensetracker.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.*
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.measureNanoTime

@RunWith(AndroidJUnit4::class)
class CommandExecutionTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val clock=Clock.fixed(Instant.parse("2026-11-02T03:00:00Z"),ZoneId.of("Asia/Tokyo"))
    private val name="command-stage1.db"
    private lateinit var db:YohakuDatabase
    private lateinit var r:LedgerRepository
    private lateinit var commands:CommandRepository
    private val cancelJob=AtomicReference<Job?>(null)
    private val inserts=AtomicInteger(0)
    @Before fun open() {
        context.deleteDatabase(name)
        db=Room.databaseBuilder(context,YohakuDatabase::class.java,name).setQueryCallback(object:RoomDatabase.QueryCallback {
            override fun onQuery(sqlQuery:String,bindArgs:List<Any?>) {
                if(cancelJob.get()!=null&&sqlQuery.contains("INSERT",ignoreCase=true)&&sqlQuery.contains("`accounts`")&&inserts.incrementAndGet()==2)cancelJob.get()?.cancel()
            }
        },Executor{it.run()}).build()
        r=LedgerRepository(db,clock);commands=CommandRepository(r)
    }
    @After fun close(){db.close();context.deleteDatabase(name)}
    private suspend fun setup():Pair<String,String> {
        val book=r.createBook("办理测试",Currency.JPY,startDate="2026-11-01",budgetMinor=100000)
        val bank=r.saveAccount(book,"扣款账户",AccountType.BANK,Currency.JPY,Money(Currency.JPY,100000),"2026-11-01")
        return book to bank
    }
    private suspend fun receive(plan:CommandPlan)=commands.receive(commandClaim(plan),"https://example.test",plan.context_digest)
    private suspend fun fixture(book:String,bank:String)=commandCardPlan(book,bank,commands.registry.catalogDigest,r.commandExports(book,RecordScope()).context.contextDigest)
    private suspend fun snapshot(book:String)=r.snapshot(r.dao.book(book)!!).first()

    @Test fun cardRulePredictionRunsLocallyThenCommitsWithDurableReceiptAndDeduplicates()=runBlocking<Unit> {
        val (book,bank)=setup();val input=fixture(book,bank);val row=receive(input)
        val before=snapshot(book);val model=commands.preview(row.planId)
        assertEquals(before,snapshot(book));assertEquals(3,model.steps.size);assertTrue(model.executable)
        assertNull("Repeated previews must differ only in generated identities",CommandConfirmationComparator.firstDifference(model,commands.preview(row.planId)))
        assertTrue(model.requirements.none{it.kind==ConfirmationKind.RISK})
        assertTrue(model.requirements.any{it.kind==ConfirmationKind.USER_FACT&&it.stepId=="forecast"})
        val bill=model.net.single{it.type=="bill"}.after.jsonObject
        assertEquals(30000L,bill.getValue("predictedTotalMinor").jsonPrimitive.long)
        assertEquals("2026-12-10",bill.getValue("predictedDate").jsonPrimitive.content)
        assertFalse(bill.getValue("confirmed").jsonPrimitive.boolean)
        assertThrows(IllegalArgumentException::class.java){runBlocking{commands.confirm(row.planId,emptySet())}}
        commands.confirm(row.planId,commandChecks(model))
        val after=snapshot(book);val finance=db.financialDao().graph(book).first()!!
        val card=after.accounts.single{it.type=="CREDIT"}
        assertEquals(30000L,after.balance(card));assertTrue(after.events.isEmpty())
        assertEquals(1,finance.rules.size);assertEquals(1,finance.plans.size);assertEquals(30000L,finance.bills.single().totalMinor)
        assertEquals("EXECUTED",commands.plan(row.planId).status)
        val execution=readCommandDocument<CommandExecutionRecord>(commands.plan(row.planId).executionJson!!)
        assertEquals(card.id,execution.outputs.getValue("card").getValue("account"))
        assertEquals(card.id,execution.generatedIds.getValue("@card/account/"))
        assertEquals("imported",db.cloudDao().pendingAcks().single().status)
        // A new executor represents interruption after commit but before the worker sends the receipt.
        commands=CommandRepository(r)
        commands.confirm(row.planId,emptySet())
        commands.receive(commandClaim(input),"https://example.test",input.context_digest)
        commands.receive(commandClaim(input.copy(message="第二个结果版本"),"result-2",2),"https://example.test",input.context_digest)
        assertEquals(after,snapshot(book));assertEquals(2,db.cloudDao().pendingAcks().size)
    }

    @Test fun categoryBatchFreezesTwentyTargetsAndPreservesSettlementAndOtherFacts()=runBlocking<Unit> {
        val (book,bank)=setup();val old=r.saveCategory(book,"餐饮",EntryKind.EXPENSE,null);val next=r.saveCategory(book,"咖啡",EntryKind.EXPENSE,null)
        repeat(20){r.saveEntry(book,EntryDraft(accountId=bank,categoryId=old,amount="500",date="2026-11-01",merchant="星巴克 $it"))}
        val before=snapshot(book)
        val plan=fixture(book,bank).copy(steps=listOf(CommandStep("recat","entry.update",1,encoded(EntryUpdateInput(EntryTargets(select=EntrySelector(category=old,merchant_contains="星巴克",kind="EXPENSE")),EntryCategorySet(next))).jsonObject)))
        val row=receive(plan);val model=commands.preview(row.planId)
        assertEquals(before,snapshot(book));assertEquals(20,model.steps.single().targets.size)
        val normalized=readCommandDocument<NormalizedPlan>(commands.plan(row.planId).normalizedJson!!)
        val target=commandJson.decodeFromJsonElement<EntryUpdateInput>(normalized.steps.single().input).targets
        assertNull(target.select);assertEquals(before.events.map{it.id}.toSet(),target.ids!!.toSet())
        assertTrue(model.net.any{it.type=="budget"&&it.before!=it.after})
        commands.confirm(row.planId,commandChecks(model))
        val after=snapshot(book)
        assertTrue(after.events.all{it.categoryId==next&&it.revision==2})
        assertEquals(before.settlements,after.settlements);assertEquals(before.storedPostings,after.storedPostings)
        assertEquals(before.events.map{it.amountMinor},after.events.map{it.amountMinor})
    }

    @Test fun relatedChangeRequiresNewPreviewButUnrelatedAccountNameDoesNot()=runBlocking<Unit> {
        val (book,bank)=setup();val unrelated=r.saveAccount(book,"零钱",AccountType.CASH,Currency.JPY,Money(Currency.JPY,100),"2026-11-01")
        val row=receive(fixture(book,bank));val model=commands.preview(row.planId)
        r.saveAccount(book,"扣款账户",AccountType.BANK,Currency.JPY,Money(Currency.JPY,90000),"2026-11-01",bank)
        assertThrows(CommandRepreviewRequired::class.java){runBlocking{commands.confirm(row.planId,commandChecks(model))}}
        assertEquals(2,snapshot(book).accounts.size);assertNull(commands.plan(row.planId).confirmationJson)
        val fresh=commands.preview(row.planId)
        r.saveAccount(book,"改名但不相关",AccountType.CASH,Currency.JPY,Money(Currency.JPY,100),"2026-11-01",unrelated)
        commands.confirm(row.planId,commandChecks(fresh))
        assertEquals(3,snapshot(book).accounts.size)
    }

    @Test fun missingPredictionDataStaysVisibleAndPreventsAllWrites()=runBlocking<Unit> {
        val (book,bank)=setup();val original=fixture(book,bank)
        val last=original.steps.last().copy(input=JsonObject(original.steps.last().input.filterKeys{it!="terms"&&it!="openingPrincipalMinor"}))
        val row=receive(original.copy(steps=original.steps.dropLast(1)+last));val before=snapshot(book)
        val model=commands.preview(row.planId)
        assertEquals(listOf("prediction.dates","prediction.openingPrincipal"),model.steps.last().missing)
        assertFalse(model.executable);assertEquals(before,snapshot(book))
        assertThrows(IllegalStateException::class.java){runBlocking{commands.confirm(row.planId,commandChecks(model))}}
    }

    @Test fun middleStepFailureAndCoroutineCancellationLeaveNoPartialLedger()=runBlocking<Unit> {
        val (book,bank)=setup();val original=fixture(book,bank);val before=snapshot(book)
        val failed=receive(original);val preview=commands.preview(failed.planId)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER command_reject_rule BEFORE INSERT ON financial_rules BEGIN SELECT RAISE(ABORT,'command middle failure'); END")
        try {
            assertThrows(android.database.sqlite.SQLiteException::class.java){runBlocking{commands.confirm(failed.planId,commandChecks(preview))}}
        }finally{db.openHelper.writableDatabase.execSQL("DROP TRIGGER command_reject_rule")}
        assertEquals(before,snapshot(book));assertEquals("FAILED",commands.plan(failed.planId).status)
        val cancelPlan=original.copy(plan_id=UUID.randomUUID().toString(),steps=listOf(original.steps.first(),original.steps.first().copy(id="second")))
        val cancelRow=commands.receive(commandClaim(cancelPlan,"cancel-result"),"https://example.test",cancelPlan.context_digest)
        val cancelPreview=commands.preview(cancelRow.planId)
        inserts.set(0)
        val job=launch {
            cancelJob.set(currentCoroutineContext().job)
            commands.confirm(cancelRow.planId,commandChecks(cancelPreview))
        }
        job.join();cancelJob.set(null)
        assertTrue("Cancellation must occur after a preceding account insert",job.isCancelled)
        assertEquals(before,snapshot(book));assertEquals("PENDING",commands.plan(cancelRow.planId).status)
    }

    @Test fun fiveHundredCategoryChangesOn3700RecordsReportPreviewAndConfirmTimes()=runBlocking {
        val (book,bank)=setup()
        val rows=(0 until 3700).map{i->"支出,${LocalDate.of(2023,8,17).plusDays(i*1167L/3700)},餐饮,${100+i%5000},架空の店 ${i%300}"}
        val bytes=("账簿,日期,类别,金额,备注\n"+rows.joinToString("\n")).toByteArray()
        val document=CsvParser.read(bytes);val settings=CsvParser.guess(document).copy(accountId=bank)
        r.importCsv(book,"fictional.csv",CsvParser.hash(bytes),document,settings,emptyMap(),r.csvPreview(book,document,settings,emptyMap()))
        val next=r.saveCategory(book,"批量调整",EntryKind.EXPENSE,null)
        val before=snapshot(book);assertEquals(3700,before.events.size)
        val targets=before.events.take(500).map{it.id}
        val input=fixture(book,bank).copy(steps=listOf(CommandStep("recat","entry.update",1,encoded(EntryUpdateInput(EntryTargets(ids=targets),EntryCategorySet(next))).jsonObject)))
        val row=receive(input);var model:ConfirmationModel?=null
        val previewMs=measureNanoTime{model=commands.preview(row.planId)}/1_000_000.0
        assertEquals(before,snapshot(book))
        val confirmMs=measureNanoTime{commands.confirm(row.planId,commandChecks(model!!))}/1_000_000.0
        Log.i("CommandStage1","records=3700 steps=1 targets=500 previewMs=$previewMs confirmMs=$confirmMs (full executor; target <=5000ms each; no timing assertion)")
        assertEquals(500,snapshot(book).events.count{it.categoryId==next})
    }
}
