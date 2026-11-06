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
    private suspend fun cycle(book:String,steps:List<CommandStep>,verify:suspend (ConfirmationModel,BookSnapshot)->Unit) {
        val before=CommandUndoJournal(r,book).snapshot();val row=receive(book,steps)
        val preview=commands.preview(row.planId);assertTrue(preview.executable);assertTrue(preview.steps.all{it.undo.available})
        assertEquals(before,CommandUndoJournal(r,book).snapshot())
        now=now.plusSeconds(2) // Generated timestamps and IDs must not invalidate a real confirmation.
        assertNull(CommandConfirmationComparator.firstDifference(preview,commands.preview(row.planId)))
        commands.confirm(row.planId,commandChecks(preview));verify(preview,snapshot(book))
        val executed=CommandUndoJournal(r,book).snapshot();val undoRow=commands.prepareUndo(row.planId);val undo=commands.preview(undoRow.planId)
        assertTrue(undo.executable);assertEquals(executed,CommandUndoJournal(r,book).snapshot())
        commands.confirm(undoRow.planId,commandChecks(undo))
        fun business(rows:Map<String,Pair<String,JsonObject>>)=rows.filterValues{it.first!="revisions"}.mapValues{(_,p)->p.first to JsonObject(p.second.filterKeys{it!="revision"||p.first!="events"})}
        assertEquals(business(before),business(CommandUndoJournal(r,book).snapshot()))
        db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use{assertFalse(it.moveToFirst())}
        assertEquals("UNDONE",commands.plan(row.planId).status)
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
}
