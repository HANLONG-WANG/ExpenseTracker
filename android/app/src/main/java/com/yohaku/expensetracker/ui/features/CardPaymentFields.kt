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
    "INSTALLMENT"->word("分期","分割払い")
    "REVOLVING"->word("リボ","リボ払い")
    "ACCOUNT_RULE"->word("采用本卡自动リボ设置","カードの自動リボ設定を適用")
    else->word("尚未确认","未確認")
}
@Composable internal fun CardPaymentFields(d:EntryDraft,onChange:(EntryDraft)->Unit) {
    YSelect(word("平台当前付款方式","現在の支払方法"),d.cardPaymentChoice,listOf(null to word("尚未确认","未確認"))+CardPaymentChoice.entries.map{it.name as String? to cardPaymentLabel(it.name)},{onChange(d.copy(cardPaymentChoice=it,cardPaymentRecheck=false,cardPaymentPeriods=if(it=="INSTALLMENT")d.cardPaymentPeriods else ""))},Modifier.testTag("card-payment-choice"))
    if(d.id!=null&&d.cardPaymentChoice=="ACCOUNT_RULE")YToggle(word("明确重新核对本次自动方式","今回の自動方式を再確認"),d.cardPaymentRecheck,{onChange(d.copy(cardPaymentRecheck=it))},Modifier.testTag("card-payment-recheck"))
    if(d.cardPaymentChoice=="INSTALLMENT")YField(word("分期次数","分割回数"),d.cardPaymentPeriods,{onChange(d.copy(cardPaymentPeriods=it))},Modifier.testTag("card-payment-periods"),numeric=true)
    YText(word("记录平台当前方式，未确认可留空；预测不代表已办理变更。","現在の方式を記録。未確認は未選択のままにし、予測と手続き済みの変更を区別します。"),11,color=LocalYohaku.current.muted)
}
@Composable internal fun CardPaymentFacts(e:EventEntity,rule:FinancialRuleEntity?=null) {
    if(e.cardPaymentChoice==null)return
    RecordFacts(listOf(word("记录的付款方式","記録した支払方法") to cardPaymentLabel(e.cardPaymentChoice),word("采用的方式","適用した方式") to cardPaymentLabel(e.cardPaymentMode))+listOfNotNull(e.cardPaymentPeriods?.let{word("分期次数","分割回数") to it.toString()}))
    if(e.cardPaymentChoice=="ACCOUNT_RULE")RecordFacts(listOf(word("采用规则版本","適用した条件の版") to (rule?.let{"v${it.version} · ${it.effectiveFrom}"}?:word("尚未匹配规则","条件は未確定"))))
    if(e.cardPaymentChoice=="ACCOUNT_RULE")YText(word("保留消费时采用的规则版本；之后修改自动リボ设置不会重写此记录。","利用時に適用した条件の版を保持し、その後の自動リボ設定変更では書き換えません。"),11,color=LocalYohaku.current.muted)
}
