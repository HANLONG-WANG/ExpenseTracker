package com.yohaku.expensetracker.data.command

import androidx.room.*
import com.yohaku.expensetracker.data.db.BookEntity
import com.yohaku.expensetracker.data.repository.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.time.YearMonth

@Serializable
@Entity(tableName="monthly_plans",foreignKeys=[ForeignKey(entity=BookEntity::class,parentColumns=["id"],childColumns=["bookId"],onDelete=ForeignKey.CASCADE)])
data class MonthlyPlanEntity(@PrimaryKey val bookId:String,val text:String)

/** Submission identity and upload snapshot survive reconnects and are included in full backups. */
@Serializable
@Entity(tableName="monthly_reviews",primaryKeys=["bookId","month"],indices=[Index(value=["localId"],unique=true)],foreignKeys=[ForeignKey(entity=BookEntity::class,parentColumns=["id"],childColumns=["bookId"],onDelete=ForeignKey.CASCADE)])
data class MonthlyReviewEntity(val bookId:String,val month:String,val localId:String,val snapshotJson:String,val submittedAt:Long,val planId:String?=null)

@Dao interface MonthlyDao {
    @Query("SELECT * FROM monthly_plans WHERE bookId=:bookId") suspend fun plan(bookId:String):MonthlyPlanEntity?
    @Query("SELECT * FROM monthly_plans WHERE bookId=:bookId") fun observePlan(bookId:String):Flow<MonthlyPlanEntity?>
    @Upsert suspend fun savePlan(plan:MonthlyPlanEntity)
    @Query("SELECT * FROM monthly_reviews WHERE bookId=:bookId ORDER BY month DESC") fun history(bookId:String):Flow<List<MonthlyReviewEntity>>
    @Query("SELECT * FROM monthly_reviews WHERE bookId=:bookId AND month=:month") suspend fun review(bookId:String,month:String):MonthlyReviewEntity?
    @Query("SELECT * FROM monthly_reviews WHERE localId=:localId") suspend fun submission(localId:String):MonthlyReviewEntity?
    @Query("SELECT * FROM monthly_reviews WHERE planId=:planId") fun forPlan(planId:String):Flow<MonthlyReviewEntity?>
    // Only CloudRepository.discard calls this after checking no remote task was created.
    @Query("DELETE FROM monthly_reviews WHERE localId=:localId AND planId IS NULL") suspend fun discardUnsubmitted(localId:String)
    @Insert suspend fun insert(review:MonthlyReviewEntity)
    @Update suspend fun update(review:MonthlyReviewEntity)
}

suspend fun LedgerRepository.saveMonthlyPlan(bookId:String,text:String)=db.withTransaction {
    val book=requireNotNull(dao.book(bookId)){"账本不存在 / 帳簿がありません"}
    require(book.initialized&&!book.archived){"请先完成设置或取消归档 / 設定を完了するかアーカイブを解除してください"}
    require(text.length<=20000){"月计划不能超过20000字 / 月の計画は20000文字以内です"}
    db.monthlyDao().savePlan(MonthlyPlanEntity(bookId,text))
}

@Serializable data class MonthlyCategory(val id:String?,val name:String,val kind:String,val amountMinor:Long)
@Serializable data class MonthlyBudget(val categoryId:String?,val name:String,val limitMinor:Long?,val spentMinor:Long,val remainingMinor:Long?)
@Serializable data class MonthlyAccount(val id:String,val name:String,val currency:String,val openingDate:String,val beforeMinor:Long?,val afterMinor:Long?,val changeMinor:Long?)
@Serializable data class MonthlySummary(val format:String="yohaku.monthly-review",val version:Int=1,val book_id:String,val month:String,val currency:String,
    val monthlyPlan:String,val incomeMinor:Long,val expenseMinor:Long,val categories:List<MonthlyCategory>,val budgets:List<MonthlyBudget>,val accounts:List<MonthlyAccount>,
    val provisionalExpenseCount:Int,val basis:String="Income and net consumption use book currency. Confirmed refunds reduce their original purchase month; confirmed expense components use their own dates. Category rows are direct categories, not parent rollups; uncategorized expense components are separate. Budgets exclude independent activities and include inherited limits. Account balances use confirmed postings by posting date in each account currency; null means the account had not opened. Change includes opening funds for accounts opened during the month. This is a snapshot of current known facts, not a frozen month-end audit.")
@Serializable data class MonthlyReviewBundle(val summary:MonthlySummary,val context:LedgerContextExport)

fun BookSnapshot.monthlySummary(month:String,plan:String):MonthlySummary {
    val period=YearMonth.parse(month);val first=period.atDay(1).toString();val last=period.atEndOfMonth().toString()
    val events=visibleEvents.filter{it.date.startsWith(month)&&it.kind in setOf("INCOME","EXPENSE")}
    val categories=events.groupBy{it.kind to it.categoryId}.map{(key,rows)->MonthlyCategory(key.second,key.second?.let{path(it)}?:"未分类 / 未分類",key.first,rows.sumOf{if(it.kind=="EXPENSE")netExpense(it)else it.baseMinor})}.toMutableList()
    val fees=visibleComponents().filter{it.date.startsWith(month)}.sumOf{it.baseMinor}
    if(fees!=0L)categories+=MonthlyCategory(null,"手续费等支出组成 / 手数料などの支出内訳","COMPONENT",fees)
    val expenseIds=events.filter{it.kind=="EXPENSE"}.map{it.id}.toSet()
    val budgetIds=listOf<String?>(null)+this.categories.filter{budgetLimit(month,it.id)!=null}.map{it.id}.sorted()
    val budgets=budgetIds.map{id->val b=budgetProjection(month,id);MonthlyBudget(id,id?.let{path(it)}?:"月预算 / 月予算",b.limitMinor,b.spentMinor,b.remainingMinor)}
    val accounts=this.accounts.sortedBy{it.id}.map{a->
        val rows=postings.filter{it.accountId==a.id}
        val before=if(a.openingDate<first)rows.filter{it.date<first}.fold(a.openingMinor){sum,p->Math.addExact(sum,p.deltaMinor)}else null
        val after=if(a.openingDate<=last)rows.filter{it.date<=last}.fold(a.openingMinor){sum,p->Math.addExact(sum,p.deltaMinor)}else null
        MonthlyAccount(a.id,a.name,a.currency,a.openingDate,before,after,after?.let{Math.subtractExact(it,before?:0)})
    }
    return MonthlySummary(book_id=book.id,month=month,currency=book.currency,monthlyPlan=plan,incomeMinor=events.filter{it.kind=="INCOME"}.sumOf{it.baseMinor},expenseMinor=monthExpense(month),categories=categories.sortedWith(compareBy<MonthlyCategory>{it.kind}.thenBy{it.id.orEmpty()}),budgets=budgets,accounts=accounts,provisionalExpenseCount=settlements.count{!it.confirmed&&it.eventId in expenseIds})
}

suspend fun LedgerRepository.monthlyReviewBundle(bookId:String,month:String,preferences:AppPreferences?=null):MonthlyReviewBundle=db.withTransaction {
    require(YearMonth.parse(month)<YearMonth.now(clock)){"请选择已结束的月份 / 終了した月を選んでください"}
    val s=financialReviewState(bookId).ledger
    require(month>=s.book.startDate.take(7)){"所选月份早于账本开始月份 / 帳簿の開始月より前です"}
    MonthlyReviewBundle(s.monthlySummary(month,db.monthlyDao().plan(bookId)?.text.orEmpty()),commandExports(bookId,RecordScope(),preferences).context)
}

@Serializable private data class MonthlyPlanInput(val text:String)
internal class MonthlyPlanAdapter:LedgerAdapter() {
    override val capability=CommandCapability("book.monthlyPlan.save",1,patchSchema(descriptorSchema(MonthlyPlanInput.serializer().descriptor),mapOf("text" to buildJsonObject{put("type","string");put("maxLength",20000)})),emptyMap(),
        "text required, exact full replacement; empty string clears content without deleting its row. No null/default inference.",
        "Current task book exists, initialized and not archived; text <= 20000 characters. Same validation as local editor.",listOf(ConfirmationKind.INFORMATION),listOf("book identity and complete original/new plan text; absence versus existing row"),
        "Save the current book's natural-language monthly plan. One reusable current text per book, applied immediately; no effective month or deferred scheduling. If a request also sets a future budget, use budget.save.month for the budget and explain in message that this text changes now and can describe the future intention. If the user explicitly forbids updating text before that month, do not claim to schedule it: explain the limitation and offer the supported budget change. Submissions retain their own immutable plan snapshot.",buildJsonObject{put("text","每月餐饮控制在三万日元内，优先储蓄。")},
        "Restore the exact original text/row from the execution snapshot. Any subsequent edit blocks the entire undo; an execution-owned new row may be removed only by local undo.")
    override fun validate(input:JsonObject){require(commandJson.decodeFromJsonElement<MonthlyPlanInput>(input).text.length<=20000)}
    override suspend fun execute(step:CommandStep,run:CommandRun):StepEffect {
        run.ledger.saveMonthlyPlan(run.bookId,commandJson.decodeFromJsonElement<MonthlyPlanInput>(step.input).text)
        val book=commandLedgerRow("books",encoded(requireNotNull(run.ledger.dao.book(run.bookId))).jsonObject)
        return StepEffect(step.id,step.command,listOf(run.bookId),listOf(ObjectEffect("book",run.bookId,book,book)))
    }
}
