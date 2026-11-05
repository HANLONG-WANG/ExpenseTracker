package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.finance.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.time.LocalDate

@Serializable internal data class KnownBillDraft(val date:String,val total:String="",val principal:String="",val fee:String="",val paymentAccountId:String?=null) {
    fun input(currency:Currency)=KnownBillInput(date,Money.parse(total,currency).minor,principal.takeIf{it.isNotBlank()}?.let{Money.parse(it,currency).minor},fee.takeIf{it.isNotBlank()}?.let{Money.parse(it,currency).minor},paymentAccountId)
}

@Composable internal fun ManualFinancialPlanEditor(s:BookSnapshot,account:AccountEntity,vm:AppViewModel,onClose:()->Unit,contract:DebtContractEntity?=null,onSaved:((String,String?)->Unit)?=null) {
    FinancialDraftHost(s,FinancialDraftContext("MANUAL",account.id),vm,onClose){ManualFinancialPlanEditorContent(s,account,vm,onClose,contract,onSaved)}
}

@Composable private fun ManualFinancialPlanEditorContent(s:BookSnapshot,account:AccountEntity,vm:AppViewModel,onClose:()->Unit,contract:DebtContractEntity?=null,onSaved:((String,String?)->Unit)?=null) {
    val draftSession=requireNotNull(LocalFinancialDraft.current)
    val contractId by financialField<String?>("contractId"){if(draftSession.resumed)null else contract?.id}
    val sourceContract=s.contracts.firstOrNull{it.id==contractId}
    val currency=Currency.valueOf(account.currency)
    var name by financialField("name"){sourceContract?.name.orEmpty()}
    var mode by financialField("mode"){sourceContract?.mode?:if(account.type==AccountType.CREDIT.name)FinanceMode.SINGLE.name else FinanceMode.FIXED_LOAN.name}
    var origin by financialField("origin"){if(sourceContract==null)"MANUAL"else if(sourceContract.fundingEventId==null)"OPENING"else "EVENT"}
    var sourceEvent by financialField<String?>("sourceEvent"){sourceContract?.fundingEventId}
    var principal by financialField("principal"){sourceContract?.principalMinor?.let{Money(currency,it).decimal.toPlainString()}.orEmpty()}
    var complete by financialField("complete"){false}
    var encoded by financialField("encoded"){vm.repository.json.encodeToString(listOf(KnownBillDraft(LocalDate.now(vm.repository.clock).toString())))}
    val periods=vm.repository.json.decodeFromString<List<KnownBillDraft>>(encoded)
    fun update(index:Int,value:KnownBillDraft){encoded=vm.repository.json.encodeToString(periods.mapIndexed{i,p->if(i==index)value else p})}
    var activeDate by remember{mutableStateOf<Int?>(null)}
    val busy by vm.busy.collectAsStateWithLifecycle()
    val review by vm.manualFinancialPlanReview.collectAsStateWithLifecycle()
    val r=review?.takeIf{it.plan.accountId==account.id&&it.plan.bookId==s.book.id}
    val formScroll=rememberScrollState();val reviewScroll=rememberScrollState()
    LaunchedEffect(r){if(r!=null)reviewScroll.scrollTo(0)}
    FinancialFlow(word("已知账单与逐期计划","既知の返済予定"),step=if(r!=null)1 else 0,
        onClose={vm.manualFinancialPlanReview.value=null;onClose()},onBack={vm.manualFinancialPlanReview.value=null},
        scrollState=if(r!=null)reviewScroll else formScroll,actions={
            if(r!=null)ManualFinancialPlanReviewActions(r,vm){id->if(onSaved!=null)onSaved(id,contractId)else onClose()}else {
        YButton(word("预览已知计划","既知の予定を確認"),{
            val inputName=name;val inputMode=mode;val inputOrigin=origin;val event=sourceEvent;val amount=principal;val all=complete;val rows=periods
            vm.execute {
                require(inputOrigin!="EVENT"||event!=null){"请选择关联记录 / 関連する記録を選択してください"}
                val input=ManualFinancialPlanInput(account.id,inputName,FinanceMode.valueOf(inputMode),rows.map{it.input(currency)},amount.takeIf{it.isNotBlank()}?.let{Money.parse(it,currency).minor},all,inputOrigin=="OPENING",if(inputOrigin=="EVENT")event else null)
                vm.financialReviewRefreshed.value=false
                vm.manualFinancialPlanReview.value=vm.repository.previewManualFinancialPlan(s.book.id,input)
            }
        },Modifier.testTag("finance-plan-preview"),primary=true,enabled=!busy)
            }
        }) {
        if(r!=null){ManualFinancialPlanReviewContent(r,vm);return@FinancialFlow}
        FinancialDraftNotice()
        if(contract!=null&&contractId!=contract.id)YText(word("已恢复原有草稿，未覆盖为此合同的资料。如需新建，请先完成或放弃原草稿。","既存の下書きを再開し、この契約の情報で上書きしていません。新規作成するには既存の下書きを完了または破棄してください。"),12,color=LocalYohaku.current.warm)
        FinancialReviewSection(account.name) {YText(word("录入平台已知金额，不需要先配置计算规则。保存计划不代表已付款，不改变账户余额。","返済先の既知金額を入力します。計算条件は不要です。予定の保存は支払済みを意味せず、口座残高は変えません。"),11,lineHeightMultiplier=1.9f)}
        YField(word("计划名称","プラン名"),name,{name=it},Modifier.testTag("finance-plan-name"))
        val modes=if(account.type==AccountType.CREDIT.name)listOf(FinanceMode.SINGLE,FinanceMode.INSTALLMENT,FinanceMode.REVOLVING)else listOf(FinanceMode.FIXED_LOAN,FinanceMode.CREDIT_LINE)
        YSelect(word("支付 / 借款模式","支払・借入方式"),mode,modes.map{it.name to financeLabel(it.name)},{mode=it},Modifier.testTag("finance-plan-mode"))
        YSelect(word("计划来源","プランの対象"),origin,listOf("MANUAL" to word("手动已知安排","手動の既知予定"),"OPENING" to word("期初已有欠款","期首の既存残高"),"EVENT" to word("关联已有消费 / 借入","既存の支出・借入に関連付け")),{origin=it},Modifier.testTag("finance-plan-origin"))
        if(origin=="EVENT") {
            YSelect(word("已有记录","既存の記録"),sourceEvent,listOf(null to word("请选择","選択してください"))+s.visibleEvents.filter{it.isFinancialSource(account)}.map{it.id as String? to (it.date+" · "+it.merchant+" · "+Money(Currency.valueOf(it.currency),it.amountMinor).display())},{sourceEvent=it},Modifier.testTag("finance-plan-source"))
        }
        YField(word("计划本金 / 不明可留空","プラン元金・不明なら空欄")+" · "+currency.name,principal,{principal=it},Modifier.testTag("finance-plan-principal"),numeric=true)
        YToggle(word("已知完整逐期计划","全回の予定が判明している"),complete,{complete=it},Modifier.testTag("finance-plan-complete"))
        periods.forEachIndexed{i,p->Panel(Modifier.testTag("finance-known-period-$i")) {
            YText(word("第${i+1}期","第${i+1}回"),16)
            KnownBillFields(s,p,{update(i,it)},if(activeDate==i)0 else null,{activeDate=if(it==null)null else i},"finance-known-$i")
            if(periods.size>1)YButton(word("移除此期","この回を削除"),{activeDate=null;encoded=vm.repository.json.encodeToString(periods.filterIndexed{n,_->n!=i})},enabled=!busy)
        }}
        YButton(word("＋ 添加下一期","＋ 次の回を追加"),{activeDate=null;encoded=vm.repository.json.encodeToString(periods+KnownBillDraft(LocalDate.parse(periods.last().date).plusMonths(1).toString(),paymentAccountId=periods.last().paymentAccountId))},Modifier.testTag("finance-plan-add-period"),enabled=!busy)

    }
}

@Composable internal fun KnownBillFields(s:BookSnapshot,p:KnownBillDraft,onChange:(KnownBillDraft)->Unit,activeDate:Int?,onDateOwner:(Int?)->Unit,tag:String) {
    YDateFields(listOf(word("实际扣款 / 到期日","実引落日・支払期日") to p.date),activeDate,onDateOwner,{_,v->onChange(p.copy(date=v))})
    YField(word("已知合计","既知の合計"),p.total,{onChange(p.copy(total=it))},Modifier.testTag("$tag-total"),numeric=true)
    YField(word("其中本金 / 不明留空","うち元金・不明なら空欄"),p.principal,{onChange(p.copy(principal=it))},Modifier.testTag("$tag-principal"),numeric=true)
    YField(word("其中利息与费用 / 不明留空","うち利息・手数料・不明なら空欄"),p.fee,{onChange(p.copy(fee=it))},Modifier.testTag("$tag-fee"),numeric=true)
    YSelect(word("付款账户 / 尚未确定可留空","引落口座・未定なら未選択"),p.paymentAccountId,listOf(null to word("未确定","未定"))+s.accounts.filter{it.active&&!AccountType.valueOf(it.type).debt}.map{it.id as String? to (it.name+" · "+it.currency)},{onChange(p.copy(paymentAccountId=it))},Modifier.testTag("$tag-bank"))
}

@Composable private fun ManualFinancialPlanReviewContent(r:ManualFinancialPlanReview,vm:AppViewModel) {
    val refreshed by vm.financialReviewRefreshed.collectAsStateWithLifecycle()
    val currency=Currency.valueOf(r.plan.currency)
    if(refreshed)FinanceRefreshed()
    FinancialPlanChangeSummary(r.plan.name,FinancialDisplayFacts.plan(null,r.plan,emptyList(),r.bills),currency)
    YText(word("只保存待付义务；实际资金与消费不会变化。","支払予定のみ保存し、実際の資金・支出は変えません。"),11,color=LocalYohaku.current.muted)
    FinancialFullDetails { r.bills.forEach{FinancialBillFacts(it,currency)} }
}

@Composable private fun ManualFinancialPlanReviewActions(r:ManualFinancialPlanReview,vm:AppViewModel,onSaved:(String)->Unit) {
    val draftSession=requireNotNull(LocalFinancialDraft.current)
    val busy by vm.busy.collectAsStateWithLifecycle()
            YButton(word("确认保存已知计划","既知の予定を保存"),{var saved=false;vm.execute(onSuccess={if(saved)onSaved(r.plan.id)}) {
                saved=commitFinancialDraft(draftSession){vm.repository.commitManualFinancialPlan(r)}!=null
                if(saved)vm.manualFinancialPlanReview.value=null else {
                    val input=ManualFinancialPlanInput(r.plan.accountId,r.plan.name,FinanceMode.valueOf(r.plan.mode),r.bills.map{KnownBillInput(it.date,it.totalMinor,it.principalMinor,it.feeMinor,it.paymentAccountId)},r.plan.principalMinor,r.plan.complete,r.plan.origin=="OPENING",r.plan.sourceEventId)
                    vm.manualFinancialPlanReview.value=vm.repository.previewManualFinancialPlan(r.plan.bookId,input);vm.financialReviewRefreshed.value=true
                }
            }},Modifier.testTag("finance-plan-confirm"),primary=true,enabled=!busy)
}
