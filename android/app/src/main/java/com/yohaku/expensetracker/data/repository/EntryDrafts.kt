package com.yohaku.expensetracker.data.repository

import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.domain.model.*
import kotlinx.serialization.json.Json
import java.time.LocalDate

private val entryDraftJson=Json { encodeDefaults=true }

/** Shared editor reconstruction; cloud patches start with exactly these preserved values. */
fun BookSnapshot.editingEntryDraft(event:EventEntity):EntryDraft {
    if(event.kind==EntryKind.TRANSFER.name)return editingTransferDraft(event)
        val settlement=settlements.find{it.eventId==event.id}
        val treat=treats.find{it.eventId==event.id}
        val use=reserveUses.firstOrNull{it.eventId==event.id}
        return EntryDraft(reserveId=use?.reserveId,reserveAmount=use?.let{Money(Currency.valueOf(book.currency),it.amountMinor).decimal.toPlainString()}.orEmpty(),treatEnabled=treat!=null,treatFriends=allocations.filter{it.treatId==treat?.id}.mapNotNull{it.friendId},treatMode=treat?.let{TreatSplitMode.valueOf(it.mode)}?:TreatSplitMode.EQUAL,treatWeights=treat?.let{entryDraftJson.decodeFromString<Map<String,String>>(it.weightsJson)}?:emptyMap(),id=event.id,kind=EntryKind.valueOf(event.kind),categoryId=event.categoryId,accountId=event.accountId,
            amount=Money(Currency.valueOf(event.currency),event.amountMinor).decimal.toPlainString(),
            baseAmount=Money(Currency.valueOf(book.currency),event.baseMinor).decimal.toPlainString(),date=event.date,cardPaymentChoice=event.cardPaymentChoice,cardPaymentPeriods=event.cardPaymentPeriods?.toString().orEmpty(),cardPaymentMonth=event.cardPaymentMonth.orEmpty(),cardPaymentFee=event.cardPaymentFeeMinor?.let{Money(Currency.valueOf(accounts.first{a->a.id==event.accountId}.currency),it).decimal.toPlainString()}.orEmpty(),merchant=event.merchant,merchantId=eventMerchants.find{it.eventId==event.id}?.merchantId,note=event.note,historicalIncluded=event.historicalIncluded,originalCurrency=Currency.valueOf(event.currency),
            accountAmount=settlement?.let{Money(Currency.valueOf(it.accountCurrency),it.accountMinor).decimal.toPlainString()}.orEmpty(),
            settlementConfirmed=settlement?.confirmed?:true,settlementDate=settlement?.settlementDate?:event.date,rateSource=settlement?.rateSource?:"manual",rateDate=settlement?.rateDate?:event.date,originalEventId=settlement?.originalEventId,manualRate=settlement?.manualRate.orEmpty(),referenceRates=settlement?.referenceRatesJson?.let{entryDraftJson.decodeFromString(it)}?:emptyList(),activityId=eventActivities.find{it.eventId==event.id}?.activityId,occurrenceId=occurrences.firstOrNull{it.eventId==event.id&&it.status in listOf("PENDING","COMPLETED")}?.id)
    }
private fun BookSnapshot.editingTransferDraft(event:EventEntity):EntryDraft {
        val transfer=transfers.find{it.eventId==event.id}
        val sourcePosting=storedPostings.find{it.eventId==event.id&&it.accountId==event.accountId}
        val toPosting=storedPostings.find{it.eventId==event.id&&it.accountId!=event.accountId}
        val toAccountId=transfer?.toAccountId?:toPosting?.accountId
        val toCurrency=transfer?.toCurrency?:toPosting?.currency?:event.currency
        val component=expenseComponents.firstOrNull{it.eventId==event.id}
        val treat=treats.find{it.eventId==event.id}
        val use=reserveUses.firstOrNull{it.eventId==event.id}
        return EntryDraft(reserveId=use?.reserveId,reserveAmount=use?.let{Money(Currency.valueOf(book.currency),it.amountMinor).decimal.toPlainString()}.orEmpty(),treatEnabled=treat!=null,treatFriends=allocations.filter{it.treatId==treat?.id}.mapNotNull{it.friendId},treatMode=treat?.let{TreatSplitMode.valueOf(it.mode)}?:TreatSplitMode.EQUAL,treatWeights=treat?.let{entryDraftJson.decodeFromString<Map<String,String>>(it.weightsJson)}?:emptyMap(),id=event.id,kind=EntryKind.TRANSFER,accountId=event.accountId,toAccountId=toAccountId,
            amount=Money(Currency.valueOf(event.currency),event.amountMinor).decimal.toPlainString(),
            toAmount=Money(Currency.valueOf(toCurrency),transfer?.toMinor?:toPosting?.deltaMinor?:0).decimal.toPlainString(),
            cardPaymentChoice=event.cardPaymentChoice,cardPaymentPeriods=event.cardPaymentPeriods?.toString().orEmpty(),cardPaymentMonth=event.cardPaymentMonth.orEmpty(),cardPaymentFee=event.cardPaymentFeeMinor?.let{Money(Currency.valueOf(event.currency),it).decimal.toPlainString()}.orEmpty(),date=event.date,toDate=transfer?.toDate?:toPosting?.date?:event.date,
            fromConfirmed=transfer?.fromConfirmed?:sourcePosting?.confirmed?:false,toConfirmed=transfer?.toConfirmed?:toPosting?.confirmed?:false,
            merchant=event.merchant,merchantId=eventMerchants.find{it.eventId==event.id}?.merchantId,note=event.note,rateSource=settlements.firstOrNull{it.eventId==event.id}?.rateSource?:"manual",referenceRates=settlements.firstOrNull{it.eventId==event.id}?.referenceRatesJson?.let{entryDraftJson.decodeFromString(it)}?:emptyList(),manualRate=transfer?.manualRate.orEmpty(),rateDate=transfer?.rateDate?:event.date,
            feeAmount=component?.let{Money(Currency.valueOf(it.originalCurrency),it.originalMinor).decimal.toPlainString()}.orEmpty(),
            feeCurrency=component?.let{Currency.valueOf(it.originalCurrency)},
            feeAccountAmount=component?.let{Money(Currency.valueOf(it.currency),it.amountMinor).decimal.toPlainString()}.orEmpty(),
            feeBaseAmount=component?.let{Money(Currency.valueOf(book.currency),it.baseMinor).decimal.toPlainString()}.orEmpty(),
            feePosition=component?.let{TransferFeePosition.valueOf(it.position)}?:TransferFeePosition.INCLUDED_FROM,
            feeAccountId=component?.accountId,feeDate=component?.date?:event.date,feeConfirmed=component?.confirmed?:false)
    }
fun BookSnapshot.refundDraft(original:EventEntity,date:String):EntryDraft {
        val settlement=settlements.find{it.eventId==original.id}
        val refundIds=settlements.filter{it.originalEventId==original.id}.map{it.eventId}.toSet()
        val remaining=original.amountMinor-visibleEvents.filter{it.id in refundIds}.sumOf{it.amountMinor}
        require(remaining>0){"已无可退款金额 / 返金可能額がありません"}
        val account=accounts.first{it.id==original.accountId}
        fun proportion(value:Long)=value.toBigDecimal().multiply(remaining.toBigDecimal()).divide(original.amountMinor.toBigDecimal(),0,java.math.RoundingMode.HALF_UP).longValueExact()
        return EntryDraft(kind=EntryKind.REFUND,categoryId=original.categoryId,accountId=original.accountId,
            originalEventId=original.id,originalCurrency=Currency.valueOf(original.currency),
            amount=Money(Currency.valueOf(original.currency),remaining).decimal.toPlainString(),
            accountAmount=Money(Currency.valueOf(account.currency),proportion(settlement?.accountMinor?:original.amountMinor)).decimal.toPlainString(),
            baseAmount=Money(Currency.valueOf(book.currency),proportion(original.baseMinor)).decimal.toPlainString(),
            date=date,merchant=original.merchant)
    }
fun BookSnapshot.newEntryDraft(kind:EntryKind,date:String,activityId:String?=null,treatFriendId:String?=null,merchantId:String?=null):EntryDraft {
    if(kind==EntryKind.TRANSFER)return newTransferDraft(date)
    val sceneId=if(kind==EntryKind.EXPENSE)activityId?:features?.takeIf{it.activityMode}?.activeActivityId else null
    val scene=activities.find{it.id==sceneId}
    val day=LocalDate.parse(date)
    val actualDate=scene?.let{day.coerceIn(LocalDate.parse(it.startDate),LocalDate.parse(it.endDate))}?:day
    val merchant=merchantId?.let{id->merchants.first{it.id==id&&it.active}}
    return withRememberedCardPayment(EntryDraft(merchant=merchant?.name.orEmpty(),merchantId=merchant?.id,kind=kind,date=actualDate.toString(),accountId=defaultEntryAccount(kind),activityId=sceneId,treatEnabled=treatFriendId!=null,treatFriends=listOfNotNull(treatFriendId)))
}
