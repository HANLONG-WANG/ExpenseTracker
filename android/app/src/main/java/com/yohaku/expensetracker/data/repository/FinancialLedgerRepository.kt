package com.yohaku.expensetracker.data.repository

import androidx.room.withTransaction
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.domain.finance.*
import com.yohaku.expensetracker.domain.model.*
import kotlinx.serialization.encodeToString
import java.lang.Math.addExact
import java.lang.Math.subtractExact
import java.time.LocalDate

data class FinancialCreditLimitChange(val account:AccountEntity,val before:AccountCreditLimit,val after:AccountCreditLimit)

data class FinancialExpenseChange(val month:String,val beforeMinor:Long,val afterMinor:Long)
class FinancialLedgerReview internal constructor(
    internal val before:FinancialReviewState,val change:PreparedLedgerChange?,val contract:DebtContractEntity?,
    val borrowingInput:FinancialBorrowingInput?,val repaymentInput:FinancialRepaymentInput?,
    val balances:List<AccountBalanceChange>,val expenses:List<FinancialExpenseChange>,val after:BookSnapshot,val planImpacts:List<FinancialPlanImpact>,val limitImpacts:List<FinancialCreditLimitChange>
)

private fun BookSnapshot.projectFinance(change:PreparedLedgerChange):BookSnapshot {
    val e=change.event
    val nextEvents=events.filter{it.id!=e.id}+e
    val live=nextEvents.filter{it.deletedAt==null}.map{it.id}.toSet()
    val all=storedPostings.filter{it.eventId!=e.id}+change.postings
    return copy(events=nextEvents,storedPostings=all,postings=all.filter{it.confirmed&&it.eventId in live},
        settlements=settlements.filter{it.eventId!=e.id}+change.settlement,
        expenseComponents=expenseComponents.filter{it.eventId!=e.id}+change.components,
        contracts=if(change.contract==null)contracts else contracts.filter{it.id!=change.contract.id}+change.contract,
        repayments=if(change.repayment==null)repayments else repayments.filter{it.eventId!=e.id}+change.repayment,
        repaymentAllocations=if(change.repayment==null)repaymentAllocations else repaymentAllocations.filter{it.eventId!=e.id}+change.repaymentAllocations)
}
private fun LedgerRepository.financialReview(state:FinancialReviewState,change:PreparedLedgerChange?,contract:DebtContractEntity?,borrow:FinancialBorrowingInput?,repay:FinancialRepaymentInput?):FinancialLedgerReview {
    val before=state.ledger;val next=if(change!=null)before.projectFinance(change)else {val value=requireNotNull(contract);before.copy(contracts=before.contracts.filter{it.id!=value.id}+value)}
    val balances=before.accounts.mapNotNull{a->val old=before.balance(a);val after=next.balance(a);if(old==after)null else AccountBalanceChange(a,old,after)}
    val months=(before.expenseComponents.filter{it.eventId==change?.event?.id}.map{it.date.take(7)}+change?.components.orEmpty().map{it.date.take(7)}+
        listOfNotNull(change?.before?.takeIf{it.kind==EntryKind.EXPENSE.name}?.date?.take(7))).distinct()
    val expenses=months.map{FinancialExpenseChange(it,before.monthExpense(it),next.monthExpense(it))}
    val nextFinance=state.finance.copy(contracts=next.contracts,repayments=next.repayments,repaymentAllocations=next.repaymentAllocations)
    val asOf=LocalDate.now(clock).toString()
    val limits=before.accounts.filter{a->state.limits.configs.any{it.accountId==a.id}}.mapNotNull{a->
        val old=accountCreditLimit(before,state.finance,state.limits,a.id,asOf)
        val updated=accountCreditLimit(next,nextFinance,state.limits,a.id,asOf)
        if(old==updated)null else FinancialCreditLimitChange(a,old,updated)
    }
    return FinancialLedgerReview(state,change,contract,borrow,repay,balances,expenses,next,financialPlanImpacts(state,next,change,contract),limits)
}
private fun requireMoney(money:Money,currency:String,positive:Boolean=false) {
    require(money.currency.name==currency&&if(positive)money.minor>0 else money.minor>=0){"请核对币种与金额 / 通貨と金額を確認してください"}
}
private fun feeBase(book:BookEntity,fee:Money,base:Money?):Money {
    val result=if(fee.currency.name==book.currency)fee else if(fee.minor==0L)Money(Currency.valueOf(book.currency),0)else requireNotNull(base){"请填写费用的本位币统计金额 / 手数料の基準通貨集計額を入力してください"}
    requireMoney(result,book.currency)
    return result
}

suspend fun LedgerRepository.previewBorrowing(bookId:String,input:FinancialBorrowingInput):FinancialLedgerReview=db.withTransaction {
    val state=financialReviewState(bookId);val s=state.ledger
    val debt=requireNotNull(s.accounts.firstOrNull{it.id==input.accountId&&it.type==AccountType.LOAN.name&&it.active}){"请选择本账本的贷款账户 / この帳簿のローン口座を選択してください"}
    require(input.mode in listOf(FinanceMode.FIXED_LOAN,FinanceMode.CREDIT_LINE)&&input.name.isNotBlank())
    requireMoney(input.principal,debt.currency,true);LocalDate.parse(input.date)
    val old=input.contractId?.let{requireNotNull(s.contracts.firstOrNull{c->c.id==it&&c.accountId==debt.id})}
    require(old==null||old.fundingEventId==input.eventId&&((old.fundingEventId==null)==input.opening)){"合同来源不可改换 / 契約の対象は変更できません"}
    if(old!=null) {
        val linked=state.finance.contractPlans.filter{it.contractId==old.id}.map{it.planId}.toSet()
        require(state.finance.plans.filter{it.active&&state.finance.arrangements.none{a->a.originalPlanId==it.id||a.targetPlanId==it.id}&&(it.id in linked||old.fundingEventId!=null&&it.sourceEventId==old.fundingEventId)}.all{it.mode==input.mode.name}){"已有计划的借款方式不能直接改换，请记录已办理变更 / 既存プランの借入方式は直接変更できません。手続き済みの変更を記録してください"}
        val cleared=s.appliedRepaymentAllocations().filter{it.contractId==old.id}.fold(0L){sum,a->addExact(sum,a.principalMinor!!)}
        require(input.principal.minor>=cleared){"合同本金小于已分配清偿本金，请先核对还款记录 / 契約元金が充当済み返済元金を下回っています。返済記録を先に確認してください"}
    }
    if(input.opening) {
        require(input.date<=debt.openingDate){"期初合同日期不能晚于账户期初 / 期首契約日は口座の期首日以前にしてください"}
        require(input.eventId==null&&input.received==null&&input.withheldFee==null&&input.separateFee==null){"期初合同只补已有本金，不重新记录到账或费用 / 期首契約は既存元金のみ補完し、入金・手数料を再記録しません"}
        val used=s.contracts.filter{it.accountId==debt.id&&it.fundingEventId==null&&it.id!=old?.id}.fold(0L){sum,c->addExact(sum,c.principalMinor)}
        require(input.principal.minor<=subtractExact(debt.openingMinor,used)){"本金超过尚未分配的期初欠款 / 元金が未割当ての期首残高を超えています"}
        val contract=DebtContractEntity(bookId,old?.id?:id(),debt.id,input.name.trim(),input.mode.name,debt.currency,input.principal.minor,input.date,null,null,null,null,null,null,null,null,old?.createdAt?:clock.millis(),(old?.revision?:0)+1)
        return@withTransaction financialReview(state,null,contract,input,null)
    }
    val funds=requireNotNull(s.accounts.firstOrNull{it.id==input.fundingAccountId&&it.active&&!AccountType.valueOf(it.type).debt}){"请选择实际到账资金账户 / 実際の入金先資金口座を選択してください"}
    val received=requireNotNull(input.received){"请填写实际到账金额 / 実入金額を入力してください"};requireMoney(received,funds.currency)
    input.withheldFee?.let{requireMoney(it,debt.currency)
        if(funds.currency==debt.currency)require(addExact(received.minor,it.minor)==input.principal.minor){"到账与预扣费用合计不等于合同本金 / 入金額と天引手数料の合計が契約元金と一致しません"}}
    val previous=input.eventId?.let{requireNotNull(s.visibleEvents.firstOrNull{e->e.id==it&&e.kind==EntryKind.BORROWING.name&&e.accountId==debt.id})}
    if(previous!=null&&old==null)require(s.storedPostings.any{it.eventId==previous.id&&it.accountId==debt.id&&it.confirmed&&it.deltaMinor==input.principal.minor&&it.date==input.date}&&s.storedPostings.any{it.eventId==previous.id&&it.accountId==funds.id&&it.confirmed&&it.deltaMinor==received.minor&&it.date==input.date}){"已有借入须与实际账户、日期和金额一致 / 既存の借入は実口座・日付・金額を一致させてください"}
    val event=EventEntity(bookId,previous?.id?:id(),EntryKind.BORROWING.name,debt.id,null,input.principal.minor,debt.currency,0,input.date,input.name.trim(),input.note,revision=(previous?.revision?:0)+1,createdAt=previous?.createdAt?:clock.millis())
    val postings=mutableListOf(PostingEntity(bookId,event.id+":principal",event.id,debt.id,input.principal.minor,debt.currency,input.date),PostingEntity(bookId,event.id+":received",event.id,funds.id,received.minor,funds.currency,input.date))
    val components=mutableListOf<ExpenseComponentEntity>()
    val withheldBase=input.withheldFee?.let{feeBase(s.book,it,input.withheldFeeBase)}
    if(input.withheldFee!=null&&input.withheldFee.minor>0)components+=ExpenseComponentEntity(bookId,event.id+":withheld",event.id,debt.id,input.withheldFee.minor,debt.currency,withheldBase!!.minor,input.date,true,"WITHHELD",input.withheldFee.minor,debt.currency)
    var separateBase:Money?=null
    input.separateFee?.let{fee->
        val payer=requireNotNull(s.accounts.firstOrNull{it.id==(input.separateFeeAccountId?:funds.id)&&it.active&&!AccountType.valueOf(it.type).debt}){"请选择另付费用账户 / 別払い手数料の口座を選択してください"}
        requireMoney(fee,payer.currency);separateBase=feeBase(s.book,fee,input.separateFeeBase)
        if(fee.minor>0) {
            postings+=PostingEntity(bookId,event.id+":separate",event.id,payer.id,-fee.minor,payer.currency,input.date)
            components+=ExpenseComponentEntity(bookId,event.id+":separate",event.id,payer.id,fee.minor,payer.currency,separateBase!!.minor,input.date,true,"SEPARATE",fee.minor,payer.currency)
        }
    }
    val contract=DebtContractEntity(bookId,old?.id?:id(),debt.id,input.name.trim(),input.mode.name,debt.currency,input.principal.minor,input.date,event.id,funds.id,received.minor,input.withheldFee?.minor,withheldBase?.minor,input.separateFee?.minor,input.separateFee?.let{input.separateFeeAccountId?:funds.id},separateBase?.minor,old?.createdAt?:clock.millis(),(old?.revision?:0)+1)
    val settlement=SettlementEntity(bookId,event.id,input.principal.minor,debt.currency,true,input.date,"manual",input.date,input.principal.minor,0,expectedAccountCurrency=debt.currency)
    val change=PreparedLedgerChange(previous,event,settlement,postings,components=components,contract=contract)
    financialReview(state,change,contract,input,null)
}

suspend fun LedgerRepository.previewRepayment(bookId:String,input:FinancialRepaymentInput):FinancialLedgerReview=db.withTransaction {
    val state=financialReviewState(bookId);val s=state.ledger
    val old=input.eventId?.let{requireNotNull(s.visibleEvents.firstOrNull{e->e.id==it&&e.kind in listOf(EntryKind.REPAYMENT.name,EntryKind.EXPENSE.name)}){"请选择有效的已有付款记录 / 有効な既存の支払記録を選択してください"}}
    val source=requireNotNull(s.accounts.firstOrNull{it.id==input.sourceAccountId&&(it.active||old?.accountId==it.id)&&!AccountType.valueOf(it.type).debt}){"请选择实际付款资金账户 / 実際の支払資金口座を選択してください"}
    val debt=requireNotNull(s.accounts.firstOrNull{it.id==input.debtAccountId&&AccountType.valueOf(it.type).debt}){"请选择本账本的债务账户 / この帳簿の債務口座を選択してください"}
    requireMoney(input.paid,source.currency,true);LocalDate.parse(input.date)
    listOfNotNull(input.received,input.principal,input.interest,input.fee).forEach{requireMoney(it,debt.currency)}
    input.clearingDate?.let{LocalDate.parse(it)}
    if(old!=null&&s.repayments.none{it.eventId==old.id}) {
        require(old.accountId==source.id&&s.storedPostings.any{it.eventId==old.id&&it.accountId==source.id&&it.confirmed&&it.deltaMinor== -input.paid.minor&&it.date==input.date}){"关联付款须与实际账户、日期和金额一致 / 関連する支払は実口座・日付・金額を一致させてください"}
    }
    if(old?.kind==EntryKind.EXPENSE.name) {
        require(old.accountId==source.id&&s.accountMoney(old)==input.paid&&s.settlements.firstOrNull{it.eventId==old.id}?.confirmed!=false&&s.storedPostings.any{it.eventId==old.id&&it.accountId==source.id&&it.confirmed&&it.deltaMinor== -input.paid.minor&&it.date==input.date}){"关联付款须与实际账户、日期和金额一致 / 関連する支払は実口座・日付・金額を一致させてください"}
        require(s.settlements.none{it.originalEventId==old.id}&&s.eventActivities.none{it.eventId==old.id}&&s.occurrences.none{it.eventId==old.id}&&s.treats.none{it.eventId==old.id}&&s.reserveUses.none{it.eventId==old.id}){"这笔支出有退款或活动等关联，请先处理关联 / この支出には返金・シーン等の関連があります。先に関連を整理してください"}
    }
    input.linkedTransferEventId?.let{id->require(s.transfers.any{it.eventId==id&&it.toAccountId==source.id&&s.visibleEvents.any{e->e.id==id}}){"请选择转入付款账户的本账本换汇记录 / 支払口座への振替・両替記録を選択してください"}}
    if(input.platformConfirmed) {
        require(input.received!=null&&input.principal!=null&&input.interest!=null&&input.fee!=null&&input.clearingDate!=null){"确认清偿需填写实收、本金、利息、费用和日期，已知为零请明确填写0 / 返済確定には受取額・元金・利息・手数料・日付を入力し、ゼロは0を明示してください"}
        require(addExact(input.principal.minor,addExact(input.interest.minor,input.fee.minor))==input.received.minor){"清偿组成与平台实收合计不符 / 返済内訳と返済先の受取額が一致しません"}
    }
    input.sourceFee?.let{requireMoney(it,source.currency);require(it.minor<=input.paid.minor)
        if(input.received!=null&&source.currency==debt.currency)require(addExact(input.received.minor,it.minor)==input.paid.minor){"实扣应等于实收加已含的付款端费用 / 実出金額は受取額と出金側手数料の合計にしてください"}}
    val event=EventEntity(bookId,old?.id?:id(),EntryKind.REPAYMENT.name,source.id,null,input.paid.minor,source.currency,0,input.date,old?.merchant.orEmpty(),input.note,revision=(old?.revision?:0)+1,createdAt=old?.createdAt?:clock.millis())
    val posts=mutableListOf(PostingEntity(bookId,event.id+":paid",event.id,source.id,-input.paid.minor,source.currency,input.date))
    val components=mutableListOf<ExpenseComponentEntity>()
    var platformFeeBase:Money?=null
    if(input.platformConfirmed) {
        if(input.principal!!.minor>0)posts+=PostingEntity(bookId,event.id+":cleared",event.id,debt.id,-input.principal.minor,debt.currency,input.clearingDate!!)
        val fees=Money(Currency.valueOf(debt.currency),addExact(input.interest!!.minor,input.fee!!.minor))
        platformFeeBase=feeBase(s.book,fees,input.feeBase)
        if(fees.minor>0)components+=ExpenseComponentEntity(bookId,event.id+":platform-fees",event.id,debt.id,fees.minor,debt.currency,platformFeeBase.minor,input.date,true,"INCLUDED_PAYMENT",fees.minor,debt.currency)
    }
    val sourceFeeBase=input.sourceFee?.let{feeBase(s.book,it,input.sourceFeeBase)}
    if(input.sourceFee!=null&&input.sourceFee.minor>0)components+=ExpenseComponentEntity(bookId,event.id+":source-fee",event.id,source.id,input.sourceFee.minor,source.currency,sourceFeeBase!!.minor,input.date,true,"INCLUDED_FROM",input.sourceFee.minor,source.currency)
    val original=s.repayments.firstOrNull{it.eventId==event.id}
    val repayment=FinancialRepaymentEntity(bookId,event.id,debt.id,input.received?.minor,input.principal?.minor,input.interest?.minor,input.fee?.minor,platformFeeBase?.minor,input.clearingDate,input.platformConfirmed,input.type.name,input.linkedTransferEventId,original?.originalInputJson?:json.encodeToString(input),input.sourceFee?.minor,sourceFeeBase?.minor)
    val allocations=prepareRepaymentAllocations(state,event.id,debt,input)
    val prior=s.settlements.firstOrNull{it.eventId==event.id}
    val settlement=SettlementEntity(bookId,event.id,input.paid.minor,source.currency,true,input.date,"manual",input.date,prior?.expectedAccountMinor?:input.paid.minor,0,expectedAccountCurrency=source.currency)
    val change=PreparedLedgerChange(old,event,settlement,posts,components=components,repayment=repayment,repaymentAllocations=allocations)
    financialReview(state,change,null,null,input)
}

private fun LedgerRepository.prepareRepaymentAllocations(state:FinancialReviewState,eventId:String,debt:AccountEntity,input:FinancialRepaymentInput):List<FinancialRepaymentAllocationEntity> {
    val s=state.ledger;val f=state.finance
    require(input.allocations.map{it.billId to it.contractId}.distinct().size==input.allocations.size){"同一笔付款不能重复关联相同义务 / 同じ支払を同じ債務に重複関連付けできません"}
    val allocations=input.allocations.mapIndexed{i,a->
        require((a.billId!=null||a.contractId!=null)&&listOfNotNull(a.principalMinor,a.interestMinor,a.feeMinor).all{it>=0})
        if(input.platformConfirmed)require(a.principalMinor!=null&&a.interestMinor!=null&&a.feeMinor!=null&&addExact(a.principalMinor!!,addExact(a.interestMinor!!,a.feeMinor!!))>0){"请填写完整且大于0的清偿分配 / 内訳をすべて入力し、0より大きい額を充当してください"}
        val bill=a.billId?.let{id->requireNotNull(f.bills.firstOrNull{it.id==id&&it.active&&f.plans.any{p->p.id==it.planId&&p.accountId==debt.id}}){"账单不属于本债务账户 / 請求はこの債務口座のものではありません"}}
        val billContract=bill?.let{f.contractForBill(it)}
        require(a.contractId==null||billContract==null||a.contractId==billContract){"账单与所选合同不一致 / 請求と選択した契約が一致しません"}
        val contractId=a.contractId?:billContract
        val billPlan=bill?.let{b->f.plans.first{it.id==b.planId}}
        if(billPlan?.origin=="POOL"&&contractId!=null)require(f.contracts.firstOrNull{it.id==contractId}?.mode==billPlan.mode||f.poolArrangements(billPlan.id).any{change->json.decodeFromString<FinancialArrangementBaseline>(change.baselineJson).contractId==contractId}){"此合同尚未并入该循环池，请先核对归属 / この契約はこの循環プールに未統合です。帰属を先に確認してください"}
        contractId?.let{id->require(f.contracts.any{it.id==id&&it.accountId==debt.id}){"合同不属于本债务账户 / 契約はこの債務口座のものではありません"}}
        if(bill!=null&&input.platformConfirmed) {
            val outstanding=s.billOutstanding(bill,input.eventId)
            require(addExact(a.principalMinor!!,addExact(a.interestMinor!!,a.feeMinor!!))<=outstanding.totalMinor&&outstanding.principalMinor?.let{a.principalMinor!!<=it}!=false&&outstanding.feeMinor?.let{addExact(a.interestMinor!!,a.feeMinor!!)<=it}!=false){"本期分配超过剩余义务，请先核对平台账单 / 当期の充当額が残額を超えています。返済先の請求を先に確認してください"}
        }
        FinancialRepaymentAllocationEntity(debt.bookId,eventId+":allocation:$i",eventId,bill?.id,contractId,a.principalMinor,a.interestMinor,a.feeMinor)
    }
    require(allocations.map{it.billId to it.contractId}.distinct().size==allocations.size){"同一义务被重复分配 / 同じ債務に重複充当されています"}
    if(!input.platformConfirmed)return allocations
    allocations.filter{it.billId!=null}.groupBy{it.billId}.forEach{(id,rows)->
        val bill=f.bills.first{it.id==id};val outstanding=s.billOutstanding(bill,input.eventId)
        val principal=rows.fold(0L){sum,a->addExact(sum,a.principalMinor!!)}
        val fee=rows.fold(0L){sum,a->addExact(sum,addExact(a.interestMinor!!,a.feeMinor!!))}
        require(addExact(principal,fee)<=outstanding.totalMinor&&outstanding.principalMinor?.let{principal<=it}!=false&&outstanding.feeMinor?.let{fee<=it}!=false){"该账期累计分配超过剩余义务 / この回の合計充当額が残額を超えています"}
    }
    fun total(value:(FinancialRepaymentAllocationEntity)->Long)=allocations.fold(0L){sum,a->addExact(sum,value(a))}
    require(total{it.principalMinor!!}<=input.principal!!.minor&&total{it.interestMinor!!}<=input.interest!!.minor&&total{it.feeMinor!!}<=input.fee!!.minor){"清偿分配超过实际确认金额 / 充当額が実際の確定額を超えています"}
    allocations.filter{it.contractId!=null}.groupBy{it.contractId}.forEach{(id,values)->
        val contract=f.contracts.first{it.id==id}
        require(values.fold(0L){sum,a->addExact(sum,a.principalMinor!!)}<=s.contractRemaining(contract,input.eventId)){"合同分配超过剩余本金 / 契約への充当額が残元金を超えています"}
    }
    allocations.filter{row->row.contractId!=null&&row.billId!=null&&f.bills.any{b->b.id==row.billId&&f.plans.any{p->p.id==b.planId&&p.origin=="POOL"&&f.contracts.first{it.id==row.contractId}.mode!=p.mode}}}.groupBy{row->f.bills.first{it.id==row.billId}.planId to row.contractId!!}.forEach{(key,rows)->
        val (poolId,contractId)=key
        val transferred=f.poolArrangements(poolId).filter{change->json.decodeFromString<FinancialArrangementBaseline>(change.baselineJson).contractId==contractId}.fold(0L){n,change->addExact(n,change.principalMinor)}
        val paid=s.appliedRepaymentAllocations(input.eventId).filter{it.contractId==contractId&&f.bills.any{b->b.id==it.billId&&b.planId==poolId}}.fold(0L){n,row->addExact(n,row.principalMinor!!)}
        require(rows.sumOf{it.principalMinor!!}<=subtractExact(transferred,paid)){"本次分配超过此合同已并入池的剩余本金 / 充当額がこの契約からプールへ統合済みの残元金を超えています"}
    }
    return allocations
}

suspend fun LedgerRepository.commitFinancialLedger(review:FinancialLedgerReview):String?=db.withTransaction {
    if(financialReviewState(review.before.ledger.book.id)!=review.before)return@withTransaction null
    for(impact in review.planImpacts)archiveExistingPlan(impact.original,impact.originalBills,predictionFacts(review.before.ledger,review.before.finance,impact.original.id,impact.original.sourceEventId,LocalDate.now(clock).toString()))
    val saved=review.change?.let{writeChange(it)}?:run{db.financialDao().putContract(review.contract!!);review.contract.id}
    writeFinancialPlanImpacts(review,review.change?.event?.id)
    saved
}

suspend fun LedgerRepository.linkFinancialContractPlan(bookId:String,contractId:String,planId:String)=db.withTransaction {
    val state=financialReviewState(bookId)
    val contract=requireNotNull(state.finance.contracts.firstOrNull{it.id==contractId})
    val plan=requireNotNull(state.finance.plans.firstOrNull{it.id==planId&&it.accountId==contract.accountId})
    require(plan.origin!="POOL"){"账户池不绑定单一合同，请在每次还款分配中选择合同 / 口座プールは単一契約に結び付けず、返済ごとに契約を指定してください"}
    require(plan.sourceEventId==contract.fundingEventId&&plan.mode==contract.mode){"计划来源与合同不一致 / プランの対象と契約が一致しません"}
    require(state.finance.contractPlans.none{link->link.contractId==contract.id&&link.planId!=plan.id&&state.finance.plans.any{it.id==link.planId&&it.active&&state.finance.arrangements.none{a->a.originalPlanId==it.id}}}){"此合同已有当前计划，请在原计划中重算或记录已办理变更 / この契約には適用中のプランがあります。元のプランを再計算するか手続き済み変更を記録してください"}
    val previous=state.finance.contractPlans.firstOrNull{it.planId==plan.id}
    require(previous==null||previous.contractId==contract.id||state.ledger.appliedRepaymentAllocations().none{a->state.finance.bills.any{it.planId==plan.id&&it.id==a.billId}}){"此计划已有清偿事实，不能改换合同 / このプランには返済記録があり、契約を変更できません"}
    if(previous?.contractId==contract.id)return@withTransaction
    archiveExistingPlan(plan,state.finance.bills.filter{it.planId==plan.id},predictionFacts(state.ledger,state.finance,plan.id,plan.sourceEventId,LocalDate.now(clock).toString()))
    db.financialDao().putContractPlan(FinancialContractPlanEntity(bookId,plan.id,contract.id))
    val next=plan.copy(revision=Math.addExact(plan.revision,1))
    db.financialDao().updatePlan(next)
    val linked=financialReviewState(bookId)
    archiveFinancialPlan(next,linked.finance.bills.filter{it.planId==plan.id},"CONTRACT_LINKED",facts=predictionFacts(linked.ledger,linked.finance,plan.id,plan.sourceEventId,LocalDate.now(clock).toString()))
}
