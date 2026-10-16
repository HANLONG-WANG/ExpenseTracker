package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
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
import java.time.*

@Serializable private data class ArrangementDateDraft(val date:String,val start:String,val end:String,val total:String="",val principal:String="",val fee:String="")
@Serializable private data class PoolTransferDraft(val billId:String,val principal:String="",val cancelFee:String="")
@Serializable private data class ClearingMoveDraft(val allocationId:String,val destination:String?=null,val principal:String="0",val interest:String="0",val fee:String="0")

@Composable internal fun FinancialArrangementEditor(s:BookSnapshot,account:AccountEntity,finance:FinancialGraph,old:FinancialPlanEntity,vm:AppViewModel,onClose:()->Unit,onPoolReview:((String)->Unit)?=null,onPoolBill:((String)->Unit)?=null) {
    FinancialDraftHost(s,FinancialDraftContext("ARRANGEMENT",account.id,targetId=old.id),vm,onClose){FinancialArrangementEditorContent(s,account,finance,old,vm,onClose,onPoolReview,onPoolBill)}
}

@Composable private fun FinancialArrangementEditorContent(s:BookSnapshot,account:AccountEntity,finance:FinancialGraph,old:FinancialPlanEntity,vm:AppViewModel,onClose:()->Unit,onPoolReview:((String)->Unit)?=null,onPoolBill:((String)->Unit)?=null) {
    val draftSession=requireNotNull(LocalFinancialDraft.current)
    val currency=Currency.valueOf(account.currency)
    val today=LocalDate.now(vm.repository.clock).toString()
    val defaultName=old.name+if(old.origin=="POOL")word(" · 分出安排","・分割した予定")else ""
    var name by financialField("name"){defaultName}
    var mode by financialField("mode"){if(account.type==AccountType.CREDIT.name)FinanceMode.INSTALLMENT.name else FinanceMode.FIXED_LOAN.name}
    var effective by financialField("effective"){today}
    var count by financialField("count"){""}
    var firstPeriod by financialField("firstPeriod"){"1"}
    var firstDate by financialField("firstDate"){finance.bills.filter{it.planId==old.id&&it.active&&it.date>=today}.minOfOrNull{it.date}?:LocalDate.parse(today).plusMonths(1).toString()}
    var firstEnd by financialField("firstEnd"){today}
    var bank by financialField("bank"){finance.bills.firstOrNull{it.planId==old.id&&it.active}?.paymentAccountId}
    var treatment by financialField<String?>("treatment"){null}
    var fee by financialField("fee"){""}
    var loanLimitDelta by financialField("loanLimitDelta"){""}
    var encoded by financialField("encoded"){"[]"}
    var manual by financialField("manual"){false}
    var manualComplete by financialField("manualComplete"){false}
    var movesJson by financialField("clearingMoves"){"[]"}
    val clearingMoves=vm.repository.json.decodeFromString<List<ClearingMoveDraft>>(movesJson)
    var poolPartsJson by financialField("poolParts"){"[]"}
    var unbilled by financialField("poolUnbilled"){""}
    val poolParts=vm.repository.json.decodeFromString<List<PoolTransferDraft>>(poolPartsJson)
    val poolBills=if(old.origin=="POOL")finance.bills.filter{it.active&&it.date>=effective&&(it.planId==old.id||poolInput(old)?.facts?.retained?.any{r->r.bill.id==it.id}==true)}.sortedBy{it.date}else emptyList()
    fun changePoolPart(index:Int,value:PoolTransferDraft){poolPartsJson=vm.repository.json.encodeToString(poolParts.mapIndexed{i,row->if(i==index)value else row})}
    val laterClearings=arrangementLaterAllocations(s,finance,old,effective).filter{old.origin!="POOL"||it.billId in poolParts.map{p->p.billId}}
    fun changeMove(index:Int,value:ClearingMoveDraft){movesJson=vm.repository.json.encodeToString(clearingMoves.mapIndexed{i,row->if(i==index)value else row})}
    val rows=vm.repository.json.decodeFromString<List<ArrangementDateDraft>>(encoded)
    val selected=FinanceMode.valueOf(mode)
    val revolving=selected in listOf(FinanceMode.REVOLVING,FinanceMode.CREDIT_LINE)
    var owner by remember{mutableStateOf<Int?>(null)}
    var merge by financialField("mergePool"){false}
    var mergeFeeDate by financialField<String?>("mergeFeeDate"){null}
    var mergeIncluded by financialField("mergeFeeIncluded"){false}
    val destinationPool=finance.plans.firstOrNull{old.origin!="POOL"&&it.accountId==account.id&&it.origin=="POOL"&&it.active&&it.mode==mode}
    val destinationInput=destinationPool?.termsJson?.let{vm.repository.json.decodeFromString<FinancialPredictionInput>(it)}
    val merging=merge&&destinationPool!=null
    val mergeRows=destinationInput?.terms?.paymentDates?.mapIndexed{i,date->ArrangementDateDraft(date,destinationInput.terms.accrualWindows[i].start,destinationInput.terms.accrualWindows[i].end)}?.filter{it.date>=effective}.orEmpty()
    var applying by remember(s.book.id,old.id){mutableStateOf(false)}
    var platform by remember(s.book.id,old.id){mutableStateOf(false)}
    var exception by remember(s.book.id,old.id){mutableStateOf(false)}
    var appliedInstant by financialField("appliedInstant"){vm.repository.clock.instant().toString()}
    val busy by vm.busy.collectAsStateWithLifecycle()
    val value by vm.financialArrangementReview.collectAsStateWithLifecycle()
    val review=value?.takeIf{it.original.id==old.id&&it.original.bookId==s.book.id}
    val refreshed by vm.financialReviewRefreshed.collectAsStateWithLifecycle()
    val formScroll=rememberScrollState();val reviewScroll=rememberScrollState();val applyScroll=rememberScrollState()
    LaunchedEffect(review,applying){if(applying)applyScroll.scrollTo(0)else if(review!=null)reviewScroll.scrollTo(0)}
    fun clearRows(){encoded="[]";owner=null}
    YSheet(if(applying)word("记录已办理","手続き記録")else if(review!=null)word("付款方案比较","プラン比較")else word("调整付款方案","支払の変更"),{
        if(applying)applying=false else if(review!=null)vm.financialArrangementReview.value=null else onClose()
    },scrollState=if(applying)applyScroll else if(review!=null)reviewScroll else formScroll) {
        if(review==null) {
            FinancialDraftNotice()
            ThemeAuditPanel(old.name){YText(word("选择新安排并比较当前与未来付款。试算不改账、不增加借款；真实办理后再独立确认。","新しい予定で現在と将来の支払を比較します。試算で記録・借入は変えず、実際の手続き後に別途確定します。"),11)}
            YText(financeLabel(old.mode)+" → "+financeLabel(mode),14)
            YField(word("新方案名称","新プラン名"),name,{name=it})
            val modes=if(account.type==AccountType.CREDIT.name)listOf(FinanceMode.SINGLE,FinanceMode.INSTALLMENT,FinanceMode.REVOLVING)else listOf(FinanceMode.FIXED_LOAN,FinanceMode.CREDIT_LINE)
            YSelect(word("新付款方式","新しい支払方式"),mode,modes.filter{old.origin!="POOL"||it.name!=old.mode}.map{it.name to financeLabel(it.name)},{m->
                mode=m;merge=false;mergeFeeDate=null;mergeIncluded=false;clearRows()
                val r=finance.rules.filter{it.accountId==account.id&&it.mode==m&&it.effectiveFrom<=effective}.maxWithOrNull(compareBy<FinancialRuleEntity>{it.effectiveFrom}.thenBy{it.version})?.let{vm.repository.json.decodeFromString<FinancialRuleVersion>(it.json)}
                if(r?.billing?.issues()?.isEmpty()==true){val d=billingDates(LocalDate.parse(effective),r.billing);firstDate=d.paymentDate.toString();firstEnd=d.closingDate.toString()}
            })
            val dates=mutableListOf(word("变更生效日","変更適用日") to effective)
            if(destinationPool==null) {
                YToggle(word("手动录入平台新方案","返済先の新プランを手入力"),manual,{manual=it},Modifier.testTag("arrangement-manual"))
                if(manual) {
                    YText(word("按平台给出的日期和金额录入，无需配置计算规则。本金或费用未知请留空；这里的金额不含上方另列的变更费，变更费只加入新方案首期。","返済先の支払日・金額を入力し、計算条件は不要です。元金・手数料が不明なら空欄にしてください。各回は別途変更手数料を含めず、変更手数料は初回にのみ加えます。"),11)
                    YToggle(word("已列出完整新方案","新プランの全期間を入力済み"),manualComplete,{manualComplete=it},Modifier.testTag("arrangement-manual-complete"))
                }
            }
            if(destinationPool==null)dates+=word("首次付款日","初回支払日") to firstDate
            if(revolving&&!manual&&destinationPool==null)dates+=word("首次计费截止日","初回計算終了日") to firstEnd
            YDateFields(dates,owner?.takeIf{it<10},{owner=it},{i,v->when(i){0->effective=v;1->firstDate=v;else->firstEnd=v};clearRows()})
            if(selected!=FinanceMode.SINGLE&&destinationPool==null) {
                YField(word("预测期数","予測回数"),count,{count=it;clearRows()},numeric=true)
                if(!manual)YField(word("新安排从第几期计费","新しい予定の計算開始回数"),firstPeriod,{firstPeriod=it;clearRows()},numeric=true)
            }
            if(destinationPool!=null) {
                YToggle(word("并入已有账户余额池","既存の口座プールに統合"),merge,{merge=it},Modifier.testTag("arrangement-merge-pool"))
                YText(destinationPool.name,16)
                YText(word("本金与办理费直接加入原池，付款日期、扣款账户及计息规则沿用原池；不另建第二份循环还款计划。","元金・変更手数料を元のプールへ加え、支払日・引落口座・計算条件を引き継ぎます。別の循環返済予定は作成しません。"),11)
                YText(mergeRows.joinToString(" · "){it.date},11)
                YSelect(word("办理费计入的付款日 / 费用0时可不选","変更手数料を含む支払日・無料は未選択可"),mergeFeeDate,listOf(null to word("请选择","選択してください"))+mergeRows.map{it.date as String? to it.date},{mergeFeeDate=it;mergeIncluded=false},Modifier.testTag("arrangement-merge-fee-date"))
                YToggle(word("该期已确认费用包含本次办理费","当期の確定費用に今回の変更手数料を含む"),mergeIncluded,{mergeIncluded=it},Modifier.testTag("arrangement-merge-fee-included"))
                YText(word("已确认或有付款关联的原池账单不会自动改写。若本期新增费用未包含，请先核对原池账单，再明确勾选；金额不足时仍不能办理。","確定・支払関連の元請求は自動変更しません。新しい費用が未反映なら元の請求を先に確認し、含むことを明示してください。金額が不足する場合は手続きできません。"),11)
                finance.bills.filter{it.active&&it.date==mergeFeeDate&&(it.planId==destinationPool.id||destinationInput?.pool?.facts?.retained?.any{r->r.bill.id==it.id}==true)}.forEach{bill->YButton(word("核对原池本期账单","元プールの当期請求を確認")+" · "+bill.date,{if(onPoolBill!=null)onPoolBill(bill.id)else onClose()},Modifier.testTag("arrangement-merge-bill-${bill.id}"))}
                YButton(word("查看 / 重算原池，保留本次输入","元プールを確認・再計算して入力を保持"),{if(onPoolReview!=null)onPoolReview(destinationPool.id)else onClose()},Modifier.testTag("arrangement-merge-open-pool"))
            }
            if(destinationPool==null)YSelect(word("新扣款账户","新しい引落口座"),bank,listOf(null to word("按规则 / 未确定","条件から取得・未定"))+s.accounts.filter{it.active&&!AccountType.valueOf(it.type).debt}.map{it.id as String? to (it.name+" · "+it.currency)},{bank=it})
            YSelect(word("原方案剩余费用如何处理","元の予定の残手数料の扱い"),treatment,listOf(null to word("请选择明确条件","明確な条件を選択"),OriginalFeeTreatment.RETAIN.name to word("保留原费用","元の手数料を保持"),OriginalFeeTreatment.CANCELLED_BY_PLATFORM.name to word("平台取消原费用","返済先が元の手数料を取消")),{treatment=it})
            if(old.origin=="POOL") {
                ThemeSection(word("从余额池转出的部分","残高プールから移す部分"),"SELECT PRINCIPAL")
                YText(word("选择具体账单的部分本金，或填写尚未排期的本金。按生效日核对可转范围；生效后的已记录清偿另选归属。未选择部分仍留在原池，逾期本金不会被转走。","請求の元金の一部、または未予定の元金を選択します。適用日で移せる範囲を確認し、その後の記録済み返済は別に充当先を選びます。未選択分は元のプールに残し、延滞元金は移しません。"),11)
                poolBills.filter{bill->poolParts.none{it.billId==bill.id}}.forEach{bill->Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    val source=finance.plans.first{it.id==bill.planId};val due=s.billOutstanding(bill)
                    YText(source.name+" · "+word("第${bill.number}期","第${bill.number}回")+" · "+bill.date,12)
                    YText(word("已记录待还本金：","記録済み未返済元金：")+(due.principalMinor?.let{Money(currency,it).display()}?:word("未知","不明")),11)
                    YButton(word("选择本金","元金を選ぶ"),{poolPartsJson=vm.repository.json.encodeToString(poolParts+PoolTransferDraft(bill.id))},Modifier.testTag("pool-transfer-select-${bill.id}"))
                }}
                poolParts.forEachIndexed{index,part->Panel(Modifier.testTag("pool-transfer-part-${part.billId}")) {
                    val bill=finance.bills.firstOrNull{it.id==part.billId}
                    bill?.let{b->YText(finance.plans.first{it.id==b.planId}.name,12)}
                    YText(bill?.let{word("原第${it.number}期","元の第${it.number}回")+" · "+it.date}?:word("原账单资料已变化","元の請求情報が変わりました"),14)
                    YField(word("转出本金","移す元金")+" · "+account.currency,part.principal,{changePoolPart(index,part.copy(principal=it))},Modifier.testTag("pool-transfer-principal-$index"),numeric=true)
                    if(treatment==OriginalFeeTreatment.CANCELLED_BY_PLATFORM.name)YField(word("平台取消的本期费用 / 已知无费用填0","返済先が取り消す当期手数料・無料は0")+" · "+account.currency,part.cancelFee,{changePoolPart(index,part.copy(cancelFee=it))},Modifier.testTag("pool-transfer-fee-$index"),numeric=true)
                    YButton(word("移除这期选择","この回の選択を削除"),{poolPartsJson=vm.repository.json.encodeToString(poolParts.filterIndexed{i,_->i!=index})},enabled=!busy)
                }}
                YField(word("未排期本金转出 / 不选留空","未予定元金から移す額・選ばない場合は空欄")+" · "+account.currency,unbilled,{unbilled=it},Modifier.testTag("pool-transfer-unbilled"),numeric=true)
                YText(word("部分本金变更时，取消的原费用须逐期明确填写；不按转出比例猜测。原池与新安排会合并比较，办理本身不改变实际资金或欠款。","一部変更で取り消す元の費用は各回で明示し、移す比率から推定しません。残プールと新予定を合わせて比較し、手続き自体で実資金・残債は変えません。"),11)
            }
            YField(word("另列变更费用 / 未知留空，已知无费用填0","別途変更手数料・不明は空欄、無料は0")+" · "+account.currency,fee,{fee=it},numeric=true)
            if(account.type==AccountType.LOAN.name) {
                YField(word("循环额度占用变动 / 未知留空，增加为正，释放为负","循環枠の利用額変動・不明は空欄、増加は正、解放は負")+" · "+account.currency,loanLimitDelta,{loanLimitDelta=it},Modifier.testTag("arrangement-loan-limit-delta"),numeric=true)
                YText(word("不因切换还款方式就假定释放额度。留空时额度预测待核对，也可按平台已确认的占用/可用额重新设定基准。","返済方式の変更だけで枠が解放されると仮定しません。空欄は枠予測が要確認となり、返済先の確定利用額・可能額で基準を再設定できます。"),11)
            }
            if(destinationPool==null)YButton(word("生成逐期日期","各回の日付を作成"),{vm.execute {
                val n=if(selected==FinanceMode.SINGLE)1 else count.toIntOrNull()?:error("请填写有效期数 / 有効な回数を入力してください")
                require(n in 1..1200)
                val rule=finance.rules.filter{it.accountId==account.id&&it.mode==mode&&it.effectiveFrom<=effective}.maxWithOrNull(compareBy<FinancialRuleEntity>{it.effectiveFrom}.thenBy{it.version})?.let{vm.repository.json.decodeFromString<FinancialRuleVersion>(it.json)}
                val end=LocalDate.parse(firstEnd);val payment=LocalDate.parse(firstDate);val start=LocalDate.parse(effective)
                encoded=vm.repository.json.encodeToString(List(n){i->val previous=end.plusMonths(i-1L);val interest=rule?.revolving?.interest
                    val nextStart=if(i==0)start else if(interest?.frequency==ChargeFrequency.MONTHLY||interest?.includeStart==true&&interest.includeEnd==true)previous.plusDays(1)else if(interest?.includeStart==false&&interest.includeEnd==false)previous.minusDays(1)else previous
                    ArrangementDateDraft(payment.plusMonths(i.toLong()).toString(),nextStart.toString(),end.plusMonths(i.toLong()).toString())})
            }},Modifier.testTag("arrangement-generate"),enabled=!busy)
            if(destinationPool==null)rows.forEachIndexed{i,row->Panel {
                YText(word("第${i+1}期","第${i+1}回"),14)
                val fields=if(revolving&&!manual)listOf(word("计费开始","計算開始") to row.start,word("计费截止","計算終了") to row.end,word("付款日","支払日") to row.date)else listOf(word("付款日","支払日") to row.date)
                fun changeRow(next:ArrangementDateDraft){encoded=vm.repository.json.encodeToString(rows.mapIndexed{k,r->if(k==i)next else r})}
                YDateFields(fields,owner?.takeIf{it/10==i+1}?.rem(10),{owner=it?.let{v->(i+1)*10+v}},{j,v->changeRow(if(!revolving||manual||j==2)row.copy(date=v)else if(j==0)row.copy(start=v)else row.copy(end=v))})
                if(manual) {
                    YField(word("本期金额 / 不含另列变更费","当期金額・別途変更手数料を除く")+" · "+account.currency,row.total,{changeRow(row.copy(total=it))},Modifier.testTag("arrangement-manual-total-$i"),numeric=true)
                    YField(word("本期本金 / 未知留空","当期元金・不明は空欄")+" · "+account.currency,row.principal,{changeRow(row.copy(principal=it))},Modifier.testTag("arrangement-manual-principal-$i"),numeric=true)
                    YField(word("本期费用 / 未知留空","当期手数料・不明は空欄")+" · "+account.currency,row.fee,{changeRow(row.copy(fee=it))},Modifier.testTag("arrangement-manual-fee-$i"),numeric=true)
                }
            }}
            if(laterClearings.isNotEmpty()||clearingMoves.isNotEmpty()) {
                ThemeSection(word("生效后的已记录清偿","適用日後の記録済み返済"),"CLEARING ALLOCATION")
                YText(word("生效日当天已清偿的部分按变更前处理。之后的清偿请明确保留原账单或转到新期次，可拆分本金、利息和费用；每项合计必须与原清偿一致。实际付款和资金不变。","適用日当日の返済は変更前として扱います。その後は元の請求か新しい回へ明示的に充当し、元金・利息・手数料を分割できます。各合計は元の返済と一致させ、実支払・資金は変えません。"),11)
                laterClearings.forEach{source->Panel(Modifier.testTag("arrangement-clearing-${source.id}")) {
                    val payment=s.repayments.first{it.eventId==source.eventId}
                    YText(payment.clearingDate.orEmpty()+" · "+(source.billId?.let{id->finance.bills.first{it.id==id}.date}?:word("原为合同分配","元は契約への充当")),13)
                    RecordFacts(listOf(word("原本金 / 利息 / 费用","元の元金・利息・手数料") to listOf(source.principalMinor,source.interestMinor,source.feeMinor).joinToString(" / "){Money(currency,it!!).display()}))
                    val assigned=clearingMoves.withIndex().filter{it.value.allocationId==source.id}
                    assigned.forEach{(index,move)->
                        YSelect(word("清偿归属","返済の充当先"),move.destination,listOf(null to word("请选择","選択してください"))+listOfNotNull(source.billId?.let{"ORIGINAL" as String? to word("保留原账单","元の請求に保持")})+(if(merging)mergeRows.map{"POOL:DATE:${it.date}" as String? to (word("原池共同付款 · ","元プールの共通支払・")+it.date)}else rows.indices.map{(it+1).toString() as String? to word("新方案第${it+1}期","新プラン第${it+1}回")}),{changeMove(index,move.copy(destination=it))},Modifier.testTag("arrangement-clearing-destination-$index"))
                        YField(word("分配本金","充当元金"),move.principal,{changeMove(index,move.copy(principal=it))},Modifier.testTag("arrangement-clearing-principal-$index"),numeric=true)
                        YField(word("分配利息","充当利息"),move.interest,{changeMove(index,move.copy(interest=it))},Modifier.testTag("arrangement-clearing-interest-$index"),numeric=true)
                        YField(word("分配费用","充当手数料"),move.fee,{changeMove(index,move.copy(fee=it))},Modifier.testTag("arrangement-clearing-fee-$index"),numeric=true)
                        YButton(word("移除此分配行","この充当行を削除"),{movesJson=vm.repository.json.encodeToString(clearingMoves.filterIndexed{i,_->i!=index})},enabled=!busy)
                    }
                    YButton(if(assigned.isEmpty())word("分配这笔清偿","この返済を充当")else word("拆分另一部分","別の部分に分ける"),{movesJson=vm.repository.json.encodeToString(clearingMoves+if(assigned.isEmpty())ClearingMoveDraft(source.id,principal=Money(currency,source.principalMinor!!).decimal.toPlainString(),interest=Money(currency,source.interestMinor!!).decimal.toPlainString(),fee=Money(currency,source.feeMinor!!).decimal.toPlainString())else ClearingMoveDraft(source.id))},Modifier.testTag("arrangement-clearing-add-${source.id}"),enabled=!busy)
                }}
                if(clearingMoves.any{m->laterClearings.none{it.id==m.allocationId}})YButton(word("清偿资料已变化，移除旧分配选择","返済情報が変わりました。古い充当選択を削除"),{movesJson=vm.repository.json.encodeToString(clearingMoves.filter{m->laterClearings.any{it.id==m.allocationId}})},enabled=!busy)
            }
            YButton(word("比较当前与新方案","現在と新プランを比較"),{vm.execute {
                val moves=clearingMoves.map{move->require(move.destination!=null){"请选择每部分的清偿归属 / 各部分の返済先を選択してください"};ArrangementClearingMove(move.allocationId,if(move.destination=="ORIGINAL"||move.destination.startsWith("POOL:"))null else move.destination.toInt(),Money.parse(move.principal,currency).minor,Money.parse(move.interest,currency).minor,Money.parse(move.fee,currency).minor,move.destination.takeIf{it.startsWith("POOL:")}?.removePrefix("POOL:"))}
                val paymentRows=if(merging)mergeRows else rows
                val input=FinancialArrangementInput(old.id,name,selected,FinancialScheduleTerms(effective,paymentRows.map{it.date},if(revolving&&!manual)paymentRows.map{AccrualWindow(it.start,it.end)}else emptyList(),firstPeriodNumber=if(merging)destinationInput!!.terms.firstPeriodNumber else if(selected==FinanceMode.SINGLE||manual)1 else firstPeriod.toIntOrNull()?:0),if(merging)destinationInput?.paymentAccountId else bank,treatment?.let{OriginalFeeTreatment.valueOf(it)},fee.takeIf{it.isNotBlank()}?.let{Money.parse(it,currency).minor},clearingMoves=moves,
                    mergePoolId=destinationPool?.id?.takeIf{merging},mergeFeeDate=mergeFeeDate.takeIf{merging},mergeFeeIncludedInRetained=merging&&mergeIncluded,loanLimitDeltaMinor=loanLimitDelta.takeIf{it.isNotBlank()}?.let{Money.parse(it,currency).minor},
                    manualPeriods=if(manual&&!merging)rows.map{r->KnownBillInput(r.date,Money.parse(r.total,currency).minor,r.principal.takeIf{it.isNotBlank()}?.let{Money.parse(it,currency).minor},r.fee.takeIf{it.isNotBlank()}?.let{Money.parse(it,currency).minor},bank)}else null,manualComplete=manualComplete,
                    poolSelection=if(old.origin=="POOL")PoolArrangementSelection(poolParts.map{part->PoolBillTransfer(part.billId,Money.parse(part.principal,currency).minor,if(treatment==OriginalFeeTreatment.CANCELLED_BY_PLATFORM.name)part.cancelFee.takeIf{it.isNotBlank()}?.let{Money.parse(it,currency).minor}else null)},unbilled.takeIf{it.isNotBlank()}?.let{Money.parse(it,currency).minor}?:0)else null)
                vm.financialReviewRefreshed.value=false;vm.financialArrangementReview.value=vm.repository.previewFinancialArrangement(s.book.id,input.copy(referenceRates=cashReferenceRates(s,finance,vm.referenceRates)))
            }
            },Modifier.testTag("arrangement-preview"),primary=true,enabled=!busy&&(if(destinationPool!=null)merging&&mergeRows.isNotEmpty()else rows.isNotEmpty()))
        }else if(!applying) {
            if(refreshed)FinanceRefreshed()
            FinancialArrangementComparisonContent(review,currency,s,vm)
            YButton(word("已在平台办理，记录结果","返済先で手続き済み・結果を記録"),{applying=true;platform=false},Modifier.testTag("arrangement-record-applied"),primary=true,enabled=!busy&&review.calculation is Calculation.Ready)
            YButton(word("返回修改试算","試算の修正に戻る"),{vm.financialArrangementReview.value=null},Modifier.testTag("arrangement-back"),enabled=!busy)
        }else {
            YText(review.input.name,18)
            RecordFacts(listOf(word("转入新安排的本金","新しい予定へ移す元金") to Money(currency,review.target!!.principalMinor!!).display(),word("生效日","適用日") to review.input.terms.agreementDate,word("变更费用","変更手数料") to Money(currency,review.input.changeFeeMinor!!).display()))
            YText(word("记录本地已办理事实，只更新待付安排，账户实际资金、欠款本金和消费不因此变化。","手続き済みの事実を記録し、支払予定のみ更新します。口座の実資金・借入元金・支出は変わりません。"),11)
            YText(review.zoneId,11)
            val local=Instant.parse(appliedInstant).atZone(ZoneId.of(review.zoneId)).toLocalDateTime().toString()
            YDateFields(listOf(word("实际办理时间","実際の手続き時刻") to local),owner,{owner=it},{_,v->appliedInstant=LocalDateTime.parse(v).atZone(ZoneId.of(review.zoneId)).toInstant().toString()})
            YToggle(word("已在平台实际办理并确认以上条件","返済先で手続き済みで上記の条件を確認した"),platform,{platform=it},Modifier.testTag("arrangement-platform-confirmed"))
            if(review.deadlines.any{Instant.parse(appliedInstant)>it.instant()})YToggle(word("平台已明确接受此次期限外变更","返済先が期限後の変更を明示的に受理した"),exception,{exception=it},Modifier.testTag("arrangement-exception"))
            YButton(word("确认记录已办理","手続き済みとして確定"),{val stamp=appliedInstant;val accepted=platform;val late=exception;var saved=false
                vm.execute(onSuccess={if(saved){vm.financialArrangementReview.value=null;onClose()}else{applying=false;platform=false}}) {
                    saved=commitFinancialDraft(draftSession){vm.repository.commitFinancialArrangement(review,stamp,accepted,late)}!=null
                    if(!saved){vm.financialArrangementReview.value=vm.repository.previewFinancialArrangement(s.book.id,review.input);vm.financialReviewRefreshed.value=true}
                }
            },Modifier.testTag("arrangement-confirm"),primary=true,enabled=!busy&&platform)
            YButton(word("返回比较","比較に戻る"),{applying=false},enabled=!busy)
        }
    }
}

@Composable private fun FinancialArrangementComparisonContent(r:FinancialArrangementReview,currency:Currency,s:BookSnapshot,vm:AppViewModel) {
    YText(r.input.name,18)
    if(r.input.manualPeriods!=null)ThemeAuditPanel(word("平台给出的新方案","返済先の新プラン"),Modifier.testTag("arrangement-manual-review")) {
        YText(if(r.input.manualComplete)word("已核对完整逐期本金，按手填金额比较。","全期間の元金を確認済みです。入力した金額で比較します。")else word("只比较已填期次，未知拆分保持未知。","入力済みの回のみ比較し、不明な内訳は保持します。"),12)
        YText(word("另列变更费加入首期。实际付款另行记录，利率变化不覆盖手填金额。","変更手数料は初回へ加算。実支払は別に記録し、利率変更でも手入力額は保持します。"),11)
    }
    if(s.accounts.first{it.id==r.original.accountId}.type==AccountType.LOAN.name)YText(word("本次循环额度占用变动：","今回の循環枠利用額の変動：")+(r.input.loanLimitDeltaMinor?.let{Money(currency,it).display()}?:word("未知，不推定释放或恢复","不明・解放や回復を推定しません")),12)
    r.input.mergePoolId?.let{id->ThemeAuditPanel(word("并入原有共同付款","元の共通支払に統合"),Modifier.testTag("pool-merge-review")) {
        YText(r.before.finance.plans.first{it.id==id}.name,16)
        YText(word("新本金和办理费直接进入原池的共同月付款；不生成另一份独立循环账单，也不改变实际余额。","元金・変更手数料は元プールの共通月払いへ加えます。別の循環請求は作らず、実残高も変えません。"),11)
        r.input.mergeFeeDate?.let{YText(word("办理费付款日：","変更手数料の支払日：")+it,12)}
        if(r.input.mergeFeeIncludedInRetained)YText(word("已确认费用包含本次办理费；无法拆分原费用基准，全程增加费用保持未知。","確定費用に今回分を含みます。元の費用基準を分離できないため、全期間の増加額は不明です。"),11)
    }}
    r.input.poolSelection?.let{selection->ThemeAuditPanel(word("本次只转出所选部分","選択した部分のみ移します"),Modifier.testTag("pool-transfer-review")) {
        YText(word("未选择部分留在原余额池；以下新方案包含剩余池安排与新转出计划，实际本金不重复计算。","未選択分は元のプールに残ります。以下の新プランは残プールと移した予定を含み、実元金を重複計上しません。"),11)
        selection.bills.forEach{part->val bill=r.before.finance.bills.first{it.id==part.billId};YText(r.before.finance.plans.first{it.id==bill.planId}.name+" · "+word("第${bill.number}期","第${bill.number}回")+" · "+bill.date+" · "+Money(currency,part.principalMinor).display(),13)
            if(r.input.originalFeeTreatment==OriginalFeeTreatment.CANCELLED_BY_PLATFORM)YText(word("平台取消原费用：","元の費用取消：")+(part.cancelledFeeMinor?.let{Money(currency,it).display()}?:word("未知","不明")),12)
        }
        if(selection.unbilledPrincipalMinor>0)YText(word("未排期本金：","未予定元金：")+Money(currency,selection.unbilledPrincipalMinor).display(),13)
    }}
    r.clearingChanges.forEach{change->Panel(Modifier.testTag("arrangement-clearing-review-${change.original.id}")) {
        YText(word("已记录清偿的归属调整","記録済み返済の充当変更"),14)
        val event=s.events.first{it.id==change.original.eventId}
        YText(event.date+" · "+word("原实际付款不变：","元の実支払を保持：")+s.accountMoney(event).display(),12)
        change.replacements.forEach{row->
            val newBill=(r.bills+r.mergedPool?.bills.orEmpty()).firstOrNull{it.id==row.billId}
            val bill=newBill?:r.before.finance.bills.firstOrNull{it.id==row.billId}
            YText((if(r.input.mergePoolId!=null&&bill?.planId==r.input.mergePoolId)word("原池共同付款","元プールの共通支払")else if(newBill!=null)word("新方案","新プラン")else word("原账单","元の請求"))+" · "+(bill?.let{word("第${it.number}期","第${it.number}回")+" · "+it.date}?:word("原合同","元の契約")),12)
            RecordFacts(listOf(word("本金","元金") to Money(currency,row.principalMinor!!).display(),word("利息","利息") to Money(currency,row.interestMinor!!).display(),word("费用","手数料") to Money(currency,row.feeMinor!!).display()))
        }
    }}
    YText(word("这只是试算，不修改账单或办理业务。逾期待付和保留费用仍显示在新方案中。","試算のみで請求・手続きは変更しません。延滞の未払分・保持する手数料も新プランに表示します。"),11)
    if(r.deadlines.isEmpty())YText(word("具体变更期限尚未配置，请以平台确认为准。","対象の変更締切は未設定です。返済先で確認してください。"),12,color=LocalYohaku.current.warm)
    r.deadlines.forEach{d->YText(d.date+" "+d.time+" · "+d.zoneId+" · "+if(vm.repository.clock.instant()>d.instant())word("已过期限","期限経過")else word("期限内","期限内"),12,color=LocalYohaku.current.warm)}
    when(val result=r.calculation) {
        is Calculation.Incomplete->FinancialReadiness(RuleReadiness.Incomplete(result.fields),word("试算需要补充或核对","試算に必要な情報・確認"))
        is Calculation.Unsupported->FinancialCalculationResult(result,currency)
        is Calculation.CannotSettle->FinancialCalculationResult(result,currency)
        is Calculation.Ready->{
            val c=r.comparison!!
            val unknown=word("未知 / 范围尚未结清","不明・範囲内で未完済")
            fun amount(n:Long?)=n?.let{Money(currency,it).display()}?:unknown
            RecordFacts(listOf(word("当前扣款减少 / 负值为增加","当面の減額・負値は増加") to amount(c.currentReductionMinor),word("全程增加费用","全期間の追加手数料") to amount(c.totalExtraFeeMinor),word("新方案预测范围后本金","新プランの予測範囲後元金") to amount(c.proposedRemainingPrincipalMinor)))
            if(c.originalUnscheduledPrincipalMinor>0)YText(word("原方案尚未排入已知日期的本金：","元の予定で日付未確定の元金：")+amount(c.originalUnscheduledPrincipalMinor),12,color=LocalYohaku.current.warm)
            ThemeSection(word("扣款账户缺口比较","引落口座の不足比較"),"FUNDS / THROUGH "+c.fundingThrough.orEmpty())
            YText(word("比较至${c.fundingThrough}的最大缺口；基础情景保留，未到账收入另列。同日未配置顺序时先扣后入。","${c.fundingThrough}までの最大不足額。基本シナリオを保持し、未入金の収入は別に表示。同日の順序が未設定なら出金を先に計算します。"),11)
            c.funding.forEach{fund->Panel(Modifier.testTag("arrangement-funding-${fund.accountId}")) {
                val account=s.accounts.first{it.id==fund.accountId};val fc=Currency.valueOf(fund.currency)
                fun display(n:Long?)=n?.let{(if(fund.referenceEstimated)"≈ "else"")+Money(fc,it).display()}?:unknown
                YText(account.name+" · "+fund.currency,14)
                RecordFacts(listOf(word("原 / 新 · 不含预计收入","元・新 / 収入見込みなし") to (display(fund.beforeGapMinor)+" → "+display(fund.afterGapMinor)),word("原 / 新 · 含预计收入情景","元・新 / 収入見込みあり") to (display(fund.beforeIncomeGapMinor)+" → "+display(fund.afterIncomeGapMinor)),word("首次缺口 · 原 / 新","最初の不足日・元 / 新") to ((fund.beforeRiskDate?:"—")+" / "+(fund.afterRiskDate?:"—"))))
            }}
            c.fundingRates.forEach{ReferenceRateFacts(it)}
            if(c.fundingRates.isNotEmpty())YText(word("外币部分按参考值估算，换汇与付款费用仍待确认，不保证实际足额。","外貨分は参考値の見積もりです。両替・支払手数料は未確定で、実際の充足を保証しません。"),11,color=LocalYohaku.current.warm)
            if(c.fundingUnassigned||c.proposedRemainingPrincipalMinor!=0L||c.originalUnscheduledPrincipalMinor>0)YText(word("尚有未指定资金账户或超出预测范围的本金，不能据此认定整体资金问题已解决。","資金口座未指定・予測範囲外の元金があり、資金全体の問題が解決したとは判定できません。"),12,color=LocalYohaku.current.warm)
            val config=LocalConfiguration.current
            val window=LocalWindowInfo.current
            val width=with(LocalDensity.current){window.containerSize.width.toDp()}
            if(width>=720.dp&&config.fontScale<=1.1f)Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)){ArrangementRows(word("维持原方式","元の方式を維持"),c.original,currency,c.originalUnscheduledPrincipalMinor.takeIf{c.originalComplete})}
                Column(Modifier.weight(1f)){ArrangementRows(word("新方案","新プラン"),c.proposed,currency,c.proposedRemainingPrincipalMinor.takeIf{c.proposedComplete})}
            }else {ArrangementRows(word("维持原方式","元の方式を維持"),c.original,currency,c.originalUnscheduledPrincipalMinor.takeIf{c.originalComplete});ArrangementRows(word("新方案","新プラン"),c.proposed,currency,c.proposedRemainingPrincipalMinor.takeIf{c.proposedComplete})}
        }
    }
}

