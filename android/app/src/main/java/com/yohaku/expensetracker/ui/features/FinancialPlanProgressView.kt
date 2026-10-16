package com.yohaku.expensetracker.ui.features

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.finance.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*

@Composable internal fun FinancialPlanProgressView(s:BookSnapshot,f:FinancialGraph,plan:FinancialPlanEntity,asOf:String,stale:Boolean) {
    val p=s.financialPlanProgress(f,plan,asOf,stale);val currency=Currency.valueOf(plan.currency)
    fun amount(n:Long?)=n?.let{Money(currency,it).display()}
    val unknown=word("未知 / 待核对","不明・要確認")
    ThemeAuditPanel(word("这份计划的剩余安排","このプランの残りの予定"),Modifier.testTag("finance-progress-${plan.id}")) {
        RecordFacts(listOf(word("待付 / 已列期次","未払・記載全回数") to "${p.unpaidPeriods} / ${p.listedPeriods}",
            word("其中已到期未清","うち期日経過・未返済") to p.overduePeriods.toString(),
            word("已列待还本金","予定分の未返済元金") to (amount(p.principalMinor)?:unknown),
            word("已列利息 / 费用","予定分の利息・費用") to (amount(p.feeMinor)?:unknown),
            word("已列待付合计","予定分の未払合計") to Money(currency,p.totalMinor).display(),
            word("已付待确认","支払済・確認待ち") to (amount(p.pendingMinor)?:unknown),
            word("仍需备款","必要資金") to (amount(p.cashRequiredMinor)?:unknown)))
        YText(word("待付中：平台确认${p.confirmedPeriods}期 / 本地预测${p.predictedPeriods}期。","未払分：返済先確定${p.confirmedPeriods}回・ローカル予測${p.predictedPeriods}回。"),11)
        p.lastDate?.let{YText(word("已列最后付款日：","記載分の最終支払日：")+it,11)}
        YText(when{stale->word("原预测待重核；期次与费用仅保留原安排，不代表最新完整结果。","元の予測は要再確認。回数・費用は元の予定で、最新の全体結果ではありません。")
            !p.complete->word("只录入了部分安排。以上不是全部剩余期数或全部未来费用。","一部の予定のみ入力済み。全残回数・全将来費用ではありません。")
            else->word("按已保存的全期安排汇总；仅在按期付款且没有新增借入、退款或变更的假设下成立。","保存済みの全期間予定を集計。期日どおりに支払い、追加借入・返金・変更がない前提です。")},11,color=LocalYohaku.current.muted)
        if(p.needsReconciliation)YText(word("存在多付或本金 / 费用分配差额，请核对；不自动抵消其他期的待付。","過払または元金・費用の充当差額があります。確認してください。他回の未払とは自動相殺しません。"),12,color=LocalYohaku.current.warm)
        YText(word("这是义务安排，不能加到账户未偿本金。预测费用尚未支付，不计入实际消费。","これは支払予定で、口座の未返済元金へ加算しません。予測費用は未払いで実支出には含めません。"),11)
    }
}

@Composable internal fun FinancialRemainingAfterBill(bill:FinancialBillEntity,currency:Currency,stale:Boolean) {
    val basis=bill.basisJson?.let{kotlinx.serialization.json.Json.decodeFromString<FinancialCalculationBasis>(it)}
    val unknown=word("未推算","未計算")
    RecordFacts(listOf(word("付款后的预计本金","支払後の予測元金") to (basis?.let{(if(stale)word("原预测 · ","元予測・")else "≈ ")+Money(currency,it.remainingPrincipalMinor).display()}?:unknown)))
    YText(word("期后本金是按当时资料的付款假设，不是实际已清偿，也不是最新账户余额。","支払後元金は当時の情報による支払仮定です。実際の返済済み額・最新口座残高ではありません。"),11,color=LocalYohaku.current.muted)
}

@Composable internal fun FinancialLoanModes(s:BookSnapshot,a:AccountEntity,f:FinancialGraph,onPlans:()->Unit,onMovements:()->Unit) {
    if(a.type!=AccountType.LOAN.name)return
    var mode by androidx.compose.runtime.saveable.rememberSaveable(s.book.id,a.id){androidx.compose.runtime.mutableStateOf(if(f.contracts.any{it.accountId==a.id&&it.mode=="FIXED_LOAN"})"FIXED_LOAN"else if(f.contracts.any{it.accountId==a.id&&it.mode=="CREDIT_LINE"}||f.plans.any{it.accountId==a.id&&it.active&&it.mode=="CREDIT_LINE"})"CREDIT_LINE"else"FIXED_LOAN")}
    ThemeSection(word("固定借款与循环借入","固定借入と循環借入"),"TWO BORROWING MODES")
    YTabs(listOf("FIXED_LOAN" to word("固定合同","固定契約"),"CREDIT_LINE" to word("循环借入","循環借入")),mode,{mode=it})
    YText(if(mode=="FIXED_LOAN")word("每份合同独立维护期数与本金、利息、费用。等额本金、等额本息、到期还本或手动计划依用户规则计算。","契約ごとに回数・元金・利息・手数料を管理。元金均等・元利均等・満期一括・手入力はユーザー条件に従います。")else word("以账户循环余额池维护共同月付款，后续借入、实际清偿按事件日期进入计息。可用额度另按已配置额度及恢复条件判断。","口座プールで共通の月次支払を管理。追加借入・実返済はイベント日から計算。利用可能枠は設定した枠・回復条件で別に判定します。"),11)
    val contracts=f.contracts.filter{it.accountId==a.id&&it.mode==mode}
    if(contracts.isEmpty())YText(word("该模式尚无合同资料。","この方式の契約情報はまだありません。"),12)
    contracts.forEach{c->
        Panel(Modifier.testTag("loan-mode-contract-${c.id}")) {
            YText(c.name+" · "+c.date,15)
            RecordFacts(listOf(word("合同本金","契約元金") to Money(Currency.valueOf(c.currency),c.principalMinor).display(),word("已记录清偿分配后本金","記録済み返済充当後の元金") to Money(Currency.valueOf(c.currency),s.contractRemaining(c)).display()))
            val links=f.contractPlans.filter{it.contractId==c.id}.map{it.planId}.toSet()
            val plans=f.plans.filter{it.active&&(it.id in links||c.fundingEventId!=null&&it.sourceEventId==c.fundingEventId)}
            val merged=f.arrangements.mapNotNull{change->val target=f.plans.first{it.id==change.targetPlanId};if(target.id !in links&&!(c.fundingEventId!=null&&target.sourceEventId==c.fundingEventId))null else f.reconciliationPool(target)?.name}
            YText(if(merged.isNotEmpty())word("已并入共同付款池：","共通支払プールに統合済み：")+merged.distinct().joinToString()else if(plans.isEmpty())word("尚未关联逐期付款安排。","各回の支払予定は未関連です。")else word("关联计划：","関連プラン：")+plans.joinToString{it.name},11)
        }
    }
    val standalone=f.plans.filter{plan->plan.accountId==a.id&&plan.active&&plan.mode==mode&&plan.origin !in listOf("POOL","POOL_RETAINED")&&contracts.none{c->f.contractPlans.any{it.contractId==c.id&&it.planId==plan.id}||c.fundingEventId!=null&&c.fundingEventId==plan.sourceEventId}}
    standalone.forEach{plan->Panel(Modifier.testTag("loan-mode-plan-${plan.id}")) {
        YText(plan.name,15)
        YText(if(plan.origin=="ARRANGEMENT")word("已办理后的独立还款安排；没有新增借入或重复建立本金合同。","手続き済みの独立した返済予定です。追加借入・元金契約の重複作成はありません。")else word("这份还款计划尚未关联单一合同。","この返済予定は単一の契約に未関連です。"),11)
    }}
    if(mode=="CREDIT_LINE") {
        val pools=f.plans.filter{it.accountId==a.id&&it.origin=="POOL"&&it.active}
        if(pools.isEmpty())YText(word("账户循环余额池尚未建立；借入合同不等于已完整安排付款。","口座の循環プールは未作成。借入契約だけでは支払予定が揃ったことにはなりません。"),12,color=LocalYohaku.current.warm)
        else pools.forEach{YText(word("账户共同付款计划：","口座共通の支払プラン：")+it.name,12)}
    }
    YText(word("同一账户可以有两类借款。合同与付款计划用于解释来源，不再次加到上方账户欠款。","同じ口座に両方式の借入を記録できます。契約・支払予定は内訳で、上の口座残債へ重複加算しません。"),11,color=LocalYohaku.current.muted)
    YButton(word("查看 / 补齐还款计划","返済予定を確認・補完"),onPlans,Modifier.testTag("loan-mode-plans"))
    YButton(word("查看借还与合同明细","借入返済・契約の明細"),onMovements,Modifier.testTag("loan-mode-movements"))
}

@Composable internal fun FinancialContractFacts(s:BookSnapshot,c:DebtContractEntity) {
    val unknown=word("未填写","未入力")
    fun money(currency:String?,minor:Long?)=if(currency==null||minor==null)unknown else Money(Currency.valueOf(currency),minor).display()
    RecordFacts(listOf(word("合同本金","契約元金") to money(c.currency,c.principalMinor),word("已记录清偿分配后本金","記録済み返済充当後の元金") to money(c.currency,s.contractRemaining(c))))
    if(c.fundingEventId==null)YText(word("期初已有合同，不重新产生借入、到账或费用。","期首の既存契約です。借入・入金・手数料は再計上しません。"),11)
    else {
        val bank=s.accounts.firstOrNull{it.id==c.fundingAccountId}
        val payer=s.accounts.firstOrNull{it.id==c.separateFeeAccountId}
        RecordFacts(listOf(word("实际到账账户 / 净额","実入金口座・純額") to ((bank?.name?:unknown)+" · "+money(bank?.currency,c.receivedMinor)),
            word("借入时预扣费用","借入時の天引き手数料") to money(c.currency,c.withheldFeeMinor),word("借入时另付费用","借入時の別払い手数料") to money(payer?.currency?:bank?.currency,c.separateFeeMinor)))
        YText(word("借入本金不算收入。已填写的实际费用在借入时记录；未填写费用保持未知，计划利息 / 费用另列。","借入元金は収入に含めません。入力済みの実手数料は借入時に記録。未入力は不明とし、予定利息・手数料は別に表示します。"),11,color=LocalYohaku.current.muted)
    }
}
