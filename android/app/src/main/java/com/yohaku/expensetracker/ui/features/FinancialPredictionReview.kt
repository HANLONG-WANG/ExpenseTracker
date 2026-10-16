package com.yohaku.expensetracker.ui.features

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.finance.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*

@Composable internal fun FinancialPredictionReviewContent(r:FinancialPredictionReview,vm:AppViewModel,onSaved:()->Unit) {
    val draftSession=requireNotNull(LocalFinancialDraft.current)
    val busy by vm.busy.collectAsStateWithLifecycle()
    val refreshed by vm.financialReviewRefreshed.collectAsStateWithLifecycle()
    val currency=Currency.valueOf(r.plan.currency)
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(15.dp)) {
        if(refreshed)FinanceRefreshed()
        YText(r.plan.name,18)
        RecordFacts(listOf(word("关联本金","対象元金") to Money(currency,r.plan.principalMinor!!).display(),
            word("保留确认账期","保持する確定請求") to r.retainedConfirmed.size.toString(),word("保留有付款或已过期的原预测","支払関連・期日経過の元予測を保持") to r.retainedOther.size.toString(),word("退出当前预测的账期","現在の予測から除外する回") to r.withdrawn.size.toString()))
        val actualRemaining=Math.subtractExact(r.originalPrincipalMinor,r.facts.principalFacts().filter{it.date<=r.facts.asOf}.fold(0L){sum,p->Math.addExact(sum,p.principalMinor)})
        RecordFacts(listOf(word("计算基准日","計算基準日") to r.facts.asOf,word("实际清偿 / 退款后的来源本金","実返済・返金後の対象元金") to Money(currency,actualRemaining).display()))
        YText(word("已确认、有付款关联和已过期的原账单保留。只假设未来未付本金在对应付款日支付；逾期未付部分不当作已偿还。","確定・支払関連・期日経過の元請求は保持します。将来の未払元金のみ支払日に支払うと仮定し、延滞の未払分を返済済みにはしません。"),11,color=LocalYohaku.current.muted)
        YText(word("未确认本期费用先按付款前余额计算，假设付款影响后续计费；实际清偿按确认日期进入计费余额。","未確定の当期費用は支払前残高から計算し、仮定する支払は後続の計算に反映します。実返済は確定日から計算残高に反映します。"),11,color=LocalYohaku.current.muted)
        if(r.sourceEstimated)YText(word("原消费账户入账尚为预计；此计划也保留预计性质。","元の利用額の口座計上は見込みです。このプランも見込みとして扱います。"),11,color=LocalYohaku.current.warm)
        YText(word("保存只更新预测；已确认金额、原消费、实际资金与欠款余额不变。","保存で更新するのは予測です。確定金額・元の支出・実資金・借入残高は変わりません。"),11,color=LocalYohaku.current.muted)
        when(val calculation=r.calculation) {
            is Calculation.Ready->{
                r.bills.forEach{bill->Panel(Modifier.testTag("prediction-review-period-${bill.number}")) {
                    FinancialBillFacts(bill,currency)
                    RecordFacts(listOf(word("实际清偿后本期待付","実返済後の当期残額") to Money(currency,r.before.ledger.billOutstanding(bill).totalMinor).display()))
                    calculation.value.firstOrNull{it.period.number==bill.number}?.let{FinancialProjectionFacts(it.basis,currency)}
                    if(bill.basisJson!=null)FinancialPredictionBasis(bill,r.before.finance,currency,vm,r.before.ledger,historical=bill.id in r.retainedOther.map{it.id})
                }}
                val remaining=calculation.value.lastOrNull()?.basis?.remainingPrincipalMinor?:actualRemaining
                YText(word("预测范围后的剩余本金：","予測範囲後の残元金：")+Money(currency,remaining).display(),14,Modifier.testTag("prediction-remaining"))
            }
            is Calculation.Incomplete->FinancialReadiness(RuleReadiness.Incomplete(calculation.fields),word("预测需要补充或核对","予測に必要な情報・確認"))
            is Calculation.Unsupported->FinancialCalculationResult(calculation,currency)
            is Calculation.CannotSettle->FinancialCalculationResult(calculation,currency)
        }
        YButton(word("确认保存预测计划","予測プランを保存"),{var saved=false;vm.execute(onSuccess={if(saved)onSaved()}) {
            saved=commitFinancialDraft(draftSession){vm.repository.commitFinancialPrediction(r)}!=null
            if(saved)vm.financialPredictionReview.value=null else {
                vm.financialPredictionReview.value=vm.repository.previewFinancialPrediction(r.plan.bookId,r.input)
                vm.financialReviewRefreshed.value=true
            }
        }},Modifier.testTag("prediction-confirm"),primary=true,enabled=!busy&&r.calculation is Calculation.Ready)
        YButton(word("返回修改","戻って修正"),{vm.financialPredictionReview.value=null},Modifier.testTag("prediction-review-back"),enabled=!busy)
    }
}

@Composable internal fun FinancialPredictionBasis(bill:FinancialBillEntity,finance:FinancialGraph,currency:Currency,vm:AppViewModel,snapshot:BookSnapshot,historical:Boolean=false) {
    var open by rememberSaveable(bill.id){mutableStateOf(false)}
    val basis=vm.repository.json.decodeFromString<FinancialCalculationBasis>(bill.basisJson!!)
    YButton((if(bill.confirmed||historical)word("原预测依据","元の予測根拠")else word("计算依据","計算根拠"))+if(open)" −"else " +",{open=!open},Modifier.testTag("prediction-basis-${bill.id}"))
    if(open) {
        YText(word("预测期后本金：","予測の支払後元金：")+Money(currency,basis.remainingPrincipalMinor).display(),12)
        basis.paymentSetting?.let{setting->ThemeAuditPanel(word("本期采用的月付款","当期に採用する月払い"),Modifier.testTag("pool-payment-basis-${bill.id}")) {
            PoolPaymentSettingFacts(setting,currency)
            YText(word("以下基础规则仍用于计息和舍入，其默认月付款由这份已办理条件替代。","以下の基本条件は金利・丸めに使い、既定の月支払額はこの手続き済み条件で置き換えます。"),11)
        }}
        if(basis.additionalFeeMinor>0)YText(word("本期另列追加费用：","当期の別途追加手数料：")+Money(currency,basis.additionalFeeMinor).display(),12,Modifier.testTag("prediction-additional-fee-${bill.id}"))
        basis.ruleIds.forEach{id->finance.rules.firstOrNull{it.id==id}?.let{row->
            YText(word("规则第${row.version}版 · 生效 ","条件第${row.version}版・適用 ")+row.effectiveFrom,11)
        }}
        if(basis.forecastFrom!=null)YText(word("计算基准日：","計算基準日：")+basis.forecastFrom,11)
        basis.principalAdditions.forEach{fact->YText(fact.date+" · "+word("新增本金","追加元金")+" · "+Money(currency,fact.principalMinor).display(),11)}
        basis.principalFacts.forEach{fact->YText(fact.date+" · "+(if(fact.kind=="REFUND")word("实际退款","実返金")else word("实际清偿本金","実返済元金"))+" · "+Money(currency,fact.principalMinor).display(),11)}
        if(basis.agreementDate!=null)YText(word("合同 / 利用基准日：","契約・利用基準日：")+basis.agreementDate,11)
        if(basis.closingDate!=null)YText(word("规则截账日 / 账期：","条件の締日・請求月：")+basis.closingDate+" · "+basis.closingDate.take(7),11)
        if(basis.start!=null)YText(word("计费区间：","計算期間：")+basis.start+" → "+basis.end,11)
        basis.segments.forEach{segment->
            YText(segment.start+" → "+segment.end,11)
            RecordFacts(listOf(word("计费余额","計算対象残高") to Money(currency,segment.balanceMinor).display(),word("年率","年利率") to (segment.annualRate.toBigDecimal().movePointRight(2).stripTrailingZeros().toPlainString()+"%"),word("天数 / 年天数","日数・年間日数") to "${segment.days} / ${segment.yearDays}"))
        }
        val rule=bill.ruleVersionId?.let{id->finance.rules.firstOrNull{it.id==id}}?.let{vm.repository.json.decodeFromString<FinancialRuleVersion>(it.json)}
        if(rule!=null)FinancialRuleFacts(rule,snapshot,finance.bills)
    }
}

@Composable internal fun FinancialProjectionFacts(basis:FinancialCalculationBasis,currency:Currency) {
    if(basis.forecastFrom!=null) {
        RecordFacts(listOf(word("本次重算期后本金","今回の再計算後の元金") to Money(currency,basis.remainingPrincipalMinor).display(),word("本期假设未来支付的未付本金","当期に支払うと仮定する未払元金") to Money(currency,basis.assumedPrincipalMinor!!).display()))
    }
}
