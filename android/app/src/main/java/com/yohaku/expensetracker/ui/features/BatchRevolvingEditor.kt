package com.yohaku.expensetracker.ui.features

import androidx.compose.runtime.*
import androidx.compose.foundation.rememberScrollState
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
import java.time.*

@Composable internal fun BatchRevolvingEditor(s:BookSnapshot,a:AccountEntity,f:FinancialGraph,date:String,vm:AppViewModel,onClose:()->Unit,onPool:((String?)->Unit)?=null) {
    var editingPool by remember{mutableStateOf(false)}
    var poolTarget by remember{mutableStateOf<String?>(null)}
    if(editingPool){FinancialPoolEditor(s,a,f,f.plans.firstOrNull{it.id==poolTarget},vm,{editingPool=false});return}
    FinancialDraftHost(s,FinancialDraftContext("BATCH_REVOLVING",a.id,targetId=date),vm,onClose) {
        BatchRevolvingEditorContent(s,a,f,date,vm,onClose,onPool?:{poolTarget=it;editingPool=true})
    }
}

@Composable private fun BatchRevolvingEditorContent(s:BookSnapshot,a:AccountEntity,f:FinancialGraph,date:String,vm:AppViewModel,onClose:()->Unit,onPool:(String?)->Unit) {
    val session=requireNotNull(LocalFinancialDraft.current)
    val c=Currency.valueOf(a.currency);val busy by vm.busy.collectAsStateWithLifecycle()
    val pool=f.plans.firstOrNull{it.accountId==a.id&&it.active&&it.origin=="POOL"&&it.mode=="REVOLVING"}
    var effective by financialField("effective"){LocalDate.now(vm.repository.clock).toString()}
    val candidates=vm.repository.batchRevolvingCandidates(s,f,a.id,date,effective)
    var selected by financialField("selected"){candidates.map{it.planId}}
    val eligible=selected.filter{id->candidates.any{it.planId==id}}
    val total=candidates.filter{it.planId in eligible}.sumOf{it.principalMinor}
    var amount by financialField("amount"){Money(c,total).decimal.toPlainString()}
    var fee by financialField("fee"){""};var feeDate by financialField<String?>("feeDate"){null}
    var included by financialField("included"){false}
    var owner by remember{mutableStateOf<Int?>(null)};var review by remember{mutableStateOf<BatchRevolvingReview?>(null)}
    var applying by remember{mutableStateOf(false)};var platform by remember{mutableStateOf(false)};var exception by remember{mutableStateOf(false)}
    var appliedAt by financialField("appliedAt"){vm.repository.clock.instant().toString()}
    var stale by remember{mutableStateOf(false)}
    val value=review
    val formScroll=rememberScrollState();val reviewScroll=rememberScrollState();val applyScroll=rememberScrollState()
    LaunchedEffect(value,applying){if(applying)applyScroll.scrollTo(0)else if(value!=null)reviewScroll.scrollTo(0)}
    FinancialFlow(word("批量转为循环还款","まとめてリボ"),step=if(applying)2 else if(value!=null)1 else 0,processing=true,
        onClose={review=null;platform=false;exception=false;onClose()},
        onBack={if(applying){applying=false;platform=false;exception=false}else review=null},
        scrollState=if(applying)applyScroll else if(value!=null)reviewScroll else formScroll,actions={
            if(value==null){if(pool!=null){YButton(word("比较当前与新方案","現在と新プランを比較"),{vm.execute{
                review=vm.repository.previewBatchRevolving(s.book.id,BatchRevolvingInput(a.id,date,eligible,Money.parse(amount,c).minor,pool.id,effective,fee.takeIf{it.isNotBlank()}?.let{Money.parse(it,c).minor},feeDate,included));stale=false
            }},Modifier.testTag("batch-revolving-preview"),primary=true,enabled=!busy&&eligible.isNotEmpty())}}else if(!applying){YButton(word("记录已办理","手続き済みを記録"),{applying=true;platform=false;exception=false},Modifier.testTag("batch-revolving-record"),primary=true,enabled=!busy&&value.calculation is Calculation.Ready)}else {YButton(word("确认记录已办理","手続き済みとして確定"),{var saved=false;vm.execute(onSuccess={if(saved)onClose()else{review=null;applying=false;stale=true}}){saved=commitFinancialDraft(session){vm.repository.commitBatchRevolving(value,appliedAt,platform,exception)}!=null}},Modifier.testTag("batch-revolving-confirm"),primary=true,enabled=!busy&&platform)}
        }) {
        YText(word("循环还款（リボ）","リボ・循環返済"),12,color=LocalYohaku.current.muted)
        YText(a.name+" · "+date,18)
        if(value==null)FinancialDraftNotice()
        if(stale)FinanceRefreshed()
        if(pool==null) {
            YText(word("请先建立本账户的循环还款计划，再选择本期转换金额。","先にこの口座のリボ・循環返済プランを作成してから、当期の変更額を選択してください。"),12)
            YButton(word("建立循环还款计划","リボ・循環返済プランを作成"),{onPool(null)},primary=true)
        }else if(value==null) {
            FinancialReviewSection(word("本期可转的一次付本金","当期の変更可能な一括元金")) {
                YText(word("含已确认未付款账单；按消费日、创建顺序分配，最后一笔可只转一部分。原费用与剩余本金保留原付款日。","確定済み・未払いの請求も対象です。利用日・作成順に配分し、最後の1件は一部変更できます。元の手数料・残元金は元の支払日に残ります。"),12)
                candidates.forEach{part->val p=f.plans.first{it.id==part.planId}
                    YToggle(part.date+" · "+p.name+" · "+Money(c,part.principalMinor).display(),part.planId in eligible,{checked->
                        selected=if(checked)(eligible+part.planId).distinct()else eligible-part.planId
                        amount=Money(c,candidates.filter{it.planId in selected}.sumOf{it.principalMinor}).decimal.toPlainString()
                    },Modifier.testTag("batch-revolving-select-${part.planId}"))
                }
                if(candidates.isEmpty())YText(word("该期已无可转换账单。","この回に変更可能な請求はありません。"),12)
                RecordFacts(listOf(word("所选本金合计","選択元金の合計") to Money(c,total).display()))
            }
            YField(word("转换金额","変更額"),amount,{amount=it},Modifier.testTag("batch-revolving-amount"),numeric=true)
            YSelect(word("目标循环还款计划","変更先のリボ・循環返済プラン"),pool.id,listOf(pool.id to pool.name),{})
            YDateFields(listOf(word("变更生效日","変更適用日") to effective),owner,{owner=it},{_,v->effective=v})
            YField(word("办理费 / 未知留空，无费用填 0","変更手数料・不明は空欄、無料は0"),fee,{fee=it},Modifier.testTag("batch-revolving-fee"),numeric=true)
            val dates=vm.repository.json.decodeFromString<FinancialPredictionInput>(pool.termsJson!!).terms.paymentDates.filter{it>=effective}
            YSelect(word("办理费计入的付款日 / 费用0时可不选","変更手数料を含む支払日・無料は未選択可"),feeDate,listOf(null to word("请选择","選択してください"))+dates.map{it as String? to it},{feeDate=it;included=false})
            YToggle(word("该期已确认费用包含本次办理费","当期の確定費用に今回の変更手数料を含む"),included,{included=it})
            YButton(word("查看 / 重算循环还款计划","リボ・循環返済プランを確認・再計算"),{onPool(pool.id)})

        }else if(!applying) {
            FinancialChangeSummary {
            value.transfers.forEach{part->
                YText(f.plans.first{it.id==part.planId}.name,14)
                RecordFacts(listOf(word("转入循环还款","リボへ変更") to Money(c,part.principalMinor).display(),word("原一次付保留本金","元の一括に残る元金") to Money(c,part.retainedPrincipalMinor).display()))
            }}
            when(val result=value.calculation) {
                is Calculation.Ready->PoolPaymentComparisonFacts(value.comparison!!,c,s)
                is Calculation.Incomplete->FinancialReadiness(RuleReadiness.Incomplete(result.fields))
                is Calculation.Unsupported->FinancialCalculationResult(result,c,compact=true)
                is Calculation.CannotSettle->FinancialCalculationResult(result,c,compact=true)
            }
            val deadlines=value.reviews.flatMap{it.deadlines}.distinct()
            if(deadlines.isEmpty())YText(word("变更截止时间未配置，请以平台确认为准。","変更締切は未設定です。返済先で確認してください。"),12)
            deadlines.forEach{YText(FinancialDisplayFacts.time(it.instant(),ZoneId.of(it.zoneId),LocalLanguage.current),12)}

        }else {
            RecordFacts(listOf(word("本批次转入本金","今回の移行元金") to Money(c,value.input.principalMinor).display(),word("办理费","変更手数料") to Money(c,value.input.feeMinor!!).display()))
            YText(word("只记录平台已办理的结果；本批次同时保存，可从办理历史撤销这次转换记录。实际资金和消费金额不变。","返済先で手続き済みの結果をまとめて保存します。履歴から今回の変更記録を取り消せます。実資金・支出額は変わりません。"),12)
            val zone=ZoneId.of(value.reviews.first().zoneId)
            FinancialTimeField(appliedAt,zone,owner,{owner=it},{appliedAt=it})
            YToggle(word("已在平台实际办理并确认以上条件","返済先で手続き済みで上記の条件を確認した"),platform,{platform=it},Modifier.testTag("batch-revolving-platform"))
            if(value.reviews.any{r->r.deadlines.any{Instant.parse(appliedAt)>it.instant()}})YToggle(word("平台已明确接受此次期限外变更","返済先が期限後の変更を明示的に受理した"),exception,{exception=it})

        }
    }
}
