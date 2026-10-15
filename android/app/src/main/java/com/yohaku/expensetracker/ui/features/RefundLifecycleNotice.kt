package com.yohaku.expensetracker.ui.features

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.yohaku.expensetracker.data.db.EventEntity
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.theme.*

@Composable internal fun RefundLifecycleNotice(s:BookSnapshot,event:EventEntity,restoring:Boolean=false) {
    val refunds=s.linkedLifecycleRefunds(event,restoring)
    if(refunds.isEmpty())return
    ThemeAuditPanel(if(restoring)word("同时恢复关联退款","関連する返金も復元")else word("同时移除关联退款","関連する返金も移動"),Modifier.testTag("refund-lifecycle")) {
        RecordFacts(refunds.map{refund->
            val settlement=s.settlements.first{it.eventId==refund.id}
            (refund.date+" · "+Money(Currency.valueOf(refund.currency),refund.amountMinor).display()) to
                ((if(settlement.confirmed)word("已到账","入金済")else word("待到账","未入金"))+" · "+s.accounts.first{it.id==refund.accountId}.name+" · "+Money(Currency.valueOf(settlement.accountCurrency),settlement.accountMinor).display())
        },audit=true)
    }
}
