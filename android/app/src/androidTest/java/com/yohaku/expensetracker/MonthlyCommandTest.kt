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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.*
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MonthlyCommandTest {
    private lateinit var db:YohakuDatabase
    private lateinit var r:LedgerRepository
    private lateinit var commands:CommandRepository
    @Before fun open(){db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,YohakuDatabase::class.java).build();r=LedgerRepository(db,Clock.fixed(Instant.parse("2026-11-01T03:00:00Z"),ZoneId.of("Asia/Tokyo")));commands=CommandRepository(r)}
    @After fun close(){db.close()}
    private suspend fun book():String=r.createBook("日常",Currency.JPY,startDate="2026-09-01",budgetMinor=10000).also{r.updateBook(r.dao.book(it)!!.copy(initialized=true))}
    private suspend fun plan(book:String,steps:List<CommandStep>,message:String="月度计划")=CommandPlan("yohaku.command-plan",1,UUID.randomUUID().toString(),book,commands.registry.catalogDigest,r.commandExports(book,RecordScope()).context.contextDigest,message,steps=steps)
    @Test fun monthlyPlanPreviewConflictExecuteAndExactUndo()=runBlocking<Unit> {
        val b=book();r.saveMonthlyPlan(b,"  餐饮不超过三万。\n留出旅行预算。  ")
        val before=db.monthlyDao().plan(b)
        val p=plan(b,listOf(CommandStep("monthly","book.monthlyPlan.save",1,buildJsonObject{put("text","先储蓄，再安排支出。")})))
        val row=commands.receive(commandClaim(p),"https://example.test",p.context_digest)
        val preview=commands.preview(row.planId)
        assertTrue(preview.executable);assertEquals(before,db.monthlyDao().plan(b))
        assertEquals("  餐饮不超过三万。\n留出旅行预算。  ",preview.steps.single().objects.single{it.type=="monthlyPlan"}.before.jsonObject.getValue("text").jsonPrimitive.content)
        r.saveMonthlyPlan(b,"用户后来编辑")
        assertTrue(runCatching{commands.confirm(row.planId,commandChecks(preview))}.exceptionOrNull() is CommandRepreviewRequired)
        assertEquals("用户后来编辑",db.monthlyDao().plan(b)!!.text)
        r.saveMonthlyPlan(b,before!!.text)
        val ready=commands.preview(row.planId);commands.confirm(row.planId,commandChecks(ready))
        assertEquals("先储蓄，再安排支出。",db.monthlyDao().plan(b)!!.text)
        val undo=commands.prepareUndo(row.planId)
        val inverse=commands.preview(undo.planId);assertTrue(inverse.executable)
        commands.confirm(undo.planId,commandChecks(inverse))
        assertEquals(before,db.monthlyDao().plan(b));assertEquals("UNDONE",commands.plan(row.planId).status)
    }
    @Test fun summaryAnswerAndMonthlyHistoryWithOptionalSuggestions()=runBlocking<Unit> {
        val b=book();val other=book()
        val bank=r.saveAccount(b,"银行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,20000),"2026-09-01")
        val expense=r.saveCategory(b,"餐饮",EntryKind.EXPENSE,null)
        val income=r.saveCategory(b,"工资",EntryKind.INCOME,null)
        r.saveEntry(b,EntryDraft(kind=EntryKind.EXPENSE,accountId=bank,categoryId=expense,originalCurrency=Currency.JPY,amount="1200",date="2026-10-10"))
        r.saveEntry(b,EntryDraft(kind=EntryKind.INCOME,accountId=bank,categoryId=income,originalCurrency=Currency.JPY,amount="5000",date="2026-10-20"))
        check(r.commitBudget(r.previewBudget(b,"2026-10",expense,Money(Currency.JPY,3000))))
        r.saveMonthlyPlan(b,"餐饮在预算以内。")
        val bundle=r.monthlyReviewBundle(b,"2026-10")
        val summary=bundle.summary
        assertEquals(5000L,summary.incomeMinor);assertEquals(1200L,summary.expenseMinor)
        assertEquals(1200L,summary.categories.single{it.id==expense}.amountMinor)
        assertEquals(1800L,summary.budgets.single{it.categoryId==expense}.remainingMinor)
        assertEquals(10000L,summary.budgets.single{it.categoryId==null}.limitMinor)
        assertEquals(20000L,summary.accounts.single().beforeMinor);assertEquals(23800L,summary.accounts.single().afterMinor);assertEquals(3800L,summary.accounts.single().changeMinor)
        assertEquals("餐饮在预算以内。",bundle.context.items.single{it.kind=="monthly_plan"}.values.getValue("text").jsonPrimitive.content)
        val answer=plan(b,emptyList(),"本月收入大于消费。")
        val answerRow=commands.receive(commandClaim(answer,resultId="answer"),"https://example.test",answer.context_digest)
        assertEquals("ANSWER",answerRow.status);assertNull(answerRow.confirmationJson)
        assertEquals("本月收入大于消费。",readCommandDocument<CommandPlan>(commands.history(b).first().first().planJson).message)
        val suggestions=listOf(CommandStep("budget","budget.save",1,buildJsonObject{put("month","2026-11");put("amount",buildJsonObject{put("currency","JPY");put("minor",12000)})}))
        val p=plan(b,suggestions,"支出保持在预算内，下月可留出更多储蓄。")
        val local="monthly-submission"
        db.monthlyDao().insert(MonthlyReviewEntity(b,"2026-10",local,commandJson.encodeToString(bundle),r.clock.millis()))
        val row=commands.receive(commandClaim(p,resultId="monthly",localId=local).copy(purpose="monthly-review"),"https://example.test",p.context_digest)
        assertEquals("PENDING",row.status);assertNull(row.confirmationJson)
        assertEquals(row.planId,db.monthlyDao().review(b,"2026-10")!!.planId);assertTrue(db.monthlyDao().history(other).first().isEmpty())
        r.saveMonthlyPlan(b,"后来更新的月计划")
        assertEquals("餐饮在预算以内。",commandJson.decodeFromString<MonthlyReviewBundle>(db.monthlyDao().review(b,"2026-10")!!.snapshotJson).summary.monthlyPlan)
        val preview=commands.preview(row.planId);assertTrue(preview.executable)
        commands.confirm(row.planId,commandChecks(preview));assertEquals("EXECUTED",commands.plan(row.planId).status)
        val undo=commands.prepareUndo(row.planId);val inverse=commands.preview(undo.planId);commands.confirm(undo.planId,commandChecks(inverse))
        assertEquals("UNDONE",commands.plan(db.monthlyDao().review(b,"2026-10")!!.planId!!).status)
        val onlyComment=plan(other,emptyList(),"这个月没有消费记录。")
        val otherBundle=r.monthlyReviewBundle(other,"2026-10")
        db.monthlyDao().insert(MonthlyReviewEntity(other,"2026-10","comment-only",commandJson.encodeToString(otherBundle),r.clock.millis()))
        val comment=commands.receive(commandClaim(onlyComment,resultId="comment",localId="comment-only").copy(purpose="monthly-review"),"https://example.test",onlyComment.context_digest)
        assertEquals("ANSWER",comment.status);assertEquals(comment.planId,db.monthlyDao().history(other).first().single().planId)
    }
}
