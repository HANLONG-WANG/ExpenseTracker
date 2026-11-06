package com.yohaku.expensetracker.ui.features

import com.yohaku.expensetracker.ui.haptics.Haptic
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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

@Composable internal fun FinancialAccountScreen(s:BookSnapshot,account:AccountEntity,vm:AppViewModel,initialTab:String="overview",onRecord:(EventEntity)->Unit={},initialMonth:String?=null,onFund:((String,String?)->Unit)?=null,requestId:Long=0,modifier:Modifier=Modifier,onBack:()->Unit={vm.back()},head:@Composable ()->Unit={}) {
    val graph by vm.financialState.collectAsStateWithLifecycle()
    val readFailure by vm.financialReadFailure.collectAsStateWithLifecycle()
    val finance=graph?.takeIf{it.book.id==s.book.id}
    var path by rememberSaveable(s.book.id,account.id){mutableStateOf(FinancialDestination.from(initialTab).copy(planId=null,periodId=null).path)}
    val destination=FinancialDestination.from(path)
    val tab=destination.path
    var pageReturn by rememberSaveable(s.book.id,account.id){mutableStateOf("manage")}
    var selectedPlan by rememberSaveable(s.book.id,account.id){mutableStateOf(FinancialDestination.from(initialTab).planId)}
    var selectedPeriod by rememberSaveable(s.book.id,account.id){mutableStateOf(FinancialDestination.from(initialTab).periodId)}
    var planReturn by rememberSaveable(s.book.id,account.id){mutableStateOf<String?>(null)}
    var periodReturn by rememberSaveable(s.book.id,account.id){mutableStateOf<String?>(null)}
    var addArrangement by rememberSaveable(s.book.id,account.id){mutableStateOf(false)}
    var createContract by rememberSaveable(s.book.id,account.id){mutableStateOf<String?>(null)}
    var savedContract by rememberSaveable(s.book.id,account.id){mutableStateOf<String?>(null)}
    var createdPlan by rememberSaveable(s.book.id,account.id){mutableStateOf<String?>(null)}
    fun navigate(target:String,returnTo:String="manage") {
        val next=FinancialDestination.from(target)
        selectedPlan=next.planId;selectedPeriod=next.periodId;periodReturn=null;planReturn=null;addArrangement=false
        path=next.copy(planId=null,periodId=null).path
        pageReturn=returnTo
    }
    var consumedRequest by rememberSaveable(s.book.id,account.id){mutableStateOf(requestId)}
    var statementMonth by rememberSaveable(s.book.id,account.id){mutableStateOf(initialMonth?:java.time.YearMonth.now(vm.repository.clock).toString())}
    var limitDraftReturn by rememberSaveable(s.book.id,account.id){mutableStateOf<String?>(null)}
    var limitSourceReturn by rememberSaveable(s.book.id,account.id){mutableStateOf("manage/rules")}
    var limitPageReturn by rememberSaveable(s.book.id,account.id){mutableStateOf("manage")}
    var limitRuleReturn by rememberSaveable(s.book.id,account.id){mutableStateOf<String?>(null)}
    var editMode by rememberSaveable(s.book.id,account.id){mutableStateOf<String?>(null)}
    var arrangementReturn by rememberSaveable(s.book.id,account.id){mutableStateOf<String?>(null)}
    var arrangementPlan by rememberSaveable(s.book.id,account.id){mutableStateOf<String?>(null)}
    var batchDate by rememberSaveable(s.book.id,account.id){mutableStateOf<String?>(null)}
    var batchPoolReturn by rememberSaveable(s.book.id,account.id){mutableStateOf<String?>(null)}
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
    val scrolls=rememberSaveableStateHolder()
    val resume=resumedDraft?.let{vm.repository.json.decodeFromString<FinancialDraftContext>(it)}
    fun planSaved(id:String,contractId:String?) {
        manual=false;prediction=null;resumedDraft=null;createContract=null
        if(contractId!=null){navigate("manage/contracts");contractLink=contractId;savedContract=contractId;createdPlan=id}else{navigate("plans");selectedPlan=id}
    }
    fun returnFromLimits() {
        if(limitDraftReturn!=null)resumedDraft=limitDraftReturn else editMode=limitRuleReturn
        limitDraftReturn=null;limitRuleReturn=null
        navigate(limitSourceReturn,limitPageReturn)
    }
    fun back() {
        when {
            editMode!=null || resume?.kind=="RULE" -> {vm.financialRuleReview.value=null;editMode=null;resumedDraft=null}
            tab=="manage/limits" && limitRuleReturn!=null -> returnFromLimits()
            selectedPeriod!=null -> {val source=periodReturn;if(source!=null)navigate(source)else selectedPeriod=null}
            selectedPlan!=null -> {if(planReturn!=null)navigate(requireNotNull(planReturn))else selectedPlan=null}
            addArrangement -> addArrangement=false
            destination.page!=null -> navigate(pageReturn)
            else -> onBack()
        }
    }
    LaunchedEffect(requestId) {
        if(consumedRequest!=requestId) {
            navigate(initialTab)
            initialMonth?.let{statementMonth=it}
            editMode=null;resumedDraft=null;limitRuleReturn=null;limitDraftReturn=null
            movement=null;movementTarget=null;contractLink=null;manual=false;prediction=null;createContract=null;createdPlan=null;savedContract=null
            confirming=null;planHistory=null;poolTarget=null;poolPaymentPlan=null;arrangementPlan=null
            batchDate=null;reconciliationPlan=null;reconciliationParents=emptyList()
            poolBillReturn=null;batchPoolReturn=null;arrangementReturn=null;poolPaymentReturn=null;reconciliationReturn=null
            consumedRequest=requestId
        }
    }
    BackHandler(enabled=selectedPlan!=null || addArrangement || destination.page!=null || editMode!=null || resume?.kind=="RULE"){back()}
    Column(modifier.fillMaxSize()) screen@{
        head()
        Column(Modifier.padding(pageContentPadding())) {
            FinancialAccountBar(account.name,(if(selectedPeriod!=null)word("期次明细","各回の明細")else if(selectedPlan!=null)word("计划详情","プラン詳細")else if(editMode!=null || resume?.kind=="RULE")financialTaskLabel("manage/rules")else if(destination.page!=null)financialTaskLabel(tab)else null),::back)
            FinancialTaskTabs(if(periodReturn=="statement"||planReturn=="statement")"statement"else destination.tab){navigate(it);editMode=null;resumedDraft=null;limitRuleReturn=null;limitDraftReturn=null;vm.financialRuleReview.value=null}
        }
        Spacer(Modifier.height(when(LocalYohaku.current.id){"journal"->14.dp;"objects"->16.dp;else->12.dp}))
        // Do not measure a restored scroll position against the temporary loading placeholder.
        if(finance==null) {
            Column(Modifier.padding(pageContentPadding())) {
                YText(readFailure?:word("正在读取金融资料…","金融情報を読込中…"),12,Modifier.testTag(if(readFailure==null)"finance-loading"else "finance-load-failure"))
                YButton(word("重新读取","再読込"),vm::retryRead)
            }
            return@screen
        }
        val scrollKey=if(editMode!=null || resume?.kind=="RULE") "rule-editor-${editMode?:resume?.mode}" else if(selectedPeriod!=null)"period-$selectedPeriod" else if(selectedPlan!=null)"plan-$selectedPlan" else tab
        scrolls.SaveableStateProvider(s.book.id+"/"+account.id+"/"+scrollKey) {
        val scroll=rememberSaveable(saver=ScrollState.Saver){ScrollState(0)}
        Column(Modifier.weight(1f).verticalScroll(scroll).testTag("page-scroll")) {
        Column(Modifier.padding(pageContentPadding()).padding(bottom=28.dp),verticalArrangement=Arrangement.spacedBy(15.dp)) content@{
    if(tab=="overview")FinancialAccountLead(s,account,finance,vm)
    val rules=finance.rules.filter{it.accountId==account.id}
    val modes=if(account.type==AccountType.CREDIT.name)listOf(FinanceMode.SINGLE,FinanceMode.INSTALLMENT,FinanceMode.REVOLVING)else listOf(FinanceMode.FIXED_LOAN,FinanceMode.CREDIT_LINE)
    fun openRuleLimits(mode:String) {
        limitRuleReturn=mode;limitDraftReturn=resumedDraft;limitSourceReturn=path;limitPageReturn=pageReturn
        resumedDraft=null;editMode=null;navigate("limits");vm.financialRuleReview.value=null
    }
    if(resume?.kind=="RULE"){FinancialDraftResume(s,account,finance,resume,vm,{resumedDraft=null},onLimits={openRuleLimits(requireNotNull(resume.mode))});return@content}
    editMode?.let{raw->val mode=FinanceMode.valueOf(raw);val latest=rules.filter{it.mode==raw}.maxByOrNull{it.version}
        FinancialRuleEditor(s,account,mode,latest?.let{vm.repository.json.decodeFromString(it.json)},vm,{editMode=null},onLimits={openRuleLimits(mode.name)});return@content}
    if(tab=="overview") {
        FinancialDraftList(s,account,vm,{resumedDraft=vm.repository.json.encodeToString(it)},onOpen={navigate("manage/drafts",returnTo="overview")})
        FinancialAccountOverview(s,account,finance,vm,{month->statementMonth=month;navigate("statement")},{bill->movementTarget=bill;movement=if(bill==null)"repay"else"repay-bill"},{arrangementPlan=it},{navigate("rules")},{navigate("plans")},{e->if(s.repayments.any{it.eventId==e.id}){movementTarget=e.id;movement="repay-edit"}else onRecord(e)},{navigate("manage/contracts")},onFund,{batchDate=it},onLimits={navigate("limits")})
    }else if(tab=="statement") {
        FinancialStatements(s,account,finance,vm,statementMonth,{statementMonth=it},{navigate("plans")},{batchDate=it},onPeriod={id->val bill=finance.bills.first{it.id==id};navigate("plans/${bill.planId}/$id");periodReturn="statement"},onRepayDate={movementTarget=it;movement="repay-date"})
    }else if(tab=="manage/limits") {
        if(limitRuleReturn!=null)YButton(word("返回规则草稿","条件の下書きへ戻る"),{returnFromLimits()},Modifier.testTag("finance-return-rule"))
        CreditLimitSection(s,account,finance,vm)
    }else if(tab=="manage/rules") {
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
    }else if(tab=="manage/contracts") {
        YButton(word("＋ 记录实际借入","＋ 実際の借入を記録"),{movementTarget=null;movement="borrow"},Modifier.testTag("finance-add-borrowing"),primary=true,enabled=!busy)
        YButton(word("补充期初合同","期首契約を補完"),{movementTarget=null;movement="opening"},Modifier.testTag("finance-add-opening-contract"),enabled=!busy)
        finance.contracts.filter{it.accountId==account.id}.forEach{c->Panel(Modifier.testTag("finance-contract-${c.id}")) {
            YText(c.name,18);YText(financeLabel(c.mode)+" · "+c.date,11)
            FinancialContractFacts(s,c)
            if(savedContract==c.id&&createdPlan!=null&&finance.contractPlans.none{it.planId==createdPlan&&it.contractId==c.id})YText(word("计划已保存，尚未关联","プランは保存済み・未関連です"),12,color=LocalYohaku.current.accent)
            val funding=c.fundingEventId?.let{id->s.events.firstOrNull{it.id==id}}
            if(funding?.deletedAt!=null)YText(word("借入记录已在回收站；独立还款和合同资料仍保留。","借入記録はごみ箱にあります。独立した返済・契約情報は保持しています。"),11,color=LocalYohaku.current.warm)
            YActionRow {
                if(c.fundingEventId==null||funding?.deletedAt==null)YButton(word("编辑合同 / 借入","契約・借入を編集"),{movementTarget=c.id;movement=if(c.fundingEventId==null)"opening"else "borrow"},enabled=!busy)
                YButton(word("关联已有计划","既存プランを関連付け"),{if(savedContract!=c.id)createdPlan=null;contractLink=c.id},Modifier.testTag("finance-link-contract-${c.id}"),enabled=!busy)
                YButton(word("对这份合同还款","この契約へ返済"),{movementTarget=c.id;movement="repay-contract"},Modifier.testTag("finance-repay-contract-${c.id}"),enabled=!busy)
            }
        }}
    }else if(tab=="manage/records") {
        YButton(word("＋ 记录实际还款","＋ 実際の返済を記録"),{movementTarget=null;movement="repay"},Modifier.testTag("finance-add-repayment"),primary=true,enabled=!busy)
        s.repayments.filter{it.debtAccountId==account.id}.mapNotNull{p->s.visibleEvents.firstOrNull{it.id==p.eventId}?.let{it to p}}.sortedByDescending{it.first.date}.forEach{(e,p)->Panel {
            YText(e.date,14);FinancialRepaymentFacts(s,e,p)
            YButton(word("核对 / 编辑还款","返済を確認・編集"),{movementTarget=e.id;movement="repay-edit"},Modifier.testTag("finance-edit-repayment-${e.id}"),enabled=!busy)
        }}
        val unallocated=s.repaymentPrincipalNeedingAllocation(finance,account.id,finance.plans.filter{vm.repository.financialPredictionStale(s,finance,it)}.map{it.id}.toSet())
        if(unallocated!=0L)YText(word("尚未分配到具体义务的已清偿本金：","個別債務への未充当返済元金：")+Money(currency,unallocated).display(),12,color=LocalYohaku.current.warm)
    }else if(tab=="manage") {
        val pages=if(account.type==AccountType.LOAN.name)listOf("contracts","records","rules","limits","history","drafts") else listOf("records","rules","limits","history","drafts")
        pages.forEach{id->FinancialDirectoryRow(id,financialTaskLabel("manage/$id")){navigate("manage/$id")}}
    }else if(tab=="manage/drafts") {
        FinancialDraftList(s,account,vm,{resumedDraft=vm.repository.json.encodeToString(it)},alwaysExpanded=true)
    }else if(tab=="manage/history") {
        FinancialBatchHistoryList(s.book.id,account.id,vm,{id->navigate("plans");selectedPlan=id})
        FinancialDirectoryRow("history-rules",word("规则版本历史","条件のバージョン履歴")){history=true;navigate("rules",returnTo="manage/history")}
        FinancialDirectoryRow("history-limits",word("额度配置版本历史","利用枠設定の履歴")){navigate("limits",returnTo="manage/history")}
        finance.plans.filter{it.accountId==account.id&&it.mode!="SINGLE"}.forEach{plan->
            YButton(plan.name+" · "+word("计划版本与变化","予定の履歴と変化"),{planHistory=plan.id},Modifier.testTag("finance-plan-history-${plan.id}"))
        }
    }else if(tab=="plans") {
        val plans=finance.plans.filter{it.accountId==account.id&&it.active&&it.mode!="SINGLE"}
        if(selectedPlan==null) {
            YButton(word("添加还款安排","返済予定を追加"),{addArrangement=true},Modifier.testTag("finance-add-arrangement"),primary=true,enabled=!busy)
            if(plans.isEmpty())YEmpty(word("暂无多期计划；一次付消费请在账单页查看。","複数回の予定はありません。一括払いの利用は請求ページで確認できます。"))
            plans.forEach{plan->FinancialPlanSummary(s,finance,plan,vm,{selectedPlan=plan.id})}
        val archived=finance.plans.filter{it.accountId==account.id&&!it.active&&it.origin!="POOL_MEMBER"&&it.mode!="SINGLE"}
        if(archived.isNotEmpty()) {
            YButton(word("已替换计划","置換済みの予定")+if(archivedPlans)" −"else" +",{archivedPlans=!archivedPlans},Modifier.testTag("finance-archived-open"))
            if(archivedPlans)archived.forEach{p->YButton(p.name+" · "+financeLabel(p.mode),{selectedPlan=p.id},Modifier.testTag("finance-archived-${p.id}"))}
        }
        } else finance.plans.firstOrNull{it.id==selectedPlan}?.let{plan->
            val bill=finance.bills.firstOrNull{it.id==selectedPeriod&&it.planId==plan.id}
            if(bill!=null)FinancialPlanPeriod(s,finance,plan,bill,vm,{movementTarget=it;movement="repay-bill"},{confirming=it},onRecord,onPayment={movementTarget=it.id;movement="repay-edit"},onPlan={planReturn=periodReturn?:planReturn;periodReturn=null;selectedPeriod=null},onCompare={arrangementPlan=plan.id},onHistory={planHistory=plan.id})
            else FinancialPlanDetail(s,finance,plan,vm,{selectedPeriod=it},{planHistory=it},{poolPaymentPlan=it},{arrangementPlan=it},
                {if(plan.origin=="POOL")poolTarget=it else prediction=it},{reconciliationParents=emptyList();reconciliationPlan=it})
        }
    }

    resume?.let{context->FinancialDraftResume(s,account,finance,context,vm,{resumedDraft=null},{bill->
        resumedDraft=null
        if(context.kind=="POOL")poolBillReturn=context.targetId.orEmpty() else if(context.kind=="ARRANGEMENT_REVIEW")reconciliationReturn=context.targetId
        confirming=bill
    },{parent->reconciliationParents=listOfNotNull(context.targetId);resumedDraft=null;reconciliationPlan=parent},onPlanSaved=::planSaved,onPool={id->batchPoolReturn=context.targetId;resumedDraft=null;poolTarget=id.orEmpty()})}
    batchDate?.let{date->BatchRevolvingEditor(s,account,finance,date,vm,{batchDate=null},{id->batchPoolReturn=date;batchDate=null;poolTarget=id.orEmpty()})}
    poolTarget?.let{id->FinancialPoolEditor(s,account,finance,finance.plans.firstOrNull{it.id==id},vm,{poolTarget=null;batchDate=batchPoolReturn;batchPoolReturn=null;arrangementPlan=arrangementReturn;arrangementReturn=null;poolPaymentPlan=poolPaymentReturn;poolPaymentReturn=null},{bill->poolBillReturn=id;poolTarget=null;confirming=bill})}
    poolPaymentPlan?.let{id->finance.plans.firstOrNull{it.id==id&&it.active&&it.origin=="POOL"}?.let{plan->FinancialPoolPaymentEditor(s,account,finance,plan,vm,{poolPaymentPlan=null},{poolPaymentReturn=id;poolPaymentPlan=null;poolTarget=id},{bill->poolPaymentReturn=id;poolPaymentPlan=null;confirming=bill})}}
    reconciliationPlan?.let{id->finance.plans.firstOrNull{it.id==id}?.let{plan->FinancialReconciliationEditor(s,account,finance,plan,vm,{reconciliationPlan=reconciliationParents.lastOrNull();reconciliationParents=reconciliationParents.dropLast(1)},{bill->reconciliationReturn=id;reconciliationPlan=null;confirming=bill},{parent->reconciliationParents=reconciliationParents+id;reconciliationPlan=parent})}}
    arrangementPlan?.let{id->finance.plans.firstOrNull{it.id==id}?.let{FinancialArrangementEditor(s,account,finance,it,vm,{arrangementPlan=null},{pool->arrangementReturn=id;arrangementPlan=null;poolTarget=pool},{bill->arrangementReturn=id;arrangementPlan=null;confirming=bill})}}
    planHistory?.let{id->finance.plans.firstOrNull{it.id==id}?.let{FinancialPlanHistory(it,vm,{planHistory=null},{planId->planHistory=null;navigate("plans");selectedPlan=planId})}}
    movement?.let{kind->
        if(kind in listOf("borrow","opening"))FinancialBorrowingEditor(s,account,vm,{movement=null},kind=="opening",finance.contracts.firstOrNull{it.id==movementTarget})
        else FinancialRepaymentEditor(s,account,finance,vm,{movement=null},eventId=movementTarget.takeIf{kind=="repay-edit"},billId=movementTarget.takeIf{kind=="repay-bill"},contractId=movementTarget.takeIf{kind=="repay-contract"},paymentDate=movementTarget.takeIf{kind=="repay-date"})
    }
    if(addArrangement)FinancialAddArrangement(account,{addArrangement=false;if(createContract!=null){contractLink=createContract;createContract=null}},{addArrangement=false;manual=true},{addArrangement=false;prediction=""},{addArrangement=false;poolTarget=finance.plans.firstOrNull{it.accountId==account.id&&it.active&&it.origin=="POOL"}?.id?:""},contract=createContract!=null)
    contractLink?.let{id->finance.contracts.firstOrNull{it.id==id}?.let{c->
        FinancialContractPlanPicker(s,finance,c,vm,createdPlan,{contractLink=null},{createContract=c.id;contractLink=null;addArrangement=true},
            {p->vm.execute(onSuccess={vm.haptic(Haptic.Done);contractLink=null;createdPlan=null}){vm.repository.linkFinancialContractPlan(s.book.id,c.id,p)}})
    }}
    prediction?.let{id->FinancialPredictionEditor(s,account,finance,if(id.isEmpty())null else finance.plans.first{it.id==id},vm,{prediction=null;if(createContract!=null){contractLink=createContract;createContract=null}},finance.contracts.firstOrNull{it.id==createContract},::planSaved)}
    if(manual)ManualFinancialPlanEditor(s,account,vm,{manual=false;if(createContract!=null){contractLink=createContract;createContract=null}},finance.contracts.firstOrNull{it.id==createContract},::planSaved)
    confirming?.let{id->finance.bills.firstOrNull{it.id==id}?.let{FinancialBillConfirmationEditor(s,account.id,it,currency,vm,{confirming=null;if(poolBillReturn!=null){poolTarget=poolBillReturn;poolBillReturn=null}else{arrangementPlan=arrangementReturn;arrangementReturn=null;poolPaymentPlan=poolPaymentReturn;poolPaymentReturn=null;reconciliationPlan=reconciliationReturn;reconciliationReturn=null}})}}
        }
        }
        }
    }
}

@Composable internal fun FinancialBillFacts(bill:FinancialBillEntity,currency:Currency,showAmountStatus:Boolean=true) {
    fun money(value:Long?)=value?.let{Money(currency,it).display()}
    YText(word("第${bill.number}期","第${bill.number}回")+" · "+bill.date,14)
    if(showAmountStatus)YText(if(bill.confirmed)word("平台已确认金额 · 尚非付款记录","返済先の確定金額・支払記録とは別")else word("规则预测","条件による予測"),11,color=LocalYohaku.current.accent)
    RecordFacts(listOf(word("本金","元金") to (money(bill.principalMinor)?:word("未知","不明")),word("利息 / 费用","利息・手数料") to (money(bill.feeMinor)?:word("未知","不明")),word("合计","合計") to (if(!bill.confirmed&&bill.basisJson?.let{kotlinx.serialization.json.Json.decodeFromString<FinancialCalculationBasis>(it).additionalFeeUnknown}==true)word("手续费待补，合计未知","手数料未入力・合計不明")else Money(currency,bill.totalMinor).display())))
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
