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
    val r=review?.takeIf{it.borrowingInput?.accountId==account.id&&it.before.ledger.book.id==s.book.id}
    FinancialFlow(if(opening)word("补充期初借款合同","期首の借入契約を補完")else word("实际借入","実際の借入"),step=if(r!=null)1 else 0,
        onClose={vm.financialLedgerReview.value=null;onClose()},onBack={vm.financialLedgerReview.value=null},
        scrollState=if(r!=null)reviewScroll else formScroll,actions={
            if(r!=null)FinancialLedgerReviewActions(r,vm,onClose)else {
                YButton(word("预览借入记录","借入記録を確認"),{vm.execute {
            val input=FinancialBorrowingInput(account.id,d.name,FinanceMode.valueOf(d.mode),requireNotNull(money(account.currency,d.principal)),d.date,
                if(opening)null else d.funds,if(opening)null else money(currency,d.received),if(opening)null else money(account.currency,d.withheld),if(opening)null else money(s.book.currency,d.withheldBase),
                if(opening)null else d.separatePayer,if(opening)null else money(feeCurrency,d.separate),if(opening)null else money(s.book.currency,d.separateBase),opening,eventId,contract?.id,d.note)
            vm.financialReviewRefreshed.value=false;vm.financialLedgerReview.value=vm.repository.previewBorrowing(s.book.id,input)
        }},Modifier.testTag("finance-borrow-preview"),primary=true,enabled=!busy)
            }
        }) {
        if(r!=null){FinancialLedgerReviewContent(r,vm,onClose);return@FinancialFlow}
        FinancialDraftNotice()
        FinancialReviewSection(account.name){YText(if(opening)word("期初欠款已包含在账户余额中，此处只补充合同，不再次增加本金或到账。","期首債務は口座残高に含まれています。契約のみ補完し、元金・入金を再加算しません。")else word("合同本金、实际到账与费用分别记录。未知费用留空，不把到账差额自动当作费用。","契約元金・実入金・手数料を分けて記録します。不明な手数料は空欄にし、入金差額から推定しません。"),11)}
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

    }
}

@Composable internal fun repaymentTypeLabel(type:RepaymentType)=when(type){RepaymentType.NORMAL->word("正常还款","通常返済");RepaymentType.PARTIAL->word("部分还款","一部返済");RepaymentType.EARLY->word("提前还款","繰上返済");RepaymentType.SETTLEMENT->word("结清","完済")}

@Composable internal fun FinancialLedgerReviewContent(r:FinancialLedgerReview,vm:AppViewModel,onClose:()->Unit) {
    val refreshed by vm.financialReviewRefreshed.collectAsStateWithLifecycle()
    if(refreshed)FinanceRefreshed()
    r.borrowingInput?.let{input->
        FinancialChangeSummary {
            YText(input.name,18)
            val unknown=word("未知","不明")
            RecordFacts(listOf(word("合同本金","契約元金") to input.principal.display()))
            if(!input.opening)RecordFacts(listOf(word("实际到账","実入金額") to (input.received?.display()?:unknown),
                word("预扣费用","天引手数料") to (input.withheldFee?.display()?:unknown),word("另付费用","別払い手数料") to (input.separateFee?.display()?:unknown)))
        }
    }
    r.repaymentInput?.let{input->
        FinancialRepaymentSummary(word("付款与清偿","支払・返済確定"),"finance-repay-review-summary") {
            RecordFacts(listOf(word("实际付款账户","実際の支払口座") to r.after.accounts.first{it.id==input.sourceAccountId}.name,
                word("实际扣款","実出金額") to input.paid.display(),word("实际付款日期","実際の支払日") to input.date))
            val unknown=word("未知","不明")
            RecordFacts(listOf(word("实付利息 / 平台费用","実支払利息・返済先手数料") to ((input.interest?.display()?:unknown)+" / "+(input.fee?.display()?:unknown)),
                word("付款端费用","出金側手数料") to (input.sourceFee?.display()?:unknown)))
            if(input.eventId!=null)YText(word("修订已有付款，请按下方前后值核对资金变化。","既存の支払を修正します。下の変更前後で資金の変化をご確認ください。"),11)
            YText(if(input.platformConfirmed)word("平台已确认清偿","返済先が返済を確定")else word("实际已付款 · 清偿待确认；本次不减少债务本金。","支払済み・返済確定待ち。今回は債務元金を減らしません。"),12,color=LocalYohaku.current.accent)
        }
    }
    ThemeSection(word("余额与支出变化","残高・支出の変化"),"BEFORE / AFTER")
    if(r.balances.isEmpty())YText(word("实际账户余额不变","実際の口座残高は変わりません"),12)
    val changes=r.balances.map{b->b.account.name to (Money(Currency.valueOf(b.account.currency),b.before).display()+" → "+Money(Currency.valueOf(b.account.currency),b.after).display())}+
        r.expenses.map{e->e.month+" · "+word("预算支出","予算支出") to (Money(Currency.valueOf(r.after.book.currency),e.beforeMinor).display()+" → "+Money(Currency.valueOf(r.after.book.currency),e.afterMinor).display())}
    if(changes.isNotEmpty())RecordFacts(changes)
    r.repaymentInput?.let{input->
        FinancialRepaymentSummary(word("分配目标与待付变化","充当先・未払額の変化"),"finance-repay-review-targets") {
            val currency=Currency.valueOf(r.after.accounts.first{it.id==input.debtAccountId}.currency)
            val unknown=word("未知","不明")
            fun value(n:Long?)=n?.let{Money(currency,it).display()}?:unknown
            input.allocations.forEach{a->
                a.billId?.let{id->r.before.finance.bills.first{it.id==id}.let{b->
                    YText(r.before.finance.plans.first{it.id==b.planId}.name+" · "+word("第${b.number}期","第${b.number}回")+" · "+b.date,12)
                    RecordFacts(listOf(word("账单待付","請求残額") to (value(r.before.ledger.billOutstanding(b).totalMinor)+" → "+value(r.after.billOutstanding(b).totalMinor))))
                }}
                a.contractId?.let{id->r.before.finance.contracts.first{it.id==id}.let{c->
                    YText(c.name,12)
                    RecordFacts(listOf(word("合同剩余本金","契約の残元金") to (value(r.before.ledger.contractRemaining(c))+" → "+value(r.after.contractRemaining(c)))))
                }}
                RecordFacts(listOf(word("分配本金 / 利息 / 费用","充当元金・利息・手数料") to listOf(a.principalMinor,a.interestMinor,a.feeMinor).joinToString(" / "){value(it)}))
            }
            if(input.allocations.isEmpty())YText(word("未分配到具体账单或合同","個別の請求・契約に未充当"),12)
        }
    }
    if(r.repaymentInput!=null)FinancialLedgerPlanImpacts(r)
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
    if(r.repaymentInput==null)FinancialLedgerPlanImpacts(r)
    FinancialFullDetails {
        r.contract?.let{FinancialBorrowingFacts(r.after,it)}
    r.repaymentInput?.let{input->
        ThemeSection(word("完整付款资料","支払の全情報"),"PAYMENT DETAILS")
        YText(repaymentTypeLabel(input.type),14)
        FinancialRepaymentFacts(r.after,r.change!!.event,r.change.repayment!!)
    }

    }
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

@Composable internal fun FinancialLedgerReviewActions(r:FinancialLedgerReview,vm:AppViewModel,onClose:()->Unit,onStale:()->Unit={}) {
    val draftSession=requireNotNull(LocalFinancialDraft.current)
    val busy by vm.busy.collectAsStateWithLifecycle()
    YButton(word("确认保存实际记录","実際の記録を保存"),{var saved=false;vm.execute(onSuccess={if(saved)onClose()}) {
            saved=commitFinancialDraft(draftSession){vm.repository.commitFinancialLedger(r)}!=null
            if(saved)vm.financialLedgerReview.value=null else {
                vm.financialLedgerReview.value=if(r.borrowingInput!=null)vm.repository.previewBorrowing(r.before.ledger.book.id,r.borrowingInput)else null
                onStale()
                vm.financialReviewRefreshed.value=true
            }
        }},Modifier.testTag("finance-ledger-confirm"),primary=true,enabled=!busy)
}
