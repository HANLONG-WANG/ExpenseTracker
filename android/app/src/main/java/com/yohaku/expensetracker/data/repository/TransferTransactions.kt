package com.yohaku.expensetracker.data.repository

import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.domain.model.*
import java.time.LocalDate
import kotlinx.serialization.encodeToString

internal suspend fun LedgerRepository.prepareTransfer(bookId:String,d:EntryDraft):PreparedLedgerChange {
    val book=dao.book(bookId)?:error("账本不存在 / 帳簿がありません")
    val from=dao.account(bookId,d.accountId?:"")?:error("请选择转出账户 / 出金口座を選択してください")
    val to=dao.account(bookId,d.toAccountId?:"")?:error("请选择转入账户 / 入金口座を選択してください")
    require(from.id!=to.id){"请选择两个不同账户 / 別の口座を選択してください"}
    require(from.active&&to.active&&!AccountType.valueOf(from.type).debt&&!AccountType.valueOf(to.type).debt){"转账请选择资金账户 / 振替は資金口座を選択してください"}
    val source=Money.parse(d.amount,Currency.valueOf(from.currency))
    val target=Money.parse(d.toAmount,Currency.valueOf(to.currency))
    require(source.minor>0&&target.minor>0){"两端金额应大于零 / 両側の金額は0より大きくしてください"}
    LocalDate.parse(d.date);LocalDate.parse(d.toDate);LocalDate.parse(d.rateDate)
    val old=d.id?.let{dao.event(bookId,it)?:error("原转账不存在 / 元の振替がありません")}
    require(old==null||old.kind==EntryKind.TRANSFER.name&&old.deletedAt==null)
    val event=EventEntity(bookId,old?.id?:id(),EntryKind.TRANSFER.name,from.id,null,source.minor,source.currency.name,0,d.date,d.merchant.trim(),d.note,
        revision=(old?.revision?:0)+1,createdAt=old?.createdAt?:clock.millis())
    val previous=old?.let{dao.transfer(bookId,it.id)}
    val priorSettlement=old?.let{dao.settlement(bookId,it.id)}
    val transfer=TransferEntity(bookId,event.id,to.id,target.minor,from.currency,to.currency,d.date,d.toDate,d.fromConfirmed,d.toConfirmed,
        previous?.expectedToMinor?:d.referenceExpectedToMinor?:target.minor,previous?.expectedToCurrency?:to.currency,d.manualRate,d.rateDate)
    val settlement=SettlementEntity(bookId,event.id,source.minor,from.currency,d.fromConfirmed,d.date,d.rateSource,d.rateDate,
        priorSettlement?.expectedAccountMinor?:d.referenceExpectedFromMinor?:source.minor,0,manualRate=d.manualRate,expectedAccountCurrency=priorSettlement?.expectedMoney()?.currency?.name?:from.currency,referenceRatesJson=d.referenceRates.takeIf{it.isNotEmpty()}?.let{json.encodeToString(it)})
    val postings=mutableListOf(
        PostingEntity(bookId,event.id+":from",event.id,from.id,-source.minor,from.currency,d.date,d.fromConfirmed),
        PostingEntity(bookId,event.id+":to",event.id,to.id,target.minor,to.currency,d.toDate,d.toConfirmed))
    val components=if(d.feeAmount.isBlank())emptyList()else {
        val payer=when(d.feePosition){TransferFeePosition.INCLUDED_FROM->from;TransferFeePosition.DEDUCTED_TO->to;TransferFeePosition.SEPARATE->dao.account(bookId,d.feeAccountId?:"")?:error("请选择手续费账户 / 手数料の口座を選択してください")}
        require(payer.active)
        val originalFee=Money.parse(d.feeAmount,d.feeCurrency?:Currency.valueOf(payer.currency))
        val fee=if(originalFee.currency.name==payer.currency)originalFee else Money.parse(d.feeAccountAmount,Currency.valueOf(payer.currency))
        require(originalFee.minor>0&&fee.minor>0){"已知手续费应大于零；没有明细时留空 / 手数料は0より大きく、不明なら空欄にしてください"}
        if(d.feePosition==TransferFeePosition.INCLUDED_FROM)require(fee.minor<source.minor){"手续费不能占据全部转出金额 / 手数料は出金総額未満にしてください"}
        val base=if(payer.currency==book.currency)fee else Money.parse(d.feeBaseAmount,Currency.valueOf(book.currency))
        require(base.minor>0)
        val date=when(d.feePosition){TransferFeePosition.INCLUDED_FROM->d.date;TransferFeePosition.DEDUCTED_TO->d.toDate;TransferFeePosition.SEPARATE->d.feeDate}
        LocalDate.parse(date)
        val confirmed=when(d.feePosition){TransferFeePosition.INCLUDED_FROM->d.fromConfirmed;TransferFeePosition.DEDUCTED_TO->d.toConfirmed;TransferFeePosition.SEPARATE->d.feeConfirmed}
        if(d.feePosition==TransferFeePosition.SEPARATE)postings+=PostingEntity(bookId,event.id+":fee",event.id,payer.id,
            if(AccountType.valueOf(payer.type).debt)fee.minor else -fee.minor,payer.currency,date,confirmed)
        listOf(ExpenseComponentEntity(bookId,event.id+":fee",event.id,payer.id,fee.minor,payer.currency,base.minor,date,confirmed,d.feePosition.name,originalFee.minor,originalFee.currency.name))
    }
    return PreparedLedgerChange(old,event,settlement,postings,transfer,components)
}

fun TransferEntity.inTransit()=fromConfirmed&&!toConfirmed
fun BookSnapshot.inTransitTransfers()=transfers.filter{it.inTransit()&&visibleEvents.any{e->e.id==it.eventId}}
