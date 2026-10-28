package com.yohaku.expensetracker.ui.features

import androidx.compose.runtime.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
import java.time.format.DateTimeFormatter

@Serializable private data class ReconciliationBillProof(val revision:Int,val principal:Long?,val fee:Long?,val total:Long,val paidPrincipal:Long,val paidFee:Long)
@Serializable private data class ReconciliationBillDraft(val id:String,val principal:String,val fee:String,val keep:Boolean,val billRevision:Int?=null,val basis:ReconciliationBillProof?=null)
@Serializable private data class ReconciliationRefundDraft(val id:String,val target:String="",val bills:Map<String,String> = emptyMap())
@Serializable private data class ReconciliationMoveDraft(val id:String,val billId:String?,val principal:String,val interest:String,val fee:String)

@Composable internal fun FinancialReconciliationEditor(s:BookSnapshot,a:AccountEntity,f:FinancialGraph,target:FinancialPlanEntity,vm:AppViewModel,onClose:()->Unit,onBill:((String)->Unit)?=null,onParent:((String)->Unit)?=null) {
    FinancialDraftHost(s,FinancialDraftContext("ARRANGEMENT_REVIEW",a.id,targetId=target.id),vm,onClose) {
        FinancialReconciliationEditorContent(s,a,f,target,vm,onClose,onBill,onParent)
    }
}

@Composable private fun FinancialReconciliationEditorContent(s:BookSnapshot,a:AccountEntity,f:FinancialGraph,target:FinancialPlanEntity,vm:AppViewModel,onClose:()->Unit,onBill:((String)->Unit)?,onParent:((String)->Unit)?) {
    val session=requireNotNull(LocalFinancialDraft.current);val c=Currency.valueOf(a.currency)
    val applied=f.currentArrangements.first{it.targetPlanId==target.id};val source=f.plans.first{it.id==applied.originalPlanId}
    val physical=vm.repository.reconciliationSource(s,f,applied)
    fun value(n:Long?)=n?.let{Money(c,it).decimal.toPlainString()}.orEmpty()
    val sourcePool=source.takeIf{it.origin=="POOL"}
    val sourceBills=f.reconciliationSourceBills(source)
    val sourceBillIds=sourceBills.map{it.id}.toSet()
    val destinationPool=f.reconciliationPool(target)
    val targetBills=f.reconciliationTargetBills(target)
    val allBills=sourceBills+targetBills
    val ids=allBills.map{it.id}.toSet()
    val contract=vm.repository.json.decodeFromString<FinancialArrangementBaseline>(applied.baselineJson).contractId
    val clearings=s.appliedRepaymentAllocations().filter{it.billId in ids||it.billId==null&&it.contractId!=null&&it.contractId==contract}
    val references=s.repaymentAllocations.filter{row->s.visibleEvents.any{it.id==row.eventId}}.mapNotNull{it.billId}.toSet()
    fun billProof(b:FinancialBillEntity)=s.billOutstanding(b).let{ReconciliationBillProof(b.revision,it.principalMinor,it.feeMinor,it.totalMinor,it.paidPrincipalMinor,it.paidFeeMinor)}
    fun changed(row:ReconciliationBillDraft,b:FinancialBillEntity)=if(row.basis!=null)row.basis!=billProof(b)else row.billRevision!=null&&row.billRevision!=b.revision
    fun mandatoryBill(b:FinancialBillEntity,zero:Boolean=false):Boolean {
        val known=b.confirmed||b.id in references||b.date<LocalDate.now(vm.repository.clock).toString()
        val adjusted=s.billAdjustments.any{it.billId==b.id}
        return if(b.id in sourceBillIds)sourcePool==null||known||adjusted else known||s.billAdjustments.any{it.billId==b.id&&(destinationPool!=null||it.arrangementId!=applied.id)}||destinationPool==null&&zero
    }
    val refunds=vm.repository.reconciliationRefundChoices(s,f,target)
    var effective by financialField("effective"){applied.effectiveDate}
    var principal by financialField("principal"){value(applied.principalMinor)}
    var opening by financialField("opening"){value(sourcePool?.let{poolInput(it)!!.openingMinor}?:source.principalMinor)}
    var poolDate by financialField("poolDate"){sourcePool?.let{poolInput(it)!!.sourceDate.name}.orEmpty()}
    var poolSharesJson by financialField("poolShares"){vm.repository.json.encodeToString(sourcePool?.let{poolInput(it)!!.unallocatedShares}.orEmpty().mapValues{value(it.value)})}
    val poolShares=vm.repository.json.decodeFromString<Map<String,String>>(poolSharesJson)
    val unallocated=if(sourcePool==null)emptyList()else poolUnallocatedPayments(s,a.id)
    var limitDelta by financialField("limitDelta"){vm.repository.json.decodeFromString<FinancialArrangementInput>(applied.inputJson).loanLimitDeltaMinor?.let{value(it)}.orEmpty()}
    var note by financialField("note"){""}
    var complete by financialField("manualComplete"){target.complete}
    var billJson by financialField("bills"){vm.repository.json.encodeToString(allBills.map{b->val due=s.billOutstanding(b);ReconciliationBillDraft(b.id,value(due.principalMinor),value(due.feeMinor),mandatoryBill(b),b.revision,billProof(b))})}
    var refundJson by financialField("refunds"){vm.repository.json.encodeToString(refunds.map{fact->
        val old=target.reconciliation()?.refunds?.firstOrNull{it.fact==fact}
        ReconciliationRefundDraft(fact.eventId,old?.let{value(it.targetMinor)}.orEmpty(),old?.bills?.associate{it.billId to value(it.principalMinor)}.orEmpty())
    })}
    var moveJson by financialField("moves"){vm.repository.json.encodeToString(clearings.map{ReconciliationMoveDraft(it.id,it.billId,value(it.principalMinor),value(it.interestMinor),value(it.feeMinor))})}
    val savedBills=vm.repository.json.decodeFromString<List<ReconciliationBillDraft>>(billJson)
    val changedBills=allBills.filter{b->savedBills.any{it.id==b.id&&changed(it,b)}}
    val bills=allBills.map{b->val saved=savedBills.firstOrNull{it.id==b.id}
        if(saved!=null&&!changed(saved,b))saved else s.billOutstanding(b).let{ReconciliationBillDraft(b.id,value(it.principalMinor),value(it.feeMinor),saved?.keep?:mandatoryBill(b),b.revision,billProof(b))}
    }
    val savedRefunds=vm.repository.json.decodeFromString<List<ReconciliationRefundDraft>>(refundJson)
    val shares=refunds.map{savedRefunds.firstOrNull{r->r.id==it.eventId}?:ReconciliationRefundDraft(it.eventId)}
    val moves=vm.repository.json.decodeFromString<List<ReconciliationMoveDraft>>(moveJson)
    fun bill(row:ReconciliationBillDraft){billJson=vm.repository.json.encodeToString(bills.map{if(it.id==row.id)row else it})}
    fun share(row:ReconciliationRefundDraft){refundJson=vm.repository.json.encodeToString(shares.map{if(it.id==row.id)row else it})}
    fun move(index:Int,row:ReconciliationMoveDraft){moveJson=vm.repository.json.encodeToString(moves.mapIndexed{i,m->if(i==index)row else m})}
    var review by remember{mutableStateOf<ArrangementReconciliationReview?>(null)}
    var confirmed by remember{mutableStateOf(false)}
    var refreshed by remember{mutableStateOf(false)}
    var calendar by remember{mutableStateOf<Int?>(null)}
    val busy by vm.busy.collectAsStateWithLifecycle();val formScroll=rememberScrollState();val previewScroll=rememberScrollState()
    LaunchedEffect(review){if(review!=null)previewScroll.scrollTo(0)}
    YSheet(if(review==null)word("重核已办理安排","手続き済み予定の再確認")else word("当前义务核对","現在の債務を確認"),{if(review!=null)review=null else onClose()},scrollState=if(review==null)formScroll else previewScroll) {
        val r=review
        if(r==null) {
            FinancialDraftNotice()
            val parent=f.currentArrangements.firstOrNull{it.targetPlanId==source.id}
            if(parent!=null&&onParent!=null)ThemeAuditPanel(word("上一步已办理安排","前の手続き済み予定")) {
                YText(source.name,14)
                YText(word("先核对上一步，再确认本次可转本金与退款份额。返回后保留本页输入，不沿用旧预览或确认。","前の予定を確認してから、今回移せる元金・返金の持分を確認します。戻ると入力を保持し、古いプレビューや確認は引き継ぎません。"),11)
                YButton(word("核对上一步安排","前の予定を確認"),{onParent(parent.targetPlanId)},Modifier.testTag("reconciliation-parent-${parent.targetPlanId}"))
            }
            if(changedBills.isNotEmpty())YText(word("原账单已更新，已采用最新待付金额与拆分；其他输入仍保留。","元の請求が更新されたため、最新の未払額・内訳を採用しました。他の入力は保持しています。"),11,Modifier.testTag("reconciliation-bills-refreshed"))
            if(sourcePool!=null)ThemeAuditPanel(word("原池与转出安排","元プールと移行先")) {
                YText(source.name,14)
                f.poolArrangements(source.id).filter{change->!f.isPoolReturn(change,source.id)&&vm.repository.arrangementBaselineIssue(s,f,f.plans.first{it.id==change.targetPlanId})!=null}.forEach{change->
                    if(onParent!=null)YButton(word("先核对并入来源：","先に統合元を確認：")+f.plans.first{it.id==change.targetPlanId}.name,{onParent(change.targetPlanId)},Modifier.testTag("reconciliation-pool-source-${change.targetPlanId}"))
                }
                YText(word("核对生效日当时的池来源和本次转出金额。后续正常新增仍归原池；退款须明确分给本次新安排多少，其余保留原池。原池共同付款与新安排一起重算，仍有其他待核对安排时会保留提示。","適用日時点のプール元データと今回の移行額を確認します。その後の通常利用は元プールに残ります。返金のうち今回の移行先から減らす額を明示し、残りは元プールに充当します。共同支払と移行先を再計算し、他に要確認の予定があれば通知を保持します。"),11)
            }
            if(destinationPool!=null)ThemeAuditPanel(word("共同付款池","共同支払プール")) {
                YText(destinationPool.name,14)
                YText(word("这里只核对本次并入的来源本金。以下池账单属于全部共同义务，不是这一笔来源的专属余额；原池与本次重核一并保存。","ここで確認する移行元金は今回統合した元データ分です。以下のプール請求は全体の共同債務であり、この元データ専用の残高ではありません。再確認と元のプールを一緒に保存します。"),11)
            }
            ThemeAuditPanel(target.name) {
                YText(word("原办理记录保留。核对当前来源、退款与各期剩余义务；不重复付款，也不增加消费或欠款。","元の手続き記録を保持します。現在の元データ・返金・各回の残額を確認し、支払・支出・借入残高を重複作成しません。"),12)
                RecordFacts(listOf((if(sourcePool==null)word("当前来源本金","元データ元金")else word("生效日原池本金 / 转出前","移行前元金")) to (physical.grossMinor?.let{Money(c,it).display()}?:word("待核对清偿归属","返済の帰属を要確認")),word("原办理转入本金","当初の元金") to Money(c,f.arrangements.first{it.id==applied.id}.principalMinor).display()))
                if(!physical.exists)YText(word("原来源已删除。可先恢复原记录；若平台也已取消，可将当前转入本金及各期待付核对为0。实际清偿不会被删除。","元の記録は削除済みです。先に復元するか、返済先でも取消済みなら移す元金・各回残額を0として確認できます。実際の返済は削除しません。"),11,color=LocalYohaku.current.warm)
            }
            YDateFields(listOf(word("当前确认的生效日","現在確認した適用日") to effective),calendar,{calendar=it},{_,v->effective=v})
            YField(word("生效日转入的本金 / 非今日剩余","適用日に移す元金・今日の残額ではありません")+" · "+a.currency,principal,{principal=it},Modifier.testTag("reconciliation-principal"),numeric=true)
            if(source.sourceEventId==null&&f.arrangements.none{it.targetPlanId==source.id})YField(word("来源所属的期初本金","元データに属する期首元金")+" · "+a.currency,opening,{opening=it},Modifier.testTag("reconciliation-opening"),numeric=true)
            if(sourcePool!=null)YSelect(word("池本金入账日期依据","プール元金の日付基準"),poolDate,listOf(PoolSourceDate.TRANSACTION.name to word("交易日期","取引日"),PoolSourceDate.ACCOUNT_POSTING.name to word("账户入账日期","口座計上日")),{poolDate=it})
            if(sourcePool!=null) {
                unallocated.filter{it.minor>0||it.eventId in poolShares}.forEach{p->
                    YField(word("未分配本金中属于原池的金额 / 零请填0","未充当のうち元プール分・ゼロも0")+" · "+p.date,poolShares[p.eventId].orEmpty(),{v->poolSharesJson=vm.repository.json.encodeToString(poolShares+(p.eventId to v))},Modifier.testTag("reconciliation-pool-share-${p.eventId}"),numeric=true)
                }
                (poolShares.keys-unallocated.map{it.eventId}.toSet()).forEach{id->
                    YText(word("原付款已删除或变化，请核对后移除旧归属。","元の支払は削除・変更されています。確認して旧割当てを除外してください。"),11)
                    YButton(word("移除失效归属","無効な割当てを除外"),{poolSharesJson=vm.repository.json.encodeToString(poolShares-id)})
                }
            }
            if(a.type==AccountType.LOAN.name)YField(word("本次核对的循环占用变动 / 未知留空","今回確認した循環枠利用の変動・不明は空欄")+" · "+a.currency,limitDelta,{limitDelta=it},Modifier.testTag("reconciliation-limit"),numeric=true)
            YText(word("以下填写已记录清偿之后仍待付的金额；本金与费用分别核对，恢复已取消部分也会留版本。","以下は記録済み返済後の未払額です。元金・手数料を別々に確認し、取消分を戻す場合も履歴を保持します。"),11)
            bills.forEach{row->val b=allBills.first{it.id==row.id};val required=mandatoryBill(b,principal.toBigDecimalOrNull()?.signum()==0)
                Panel(Modifier.testTag("reconciliation-bill-${b.id}")) {
                    YText((if(b.id in sourceBillIds)word("原安排","元の予定")else if(destinationPool!=null)word("共同付款池","共同支払プール")else word("已办理安排","手続き済み予定"))+" · "+b.date+" · "+word("第${b.number}期","第${b.number}回"),14)
                    if(!required)YToggle(word("已向平台核对，保留本期金额","返済先で確認し当期金額を保持"),row.keep,{bill(row.copy(keep=it))})
                    if(required||row.keep) {
                        YField(word("仍待付本金 / 不明可留空","未払元金・不明は空欄")+" · "+a.currency,row.principal,{bill(row.copy(principal=it))},Modifier.testTag("reconciliation-bill-principal-${b.id}"),numeric=true)
                        YField(word("仍待付费用 / 不明可留空","未払手数料・不明は空欄")+" · "+a.currency,row.fee,{bill(row.copy(fee=it))},Modifier.testTag("reconciliation-bill-fee-${b.id}"),numeric=true)
                    }else YText(word("未确认的未来金额按新基准重算，缺项则保留原预测并提示。","未確定の将来額は新基準で再計算し、情報不足なら元の予測を保持して通知します。"),11)
                    val current=s.billOutstanding(b)
                    YText(word("当前待付合计：","現在の未払合計：")+Money(c,current.totalMinor).display(),12)
                    if(current.principalMinor==null||current.feeMinor==null)YText(word("未知拆分留空保留；调整合计或补充已知拆分，请先核对原账单。","不明な内訳は空欄のまま保持します。合計の修正・判明した内訳の入力は元の請求で確認してください。"),11)
                    if(onBill!=null)YButton(word("查看 / 核对原账单","元の請求を確認"),{onBill(b.id)},Modifier.testTag("reconciliation-open-bill-${b.id}"))
                }
            }
            if(target.termsJson==null)YToggle(word("本次已列完整手动方案","今回の手動予定は全期間を入力済み"),complete,{complete=it})
            refunds.forEach{fact->val row=shares.first{it.id==fact.eventId};Panel(Modifier.testTag("reconciliation-refund-${fact.eventId}")) {
                YText(word("退款归属","返金の帰属")+" · "+fact.date+" · "+Money(c,fact.principalMinor).display(),14)
                if(fact.date>effective)YField(word("其中减少新安排的本金","うち新予定から減らす元金")+" · "+a.currency,row.target,{share(row.copy(target=it))},Modifier.testTag("reconciliation-refund-target-${fact.eventId}"),numeric=true)
                else YText(word("生效前退款在原来源扣除，不再重复减少新安排。","適用前の返金は元データから控除し、新予定から重複控除しません。"),11)
                sourceBills.forEach{b->YField(word("减少该原账单 / 不分配留空","この元請求から減額・割当てなしは空欄")+" · "+b.date+" / "+b.number,row.bills[b.id].orEmpty(),{share(row.copy(bills=row.bills+(b.id to it)))},Modifier.testTag("reconciliation-refund-bill-${fact.eventId}-${b.id}"),numeric=true)}
                YText(word("其余归入原来源未排期本金。各原账单待付额也需反映这笔减少；不按比例猜测。","残りは元データの未予定元金に充当します。元の各請求残額にも減額を反映し、比率から推定しません。"),11)
            }}
            if(clearings.isNotEmpty())ThemeSection(word("实际清偿归属","実返済の充当先"),"CLEARING ALLOCATION")
            moves.forEachIndexed{index,row->val original=clearings.firstOrNull{it.id==row.id}
                if(original!=null)Panel {
                    YText(s.repayments.first{it.eventId==original.eventId}.clearingDate.orEmpty(),12)
                    YSelect(word("这部分清偿归属","この返済部分の充当先"),row.billId,listOf(null to word("原合同 / 未关联账单","元の契約・請求未関連"))+allBills.map{it.id as String? to ((if(it.planId==source.id)word("原安排","元の予定")else word("新安排","新予定"))+" · "+it.date+" / "+it.number)},{move(index,row.copy(billId=it))},Modifier.testTag("reconciliation-clearing-destination-$index"))
                    YField(word("分配本金","充当元金"),row.principal,{move(index,row.copy(principal=it))},Modifier.testTag("reconciliation-clearing-principal-$index"),numeric=true)
                    YField(word("分配利息","充当利息"),row.interest,{move(index,row.copy(interest=it))},numeric=true)
                    YField(word("分配费用","充当手数料"),row.fee,{move(index,row.copy(fee=it))},numeric=true)
                    YActionRow {
                        YButton(word("拆分另一部分","別の部分に分ける"),{moveJson=vm.repository.json.encodeToString(moves+ReconciliationMoveDraft(row.id,row.billId,"","",""))})
                        if(moves.count{it.id==row.id}>1)YButton(word("移除此部分","この部分を削除"),{moveJson=vm.repository.json.encodeToString(moves.filterIndexed{i,_->i!=index})})
                    }
                }
            }
            if(moves.any{m->clearings.none{it.id==m.id}})YButton(word("清偿已变化，重新读取分配行","返済情報が変わりました。充当行を再読込"),{moveJson=vm.repository.json.encodeToString(clearings.map{ReconciliationMoveDraft(it.id,it.billId,value(it.principalMinor),value(it.interestMinor),value(it.feeMinor))})})
            YField(word("核对依据 / 可留空","確認の根拠・空欄可"),note,{note=it})
            if(refreshed)FinanceRefreshed()
            YButton(word("预览重核结果","再確認の結果をプレビュー"),{vm.execute {
                val rows=bills.filter{row->val b=allBills.first{it.id==row.id};row.keep||mandatoryBill(b,principal.toBigDecimalOrNull()?.signum()==0)}.map{ReconciledBillObligation(it.id,it.principal.takeIf{v->v.isNotBlank()}?.let{v->Money.parse(v,c).minor},it.fee.takeIf{v->v.isNotBlank()}?.let{v->Money.parse(v,c).minor})}
                val allocations=moves.map{ReconciledClearingMove(it.id,it.billId,Money.parse(it.principal,c).minor,Money.parse(it.interest,c).minor,Money.parse(it.fee,c).minor)}
                val refundParts=refunds.map{fact->val row=shares.first{it.id==fact.eventId};ReconciledRefundShare(fact.eventId,if(fact.date<=effective)0 else Money.parse(row.target,c).minor,row.bills.filterValues{it.isNotBlank()}.map{ReconciledRefundBill(it.key,Money.parse(it.value,c).minor)})}
                review=vm.repository.previewArrangementReconciliation(s.book.id,ArrangementReconciliationInput(target.id,effective,principal.takeIf{it.isNotBlank()}?.let{Money.parse(it,c).minor},rows,refundParts,allocations,if(source.sourceEventId==null&&f.arrangements.none{it.targetPlanId==source.id})Money.parse(opening,c).minor else null,complete,note,limitDelta.takeIf{it.isNotBlank()}?.let{Money.parse(it,c).minor},sourcePool?.let{PoolSourceDate.valueOf(poolDate)},sourcePool?.let{poolShares.filterValues{it.isNotBlank()}.mapValues{Money.parse(it.value,c).minor}}))
                confirmed=false
            }},Modifier.testTag("reconciliation-preview"),primary=true,enabled=!busy)
        }else {
            ReconciliationReviewFacts(r,c)
            if(r.calculation is Calculation.Ready) {
                YToggle(word("已核对平台当前义务与退款归属","返済先の現在の債務・返金の帰属を確認した"),confirmed,{confirmed=it},Modifier.testTag("reconciliation-confirmed"))
                YActionRow {
                    YButton(word("保存本次重核版本","今回の再確認版を保存"),{var ok=false;vm.execute(onSuccess={if(ok)onClose()else{review=null;confirmed=false;refreshed=true}}) {
                        ok=commitFinancialDraft(session){vm.repository.commitArrangementReconciliation(r,confirmed)}!=null
                    }},Modifier.testTag("reconciliation-save"),primary=true,enabled=!busy&&confirmed)
                }
            }
            YButton(word("返回修改","修正に戻る"),{review=null},Modifier.testTag("reconciliation-back"),enabled=!busy)
        }
    }
}

@Composable private fun ReconciliationReviewFacts(r:ArrangementReconciliationReview,c:Currency) {
    ThemeAuditPanel(word("当前义务核对","現在の債務確認"),Modifier.testTag("reconciliation-review")) {
        YText(r.original.name,16)
        when(val result=r.calculation) {
            is Calculation.Incomplete->FinancialReadiness(RuleReadiness.Incomplete(result.fields),word("需要核对","確認が必要"))
            is Calculation.Ready->{val record=r.record!!
                RecordFacts(listOf(word("生效日转入本金","移行元金") to Money(c,record.input.principalMinor!!).display(),word("退款后安排本金","返金後元金") to Money(c,r.plans.first{it.id==r.original.id}.principalMinor!!).display(),word("来源未排期本金","未予定元金") to Money(c,record.unassignedOriginalMinor).display()))
                if(r.before.ledger.accounts.first{it.id==r.original.accountId}.type==AccountType.LOAN.name)YText(word("循环占用变动：","循環枠利用の変動：")+(record.input.loanLimitDeltaMinor?.let{Money(c,it).display()}?:word("未知，不推定释放或恢复","不明・解放や回復を推定しません")),12)
                YText(word("保留原办理记录、原账单及实际付款。这里只记录当前核对结果，调整义务与清偿归属。","元の手続き・請求・実支払を保持し、現在の確認結果として債務・返済の帰属を更新します。"),11)
                record.refunds.forEach{refund->
                    YText(refund.fact.date+" · "+word("退款 / 新安排减少：","返金・新予定の減額：")+Money(c,refund.fact.principalMinor).display()+" / "+Money(c,refund.targetMinor).display(),12)
                    refund.bills.forEach{part->
                        val bill=r.before.finance.bills.first{it.id==part.billId}
                        YText(bill.date+" / "+bill.number+" · "+word("减少本金：","元金減額：")+Money(c,part.principalMinor).display(),12)
                    }
                }
                if(record.unassignedOriginalMinor>0)YText(word("来源仍有未排期本金，账户风险继续提示，不视为已安排。","元データに未予定元金が残り、口座の要確認表示を継続します。"),11,color=LocalYohaku.current.warm)
            }
            is Calculation.Unsupported->FinancialCalculationResult(result,c)
            is Calculation.CannotSettle->FinancialCalculationResult(result,c)
        }
    }
    if(r.calculation is Calculation.Ready) {
        r.input.bills.forEach{bill->Panel {
            val original=r.before.finance.bills.first{it.id==bill.billId}
            YText((if(r.before.finance.reconciliationPool(r.original)!=null&&original.planId!=r.before.finance.arrangements.first{it.targetPlanId==r.original.id}.originalPlanId)word("共同付款池","共同支払プール")else if(original.planId==r.original.id)word("已办理安排","手続き済み予定")else word("原安排","元の予定"))+" · "+original.date+" / "+original.number,12)
            val unknown=word("未知","不明")
            fun amount(n:Long?)=n?.let{Money(c,it).display()}?:unknown
            val current=r.before.ledger.copy(billAdjustments=r.before.ledger.billAdjustments+r.adjustments).billOutstanding(original)
            RecordFacts(listOf(word("本期待付本金 / 费用","当期の未払元金・手数料") to (amount(bill.principalMinor)+" / "+amount(bill.feeMinor)),word("待付合计","未払合計") to Money(c,current.totalMinor).display()))
        }}
        val poolSource=r.record?.baseline?.pool!=null
        val forecasts=if(poolSource)listOf(false to r.prediction,true to r.poolPrediction)else listOf((r.poolPrediction!=null) to (r.poolPrediction?:r.prediction))
        forecasts.forEach{(shared,forecast)->
        if(shared)ThemeSection(word("原池全部共同付款","プール全体の支払"),"SHARED PAYMENTS")
        else if(poolSource)ThemeSection(word("新安排付款","移行先の支払"),"NEW PAYMENTS")
        when(val result=forecast?.calculation) {
            is Calculation.Ready->result.value.forEach{p->YText(p.period.date.toString()+" · "+Money(c,p.period.totalMinor).display()+" · "+word("本金","元金")+Money(c,p.period.principalMinor).display(),12)}
            is Calculation.Incomplete->{YText(word("本次核对可保存，预测仍需补齐以下资料：","今回の確認は保存できます。予測には次の補完が必要です："),12);FinancialReadiness(RuleReadiness.Incomplete(result.fields),word("预测待核对","予測を要確認"))}
            null->YText(word("已知账单保留；若未来日期或算法仍需调整，请在保存后继续核对计划。","既知の請求を保持します。将来の日付・計算法の調整が必要なら、保存後に予定を引き続き確認してください。"),11)
            is Calculation.Unsupported->{YText(word("核对可保存，原预测保留待处理。","確認は保存でき、元の予測は要確認のまま保持します。"),12);FinancialCalculationResult(result,c)}
            is Calculation.CannotSettle->{YText(word("核对可保存，原预测保留待处理。","確認は保存でき、元の予測は要確認のまま保持します。"),12);FinancialCalculationResult(result,c)}
        }
        }
    }
}

@Composable internal fun FinancialReconciliationHistory(r:ArrangementReconciliation,c:Currency,zone:ZoneId) {
    ThemeAuditPanel(word("重核记录","再確認"),Modifier.testTag("reconciliation-history")) {
        YText(Instant.parse(r.recordedAt).atZone(zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),12)
        RecordFacts(listOf(word("来源本金","元の元金") to (r.source.grossMinor?.let{Money(c,it).display()}?:word("未知","不明")),word("核对转入本金","移行元金") to Money(c,r.input.principalMinor!!).display(),word("来源未排期本金","未予定元金") to Money(c,r.unassignedOriginalMinor).display()))
        r.refunds.forEach{YText(it.fact.date+" · "+Money(c,it.fact.principalMinor).display()+" → "+Money(c,it.targetMinor).display(),12)}
        if(r.input.note.isNotBlank())YText(r.input.note,12)
        YText(word("此处保留当时核对内容，后续更正会另存版本。","ここでは当時の確認内容を保持し、後日の訂正は別の版に保存します。"),11)
    }
}
