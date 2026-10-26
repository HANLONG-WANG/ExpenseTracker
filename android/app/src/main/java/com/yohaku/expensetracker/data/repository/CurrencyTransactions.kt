package com.yohaku.expensetracker.data.repository

import androidx.room.withTransaction
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.domain.model.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import java.time.LocalDate

/** Refund original amount is in the purchase currency; settlement and statistics are independent. */
data class RefundInput(val originalEventId:String,val originalAmount:Money,val accountId:String,
    val accountAmount:Money,val baseAmount:Money,val date:String,val confirmed:Boolean,
    val id:String?=null,val note:String="",val rateSource:String="manual",val rateDate:String=date)

data class SettlementConfirmation(val eventId:String,val revision:Int,val accountAmount:Money,
    val baseAmount:Money,val date:String,val source:String="manual",val rateDate:String=date)

fun SettlementEntity.accountMoney()=Money(Currency.valueOf(accountCurrency),accountMinor)
fun BookSnapshot.accountMoney(event:EventEntity)=settlements.find{it.eventId==event.id}?.accountMoney()?:Money(Currency.valueOf(event.currency),event.amountMinor)
fun SettlementEntity.expectedMoney()=Money(Currency.valueOf(expectedAccountCurrency.ifBlank{accountCurrency}),expectedAccountMinor)

data class SettlementImpact(val eventId:String,val revision:Int,val impact:EntryImpact)

/** Net statistics follow the purchase month; a pending refund contributes nothing. */
fun BookSnapshot.netExpense(event:EventEntity):Long {
    if(event.kind!=EntryKind.EXPENSE.name || event.deletedAt!=null)return 0
    val refunds=settlements.filter{it.originalEventId==event.id&&it.confirmed}.map{it.eventId}.toSet()
    return visibleEvents.filter{it.id in refunds}.fold(event.baseMinor){total,r->Math.subtractExact(total,r.baseMinor)}
}
/** Net spending per day in book currency on the monthExpense basis: refunds reduce their original day, confirmed components count on their own date. */
fun BookSnapshot.dailyExpense(events:Collection<EventEntity>):Map<String,Long> {
    val visible=visibleEvents.associateBy{it.id}
    val refunds=settlements.filter{it.confirmed&&it.originalEventId!=null}.mapNotNull{s->visible[s.eventId]?.let{s.originalEventId!! to it.baseMinor}}.groupBy({it.first},{it.second})
    val expenses=events.filter{it.kind==EntryKind.EXPENSE.name&&it.deletedAt==null}
    val ids=expenses.map{it.id}.toSet()
    val days=HashMap<String,Long>()
    expenses.forEach{e->days[e.date]=Math.addExact(days[e.date]?:0L,refunds[e.id].orEmpty().fold(e.baseMinor){total,r->Math.subtractExact(total,r)})}
    expenseComponents.filter{it.confirmed&&it.eventId in ids}.forEach{c->days[c.date]=Math.addExact(days[c.date]?:0L,c.baseMinor)}
    return days
}
fun BookSnapshot.visibleComponents()=expenseComponents.filter{c->c.confirmed&&visibleEvents.any{it.id==c.eventId}}
fun BookSnapshot.monthExpense(month:String):Long {
    val consumption=visibleEvents.filter{it.date.startsWith(month)&&it.kind==EntryKind.EXPENSE.name}.fold(0L){total,e->Math.addExact(total,netExpense(e))}
    return visibleComponents().filter{it.date.startsWith(month)}.fold(consumption){total,c->Math.addExact(total,c.baseMinor)}
}

private fun BookSnapshot.changedImpact(event:EventEntity,next:BookSnapshot):EntryImpact {
    val month=settlements.find{it.eventId==event.id}?.originalEventId?.let{id->events.first{it.id==id}.date.take(7)} ?: event.date.take(7)
    fun income(s:BookSnapshot)=s.visibleEvents.filter{it.kind==EntryKind.INCOME.name&&it.date.startsWith(month)}.sumOf{it.baseMinor}
    val before=budgetProjection(month);val after=next.budgetProjection(month)
    return EntryImpact(month,Currency.valueOf(book.currency),monthExpense(month),next.monthExpense(month),income(this),income(next),
        accounts.mapNotNull{a->val b=balance(a);val n=next.balance(a);if(b==n)null else AccountBalanceChange(a,b,n)},before.spentMinor,after.spentMinor,before.reserveMinor,after.reserveMinor)
}

suspend fun LedgerRepository.previewSettlement(bookId:String,input:SettlementConfirmation):SettlementImpact=db.withTransaction {
    val book=dao.book(bookId) ?: error("账本不存在 / 帳簿がありません")
    val snapshot=snapshot(book).first()
    val event=snapshot.visibleEvents.first{it.id==input.eventId}
    require(event.revision==input.revision){"记录已变化，请重新预览 / 記録が変更されました。再確認してください"}
    require(event.kind in listOf(EntryKind.EXPENSE.name,EntryKind.INCOME.name,EntryKind.REFUND.name))
    val account=snapshot.accounts.first{it.id==event.accountId}
    require(input.accountAmount.currency.name==account.currency && input.baseAmount.currency.name==book.currency)
    require(input.accountAmount.minor>0 && input.baseAmount.minor>0)
    require(account.currency!=book.currency||input.accountAmount.minor==input.baseAmount.minor)
    LocalDate.parse(input.date);LocalDate.parse(input.rateDate)
    val sign=if((event.kind==EntryKind.EXPENSE.name) xor AccountType.valueOf(account.type).debt)-1L else 1L
    val posting=PostingEntity(bookId,"preview",event.id,account.id,Math.multiplyExact(input.accountAmount.minor,sign),account.currency,input.date)
    val settlement=snapshot.settlements.find{it.eventId==event.id}
    val next=snapshot.copy(events=snapshot.events.map{if(it.id==event.id)it.copy(baseMinor=input.baseAmount.minor)else it},
        postings=snapshot.postings.filter{it.eventId!=event.id}+if(event.historicalIncluded)emptyList()else listOf(posting),
        settlements=snapshot.settlements.map{if(it.eventId==event.id)it.copy(confirmed=true)else it})
    require(settlement!=null){"缺少换算资料 / 換算情報がありません"}
    SettlementImpact(event.id,event.revision,snapshot.changedImpact(event,next))
}

/** Revision checked again in the write transaction: a stale preview cannot confirm changed data. */
suspend fun LedgerRepository.confirmSettlement(bookId:String,input:SettlementConfirmation)=db.withTransaction {
    previewSettlement(bookId,input)
    val old=dao.event(bookId,input.eventId)!!
    val settlement=dao.settlement(bookId,old.id)!!
    val account=dao.account(bookId,old.accountId)!!
    val event=old.copy(baseMinor=input.baseAmount.minor,revision=old.revision+1)
    dao.putEvent(event)
    dao.putSettlement(settlement.copy(accountMinor=input.accountAmount.minor,confirmed=true,settlementDate=input.date,rateSource=input.source,rateDate=input.rateDate))
    dao.clearPostings(bookId,event.id)
    if(!event.historicalIncluded){
        val sign=if((event.kind==EntryKind.EXPENSE.name) xor AccountType.valueOf(account.type).debt)-1L else 1L
        dao.putPostings(listOf(PostingEntity(bookId,event.id+":settlement",event.id,account.id,Math.multiplyExact(input.accountAmount.minor,sign),account.currency,input.date)))
    }
    dao.putRevision(RevisionEntity(bookId,id(),event.id,event.revision,clock.millis(),json.encodeToString(old),json.encodeToString(event)))
}

suspend fun LedgerRepository.saveRefund(bookId:String,input:RefundInput):String=db.withTransaction {
    writeChange(prepareRefund(bookId,input))
}
internal suspend fun LedgerRepository.prepareRefund(bookId:String,input:RefundInput):PreparedLedgerChange {
    val book=dao.book(bookId) ?: error("账本不存在 / 帳簿がありません")
    val original=dao.event(bookId,input.originalEventId) ?: error("原消费不存在 / 元の支出がありません")
    require(original.kind==EntryKind.EXPENSE.name&&original.deletedAt==null)
    val account=dao.account(bookId,input.accountId) ?: error("账户不属于本账本 / 別の帳簿の口座です")
    require(account.active)
    require(input.originalAmount.currency.name==original.currency && input.accountAmount.currency.name==account.currency && input.baseAmount.currency.name==book.currency)
    require(input.originalAmount.minor>0&&input.accountAmount.minor>0&&input.baseAmount.minor>0)
    require(account.currency!=book.currency||input.accountAmount.minor==input.baseAmount.minor)
    LocalDate.parse(input.date);LocalDate.parse(input.rateDate)
    val old=input.id?.let{dao.event(bookId,it) ?: error("退款不存在 / 返金がありません")}
    require(old==null||old.kind==EntryKind.REFUND.name&&old.deletedAt==null&&dao.settlement(bookId,old.id)?.originalEventId==original.id)
    val refunded=dao.refunds(bookId,original.id).filter{it.deletedAt==null&&it.id!=old?.id}.fold(0L){sum,e->Math.addExact(sum,e.amountMinor)}
    require(Math.addExact(refunded,input.originalAmount.minor)<=original.amountMinor){"累计退款不能超过原币消费 / 返金総額は元の支出額以下にしてください"}
    val event=EventEntity(bookId,old?.id?:id(),EntryKind.REFUND.name,account.id,original.categoryId,input.originalAmount.minor,original.currency,
        input.baseAmount.minor,input.date,original.merchant,input.note,revision=(old?.revision?:0)+1,createdAt=old?.createdAt?:clock.millis())
    val prior=old?.let{dao.settlement(bookId,it.id)}
    val settlement=SettlementEntity(bookId,event.id,input.accountAmount.minor,account.currency,input.confirmed,input.date,input.rateSource,input.rateDate,
        prior?.expectedAccountMinor?:input.accountAmount.minor,prior?.expectedBaseMinor?:input.baseAmount.minor,original.id,expectedAccountCurrency=prior?.expectedMoney()?.currency?.name?:account.currency)
    val delta=if(AccountType.valueOf(account.type).debt)-input.accountAmount.minor else input.accountAmount.minor
    val postings=listOf(PostingEntity(bookId,event.id+":settlement",event.id,account.id,delta,account.currency,input.date,input.confirmed))
    return PreparedLedgerChange(old,event,settlement,postings,activityId=dao.eventActivity(bookId,original.id)?.activityId)
}
