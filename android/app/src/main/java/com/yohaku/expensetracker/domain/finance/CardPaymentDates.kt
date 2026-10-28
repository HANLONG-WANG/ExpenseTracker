package com.yohaku.expensetracker.domain.finance

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.MonthDay
import java.time.YearMonth

@Serializable data class BonusPaymentWindow(val startMonth:Int,val startDay:Int,val endMonth:Int,val endDay:Int,val paymentMonth:Int,val paymentDay:Int) {
    fun valid()=runCatching { MonthDay.of(startMonth,startDay);MonthDay.of(endMonth,endDay);paymentMonth in 1..12&&paymentDay in 1..31 }.getOrDefault(false)
    fun contains(day:MonthDay):Boolean {
        val start=MonthDay.of(startMonth,startDay);val end=MonthDay.of(endMonth,endDay)
        return if(start<=end)day in start..end else day>=start||day<=end
    }
}
fun bonusPaymentIssues(windows:List<BonusPaymentWindow>):List<String> {
    if(windows.size>2||windows.any{!it.valid()})return listOf("bonus.dates")
    if(windows.size==2&&(0L..365L).any{day->val date=MonthDay.from(LocalDate.of(2024,1,1).plusDays(day));windows.all{it.contains(date)}})return listOf("bonus.overlap")
    return emptyList()
}
fun bonusPaymentDate(date:LocalDate,windows:List<BonusPaymentWindow>):LocalDate? {
    require(bonusPaymentIssues(windows).isEmpty())
    val window=windows.firstOrNull{it.contains(MonthDay.from(date))}?:return null
    var end=MonthDay.of(window.endMonth,window.endDay).atYear(date.year)
    if(end<date)end=MonthDay.of(window.endMonth,window.endDay).atYear(date.year+1)
    var payment=dayInMonth(YearMonth.of(end.year,window.paymentMonth),window.paymentDay)
    if(payment<=end)payment=dayInMonth(YearMonth.of(end.year+1,window.paymentMonth),window.paymentDay)
    return payment
}
fun deferredPaymentDate(date:LocalDate,month:YearMonth,billing:BillingRule):LocalDate {
    require(month>=YearMonth.from(billingDates(date,billing).paymentDate)){"指定付款月不能早于正常一次付月份 / 指定月は通常の一括支払月以降にしてください"}
    return dayInMonth(month,billing.paymentDay!!)
}
fun paymentRecoveryDate(date:LocalDate,delayDays:Int):LocalDate {
    require(delayDays>=0)
    return date.plusDays(delayDays.toLong())
}
