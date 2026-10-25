package com.yohaku.expensetracker.ui.features

import androidx.compose.foundation.rememberScrollState
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.time.LocalDate

private fun amount(currency:String,value:Long?)=value?.let{Money(Currency.valueOf(currency),it).decimal.toPlainString()}.orEmpty()
private fun money(currency:String,value:String)=value.takeIf{it.isNotBlank()}?.let{Money.parse(it,Currency.valueOf(currency))}
@Serializable private data class BorrowingDraft(
    val name:String="",val mode:String=FinanceMode.FIXED_LOAN.name,val principal:String="",val date:String,
    val funds:String?=null,val received:String="",val withheld:String="",val withheldBase:String="",
    val separatePayer:String?=null,val separate:String="",val separateBase:String="",val note:String=""
)

@Composable internal fun FinancialBorrowingEditor(s:BookSnapshot,account:AccountEntity,vm:AppViewModel,onClose:()->Unit,opening:Boolean=false,contract:DebtContractEntity?=null,eventId:String?=contract?.fundingEventId) {
    FinancialDraftHost(s,FinancialDraftContext("BORROW",account.id,targetId=eventId,contractId=contract?.id,opening=opening),vm,onClose){FinancialBorrowingEditorContent(s,account,vm,onClose,opening,contract,eventId)}
}

@Composable private fun FinancialBorrowingEditorContent(s:BookSnapshot,account:AccountEntity,vm:AppViewModel,onClose:()->Unit,opening:Boolean=false,contract:DebtContractEntity?=null,eventId:String?=contract?.fundingEventId) {
    val original=eventId?.let{id->s.events.firstOrNull{it.id==id}}
    val legacyReceipt=if(contract==null&&original!=null)s.storedPostings.firstOrNull{it.eventId==original.id&&it.deltaMinor>=0&&s.accounts.any{a->a.id==it.accountId&&!AccountType.valueOf(a.type).debt}}else null
    var encoded by financialField("encoded"){vm.repository.json.encodeToString(
        BorrowingDraft(contract?.name?:original?.merchant.orEmpty(),contract?.mode?:FinanceMode.FIXED_LOAN.name,amount(account.currency,contract?.principalMinor?:original?.amountMinor),contract?.date?:original?.date?:if(opening)account.openingDate else LocalDate.now(vm.repository.clock).toString(),
            (contract?.fundingAccountId?:legacyReceipt?.accountId),amount(s.accounts.firstOrNull{it.id==(contract?.fundingAccountId?:legacyReceipt?.accountId)}?.currency?:account.currency,contract?.receivedMinor?:legacyReceipt?.deltaMinor),amount(account.currency,contract?.withheldFeeMinor),amount(s.book.currency,contract?.withheldFeeBaseMinor),contract?.separateFeeAccountId,
            amount(s.accounts.firstOrNull{it.id==contract?.separateFeeAccountId}?.currency?:account.currency,contract?.separateFeeMinor),amount(s.book.currency,contract?.separateFeeBaseMinor),original?.note.orEmpty())
    )}
    val d=vm.repository.json.decodeFromString<BorrowingDraft>(encoded)
    fun update(v:BorrowingDraft){encoded=vm.repository.json.encodeToString(v)}
    val funds=s.accounts.filter{it.active&&!AccountType.valueOf(it.type).debt}
    val currency=s.accounts.firstOrNull{it.id==d.funds}?.currency?:account.currency
    val feeCurrency=s.accounts.firstOrNull{it.id==(d.separatePayer?:d.funds)}?.currency?:currency
    var dateOwner by remember{mutableStateOf<Int?>(null)}
    val busy by vm.busy.collectAsStateWithLifecycle()
    val review by vm.financialLedgerReview.collectAsStateWithLifecycle()
    val formScroll=rememberScrollState();val reviewScroll=rememberScrollState()
    LaunchedEffect(review){if(review!=null)reviewScroll.scrollTo(0)}
    YSheet(if(opening)word("补充期初借款合同","期首の借入契約を補完")else word("实际借入","実際の借入"),{vm.financialLedgerReview.value=null;onClose()},scrollState=if(review!=null)reviewScroll else formScroll) {
        val r=review?.takeIf{it.borrowingInput?.accountId==account.id&&it.before.ledger.book.id==s.book.id}
        if(r!=null){FinancialLedgerReviewContent(r,vm,onClose);return@YSheet}
        FinancialDraftNotice()
        ThemeAuditPanel(account.name){YText(if(opening)word("期初欠款已包含在账户余额中，此处只补充合同，不再次增加本金或到账。","期首債務は口座残高に含まれています。契約のみ補完し、元金・入金を再加算しません。")else word("合同本金、实际到账与费用分别记录。未知费用留空，不把到账差额自动当作费用。","契約元金・実入金・手数料を分けて記録します。不明な手数料は空欄にし、入金差額から推定しません。"),11)}
        YField(word("合同名称","契約名"),d.name,{update(d.copy(name=it))})
        YSelect(word("借款模式","借入方式"),d.mode,listOf(FinanceMode.FIXED_LOAN,FinanceMode.CREDIT_LINE).map{it.name to financeLabel(it.name)},{update(d.copy(mode=it))})
        YField(word("合同本金","契約元金")+" · "+account.currency,d.principal,{update(d.copy(principal=it))},numeric=true)
        YDateFields(listOf(word("借入日期","借入日") to d.date),dateOwner,{dateOwner=it},{_,v->update(d.copy(date=v))})
        if(!opening) {
            YSelect(word("实际到账账户","実際の入金先口座"),d.funds,listOf(null to word("请选择","選択してください"))+funds.map{it.id as String? to (it.name+" · "+it.currency)},{update(d.copy(funds=it,received="",separatePayer=null,separate="",separateBase=""))})
            YField(word("实际到账","実入金額")+" · "+currency,d.received,{update(d.copy(received=it))},numeric=true)
            YField(word("预扣费用 / 不明留空","天引手数料・不明なら空欄")+" · "+account.currency,d.withheld,{update(d.copy(withheld=it))},numeric=true)
            if(account.currency!=s.book.currency)YField(word("预扣费用统计金额","天引手数料の集計額")+" · "+s.book.currency,d.withheldBase,{update(d.copy(withheldBase=it))},numeric=true)
            YSelect(word("另付费用账户","別払い手数料の口座"),d.separatePayer,listOf(null to word("同到账账户","入金先と同じ"))+funds.map{it.id as String? to it.name},{update(d.copy(separatePayer=it,separate="",separateBase=""))})
            YField(word("另付费用 / 未知留空","別払い手数料・不明なら空欄")+" · "+feeCurrency,d.separate,{update(d.copy(separate=it))},numeric=true)
            if(feeCurrency!=s.book.currency)YField(word("另付费用统计金额","別払い手数料の集計額")+" · "+s.book.currency,d.separateBase,{update(d.copy(separateBase=it))},numeric=true)
            YField(word("备注","メモ"),d.note,{update(d.copy(note=it))})
        }
        YButton(word("预览借入记录","借入記録を確認"),{vm.execute {
            val input=FinancialBorrowingInput(account.id,d.name,FinanceMode.valueOf(d.mode),requireNotNull(money(account.currency,d.principal)),d.date,
                if(opening)null else d.funds,if(opening)null else money(currency,d.received),if(opening)null else money(account.currency,d.withheld),if(opening)null else money(s.book.currency,d.withheldBase),
                if(opening)null else d.separatePayer,if(opening)null else money(feeCurrency,d.separate),if(opening)null else money(s.book.currency,d.separateBase),opening,eventId,contract?.id,d.note)
            vm.financialReviewRefreshed.value=false;vm.financialLedgerReview.value=vm.repository.previewBorrowing(s.book.id,input)
        }},Modifier.testTag("finance-borrow-preview"),primary=true,enabled=!busy)
    }
}

@Serializable private data class AllocationDraft(val billId:String?=null,val contractId:String?=null,val principal:String="",val interest:String="",val fee:String="")
@Serializable private data class RepaymentDraft(
    val source:String?=null,val paid:String="",val date:String,val received:String="",val principal:String="",val interest:String="",val fee:String="",val feeBase:String="",
    val confirmed:Boolean=false,val clearingDate:String,val type:String=RepaymentType.NORMAL.name,val sourceFee:String="",val sourceFeeBase:String="",
    val rows:List<AllocationDraft> = emptyList(),val linkedTransfer:String?=null,val existing:String?=null,val note:String=""
)

@Composable internal fun FinancialRepaymentEditor(s:BookSnapshot,account:AccountEntity,finance:FinancialGraph,vm:AppViewModel,onClose:()->Unit,eventId:String?=null,billId:String?=null,contractId:String?=null) {
    FinancialDraftHost(s,FinancialDraftContext("REPAY",account.id,targetId=eventId,billId=billId,contractId=contractId),vm,onClose){FinancialRepaymentEditorContent(s,account,finance,vm,onClose,eventId,billId,contractId)}
}

@Composable private fun FinancialRepaymentEditorContent(s:BookSnapshot,account:AccountEntity,finance:FinancialGraph,vm:AppViewModel,onClose:()->Unit,eventId:String?=null,billId:String?=null,contractId:String?=null) {
    val old=s.repayments.firstOrNull{it.eventId==eventId}
    val event=s.events.firstOrNull{it.id==eventId}
    val initialBill=finance.bills.firstOrNull{it.id==billId}
    val initialDate=LocalDate.now(vm.repository.clock).toString()
    var encoded by financialField("encoded"){vm.repository.json.encodeToString(
        RepaymentDraft(event?.accountId?:initialBill?.paymentAccountId,amount(event?.currency?:account.currency,event?.amountMinor),event?.date?:initialDate,amount(account.currency,old?.receivedMinor),amount(account.currency,old?.principalMinor),amount(account.currency,old?.interestMinor),amount(account.currency,old?.feeMinor),amount(s.book.currency,old?.feeBaseMinor),
            old?.platformConfirmed?:false,old?.clearingDate?:initialDate,old?.paymentType?:RepaymentType.NORMAL.name,amount(event?.currency?:account.currency,old?.sourceFeeMinor),amount(s.book.currency,old?.sourceFeeBaseMinor),
            if(old!=null)s.repaymentAllocations.filter{it.eventId==eventId}.map{AllocationDraft(it.billId,it.contractId,amount(account.currency,it.principalMinor),amount(account.currency,it.interestMinor),amount(account.currency,it.feeMinor))}else if(billId!=null||contractId!=null)listOf(AllocationDraft(billId,contractId))else emptyList(),old?.linkedTransferEventId,eventId,event?.note.orEmpty())
    )}
    val d=vm.repository.json.decodeFromString<RepaymentDraft>(encoded)
    fun update(v:RepaymentDraft){encoded=vm.repository.json.encodeToString(v)}
    fun row(index:Int,value:AllocationDraft){update(d.copy(rows=d.rows.mapIndexed{i,a->if(i==index)value else a}))}
    val funds=s.accounts.filter{(it.active||event?.accountId==it.id)&&!AccountType.valueOf(it.type).debt}
    val source=s.accounts.firstOrNull{it.id==d.source}
    val sourceCurrency=source?.currency?:s.book.currency
    val bills=finance.bills.filter{it.active&&finance.plans.any{p->p.id==it.planId&&p.accountId==account.id}}
    val contracts=finance.contracts.filter{it.accountId==account.id}
    val busy by vm.busy.collectAsStateWithLifecycle()
    val review by vm.financialLedgerReview.collectAsStateWithLifecycle()
    var dateOwner by remember{mutableStateOf<Int?>(null)}
    val formScroll=rememberScrollState();val reviewScroll=rememberScrollState()
    LaunchedEffect(review){if(review!=null)reviewScroll.scrollTo(0)}
    YSheet(word("实际还款与清偿","実際の支払と返済"),{vm.financialLedgerReview.value=null;onClose()},scrollState=if(review!=null)reviewScroll else formScroll) {
        val r=review?.takeIf{it.repaymentInput?.debtAccountId==account.id&&it.before.ledger.book.id==s.book.id}
        if(r!=null){FinancialLedgerReviewContent(r,vm,onClose);return@YSheet}
        FinancialDraftNotice()
        ThemeAuditPanel(account.name){YText(word("实际付款立即减少资金。平台未确认时，债务本金与账单待付金额保持原值。各项未知可留空；确认清偿时请填写完整，已知为零填0。","実際の支払で資金が減ります。返済先の未確定時は債務元金・請求残額を維持します。不明は空欄、返済確定時は全項目を入力し、ゼロは0を明示してください。"),11)}
        if(eventId==null) {
            val candidates=s.visibleEvents.filter{it.kind in listOf(EntryKind.EXPENSE.name,EntryKind.REPAYMENT.name)&&s.repayments.none{p->p.eventId==it.id}&&funds.any{a->a.id==it.accountId}}
            YSelect(word("付款来源","支払の記録"),d.existing,listOf(null to word("记录新的实际付款","新しい支払を記録"))+candidates.map{it.id as String? to (it.date+" · "+it.merchant+" · "+s.accountMoney(it).display())},{id->val e=candidates.firstOrNull{it.id==id};update(d.copy(existing=id,source=e?.accountId,paid=e?.let{amount(s.accountMoney(it).currency.name,s.accountMoney(it).minor)}.orEmpty(),date=e?.date?:initialDate))})
            if(d.existing!=null)YText(word("将这笔已有付款改为还款记录，保留记录编号与原扣款，只把明确的利息及费用计为支出。","既存の支払を返済記録に変更します。記録ID・出金を維持し、明示した利息・手数料のみ支出に計上します。"),11,color=LocalYohaku.current.warm)
        }
        YSelect(word("实际付款账户","実際の支払口座"),d.source,listOf(null to word("请选择","選択してください"))+funds.map{it.id as String? to (it.name+" · "+it.currency)},{update(d.copy(source=it,paid="",sourceFee="",sourceFeeBase="",linkedTransfer=null))})
        YField(word("实际扣款总额","実出金総額")+" · "+sourceCurrency,d.paid,{update(d.copy(paid=it))},numeric=true)
        YDateFields(listOf(word("实际付款日期","実際の支払日") to d.date,word("平台清偿日期","返済先の確定日") to d.clearingDate),dateOwner,{dateOwner=it},{i,v->update(if(i==0)d.copy(date=v)else d.copy(clearingDate=v))})
        YSelect(word("还款类型","返済種別"),d.type,RepaymentType.entries.map{it.name to repaymentTypeLabel(it)},{update(d.copy(type=it))})
        YToggle(word("平台已确认清偿组成","返済先が内訳を確定した"),d.confirmed,{update(d.copy(confirmed=it))},Modifier.testTag("finance-repay-cleared"))
        YField(word("平台实际收到","返済先の実受取額")+" · "+account.currency,d.received,{update(d.copy(received=it))},numeric=true)
        YField(word("清偿本金","返済元金")+" · "+account.currency,d.principal,{update(d.copy(principal=it))},numeric=true)
        YField(word("实付利息","実支払利息")+" · "+account.currency,d.interest,{update(d.copy(interest=it))},numeric=true)
        YField(word("平台费用","返済先手数料")+" · "+account.currency,d.fee,{update(d.copy(fee=it))},numeric=true)
        if(account.currency!=s.book.currency)YField(word("利息及平台费用统计合计","利息・返済先手数料の集計合計")+" · "+s.book.currency,d.feeBase,{update(d.copy(feeBase=it))},numeric=true)
        YField(word("总扣款内含付款端费用","実出金総額に含む出金側手数料")+" · "+sourceCurrency,d.sourceFee,{update(d.copy(sourceFee=it))},numeric=true)
        if(sourceCurrency!=s.book.currency)YField(word("付款端费用统计金额","出金側手数料の集計額")+" · "+s.book.currency,d.sourceFeeBase,{update(d.copy(sourceFeeBase=it))},numeric=true)
        val transfers=s.transfers.filter{it.toAccountId==d.source&&s.visibleEvents.any{e->e.id==it.eventId}}
        YSelect(word("关联先前换汇 / 转账","先行する両替・振替に関連付け"),d.linkedTransfer,listOf(null to word("不关联 / 直接付款","関連なし・直接支払"))+transfers.map{it.eventId as String? to (s.events.first{e->e.id==it.eventId}.date+" · "+it.eventId.take(8))},{update(d.copy(linkedTransfer=it))})
        ThemeSection(word("清偿分配","返済の充当"),"ALLOCATION")
        YText(word("未分配本金仍会减少账户欠款，但不会自动结清任一账单或合同。","未充当の元金も口座の債務を減らしますが、個別の請求・契約は自動精算しません。"),11)
        d.rows.forEachIndexed{i,a->Panel(Modifier.testTag("finance-repay-allocation-$i")) {
            YSelect(word("目标账单","対象の請求"),a.billId,listOf(null to word("不指定账单","請求を指定しない"))+bills.map{it.id as String? to (finance.plans.first{p->p.id==it.planId}.name+" · ${it.number} · ${it.date}")},{row(i,a.copy(billId=it,contractId=null))})
            YSelect(word("目标合同","対象の契約"),a.contractId,listOf(null to word("按账单关联 / 不指定","請求の関連先・指定なし"))+contracts.map{it.id as String? to it.name},{row(i,a.copy(contractId=it))})
            YField(word("分配本金","充当元金"),a.principal,{row(i,a.copy(principal=it))},numeric=true)
            YField(word("分配利息","充当利息"),a.interest,{row(i,a.copy(interest=it))},numeric=true)
            YField(word("分配费用","充当手数料"),a.fee,{row(i,a.copy(fee=it))},numeric=true)
            YButton(word("移除此分配","この充当を削除"),{update(d.copy(rows=d.rows.filterIndexed{n,_->n!=i}))},enabled=!busy)
        }}
        YButton(word("＋ 添加清偿目标","＋ 充当先を追加"),{update(d.copy(rows=d.rows+AllocationDraft()))},Modifier.testTag("finance-repay-add-allocation"),enabled=!busy)
        YField(word("备注","メモ"),d.note,{update(d.copy(note=it))})
        YButton(word("预览实际还款","実際の返済を確認"),{vm.execute {
            val input=FinancialRepaymentInput(requireNotNull(d.source){"请选择付款账户 / 支払口座を選択してください"},account.id,requireNotNull(money(sourceCurrency,d.paid)),d.date,money(account.currency,d.received),money(account.currency,d.principal),money(account.currency,d.interest),money(account.currency,d.fee),money(s.book.currency,d.feeBase),d.confirmed,if(d.confirmed)d.clearingDate else null,RepaymentType.valueOf(d.type),money(sourceCurrency,d.sourceFee),money(s.book.currency,d.sourceFeeBase),
                d.rows.map{RepaymentAllocationInput(it.billId,it.contractId,money(account.currency,it.principal)?.minor,money(account.currency,it.interest)?.minor,money(account.currency,it.fee)?.minor)},d.linkedTransfer,eventId?:d.existing,d.note)
            vm.financialReviewRefreshed.value=false;vm.financialLedgerReview.value=vm.repository.previewRepayment(s.book.id,input)
        }},Modifier.testTag("finance-repay-preview"),primary=true,enabled=!busy)
    }
}

@Composable internal fun repaymentTypeLabel(type:RepaymentType)=when(type){RepaymentType.NORMAL->word("正常还款","通常返済");RepaymentType.PARTIAL->word("部分还款","一部返済");RepaymentType.EARLY->word("提前还款","繰上返済");RepaymentType.SETTLEMENT->word("结清","完済")}

@Composable private fun FinancialLedgerReviewContent(r:FinancialLedgerReview,vm:AppViewModel,onClose:()->Unit) {
    val draftSession=requireNotNull(LocalFinancialDraft.current)
    val busy by vm.busy.collectAsStateWithLifecycle()
    val refreshed by vm.financialReviewRefreshed.collectAsStateWithLifecycle()
    if(refreshed)FinanceRefreshed()
    r.borrowingInput?.let{input->
        YText(input.name,18)
        FinancialBorrowingFacts(r.after,r.contract!!)
    }
    r.repaymentInput?.let{input->
        YText(repaymentTypeLabel(input.type),18)
        FinancialRepaymentFacts(r.after,r.change!!.event,r.change.repayment!!)
        input.allocations.forEach{a->
            YText(listOfNotNull(a.billId?.let{id->r.before.finance.bills.first{it.id==id}.let{b->word("第${b.number}期","第${b.number}回")+" · "+b.date}},a.contractId?.let{id->r.before.finance.contracts.first{it.id==id}.name}).joinToString(" · "),12)
            val currency=input.received?.currency?:Currency.valueOf(r.after.accounts.first{it.id==input.debtAccountId}.currency)
            RecordFacts(listOf(word("分配本金 / 利息 / 费用","充当元金・利息・手数料") to listOf(a.principalMinor,a.interestMinor,a.feeMinor).joinToString(" / "){it?.let{n->Money(currency,n).display()}?:"?"}))
        }
    }
    ThemeSection(word("余额与支出变化","残高・支出の変化"),"BEFORE / AFTER")
    if(r.balances.isEmpty())YText(word("实际账户余额不变","実際の口座残高は変わりません"),12)
    val changes=r.balances.map{b->b.account.name to (Money(Currency.valueOf(b.account.currency),b.before).display()+" → "+Money(Currency.valueOf(b.account.currency),b.after).display())}+
        r.expenses.map{e->e.month+" · "+word("预算支出","予算支出") to (Money(Currency.valueOf(r.after.book.currency),e.beforeMinor).display()+" → "+Money(Currency.valueOf(r.after.book.currency),e.afterMinor).display())}
    if(changes.isNotEmpty())RecordFacts(changes)
    if(r.limitImpacts.isNotEmpty()) {
        ThemeSection(word("额度预计变化","利用枠の予測変化"),"ESTIMATED CAPACITY")
        r.limitImpacts.forEach{impact->
            val currency=Currency.valueOf(impact.account.currency)
            fun amount(n:Long?)=n?.let{Money(currency,it).display()}?:"?"
            YText(impact.account.name,14)
            RecordFacts(listOf(word("≈ 预计可用 / 共享约束后","≈ 予測利用可能額・共通枠適用後") to (amount(impact.before.effectiveTotalMinor)+" → "+amount(impact.after.effectiveTotalMinor))))
            impact.after.issues.forEach{YText(limitLabel(it),12,color=LocalYohaku.current.warm)}
        }
        YText(word("预计额度不是现金，也不代表平台已恢复可用额度。","予測枠は現金ではなく、返済先での回復確定も意味しません。"),11)
    }
    FinancialLedgerPlanImpacts(r)
    YButton(word("确认保存实际记录","実際の記録を保存"),{var saved=false;vm.execute(onSuccess={if(saved)onClose()}) {
        saved=commitFinancialDraft(draftSession){vm.repository.commitFinancialLedger(r)}!=null
        if(saved)vm.financialLedgerReview.value=null else {
            vm.financialLedgerReview.value=if(r.borrowingInput!=null)vm.repository.previewBorrowing(r.before.ledger.book.id,r.borrowingInput)else vm.repository.previewRepayment(r.before.ledger.book.id,r.repaymentInput!!)
            vm.financialReviewRefreshed.value=true
        }
    }},Modifier.testTag("finance-ledger-confirm"),primary=true,enabled=!busy)
    YButton(word("返回修改","戻って修正"),{vm.financialLedgerReview.value=null},Modifier.testTag("finance-ledger-back"),enabled=!busy)
}

@Composable internal fun FinancialRepaymentFacts(s:BookSnapshot,event:EventEntity,p:FinancialRepaymentEntity) {
    val debt=s.accounts.first{it.id==p.debtAccountId}
    val unknown=word("未知","不明")
    fun value(n:Long?)=n?.let{Money(Currency.valueOf(debt.currency),it).display()}?:unknown
    YText(if(p.platformConfirmed)word("平台已确认清偿","返済先が返済を確定")else word("实际已付款 · 清偿待确认","支払済み・返済確定待ち"),12,color=LocalYohaku.current.accent)
    RecordFacts(listOf(word("实际扣款","実出金額") to Money(Currency.valueOf(event.currency),event.amountMinor).display(),word("还款目标","返済先") to debt.name,word("平台实收","返済先受取額") to value(p.receivedMinor),word("清偿本金","返済元金") to value(p.principalMinor),word("实付利息","実支払利息") to value(p.interestMinor),word("平台费用","返済先手数料") to value(p.feeMinor),word("清偿日期","返済確定日") to (p.clearingDate?:word("待确认","未確定")),word("总扣款内含付款端费用","実出金総額に含む出金側手数料") to (p.sourceFeeMinor?.let{Money(Currency.valueOf(event.currency),it).display()}?:word("未知","不明"))))
    if(p.linkedTransferEventId!=null)YText(word("关联先前换汇 / 转账：","先行する両替・振替：")+p.linkedTransferEventId.take(8),11)
}

@Composable internal fun FinancialBorrowingFacts(s:BookSnapshot,c:DebtContractEntity) {
    val unknown=word("未知","不明")
    fun display(currency:String,n:Long?)=n?.let{Money(Currency.valueOf(currency),it).display()}?:unknown
    RecordFacts(listOf(word("合同本金","契約元金") to display(c.currency,c.principalMinor),word("借款模式","借入方式") to financeLabel(c.mode),word("借入日期","借入日") to c.date))
    if(c.fundingEventId==null)YText(word("期初合同 · 不产生新的资金变动","期首契約・新しい資金変動は発生しません"),11)
    else {
        val funds=s.accounts.first{it.id==c.fundingAccountId}
        RecordFacts(listOf(word("实际到账账户","実際の入金先口座") to funds.name,word("实际到账","実入金額") to display(funds.currency,c.receivedMinor),word("预扣费用","天引手数料") to display(c.currency,c.withheldFeeMinor),word("另付费用","別払い手数料") to display(s.accounts.firstOrNull{it.id==c.separateFeeAccountId}?.currency?:funds.currency,c.separateFeeMinor)))
    }
}
