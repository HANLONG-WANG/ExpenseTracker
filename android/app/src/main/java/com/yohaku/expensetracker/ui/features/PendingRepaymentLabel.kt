package com.yohaku.expensetracker.ui.features

import androidx.compose.runtime.Composable
import com.yohaku.expensetracker.data.repository.BookSnapshot
import com.yohaku.expensetracker.data.repository.pendingRepaymentPaidAmounts
import com.yohaku.expensetracker.ui.theme.word

@Composable internal fun pendingRepaymentLabel(s:BookSnapshot,accountId:String,asOf:String):String? {
    val paid=s.pendingRepaymentPaidAmounts(accountId,asOf)
    if(paid.isEmpty())return null
    // Amounts keep their payment currency symbol; the debt above stays unchanged until clearing.
    return word("已付待确认：","支払済み・確認待ち：")+paid.joinToString(" / "){it.display()}
}
