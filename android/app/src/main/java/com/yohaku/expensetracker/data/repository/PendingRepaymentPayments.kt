package com.yohaku.expensetracker.data.repository

import com.yohaku.expensetracker.domain.model.Money

/** Actual payments awaiting clearing, including payments not yet allocated to a bill.
 * These source-currency amounts are not cleared principal or a deduction from debt.
 */
fun BookSnapshot.pendingRepaymentPaidAmounts(accountId:String,asOf:String):List<Money> {
    val ids=repayments.filter{it.debtAccountId==accountId&&!it.platformConfirmed}.map{it.eventId}.toSet()
    return visibleEvents.filter{it.id in ids&&it.date<=asOf}.map{accountMoney(it)}
        .groupBy{it.currency}.map{(currency,amounts)->Money(currency,amounts.fold(0L){total,amount->Math.addExact(total,amount.minor)})}
}
