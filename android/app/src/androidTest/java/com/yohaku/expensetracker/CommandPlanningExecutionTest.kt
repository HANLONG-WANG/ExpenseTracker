package com.yohaku.expensetracker

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.data.command.*
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.*
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CommandPlanningExecutionTest {
    private lateinit var db:YohakuDatabase
    private lateinit var r:LedgerRepository
    private lateinit var commands:CommandRepository
    private var now=Instant.parse("2026-11-02T03:00:00Z")
    private val clock=object:Clock(){override fun getZone()=ZoneId.of("Asia/Tokyo");override fun withZone(zone:ZoneId):Clock=Clock.fixed(now,zone);override fun instant()=now}
    @Before fun open(){db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,YohakuDatabase::class.java).build();r=LedgerRepository(db,clock);commands=CommandRepository(r)}
    @After fun close(){db.close()}
    private fun step(id:String,name:String,input:String)=CommandStep(id,name,1,commandJson.parseToJsonElement(input).jsonObject)
    private fun ref(step:String,output:String)="""{"ref":"$step","output":"$output"}"""
    private suspend fun setup():Triple<String,String,String>{val b=r.createBook("日常",Currency.JPY,startDate="2026-11-01");val a=r.saveAccount(b,"银行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,300000),"2026-11-01");val c=r.saveCategory(b,"生活",EntryKind.EXPENSE,null);return Triple(b,a,c)}
    private suspend fun snapshot(book:String)=r.snapshot(requireNotNull(r.dao.book(book))).first()
    private suspend fun receive(book:String,steps:List<CommandStep>):CommandPlanEntity {
        val p=CommandPlan("yohaku.command-plan",1,UUID.randomUUID().toString(),book,commands.registry.catalogDigest,r.commandExports(book,RecordScope()).context.contextDigest,message="预算与活动安排",steps=steps)
        return commands.receive(commandClaim(p),"https://example.test",p.context_digest)
    }
    private suspend fun cycle(book:String,steps:List<CommandStep>,verify:suspend (ConfirmationModel,BookSnapshot)->Unit) {
        val original=CommandUndoJournal(r,book).snapshot();val row=receive(book,steps)
        val preview=commands.preview(row.planId);assertTrue(preview.executable);assertTrue(preview.steps.all{it.undo.available})
        assertEquals(original,CommandUndoJournal(r,book).snapshot())
        now=now.plusSeconds(2)
        assertNull(CommandConfirmationComparator.firstDifference(preview,commands.preview(row.planId)))
        commands.confirm(row.planId,commandChecks(preview));verify(preview,snapshot(book))
        val executed=CommandUndoJournal(r,book).snapshot();val inverse=commands.prepareUndo(row.planId);val undo=commands.preview(inverse.planId)
        assertTrue(undo.executable);assertEquals(executed,CommandUndoJournal(r,book).snapshot())
        commands.confirm(inverse.planId,commandChecks(undo))
        assertEquals(original,CommandUndoJournal(r,book).snapshot());assertEquals("UNDONE",commands.plan(row.planId).status)
        db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use{assertFalse(it.moveToFirst())}
    }
    @Test fun monthlyCategoryAndBookBudgets()=runBlocking<Unit> {
        val (b,_,c)=setup();check(r.commitBudget(r.previewBudget(b,"2026-11",null,Money(Currency.JPY,90000))))
        cycle(b,listOf(
            step("month","budget.save","""{"month":"2026-11","amount":{"currency":"JPY","minor":120000}}"""),
            step("category","budget.save","""{"month":"2026-11","category":"$c","amount":{"currency":"JPY","minor":30000}}"""),
            step("clear","budget.save","""{"month":"2026-12","category":"$c","amount":null}"""),
            step("book","budget.saveBook","""{"month":"2027-01","amount":{"currency":"JPY","minor":130000},"name":"新年日常"}"""))) {m,s->
            assertEquals(120000L,s.budgetLimit("2026-11"));assertEquals(30000L,s.budgetLimit("2026-11",c));assertNull(s.budgetLimit("2026-12",c));assertEquals(130000L,s.budgetLimit("2027-01"));assertEquals("新年日常",s.book.name);assertEquals("magazine",s.book.theme);assertEquals("zh",s.book.language)
            val setting=m.steps.first().objects.single{it.type=="budgetSetting"};assertEquals(90000L,setting.before.jsonObject.getValue("amountMinor").jsonPrimitive.long);assertEquals(120000L,setting.after.jsonObject.getValue("amountMinor").jsonPrimitive.long)
        }
    }
    @Test fun activityReserveTreatEndingAndVisibility()=runBlocking<Unit> {
        val (b,_,c)=setup()
        cycle(b,listOf(
            step("mode","activity.setMode","""{"enabled":true}"""),
            step("activity","activity.save","""{"name":"京都旅行","start":"2026-11-02","end":"2026-11-05","budget":{"currency":"JPY","minor":50000},"categoryBudgets":[{"category":"$c","amount":{"currency":"JPY","minor":20000}}]}"""),
            step("current","activity.setCurrent","""{"activity":${ref("activity","activity")}}"""),
            step("friend","friend.save","""{"name":"小林"}"""),
            step("participant","activityParticipant.add","""{"activity":${ref("activity","activity")},"friend":${ref("friend","friend")}}"""),
            step("reserve","activityReserve.save","""{"activity":${ref("activity","activity")},"name":"旅馆","date":"2026-11-02","amount":{"currency":"JPY","minor":12000}}"""),
            step("release","activityReserve.release","""{"reserve":${ref("reserve","reserve")},"amount":{"currency":"JPY","minor":2000}}"""),
            step("treat","activityTreat.save","""{"activity":${ref("activity","activity")},"payer":${ref("friend","friend")},"name":"晚餐","date":"2026-11-02","total":{"currency":"JPY","minor":6000},"base":{"currency":"JPY","minor":6000},"shares":[{"friend":null},{"friend":${ref("friend","friend")}}]}"""),
            step("hideTreat","activityTreat.lifecycle","""{"treat":${ref("treat","treat")},"operation":"TRASH"}"""),
            step("restoreTreat","activityTreat.lifecycle","""{"treat":${ref("treat","treat")},"operation":"RESTORE"}"""),
            step("end","activity.end","""{"activity":${ref("activity","activity")}}"""),
            step("trash","activity.lifecycle","""{"activity":${ref("activity","activity")},"operation":"TRASH"}"""),
            step("restore","activity.lifecycle","""{"activity":${ref("activity","activity")},"operation":"RESTORE"}"""))) {m,s->
            val mode=m.steps.single{it.stepId=="mode"}.objects.single{it.type=="activityMode"}
            assertEquals(JsonPrimitive(false),mode.before.jsonObject["activityMode"])
            assertEquals(JsonPrimitive(true),mode.after.jsonObject["activityMode"])
            val a=s.activities.single();assertTrue(a.ended);assertNull(a.deletedAt);assertNull(s.features!!.activeActivityId)
            assertEquals(2000L,s.reserves.single().releasedMinor);assertEquals(0L,s.activityHeld(a.id));assertFalse(s.reserves.single().active)
            assertEquals(3000L,s.friendBalances(a.id).single().theyGive);assertTrue(s.events.isEmpty())
            assertNull(r.commandExports(b,RecordScope()).records)
            val selected=r.commandExports(b,RecordScope(mode="dates",from="2026-11-01",through="2026-11-05")).records!!
            assertEquals(s.treats.single().id,selected.activityTreats.single().id)
            val reserved=m.steps.single{it.stepId=="reserve"}.objects.single{it.type=="activityBalance"}
            assertEquals(50000L,reserved.before.jsonObject.getValue("availableMinor").jsonPrimitive.long);assertEquals(38000L,reserved.after.jsonObject.getValue("availableMinor").jsonPrimitive.long)
        }
    }
    @Test fun recurrencePaymentCancellationAndBatchLinkConsumeOnlyMatchingDraft()=runBlocking<Unit> {
        val (b,a,c)=setup()
        val old=r.saveRecurrence(b,RecurrenceInput("旧订阅",a,c,Money(Currency.JPY,1000),null,false,RecurrenceFrequency.WEEKLY,"2026-11-02"))
        val pending=r.dao.occurrences(b).filter{it.recurrenceId==old}.sortedBy{it.scheduledDate}
        val first=r.saveEntry(b,EntryDraft(accountId=a,categoryId=c,amount="1000",date="2026-11-02"))
        val second=r.saveEntry(b,EntryDraft(accountId=a,categoryId=c,amount="1000",date="2026-11-02"))
        val paymentDraft=snapshot(b).occurrenceDraft(pending[2],r.dao.recurrence(b,old)!!).copy(date="2026-11-02",settlementDate="2026-11-02",note="尚未提交的支付草稿",mediaInitialized=true,attachmentIds=listOf("payment-proof"),receiptFileIds=listOf("payment-photo"))
        // Database-only local media fixture: this command changes associations, never file bytes.
        for((id,mime) in listOf("payment-proof" to "application/pdf","payment-photo" to "image/png")) {
            r.dao.putFile(FileBlobEntity(b,id,id,"支付凭证",mime,1,"fixture"));r.dao.stageMedia(DraftMediaEntity(b,id,paymentDraft.mediaSession))
        }
        r.saveDraft(b,paymentDraft)
        cycle(b,listOf(
            step("rule","recurrence.save","""{"name":"房租","account":"$a","category":"$c","frequency":"WEEKLY","anchorDate":"2026-11-02","amount":{"currency":"JPY","minor":80000},"estimated":false}"""),
            step("today","occurrence.resolve","""{"recurrence":${ref("rule","recurrence")},"date":"2026-11-02"}"""),
            step("paid","occurrence.pay","""{"occurrence":${ref("today","occurrence")}}"""),
            step("next","occurrence.resolve","""{"recurrence":${ref("rule","recurrence")},"date":"2026-11-09"}"""),
            step("cancel","occurrence.lifecycle","""{"occurrence":${ref("next","occurrence")},"operation":"CANCEL"}"""),
            step("restore","occurrence.lifecycle","""{"occurrence":${ref("next","occurrence")},"operation":"RESTORE"}"""),
            step("cancelAgain","occurrence.lifecycle","""{"occurrence":${ref("next","occurrence")},"operation":"CANCEL"}"""),
            step("links","occurrence.link","""{"links":[{"occurrence":"${pending[0].id}","entry":"$first"},{"occurrence":"${pending[1].id}","entry":"$second"}]}"""),
            step("payExisting","occurrence.pay","""{"occurrence":"${pending[2].id}"}"""),
            step("unlink","occurrence.unlink","""{"occurrence":"${pending[1].id}"}"""),
            step("stop","recurrence.setActive","""{"recurrence":"$old","active":false}"""),
            step("start","recurrence.setActive","""{"recurrence":"$old","active":true}"""))) {m,s->
            assertEquals(4,s.visibleEvents.size);assertEquals(217000L,s.balance(s.accounts.single()))
            val rule=s.recurrences.single{it.name=="房租"};assertEquals("COMPLETED",s.occurrences.single{it.recurrenceId==rule.id&&it.scheduledDate=="2026-11-02"}.status)
            assertEquals("CANCELLED",s.occurrences.single{it.recurrenceId==rule.id&&it.scheduledDate=="2026-11-09"}.status)
            assertNull(s.draft);assertEquals(first,s.occurrences.single{it.id==pending[0].id}.eventId);assertNull(s.occurrences.single{it.id==pending[1].id}.eventId)
            assertTrue(m.steps.single{it.stepId=="links"}.objects.none{it.type=="drafts"})
            assertTrue(m.steps.single{it.stepId=="payExisting"}.objects.any{it.type=="drafts"&&it.after==JsonNull})
            val paid=s.visibleEvents.single{it.note=="尚未提交的支付草稿"}
            assertEquals("payment-proof",s.attachments.single{it.eventId==paid.id}.fileId)
            assertEquals("payment-photo",s.receiptImages.single{image->s.receipts.any{it.id==image.receiptId&&it.eventId==paid.id}}.fileId)
            assertTrue(s.stagedMedia.isEmpty())
        }
    }
    @Test fun expectedIncomeExistingAndNewReceiptAndCancellation()=runBlocking<Unit> {
        val (b,a,_)=setup();val incomeCategory=r.saveCategory(b,"工资",EntryKind.INCOME,null)
        val receipt=r.saveEntry(b,EntryDraft(kind=EntryKind.INCOME,accountId=a,categoryId=incomeCategory,amount="200000",date="2026-11-02"))
        cycle(b,listOf(
            step("income","expectedIncome.save","""{"name":"本月工资","amount":{"currency":"JPY","minor":210000},"date":"2026-11-02","account":"$a"}"""),
            step("linked","expectedIncome.arrive","""{"income":${ref("income","expectedIncome")},"entry":"$receipt"}"""),
            step("bonus","expectedIncome.save","""{"name":"奖金","amount":{"currency":"JPY","minor":5000},"date":"2026-11-02","account":"$a"}"""),
            step("newReceipt","expectedIncome.arrive","""{"income":${ref("bonus","expectedIncome")},"set":{"category":"$incomeCategory","amount":{"currency":"JPY","minor":6000}}}"""),
            step("cancelled","expectedIncome.save","""{"name":"取消的补贴","amount":{"currency":"USD","minor":2000},"date":"2026-11-05","account":"$a"}"""),
            step("cancel","expectedIncome.cancel","""{"income":${ref("cancelled","expectedIncome")}}"""))) {m,s->
            val defaults=m.steps.single{it.stepId=="newReceipt"}.objects.single{it.type=="entryDefaults"}
            assertEquals(JsonPrimitive("@newReceipt/entry/"),defaults.after.jsonObject["eventId"])
            assertEquals(2,s.visibleEvents.size);assertEquals(506000L,s.balance(s.accounts.single()))
            assertEquals(receipt,s.expectedIncomes.single{it.name=="本月工资"}.eventId);assertEquals(210000L,s.expectedIncomes.single{it.name=="本月工资"}.amountMinor)
            assertEquals("ARRIVED",s.expectedIncomes.single{it.name=="奖金"}.status);assertEquals("CANCELLED",s.expectedIncomes.single{it.name=="取消的补贴"}.status);assertNull(s.expectedIncomes.single{it.name=="取消的补贴"}.accountMinor)
        }
    }
    @Test fun categoryMigrationAcrossEntriesBudgetsActivitiesAndScheduleHistory()=runBlocking<Unit> {
        val (b,a,source)=setup();val destination=r.saveCategory(b,"日常支出",EntryKind.EXPENSE,null)
        val e1=r.saveEntry(b,EntryDraft(accountId=a,categoryId=source,amount="800",date="2026-11-02"))
        val e2=r.saveEntry(b,EntryDraft(accountId=a,categoryId=source,amount="900",date="2026-11-02"))
        r.deleteEntry(b,e2)
        check(r.commitBudget(r.previewBudget(b,"2026-11",source,Money(Currency.JPY,50000))))
        r.setActivityMode(b,true);val review=r.previewActivity(b,ActivityInput("散步","2026-11-01","2026-11-10",Money(Currency.JPY,10000),mapOf(source to Money(Currency.JPY,10000))))
        check(r.commitActivity(review));val activity=review.activity.id
        val rule=r.saveRecurrence(b,RecurrenceInput("通勤",a,source,Money(Currency.JPY,2000),null,false,RecurrenceFrequency.WEEKLY,"2026-11-02"))
        val occurrence=r.dao.occurrences(b).first{it.recurrenceId==rule};r.cancelOccurrence(b,occurrence.id)
        cycle(b,listOf(
            step("entries","entry.update","""{"targets":{"ids":["$e1","$e2"]},"set":{"category":"$destination"}}"""),
            step("budget","budget.save","""{"month":"2026-11","category":"$destination","amount":{"currency":"JPY","minor":50000}}"""),
            step("oldBudget","budget.save","""{"month":"2026-11","category":"$source","amount":null}"""),
            step("activity","activity.save","""{"activity":"$activity","name":"散步","start":"2026-11-01","end":"2026-11-10","budget":{"currency":"JPY","minor":10000},"categoryBudgets":[{"category":"$destination","amount":{"currency":"JPY","minor":10000}}]}"""),
            step("rule","recurrence.save","""{"recurrence":"$rule","name":"通勤","account":"$a","category":"$destination","frequency":"WEEKLY","anchorDate":"2026-11-02","amount":{"currency":"JPY","minor":2000},"estimated":false}"""),
            step("history","occurrence.update","""{"occurrences":["${occurrence.id}"],"category":"$destination"}"""),
            step("archive","category.lifecycle","""{"category":"$source","operation":"ARCHIVE"}"""))) {_,s->
            assertTrue(s.events.all{it.categoryId==destination});assertNotNull(s.events.single{it.id==e2}.deletedAt)
            assertNull(s.budgetLimit("2026-11",source));assertEquals(50000L,s.budgetLimit("2026-11",destination))
            assertEquals(destination,s.activityBudgets.single().categoryId);assertTrue(s.occurrences.all{it.categoryId==destination});assertFalse(s.categories.single{it.id==source}.active)
        }
    }
    @Test fun changedActivityAssociationBlocksWholeUndoAndChangedBudgetBlocksConfirmation()=runBlocking<Unit> {
        val (b,_,c)=setup();r.setActivityMode(b,true)
        val a=r.saveActivity(b,"已有活动","2026-11-01","2026-11-10",Money(Currency.JPY,10000))
        val row=receive(b,listOf(step("edit","activity.save","""{"activity":"$a","name":"已有活动","start":"2026-11-01","end":"2026-11-10","budget":{"currency":"JPY","minor":20000}}"""),step("budget","budget.save","""{"month":"2026-11","category":"$c","amount":{"currency":"JPY","minor":50000}}""")))
        val preview=commands.preview(row.planId)
        check(r.commitBudget(r.previewBudget(b,"2026-11",c,Money(Currency.JPY,30000))))
        val changed=CommandUndoJournal(r,b).snapshot()
        assertTrue(runCatching{commands.confirm(row.planId,commandChecks(preview))}.exceptionOrNull() is CommandRepreviewRequired)
        assertEquals(changed,CommandUndoJournal(r,b).snapshot())
        val fresh=commands.preview(row.planId);commands.confirm(row.planId,commandChecks(fresh))
        val friend=r.saveFriend(b,"后来参加者");r.addParticipant(b,a,friend)
        val final=CommandUndoJournal(r,b).snapshot();val undo=commands.prepareUndo(row.planId);val blocked=commands.preview(undo.planId)
        assertFalse(blocked.executable);assertTrue(blocked.conflicts.isNotEmpty());assertTrue(runCatching{commands.confirm(undo.planId,commandChecks(blocked))}.isFailure)
        assertEquals(final,CommandUndoJournal(r,b).snapshot())
    }
}
