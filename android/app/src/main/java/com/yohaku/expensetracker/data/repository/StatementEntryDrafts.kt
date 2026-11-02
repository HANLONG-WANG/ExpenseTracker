package com.yohaku.expensetracker.data.repository

import com.yohaku.expensetracker.data.files.StatementRow
import com.yohaku.expensetracker.domain.model.*
import kotlinx.serialization.Serializable

@Serializable
data class StatementSupplementSeed(val bookId:String,val accountId:String,val row:StatementRow,
    val kind:EntryKind,val occurredDate:String,val postedDate:String,val originalEventId:String?=null)

/** Shared statement-to-editor preparation. Missing amounts stay empty, never zero. */
fun BookSnapshot.statementSupplementDraft(seed:StatementSupplementSeed):EntryDraft {
    val s=this
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
            val outgoing=row.direction=="out"
            require(if(outgoing)account in s.transferSources()else account in funds){"转出可选资金账户或信用卡，转入须为资金账户 / 出金は資金口座・カード、入金は資金口座を選択してください"}
            EntryDraft(kind=EntryKind.TRANSFER,accountId=if(outgoing)account.id else null,
                toAccountId=if(outgoing)null else account.id,amount=if(outgoing)row.settlement.amount else "",
                toAmount=if(outgoing)""else row.settlement.amount,date=seed.postedDate,toDate=seed.postedDate,
                fromConfirmed=outgoing,toConfirmed=!outgoing,note=row.summary)
        }
        else->error("请选择对应金融表单 / 対応する金融フォームを選択してください")
    }
    return s.withRememberedCardPayment(d)
}

@Serializable
data class StatementActualSeed(val eventId:String,val accountId:String,val row:StatementRow,val postedDate:String)

fun BookSnapshot.statementActualDraft(seed:StatementActualSeed):EntryDraft {
    java.time.LocalDate.parse(seed.postedDate)
    val event=visibleEvents.first{it.id==seed.eventId}
    val account=accounts.first{it.id==seed.accountId}
    require(account.currency==seed.row.settlement.currency)
    val current=editingEntryDraft(event)
    val outgoing=when(event.kind){"EXPENSE"->true;"INCOME","REFUND"->false;"TRANSFER"->current.accountId==account.id;else->error("借入和还款请使用金融表单 / 借入・返済は金融フォームを使用してください")}
    require(seed.row.direction==if(outgoing)"out"else"in"){"流水方向与记录不一致 / 明細と記録の方向が一致しません"}
    return if(event.kind==EntryKind.TRANSFER.name) {
        if(current.accountId==account.id)current.copy(amount=seed.row.settlement.amount,date=seed.postedDate,fromConfirmed=true)
        else {require(current.toAccountId==account.id);current.copy(toAmount=seed.row.settlement.amount,toDate=seed.postedDate,toConfirmed=true)}
    }else {
        require(event.kind in listOf("EXPENSE","INCOME","REFUND")&&event.accountId==account.id)
        current.copy(amount=if(event.currency==account.currency)seed.row.settlement.amount else current.amount,
            date=if(event.kind==EntryKind.REFUND.name)seed.postedDate else current.date,accountAmount=seed.row.settlement.amount,settlementDate=seed.postedDate,settlementConfirmed=true,
            baseAmount=if(seed.row.settlement.currency==book.currency)seed.row.settlement.amount else "",rateSource="statement",rateDate=seed.postedDate)
    }
}
