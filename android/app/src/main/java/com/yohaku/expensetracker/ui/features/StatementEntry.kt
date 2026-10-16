package com.yohaku.expensetracker.ui.features

import com.yohaku.expensetracker.data.files.StatementRow
import com.yohaku.expensetracker.data.repository.BookSnapshot
import com.yohaku.expensetracker.domain.model.*
import kotlinx.serialization.Serializable

@Serializable
data class StatementSupplementSeed(val bookId:String,val accountId:String,val row:StatementRow,
    val kind:EntryKind,val occurredDate:String,val postedDate:String,val originalEventId:String?=null)

/** Only a normal unsaved form is opened. Returning/cancelling cannot create a financial fact. */
internal fun AppViewModel.startStatementSupplement(s:BookSnapshot,seed:StatementSupplementSeed) {
    require(seed.bookId==s.book.id)
    val account=s.accounts.first{it.id==seed.accountId&&it.active}
    val row=seed.row
    require(account.currency==row.settlement.currency)
    java.time.LocalDate.parse(seed.occurredDate);java.time.LocalDate.parse(seed.postedDate)
    val original=seed.originalEventId?.takeIf{seed.kind==EntryKind.REFUND}?.let{id->s.visibleEvents.first{it.id==id&&it.kind==EntryKind.EXPENSE.name}}
    val originalCurrency=original?.currency?:row.original?.currency?:row.settlement.currency
    val originalAmount=when {
        row.original?.currency==originalCurrency->row.original.amount
        originalCurrency==row.settlement.currency->row.settlement.amount
        else->""
    }
    val baseAmount=when(s.book.currency){row.settlement.currency->row.settlement.amount;originalCurrency->originalAmount;else->""}
    val funds=s.accounts.filter{it.active&&!AccountType.valueOf(it.type).debt}
    val d=when(seed.kind) {
        EntryKind.EXPENSE,EntryKind.INCOME,EntryKind.REFUND -> {
            require(seed.kind!=EntryKind.REFUND||original!=null)
            require(if(seed.kind==EntryKind.EXPENSE)row.direction=="out" else row.direction=="in")
            require(seed.kind!=EntryKind.INCOME||account in funds)
            EntryDraft(kind=seed.kind,accountId=account.id,categoryId=original?.categoryId,
                originalEventId=original?.id,originalCurrency=Currency.valueOf(originalCurrency),
                amount=originalAmount,baseAmount=baseAmount,accountAmount=row.settlement.amount,
                date=seed.occurredDate,settlementDate=seed.postedDate,settlementConfirmed=true,
                rateSource="statement",rateDate=seed.postedDate,note=row.summary,merchant=original?.merchant.orEmpty())
        }
        EntryKind.TRANSFER -> {
            require(account in funds)
            val outgoing=row.direction=="out"
            EntryDraft(kind=EntryKind.TRANSFER,accountId=if(outgoing)account.id else null,
                toAccountId=if(outgoing)null else account.id,amount=if(outgoing)row.settlement.amount else "",
                toAmount=if(outgoing)""else row.settlement.amount,date=seed.postedDate,toDate=seed.postedDate,
                fromConfirmed=outgoing,toConfirmed=!outgoing,note=row.summary)
        }
        else->error("请选择对应金融表单 / 対応する金融フォームを選択してください")
    }
    entryReturnRoute.value=route.value
    entry.value=d
    navigate(if(d.kind in listOf(EntryKind.EXPENSE,EntryKind.INCOME))"category-picker"else "entry")
    persistDraft(s.book.id)
}

@Serializable
data class StatementActualSeed(val eventId:String,val accountId:String,val row:StatementRow,val postedDate:String)

internal fun AppViewModel.startStatementActual(s:BookSnapshot,seed:StatementActualSeed) {
    val event=s.visibleEvents.first{it.id==seed.eventId}
    val account=s.accounts.first{it.id==seed.accountId}
    require(account.currency==seed.row.settlement.currency)
    if(event.kind==EntryKind.TRANSFER.name) {
        editEntry(s,event)
        val current=entry.value!!
        val next=if(current.accountId==account.id)current.copy(amount=seed.row.settlement.amount,date=seed.postedDate,fromConfirmed=true)
            else {require(current.toAccountId==account.id);current.copy(toAmount=seed.row.settlement.amount,toDate=seed.postedDate,toConfirmed=true)}
        changeEntry(s.book.id,next)
    }else {
        require(event.accountId==account.id)
        confirmActual(s,event)
        val current=entry.value!!
        changeEntry(s.book.id,current.copy(accountAmount=seed.row.settlement.amount,settlementDate=seed.postedDate,
            baseAmount=if(seed.row.settlement.currency==s.book.currency)seed.row.settlement.amount else "",rateSource="statement",rateDate=seed.postedDate))
    }
}
