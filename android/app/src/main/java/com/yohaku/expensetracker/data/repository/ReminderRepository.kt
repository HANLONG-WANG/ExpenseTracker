package com.yohaku.expensetracker.data.repository

import androidx.room.withTransaction
import com.yohaku.expensetracker.domain.reminders.ReminderItem
import java.time.LocalDate
import kotlinx.coroutines.flow.first

/** Uses the same financial facts, stale-plan checks and saved rates as the important-items page. */
suspend fun LedgerRepository.reminderItems(preferences:AppPreferences):List<ReminderItem> = db.withTransaction {
    val result=mutableListOf<ReminderItem>()
    val today=LocalDate.now(clock);val through=today.plusDays(366)
    val rates=preferences.referenceRateCache().rates
    for(book in books.first().filter{it.initialized&&it.deletedAt==null}) {
        val month=java.time.YearMonth.from(today)
        val reviewMonth=month.minusMonths(1).toString()
        if(reviewMonth>=book.startDate.take(7)&&!book.archived&&db.monthlyDao().review(book.id,reviewMonth)==null)
            result+=ReminderItem(book.id,"monthly-review:$reviewMonth","MONTHLY_REVIEW",month.atDay(1).toString(),book.currency,null,
                if(book.language=="ja")"${reviewMonth}の振り返りを送信"else"提交${reviewMonth}月度评语",targetMonth=reviewMonth)
        val state=financialReviewState(book.id);val s=state.ledger;val f=state.finance
        val stale=f.plans.filter{financialPredictionStale(s,f,it)}.map{it.id}.toSet()
        val timeline=s.cashTimeline(f,today,through,stale,rates)
        result+=s.paymentReminders(s.importantPayments(f,timeline))
        val cards=s.accounts.filter{it.type==com.yohaku.expensetracker.domain.model.AccountType.CREDIT.name}.associateBy{it.id}
        for(o in s.copy(occurrences=s.forecastOccurrences(today,through)).pendingOccurrences().filter{it.accountId in cards})
            result+=ReminderItem(book.id,"occurrence:${o.id}","RECURRING",maxOf(o.scheduledDate,today.toString()),o.currency,o.amountMinor,o.name,cards.getValue(o.accountId).name,o.estimated)
        for(row in s.financialDeadlines(f,today.toString(),through.toString())) {
            val d=row.deadline
            result+=ReminderItem(book.id,"deadline:${d.billId}:${d.changeType}","DEADLINE",d.date,row.currency,row.outstandingMinor,
                row.planName,row.accountName,zoneId=d.zoneId,deadlineInstant=d.instant().toEpochMilli(),targetAccountId=row.accountId,targetMonth=row.billDate.take(7))
        }
        // Risks use the existing 90-day view, rather than extending its advertised forecast horizon.
        val riskTimeline=s.cashTimeline(f,today,today.plusDays(90),stale,rates)
        for(risk in riskTimeline.accountRisks()) {
            val amount=risk.maximumGapMinor?:continue
            val date=risk.firstRiskDate?:continue
            val a=risk.opening.account
            result+=ReminderItem(book.id,"funds:${a.id}","FUNDS",date,a.currency,amount,
                if(book.language=="ja")"引落口座の資金不足"else"扣款账户资金缺口",a.name,risk.estimated,
                risk=true,basisMinor=risk.nextNeedMinor?:0,mergeKey=risk.firstRiskItemId?:"funds:${a.id}",targetAccountId=a.id)
        }
        val pace=s.budgetPace(today,f,rates)
        val actual=pace.actualOverMinor?.takeIf{it>0}
        val amount=actual?:pace.projectedOverMinor?.takeIf{it>0}
        if(amount!=null)result+=ReminderItem(book.id,"budget:${today.toString().take(7)}","BUDGET",if(actual!=null)today.toString()else today.withDayOfMonth(today.lengthOfMonth()).toString(),book.currency,amount,
            if(book.language=="ja")"今月の予算超過"else"本月预算超支",estimated=actual==null,risk=true,basisMinor=pace.limitMinor?:0)
    }
    result
}

/** Shared bill groups feed repayment reminders; recurring purchases retain their own identity. */
internal fun BookSnapshot.paymentReminders(payments:List<ImportantPayment>):List<ReminderItem> {
    val repayments=payments.billGroups().map{group->
        val i=group.item
        val account=accounts.first{it.id==i.sourceId}.name
        // The notification already names the account; the title says what is due there.
        val title=if(group.count==1)i.name else if(book.language=="ja")"請求${group.count}件"else"${group.count}笔账单"
        ReminderItem(book.id,"repayment:${group.key.stableKey}","REPAYMENT",i.scheduledDate,i.currency,group.outstandingMinor,
            title,account,group.estimated,targetAccountId=i.sourceId)
    }
    // A card bank-debit forecast is not a reminder: the purchase is reminded on its own date,
    // and once recorded its CARD_ENTRY bill brings the repayment reminder.
    val recurring=payments.filter{it.item.kind!="BILL"&&it.item.kind!="CARD_RECURRENCE"}.map{payment->
        val i=payment.item
        ReminderItem(book.id,i.id,"RECURRING",i.scheduledDate,i.currency,payment.outstandingMinor,
            i.name,accounts.firstOrNull{it.id==i.accountId}?.name.orEmpty(),i.estimated||i.issue!=null)
    }
    return repayments+recurring
}
