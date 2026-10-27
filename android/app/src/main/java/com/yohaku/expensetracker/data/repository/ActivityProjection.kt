package com.yohaku.expensetracker.data.repository

import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.domain.model.*
import java.math.RoundingMode

fun BookSnapshot.activityExpenses(activityId:String)=visibleEvents.filter{e->e.kind==EntryKind.EXPENSE.name&&activityByEvent[e.id]==activityId}
fun BookSnapshot.activitySpent(activityId:String,categoryId:String?=null):Long {
    val ids=categoryId?.let{id->setOf(id)+categories.filter{it.parentId==id}.map{it.id}}
    val expense=activityExpenses(activityId).filter{ids==null||it.categoryId in ids}.fold(0L){sum,e->Math.addExact(sum,netExpense(e))}
    return if(ids!=null)expense else visibleComponents().filter{c->activityByEvent[c.eventId]==activityId}.fold(expense){sum,c->Math.addExact(sum,c.baseMinor)}
}
fun BookSnapshot.reserveRemaining(reserve:ActivityReserveEntity,ignoringEventId:String?=null):Long {
    if(!reserve.active)return 0
    val live=visibleEvents.map{it.id}.toSet()
    val used=reserveUses.filter{it.reserveId==reserve.id&&it.eventId in live&&it.eventId!=ignoringEventId}.fold(0L){sum,u->Math.addExact(sum,u.amountMinor)}
    return Math.subtractExact(Math.subtractExact(reserve.amountMinor,reserve.releasedMinor),used)
}
fun BookSnapshot.reserveAccountRemaining(reserve:ActivityReserveEntity):Money? {
    val amount=reserve.accountMinor?:return null
    return Money(Currency.valueOf(reserve.accountCurrency!!),amount.toBigDecimal().multiply(reserveRemaining(reserve).toBigDecimal()).divide(reserve.amountMinor.toBigDecimal(),0,RoundingMode.HALF_UP).longValueExact())
}
fun BookSnapshot.activityHeld(activityId:String)=reserves.filter{it.activityId==activityId}.fold(0L){sum,r->Math.addExact(sum,reserveRemaining(r))}
fun BookSnapshot.liveTreats(activityId:String)=treats.filter{t->t.activityId==activityId&&t.deletedAt==null&&(t.eventId==null||visibleEvents.any{it.id==t.eventId})}
data class FriendBalance(val friend:FriendEntity,val iGive:Long,val theyGive:Long) {val difference get()=Math.subtractExact(iGive,theyGive)}
fun BookSnapshot.friendBalances(activityId:String):List<FriendBalance> {
    val live=liveTreats(activityId)
    val ids=(participants.filter{it.activityId==activityId}.map{it.friendId}+live.mapNotNull{it.payerFriendId}+allocations.filter{a->live.any{it.id==a.treatId}}.mapNotNull{it.friendId}).distinct()
    return ids.map{id->
        val mine=live.filter{it.payerFriendId==null}.map{it.id}.toSet()
        val theirs=live.filter{it.payerFriendId==id}.map{it.id}.toSet()
        FriendBalance(friends.first{it.id==id},allocations.filter{it.treatId in mine&&it.friendId==id}.fold(0L){s,a->Math.addExact(s,a.baseMinor)},
            allocations.filter{it.treatId in theirs&&it.participant=="ME"}.fold(0L){s,a->Math.addExact(s,a.baseMinor)})
    }
}

data class FriendBalanceChange(val activityName:String,val friendName:String,val before:Long,val after:Long)
fun BookSnapshot.friendChanges(next:BookSnapshot):List<FriendBalanceChange> = activities.flatMap{activity->
    val before=friendBalances(activity.id);val after=next.friendBalances(activity.id)
    (before.map{it.friend.id}+after.map{it.friend.id}).distinct().mapNotNull{id->
        val previous=before.find{it.friend.id==id};val following=after.find{it.friend.id==id}
        val from=previous?.difference?:0L;val to=following?.difference?:0L
        if(from==to)null else FriendBalanceChange(activity.name,(previous?:following)!!.friend.name,from,to)
    }
}
