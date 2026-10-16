package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.data.repository.BookSnapshot
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.domain.ledger.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*

@Composable internal fun ManualRateEditor(s:BookSnapshot,d:EntryDraft,vm:AppViewModel,onClose:()->Unit) {
    val account=s.accounts.find{it.id==d.accountId}
    val target=Currency.valueOf(account?.currency?:s.book.currency)
    val original=d.originalCurrency?:target
    var rate by rememberSaveable(s.book.id,d.id){mutableStateOf(d.manualRate.ifBlank{
        if(original==target)"1" else runCatching{exchangeRate(Money.parse(d.amount,original),Money.parse(d.accountAmount,target)).toPlainString()}.getOrDefault("")
    })}
    var base by rememberSaveable(s.book.id,d.id){mutableStateOf(d.baseAmount)}
    var estimate by rememberSaveable(s.book.id,d.id){mutableStateOf(false)}
    var date by rememberSaveable(s.book.id,d.id){mutableStateOf(d.rateDate)}
    var owner by remember{mutableStateOf<Int?>(null)}
    YSheet(word("换算资料 · 保留历史金额","換算情報・履歴額を保持"),onClose) {
        Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            RecordFacts(listOf(word("方向","方向") to "1 ${original.name} → ${target.name}",word("来源","出典") to word("手动填写","手入力")),audit=true)
            if(original!=target)ReferenceRatePanel(vm.referenceRates,original,target,date,enabled=!d.settlementConfirmed,onUse={q->vm.changeEntry(s.book.id,applyReferenceAccountAmount(d,target,q));onClose()})
            if(target.name!=s.book.currency)ReferenceRatePanel(vm.referenceRates,target,Currency.valueOf(s.book.currency),date,onUse={q->vm.changeEntry(s.book.id,applyReferenceBaseAmount(d,target,Currency.valueOf(s.book.currency),q));onClose()})
            YField(word("手动汇率 / 仅预填预计金额","手動レート・見込額の仮入力のみ"),rate,{rate=it},numeric=true)
            if(target.name!=s.book.currency)YField(word("该笔本位币统计金额","この取引の基準通貨集計額")+" · "+s.book.currency,base,{base=it},numeric=true)
            YDateFields(listOf(word("汇率日期","レートの日付") to date),owner,{owner=it},{_,value->date=value})
            if(original!=target)YToggle(word("用此汇率重新估算入账","このレートで計上額を再見積もり"),estimate,{estimate=it})
            YText(word("只有主动开启才改变预计金额；保存换算资料不会自动确认实际入账。","オンにした場合だけ見込額を変更。換算情報の保存で実計上を自動確認しません。"),11,color=LocalYohaku.current.muted)
            YButton(word("保存换算资料，返回记账","換算情報を保持して記録へ"),{
                var changed=d
                vm.execute(onSuccess={vm.changeEntry(s.book.id,changed);onClose()}) {
                    val parsed=rate.toBigDecimalOrNull()?:error("请输入有效汇率 / 有効なレートを入力してください")
                    require(parsed.signum()>0){"汇率应大于零 / レートは0より大きくしてください"}
                    require(original!=target||parsed.compareTo(java.math.BigDecimal.ONE)==0){"相同币种汇率为1 / 同じ通貨のレートは1です"}
                    java.time.LocalDate.parse(date)
                    changed=d.copy(manualRate=rate,baseAmount=base,rateDate=date,rateSource="manual",referenceAccountLinked=false,referenceBaseLinked=false,referenceExpectedAccountMinor=null,referenceExpectedBaseMinor=null,referenceRates=emptyList())
                    if(estimate){
                        val amount=Money.parse(d.amount,original).convert(target,parsed).decimal.toPlainString()
                        changed=if(original==target)changed.copy(amount=amount,settlementConfirmed=false)else changed.copy(accountAmount=amount,settlementConfirmed=false)
                    }
                    if(target.name!=s.book.currency)Money.parse(base,Currency.valueOf(s.book.currency))
                }
            },primary=true)
        }
    }
}
