package com.yohaku.expensetracker

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.data.cloud.*
import com.yohaku.expensetracker.data.command.*
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.files.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.csv.*
import com.yohaku.expensetracker.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.time.*
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CommandWorkflowExecutionTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db:YohakuDatabase
    private lateinit var r:LedgerRepository
    private lateinit var commands:CommandRepository
    private lateinit var prefs:AppPreferences
    private lateinit var root:File
    private lateinit var preferenceScope:CoroutineScope
    private var now=Instant.parse("2026-11-02T03:00:00Z")
    private val clock=object:Clock(){override fun getZone()=ZoneId.of("Asia/Tokyo");override fun withZone(zone:ZoneId):Clock=Clock.fixed(now,zone);override fun instant()=now}
    @Before fun open() {
        root=File(context.cacheDir,"command-workflow-${UUID.randomUUID()}").apply{mkdirs()}
        db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        r=LedgerRepository(db,clock,File(root,"media"))
        preferenceScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        prefs=AppPreferences(context,PreferenceDataStoreFactory.create(scope=preferenceScope,produceFile={File(root,"test.preferences_pb")}))
        commands=CommandRepository(r,preferences=prefs,sourceRoot=File(root,"cloud"))
    }
    @After fun close(){preferenceScope.cancel();db.close();root.deleteRecursively()}
    private fun step(id:String,name:String,input:String)=CommandStep(id,name,1,commandJson.parseToJsonElement(input).jsonObject)
    private suspend fun setup():Triple<String,String,String> {
        val b=r.createBook("文件与设置",Currency.JPY,startDate="2026-11-01")
        val a=r.saveAccount(b,"银行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,100000),"2026-11-01")
        val c=r.saveCategory(b,"日常",EntryKind.EXPENSE,null)
        prefs.select(b);return Triple(b,a,c)
    }
    private suspend fun snapshot(book:String)=r.snapshot(requireNotNull(r.dao.book(book))).first()
    private suspend fun receive(book:String,steps:List<CommandStep>,localId:String=UUID.randomUUID().toString()):CommandPlanEntity {
        val plan=CommandPlan("yohaku.command-plan",1,UUID.randomUUID().toString(),book,commands.registry.catalogDigest,r.commandExports(book,RecordScope(),prefs).context.contextDigest,"文件工作流",steps=steps)
        return commands.receive(commandClaim(plan,localId=localId),"https://example.test",plan.context_digest)
    }
    private suspend fun execute(row:CommandPlanEntity):ConfirmationModel {
        val before=CommandUndoJournal(r,row.bookId).snapshot();val preferences=prefs.commandValues(row.bookId)
        val model=commands.preview(row.planId);assertTrue(model.conflicts.toString()+model.unknown,model.executable)
        assertEquals(before,CommandUndoJournal(r,row.bookId).snapshot());assertEquals(preferences,prefs.commandValues(row.bookId))
        assertTrue(db.commandDao().planEffects(row.planId).isEmpty())
        now=now.plusSeconds(2)
        commands.confirm(row.planId,commandChecks(model));return model
    }
    private suspend fun undo(row:CommandPlanEntity) {
        val original=commands.prepareUndo(row.planId);val before=CommandUndoJournal(r,row.bookId).snapshot()
        val model=commands.preview(original.planId);assertTrue(model.conflicts.toString(),model.executable)
        assertEquals(before,CommandUndoJournal(r,row.bookId).snapshot())
        commands.confirm(original.planId,commandChecks(model));assertEquals("UNDONE",commands.plan(row.planId).status)
        db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use{assertFalse(it.moveToFirst())}
    }
    @Test fun statementsProductsAndSourceAttachmentRetryThenUndo()=runBlocking<Unit> {
        val (book,account,category)=setup()
        val draft=EntryDraft(accountId=account,categoryId=category,amount="1000",date="2026-11-02")
        r.saveDraft(book,draft)
        r.importMedia(book,draft.mediaSession,"receipt.jpg","image/jpeg",receipt=true,preserveOriginal=true){byteArrayOf(1,2,3).inputStream()}
        val entry=r.saveEntry(book,r.json.decodeFromString<EntryDraft>(r.dao.storedDraft(book)!!.json))
        val receipt=snapshot(book).receipts.single();val imageId=snapshot(book).entryReceiptImages(entry).single().id
        val source=StatementFile("yohaku.statement",1,r.id(),book,account,"JPY","2026-11-01","2026-11-02","posted_date",true,"银行",emptyList(),listOf(
            StatementRow(r.id(),"2026-11-02","out",FileMoney("JPY","950"),"原消费"),
            StatementRow(r.id(),"2026-11-02","out",FileMoney("JPY","300"),"补记消费")))
        r.saveStatement(book,r.previewStatement(book,FileProtocols.json.encodeToString(source)))
        val local=UUID.randomUUID().toString();val directory=File(root,"cloud/$local").apply{mkdirs()};val file=File(directory,"source-0").apply{writeText("original-source")}
        db.cloudDao().insertTask(CloudTaskEntity(local,book,"general","源文件",null,null,"https://example.test",local,"{}",createdAt=0,updatedAt=0,purpose="command"))
        db.cloudDao().insertTransfers(listOf(CloudTransferEntity(local,0,"source.txt",file.path,file.length(),sha256("original-source"),"text/plain",fileId="uploaded-source",uploadedAt=0)))
        val before=CommandUndoJournal(r,book).snapshot()
        val row=receive(book,listOf(
            step("accept","statement.accept","""{"batch":"${source.file_id}","row":"${source.rows[0].row_id}","entry":"$entry","note":"核对差异"}"""),
            step("unlink","statement.unlink","""{"batch":"${source.file_id}","row":"${source.rows[0].row_id}"}"""),
            step("actual","statement.confirmActual","""{"batch":"${source.file_id}","row":"${source.rows[0].row_id}","entry":"$entry","postedDate":"2026-11-02"}"""),
            step("supplement","statement.supplement","""{"batch":"${source.file_id}","row":"${source.rows[1].row_id}","occurredDate":"2026-11-02","postedDate":"2026-11-02","set":{"category":"$category"}}"""),
            step("products","receipt.items.save","""{"receipt":"${receipt.id}","imageIds":["$imageId"],"items":[{"name":"牛奶","quantity":"1","unitPrice":{"currency":"JPY","minor":950},"amount":{"currency":"JPY","minor":950}}],"total":{"currency":"JPY","minor":950},"complete":true}"""),
            step("attach","attachment.attachSource","""{"entry":"$entry","sourceName":"source.txt"}""")),local)
        val preview=commands.preview(row.planId)
        assertEquals(before,CommandUndoJournal(r,book).snapshot());assertEquals("post-commit",preview.steps.last().mode)
        file.delete() // metadata still compares; the deferred physical source is unavailable.
        commands.confirm(row.planId,commandChecks(preview))
        assertEquals("RETRY",db.commandDao().planEffects(row.planId).single().status)
        assertEquals(950L,snapshot(book).events.single{it.id==entry}.amountMinor)
        assertEquals(2,snapshot(book).visibleEvents.size)
        assertEquals("牛奶",FileProtocols.json.decodeFromString<ReceiptItemsRecord>(db.fileWorkflowDao().itemsForReceipt(book,receipt.id)!!.recordJson).items.single().name)
        file.writeText("original-source")
        commands=CommandRepository(r,preferences=prefs,sourceRoot=File(root,"cloud"))
        commands.followups.retry(db.commandDao().planEffects(row.planId).single().id)
        commands.followups.resume();assertEquals(1,r.dao.attachments(book,entry).size)
        assertEquals("original-source",r.mediaFile(r.dao.file(book,r.dao.attachments(book,entry).single().fileId)!!).readText())
        undo(row)
        assertEquals(before,CommandUndoJournal(r,book).snapshot());assertTrue(r.dao.attachments(book,entry).isEmpty());assertTrue(file.exists())
        assertTrue(runCatching{commands.prepareUndo(row.planId)}.isFailure)
    }
    @Test fun allPreferencesSwitchAfterCommitAndRestoreWithConflict()=runBlocking<Unit> {
        val (book,account,category)=setup();val other=r.createBook("旅行",Currency.JPY,startDate="2026-11-01")
        val destination=r.saveCategory(book,"食品",EntryKind.EXPENSE,null)
        r.saveDraft(book,EntryDraft(accountId=account,categoryId=category,note="未补完输入",date="2026-11-02"))
        prefs.setForegroundLocation(book,true)
        prefs.saveReminderSettings(book,com.yohaku.expensetracker.domain.reminders.ReminderPreferences(enabled=true))
        val before=prefs.commandValues(book);val journal=CommandUndoJournal(r,book).snapshot()
        assertEquals(JsonPrimitive(true),before.getValue("haptics"))
        val row=receive(book,listOf(
            step("draftCategory","entryDraft.reclassify","""{"sourceCategory":"$category","category":"$destination"}"""),
            step("analysis","preferences.analysis","""{"modules":["TREND","CATEGORIES"],"pinned":["TREND"]}"""),
            step("reminders","preferences.reminders","""{"enabled":false,"monthlyReview":false,"time":"08:30","repaymentDays":[3,1],"budget":false}"""),
            step("rates","preferences.automaticRates","""{"enabled":false}"""),
            step("location","preferences.location","""{"enabled":false}"""),
            step("photos","preferences.photoStorage","""{"original":true}"""),
            step("haptics","preferences.haptics","""{"enabled":false}"""),
            step("switch","book.select","""{"book":"$other"}""")))
        val preview=commands.preview(row.planId);assertEquals(book,prefs.activeBook.first());assertEquals(before,prefs.commandValues(book));assertEquals(journal,CommandUndoJournal(r,book).snapshot())
        val hapticChange=preview.steps.single{it.stepId=="haptics"}.objects.single()
        assertEquals(JsonPrimitive(true),hapticChange.before.jsonObject["haptics"]);assertEquals(JsonPrimitive(false),hapticChange.after.jsonObject["haptics"])
        commands.confirm(row.planId,commandChecks(preview))
        assertEquals(other,prefs.activeBook.first());assertFalse(prefs.automaticReferenceRates.first());assertTrue(prefs.photoSaveOriginal.first());assertFalse(prefs.hapticsEnabled.first())
        assertEquals("08:30",prefs.reminderSettings.first()[book]!!.time);assertFalse(book in prefs.foregroundLocationBooks.first())
        db.commandDao().planEffects(row.planId).filter{it.status=="WAITING"}.forEach{commands.followups.completeInteraction(it.id)}
        prefs.setPhotoSaveOriginal(false);prefs.setHapticsEnabled(true)
        val inverse=commands.prepareUndo(row.planId);val blocked=commands.preview(inverse.planId)
        assertFalse(blocked.executable);assertTrue(blocked.conflicts.any{it.type=="preference"&&it.key=="photoOriginal"});assertTrue(blocked.conflicts.any{it.type=="preference"&&it.key=="haptics"})
        assertTrue(runCatching{commands.confirm(inverse.planId,commandChecks(blocked))}.isFailure)
        prefs.setPhotoSaveOriginal(true);prefs.setHapticsEnabled(false);undo(row)
        assertEquals(before,prefs.commandValues(book));assertEquals(journal,CommandUndoJournal(r,book).snapshot())
        // An already queued v37 preference has no monthlyReview field. Upgrade uses its default.
        val legacyBefore=JsonObject(before.getValue("reminders").jsonObject.filterKeys{it!="monthlyReview"})
        val legacyAfter=JsonObject(legacyBefore+("enabled" to JsonPrimitive(false)))
        prefs.applyCommandPreference("legacy-reminders",book,"reminders",legacyBefore,legacyAfter)
        assertFalse(prefs.reminderSettings.first()[book]!!.enabled);assertTrue(prefs.reminderSettings.first()[book]!!.monthlyReview)
    }
    @Test fun csvBatchTrashRetainsLaterEditsAndUndoRestoresExactRows()=runBlocking<Unit> {
        val (book,account,category)=setup()
        val document=CsvParser.read("date,amount,note\n2026-11-02,100,one\n2026-11-02,200,two\n".toByteArray(),"UTF-8",",",true)
        val settings=CsvSettings(accountId=account,rule="EXPENSE",columns=mapOf("date" to 0,"amount" to 1,"note" to 2),defaults=mapOf("EXPENSE" to category))
        val review=r.csvPreview(book,document,settings,emptyMap());assertTrue(review.rows.toString(),review.canImport)
        val batch=r.importCsv(book,"source.csv","source-hash",document,settings,emptyMap(),review)
        val event=snapshot(book).visibleEvents.first();r.saveEntry(book,snapshot(book).editingEntryDraft(event).copy(note="后来编辑"))
        val before=CommandUndoJournal(r,book).snapshot()
        val row=receive(book,listOf(step("undoCsv","csvBatch.undo","""{"batch":"${batch.id}"}""")))
        execute(row)
        val s=snapshot(book);assertTrue(s.visibleEvents.isEmpty());assertEquals(2,s.events.size);assertEquals("后来编辑",s.events.single{it.id==event.id}.note)
        assertNotNull(db.csvImportDao().batch(book,batch.id));undo(row)
        assertEquals(before,CommandUndoJournal(r,book).snapshot())
    }
    @Test fun interruptedPreferenceWriteResumesWithoutOverwritingLaterUserChange()=runBlocking<Unit> {
        val (book,_,_)=setup()
        var writes=0
        commands=CommandRepository(r,preferences=prefs,afterEffectWrite={writes++;throw CancellationException("simulated process stop")})
        val row=receive(book,listOf(step("haptics","preferences.haptics","""{"enabled":false}""")))
        val preview=commands.preview(row.planId)
        assertTrue(runCatching{commands.confirm(row.planId,commandChecks(preview))}.exceptionOrNull() is CancellationException)
        assertEquals("EXECUTED",commands.plan(row.planId).status);assertEquals("RUNNING",db.commandDao().planEffects(row.planId).single().status)
        assertFalse(prefs.hapticsEnabled.first());prefs.setHapticsEnabled(true)
        commands=CommandRepository(r,preferences=prefs)
        commands.followups.resume();commands.followups.resume()
        assertTrue(prefs.hapticsEnabled.first());assertEquals(1,writes);assertEquals("DONE",db.commandDao().planEffects(row.planId).single().status)
        assertEquals(2,db.commandDao().planEffects(row.planId).single().attempts)
    }
    @Test fun interactiveDraftIsCreatedOnceAndNeverSubmitted()=runBlocking<Unit> {
        val (book,_,_)=setup()
        val row=receive(book,listOf(step("draft","entry.prepareDraft","""{"set":{"merchant":"待补完","note":"请补金额"}}"""),step("export","backup.export","{}")))
        val preview=execute(row);assertTrue(preview.steps.all{it.mode=="interactive"&&!it.undo.available})
        assertTrue(snapshot(book).visibleEvents.isEmpty());val draft=r.dao.storedDraft(book)!!
        assertEquals("",r.json.decodeFromString<EntryDraft>(draft.json).amount)
        commands=CommandRepository(r,preferences=prefs);commands.followups.resume()
        assertEquals(draft,r.dao.storedDraft(book));assertTrue(db.commandDao().planEffects(row.planId).all{it.status=="WAITING"})
        assertTrue(runCatching{commands.prepareUndo(row.planId)}.isFailure)
        commands.followups.completeInteraction(db.commandDao().planEffects(row.planId).first().id)
        assertTrue(snapshot(book).visibleEvents.isEmpty());assertEquals(draft,r.dao.storedDraft(book))
    }
}
