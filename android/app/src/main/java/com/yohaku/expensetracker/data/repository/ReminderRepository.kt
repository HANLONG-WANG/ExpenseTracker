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
        val state=financialReviewState(book.id);val s=state.ledger;val f=state.finance
        val stale=f.plans.filter{financialPredictionStale(s,f,it)}.map{it.id}.toSet()
        val timeline=s.cashTimeline(f,today,through,stale,rates)
        for(payment in s.importantPayments(f,timeline)) {
            val i=payment.item
            result+=ReminderItem(book.id,i.id,if(i.kind=="BILL")"REPAYMENT"else"RECURRING",i.scheduledDate,i.currency,payment.outstandingMinor,
                i.name,s.accounts.firstOrNull{it.id==(if(i.kind=="BILL")i.sourceId else i.accountId)}?.name.orEmpty(),
                i.estimated||i.issue!=null,targetAccountId=i.sourceId.takeIf{i.kind=="BILL"})
        }
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
