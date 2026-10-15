package com.yohaku.expensetracker.data.repository

import androidx.room.withTransaction
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.domain.activity.apportion
import com.yohaku.expensetracker.domain.activity.apportionWithTail
import com.yohaku.expensetracker.domain.model.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import java.math.BigDecimal
import java.time.LocalDate

data class PreparedTreat(val treat:TreatEntity,val allocations:List<TreatAllocationEntity>)
data class OtherTreatInput(val activityId:String,val payerId:String,val total:Money,val base:Money,val date:String,val name:String,
    val friendIds:List<String>,val mode:TreatSplitMode=TreatSplitMode.EQUAL,val weights:Map<String,String> = emptyMap(),val id:String?=null)
data class TreatReview(val snapshot:BookSnapshot,val change:PreparedTreat,val before:List<FriendBalance>,val after:List<FriendBalance>)

suspend fun LedgerRepository.saveFriend(bookId:String,name:String,id:String?=null):String=db.withTransaction {
    require(dao.book(bookId)!=null);require(name.isNotBlank()){ "请输入朋友名称 / 友達の名前を入力してください" }
    val old=id?.let{dao.friend(bookId,it)?:error("朋友不属于本账本 / 別の帳簿の友達です")}
    val friend=FriendEntity(bookId,old?.id?:this.id(),name.trim(),old?.active?:true)
    dao.putFriend(friend);friend.id
}
suspend fun LedgerRepository.addParticipant(bookId:String,activityId:String,friendId:String)=db.withTransaction {
    require(dao.activity(bookId,activityId)!=null&&dao.friend(bookId,friendId)?.active==true){"请选择本账本的活动和朋友 / この帳簿のシーンと友達を選択してください"}
    val current=dao.participants(bookId,activityId)
    if(current.none{it.friendId==friendId})dao.putParticipant(ActivityParticipantEntity(bookId,activityId,friendId,current.size))
}
internal suspend fun LedgerRepository.prepareTreat(bookId:String,activityId:String,event:EventEntity?,input:OtherTreatInput?,friendIds:List<String>,mode:TreatSplitMode,weights:Map<String,String>):PreparedTreat {
    val book=dao.book(bookId)?:error("账本不存在 / 帳簿がありません")
    val activity=dao.activity(bookId,activityId)?:error("必须关联本账本的活动 / この帳簿のシーンが必要です")
    val payer=input?.payerId
    val total=event?.let{Money(Currency.valueOf(it.currency),it.amountMinor)}?:input!!.total
    val base=event?.let{Money(Currency.valueOf(book.currency),it.baseMinor)}?:input!!.base
    val date=event?.date?:input!!.date
    require(base.currency.name==book.currency&&total.minor>0&&base.minor>0)
    LocalDate.parse(date)
    require(!activity.ended||date in activity.startDate..activity.endDate){"已结束活动只补录活动期间的事实 / 終了済みシーンは期間内の事実のみ記録できます"}
    if(event!=null)require(event.kind==EntryKind.EXPENSE.name)
    val selected=friendIds.distinct();require(selected.isNotEmpty()){ "请选择参与朋友 / 参加する友達を選択してください" }
    val participants=dao.participants(bookId,activityId).map{it.friendId}.toSet()
    require(selected.all{it in participants}){"参与朋友必须属于这个活动 / 参加する友達はこのシーンに属する必要があります"}
    require(payer==null||payer in selected){"付款朋友必须是本次参与者 / 支払った友達を参加者に含めてください"}
    val keys=listOf("ME")+selected
    val portions=when(mode) {
        TreatSplitMode.EQUAL->apportion(total.minor,keys.map{BigDecimal.ONE})
        TreatSplitMode.PERCENT->{
            val values=keys.map{weights[it]?.toBigDecimalOrNull()?:error("请填写每人比例 / 全員の割合を入力してください")}
            require(values.all{it.signum()>=0}&&values.fold(BigDecimal.ZERO,BigDecimal::add).compareTo(BigDecimal("100"))==0){"比例合计必须为100% / 割合の合計は100%にしてください"}
            val tail=weights["__tail"]?.let{keys.indexOf(it)}?:keys.lastIndex
            require(tail>=0){"尾差归属人必须参与本次互请 / 端数の受取人は参加者から選択してください"}
            apportionWithTail(total.minor,values,tail)
        }
        TreatSplitMode.AMOUNTS->{
            val values=keys.map{Money.parse(weights[it].orEmpty(),total.currency).minor}
            require(values.fold(0L,Math::addExact)==total.minor){"份额合计必须等于整单 / 取り分の合計は全体額に一致させてください"}
            values
        }
    }
    require(payer==null||portions.first()>0){"别人付款的记录必须包含本人实际份额 / 他人の支払は自分の取り分を含めてください"}
    val bases=apportion(base.minor,portions.map{it.toBigDecimal()})
    val old=event?.let{dao.eventTreat(bookId,it.id)}?:input?.id?.let{dao.treat(bookId,it)?:error("互请记录不存在 / ごちそう記録がありません")}
    require(old==null||old.bookId==bookId&&(event!=null||old.activityId==activityId)&&(event!=null&&old.eventId==event.id||event==null&&old.eventId==null))
    val treat=TreatEntity(bookId,old?.id?:id(),activityId,event?.id,payer,total.minor,total.currency.name,base.minor,date,event?.merchant?:input!!.name.trim(),mode.name,json.encodeToString(weights),(old?.revision?:0)+1)
    return PreparedTreat(treat,keys.mapIndexed{i,key->TreatAllocationEntity(bookId,treat.id,key,if(key=="ME")null else key,portions[i],bases[i])})
}
internal suspend fun LedgerRepository.writeTreat(change:PreparedTreat) {
    dao.putTreat(change.treat);dao.clearAllocations(change.treat.bookId,change.treat.id);dao.putAllocations(change.allocations)
}
suspend fun LedgerRepository.previewOtherTreat(bookId:String,input:OtherTreatInput):TreatReview=db.withTransaction {
    require(dao.features(bookId)?.activityMode==true){"请先开启本账本活动功能 / この帳簿のシーン機能を有効にしてください"}
    val snapshot=snapshot(dao.book(bookId)!!).first()
    val change=prepareTreat(bookId,input.activityId,null,input,input.friendIds,input.mode,input.weights)
    val next=snapshot.copy(treats=snapshot.treats.filter{it.id!=change.treat.id}+change.treat,allocations=snapshot.allocations.filter{it.treatId!=change.treat.id}+change.allocations)
    TreatReview(snapshot,change,snapshot.friendBalances(input.activityId),next.friendBalances(input.activityId))
}
suspend fun LedgerRepository.commitOtherTreat(review:TreatReview):Boolean=db.withTransaction {
    val book=dao.book(review.change.treat.bookId)!!
    if(snapshot(book).first().reviewState()!=review.snapshot.reviewState())return@withTransaction false
    writeTreat(review.change);true
}
suspend fun LedgerRepository.removeOtherTreat(bookId:String,id:String)=db.withTransaction {
    val old=dao.treat(bookId,id)?:error("互请记录不存在 / ごちそう記録がありません")
    require(old.eventId==null){"本人付款请从原支出编辑或删除 / 自分の支払は元の支出を編集・削除してください"}
    dao.putTreat(old.copy(deletedAt=clock.millis(),revision=old.revision+1))
}
