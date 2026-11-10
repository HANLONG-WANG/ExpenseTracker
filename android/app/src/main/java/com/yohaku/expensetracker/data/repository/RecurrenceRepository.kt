package com.yohaku.expensetracker.data.repository

import androidx.room.withTransaction
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.domain.budget.recurrenceDates
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

data class RecurrenceInput(val name:String,val accountId:String,val categoryId:String,val amount:Money?,val baseAmount:Money?,val estimated:Boolean,
    val frequency:RecurrenceFrequency,val anchorDate:String,val endDate:String?=null,val id:String?=null,val active:Boolean=true,val activityId:String?=null,
    val merchant:String="",val merchantId:String?=null,val note:String="",val cardPaymentChoice:String?=null,val cardPaymentPeriods:Int?=null,val merchantBaseline:MerchantBaseline?=null)

suspend fun LedgerRepository.saveRecurrence(bookId:String,input:RecurrenceInput):String=db.withTransaction {
    val book=dao.book(bookId)?:error("账本不存在 / 帳簿がありません")
    require(input.name.isNotBlank()){ "请输入名称 / 名前を入力してください" }
    val old=input.id?.let{dao.recurrence(bookId,it)?:error("周期规则不存在 / 定期ルールがありません")}
    val account=dao.account(bookId,input.accountId)?:error("账户不属于本账本 / 別の帳簿の口座です")
    val category=dao.category(bookId,input.categoryId)?:error("分类不属于本账本 / 別の帳簿のカテゴリです")
    require((account.active||old?.accountId==account.id)&&account.type!=AccountType.LOAN.name&&(categoryEnabled(bookId,category.id)||old?.categoryId==category.id)&&category.kind==EntryKind.EXPENSE.name){"请选择未归档的支出分类和可用的消费付款账户 / アーカイブされていない支出カテゴリと有効な支払口座を選択してください"}
    require(input.amount==null||input.amount.currency.name==account.currency&&input.amount.minor>0){"金额应大于零，未知请留空 / 金額は0より大きく、不明なら空欄にしてください"}
    val base=if(input.amount==null)null else if(account.currency==book.currency)input.amount else input.baseAmount
    require(base==null||base.currency.name==book.currency&&base.minor>0){"本位币预留应大于零，未知请留空 / 基準通貨の確保額は0より大きく、不明なら空欄にしてください"}
    val anchor=LocalDate.parse(input.anchorDate);input.endDate?.let{require(LocalDate.parse(it)>=anchor){"结束日期不能早于首次安排 / 終了日は最初の予定日以降にしてください"}}
    if(input.activityId!=null) {
        require(dao.activity(bookId,input.activityId)?.let{it.deletedAt==null}==true)
        require(dao.features(bookId)?.activityMode==true||old?.activityId==input.activityId)
    }
    val fields=if(old!=null&&merchantFieldsUnchanged(MerchantBaseline(input.merchant,input.merchantId),input.merchantBaseline,dao.merchants(bookId)))MerchantBaseline(old.merchant,old.merchantId)else MerchantBaseline(input.merchant,input.merchantId)
    val merchant=resolveSavedMerchant(bookId,EntryKind.EXPENSE,fields.id,fields.name.ifBlank{input.name},dao.merchants(bookId))
    val payment=fixedCardPayment(account,input.cardPaymentChoice,input.cardPaymentPeriods)
    val rule=RecurrenceEntity(bookId,old?.id?:id(),input.name.trim(),account.id,category.id,account.currency,input.amount?.minor,base?.minor,
        input.estimated||input.amount==null,input.frequency.name,anchor.toString(),input.endDate,input.active,input.activityId,merchant?.name?:fields.name,merchant?.id,input.note,payment.first,payment.second)
    dao.putRecurrence(rule)
    val months=(dao.occurrences(bookId).filter{it.recurrenceId==rule.id}.map{it.scheduledDate.take(7)}+YearMonth.now(clock).toString()).distinct()
    months.forEach{ensureOccurrences(bookId,it)}
    rule.id
}
/** Stable scheduled-date identity; materialization creates tasks only, never ledger events. */
suspend fun LedgerRepository.ensureOccurrences(bookId:String,month:String,recurrenceId:String?=null)=db.withTransaction {
    val period=YearMonth.parse(month)
    require(dao.book(bookId)!=null)
    val existing=dao.occurrences(bookId).associateBy{it.id}
    dao.recurrences(bookId).filter{recurrenceId==null||it.id==recurrenceId}.forEach{rule->
        val dates=if(rule.active)recurrenceDates(LocalDate.parse(rule.anchorDate),rule.endDate?.let{LocalDate.parse(it)},RecurrenceFrequency.valueOf(rule.frequency),period).map{it.toString()}else emptyList()
        existing.values.filter{it.recurrenceId==rule.id&&it.scheduledDate.startsWith(month)&&it.status=="PENDING"&&it.scheduledDate !in dates}.forEach{dao.putOccurrence(it.copy(status="WITHDRAWN"))}
        dates.forEach{date->
            val id=UUID.nameUUIDFromBytes("$bookId/${rule.id}/$date".toByteArray(Charsets.UTF_8)).toString()
            val old=existing[id]
            if(old==null||old.status in listOf("PENDING","WITHDRAWN")) {
                val value=OccurrenceEntity(bookId,id,rule.id,date,rule.name,rule.accountId,rule.categoryId,rule.currency,rule.amountMinor,rule.baseMinor,rule.estimated,eventId=old?.eventId,activityId=rule.activityId)
                if(value!=old)dao.putOccurrence(value)
            }
        }
    }
}
suspend fun LedgerRepository.cancelOccurrence(bookId:String,id:String)=db.withTransaction {
    val old=dao.occurrence(bookId,id)?:error("周期实例不存在 / 定期予定がありません")
    dao.putOccurrence(old.copy(status="CANCELLED"))
}
fun RecurrenceEntity.includesScheduledDate(value:String):Boolean {
    val date=LocalDate.parse(value)
    return active&&date in recurrenceDates(LocalDate.parse(anchorDate),endDate?.let{LocalDate.parse(it)},RecurrenceFrequency.valueOf(frequency),YearMonth.from(date))
}

/** Explicitly undo a user's cancellation; old releases also lacked cancellation reasons. */
suspend fun LedgerRepository.restoreOccurrence(bookId:String,id:String)=db.withTransaction {
    val old=dao.occurrence(bookId,id)?:error("周期实例不存在 / 定期予定がありません")
    require(old.status=="CANCELLED"){"这次待办未被取消 / この予定は取消されていません"}
    val rule=dao.recurrence(bookId,old.recurrenceId)!!
    require(rule.includesScheduledDate(old.scheduledDate)){"请先启用规则并确认该日期仍在安排范围内 / ルールを有効にし、この日が予定の範囲内か確認してください"}
    val recorded=old.eventId?.let{dao.event(bookId,it)}?.let{it.deletedAt==null&&it.kind==EntryKind.EXPENSE.name}==true
    dao.putOccurrence(if(recorded)old.copy(status="COMPLETED")else old.copy(status="PENDING",name=rule.name,accountId=rule.accountId,categoryId=rule.categoryId,currency=rule.currency,amountMinor=rule.amountMinor,baseMinor=rule.baseMinor,estimated=rule.estimated,activityId=rule.activityId))
}
suspend fun LedgerRepository.unlinkOccurrence(bookId:String,id:String)=db.withTransaction {
    val old=dao.occurrence(bookId,id)?:error("周期实例不存在 / 定期予定がありません")
    val rule=dao.recurrence(bookId,old.recurrenceId)!!
    dao.putOccurrence(old.copy(status=if(rule.includesScheduledDate(old.scheduledDate))"PENDING"else"WITHDRAWN",eventId=null))
}
internal suspend fun LedgerRepository.recheckOccurrences(bookId:String,eventId:String) {
    val event=dao.event(bookId,eventId)
    dao.eventOccurrences(bookId,eventId).filter{it.status!="CANCELLED"}.forEach{old->
        val rule=dao.recurrence(bookId,old.recurrenceId)!!
        val status=if(event?.deletedAt==null&&event?.kind==EntryKind.EXPENSE.name)"COMPLETED"else if(rule.includesScheduledDate(old.scheduledDate))"PENDING"else"WITHDRAWN"
        if(old.status!=status)dao.putOccurrence(old.copy(status=status))
    }
}
fun BookSnapshot.pendingOccurrences()=occurrences.filter{o->o.status in listOf("PENDING","COMPLETED")&&(o.eventId==null||visibleEvents.none{it.id==o.eventId&&it.kind==EntryKind.EXPENSE.name})}

data class OccurrenceLinkReview(val snapshot:BookSnapshot,val occurrence:OccurrenceEntity,val event:EventEntity,val before:BudgetProjection,val after:BudgetProjection)
suspend fun LedgerRepository.previewOccurrenceLink(bookId:String,occurrenceId:String,eventId:String):OccurrenceLinkReview=db.withTransaction {
    val snapshot=snapshot(dao.book(bookId)?:error("账本不存在 / 帳簿がありません")).first()
    prepareOccurrenceLink(snapshot,occurrenceId,eventId)
}

/** One shared preparation for the screen and batch commands; no per-item ledger reload. */
internal fun prepareOccurrenceLink(snapshot:BookSnapshot,occurrenceId:String,eventId:String):OccurrenceLinkReview {
    val occurrence=snapshot.occurrences.firstOrNull{it.id==occurrenceId}?:error("周期实例不存在 / 定期予定がありません")
    val event=snapshot.events.firstOrNull{it.id==eventId}?:error("支出不存在 / 支出がありません")
    require(event.kind==EntryKind.EXPENSE.name&&event.deletedAt==null)
    require(occurrence.status=="PENDING"||occurrence.status=="COMPLETED"&&occurrence.eventId==eventId){"周期实例已完成或取消 / 定期予定は完了・取消済みです"}
    require(snapshot.occurrences.none{it.eventId==eventId&&it.id!=occurrenceId&&it.status=="COMPLETED"}){"支出已关联其他周期实例 / 他の定期予定に関連済みです"}
    val next=snapshot.copy(occurrences=snapshot.occurrences.map{if(it.id==occurrence.id)it.copy(status="COMPLETED",eventId=event.id)else it})
    val month=occurrence.scheduledDate.take(7)
    return OccurrenceLinkReview(snapshot,occurrence,event,snapshot.budgetProjection(month),next.budgetProjection(month))
}
fun EntryDraft?.isOccurrencePaymentDraft(occurrenceId:String)=this?.let{it.id==null&&it.occurrenceId==occurrenceId}==true
fun BookSnapshot.occurrencePaymentDraft(occurrence:OccurrenceEntity,resumeStored:Boolean=false):EntryDraft =
    draft?.takeIf{resumeStored&&it.isOccurrencePaymentDraft(occurrence.id)}?:occurrenceDraft(occurrence,recurrences.first{it.id==occurrence.recurrenceId})
fun EntryDraft.withOccurrenceAmount(amount:String,foreignCurrency:Boolean)=copy(amount=amount,baseAmount=if(foreignCurrency)""else baseAmount)

/** Only the matching payment draft is consumed; another open draft is never discarded. */
internal suspend fun LedgerRepository.consumeOccurrencePaymentDraft(bookId:String,occurrenceId:String) {
    val draft=dao.storedDraft(bookId)?.let{json.decodeFromString<EntryDraft>(it.json)}
    if(draft.isOccurrencePaymentDraft(occurrenceId))clearDraft(bookId)
}
suspend fun LedgerRepository.commitOccurrenceLink(review:OccurrenceLinkReview,discardPaymentDraft:Boolean=false):Boolean=db.withTransaction {
    val book=dao.book(review.occurrence.bookId)?:error("账本不存在 / 帳簿がありません")
    if(snapshot(book).first().reviewState()!=review.snapshot.reviewState())return@withTransaction false
    dao.putOccurrence(review.occurrence.copy(status="COMPLETED",eventId=review.event.id))
    if(discardPaymentDraft)consumeOccurrencePaymentDraft(book.id,review.occurrence.id)
    true
}
internal suspend fun LedgerRepository.linkOccurrenceBatch(bookId:String,links:List<Pair<String,String>>)=db.withTransaction {
    require(links.isNotEmpty()&&links.map{it.first}.distinct().size==links.size)
    var snapshot=snapshot(requireNotNull(dao.book(bookId))).first()
    val changes=links.map{(occurrence,event)->
        val review=prepareOccurrenceLink(snapshot,occurrence,event)
        val updated=review.occurrence.copy(status="COMPLETED",eventId=review.event.id)
        snapshot=snapshot.copy(occurrences=snapshot.occurrences.map{if(it.id==updated.id)updated else it})
        updated
    }
    changes.forEach{dao.putOccurrence(it);consumeOccurrencePaymentDraft(bookId,it.id)}
}

/** Shared payment commit for the quick confirmation and command path. Full-form saves use the
 * same entry review writer; media/network cleanup remains outside the database transaction. */
suspend fun LedgerRepository.commitOccurrencePayment(bookId:String,review:LedgerChangeReview,discardPaymentDraft:Boolean=false):String?=db.withTransaction {
    val occurrence=requireNotNull(review.change.occurrenceId)
    val id=commitEntryReview(bookId,review,clearDraft=false)?:return@withTransaction null
    if(discardPaymentDraft)consumeOccurrencePaymentDraft(bookId,occurrence)
    id
}

fun RecurrenceEntity.editInput()=RecurrenceInput(name,accountId,categoryId,
    amountMinor?.let{Money(Currency.valueOf(currency),it)},null,estimated,RecurrenceFrequency.valueOf(frequency),anchorDate,endDate,id,active,activityId,merchant,merchantId,note,cardPaymentChoice,cardPaymentPeriods)
suspend fun LedgerRepository.setRecurrenceActive(bookId:String,id:String,active:Boolean)=db.withTransaction {
    val rule=requireNotNull(dao.recurrence(bookId,id)){"周期规则不存在 / 定期ルールがありません"}
    val book=requireNotNull(dao.book(bookId))
    saveRecurrence(bookId,rule.editInput().copy(active=active,baseAmount=rule.baseMinor?.let{Money(Currency.valueOf(book.currency),it)}))
}
/** Category migration also covers cancelled/completed history; payment facts are not rewritten. */
suspend fun LedgerRepository.updateOccurrenceCategories(bookId:String,ids:List<String>,categoryId:String)=db.withTransaction {
    require(ids.isNotEmpty()&&ids.distinct().size==ids.size)
    val category=requireNotNull(dao.category(bookId,categoryId))
    require(category.kind==EntryKind.EXPENSE.name&&categoryEnabled(bookId,categoryId)){"请选择启用的支出分类 / 有効な支出カテゴリを選択してください"}
    val all=dao.occurrences(bookId).associateBy{it.id}
    val next=ids.map{requireNotNull(all[it]){"周期实例不存在 / 定期予定がありません"}.copy(categoryId=categoryId)}
    next.forEach{dao.putOccurrence(it)}
}

/** Forecast missing future tasks without writing them or reviving cancelled/completed tasks. */
internal fun BookSnapshot.forecastOccurrences(from:LocalDate,through:LocalDate):List<OccurrenceEntity> {
    val known=occurrences.associateBy{it.id};val result=occurrences.toMutableList()
    var month=YearMonth.from(from);val last=YearMonth.from(through)
    while(month<=last) {
        for(rule in recurrences.filter{it.active})for(date in recurrenceDates(LocalDate.parse(rule.anchorDate),rule.endDate?.let{LocalDate.parse(it)},RecurrenceFrequency.valueOf(rule.frequency),month)) {
            if(date>through)continue
            val id=UUID.nameUUIDFromBytes("${book.id}/${rule.id}/$date".toByteArray(Charsets.UTF_8)).toString()
            if(id !in known)result+=OccurrenceEntity(book.id,id,rule.id,date.toString(),rule.name,rule.accountId,rule.categoryId,rule.currency,rule.amountMinor,rule.baseMinor,rule.estimated,activityId=rule.activityId)
        }
        month=month.plusMonths(1)
    }
    return result
}
