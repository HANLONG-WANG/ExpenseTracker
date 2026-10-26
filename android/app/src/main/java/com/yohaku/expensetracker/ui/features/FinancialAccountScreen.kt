package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.layout.*
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
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
import kotlinx.serialization.encodeToString

@Composable internal fun FinancialAccountScreen(s:BookSnapshot,account:AccountEntity,vm:AppViewModel,initialTab:String="overview",onRecord:(EventEntity)->Unit={},initialMonth:String?=null,onFund:((String,String?)->Unit)?=null) {
    val graph by vm.financialState.collectAsStateWithLifecycle()
    val finance=graph?.takeIf{it.book.id==s.book.id}
    var tab by rememberSaveable(s.book.id,account.id){mutableStateOf(initialTab)}
    var statementMonth by rememberSaveable(s.book.id,account.id){mutableStateOf(initialMonth?:java.time.YearMonth.now(vm.repository.clock).toString())}
    var limitRuleReturn by rememberSaveable(s.book.id,account.id){mutableStateOf<String?>(null)}
    var editMode by rememberSaveable(s.book.id,account.id){mutableStateOf<String?>(null)}
    var arrangementReturn by rememberSaveable(s.book.id,account.id){mutableStateOf<String?>(null)}
    var arrangementPlan by rememberSaveable(s.book.id,account.id){mutableStateOf<String?>(null)}
    var poolTarget by rememberSaveable(s.book.id,account.id){mutableStateOf<String?>(null)}
    var poolBillReturn by rememberSaveable(s.book.id,account.id){mutableStateOf<String?>(null)}
    var poolPaymentPlan by rememberSaveable(s.book.id,account.id){mutableStateOf<String?>(null)}
    var poolPaymentReturn by rememberSaveable(s.book.id,account.id){mutableStateOf<String?>(null)}
    var reconciliationPlan by rememberSaveable(s.book.id,account.id){mutableStateOf<String?>(null)}
    var reconciliationParents by rememberSaveable(s.book.id,account.id){mutableStateOf<List<String>>(emptyList())}
    var reconciliationReturn by rememberSaveable(s.book.id,account.id){mutableStateOf<String?>(null)}
    var prediction by rememberSaveable(s.book.id,account.id){mutableStateOf<String?>(null)}
    var movement by rememberSaveable(s.book.id,account.id){mutableStateOf<String?>(null)}
    var movementTarget by rememberSaveable(s.book.id,account.id){mutableStateOf<String?>(null)}
    var contractLink by rememberSaveable(s.book.id,account.id){mutableStateOf<String?>(null)}
    var manual by rememberSaveable(s.book.id,account.id){mutableStateOf(false)}
    var confirming by rememberSaveable(s.book.id,account.id){mutableStateOf<String?>(null)}
    var planHistory by rememberSaveable(s.book.id,account.id){mutableStateOf<String?>(null)}
    var resumedDraft by rememberSaveable(s.book.id,account.id){mutableStateOf<String?>(null)}
    var historyVersion by rememberSaveable(s.book.id,account.id){mutableStateOf<String?>(null)}
    var archivedPlans by rememberSaveable(s.book.id,account.id){mutableStateOf(false)}
    var history by rememberSaveable(s.book.id,account.id){mutableStateOf(false)}
    val busy by vm.busy.collectAsStateWithLifecycle()
    val currency=Currency.valueOf(account.currency)
    if((tab=="overview"||account.type==AccountType.CREDIT.name)&&finance!=null)FinancialAccountLead(s,account,finance,vm)
    else YTitle(account.name,if(account.type==AccountType.CREDIT.name)"CREDIT, WITH CLARITY" else "PERSONAL LOAN",word("用户配置规则 · 不采用机构预设","手動設定・会社別プリセットなし"),pageFlow=true)
    val loanOverview=account.type==AccountType.LOAN.name&&tab=="overview"
    if(!loanOverview)FinancialAccountTabs(tab,{tab=it;editMode=null},showActions=tab!="overview"&&account.type!=AccountType.CREDIT.name,card=account.type==AccountType.CREDIT.name)
    if(finance==null){YText(word("正在读取金融资料…","金融情報を読込中…"),12);YButton(word("重新读取","再読込"),vm::retryRead);return}
    val rules=finance.rules.filter{it.accountId==account.id}
    val modes=if(account.type==AccountType.CREDIT.name)listOf(FinanceMode.SINGLE,FinanceMode.INSTALLMENT,FinanceMode.REVOLVING)else listOf(FinanceMode.FIXED_LOAN,FinanceMode.CREDIT_LINE)
    val resume=resumedDraft?.let{vm.repository.json.decodeFromString<FinancialDraftContext>(it)}
    fun openRuleLimits(mode:String) {limitRuleReturn=mode;resumedDraft=null;editMode=null;tab="limits";vm.financialRuleReview.value=null}
    if(resume?.kind=="RULE"){FinancialDraftResume(s,account,finance,resume,vm,{resumedDraft=null},onLimits={openRuleLimits(requireNotNull(resume.mode))});return}
    editMode?.let{raw->val mode=FinanceMode.valueOf(raw);val latest=rules.filter{it.mode==raw}.maxByOrNull{it.version}
        FinancialRuleEditor(s,account,mode,latest?.let{vm.repository.json.decodeFromString(it.json)},vm,{editMode=null},onLimits={openRuleLimits(mode.name)});return}
    if(tab=="overview") {
        FinancialDraftList(s,account,vm,{resumedDraft=vm.repository.json.encodeToString(it)})
        FinancialAccountOverview(s,account,finance,vm,{month->statementMonth=month;tab="statement"},{bill->movementTarget=bill;movement=if(bill==null)"repay"else"repay-bill"},{arrangementPlan=it},{tab="rules"},{tab="plans"},onRecord,{tab="movements"},onFund)
        if(loanOverview)FinancialAccountTabs(tab,{tab=it;editMode=null})
        else FinancialAccountActions{tab=it;editMode=null}
    }else if(tab=="statement") {
        FinancialStatements(s,account,finance,vm,statementMonth,{statementMonth=it},{confirming=it},{tab="plans"},onRecord)
    }else if(tab=="limits") {
        if(limitRuleReturn!=null)YButton(word("返回规则草稿","条件の下書きへ戻る"),{editMode=limitRuleReturn;limitRuleReturn=null;tab="rules"},Modifier.testTag("finance-return-rule"))
        CreditLimitSection(s,account,finance,vm)
    }else if(tab=="rules") {
        modes.forEach{mode->
            val latest=rules.filter{it.mode==mode.name}.maxByOrNull{it.version}
            val rule=latest?.let{vm.repository.json.decodeFromString<FinancialRuleVersion>(it.json)}
            Panel(Modifier.testTag("finance-mode-${mode.name}")) {
                YText(financeLabel(mode.name),18)
                if(rule==null)YText(word("尚未填写；仍可录入已知账单与计划。","未設定です。既知の請求・予定は入力できます。"),11,color=LocalYohaku.current.muted)
                else {
                    RecordFacts(listOf(word("版本 / 生效日","バージョン・適用日") to "${latest.version} · ${rule.effectiveFrom}",word("时区","タイムゾーン") to rule.zoneId))
                    FinancialReadiness(rule.readiness())
                    YText(financeLabel(finance.ruleValidationStatus(rule.id)),11,Modifier.testTag("finance-validation-${rule.id}"),color=LocalYohaku.current.muted)
                }
                YButton(if(rule==null)word("填写规则","条件を設定")else word("编辑为新版本","新バージョンとして編集"),{editMode=mode.name},Modifier.testTag("finance-edit-${mode.name}"),primary=rule==null,enabled=!busy)
            }
        }
        if(rules.isNotEmpty()) {
            YButton(word("规则版本历史","条件のバージョン履歴")+if(history)" −"else " +",{history=!history},Modifier.testTag("finance-rule-history"))
            if(history)rules.sortedWith(compareBy<FinancialRuleEntity>{it.mode}.thenByDescending{it.version}).forEach{version->Panel {
                YText(financeLabel(version.mode)+" · "+word("第${version.version}版","第${version.version}版"),14)
                YText(version.effectiveFrom,12)
                FinancialReadiness(vm.repository.json.decodeFromString<FinancialRuleVersion>(version.json).readiness())
                YText(financeLabel(finance.ruleValidationStatus(version.id)),11)
                YButton(word("查看当时规则","当時の条件を見る"),{historyVersion=if(historyVersion==version.id)null else version.id})
                if(historyVersion==version.id)FinancialRuleFacts(vm.repository.json.decodeFromString(version.json),s,finance.bills)
            }}
        }
    }else if(tab=="movements") {
        if(account.type==AccountType.LOAN.name) {
            YButton(word("＋ 记录实际借入","＋ 実際の借入を記録"),{movementTarget=null;movement="borrow"},Modifier.testTag("finance-add-borrowing"),primary=true,enabled=!busy)
            YButton(word("补充期初合同","期首契約を補完"),{movementTarget=null;movement="opening"},Modifier.testTag("finance-add-opening-contract"),enabled=!busy)
        }
        YButton(word("＋ 记录实际还款","＋ 実際の返済を記録"),{movementTarget=null;movement="repay"},Modifier.testTag("finance-add-repayment"),primary=true,enabled=!busy)
        finance.contracts.filter{it.accountId==account.id}.forEach{c->Panel(Modifier.testTag("finance-contract-${c.id}")) {
            YText(c.name,18);YText(financeLabel(c.mode)+" · "+c.date,11)
            FinancialContractFacts(s,c)
            val funding=c.fundingEventId?.let{id->s.events.firstOrNull{it.id==id}}
            if(funding?.deletedAt!=null)YText(word("借入记录已在回收站；独立还款和合同资料仍保留。","借入記録はごみ箱にあります。独立した返済・契約情報は保持しています。"),11,color=LocalYohaku.current.warm)
            YActionRow {
                if(c.fundingEventId==null||funding?.deletedAt==null)YButton(word("编辑合同 / 借入","契約・借入を編集"),{movementTarget=c.id;movement=if(c.fundingEventId==null)"opening"else "borrow"},enabled=!busy)
                YButton(word("关联已有计划","既存プランを関連付け"),{contractLink=c.id},enabled=!busy)
                YButton(word("对这份合同还款","この契約へ返済"),{movementTarget=c.id;movement="repay-contract"},Modifier.testTag("finance-repay-contract-${c.id}"),enabled=!busy)
            }
        }}
        s.repayments.filter{it.debtAccountId==account.id}.mapNotNull{p->s.visibleEvents.firstOrNull{it.id==p.eventId}?.let{it to p}}.sortedByDescending{it.first.date}.forEach{(e,p)->Panel {
            YText(e.date,14);FinancialRepaymentFacts(s,e,p)
            YButton(word("核对 / 编辑还款","返済を確認・編集"),{movementTarget=e.id;movement="repay-edit"},Modifier.testTag("finance-edit-repayment-${e.id}"),enabled=!busy)
        }}
        val unallocated=s.unallocatedRepaymentPrincipal(account.id)
        if(unallocated!=0L)YText(word("尚未分配到具体义务的已清偿本金：","個別債務への未充当返済元金：")+Money(currency,unallocated).display(),12,color=LocalYohaku.current.warm)
    }else {
        val plans=finance.plans.filter{it.accountId==account.id&&it.active}
        val pool=plans.firstOrNull{it.origin=="POOL"}
        YButton(if(pool==null)word("建立账户循环余额池","口座の循環プールを作成")else word("核对 / 重算账户余额池","口座プールを確認・再計算"),{poolTarget=pool?.id?:""},Modifier.testTag("finance-pool-open"),primary=true,enabled=!busy)
        YText(word("平台确认金额与实际付款分开保存。已知计划不重复计入欠款本金。","返済先の確定金額と実際の支払を分けて保存します。予定を元金に重複加算しません。"),11,color=LocalYohaku.current.muted)
        if(plans.isEmpty())YEmpty(word("还没有已知账单或计划","既知の請求・予定はまだありません"))
        plans.forEach{plan->
            ThemeSection(plan.name,financeLabel(plan.mode))
            val stale=vm.repository.financialPredictionStale(s,finance,plan)
            FinancialPlanProgressView(s,finance,plan,java.time.LocalDate.now(vm.repository.clock).toString(),stale)
            YActionRow {
                YButton(word("计划版本与变化","予定の履歴と変化"),{planHistory=plan.id},Modifier.testTag("finance-plan-history-${plan.id}"),enabled=!busy)
                if(plan.origin=="POOL")YButton(word("调整共同月付款","共通月払いを変更"),{poolPaymentPlan=plan.id},Modifier.testTag("finance-pool-payment-${plan.id}"),enabled=!busy)
                if(plan.origin=="POOL"||plan.origin!="POOL_RETAINED"&&finance.arrangements.none{it.originalPlanId==plan.id})YButton(word("比较付款方案","支払プランを比較"),{arrangementPlan=plan.id},Modifier.testTag("finance-compare-plan-${plan.id}"),enabled=!busy)
            }
            val current=plan.termsJson?.let{vm.repository.json.decodeFromString<FinancialPredictionInput>(it)}
            if(vm.repository.financialPredictionStale(s,finance,plan))YText(word("来源、清偿、账期或规则已变化；以下保留原预测，请核查并重算。","対象・返済・請求期間・条件が変わりました。以下は元の予測です。確認して再計算してください。"),12,Modifier.testTag("prediction-stale-${plan.id}"),color=LocalYohaku.current.warm)
            if(plan.origin=="POOL"||plan.origin!="POOL_RETAINED"&&finance.arrangements.none{it.originalPlanId==plan.id})YButton(if(plan.termsJson==null)word("用规则核对这份计划","条件でこのプランを検算")else word("重算未确认预测","未確定の予測を再計算"),{if(plan.origin=="POOL")poolTarget=plan.id else prediction=plan.id},Modifier.testTag("finance-recalculate-${plan.id}"),enabled=!busy)
            finance.bills.filter{it.planId==plan.id&&it.active}.sortedBy{it.number}.forEach{bill->Panel(Modifier.testTag("finance-bill-${bill.id}")) {
                FinancialBillFacts(bill,currency)
                val manualReview=plan.reconciliation()?.input?.principalMinor==0L
                FinancialRemainingAfterBill(bill,currency,stale||manualReview)
                val outstanding=s.billOutstanding(bill)
                RecordFacts(listOf(word("实际清偿后待付","実返済後の請求残額") to Money(currency,outstanding.totalMinor).display()))
                if(plan.reconciliation()?.input?.bills?.any{it.billId==bill.id}==true)RecordFacts(listOf(word("重核后待付本金 / 费用","再確認後の未払元金・手数料") to ((outstanding.principalMinor?.let{Money(currency,it).display()}?:word("未知","不明"))+" / "+(outstanding.feeMinor?.let{Money(currency,it).display()}?:word("未知","不明")))))
                if(outstanding.totalMinor<0)YText(word("已付款超过当前账单，请核对多付或退款；实际付款保持原值。","支払済額が現在の請求を超えています。過払・返金を確認してください。実支払額は保持します。"),12,color=LocalYohaku.current.warm)
                else if(outstanding.settled)YText(word("本期已由实际还款结清","当期は実際の返済で精算済み"),12,color=LocalYohaku.current.accent)
                else YButton(word("记录本期实际还款","当期の実際の返済を記録"),{movementTarget=bill.id;movement="repay-bill"},Modifier.testTag("finance-pay-bill-${bill.id}"),enabled=!busy)
                current?.projections?.firstOrNull{it.number==bill.number}?.let{FinancialProjectionFacts(it.basis,currency)}
                if(plan.termsJson!=null&&bill.basisJson!=null)FinancialPredictionBasis(bill,finance,currency,vm,s,historical=manualReview||current?.facts?.retainedBills?.any{it.id==bill.id}==true)
                YText(word("扣款账户：","引落口座：")+(s.accounts.firstOrNull{it.id==bill.paymentAccountId}?.name?:word("未确定","未定")),11)
                YButton(if(bill.confirmed)word("明确调整这期账单","この回の請求を明示的に修正")else word("确认平台账单","返済先の請求を確定"),{confirming=bill.id},Modifier.testTag("finance-confirm-bill-${bill.id}"),enabled=!busy)
                if(bill.confirmed&&bill.ruleVersionId!=null&&bill.predictedTotalMinor!=null)YButton(word("与原预测核对规则","元の予測で条件を検証"),{vm.execute{vm.repository.validateFinancialRule(s.book.id,bill.id)}},Modifier.testTag("finance-validate-bill-${bill.id}"),enabled=!busy)
            }}
        }
        finance.arrangements.filter{a->finance.plans.any{it.id==a.targetPlanId&&it.accountId==account.id}}.forEach{a->Panel {
            val original=finance.plans.first{it.id==a.originalPlanId};val target=finance.plans.first{it.id==a.targetPlanId}
            YText(word("已办理变更","手続き済み変更")+" · "+a.effectiveDate,16)
            YText(financeLabel(original.mode)+" → "+financeLabel(target.mode),12)
            if(vm.repository.arrangementBaselineIssue(s,finance,target)!=null)YText(word("来源、退款或清偿已变化，请重核当前安排。","元データ・返金・返済に変更があります。現在の予定を再確認してください。"),12,color=LocalYohaku.current.warm)
            if(target.origin!="POOL_RETAINED"||finance.reconciliationPool(target)!=null)YButton(word("重核来源 / 退款 / 清偿","元データ・返金・返済を再確認"),{reconciliationParents=emptyList();reconciliationPlan=target.id},Modifier.testTag("finance-reconcile-${target.id}"),enabled=!busy)
            YButton(word("查看原安排版本","元の予定の履歴を見る"),{planHistory=original.id},enabled=!busy)
            YButton(if(target.origin=="POOL_MEMBER")word("查看并入记录","統合の記録を見る")else word("查看新安排版本","新しい予定の履歴を見る"),{planHistory=target.id},Modifier.testTag("finance-arrangement-history-${a.id}"),enabled=!busy)
        }}
        val archived=finance.plans.filter{it.accountId==account.id&&!it.active&&it.origin!="POOL_MEMBER"}
        if(archived.isNotEmpty()) {
            YButton(word("已替换的本地预测","置換済みの予測")+if(archivedPlans)" −"else" +",{archivedPlans=!archivedPlans})
            if(archivedPlans)archived.forEach{p->YButton(p.name+" · "+financeLabel(p.mode),{planHistory=p.id},Modifier.testTag("finance-archived-${p.id}"))}
        }
        YButton(word("从消费 / 期初生成预测","利用・期首残高から予測を作成"),{prediction=""},Modifier.testTag("finance-add-prediction"),primary=true,enabled=!busy)
        YButton(word("＋ 添加已知账单 / 逐期计划","＋ 既知の請求・各回の予定を追加"),{manual=true},Modifier.testTag("finance-add-plan"),primary=true,enabled=!busy)
    }
    if(account.type==AccountType.CREDIT.name&&tab!="overview")FinancialAccountActions{tab=it;editMode=null}
    resume?.let{context->FinancialDraftResume(s,account,finance,context,vm,{resumedDraft=null},{bill->
        resumedDraft=null
        if(context.kind=="POOL")poolBillReturn=context.targetId.orEmpty() else if(context.kind=="ARRANGEMENT_REVIEW")reconciliationReturn=context.targetId
        confirming=bill
    },{parent->reconciliationParents=listOfNotNull(context.targetId);resumedDraft=null;reconciliationPlan=parent})}
    poolTarget?.let{id->FinancialPoolEditor(s,account,finance,finance.plans.firstOrNull{it.id==id},vm,{poolTarget=null;arrangementPlan=arrangementReturn;arrangementReturn=null;poolPaymentPlan=poolPaymentReturn;poolPaymentReturn=null},{bill->poolBillReturn=id;poolTarget=null;confirming=bill})}
    poolPaymentPlan?.let{id->finance.plans.firstOrNull{it.id==id&&it.active&&it.origin=="POOL"}?.let{plan->FinancialPoolPaymentEditor(s,account,finance,plan,vm,{poolPaymentPlan=null},{poolPaymentReturn=id;poolPaymentPlan=null;poolTarget=id},{bill->poolPaymentReturn=id;poolPaymentPlan=null;confirming=bill})}}
    reconciliationPlan?.let{id->finance.plans.firstOrNull{it.id==id}?.let{plan->FinancialReconciliationEditor(s,account,finance,plan,vm,{reconciliationPlan=reconciliationParents.lastOrNull();reconciliationParents=reconciliationParents.dropLast(1)},{bill->reconciliationReturn=id;reconciliationPlan=null;confirming=bill},{parent->reconciliationParents=reconciliationParents+id;reconciliationPlan=parent})}}
    arrangementPlan?.let{id->finance.plans.firstOrNull{it.id==id}?.let{FinancialArrangementEditor(s,account,finance,it,vm,{arrangementPlan=null},{pool->arrangementReturn=id;arrangementPlan=null;poolTarget=pool},{bill->arrangementReturn=id;arrangementPlan=null;confirming=bill})}}
    planHistory?.let{id->finance.plans.firstOrNull{it.id==id}?.let{FinancialPlanHistory(it,vm,{planHistory=null})}}
    movement?.let{kind->
        if(kind in listOf("borrow","opening"))FinancialBorrowingEditor(s,account,vm,{movement=null},kind=="opening",finance.contracts.firstOrNull{it.id==movementTarget})
        else FinancialRepaymentEditor(s,account,finance,vm,{movement=null},eventId=movementTarget.takeIf{kind=="repay-edit"},billId=movementTarget.takeIf{kind=="repay-bill"},contractId=movementTarget.takeIf{kind=="repay-contract"})
    }
    contractLink?.let{id->YSheet(word("关联已有计划","既存プランを関連付け"),{contractLink=null}) {
        val c=finance.contracts.first{it.id==id}
        YText(c.name,18)
        YText(word("仅选择相同来源的计划。此关联不改变余额或账单金额。","同じ対象のプランを選択します。関連付けで残高・請求額は変わりません。"),11)
        finance.plans.filter{it.accountId==account.id&&it.active&&it.origin!="POOL"&&it.sourceEventId==c.fundingEventId}.forEach{p->YButton(p.name,{vm.execute(onSuccess={contractLink=null}){vm.repository.linkFinancialContractPlan(s.book.id,c.id,p.id)}},enabled=!busy)}
    }}
    prediction?.let{id->FinancialPredictionEditor(s,account,finance,if(id.isEmpty())null else finance.plans.first{it.id==id},vm,{prediction=null})}
    if(manual)ManualFinancialPlanEditor(s,account,vm,{manual=false})
    confirming?.let{id->finance.bills.firstOrNull{it.id==id}?.let{FinancialBillConfirmationEditor(s,account.id,it,currency,vm,{confirming=null;if(poolBillReturn!=null){poolTarget=poolBillReturn;poolBillReturn=null}else{arrangementPlan=arrangementReturn;arrangementReturn=null;poolPaymentPlan=poolPaymentReturn;poolPaymentReturn=null;reconciliationPlan=reconciliationReturn;reconciliationReturn=null}})}}
}

@Composable internal fun FinancialBillFacts(bill:FinancialBillEntity,currency:Currency) {
    fun money(value:Long?)=value?.let{Money(currency,it).display()}
    YText(word("第${bill.number}期","第${bill.number}回")+" · "+bill.date,14)
    YText(if(bill.confirmed)word("平台已确认金额 · 尚非付款记录","返済先の確定金額・支払記録とは別")else word("规则预测","条件による予測"),11,color=LocalYohaku.current.accent)
    RecordFacts(listOf(word("本金","元金") to (money(bill.principalMinor)?:word("未知","不明")),word("利息 / 费用","利息・手数料") to (money(bill.feeMinor)?:word("未知","不明")),word("合计","合計") to Money(currency,bill.totalMinor).display()))
    if(bill.confirmed&&bill.predictedTotalMinor!=null)YText(word("原预测：","元の予測：")+money(bill.predictedTotalMinor)+" · "+bill.predictedDate,11,color=LocalYohaku.current.muted)
}

@Composable internal fun FinancialBillConfirmationEditor(s:BookSnapshot,accountId:String,bill:FinancialBillEntity,currency:Currency,vm:AppViewModel,onClose:()->Unit,initial:KnownBillDraft?=null) {
    FinancialDraftHost(s,FinancialDraftContext("BILL",accountId,billId=bill.id),vm,onClose){FinancialBillConfirmationEditorContent(s,bill,currency,vm,onClose,initial)}
}

@Composable private fun FinancialBillConfirmationEditorContent(s:BookSnapshot,bill:FinancialBillEntity,currency:Currency,vm:AppViewModel,onClose:()->Unit,initial:KnownBillDraft?) {
    val draftSession=requireNotNull(LocalFinancialDraft.current)
    fun amount(value:Long?)=value?.let{Money(currency,it).decimal.toPlainString()}.orEmpty()
    var encoded by financialField("encoded"){vm.repository.json.encodeToString(initial?:KnownBillDraft(bill.date,amount(bill.totalMinor),amount(bill.principalMinor),amount(bill.feeMinor),bill.paymentAccountId))}
    val draft=vm.repository.json.decodeFromString<KnownBillDraft>(encoded)
    var date by remember{mutableStateOf<Int?>(null)}
    val busy by vm.busy.collectAsStateWithLifecycle()
    val review by vm.financialBillReview.collectAsStateWithLifecycle()
    if(review?.bill?.id==bill.id&&review?.bill?.bookId==s.book.id) {
        val r=review!!
        val refreshed by vm.financialReviewRefreshed.collectAsStateWithLifecycle()
        YSheet(word("账单调整预览","請求修正の確認"),{vm.financialBillReview.value=null}) {
            if(refreshed)FinanceRefreshed()
            FinancialBillFacts(r.bill,currency)
            RecordFacts(listOf(word("调整前合计","修正前合計") to Money(currency,r.original.totalMinor).display(),word("调整后合计","修正後合計") to Money(currency,r.bill.totalMinor).display()))
            val beforeOutstanding=s.billOutstanding(r.original)
            val afterOutstanding=s.billOutstanding(r.bill)
            if(beforeOutstanding.paidPrincipalMinor!=0L||beforeOutstanding.paidFeeMinor!=0L) {
                RecordFacts(listOf(word("实际清偿后待付","実返済後の請求残額") to (Money(currency,beforeOutstanding.totalMinor).display()+" → "+Money(currency,afterOutstanding.totalMinor).display())))
                if(afterOutstanding.totalMinor<0)YText(word("调整后出现多付差额；确认不会撤销已发生付款，请另行核对退款或抵扣。","修正後は過払差額が発生します。確定で実支払は取り消しません。返金・充当を別途確認してください。"),12,color=LocalYohaku.current.warm)
            }
            YText(word("只修改本期平台义务，不修改规则、原消费或实际资金。","この回の返済先の請求のみ修正し、条件・元の支出・実資金は変更しません。"),11)
            YActionRow {
                YButton(word("确认本期金额","この回の金額を確定"),{var saved=false;vm.execute(onSuccess={if(saved)onClose()}) {
                    saved=commitFinancialDraft(draftSession){if(vm.repository.commitFinancialBillConfirmation(r))bill.id else null}!=null
                    if(saved)vm.financialBillReview.value=null else {
                        vm.financialBillReview.value=vm.repository.previewFinancialBillConfirmation(s.book.id,bill.id,KnownBillInput(r.bill.date,r.bill.totalMinor,r.bill.principalMinor,r.bill.feeMinor,r.bill.paymentAccountId));vm.financialReviewRefreshed.value=true
                    }
                }},Modifier.testTag("finance-bill-confirm"),primary=true,enabled=!busy)
                YButton(word("返回修改","戻って修正"),{vm.financialBillReview.value=null},Modifier.testTag("finance-review-back"),enabled=!busy)
            }
        }
        return
    }
    YSheet(word("本期平台账单","当期の返済先請求"),{vm.financialBillReview.value=null;onClose()}) {
        FinancialDraftNotice()
        KnownBillFields(s,draft,{encoded=vm.repository.json.encodeToString(it)},date,{date=it},"finance-actual")
        YButton(word("预览本期调整","当期の修正を確認"),{val input=draft;vm.execute{vm.financialReviewRefreshed.value=false;vm.financialBillReview.value=vm.repository.previewFinancialBillConfirmation(s.book.id,bill.id,input.input(currency))}},Modifier.testTag("finance-bill-preview"),primary=true,enabled=!busy)
    }
}

@Composable private fun FinancialAccountTabs(tab:String,onTab:(String)->Unit,showActions:Boolean=true,card:Boolean=false) {
    YTabs(listOf("overview" to word("总览","概要"),"statement" to word("账单","明細"),"plans" to if(card)word("分期/リボ","分割/リボ")else word("计划","予定"),"rules" to word("规则","条件")),tab,onTab)
    if(showActions)FinancialAccountActions(onTab)
}

@Composable private fun FinancialAccountActions(onTab:(String)->Unit) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        YButton(word("实际借还","借入・返済"),{onTab("movements")},Modifier.weight(1f).testTag("analysis-tab-movements"),inline=true)
        YButton(word("额度与恢复","利用枠と回復"),{onTab("limits")},Modifier.weight(1f).testTag("analysis-tab-limits"),inline=true)
    }
}
