package com.yohaku.expensetracker

import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.data.command.*
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.csv.CsvParser
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.domain.merchant.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.*
import java.util.UUID
import kotlin.system.measureNanoTime

@RunWith(AndroidJUnit4::class)
class CommandLedgerExecutionTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db:YohakuDatabase
    private lateinit var r:LedgerRepository
    private lateinit var commands:CommandRepository
    private var now=Instant.parse("2026-11-02T03:00:00Z")
    private val clock=object:Clock(){override fun getZone()=ZoneId.of("Asia/Tokyo");override fun withZone(zone:ZoneId):Clock=Clock.fixed(now,zone);override fun instant()=now}
    @Before fun open(){db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build();r=LedgerRepository(db,clock);commands=CommandRepository(r)}
    @After fun close(){db.close()}
    private fun step(id:String,name:String,input:String)=CommandStep(id,name,1,commandJson.parseToJsonElement(input).jsonObject)
    private suspend fun setup():Pair<String,String>{val b=r.createBook("基础账本",Currency.JPY,startDate="2026-11-01");val a=r.saveAccount(b,"银行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,100000),"2026-11-01");return b to a}
    private suspend fun snapshot(book:String)=r.snapshot(requireNotNull(r.dao.storedBook(book))).first()
    private suspend fun receive(book:String,steps:List<CommandStep>):CommandPlanEntity {
        val plan=CommandPlan("yohaku.command-plan",1,UUID.randomUUID().toString(),book,commands.registry.catalogDigest,r.commandExports(book,RecordScope()).context.contextDigest,message="基础账本组合",steps=steps)
        return commands.receive(commandClaim(plan),"https://example.test",plan.context_digest)
    }
    private suspend fun execute(book:String,steps:List<CommandStep>):CommandPlanEntity {
        val row=receive(book,steps);val preview=commands.preview(row.planId)
        assertTrue(preview.executable)
        return commands.confirm(row.planId,commandChecks(preview))
    }
    private suspend fun assertUndoConflict(row:CommandPlanEntity,reason:String) {
        val business=CommandUndoJournal(r,row.bookId).snapshot()
        val history=db.merchantHistoryDao().page(row.bookId,1000)
        val undo=commands.prepareUndo(row.planId);val preview=commands.preview(undo.planId)
        assertFalse(preview.executable)
        assertTrue(preview.conflicts.toString(),preview.conflicts.any{it.field.contains(reason)})
        assertThrows(IllegalStateException::class.java){runBlocking{commands.confirm(undo.planId,commandChecks(preview))}}
        assertEquals(business,CommandUndoJournal(r,row.bookId).snapshot())
        assertEquals(history,db.merchantHistoryDao().page(row.bookId,1000))
        assertEquals("EXECUTED",commands.plan(row.planId).status)
    }
    private fun withoutDraftSyncVersion(rows:Map<String,Pair<String,JsonObject>>)=rows.mapValues{(_,p)->
        if(p.first!="drafts")p else {
            val draft=r.json.decodeFromString<EntryDraft>(p.second.getValue("json").jsonPrimitive.content)
            p.first to JsonObject(p.second+("json" to JsonPrimitive(r.json.encodeToString(EntryDraft.serializer(),draft.copy(merchantSyncVersion=0)))))
        }
    }
    private suspend fun cycle(book:String,steps:List<CommandStep>,verify:suspend (ConfirmationModel,BookSnapshot)->Unit) {
        val before=CommandUndoJournal(r,book).snapshot();val historyBefore=db.merchantHistoryDao().page(book,1000);val row=receive(book,steps)
        val preview=commands.preview(row.planId);assertTrue(preview.executable);assertTrue(preview.steps.all{it.undo.available})
        assertEquals(before,CommandUndoJournal(r,book).snapshot())
        assertEquals(historyBefore,db.merchantHistoryDao().page(book,1000))
        now=now.plusSeconds(2) // Generated timestamps and IDs must not invalidate a real confirmation.
        assertNull(CommandConfirmationComparator.firstDifference(preview,commands.preview(row.planId)))
        commands.confirm(row.planId,commandChecks(preview));verify(preview,snapshot(book))
        val historyExecuted=db.merchantHistoryDao().page(book,1000)
        val originals=historyExecuted.filter{it.cloudPlanId==row.planId&&it.cloudExecutionId==row.localId}
        val executed=CommandUndoJournal(r,book).snapshot();val undoRow=commands.prepareUndo(row.planId);val undo=commands.preview(undoRow.planId)
        assertTrue(undo.conflicts.toString(),undo.executable);assertEquals(executed,CommandUndoJournal(r,book).snapshot())
        assertEquals(historyExecuted,db.merchantHistoryDao().page(book,1000))
        commands.confirm(undoRow.planId,commandChecks(undo))
        val historyUndone=db.merchantHistoryDao().page(book,1000)
        assertEquals(historyExecuted.size+originals.size,historyUndone.size)
        for(original in originals) {
            val retained=historyUndone.single{it.id==original.id}
            val inverse=historyUndone.single{it.undoOfOperationId==original.id}
            assertEquals(MerchantOperationType.CLOUD_UNDO,inverse.type)
            assertEquals(inverse.id,retained.undoneByOperationId);assertEquals(inverse.timestamp,retained.undoneAt)
            assertEquals(original,retained.copy(undoneByOperationId=null,undoneAt=null))
            assertEquals(undoRow.planId,inverse.cloudPlanId)
            assertEquals(original.targetMerchantId,inverse.sourceMerchantId);assertEquals(original.sourceMerchantId,inverse.targetMerchantId)
            assertEquals(original.targetAfter,inverse.sourceBefore);assertEquals(original.targetBefore,inverse.sourceAfter)
            assertEquals(original.sourceAfter,inverse.targetBefore);assertEquals(original.sourceBefore,inverse.targetAfter)
            val impacts=db.merchantHistoryDao().undoImpacts(book,original.id)
            assertEquals(impacts.map{it.copy(operationId=inverse.id,oldMerchantName=it.newMerchantName,newMerchantName=it.oldMerchantName,oldMerchantId=it.newMerchantId,newMerchantId=it.oldMerchantId)},db.merchantHistoryDao().undoImpacts(book,inverse.id))
        }
        fun business(rows:Map<String,Pair<String,JsonObject>>)=rows.filterValues{it.first!="revisions"}.mapValues{(_,p)->p.first to JsonObject(p.second.filterKeys{it!="revision"||p.first!="events"})}
        assertEquals(business(withoutDraftSyncVersion(before)),business(withoutDraftSyncVersion(CommandUndoJournal(r,book).snapshot())))
        db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use{assertFalse(it.moveToFirst())}
        assertEquals("UNDONE",commands.plan(row.planId).status)
    }
    @Test fun preUpgradeJournalsRejectCleanedObjectsButUndoUntouchedRowsAndCheckNewColumns()=runBlocking<Unit> {
        val name="command-undo-v39.db";context.deleteDatabase(name)
        db.close();db=com.yohaku.expensetracker.data.backup.openYohakuDatabase(context,name)
        r=LedgerRepository(db,clock);commands=CommandRepository(r)
        try {
            val (book,bank)=setup();val category=r.saveCategory(book,"Food",EntryKind.EXPENSE,null)
            val untouched=r.saveEntry(book,EntryDraft(accountId=bank,categoryId=category,amount="10",merchant="Unchanged"))
            val cleaned=r.saveEntry(book,EntryDraft(accountId=bank,categoryId=category,amount="10",merchant="cafe"))
            val cleanPlan=execute(book,listOf(step("note","entry.update","""{"targets":{"ids":["$cleaned"]},"set":{"note":"cloud"}}""")))
            val untouchedPlan=execute(book,listOf(step("note","entry.update","""{"targets":{"ids":["$untouched"]},"set":{"note":"cloud"}}""")))
            val templatePlan=execute(book,listOf(step("template","template.save","""{"name":"Coffee","kind":"EXPENSE","category":"$category","account":"$bank","currency":"JPY","merchant":"cafe"}""")))
            val createdPlan=execute(book,listOf(step("entry","entry.create","""{"account":"$bank","category":"$category","amount":{"currency":"JPY","minor":10},"merchant":"CSV"}""")))
            val created=snapshot(book).events.single{it.merchant=="CSV"}.id
            db.csvImportDao().putBatch(CsvBatchEntity(book,"batch","old.csv","hash",1,1,1,0,bank))
            db.csvImportDao().putEvents(listOf(CsvBatchEventEntity(book,created,"batch")))
            val csv=CommandUndoJournal(r,book).snapshot().values.single{it.first=="csv_batch_events"}.second
            val csvChange=UndoRowChange("csv_batch_events",buildJsonObject{put("bookId",book);put("eventId",created)},null,csv)
            suspend fun legacy(plan:CommandPlanEntity,extra:List<UndoRowChange> = emptyList()) {
                val record=readCommandExecution(commands.plan(plan.planId).executionJson!!)
                val undo=record.undo!!.copy(rows=record.undo.rows+extra)
                val json=commandJson.encodeToJsonElement(record.copy(undo=undo)).jsonObject
                val oldUndo=json.getValue("undo").jsonObject
                fun oldRow(value:JsonElement):JsonElement=if(value is JsonObject)JsonObject(value-"sourceMatchKey")else value
                val rows=oldUndo.getValue("rows").jsonArray.map{value->
                    val row=value.jsonObject
                    if(row["table"]==JsonPrimitive("csv_batch_events"))JsonObject(row.mapValues{(key,v)->if(key in setOf("before","after"))oldRow(v)else v})else row
                }
                val old=JsonObject(json+("undo" to JsonObject((oldUndo-"databaseVersion")+("rows" to JsonArray(rows)))))
                db.commandDao().update(commands.plan(plan.planId).copy(executionJson=storeCommandJson(old.toString())))
            }
            legacy(cleanPlan);legacy(untouchedPlan);legacy(templatePlan);legacy(createdPlan,listOf(csvChange))
            // Legacy writes predate automatic linking. The migration, not a read, repairs them.
            r.dao.putMerchant(MerchantEntity(book,"shop","Ｃａｆｅ",active=false))
            db.close()
            android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use {
                restoreFixtureDatabase(it,39,"com.yohaku.expensetracker.data.db.YohakuDatabase/39.json")
            }
            db=com.yohaku.expensetracker.data.backup.openYohakuDatabase(context,name)
            r=LedgerRepository(db,clock);commands=CommandRepository(r)
            assertEquals("shop",r.dao.eventMerchant(book,cleaned)!!.merchantId)
            assertUndoConflict(cleanPlan,"数据已升级整理")
            assertUndoConflict(templatePlan,"数据已升级整理")
            val undo=commands.prepareUndo(untouchedPlan.planId);val preview=commands.preview(undo.planId)
            assertTrue(preview.conflicts.toString(),preview.executable);commands.confirm(undo.planId,commandChecks(preview))
            assertEquals("",r.dao.event(book,untouched)!!.note)
            // A journal produced after cleanup must remain undoable on the same marked object.
            val newPlan=execute(book,listOf(step("note","entry.update","""{"targets":{"ids":["$cleaned"]},"set":{"note":"after upgrade"}}""")))
            val newUndo=commands.prepareUndo(newPlan.planId);val newPreview=commands.preview(newUndo.planId)
            assertTrue(newPreview.conflicts.toString(),newPreview.executable);commands.confirm(newUndo.planId,commandChecks(newPreview))
            assertEquals("cloud",r.dao.event(book,cleaned)!!.note)
            assertEquals("shop",r.dao.eventMerchant(book,cleaned)!!.merchantId)
            val oldCsv=readCommandExecution(commands.plan(createdPlan.planId).executionJson!!).undo!!
            val journal=CommandUndoJournal(r,book)
            assertTrue(journal.conflicts(journal.migratedRecord(oldCsv.copy(databaseVersion=40))).any{it.field=="sourceMatchKey"})
            // Entire-row removal must notice a post-upgrade source key, even though old JSON lacks it.
            db.openHelper.writableDatabase.execSQL("UPDATE csv_batch_events SET sourceMatchKey='later-source' WHERE bookId=? AND eventId=?",arrayOf(book,created))
            assertUndoConflict(createdPlan,"sourceMatchKey")
            db.openHelper.writableDatabase.execSQL("UPDATE csv_batch_events SET sourceMatchKey=NULL WHERE bookId=? AND eventId=?",arrayOf(book,created))
            val csvUndo=commands.prepareUndo(createdPlan.planId);val csvPreview=commands.preview(csvUndo.planId)
            assertTrue(csvPreview.conflicts.toString(),csvPreview.executable);commands.confirm(csvUndo.planId,commandChecks(csvPreview))
            assertNull(r.dao.event(book,created))
            db.openHelper.readableDatabase.query("SELECT * FROM csv_batch_events").use{assertFalse(it.moveToFirst())}
        }finally{db.close();context.deleteDatabase(name)}
    }

    @Test fun budgetExclusionEditsTemplatesExportsAndUndo()=runBlocking<Unit> {
        val (book,bank)=setup();val c=r.saveCategory(book,"食事",EntryKind.EXPENSE,null)
        val e=r.saveEntry(book,EntryDraft(accountId=bank,categoryId=c,amount="1200",date="2026-11-02"))
        cycle(book,listOf(
            step("exclude","entry.update","""{"targets":{"ids":["$e"]},"set":{"budgetExcluded":true}}"""),
            step("note","entry.update","""{"targets":{"ids":["$e"]},"set":{"note":"保持预算开关"}}"""),
            step("template","template.save","""{"name":"旅行","kind":"EXPENSE","category":"$c","account":"$bank","currency":"JPY","amountMinor":200,"budgetExcluded":true}"""),
            step("entry","template.createEntry","""{"template":{"ref":"template","output":"template"}}"""))) {model,s->
            assertTrue(s.visibleEvents.all{it.budgetExcluded});assertTrue(s.templates.single().budgetExcluded)
            assertEquals(0L,s.budgetProjection("2026-11").spentMinor);assertEquals(1400L,s.monthExpense("2026-11"))
            assertEquals(98600L,s.balance(s.accounts.single{it.id==bank}))
            assertTrue(model.net.any{it.type=="entry"&&it.after.jsonObject["budgetExcluded"]==JsonPrimitive(true)})
            val export=r.commandExports(book,RecordScope(mode="account",account=bank))
            assertTrue(export.records!!.records.all{it.budgetExcluded})
            assertEquals(JsonPrimitive(true),export.context.items.single{it.kind=="template"}.values["budgetExcluded"])
        }
        assertFalse(snapshot(book).events.single().budgetExcluded)
        val incomeCategory=r.saveCategory(book,"工资",EntryKind.INCOME,null)
        val income=r.saveEntry(book,EntryDraft(kind=EntryKind.INCOME,accountId=bank,categoryId=incomeCategory,amount="10",budgetExcluded=true))
        assertFalse(snapshot(book).events.single{it.id==income}.budgetExcluded)
        val rejected=receive(book,listOf(step("invalid","entry.update","""{"targets":{"ids":["$income"]},"set":{"budgetExcluded":true}}""")))
        assertThrows(IllegalArgumentException::class.java){runBlocking{commands.preview(rejected.planId)}}
        assertEquals("FAILED",commands.plan(rejected.planId).status)
        assertFalse(snapshot(book).events.single{it.id==income}.budgetExcluded)
    }
    @Test fun accountsCategoriesAndThreeEntries()=runBlocking<Unit> {
        val (book,_)=setup()
        cycle(book,listOf(
            step("wallet","account.save","""{"name":"钱包","type":"CASH","currency":"JPY","openingMinor":10000,"openingDate":"2026-11-01"}"""),
            step("parent","category.save","""{"name":"日常","kind":"EXPENSE"}"""),
            step("food","category.save","""{"name":"午餐","kind":"EXPENSE","icon":null}"""),
            step("nest","category.reparent","""{"category":{"ref":"food","output":"category"},"parent":{"ref":"parent","output":"category"}}"""),
            step("favorite","category.setFavorite","""{"category":{"ref":"food","output":"category"},"favorite":true}"""),
            *Array(3){i->step("meal$i","entry.create","""{"account":{"ref":"wallet","output":"account"},"category":{"ref":"food","output":"category"},"amount":{"currency":"JPY","minor":900},"note":"午餐 $i"}""")},
            step("archive","category.lifecycle","""{"category":{"ref":"food","output":"category"},"operation":"ARCHIVE"}"""),
            step("restore","category.lifecycle","""{"category":{"ref":"food","output":"category"},"operation":"UNARCHIVE"}"""),
            step("disable","account.setActive","""{"target":{"ref":"wallet","output":"account"},"active":false}"""),
            step("enable","account.setActive","""{"target":{"ref":"wallet","output":"account"},"active":true}"""))) {model,s->
            assertEquals(3,s.visibleEvents.size);assertEquals(7300L,s.balance(s.accounts.single{it.name=="钱包"}));assertTrue(s.categories.single{it.name=="午餐"}.favorite)
            assertEquals(3,model.net.count{it.type=="entry"});assertTrue(model.requirements.none{it.kind==ConfirmationKind.RISK})
        }
    }
    @Test fun bookSettingsCreationAndOtherBookLifecycle()=runBlocking<Unit> {
        val (book,_)=setup();val other=r.createBook("旧账本",Currency.JPY,startDate="2026-01-01")
        cycle(book,listOf(
            step("settings","book.update","""{"name":"新名称","startDate":"2026-10-01","budgetMinor":30000,"theme":"objects","language":"ja"}"""),
            step("setup","book.completeSetup","{}"),
            step("newbook","book.create","""{"name":"旅行","currency":"USD","startDate":"2026-11-02","budgetMinor":10000}"""),
            step("archive","book.lifecycle","""{"book":"$other","operation":"ARCHIVE"}"""),
            step("unarchive","book.lifecycle","""{"book":"$other","operation":"UNARCHIVE"}"""),
            step("trash","book.lifecycle","""{"book":"$other","operation":"TRASH"}"""),
            step("restore","book.lifecycle","""{"book":"$other","operation":"RESTORE"}"""))) {m,s->
            assertEquals("新名称",s.book.name);assertEquals("objects",s.book.theme);assertTrue(s.book.initialized)
            assertTrue(m.net.any{it.type=="book"});assertEquals(3,r.books.first().size);assertNull(r.dao.storedBook(other)!!.deletedAt)
        }
    }
    @Test fun merchantRenameAdoptionAndActivationUndoRestoreEveryOwnedRow()=runBlocking<Unit> {
        val (book,bank)=setup();val category=r.saveCategory(book,"食事",EntryKind.EXPENSE,null)
        val shop=r.saveMerchant(book,"Cafe")
        val linked=r.saveEntry(book,EntryDraft(accountId=bank,categoryId=category,amount="100",date="2026-11-02",merchant="旧文字",merchantId=shop))
        val legacy=r.saveEntry(book,EntryDraft(accountId=bank,categoryId=category,amount="200",date="2026-11-02",merchant="ＣＡＦＥ",capturedPlace=CapturedPlace(35.0,139.0,5.0)))
        val adopted=r.saveEntry(book,EntryDraft(accountId=bank,categoryId=category,amount="300",date="2026-11-02",merchant="NEXT"))
        r.saveTemplate(book,TemplateInput("常用",EntryKind.EXPENSE,category,bank,Currency.JPY,merchant="Cafe",merchantId=shop))
        r.saveRecurrence(book,RecurrenceInput("每月",bank,category,Money(Currency.JPY,100),null,false,RecurrenceFrequency.MONTHLY,"2026-11-02",merchant="Cafe",merchantId=shop))
        r.saveDraft(book,EntryDraft(merchant="Cafe",merchantId=shop))
        val original=CommandUndoJournal(r,book).snapshot()
        cycle(book,listOf(step("rename","merchant.save","""{"merchant":"$shop","name":"Next"}"""))) {_,s->
            assertTrue(s.events.all{it.merchant=="Next"});assertEquals(setOf(linked,legacy,adopted),s.eventMerchants.map{it.eventId}.toSet())
            assertTrue(s.eventPlaces.isEmpty());assertEquals("Next",s.templates.single().merchant);assertEquals("Next",s.recurrences.single().merchant);assertEquals("Next",s.draft!!.merchant)
        }
        assertEquals(withoutDraftSyncVersion(original),withoutDraftSyncVersion(CommandUndoJournal(r,book).snapshot())) // All business fields restore; the synchronization generation advances.
        cycle(book,listOf(step("stop","merchant.setActive","""{"target":"$shop","active":false}"""))) {_,s->
            assertFalse(s.merchants.single().active);assertEquals("Cafe",s.events.single{it.id==linked}.merchant)
            assertEquals(shop,s.eventMerchants.single{it.eventId==legacy}.merchantId)
            assertEquals("NEXT",s.events.single{it.id==adopted}.merchant)
        }
        assertEquals(withoutDraftSyncVersion(original),withoutDraftSyncVersion(CommandUndoJournal(r,book).snapshot()))
        val other=r.saveMerchant(book,"Other")
        val before=CommandUndoJournal(r,book).snapshot()
        val row=receive(book,listOf(step("duplicate","merchant.save","""{"merchant":"$shop","name":" ＯＴＨＥＲ "}""")))
        assertTrue(assertThrows(IllegalArgumentException::class.java){runBlocking{commands.preview(row.planId)}}.message!!.contains("应用内合并"))
        assertEquals(before,CommandUndoJournal(r,book).snapshot());assertNotNull(r.dao.merchant(book,other))
    }
    @Test fun merchantProfileAndRenameUseSharedScopesAndWriteCloudHistoryOnlyOnCommit()=runBlocking<Unit> {
        val (book,bank)=setup();val category=r.saveCategory(book,"食事",EntryKind.EXPENSE,null)
        val shop=r.saveMerchant(book,"Cafe")
        val linked=r.saveEntry(book,EntryDraft(accountId=bank,categoryId=category,amount="100",date="2026-11-02",merchantId=shop))
        val raw=r.saveEntry(book,EntryDraft(accountId=bank,categoryId=category,amount="100",date="2026-11-02",merchant="Next",capturedPlace=CapturedPlace(35.0,139.0,5.0)))
        r.dao.putEvent(r.dao.event(book,linked)!!.copy(merchant="legacy display"))
        val before=snapshot(book)
        val profiles=receive(book,listOf(step("note","merchant.save","""{"merchant":"$shop","name":"Cafe","note":"memo"}"""),
            step("stop","merchant.setActive","""{"target":"$shop","active":false}""")))
        val historyBefore=db.merchantHistoryDao().page(book,100)
        val profilePreview=commands.preview(profiles.planId)
        assertEquals(historyBefore,db.merchantHistoryDao().page(book,100))
        commands.confirm(profiles.planId,commandChecks(profilePreview))
        assertEquals(before.events,snapshot(book).events);assertEquals(before.eventPlaces,snapshot(book).eventPlaces)
        assertEquals(before.eventMerchants,snapshot(book).eventMerchants)
        val profileHistory=db.merchantHistoryDao().page(book,100).filter{it.source==MerchantOperationSource.CLOUD}
        assertEquals(setOf(MerchantOperationType.CHANGE_NOTE,MerchantOperationType.CHANGE_ACTIVE),profileHistory.map{it.type}.toSet())
        assertTrue(profileHistory.all{it.cloudPlanId==profiles.planId&&it.cloudExecutionId==profiles.localId})
        assertEquals(setOf("note","stop"),profileHistory.map{it.cloudCommandId}.toSet())
        val row=receive(book,listOf(step("rename","merchant.save","""{"merchant":"$shop","name":"Next"}""")))
        val preview=commands.preview(row.planId)
        val counts=preview.steps.single().objects.single{it.type=="merchantImpact"}.after.jsonObject
        assertEquals(JsonPrimitive(1),counts["newlyLinkedRecords"]);assertEquals(JsonPrimitive(1),counts["pendingPlaces"])
        assertEquals(JsonPrimitive(1),counts["renamedRecords"])
        assertEquals(3,db.merchantHistoryDao().page(book,100).size)
        // Same count, different pending coordinates must still invalidate cloud confirmation.
        val capture=before.eventPlaces.single()
        r.dao.putEventPlace(capture.copy(latitude=36.0))
        assertNotNull(CommandConfirmationComparator.firstDifference(preview,commands.preview(row.planId)))
        r.dao.putEventPlace(capture)
        val restored=commands.preview(row.planId)
        assertNull(CommandConfirmationComparator.firstDifference(preview,restored))
        commands.confirm(row.planId,commandChecks(restored))
        val after=snapshot(book)
        assertTrue(after.events.all{it.merchant=="Next"});assertEquals(shop,after.eventMerchants.single{it.eventId==raw}.merchantId)
        assertEquals(before.eventPlaces,after.eventPlaces);assertEquals(before.eventSaveTimes,after.eventSaveTimes)
        assertEquals(4,db.merchantHistoryDao().page(book,100).size)
        assertEquals("legacy display",db.merchantHistoryDao().forObject(book,MerchantObjectType.EVENT,linked,100).single().let {
            db.merchantHistoryDao().impacts(book,it.id,100).single{impact->impact.objectType==MerchantObjectType.EVENT&&impact.objectId==linked}.oldMerchantName
        })
    }

    @Test fun merchantLocationAndFriend()=runBlocking<Unit> {
        val (book,_)=setup()
        cycle(book,listOf(
            step("shop","merchant.save","""{"name":"喫茶店","note":"车站旁"}"""),
            step("place","merchantLocation.save","""{"merchant":{"ref":"shop","output":"merchant"},"name":"东口","latitude":35.68,"longitude":139.76}"""),
            step("move","merchantLocation.save","""{"merchant":{"ref":"shop","output":"merchant"},"location":{"ref":"place","output":"merchantLocation"},"name":"西口","latitude":35.69,"longitude":139.75,"radiusMeters":80}"""),
            step("friend","friend.save","""{"name":"小林"}"""),
            step("rename","friend.save","""{"friend":{"ref":"friend","output":"friend"},"name":"小林同学"}"""),
            step("stopShop","merchant.setActive","""{"target":{"ref":"shop","output":"merchant"},"active":false}"""),
            step("stopFriend","friend.setActive","""{"target":{"ref":"friend","output":"friend"},"active":false}"""))) {m,s->
            assertEquals("西口",s.merchantLocations.single().name);assertFalse(s.merchants.single().active);assertFalse(s.friends.single().active)
            assertTrue(m.net.any{it.type=="merchantLocation"&&it.after.jsonObject["radiusMeters"]==JsonPrimitive(80)})
        }
    }
    @Test fun merchantUndoRejectsNewUniqueNameAndDuplicateName()=runBlocking<Unit> {
        val (book,bank)=setup();val category=r.saveCategory(book,"Food",EntryKind.EXPENSE,null)
        val event=r.saveEntry(book,EntryDraft(accountId=bank,categoryId=category,amount="100",merchant="X"))
        val edit=execute(book,listOf(step("rename","entry.update","""{"targets":{"ids":["$event"]},"set":{"merchant":"Y"}}""")))
        r.saveMerchant(book,"ｘ",active=false)
        assertUndoConflict(edit,"未关联的唯一同名支出")
        val a=r.saveMerchant(book,"A")
        val rename=execute(book,listOf(step("rename","merchant.save","""{"merchant":"$a","name":"B"}""")))
        r.saveMerchant(book,"Ａ")
        assertUndoConflict(rename,"规范化重名")
    }
    @Test fun merchantUndoIgnoresUnchangedLegacyViolationsButRejectsRestoringRepairedOnes()=runBlocking<Unit> {
        val (book,bank)=setup();val category=r.saveCategory(book,"Food",EntryKind.EXPENSE,null)
        val a=r.saveMerchant(book,"A");val duplicate=r.saveMerchant(book,"Other")
        r.dao.putMerchant(r.dao.merchant(book,duplicate)!!.copy(name="ａ"))
        val event=r.saveEntry(book,EntryDraft(accountId=bank,categoryId=category,amount="100",merchantId=a))
        r.dao.putEvent(r.dao.event(book,event)!!.copy(merchant="legacy display"))
        val raw=r.saveEntry(book,EntryDraft(accountId=bank,categoryId=category,amount="100",merchant="raw"))
        val unique=r.saveMerchant(book,"Unique",active=false)
        r.dao.putEvent(r.dao.event(book,raw)!!.copy(merchant="unique"))
        val refund=r.saveRefund(book,RefundInput(event,Money(Currency.JPY,10),bank,Money(Currency.JPY,10),Money(Currency.JPY,10),"2026-11-02",true))
        r.dao.putEvent(r.dao.event(book,refund)!!.copy(merchant="old refund"))
        r.saveTemplate(book,TemplateInput("Broken",EntryKind.EXPENSE,category,bank,Currency.JPY,merchantId=unique))
        db.openHelper.writableDatabase.execSQL("UPDATE entry_templates SET merchantId='missing' WHERE bookId=?",arrayOf(book))
        // Include a merchant business row so the full consistency checker actually runs.
        cycle(book,listOf(step("stop","merchant.setActive","""{"target":"$a","active":false}"""))) {_,s->assertFalse(s.merchants.single{it.id==a}.active)}
        // Repairing old data is allowed; undo must not reintroduce its now-absent defects.
        val repair=execute(book,listOf(step("rename","merchant.save","""{"merchant":"$a","name":"Fixed"}""")))
        assertUndoConflict(repair,"记录名称与关联商户不一致")
        val preview=commands.preview(commands.prepareUndo(repair.planId).planId)
        assertTrue(preview.conflicts.any{it.field.contains("退款名称与原消费不一致")})
    }
    @Test fun merchantUndoKeepsLiveReferenceAndLaterModificationGuards()=runBlocking<Unit> {
        val (book,bank)=setup();val category=r.saveCategory(book,"Food",EntryKind.EXPENSE,null)
        val create=execute(book,listOf(step("shop","merchant.save","""{"name":"Shop"}""")))
        val shop=snapshot(book).merchants.single().id
        r.saveEntry(book,EntryDraft(accountId=bank,categoryId=category,amount="100",merchantId=shop))
        assertUndoConflict(create,"merchantId")
        val note=execute(book,listOf(step("note","merchant.save","""{"merchant":"$shop","name":"Shop","note":"cloud"}""")))
        val undo=commands.prepareUndo(note.planId);val preview=commands.preview(undo.planId)
        assertTrue(preview.executable)
        r.saveMerchant(book,"Shop",note="later",id=shop)
        val history=db.merchantHistoryDao().page(book,1000)
        assertThrows(CommandRepreviewRequired::class.java){runBlocking{commands.confirm(undo.planId,commandChecks(preview))}}
        assertEquals(history,db.merchantHistoryDao().page(book,1000))
        assertEquals("later",r.dao.merchant(book,shop)!!.note)
        assertUndoConflict(note,"note")
    }
    @Test fun merchantUndoRejectsMissingTemplateAndRecurrenceReferences()=runBlocking<Unit> {
        val (book,bank)=setup();val category=r.saveCategory(book,"Food",EntryKind.EXPENSE,null)
        val shop=r.saveMerchant(book,"Shop")
        r.saveTemplate(book,TemplateInput("Daily",EntryKind.EXPENSE,category,bank,Currency.JPY,merchantId=shop))
        r.saveRecurrence(book,RecurrenceInput("Daily",bank,category,Money(Currency.JPY,100),null,false,RecurrenceFrequency.MONTHLY,"2026-11-02",merchantId=shop))
        val s=snapshot(book)
        val edit=execute(book,listOf(
            step("template","template.save","""{"template":"${s.templates.single().id}","name":"Daily","kind":"EXPENSE","category":"$category","account":"$bank","currency":"JPY","merchant":"Elsewhere"}"""),
            step("rule","recurrence.save","""{"recurrence":"${s.recurrences.single().id}","name":"Daily","account":"$bank","category":"$category","frequency":"MONTHLY","anchorDate":"2026-11-02","amount":{"currency":"JPY","minor":100},"merchant":"Elsewhere"}""")))
        r.deleteMerchant(book,shop)
        assertUndoConflict(edit,"失效的商户引用")
        val preview=commands.preview(commands.prepareUndo(edit.planId).planId)
        assertEquals(setOf("entry_templates","recurrences"),preview.conflicts.filter{it.field.contains("失效的商户引用")}.map{it.type}.toSet())
    }
    @Test fun merchantUndoHistoryRollsBackWithFailedCommit()=runBlocking<Unit> {
        val (book,_)=setup()
        val original=execute(book,listOf(step("shop","merchant.save","""{"name":"Shop"}""")))
        val undo=commands.prepareUndo(original.planId);val preview=commands.preview(undo.planId)
        assertTrue(preview.executable)
        val before=CommandUndoJournal(r,book).snapshot();val history=db.merchantHistoryDao().page(book,1000)
        // Fail after markUndone/inverse history insertion, exercising the real outer transaction.
        val sql=db.openHelper.writableDatabase
        sql.execSQL("CREATE TEMP TRIGGER fail_undo_commit BEFORE UPDATE OF status ON command_plans WHEN NEW.status='UNDONE' BEGIN SELECT RAISE(ABORT,'test undo commit failure'); END")
        try {
            assertThrows(android.database.sqlite.SQLiteConstraintException::class.java){runBlocking{commands.confirm(undo.planId,commandChecks(preview))}}
        } finally { sql.execSQL("DROP TRIGGER fail_undo_commit") }
        assertEquals(before,CommandUndoJournal(r,book).snapshot());assertEquals(history,db.merchantHistoryDao().page(book,1000))
        assertEquals("EXECUTED",commands.plan(original.planId).status)
        val retry=commands.preview(undo.planId);assertTrue(retry.executable)
        commands.confirm(undo.planId,commandChecks(retry))
        assertTrue(snapshot(book).merchants.isEmpty())
        assertEquals(2,db.merchantHistoryDao().page(book,1000).size)
    }
    @Test fun transferRefundForeignActualAndFullEdit()=runBlocking<Unit> {
        val (book,bank)=setup();val category=r.saveCategory(book,"食事",EntryKind.EXPENSE,null)
        val cash=r.saveAccount(book,"现金",AccountType.CASH,Currency.JPY,Money(Currency.JPY,0),"2026-11-01")
        val purchase=r.saveEntry(book,EntryDraft(accountId=bank,categoryId=category,amount="5000",date="2026-11-02"))
        cycle(book,listOf(
            step("transfer","entry.create","""{"kind":"TRANSFER","account":"$bank","toAccount":"$cash","amount":{"currency":"JPY","minor":3000},"toAmount":{"currency":"JPY","minor":2900},"fromConfirmed":true,"toConfirmed":true,"feeAmount":{"currency":"JPY","minor":100},"feePosition":"INCLUDED_FROM"}"""),
            step("refund","entry.create","""{"kind":"REFUND","originalEvent":"$purchase","amount":{"currency":"JPY","minor":1000}}"""),
            step("foreign","entry.create","""{"account":"$bank","category":"$category","amount":{"currency":"USD","minor":1000},"accountAmount":{"currency":"JPY","minor":1500}}"""),
            step("actual","entry.confirmActual","""{"targets":{"select":{"currency":"USD","kind":"EXPENSE"}},"set":{"accountAmount":{"currency":"JPY","minor":1550},"settlementConfirmed":true}}"""),
            step("edit","entry.update","""{"targets":{"ids":["$purchase"]},"set":{"amount":{"currency":"JPY","minor":5500},"note":"修改金额","date":"2026-11-01"}}"""))) {m,s->
            assertEquals(100L,s.expenseComponents.single().amountMinor);assertEquals(2900L,s.balance(s.accounts.single{it.id==cash}))
            assertEquals(1000L,s.visibleEvents.single{it.kind=="REFUND"}.amountMinor);assertEquals(1550L,s.settlements.single{it.eventId==s.events.single{e->e.currency=="USD"}.id}.accountMinor)
            assertEquals("修改金额",s.events.single{it.id==purchase}.note);assertTrue(m.net.any{it.type=="entryDetails"})
        }
    }
    @Test fun templateSaveReorderAndCompileToOrdinaryEntry()=runBlocking<Unit> {
        val (book,bank)=setup();val c=r.saveCategory(book,"工资",EntryKind.INCOME,null)
        cycle(book,listOf(
            step("first","template.save","""{"name":"工资","kind":"INCOME","category":"$c","account":"$bank","currency":"JPY","amountMinor":200000}"""),
            step("second","template.save","""{"name":"奖金","kind":"INCOME","category":"$c","account":"$bank","currency":"JPY","amountMinor":50000}"""),
            step("move","template.move","""{"template":{"ref":"second","output":"template"},"direction":-1}"""),
            step("income","template.createEntry","""{"template":{"ref":"first","output":"template"}}"""))) {m,s->
            assertEquals("奖金",s.templates.first().name);assertEquals(200000L,s.visibleEvents.single().amountMinor);assertEquals("entry.create",m.steps.last().command)
        }
    }
    @Test fun recordTrashAndRestoreKeepIntermediateEffectsAndUndo()=runBlocking<Unit> {
        val (book,bank)=setup();val c=r.saveCategory(book,"食事",EntryKind.EXPENSE,null)
        val e=r.saveEntry(book,EntryDraft(accountId=bank,categoryId=c,amount="1200",date="2026-11-02"))
        r.saveRefund(book,RefundInput(e,Money(Currency.JPY,200),bank,Money(Currency.JPY,200),Money(Currency.JPY,200),"2026-11-02",true))
        cycle(book,listOf(step("trash","entry.lifecycle","""{"entry":"$e","operation":"TRASH"}"""),step("restore","entry.lifecycle","""{"entry":"$e","operation":"RESTORE"}"""))) {m,s->
            assertEquals(2,s.visibleEvents.size);assertEquals(2,m.steps.first().objects.count{it.type=="entry"&&it.after.jsonObject["deletedAt"]!=JsonNull})
            assertTrue(m.steps.last().objects.filter{it.type=="entry"}.all{it.after.jsonObject["deletedAt"]==JsonNull})
        }
    }
    @Test fun permanentOperationsRejectTheWholePlanWithAlternatives()=runBlocking<Unit> {
        val (book,bank)=setup();val original=snapshot(book)
        val forbidden=permanentAlternatives.keys.map{step("forbidden",it,"{}")}+listOf(
            step("forbidden","category.lifecycle","""{"category":"unused","operation":"MERGE_DELETE"}"""),
            step("forbidden","category.lifecycle","""{"category":"unused","operation":"DELETE_EMPTY"}"""),
            step("forbidden","book.lifecycle","""{"operation":"PURGE"}"""),
            step("forbidden","entry.lifecycle","""{"entry":"unused","operation":"EMPTY"}"""),
            step("forbidden","activity.lifecycle","""{"activity":"unused","operation":"PURGE"}"""),
            step("forbidden","activityTreat.lifecycle","""{"treat":"unused","operation":"PURGE"}"""),
            step("forbidden","occurrence.lifecycle","""{"occurrence":"unused","operation":"DELETE"}"""))
        for(bad in forbidden) {
            val row=receive(book,listOf(step("before","account.save","""{"account":"$bank","name":"不可保存","type":"BANK","currency":"JPY","openingMinor":100000,"openingDate":"2026-11-01"}"""),bad))
            assertEquals(bad.command,"FAILED",row.status);assertTrue(row.error.orEmpty().contains(" / "));assertEquals(original,snapshot(book))
        }
    }
    @Test fun batchFiveHundredNotesOnLargeLedgerLogsPerformance()=runBlocking<Unit> {
        val (book,bank)=setup()
        val rows=(0 until 3700).map{i->"支出,${LocalDate.of(2023,8,17).plusDays(i*1167L/3700)},餐饮,${100+i%5000},架空の店 ${i%300}"}
        val bytes=("账簿,日期,类别,金额,备注\n"+rows.joinToString("\n")).toByteArray()
        val document=CsvParser.read(bytes);val settings=CsvParser.guess(document).copy(accountId=bank)
        r.importCsv(book,"fictional.csv",CsvParser.hash(bytes),document,settings,emptyMap(),r.csvPreview(book,document,settings,emptyMap()))
        assertEquals(3700,snapshot(book).events.size)
        val targets=snapshot(book).visibleEvents.take(500).map{it.id}
        assertEquals(500,targets.size)
        val row=receive(book,listOf(CommandStep("notes","entry.update",1,buildJsonObject{put("targets",encoded(EntryTargets(ids=targets)));put("set",buildJsonObject{put("note","批量核对")})})))
        lateinit var model:ConfirmationModel
        val previewMs=measureNanoTime{model=commands.preview(row.planId)}/1_000_000
        val confirmMs=measureNanoTime{commands.confirm(row.planId,commandChecks(model))}/1_000_000
        Log.i("CommandStage3","records=${snapshot(book).events.size} targets=500 previewMs=$previewMs confirmMs=$confirmMs")
        assertEquals(500,snapshot(book).events.count{it.note=="批量核对"})
    }

    @Test fun merchantResolutionAcrossCommandsAndRefundUpdatesUndoTogether()=runBlocking<Unit> {
        val (book,bank)=setup();val category=r.saveCategory(book,"Food",EntryKind.EXPENSE,null)
        val income=r.saveCategory(book,"Income",EntryKind.INCOME,null)
        val cash=r.saveAccount(book,"Cash",AccountType.CASH,Currency.JPY,Money(Currency.JPY,0),"2026-11-01")
        val a=r.saveMerchant(book,"A");val b=r.saveMerchant(book,"Ｃａｆｅ",active=false)
        val purchase=r.saveEntry(book,EntryDraft(accountId=bank,categoryId=category,amount="100",date="2026-11-02",merchant="A"))
        val refund=r.saveRefund(book,RefundInput(purchase,Money(Currency.JPY,20),bank,Money(Currency.JPY,20),Money(Currency.JPY,20),"2026-11-02",true))
        val trashed=r.saveRefund(book,RefundInput(purchase,Money(Currency.JPY,5),bank,Money(Currency.JPY,5),Money(Currency.JPY,5),"2026-11-02",false))
        r.deleteEntry(book,trashed)
        cycle(book,listOf(
            step("move","entry.update","""{"targets":{"ids":["$purchase"]},"set":{"merchant":"cafe"}}"""),
            step("expense","entry.create","""{"account":"$bank","category":"$category","amount":{"currency":"JPY","minor":10},"merchant":" cafe "}"""),
            step("income","entry.create","""{"kind":"INCOME","account":"$bank","category":"$income","amount":{"currency":"JPY","minor":10},"merchant":"cafe"}"""),
            step("linkedIncome","entry.create","""{"kind":"INCOME","account":"$bank","category":"$income","amount":{"currency":"JPY","minor":10},"merchantId":"$b"}"""),
            step("transfer","entry.create","""{"kind":"TRANSFER","account":"$bank","toAccount":"$cash","amount":{"currency":"JPY","minor":10},"toAmount":{"currency":"JPY","minor":10},"merchant":"cafe"}"""),
            step("template","template.save","""{"name":"Daily","kind":"EXPENSE","category":"$category","account":"$bank","currency":"JPY","amountMinor":10,"merchant":"cafe"}"""),
            step("fromTemplate","template.createEntry","""{"template":{"ref":"template","output":"template"}}"""),
            step("rule","recurrence.save","""{"name":"cafe","account":"$bank","category":"$category","frequency":"MONTHLY","anchorDate":"2026-11-02","amount":{"currency":"JPY","minor":10}}"""))) {_,s->
            assertEquals(b,s.eventMerchants.single{it.eventId==purchase}.merchantId)
            assertTrue(s.events.filter{it.id in setOf(refund,trashed)}.all{it.merchant=="Ｃａｆｅ"})
            assertEquals("id:$b",s.merchantKey(s.events.single{it.id==refund}));assertTrue(s.merchantEvents(a).isEmpty())
            assertEquals(80L,s.netExpense(s.events.single{it.id==purchase}))
            assertEquals(b,s.templates.single().merchantId);assertEquals(b,s.recurrences.single().merchantId)
            assertEquals(4,s.eventMerchants.size) // purchase, new expense, explicit income, template payment
            assertTrue(s.events.filter{it.kind=="TRANSFER"||it.kind=="INCOME"&&it.merchant=="cafe"}.all{e->s.eventMerchants.none{it.eventId==e.id}})
        }
        val before=CommandUndoJournal(r,book).snapshot()
        val bad=receive(book,listOf(step("bad","entry.create","""{"account":"$bank","category":"$category","amount":{"currency":"JPY","minor":10},"merchantId":"$b","merchant":"A"}""")))
        val error=assertThrows(IllegalStateException::class.java){runBlocking{commands.preview(bad.planId)}}
        assertTrue(error.message!!.contains("ID 与名称不一致"));assertEquals(before,CommandUndoJournal(r,book).snapshot())
    }
    @Test fun cloudMerchantEditRespectsRetainedDraftIntentAndUndoSyncVersion()=runBlocking<Unit> {
        val (book,bank)=setup();val category=r.saveCategory(book,"Food",EntryKind.EXPENSE,null)
        val a=r.saveMerchant(book,"A");val b=r.saveMerchant(book,"B")
        val event=r.saveEntry(book,EntryDraft(accountId=bank,categoryId=category,amount="100",merchantId=a))
        val draft=snapshot(book).editingEntryDraft(snapshot(book).events.single()).copy(note="local note")
        r.saveDraft(book,draft)
        val moved=execute(book,listOf(step("move","entry.update","""{"targets":{"ids":["$event"]},"set":{"merchantId":"$b"}}""")))
        assertEquals(draft,snapshot(book).draft)
        assertEquals(b,r.previewEntry(book,draft).change.merchantId)
        assertNull(r.previewEntry(book,draft).change.merchantConflict)
        assertNotNull(r.previewEntry(book,draft.copy(merchantId=null,merchant="My choice")).change.merchantConflict)
        val undo=commands.prepareUndo(moved.planId);val preview=commands.preview(undo.planId)
        assertTrue(preview.executable);commands.confirm(undo.planId,commandChecks(preview))
        assertEquals(a,r.previewEntry(book,draft).change.merchantId)
        // New-entry draft synchronization participates in cloud undo, but never rewinds its generation.
        val fresh=draft.copy(id=null,merchantBaseline=null)
        r.saveDraft(book,fresh)
        val rename=execute(book,listOf(step("rename","merchant.save","""{"merchant":"$a","name":"A renamed"}""")))
        val updated=snapshot(book).draft!!;assertEquals("A renamed",updated.merchant)
        val undoRename=commands.prepareUndo(rename.planId);val undoPreview=commands.preview(undoRename.planId)
        assertTrue(undoPreview.conflicts.toString(),undoPreview.executable)
        commands.confirm(undoRename.planId,commandChecks(undoPreview))
        val restored=snapshot(book).draft!!
        assertEquals("A",restored.merchant);assertTrue(restored.merchantSyncVersion>updated.merchantSyncVersion)
        assertFalse(r.saveDraft(book,updated));assertFalse(r.saveDraft(book,fresh))
    }

}
