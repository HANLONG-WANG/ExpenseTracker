package com.yohaku.expensetracker.ui.features

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.yohaku.expensetracker.data.db.EventEntity
import com.yohaku.expensetracker.data.db.FinancialRuleEntity
import com.yohaku.expensetracker.data.repository.CardPaymentChoice
import com.yohaku.expensetracker.domain.model.EntryDraft
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*

@Composable internal fun cardPaymentLabel(value:String?)=when(value){
    "SINGLE"->word("一次付","一括払い")
    "BONUS"->word("ボーナス一括","ボーナス一括")
    "DEFERRED"->word("指定月一次付","指定月一括")
    "INSTALLMENT"->word("分期","分割払い")
    "REVOLVING"->word("リボ","リボ払い")
    "ACCOUNT_RULE"->word("采用本卡自动リボ设置","カードの自動リボ設定を適用")
    else->word("尚未确认","未確認")
}
@Composable internal fun CardPaymentFields(d:EntryDraft,onChange:(EntryDraft)->Unit) {
    YSelect(word("平台当前付款方式","現在の支払方法"),d.cardPaymentChoice,listOf(null to word("尚未确认","未確認"))+CardPaymentChoice.entries.map{it.name as String? to cardPaymentLabel(it.name)},{onChange(d.copy(cardPaymentChoice=it,cardPaymentRecheck=false,cardPaymentPeriods=if(it=="INSTALLMENT")d.cardPaymentPeriods else ""))},Modifier.testTag("card-payment-choice"))
    if(d.id!=null&&d.cardPaymentChoice=="ACCOUNT_RULE")YToggle(word("明确重新核对本次自动方式","今回の自動方式を再確認"),d.cardPaymentRecheck,{onChange(d.copy(cardPaymentRecheck=it))},Modifier.testTag("card-payment-recheck"))
    if(d.cardPaymentChoice=="INSTALLMENT")YField(word("分期次数","分割回数"),d.cardPaymentPeriods,{onChange(d.copy(cardPaymentPeriods=it))},Modifier.testTag("card-payment-periods"),numeric=true)
    if(d.cardPaymentChoice=="DEFERRED") {
        YField(word("付款月（YYYY-MM）","支払月（YYYY-MM）"),d.cardPaymentMonth,{onChange(d.copy(cardPaymentMonth=it))},Modifier.testTag("card-payment-month"))
        YField(word("手续费 / 未知留空，无费用填 0","手数料・不明は空欄、無料は0"),d.cardPaymentFee,{onChange(d.copy(cardPaymentFee=it))},numeric=true)
    }
    YText(word("记录平台当前方式，未确认可留空；预测不代表已办理变更。","現在の方式を記録。未確認は未選択のままにし、予測と手続き済みの変更を区別します。"),11,color=LocalYohaku.current.muted)
}
@Composable internal fun CardPaymentFacts(e:EventEntity,rule:FinancialRuleEntity?=null,accountCurrency:String=e.currency) {
    if(e.cardPaymentChoice==null)return
    RecordFacts(listOf(word("记录的付款方式","記録した支払方法") to cardPaymentLabel(e.cardPaymentChoice),word("采用的方式","適用した方式") to cardPaymentLabel(e.cardPaymentMode))+listOfNotNull(e.cardPaymentPeriods?.let{word("分期次数","分割回数") to it.toString()}))
    e.cardPaymentMonth?.let{RecordFacts(listOf(word("付款月","支払月") to it))}
    if(e.cardPaymentChoice=="DEFERRED")RecordFacts(listOf(word("手续费","手数料") to (e.cardPaymentFeeMinor?.let{com.yohaku.expensetracker.domain.model.Money(com.yohaku.expensetracker.domain.model.Currency.valueOf(accountCurrency),it).display()}?:word("未知","不明"))))
    if(e.cardPaymentChoice in listOf("ACCOUNT_RULE","BONUS","DEFERRED"))RecordFacts(listOf(word("采用规则版本","適用した条件の版") to (rule?.let{"v${it.version} · ${it.effectiveFrom}"}?:word("尚未匹配规则","条件は未確定"))))
    if(e.cardPaymentChoice=="ACCOUNT_RULE")YText(word("保留消费时采用的规则版本；之后修改自动リボ设置不会重写此记录。","利用時に適用した条件の版を保持し、その後の自動リボ設定変更では書き換えません。"),11,color=LocalYohaku.current.muted)
}
