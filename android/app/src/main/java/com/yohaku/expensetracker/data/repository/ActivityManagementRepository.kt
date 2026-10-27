package com.yohaku.expensetracker.data.repository

import androidx.room.withTransaction
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.domain.model.*
import kotlinx.coroutines.flow.first
import java.time.YearMonth

/** Historical membership remains readable; deleted scenes contribute to daily spending. */
fun BookSnapshot.currentActivityId(eventId:String):String?=activityByEvent[eventId]?.takeIf{id->activities.any{it.id==id&&it.deletedAt==null}}
val BookSnapshot.availableActivities get()=activities.filter{it.deletedAt==null}
class ActivityDeletionReview internal constructor(internal val before:BookSnapshot,internal val after:BookSnapshot,val activity:ActivityEntity,
    val recordCount:Int,val treatCount:Int,val releasedMinor:Long,val cancelledCount:Int,val draftConverted:Boolean,
    val months:List<Pair<BudgetProjection,BudgetProjection>>,val categoryBudgets:List<CategoryProjectionChange>)

suspend fun LedgerRepository.previewActivityDeletion(bookId:String,activityId:String):ActivityDeletionReview=db.withTransaction {
    val s=snapshot(requireNotNull(dao.book(bookId))).first()
    val a=requireNotNull(s.activities.find{it.id==activityId&&it.deletedAt==null}){"活动不存在或已删除 / シーンは存在しないか削除済みです"}
    val events=s.eventActivities.filter{it.activityId==a.id}.map{it.eventId}.toSet()
    val pendingIds=s.pendingOccurrences().filter{it.activityId==a.id}.map{it.id}.toSet()
    val converted=s.draft?.let{it.id==null&&it.activityId==a.id}==true
    val next=s.copy(activities=s.activities.map{if(it.id==a.id)it.copy(deletedAt=clock.millis())else it},
        recurrences=s.recurrences.map{if(it.activityId==a.id)it.copy(active=false)else it},
        occurrences=s.occurrences.map{if(it.activityId==a.id&&(it.id in pendingIds||it.status=="WITHDRAWN"))it.copy(status="CANCELLED")else it},
        reserves=s.reserves.map{if(it.activityId==a.id)it.copy(active=false)else it},
        features=s.features?.let{if(it.activeActivityId==a.id)it.copy(activeActivityId=null)else it},
        draft=if(converted)s.draft!!.copy(activityId=null,reserveId=null,reserveAmount="",occurrenceId=null,treatEnabled=false,treatFriends=emptyList(),treatWeights=emptyMap())else s.draft)
    val months=(s.events.filter{it.id in events}.map{it.date.take(7)}+s.reserves.filter{it.activityId==a.id}.map{it.date.take(7)}+
        s.occurrences.filter{it.activityId==a.id}.map{it.scheduledDate.take(7)}+YearMonth.now(clock).toString()).distinct().sorted()
    ActivityDeletionReview(s,next,a,s.events.count{it.id in events},s.treats.count{it.activityId==a.id},s.activityHeld(a.id),s.pendingOccurrences().count{it.activityId==a.id},converted,
        months.map{s.budgetProjection(it) to next.budgetProjection(it)},s.categories.flatMap{c->months.mapNotNull{m->
            val before=s.budgetProjection(m,c.id);val after=next.budgetProjection(m,c.id)
            if(before.limitMinor!=null&&before!=after)CategoryProjectionChange(c.id,m,before,after)else null
        }})
}
suspend fun LedgerRepository.commitActivityDeletion(review:ActivityDeletionReview):Boolean=db.withTransaction {
    val b=review.activity.bookId
    if(snapshot(requireNotNull(dao.book(b))).first()!=review.before)return@withTransaction false
    val old=review.before;val next=review.after
    dao.putActivity(next.activities.first{it.id==review.activity.id})
    next.recurrences.filter{it!=old.recurrences.find{r->r.id==it.id}}.forEach{dao.putRecurrence(it)}
    next.occurrences.filter{it!=old.occurrences.find{o->o.id==it.id}}.forEach{dao.putOccurrence(it)}
    next.reserves.filter{it!=old.reserves.find{r->r.id==it.id}}.forEach{dao.putReserve(it)}
    if(next.features!=old.features)next.features?.let{dao.putFeatures(it)}
    if(next.draft!=old.draft)next.draft?.let{saveDraft(b,it)}
    true
}
