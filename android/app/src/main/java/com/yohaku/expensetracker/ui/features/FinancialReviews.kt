package com.yohaku.expensetracker.ui.features

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.data.db.FinancialBillEntity
import com.yohaku.expensetracker.domain.finance.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*

@Composable internal fun FinancialReadiness(readiness:RuleReadiness,title:String?=null) {
    when(readiness) {
        RuleReadiness.Complete->YText(word("计算条件已填写完整 · 尚需实际账单验证","計算条件入力済み・実際の請求での検証が必要"),12,Modifier.testTag("finance-rule-complete"),color=LocalYohaku.current.accent)
        is RuleReadiness.Incomplete->androidx.compose.foundation.layout.Column(Modifier.testTag("finance-rule-incomplete")) {
            YText(title?:word("规则尚缺以下资料","次の条件が不足しています"),14,color=LocalYohaku.current.warm)
            readiness.fields.forEach{YText("• "+financialIssueName(it),11)}
        }
        is RuleReadiness.Unsupported->{YText(word("此算法需使用手动计划","手動プランが必要"),14);YText(readiness.reason,11)}
    }
}

@Composable internal fun FinancialCalculationResult(result:Calculation<List<PaymentPeriod>>,currency:Currency,compact:Boolean=false) {
    when(result) {
        is Calculation.Incomplete->FinancialReadiness(RuleReadiness.Incomplete(result.fields),word("预测需要补充或核对","予測に必要な情報・確認"))
        is Calculation.Unsupported->YText(when(result.reason){
            "arrangement.samePoolMode"->financialIssueName(result.reason)
            "billing.singleRequiresOnePeriod"->word("一次付自动预测需明确单个账期；多账期请分别关联来源或保留手动计划。","一括払いの自動予測には単一の対象請求が必要です。複数請求は対象を分けるか手動プランを保持してください。")
            "loan.actualEventsRequireAdjustedPlan"->word("该模式的实际本金变动需先确认调整后的平台计划。","この方式での実元金の変動は、返済先の変更後の予定を先に確認してください。")
            "installment.periods"->word("规则未包含所选期数。","条件に選択した回数がありません。")
            "interest.changedMethodWithinPeriod"->word("本期内计费方式发生改变，请使用平台已知计划。","期間中に計算方式が変わっています。返済先の既知プランを使用してください。")
            "interest.monthlyRateBoundary"->word("月计费中途变更利率的归属未明确，请使用平台已知计划。","月の途中での利率変更の扱いが未確定です。返済先の既知プランを使用してください。")
            "interest.monthlyEventBalance"->word("月内逐日余额的月计方案需手动已知计划。","月内増減の月単位計算には既知の手動プランを使用してください。")
            "loan.manual"->word("手动模式请填写平台逐期计划。","手動方式では返済先の各回の予定を入力してください。")
            "loan.annuityRequiresRegularMonthlyInterest"->word("等额本息需常规月计息及逐月付款日；特殊约定请录入平台计划。","元利均等には通常の月単位利息と月ごとの支払日が必要です。特殊条件は返済先の予定を入力してください。")
            else->result.reason
        },12,color=LocalYohaku.current.warm)
        is Calculation.CannotSettle->{
            val title=word("无法按此金额结清","この金額では完済できません")
            val facts=listOf(word("本金","元金") to Money(currency,result.principalMinor).display(),word("本期费用","当期手数料") to Money(currency,result.feeMinor).display(),word("设定付款","設定支払額") to Money(currency,result.paymentMinor).display())
            if(compact)FinancialReviewSection(title){RecordFacts(facts)}else ThemeAuditPanel(title){RecordFacts(facts)}
        }
        is Calculation.Ready->{
            YText(word("仅计算预览，不创建计划或付款。","計算プレビューのみ。プラン・支払は作成しません。"),11,color=LocalYohaku.current.muted)
            val periods=result.value.sortedBy{it.date}
            ArrangementRows(word("试算期次","試算の各回"),periods.map{ArrangementMoneyRow(it.date.toString(),it.principalMinor,it.feeMinor,it.totalMinor,null)},currency,
                basisId="trial",remainingByRow=periods.map{it.remainingPrincipalMinor},periodNumbers=periods.map{it.number},rowTag={"finance-trial-period-${periods[it].number}"})

        }
    }
}

@Composable internal fun FinancialRuleReviewContent(r:FinancialRuleReview,vm:AppViewModel) {
    val refreshed by vm.financialReviewRefreshed.collectAsStateWithLifecycle()
    if(refreshed)FinanceRefreshed()
    val previous=r.before.finance.rules.filter{it.accountId==r.rule.accountId&&it.mode==r.rule.mode.name}.maxByOrNull{it.version}
        ?.let{vm.repository.json.decodeFromString<FinancialRuleVersion>(it.json)}
    YText(word("新版本","新バージョン")+" · "+r.version,12)
    YText(word("需重算的预测账单：","再計算が必要な予測請求：")+r.affectedPredictions.size+" · "+word("已确认账单保持不变：","確定済みの請求は変更しません：")+r.retainedConfirmedBills.size,11)
    FinancialReadiness(r.readiness)
    val names=r.before.ledger.accounts.associate{it.id to it.name}+r.before.finance.bills.associate{bill->bill.id to ((r.before.finance.plans.firstOrNull{it.id==bill.planId}?.name?.plus(" · ").orEmpty())+word("第${bill.number}期","第${bill.number}回")+" · "+bill.date)}
    val unavailable=word("原资料待核查","元の情報を要確認")
    FinancialRuleChangeSummary(FinancialDisplayFacts.rule(previous,r.rule),r.before.ledger,Currency.valueOf(r.before.ledger.accounts.first{it.id==r.rule.accountId}.currency),referenceName={names[it]?:unavailable})
    YText(word("保存新版本不会覆盖已确认账单，也不会办理金融操作。","新バージョンを保存しても確定請求は書き換えず、金融手続きも行いません。"),11,color=LocalYohaku.current.muted)
    FinancialFullDetails { FinancialRuleFacts(r.rule,r.before.ledger,r.before.finance.bills) }
}

@Composable internal fun FinancialRuleReviewActions(r:FinancialRuleReview,vm:AppViewModel,onSaved:()->Unit) {
    val draftSession=requireNotNull(LocalFinancialDraft.current)
    val busy by vm.busy.collectAsStateWithLifecycle()
    YButton(word("确认保存规则版本","条件バージョンを保存"),{var saved=false;vm.execute(onSuccess={if(saved)onSaved()}) {
                saved=commitFinancialDraft(draftSession){vm.repository.commitFinancialRule(r)}!=null
                if(saved)vm.financialRuleReview.value=null else {
                    vm.financialRuleReview.value=vm.repository.previewFinancialRule(r.rule.bookId,r.rule.accountId,r.rule)
                    vm.financialReviewRefreshed.value=true
                }
            }},Modifier.testTag("finance-rule-confirm"),primary=true,enabled=!busy)
}

@Composable internal fun FinanceRefreshed()=YText(word("资料已变化，请确认重新计算的结果。","情報が変わりました。再計算した結果を確認してください。"),12,Modifier.testTag("finance-review-refreshed"),color=LocalYohaku.current.warm)

@Composable internal fun FinancialRuleFacts(rule:FinancialRuleVersion,s:BookSnapshot,bills:List<FinancialBillEntity> = emptyList()) {
    val currency=Currency.valueOf(s.accounts.first{it.id==rule.accountId}.currency)
    val values=rule.toForm(currency).values
    val names=listOf(
        "closing" to financialIssueName("billing.closingDay"),"closingIncluded" to financialIssueName("billing.closingBoundary"),
        "monthOffset" to financialIssueName("billing.paymentMonthOffset"),"payday" to financialIssueName("billing.paymentDay"),"bank" to financialIssueName("billing.paymentAccount"),
        "apr" to word("年率（%）","年利率（%）"),"frequency" to financialIssueName("interest.frequency"),"balance" to financialIssueName("interest.balanceBasis"),
        "includeStart" to financialIssueName("interest.includeStart"),"includeEnd" to financialIssueName("interest.includeEnd"),"yearDays" to financialIssueName("interest.yearDays"),
        "roundAt" to financialIssueName("interest.rounding"),"rounding" to financialIssueName("interest.roundingMode"),"first" to financialIssueName("interest.firstCharge"),
        "paymentMethod" to financialIssueName("revolving.method"),"payment" to financialIssueName("revolving.payment"),"tiers" to word("余额阶梯表","残高別コース表"),
        "tierBalanceDate" to financialIssueName("revolving.tierBalanceDate"),"tiersIncludeFees" to financialIssueName("revolving.tierPaymentIncludesFees"),"automatic" to financialIssueName("revolving.automatic"),
        "periods" to financialIssueName("installment.supportedPeriods"),"feeMethod" to financialIssueName("installment.feeMethod"),"totalFee" to financialIssueName("installment.totalFee"),
        "totalRate" to word("总费率（%）","総料率（%）"),"feeTable" to word("每100单位费用表","100通貨単位当たり手数料表"),
        "principalRemainder" to financialIssueName("installment.principalRemainder"),"feeRemainder" to financialIssueName("installment.feeRemainder"),
        "loanMethod" to financialIssueName("loan.method"),"allocation" to financialIssueName("loan.allocation"),"unsupported" to word("特殊算法说明","特殊な計算条件"))
    val choices=setOf("frequency","balance","roundAt","rounding","first","paymentMethod","feeMethod","principalRemainder","feeRemainder","loanMethod","allocation")
    val booleans=setOf("closingIncluded","includeStart","includeEnd","tiersIncludeFees","automatic")
    RecordFacts(names.filter{!values[it.first].isNullOrBlank()&&(it.first!="automatic"||rule.mode==FinanceMode.REVOLVING)}.map{(key,label)->val raw=values.getValue(key)
        label to when {key=="bank"->s.accounts.firstOrNull{it.id==raw}?.name?:word("账户已不可用","口座が利用できません")
            key=="tierBalanceDate"->if(raw=="BILL_CLOSING")word("本期计费截止余额","当期計算終了時点の残高")else word("付款日还本前余额","支払日の元金返済前残高")
            key in choices->financeLabel(raw)
            key in booleans->if(raw=="true")word("是","はい")else word("否","いいえ")
            key in setOf("payment","totalFee")->raw+" "+currency.name
            else->raw}
    })
    rule.bonusPayments.forEachIndexed{i,w->RecordFacts(listOf(word("奖金月期间 ${i+1}","ボーナス期間${i+1}") to "${w.startMonth}/${w.startDay}〜${w.endMonth}/${w.endDay} → ${w.paymentMonth}/${w.paymentDay}"))}
    rule.limitRecovery?.let{previous->
        RecordFacts(listOf(word("此版本曾填写的恢复条件","このバージョンの枠回復入力") to limitLabel(previous.name)))
        YText(word("此处保留原输入；实际额度预测采用“额度与恢复”的独立设置。","以前の入力記録です。実際の枠予測は「利用枠と回復」の個別設定を使用します。"),11,color=LocalYohaku.current.muted)
    }
    rule.deadlines.forEach{d->
        val bill=bills.firstOrNull{it.id==d.billId}
        YText((bill?.let{word("第${it.number}期","第${it.number}回")+" · "+it.date}?:word("账期资料待核查","対象請求を要確認"))+" · "+financeLabel(d.changeType.name)+" · "+FinancialDisplayFacts.localTime(java.time.LocalDateTime.parse(d.date+"T"+d.time),d.zoneId,LocalLanguage.current),11)
    }
}
