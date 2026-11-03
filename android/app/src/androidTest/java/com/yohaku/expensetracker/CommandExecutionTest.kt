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

    @Test fun undoEntryEditAfterMerchantDeletionKeepsTextWithoutRestoringMissingLink()=runBlocking<Unit> {
        // Both an unchanged link removed locally and an old link present only in the undo journal.
        for(unlink in listOf(false,true)) {
            val (book,bank)=setup()
            val category=r.saveCategory(book,"支出",EntryKind.EXPENSE,null)
            val merchant=r.saveMerchant(book,"原店名")
            val event=r.saveEntry(book,EntryDraft(accountId=bank,categoryId=category,amount="500",date="2026-11-01",merchant="记录原名",merchantId=merchant))
            val original=snapshot(book).events.single()
            val patch=buildJsonObject {
                put("amount",buildJsonObject{put("currency","JPY");put("minor",700)})
                if(unlink)put("merchantId",JsonNull)
            }
            val input=buildJsonObject {
                put("targets",buildJsonObject{put("ids",JsonArray(listOf(JsonPrimitive(event))))})
                put("set",patch)
            }
            val row=receive(fixture(book,bank).copy(steps=listOf(CommandStep("edit","entry.update",1,input))))
            val preview=commands.preview(row.planId);assertTrue(preview.executable)
            commands.confirm(row.planId,commandChecks(preview))
            r.deleteMerchant(book,merchant)
            val inverse=commands.prepareUndo(row.planId);val undo=commands.preview(inverse.planId)
            assertTrue(undo.conflicts.toString(),undo.executable)
            commands.confirm(inverse.planId,commandChecks(undo))
            assertEquals(original,snapshot(book).events.single())
            assertTrue(snapshot(book).eventMerchants.isEmpty())
            assertNull(r.dao.merchant(book,merchant))
        }
    }

    @Test fun protocolCurrentDebtFreeCardExamplePreviewsWithUnknownLimitPolicies()=runBlocking<Unit> {
        val (book,bank)=setup()
        val exported=r.commandExports(book,RecordScope()).context
        assertEquals("2026-11-02",exported.asOfDate);assertEquals("Asia/Tokyo",exported.zoneId)
        assertEquals(JsonPrimitive(false),exported.items.single{it.kind=="book"}.values["initialized"])
        val protocol=context.assets.open("yohaku-command-protocol-v1.md").bufferedReader().use{it.readText()}
        val raw=protocol.substringAfter("## 完整示例：新信用卡、额度与当前还款规则").substringAfter("```json").substringBefore("```")
        val example=commandJson.decodeFromString<CommandPlan>(raw)
        val plan=example.copy(plan_id=UUID.randomUUID().toString(),book_id=book,catalog_digest=commands.registry.catalogDigest,context_digest=exported.contextDigest,
            steps=example.steps.map{step->if(step.command=="financialRule.addVersion")step.copy(input=JsonObject(step.input+("paymentAccount" to JsonPrimitive(bank))))else step})
        val before=CommandUndoJournal(r,book).snapshot()
        val row=receive(plan);assertEquals(row.error,"PENDING",row.status)
        val model=commands.preview(row.planId)
        assertEquals(2,model.steps.size);assertTrue(model.steps.flatMap{it.missing}.toString(),model.executable)
        assertTrue(model.requirements.any{it.kind==ConfirmationKind.USER_FACT&&it.stepId=="card"})
        assertTrue(model.steps.none{it.command=="financialPrediction.save"})
        assertEquals(before,CommandUndoJournal(r,book).snapshot())
    }

    @Test fun cardRulePredictionRunsLocallyThenCommitsWithDurableReceiptAndDeduplicates()=runBlocking<Unit> {
        val (book,bank)=setup();val input=fixture(book,bank).withCommandCreditLimit();val row=receive(input)
        val originalRows=CommandUndoJournal(r,book).snapshot()
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
        assertTrue(model.steps.all{it.undo.available})
        assertTrue(commands.undoAvailability(commands.plan(row.planId)).available)
        val inverse=commands.prepareUndo(row.planId);val undo=commands.preview(inverse.planId)
        assertTrue(undo.executable)
        assertEquals(setOf("accounts","financial_rules","financial_plans","financial_bills","financial_plan_revisions","credit_limit_configs"),undo.removals.map{it.table}.toSet())
        assertEquals(after,snapshot(book)) // removing owned rows during trial must roll back too
        commands.confirm(inverse.planId,commandChecks(undo))
        assertEquals(before,snapshot(book));assertEquals(originalRows,CommandUndoJournal(r,book).snapshot())
        assertTrue(r.scopedReferenceCounts(book,"accounts",card.id).isEmpty())
        db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use{assertFalse(it.moveToFirst())}
        assertEquals("UNDONE",commands.plan(row.planId).status);assertNotNull(db.cloudDao().imported(row.sourceKey))
        assertThrows(IllegalStateException::class.java){runBlocking<Unit>{commands.prepareUndo(row.planId)}}
        assertThrows(IllegalStateException::class.java){runBlocking<Unit>{commands.confirm(row.planId,emptySet())}}

    }


    @Test fun newCardCannotBeRemovedWhileExternalRecordsOrJsonDraftsReferToIt()=runBlocking<Unit> {
        val (book,bank)=setup();val row=receive(fixture(book,bank).withCommandCreditLimit())
        val forward=commands.preview(row.planId);commands.confirm(row.planId,commandChecks(forward))
        val card=snapshot(book).accounts.single{it.type=="CREDIT"}
        val inverse=commands.prepareUndo(row.planId);val preview=commands.preview(inverse.planId)
        r.saveDraft(book,EntryDraft(accountId=card.id,note="方案以外的草稿"))
        assertThrows(CommandRepreviewRequired::class.java){runBlocking<Unit>{commands.confirm(inverse.planId,commandChecks(preview))}}
        val draftConflict=commands.preview(inverse.planId)
        assertFalse(draftConflict.executable)
        assertTrue(draftConflict.conflicts.any{it.key==card.id&&it.reference?.table=="drafts"&&it.field=="json.accountId"})
        r.clearDraft(book)
        val category=r.saveCategory(book,"购物",EntryKind.EXPENSE,null)
        val event=r.saveEntry(book,EntryDraft(accountId=card.id,categoryId=category,amount="500",date="2026-11-02",merchant="后来购物"))
        val after=snapshot(book);val finance=CommandUndoJournal(r,book).snapshot()
        val blocked=commands.preview(inverse.planId)
        assertFalse(blocked.executable)
        assertTrue(blocked.conflicts.any{it.key==card.id&&it.reference?.let{ref->ref.table=="events"&&ref.key.contains(event)&&ref.name.contains("后来购物")}==true})
        assertThrows(IllegalStateException::class.java){runBlocking<Unit>{commands.confirm(inverse.planId,emptySet())}}
        assertEquals(after,snapshot(book));assertEquals(finance,CommandUndoJournal(r,book).snapshot())
        val forecast=readCommandExecution(commands.plan(row.planId).executionJson!!).outputs.getValue("forecast").getValue("prediction")
        val bill=db.financialDao().graph(book).first()!!.bills.first{it.planId==forecast}
        assertTrue(r.commitFinancialBillConfirmation(r.previewFinancialBillConfirmation(book,bill.id,KnownBillInput(bill.date,31000,30000,1000,bank))))
        val billConflict=commands.preview(inverse.planId)
        assertTrue(billConflict.conflicts.any{it.key==bill.id&&it.field=="confirmedTotalMinor"&&it.current==JsonPrimitive(31000)})
    }

    @Test fun existingAccountRuleAndPredictionRecalculationRestoreExactSnapshots()=runBlocking<Unit> {
        val (book,bank)=setup();val initial=receive(fixture(book,bank).withCommandCreditLimit())
        val first=commands.preview(initial.planId);commands.confirm(initial.planId,commandChecks(first))
        val graph=db.financialDao().graph(book).first()!!;val card=snapshot(book).accounts.single{it.type=="CREDIT"}
        val base=fixture(book,bank)
        val rule=base.steps[1].copy(input=JsonObject(base.steps[1].input+("account" to JsonPrimitive(card.id))))
        val prediction=base.steps[2].copy(input=JsonObject(base.steps[2].input+mapOf("account" to JsonPrimitive(card.id),"plan" to JsonPrimitive(graph.plans.single().id),
            "terms" to commandJson.parseToJsonElement("""{"agreementDate":"2026-11-01","paymentDates":["2026-12-12"]}"""))))
        val input=base.copy(steps=listOf(rule,prediction))
        val before=CommandUndoJournal(r,book).snapshot()
        val row=commands.receive(commandClaim(input,"recalculate-result"),"https://example.test",input.context_digest)
        val preview=commands.preview(row.planId);commands.confirm(row.planId,commandChecks(preview))
        // This account/finance-only journal has the same rows as the accepted stage-2 format.
        // Persist version 2 explicitly so this remains a compatibility test after v3 became default.
        val executed=commands.plan(row.planId)
        val execution=readCommandExecution(executed.executionJson!!)
        val legacy=execution.copy(undo=requireNotNull(execution.undo).copy(version=2))
        db.commandDao().update(executed.copy(executionJson=storeCommandJson(encoded(legacy).toString())))
        val inverse=commands.prepareUndo(row.planId);val undo=commands.preview(inverse.planId)
        assertEquals(listOf("local.restoreExecution"),undo.steps.map{it.command})
        assertTrue(undo.executable);assertTrue(undo.removals.any{it.table=="financial_rules"})
        assertTrue(undo.removals.none{it.table in setOf("accounts","financial_plans")})
        commands.confirm(inverse.planId,commandChecks(undo))
        assertEquals(before,CommandUndoJournal(r,book).snapshot())
        assertEquals(graph,db.financialDao().graph(book).first())
    }

    @Test fun categoryUndoRestoresPersistedUncategorizedState()=runBlocking<Unit> {
        val (book,bank)=setup();val category=r.saveCategory(book,"分类",EntryKind.EXPENSE,null)
        val id=r.saveEntry(book,EntryDraft(accountId=bank,categoryId=category,amount="500",date="2026-11-01"))
        // The persisted schema permits a historical expense without a category, although the editor
        // requires one on a new save. Seed that pre-existing state, not a new cloud capability.
        r.dao.putEvent(snapshot(book).events.single().copy(categoryId=null))
        val input=fixture(book,bank).copy(steps=listOf(CommandStep("recat","entry.update",1,encoded(EntryUpdateInput(EntryTargets(ids=listOf(id)),EntryCategorySet(category))).jsonObject)))
        val row=receive(input);val preview=commands.preview(row.planId);commands.confirm(row.planId,commandChecks(preview))
        // Exact v2 category inverse shape from 92db7a6: per-category local steps and full guards,
        // no owned event/revision rows. It must restore null and append an audit revision.
        val after=snapshot(book)
        val executed=commands.plan(row.planId)
        val execution=readCommandExecution(executed.executionJson!!)
        val legacy=execution.copy(undo=UndoRecord(
            steps=listOf(CommandStep("undo_recat_0","local.entry.restoreCategory",1,encoded(RestoreCategoryInput(listOf(id),null)).jsonObject)),
            guards=listOf(UndoGuard("entry",id,encoded(after.events.single())),UndoGuard("entryDetails",id,encoded(after.entryDetails(id)))),
            effects=requireNotNull(execution.undo).effects,version=2))
        db.commandDao().update(executed.copy(executionJson=storeCommandJson(encoded(legacy).toString())))
        val inverse=commands.prepareUndo(row.planId);val undo=commands.preview(inverse.planId)
        assertEquals(listOf("local.entry.restoreCategory"),undo.steps.map{it.command})
        assertTrue(undo.executable);commands.confirm(inverse.planId,commandChecks(undo))
        assertNull(snapshot(book).events.single().categoryId)
        assertEquals(after.events.single().revision+1,snapshot(book).events.single().revision)
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
        val inverse=commands.prepareUndo(row.planId);val undo=commands.preview(inverse.planId)
        assertTrue(undo.executable);assertEquals(after,snapshot(book))
        assertEquals(20,undo.net.count{it.type=="entry"&&it.before!=it.after})
        commands.confirm(inverse.planId,commandChecks(undo))
        val restored=snapshot(book)
        assertEquals(before.events.sortedBy{it.id},restored.events.map{it.copy(revision=1)}.sortedBy{it.id})
        assertEquals(before.accounts,restored.accounts);assertEquals(before.settlements,restored.settlements)
        assertEquals(before.storedPostings,restored.storedPostings)
        assertEquals(before.budgetProjection("2026-11",old),restored.budgetProjection("2026-11",old))
        assertEquals("UNDONE",commands.plan(row.planId).status);assertEquals(clock.millis(),commands.plan(row.planId).undoneAt)
        assertNotNull(commands.plan(row.planId).executionJson);assertNotNull(commands.plan(inverse.planId).executionJson)
        assertNotNull(db.cloudDao().imported(row.sourceKey))
        assertEquals(1,db.cloudDao().pendingAcks().size) // local undo sends no cloud receipt
        assertThrows(IllegalStateException::class.java){runBlocking<Unit>{commands.prepareUndo(row.planId)}}
        assertThrows(IllegalStateException::class.java){runBlocking<Unit>{commands.prepareUndo(inverse.planId)}}
        assertThrows(IllegalStateException::class.java){runBlocking<Unit>{commands.confirm(row.planId,emptySet())}}
        commands.confirm(inverse.planId,emptySet()) // retry is idempotent, never writes again
        val receivedAgain=commands.receive(commandClaim(plan,"after-undo-result",2),"https://example.test",plan.context_digest)
        assertEquals("UNDONE",receivedAgain.status);assertEquals(restored,snapshot(book))
    }


    @Test fun undoChecksAllTargetsAndRechecksAtConfirmation()=runBlocking<Unit> {
        val (book,bank)=setup();val old=r.saveCategory(book,"餐饮",EntryKind.EXPENSE,null);val next=r.saveCategory(book,"咖啡",EntryKind.EXPENSE,null)
        repeat(20){r.saveEntry(book,EntryDraft(accountId=bank,categoryId=old,amount="500",date="2026-11-01",merchant="店 $it"))}
        val before=snapshot(book)
        val input=fixture(book,bank).copy(steps=listOf(CommandStep("recat","entry.update",1,encoded(EntryUpdateInput(EntryTargets(ids=before.events.map{it.id}),EntryCategorySet(next))).jsonObject)))
        val row=receive(input);val preview=commands.preview(row.planId);commands.confirm(row.planId,commandChecks(preview))
        val inverse=commands.prepareUndo(row.planId);val undo=commands.preview(inverse.planId)
        val changed=snapshot(book).events.first()
        // A normal save changes the note and revision after the undo preview.
        r.saveEntry(book,EntryDraft(id=changed.id,accountId=bank,categoryId=next,amount="500",date=changed.date,merchant=changed.merchant,note="后来修改"))
        val afterEdit=snapshot(book)
        assertThrows(CommandRepreviewRequired::class.java){runBlocking<Unit>{commands.confirm(inverse.planId,commandChecks(undo))}}
        val blocked=commands.preview(inverse.planId)
        assertFalse(blocked.executable)
        assertTrue(blocked.conflicts.any{it.key==changed.id&&it.field=="note"&&it.current==JsonPrimitive("后来修改")})
        assertTrue(blocked.conflicts.any{it.key==changed.id&&it.field=="revision"})
        assertThrows(IllegalStateException::class.java){runBlocking<Unit>{commands.confirm(inverse.planId,emptySet())}}
        assertEquals(afterEdit,snapshot(book));assertTrue(afterEdit.events.all{it.categoryId==next})

        val accountPlan=fixture(book,bank).copy(steps=listOf(fixture(book,bank).steps.first()))
        val accountRow=commands.receive(commandClaim(accountPlan,"account-result"),"https://example.test",accountPlan.context_digest)
        val accountPreview=commands.preview(accountRow.planId);commands.confirm(accountRow.planId,commandChecks(accountPreview))
        val card=snapshot(book).accounts.single{it.type=="CREDIT"}
        r.saveAccount(book,"后来改名",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,card.openingMinor),card.openingDate,card.id)
        val accountUndo=commands.prepareUndo(accountRow.planId);val accountBlocked=commands.preview(accountUndo.planId)
        assertFalse(accountBlocked.executable)
        assertTrue(accountBlocked.conflicts.any{it.key==card.id&&it.field=="name"&&it.current==JsonPrimitive("后来改名")})
        assertEquals("EXECUTED",commands.plan(accountRow.planId).status)
    }

    @Test fun unusedAccountCreationAndAccountEditUseExistingOperationsToUndo()=runBlocking<Unit> {
        val (book,bank)=setup();val before=snapshot(book)
        val original=fixture(book,bank)
        val edit=CommandStep("edit","account.save",1,encoded(AccountCommandInput("新的名称","JPY",80000,"2026-11-01",JsonPrimitive(bank),"BANK")).jsonObject)
        val input=original.copy(steps=listOf(edit,original.steps.first()))
        val row=receive(input);val preview=commands.preview(row.planId);commands.confirm(row.planId,commandChecks(preview))
        val inverse=commands.prepareUndo(row.planId);val undo=commands.preview(inverse.planId)
        assertTrue(undo.executable);assertTrue(undo.net.any{it.type=="account"&&it.after==JsonNull})
        commands.confirm(inverse.planId,commandChecks(undo))
        assertEquals(before.accounts,snapshot(book).accounts);assertEquals(before.events,snapshot(book).events)
        assertEquals("UNDONE",commands.plan(row.planId).status)
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

    @Test fun fiveHundredCategoryChangesOn3700RecordsReportPreviewAndConfirmTimes()=runBlocking<Unit> {
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
