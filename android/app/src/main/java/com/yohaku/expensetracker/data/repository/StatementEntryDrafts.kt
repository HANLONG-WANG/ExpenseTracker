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
data class StatementActualSeed(val eventId:String,val accountId:String,val row:StatementRow,val postedDate:String,val postingId:String?=null)

fun BookSnapshot.statementActualDraft(seed:StatementActualSeed):EntryDraft {
    java.time.LocalDate.parse(seed.postedDate)
    val event=visibleEvents.first{it.id==seed.eventId}
    val account=accounts.first{it.id==seed.accountId}
    require(account.currency==seed.row.settlement.currency)
    val current=editingEntryDraft(event)
    val posting=statementPosting(seed.eventId,seed.accountId,seed.row,seed.postingId)
    return if(event.kind==EntryKind.TRANSFER.name) {
        when(posting.id) {
            event.id+":from"->current.copy(amount=seed.row.settlement.amount,date=seed.postedDate,fromConfirmed=true)
            event.id+":to"->current.copy(toAmount=seed.row.settlement.amount,toDate=seed.postedDate,toConfirmed=true)
            event.id+":fee"->{
                require(current.feePosition==TransferFeePosition.SEPARATE)
                current.copy(feeAmount=if(current.feeCurrency?.name==account.currency)seed.row.settlement.amount else current.feeAmount,
                    feeAccountAmount=seed.row.settlement.amount,feeDate=seed.postedDate,feeConfirmed=true,
                    feeBaseAmount=if(account.currency==book.currency)seed.row.settlement.amount else "")
            }
            else->error("转账资金端不存在 / 振替の資金側がありません")
        }
    }else {
        require(event.kind in listOf("EXPENSE","INCOME","REFUND")&&event.accountId==account.id)
        current.copy(amount=if(event.currency==account.currency)seed.row.settlement.amount else current.amount,
            accountAmount=seed.row.settlement.amount,settlementDate=seed.postedDate,settlementConfirmed=true,
            baseAmount=if(seed.row.settlement.currency==book.currency)seed.row.settlement.amount else "",rateSource="statement",rateDate=seed.postedDate)
    }
}

/** Missing identity is accepted only when account and direction identify exactly one posting. */
fun BookSnapshot.statementPosting(eventId:String,accountId:String,row:StatementRow,postingId:String?=null):com.yohaku.expensetracker.data.db.PostingEntity {
    val account=accounts.first{it.id==accountId}
    val candidates=storedPostings.filter{p->p.eventId==eventId&&p.accountId==accountId&&p.currency==row.settlement.currency&&p.deltaMinor!=0L&&
        (if(if(AccountType.valueOf(account.type).debt)p.deltaMinor>0 else p.deltaMinor<0)"out"else"in")==row.direction&&
        (postingId==null||p.id==postingId)}
    require(candidates.size==1){"请指定与流水账户、币种和方向一致的具体资金端 postingId / 明細の口座・通貨・方向に一致する資金側 postingId を指定してください"}
    return candidates.single()
}

/** Validate after adapting the draft, including derived same-currency amounts. */
fun BookSnapshot.requireStatementSupplementFacts(seed:StatementSupplementSeed,draft:EntryDraft) {
    val row=seed.row;val currency=Currency.valueOf(row.settlement.currency)
    fun same(amount:String)=Money.parse(amount,currency)==Money.parse(row.settlement.amount,currency)
    val valid=if(seed.kind==EntryKind.TRANSFER) {
        if(row.direction=="out")draft.accountId==seed.accountId&&same(draft.amount)&&draft.date==seed.postedDate&&draft.fromConfirmed
        else draft.toAccountId==seed.accountId&&same(draft.toAmount)&&draft.toDate==seed.postedDate&&draft.toConfirmed
    }else {
        val originalCurrency=draft.originalCurrency?:currency
        draft.accountId==seed.accountId&&same(if(originalCurrency==currency)draft.amount else draft.accountAmount)&&
            draft.date==seed.occurredDate&&draft.settlementDate==seed.postedDate&&draft.settlementConfirmed&&!draft.historicalIncluded&&
            (if(seed.kind==EntryKind.EXPENSE)"out"else"in")==row.direction&&
            (row.original==null||Money.parse(draft.amount,originalCurrency)==Money.parse(row.original.amount,Currency.valueOf(row.original.currency)))
    }
    require(draft.kind==seed.kind&&draft.originalEventId==seed.originalEventId&&valid){"不能改写流水已知的金额、币种、日期或方向 / 明細の既知の金額・通貨・日付・方向は変更できません"}
}
