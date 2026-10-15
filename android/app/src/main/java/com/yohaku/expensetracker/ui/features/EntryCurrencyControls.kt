package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.domain.ledger.exchangeRate
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*

/** Original/settlement controls from entryCurrencyFields; all values survive draft restoration. */
@Composable internal fun EntryCurrencyControls(d:EntryDraft,accountCurrency:String,onChange:(EntryDraft)->Unit) {
    val original=d.originalCurrency?.name ?: accountCurrency
    val foreign=original!=accountCurrency
    Column(Modifier.fillMaxWidth().padding(top=12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        YSelect(word("消费 / 收付原币","取引の原通貨"),original,Currency.entries.map{it.name as String? to it.name},{selected->
            val currency=Currency.valueOf(selected!!)
            val accountAmount=if(foreign)d.accountAmount else d.amount
            onChange(if(selected==accountCurrency)d.copy(originalCurrency=currency,amount=accountAmount,accountAmount="",settlementConfirmed=true)
                else d.copy(originalCurrency=currency,amount=if(foreign)d.amount else "",accountAmount=accountAmount,settlementConfirmed=false))
        })
        if(foreign) {
            YField(word("原币金额","原通貨額")+" · "+original,d.amount,{onChange(d.copy(amount=it))},numeric=true)
            CurrencyEntryNote {
                YText(word("两种金额，仍是一笔记录","二つの金額、同じ一件"),12)
                YText(word("保留原币价格；预算按入账金额折算。已确认金额不会随参考汇率变化。","原通貨の価格を保持。予算は計上額から換算。実額は参考レートで変更しません。"),if(LocalYohaku.current.id=="journal")11 else 9,color=LocalYohaku.current.muted,lineHeightMultiplier=1.9f,cssLineBox=true)
                val rate=runCatching{exchangeRate(Money.parse(d.amount,Currency.valueOf(original)),Money.parse(d.accountAmount,Currency.valueOf(accountCurrency)))}.getOrNull()
                if(rate!=null)YText("1 $original = ${rate.toPlainString()} $accountCurrency",12,number=true)
                YSelect(word("入账金额状态","計上額の状態"),if(d.settlementConfirmed)"confirmed" else "pending",listOf(
                    "confirmed" as String? to word("已确认实际金额","実額確認済"),"pending" as String? to word("预计换算 · 待确认","換算見込み・確認待ち")
                ),{onChange(d.copy(settlementConfirmed=it=="confirmed"))})
            }
        }
    }
}
