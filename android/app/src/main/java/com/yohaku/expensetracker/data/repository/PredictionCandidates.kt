package com.yohaku.expensetracker.data.repository

import com.yohaku.expensetracker.data.db.EventEntity
import com.yohaku.expensetracker.domain.finance.*
import java.time.LocalDate

/** Candidate dates are suggestions; neither this function nor the command protocol asserts platform facts. */
fun predictionCandidates(mode:FinanceMode,count:Int,start:LocalDate,firstDate:LocalDate,firstEnd:LocalDate,
    rule:FinancialRuleVersion?,event:EventEntity?=null):List<Pair<String,AccrualWindow>> {
    val n=if(mode==FinanceMode.SINGLE)1 else count
    require(n in 1..1200){"请输入1至1200期 / 1〜1200回を入力してください"}
    val payment=if(mode==FinanceMode.SINGLE&&rule?.billing?.issues()?.isEmpty()==true)
        event?.takeIf{it.cardPaymentChoice in listOf("BONUS","DEFERRED")}?.let{recordedSinglePaymentDate(it,rule)}?:billingDates(start,rule.billing).paymentDate
        else firstDate
    val interest=rule?.revolving?.interest
    return List(n){i->
        val previous=firstEnd.plusMonths(i-1L)
        val nextStart=if(i==0)start else if(interest?.frequency==ChargeFrequency.MONTHLY||interest?.includeStart==true&&interest.includeEnd==true)previous.plusDays(1)
            else if(interest?.includeStart==false&&interest.includeEnd==false)previous.minusDays(1)else previous
        payment.plusMonths(i.toLong()).toString() to AccrualWindow(nextStart.toString(),firstEnd.plusMonths(i.toLong()).toString())
    }
}
