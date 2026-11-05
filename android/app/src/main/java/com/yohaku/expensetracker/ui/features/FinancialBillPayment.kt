package com.yohaku.expensetracker.ui.features

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.yohaku.expensetracker.data.db.EventEntity
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*

@Composable internal fun FinancialBillPayment(s:BookSnapshot,row:FinancialStatementRow,onPay:(String)->Unit,onPayment:(EventEntity)->Unit,prefix:String="statement") {
    val bill=row.bill
    val allocated=s.repaymentAllocations.filter{it.billId==bill.id}.map{it.eventId}.toSet()
    val payments=s.visibleEvents.filter{it.id in allocated}
    val pending=s.repayments.any{it.eventId in allocated&&!it.platformConfirmed&&payments.any{e->e.id==it.eventId}}
    val cleared=row.outstanding.paidPrincipalMinor+row.outstanding.paidFeeMinor
    YText(if(bill.confirmed)word("金额已确认","金額確定")else word("金额未确认 · 规则预测","金額未確定・条件による予測"),12,Modifier.testTag("$prefix-amount-status-${bill.id}"),color=LocalYohaku.current.muted)
    YText(when {
        row.outstanding.totalMinor<0->word("已付超过账单，请核对","請求額を超える支払・要確認")
        row.outstanding.settled->word("已结清","精算済み")
        pending&&row.cashRequiredMinor==0L->word("已付款，待平台确认","支払済み・返済先の確認待ち")
        pending&&row.pendingTotalMinor==null->word("已有付款，清偿待确认","支払記録あり・返済確定待ち")
        pending->word("部分付款 / 清偿待确认","一部支払・返済確定待ち")
        cleared>0->word("部分清偿","一部返済済み")
        else->word("未付款","未払い")
    },13,Modifier.testTag("$prefix-payment-status-${bill.id}"),color=LocalYohaku.current.accent)
    if(pending) {
        val currency=Currency.valueOf(row.plan.currency)
        val unknown=word("未知，请先核对已有付款","不明・先に既存支払を確認")
        fun value(n:Long?)=n?.let{Money(currency,it).display()}?:unknown
        YText(word("已付待确认：","支払済み・確認待ち：")+value(row.pendingTotalMinor)+word("；仍需付款：","・今後の支払：")+value(row.cashRequiredMinor),11)
    }
    payments.forEach{e->YButton(word("查看付款","支払を見る")+" · "+e.date,{onPayment(e)},Modifier.testTag("$prefix-payment-${bill.id}-${e.id}"),inline=true)}
    if(row.outstanding.totalMinor>0&&row.cashRequiredMinor!=0L)YButton(
        if(pending&&row.cashRequiredMinor==null)word("核对后记录其他还款","確認して別の返済を記録")else word("记录还款","返済を記録"),
        {onPay(bill.id)},Modifier.testTag(if(prefix=="statement")"statement-repay-${bill.id}"else"finance-pay-bill-${bill.id}"),primary=true)
}
