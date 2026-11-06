package com.yohaku.expensetracker.ui.features

import com.yohaku.expensetracker.ui.haptics.Haptic
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
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
import java.time.LocalDate

private fun amount(currency:String,value:Long?)=value?.let{Money(Currency.valueOf(currency),it).decimal.toPlainString()}.orEmpty()
private fun money(currency:String,value:String)=value.takeIf{it.isNotBlank()}?.let{Money.parse(it,Currency.valueOf(currency))}

@Composable internal fun FinancialRepaymentEditor(s:BookSnapshot,account:AccountEntity,finance:FinancialGraph,vm:AppViewModel,onClose:()->Unit,eventId:String?=null,billId:String?=null,contractId:String?=null,paymentDate:String?=null) {
    FinancialDraftHost(s,FinancialDraftContext("REPAY",account.id,targetId=eventId,billId=billId,contractId=contractId,paymentDate=paymentDate),vm,onClose){FinancialRepaymentEditorContent(s,account,finance,vm,onClose,eventId,billId,contractId,paymentDate)}
}

@Composable private fun FinancialRepaymentEditorContent(s:BookSnapshot,account:AccountEntity,finance:FinancialGraph,vm:AppViewModel,onClose:()->Unit,eventId:String?,billId:String?,contractId:String?,paymentDate:String?) {
    val old=s.repayments.firstOrNull{it.eventId==eventId}
    val event=s.events.firstOrNull{it.id==eventId}
    val initialDate=LocalDate.now(vm.repository.clock).toString()
    val stale=finance.plans.filter{vm.repository.financialPredictionStale(s,finance,it)}.map{it.id}.toSet()
    val statement=s.financialAccountSummary(finance,account.id,initialDate,stale).rows
    val initial=statement.firstOrNull{it.bill.id==billId}
    fun suggestion(id:String?)=statement.firstOrNull{it.bill.id==id}?.let{repaymentSuggestion(it,it.plan.id in stale)}
    val initialSuggestion=suggestion(billId)
    val dateTargets=statement.filter{it.bill.date==paymentDate&&it.outstanding.totalMinor>0&&it.cashRequiredMinor!=0L}
    val initialSource=event?.accountId?:initial?.bill?.paymentAccountId?:dateTargets.map{it.bill.paymentAccountId}.distinct().singleOrNull()
    val initialCurrency=s.accounts.firstOrNull{it.id==initialSource}?.currency
    var encoded by financialField("encoded"){vm.repository.json.encodeToString(
        RepaymentDraft(initialSource,amount(event?.currency?:account.currency,event?.amountMinor?:initialSuggestion?.paid?.takeIf{initialCurrency==account.currency}),event?.date?:initialDate,
            amount(account.currency,old?.receivedMinor),amount(account.currency,if(old!=null)old.principalMinor else initialSuggestion?.principal),
            amount(account.currency,if(old!=null)old.interestMinor else 0L.takeIf{initialSuggestion?.zeroCombinedFee==true}),amount(account.currency,if(old!=null)old.feeMinor else 0L.takeIf{initialSuggestion?.zeroCombinedFee==true}),amount(s.book.currency,old?.feeBaseMinor),
            false,old?.clearingDate?:initialDate,old?.paymentType?:RepaymentType.NORMAL.name,amount(event?.currency?:account.currency,old?.sourceFeeMinor),amount(s.book.currency,old?.sourceFeeBaseMinor),
            if(old!=null)s.repaymentAllocations.filter{it.eventId==eventId}.map{AllocationDraft(it.billId,it.contractId,amount(account.currency,it.principalMinor),amount(account.currency,it.interestMinor),amount(account.currency,it.feeMinor))}else if(billId!=null||contractId!=null)listOf(AllocationDraft(billId,contractId))else emptyList(),
            old?.linkedTransferEventId,eventId,event?.note.orEmpty(),singleTarget=old==null).let{it.copy(singleTarget=old==null||it.canShareComposition())}.withSingleAllocation().let{draft->
            if(paymentDate==null)draft else repaymentDateDraft(draft,dateTargets,stale,Currency.valueOf(account.currency),initialCurrency==account.currency)
        })}
    val stored=vm.repository.json.decodeFromString<RepaymentDraft>(encoded)
    val d=if(stored.singleTarget==null)stored.copy(singleTarget=stored.canShareComposition())else stored
    fun update(v:RepaymentDraft){encoded=vm.repository.json.encodeToString(v.copy(confirmed=false).withSingleAllocation())}
    fun row(index:Int,value:AllocationDraft){update(d.copy(rows=d.rows.mapIndexed{i,a->if(i==index)value else a}))}
    val session=requireNotNull(LocalFinancialDraft.current)
    // A saved record is a fact; a resumed form's confirmation is not.
    var confirmed by remember(session){mutableStateOf(old?.platformConfirmed==true&&!session.resumed)}
    val source=s.accounts.firstOrNull{it.id==d.source}
    val sourceCurrency=source?.currency?:s.book.currency
    val funds=s.accounts.filter{(it.active||event?.accountId==it.id)&&!AccountType.valueOf(it.type).debt}
    val bills=finance.bills.filter{it.active&&finance.plans.any{p->p.id==it.planId&&p.accountId==account.id}}
    val contracts=finance.contracts.filter{it.accountId==account.id}
    val currentSuggestion=suggestion(d.rows.singleOrNull()?.billId)
    val paidSuggestion=currentSuggestion.takeIf{sourceCurrency==account.currency}
    var details by financialField("repaymentDetails"){old!=null||d.singleTarget!=true||d.received.isNotBlank()||d.sourceFee.isNotBlank()||d.feeBase.isNotBlank()||d.sourceFeeBase.isNotBlank()||sourceCurrency!=account.currency||currentSuggestion?.zeroCombinedFee==false}
    var links by financialField("repaymentLinks"){old!=null||d.linkedTransfer!=null||d.existing!=null}
    var multiple by financialField("repaymentMultiple"){old!=null||d.singleTarget!=true}
    var other by financialField("repaymentOther"){old!=null||d.type!=RepaymentType.NORMAL.name||d.note.isNotBlank()}
    val busy by vm.busy.collectAsStateWithLifecycle()
    val review by vm.financialLedgerReview.collectAsStateWithLifecycle()
    val refreshed by vm.financialReviewRefreshed.collectAsStateWithLifecycle()
    var dateOwner by remember{mutableStateOf<Int?>(null)}
    val formScroll=rememberScrollState();val reviewScroll=rememberScrollState()
    LaunchedEffect(review){if(review!=null)reviewScroll.scrollTo(0)}
    fun selectTarget(value:String?) {
        val targetBill=value?.removePrefix("bill:")?.takeIf{value.startsWith("bill:")}
        val targetContract=value?.removePrefix("contract:")?.takeIf{value.startsWith("contract:")}
        val next=suggestion(targetBill)
        val payer=d.source?:bills.firstOrNull{it.id==targetBill}?.paymentAccountId
        val currency=s.accounts.firstOrNull{it.id==payer}?.currency
        update(d.copy(source=payer,paid=amount(currency?:account.currency,next?.paid?.takeIf{currency==account.currency}),principal=amount(account.currency,next?.principal),interest=if(next?.zeroCombinedFee==true)"0"else"",fee=if(next?.zeroCombinedFee==true)"0"else"",rows=if(value==null)emptyList()else listOf(AllocationDraft(targetBill,targetContract))))
        if(next?.zeroCombinedFee!=true||currency!=account.currency)details=true
    }
    val r=review?.takeIf{it.repaymentInput?.debtAccountId==account.id&&it.before.ledger.book.id==s.book.id}
    FinancialFlow(word("记录还款","返済を記録"),step=if(r!=null)1 else 0,
        onClose={vm.financialLedgerReview.value=null;onClose()},onBack={vm.financialLedgerReview.value=null},
        scrollState=if(r!=null)reviewScroll else formScroll,actions={
            if(r!=null)FinancialLedgerReviewActions(r,vm,onClose,onStale={confirmed=false})else {
                YButton(word("预览实际还款","実際の返済を確認"),{vm.execute {
            val input=FinancialRepaymentInput(requireNotNull(d.source){"请选择付款账户 / 支払口座を選択してください"},account.id,requireNotNull(money(sourceCurrency,d.paid)),d.date,money(account.currency,d.received),money(account.currency,d.principal),money(account.currency,d.interest),money(account.currency,d.fee),money(s.book.currency,d.feeBase),confirmed,if(confirmed)d.clearingDate else null,RepaymentType.valueOf(d.type),money(sourceCurrency,d.sourceFee),money(s.book.currency,d.sourceFeeBase),
                d.withSingleAllocation().rows.map{RepaymentAllocationInput(it.billId,it.contractId,money(account.currency,it.principal)?.minor,money(account.currency,it.interest)?.minor,money(account.currency,it.fee)?.minor)},d.linkedTransfer,eventId?:d.existing,d.note)
            vm.financialReviewRefreshed.value=false;vm.financialLedgerReview.value=vm.repository.previewRepayment(s.book.id,input)
        }},Modifier.testTag("finance-repay-preview"),primary=true,enabled=!busy)
            }
        }) {
        if(r!=null){FinancialLedgerReviewContent(r,vm,onClose);return@FinancialFlow}
        if(refreshed)FinanceRefreshed()
        if(d.singleTarget==true) {
            val target=d.rows.singleOrNull()
            YSelect(word("还款目标","返済の対象"),target?.billId?.let{"bill:$it"}?:target?.contractId?.let{"contract:$it"},
                listOf(null to (account.name+" · "+word("不指定账单","請求を指定しない")))+
                    bills.map{"bill:${it.id}" as String? to (finance.plans.first{p->p.id==it.planId}.name+" · ${it.number} · ${it.date}")}+
                    contracts.map{"contract:${it.id}" as String? to it.name},::selectTarget,Modifier.testTag("finance-repay-target"))
        }else YText(account.name+" · "+word("${d.rows.size} 个分配目标","${d.rows.size} 件の充当先"),14)
        YSelect(word("实际付款账户","実際の支払口座"),d.source,listOf(null to word("请选择","選択してください"))+funds.map{it.id as String? to (it.name+" · "+it.currency)},{
            update(d.copy(source=it,paid="",sourceFee="",sourceFeeBase="",linkedTransfer=null));if(s.accounts.firstOrNull{a->a.id==it}?.currency!=account.currency)details=true
        })
        YField(word("实际扣款总额","実出金総額")+" · "+sourceCurrency,d.paid,{
            update(d.withPaid(it,paidSuggestion,Currency.valueOf(sourceCurrency)))
            if(paidSuggestion?.differsFromPaid(it,Currency.valueOf(sourceCurrency))==true)details=true
        },numeric=true)
        if(old==null&&currentSuggestion?.paid!=null)YText(word("本期已知待付建议：","当期の既知の未払推奨額：")+Money(Currency.valueOf(account.currency),currentSuggestion.paid).display()+word("。已扣除明确的已付待确认分配，请核对实际付款。","。明示済みの確認待ち充当額を差し引いています。実支払額をご確認ください。"),11)
        Column(Modifier.testTag("finance-repay-payment-date")) { YDateFields(listOf(word("实际付款日期","実際の支払日") to d.date),dateOwner?.takeIf{it==0},{dateOwner=it},{_,v->update(d.copy(date=v))}) }
        YToggle(word("平台已确认清偿组成","返済先が内訳を確定した"),confirmed,{confirmed=it;if(it)details=true},Modifier.testTag("finance-repay-cleared"))
        FinancialRepaymentSummary(word("组成与分配摘要","内訳・充当の概要"),"finance-repay-allocation-summary") {
            YText(if(confirmed)word("平台清偿已确认","返済先の返済確定済み")else word("实际已付款 · 清偿待确认","支払済み・返済確定待ち"),12,color=LocalYohaku.current.accent)
            val unknown=word("未知","不明")
            val invalid=word("金额无效","金額が無効")
            fun display(value:String)=if(value.isBlank())unknown else runCatching{Money.parse(value,Currency.valueOf(account.currency)).display()}.getOrElse{invalid}
            YText(word("本金 / 利息 / 平台费用：","元金・利息・返済先手数料：")+listOf(d.principal,d.interest,d.fee).joinToString(" / "){display(it)},12)
            d.rows.forEach{a->YText(listOfNotNull(a.billId?.let{id->bills.firstOrNull{it.id==id}?.let{b->finance.plans.first{it.id==b.planId}.name+" · ${b.number} · ${b.date}"}},a.contractId?.let{id->contracts.firstOrNull{it.id==id}?.name}).joinToString(" · "),12)
                if(d.singleTarget!=true)YText(listOf(a.principal,a.interest,a.fee).joinToString(" / "){display(it)},12)
            }
            if(d.rows.isEmpty())YText(word("未指定目标；本金不会自动结清账单或合同。","対象未指定。元金で請求・契約を自動精算しません。"),11)
            YText(word("实收与付款端费用未填写时仍为未知；付款不等于平台确认。","受取額・出金側手数料の未入力は不明のままです。支払と返済先の確定は別です。"),11,color=LocalYohaku.current.muted)
        }
        YButton(word("费用与清偿组成","手数料・返済の内訳")+if(details)" −"else" +",{details=!details},Modifier.testTag("finance-repay-details"))
        if(details) {
            if(currentSuggestion?.zeroCombinedFee==false)YText(word("账单只有利息/费用合计，不能自动拆分。部分还款请按实际组成填写。","請求には利息・手数料の合計のみがあり、自動で分けられません。一部返済は実際の内訳を入力してください。"),11,color=LocalYohaku.current.warm)
            YText(word("未知留空，已知为零填 0。修改付款金额不会按比例猜测清偿组成。","不明は空欄、既知のゼロは0を入力。支払額の変更から返済内訳を比例推定しません。"),11)
            YField(word("平台实际收到","返済先の実受取額")+" · "+account.currency,d.received,{update(d.copy(received=it))},numeric=true)
            YField(word("清偿本金","返済元金")+" · "+account.currency,d.principal,{update(d.copy(principal=it))},numeric=true)
            YField(word("实付利息","実支払利息")+" · "+account.currency,d.interest,{update(d.copy(interest=it))},numeric=true)
            YField(word("平台费用","返済先手数料")+" · "+account.currency,d.fee,{update(d.copy(fee=it))},numeric=true)
            if(account.currency!=s.book.currency)YField(word("利息及平台费用统计合计","利息・返済先手数料の集計合計")+" · "+s.book.currency,d.feeBase,{update(d.copy(feeBase=it))},numeric=true)
            YField(word("总扣款内含付款端费用","実出金総額に含む出金側手数料")+" · "+sourceCurrency,d.sourceFee,{update(d.copy(sourceFee=it))},numeric=true)
            if(sourceCurrency!=s.book.currency)YField(word("付款端费用统计金额","出金側手数料の集計額")+" · "+s.book.currency,d.sourceFeeBase,{update(d.copy(sourceFeeBase=it))},numeric=true)
            Column(Modifier.testTag("finance-repay-clearing-date")) { YDateFields(listOf(word("平台清偿日期","返済先の確定日") to d.clearingDate),dateOwner?.takeIf{it==1}?.let{0},{dateOwner=it?.let{1}},{_,v->update(d.copy(clearingDate=v))}) }
        }
        YButton(word("关联已有付款或转账","既存の支払・振替に関連付け")+if(links)" −"else" +",{links=!links},Modifier.testTag("finance-repay-link-existing"))
        if(links) {
            if(eventId==null) {
                val candidates=s.visibleEvents.filter{it.kind in listOf(EntryKind.EXPENSE.name,EntryKind.REPAYMENT.name)&&s.repayments.none{p->p.eventId==it.id}&&funds.any{a->a.id==it.accountId}}
                YSelect(word("付款来源","支払の記録"),d.existing,listOf(null to word("记录新的实际付款","新しい支払を記録"))+candidates.map{it.id as String? to (it.date+" · "+it.merchant+" · "+s.accountMoney(it).display())},{id->val e=candidates.firstOrNull{it.id==id};update(d.withPaid(e?.let{amount(s.accountMoney(it).currency.name,s.accountMoney(it).minor)}?:d.paid,currentSuggestion.takeIf{(e?.let{s.accountMoney(it).currency.name}?:sourceCurrency)==account.currency},Currency.valueOf(e?.let{s.accountMoney(it).currency.name}?:sourceCurrency)).copy(existing=id,source=e?.accountId?:d.source,date=e?.date?:d.date));details=true})
            }else YText(word("编辑原付款，保留记录编号；不会重复扣款。","元の支払を編集し、記録IDを保持。重複出金はしません。"),11)
            if(d.existing!=null)YText(word("保留已有付款编号与原扣款，请核对账户、日期和金额。","既存支払のID・出金を保持。口座・日付・金額をご確認ください。"),11)
            val transfers=s.transfers.filter{it.toAccountId==d.source&&s.visibleEvents.any{e->e.id==it.eventId}}
            YSelect(word("关联先前换汇 / 转账","先行する両替・振替に関連付け"),d.linkedTransfer,listOf(null to word("不关联 / 直接付款","関連なし・直接支払"))+transfers.map{it.eventId as String? to (s.events.first{e->e.id==it.eventId}.date+" · "+it.eventId.take(8))},{update(d.copy(linkedTransfer=it))})
        }
        YButton(word("分配到多个目标","複数の対象へ充当")+if(multiple)" −"else" +",{multiple=!multiple},Modifier.testTag("finance-repay-multiple-targets"))
        if(multiple) {
            YText(word("单目标沿用上面的组成；启用独立分配后可分别填写各目标。未分配本金不会自动结清账单。","単一対象は上の内訳を使用。個別充当を有効にすると対象別に入力できます。未充当元金で請求を自動精算しません。"),11)
            if(d.singleTarget==true)YButton(word("启用独立分配","個別充当を有効にする"),{update(d.copy(singleTarget=false))},Modifier.testTag("finance-repay-independent"),haptic=Haptic.ToggleOn)
            else d.rows.forEachIndexed{i,a->Panel(Modifier.testTag("finance-repay-allocation-$i")) {
                YSelect(word("目标账单","対象の請求"),a.billId,listOf(null to word("不指定账单","請求を指定しない"))+bills.map{it.id as String? to (finance.plans.first{p->p.id==it.planId}.name+" · ${it.number} · ${it.date}")},{row(i,a.copy(billId=it,contractId=null))})
                YSelect(word("目标合同","対象の契約"),a.contractId,listOf(null to word("按账单关联 / 不指定","請求の関連先・指定なし"))+contracts.map{it.id as String? to it.name},{row(i,a.copy(contractId=it))})
                YField(word("分配本金","充当元金"),a.principal,{row(i,a.copy(principal=it))},numeric=true)
                YField(word("分配利息","充当利息"),a.interest,{row(i,a.copy(interest=it))},numeric=true)
                YField(word("分配费用","充当手数料"),a.fee,{row(i,a.copy(fee=it))},numeric=true)
                YButton(word("移除此分配","この充当を削除"),{update(d.copy(rows=d.rows.filterIndexed{n,_->n!=i}))},enabled=!busy)
            }}
            YButton(word("＋ 添加清偿目标","＋ 充当先を追加"),{update(d.copy(singleTarget=false,rows=d.rows+AllocationDraft()))},Modifier.testTag("finance-repay-add-allocation"),enabled=!busy)
        }
        YButton(word("还款类型与备注","返済種別・メモ")+if(other)" −"else" +",{other=!other},Modifier.testTag("finance-repay-other"))
        if(other) {
            YSelect(word("还款类型","返済種別"),d.type,RepaymentType.entries.map{it.name to repaymentTypeLabel(it)},{update(d.copy(type=it))})
            YField(word("备注","メモ"),d.note,{update(d.copy(note=it))})
        }

        FinancialDraftNotice()
    }
}
