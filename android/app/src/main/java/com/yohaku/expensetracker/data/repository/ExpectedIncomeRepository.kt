package com.yohaku.expensetracker.data.repository

import androidx.room.withTransaction
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.domain.model.*
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import com.yohaku.expensetracker.domain.currency.ReferenceRateSnapshot
import kotlinx.serialization.encodeToString

/** A plan is not a money posting. Original expected amounts survive arrival confirmation. */
data class ExpectedIncomeInput(val name:String,val amount:Money,val date:String,val accountId:String,val accountAmount:Money?=null,val id:String?=null,val referenceRate:ReferenceRateSnapshot?=null)
data class ExpectedIncomeReview internal constructor(val before:BookSnapshot,val income:ExpectedIncomeEntity)
data class ExpectedIncomeArrivalReview internal constructor(val before:BookSnapshot,val income:ExpectedIncomeEntity,val entry:LedgerChangeReview?,val existing:EventEntity?) {
    val event get()=entry?.change?.event?:existing!!
}

fun BookSnapshot.expectedIncomeArrival(income:ExpectedIncomeEntity):EventEntity? {
    if(income.status!="ARRIVED")return null
    return visibleEvents.firstOrNull{it.id==income.eventId&&it.kind==EntryKind.INCOME.name&&!it.historicalIncluded&&
        it.accountId==income.accountId&&settlements.any{s->s.eventId==it.id&&s.confirmed}}
}
fun BookSnapshot.expectedIncomeNeedsReview(income:ExpectedIncomeEntity)=income.status=="ARRIVED"&&expectedIncomeArrival(income)==null

suspend fun LedgerRepository.previewExpectedIncome(bookId:String,input:ExpectedIncomeInput):ExpectedIncomeReview=db.withTransaction {
    val b=requireNotNull(dao.book(bookId));val s=snapshot(b).first()
    val account=requireNotNull(s.accounts.firstOrNull{it.id==input.accountId&&it.active&&!AccountType.valueOf(it.type).debt}){"请选择本账本的资金账户 / この帳簿の資金口座を選択してください"}
    require(input.name.isNotBlank()&&input.amount.minor>0);LocalDate.parse(input.date)
    require(input.accountAmount==null||input.accountAmount.currency.name==account.currency&&input.accountAmount.minor>0)
    val old=input.id?.let{id->requireNotNull(s.expectedIncomes.firstOrNull{it.id==id})}
    require(old==null||s.expectedIncomeArrival(old)==null){"已到账收入请编辑实际记录 / 入金済み収入は実際の記録を編集してください"}
    val converted=if(input.amount.currency.name==account.currency)input.amount.minor else input.accountAmount?.minor
    input.referenceRate?.let{require(it.base==input.amount.currency&&it.quote.name==account.currency&&it.convert(input.amount).minor==converted)}
    ExpectedIncomeReview(s,ExpectedIncomeEntity(bookId,old?.id?:id(),input.name.trim(),input.amount.minor,input.amount.currency.name,input.date,account.id,converted,
        revision=(old?.revision?:0)+1,updatedAt=clock.millis(),accountEstimateJson=input.referenceRate?.let{json.encodeToString(listOf(it))}))
}
suspend fun LedgerRepository.commitExpectedIncome(review:ExpectedIncomeReview):String?=db.withTransaction {
    if(snapshot(requireNotNull(dao.book(review.income.bookId))).first().reviewState()!=review.before.reviewState())return@withTransaction null
    dao.putExpectedIncome(review.income);review.income.id
}
suspend fun LedgerRepository.cancelExpectedIncome(bookId:String,id:String)=db.withTransaction {
    val old=requireNotNull(dao.expectedIncome(bookId,id))
    require(old.status!="ARRIVED"){"已到账请处理实际收入，不取消实际资金 / 入金済みは実収入を編集してください"}
    dao.putExpectedIncome(old.copy(status="CANCELLED",revision=old.revision+1,updatedAt=clock.millis()))
}

/** New receipt and link are committed atomically through the normal income transaction. */
suspend fun LedgerRepository.previewExpectedIncomeArrival(bookId:String,id:String,draft:EntryDraft?=null,existingEventId:String?=null):ExpectedIncomeArrivalReview=db.withTransaction {
    require((draft!=null)!=(existingEventId!=null))
    val s=snapshot(requireNotNull(dao.book(bookId))).first();val income=requireNotNull(s.expectedIncomes.firstOrNull{it.id==id})
    require(income.status=="PENDING"||s.expectedIncomeNeedsReview(income)){"该预计收入已经处理 / この収入予定は処理済みです"}
    val entry=draft?.let{
        require(it.id==null&&it.kind==EntryKind.INCOME&&it.accountId==income.accountId&&!it.historicalIncluded&&it.settlementConfirmed)
        previewEntry(bookId,it)
    }
    val event=entry?.change?.event?:requireNotNull(s.visibleEvents.firstOrNull{it.id==existingEventId})
    val settlement=entry?.change?.settlement?:requireNotNull(s.settlements.firstOrNull{it.eventId==event.id})
    require(event.kind==EntryKind.INCOME.name&&event.accountId==income.accountId&&!event.historicalIncluded&&settlement.confirmed)
    require(event.date<=LocalDate.now(clock).toString()&&settlement.settlementDate<=LocalDate.now(clock).toString()){"到账确认日期不能在未来 / 入金確認日を未来に設定できません"}
    require(s.expectedIncomes.none{it.id!=income.id&&it.eventId==event.id}){"此收入已关联另一预计项 / この収入は別の予定に関連付け済みです"}
    ExpectedIncomeArrivalReview(s,income,entry,if(entry==null)event else null)
}
suspend fun LedgerRepository.commitExpectedIncomeArrival(review:ExpectedIncomeArrivalReview):String?=db.withTransaction {
    val bookId=review.income.bookId
    if(snapshot(requireNotNull(dao.book(bookId))).first().reviewState()!=review.before.reviewState())return@withTransaction null
    val eventId=review.entry?.let{writeChange(it.change)}?:review.existing!!.id
    dao.putExpectedIncome(review.income.copy(status="ARRIVED",eventId=eventId,revision=review.income.revision+1,updatedAt=clock.millis()))
    eventId
}
