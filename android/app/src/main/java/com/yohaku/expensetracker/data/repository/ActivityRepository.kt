package com.yohaku.expensetracker.data.repository

import androidx.room.withTransaction
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.domain.model.*
import kotlinx.coroutines.flow.first
import java.time.LocalDate

data class ActivityInput(val name:String,val start:String,val end:String,val budget:Money?,val categoryBudgets:Map<String,Money> = emptyMap(),val excludeMonthly:Boolean=false,val ended:Boolean=false,val id:String?=null)
data class ActivityReview(val snapshot:BookSnapshot,val activity:ActivityEntity,val budgets:List<ActivityBudgetEntity>,val withdrawn:List<OccurrenceEntity>,val months:List<Pair<BudgetProjection,BudgetProjection>>)
data class ReserveInput(val activityId:String,val name:String,val date:String,val amount:Money,val accountId:String?=null,val accountAmount:Money?=null,val occurrenceId:String?=null,val id:String?=null)
data class ReserveReview(val snapshot:BookSnapshot,val reserve:ActivityReserveEntity,val months:List<Pair<BudgetProjection,BudgetProjection>>)

suspend fun LedgerRepository.previewActivity(bookId:String,input:ActivityInput):ActivityReview=db.withTransaction {
    val snapshot=snapshot(requireNotNull(dao.book(bookId))).first()
    snapshot.prepareActivity(input,input.id?:id())
}

internal fun BookSnapshot.prepareActivity(input:ActivityInput,resolvedId:String):ActivityReview {
    require(features?.activityMode==true)
    require(input.name.isNotBlank()){ "请输入活动名称 / シーン名を入力してください" }
    require(LocalDate.parse(input.start)<=LocalDate.parse(input.end)){ "结束日期不能早于开始日期 / 終了日は開始日以降にしてください" }
    require(input.budget==null||input.budget.currency.name==book.currency&&input.budget.minor>=0)
    val old=input.id?.let{activities.find{a->a.id==it}?:error("活动不属于本账本 / 別の帳簿のシーンです")}
    require(old?.deletedAt==null){"活动已删除 / シーンは削除済みです"}
    require(old?.ended!=true||input.ended){"结束的活动不能开启新一轮 / 終了済みシーンは再開できません"}
    val activity=ActivityEntity(book.id,resolvedId,input.name.trim(),input.start,input.end,input.budget?.minor,input.excludeMonthly,input.ended)
    val budgets=input.categoryBudgets.map{(categoryId,amount)->
        require(categories.any{it.id==categoryId&&it.kind==EntryKind.EXPENSE.name&&(categoryEnabled(it.id)||activityBudgets.any{b->b.activityId==old?.id&&b.categoryId==it.id})})
        require(amount.currency.name==book.currency&&amount.minor>=0)
        ActivityBudgetEntity(book.id,activity.id,categoryId,amount.minor)
    }
    val withdrawn=occurrences.filter{activity.ended&&it.activityId==activity.id&&it.status=="PENDING"&&it.scheduledDate !in activity.startDate..activity.endDate}.map{it.copy(status="WITHDRAWN")}
    val withdrawals=withdrawn.associateBy{it.id}
    val next=copy(occurrences=occurrences.map{withdrawals[it.id]?:it},activities=activities.filter{it.id!=activity.id}+activity,
        reserves=reserves.map{if(input.ended&&it.activityId==activity.id)it.copy(active=false)else it})
    val months=(visibleEvents.filter{e->eventActivities.any{it.eventId==e.id&&it.activityId==activity.id}}.map{it.date.take(7)}+
        reserves.filter{it.activityId==activity.id}.map{it.date.take(7)}+occurrences.filter{it.activityId==activity.id}.map{it.scheduledDate.take(7)}).distinct().sorted()
    return ActivityReview(this,activity,budgets,withdrawn,months.map{budgetProjection(it) to next.budgetProjection(it)})
}
suspend fun LedgerRepository.commitActivity(review:ActivityReview):Boolean=db.withTransaction {
    val book=dao.book(review.activity.bookId)!!
    if(snapshot(book).first().reviewState()!=review.snapshot.reviewState())return@withTransaction false
    dao.putActivity(review.activity)
    dao.clearActivityBudgets(book.id,review.activity.id);review.budgets.forEach{dao.putActivityBudget(it)}
    if(review.activity.ended) {
        review.withdrawn.forEach{dao.putOccurrence(it)}
        dao.reserves(book.id).filter{it.activityId==review.activity.id}.forEach{dao.putReserve(it.copy(active=false))}
        dao.features(book.id)?.takeIf{it.activeActivityId==review.activity.id}?.let{dao.putFeatures(it.copy(activeActivityId=null))}
    }
    true
}
suspend fun LedgerRepository.previewReserve(bookId:String,input:ReserveInput):ReserveReview=db.withTransaction {
    val snapshot=snapshot(requireNotNull(dao.book(bookId))).first()
    snapshot.prepareReserve(input,input.id?:id())
}

internal fun BookSnapshot.prepareReserve(input:ReserveInput,resolvedId:String):ReserveReview {
    val activity=activities.find{it.id==input.activityId}?:error("活动不存在 / シーンがありません")
    require(features?.activityMode==true&&!activity.ended&&activity.deletedAt==null)
    require(input.name.isNotBlank());LocalDate.parse(input.date)
    require(input.amount.currency.name==book.currency&&input.amount.minor>0)
    val account=input.accountId?.let{accounts.find{a->a.id==it}?:error("账户不属于本账本 / 別の帳簿の口座です")}
    require((account==null)==(input.accountAmount==null))
    require(account==null||account.active&&input.accountAmount!!.currency.name==account.currency&&input.accountAmount.minor>0)
    require(account==null||!AccountType.valueOf(account.type).debt){"预留请选择资金账户 / 確保には資金口座を選んでください"}
    require(account?.currency!=book.currency||input.accountAmount==input.amount){"同币种的预留金额必须一致 / 同じ通貨の確保額は一致させてください"}
    val old=input.id?.let{reserves.find{r->r.id==it}?:error("预留不存在 / 確保がありません")}
    val occurrence=input.occurrenceId?.let{occurrences.find{o->o.id==it}?:error("周期实例不存在 / 定期予定がありません")}
    require(occurrence==null||occurrence.activityId==activity.id&&(occurrence.status=="PENDING"||old?.occurrenceId==occurrence.id))
    require(old==null||old.activityId==activity.id&&old.active)
    require(reserves.none{it.id!=old?.id&&it.occurrenceId!=null&&it.occurrenceId==input.occurrenceId}){"这个周期已有预留 / この定期予定は確保済みです"}
    val reserve=ActivityReserveEntity(book.id,resolvedId,activity.id,input.name.trim(),input.date,input.amount.minor,account?.id,input.accountAmount?.minor,account?.currency,input.occurrenceId,old?.releasedMinor?:0)
    val next=copy(reserves=reserves.filter{it.id!=reserve.id}+reserve)
    require(next.reserveRemaining(reserve)>=0){"预留不能小于已使用及释放金额 / 確保額は使用・解放済み額以上にしてください"}
    val months=listOfNotNull(old?.date,reserve.date).map{it.take(7)}.distinct().sorted()
    return ReserveReview(this,reserve,months.map{budgetProjection(it) to next.budgetProjection(it)})
}
suspend fun LedgerRepository.previewRelease(bookId:String,reserveId:String,amount:Money):ReserveReview=db.withTransaction {
    val book=dao.book(bookId)!!;val snapshot=snapshot(book).first();val old=dao.reserve(bookId,reserveId)!!
    require(amount.currency.name==book.currency&&amount.minor>0&&amount.minor<=snapshot.reserveRemaining(old))
    val reserve=old.copy(releasedMinor=Math.addExact(old.releasedMinor,amount.minor))
    val next=snapshot.copy(reserves=snapshot.reserves.filter{it.id!=reserve.id}+reserve)
    val month=reserve.date.take(7)
    ReserveReview(snapshot,reserve,listOf(snapshot.budgetProjection(month) to next.budgetProjection(month)))
}
suspend fun LedgerRepository.commitReserve(review:ReserveReview):Boolean=db.withTransaction {
    val book=dao.book(review.reserve.bookId)!!
    if(snapshot(book).first().reviewState()!=review.snapshot.reviewState())return@withTransaction false
    dao.putReserve(review.reserve);true
}
