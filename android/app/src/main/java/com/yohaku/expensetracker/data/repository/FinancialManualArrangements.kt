package com.yohaku.expensetracker.data.repository

import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.domain.model.AccountType
import java.lang.Math.addExact
import java.lang.Math.subtractExact

/** Platform amounts remain known bills, independent of any supported interest algorithm. */
internal fun LedgerRepository.manualArrangementBills(s:BookSnapshot,input:FinancialArrangementInput,target:FinancialPlanEntity):List<FinancialBillEntity>? {
    val rows=input.manualPeriods?:return null
    require(rows.isNotEmpty()&&rows.map{it.date}==input.terms.paymentDates){"手动期次与付款日期不符 / 手動の各回と支払日が一致しません"}
    require(rows.zipWithNext().all{(a,b)->a.date<b.date}){"请按还款日期顺序填写 / 支払日順に入力してください"}
    rows.forEach{row->
        validateKnownBill(row)
        row.paymentAccountId?.let{id->require(s.accounts.any{it.id==id&&it.active&&!AccountType.valueOf(it.type).debt})}
    }
    val known=rows.mapNotNull{it.principalMinor}.fold(0L,::addExact)
    require(known<=target.principalMinor!!){"逐期本金超过转入本金 / 各回の元金が移す元金を超えています"}
    require(!input.manualComplete||rows.all{it.principalMinor!=null}&&known==target.principalMinor){"完整新方案需要逐期本金合计等于转入本金 / 全期間の新プランは各回元金の合計を移す元金と一致させてください"}
    return rows.mapIndexed{index,p->
        val extra=if(index==0)input.changeFeeMinor!! else 0L
        FinancialBillEntity(s.book.id,id(),target.id,index+1,p.paymentAccountId?:input.paymentAccountId,null,null,null,null,null,null,
            p.date,p.principalMinor,p.feeMinor?.let{addExact(it,extra)},addExact(p.totalMinor,extra),clock.millis())
    }
}

internal fun manualArrangementRemaining(input:FinancialArrangementInput,principal:Long):Long? {
    val rows=input.manualPeriods?:return null
    if(rows.any{it.principalMinor==null})return null
    return subtractExact(principal,rows.fold(0L){n,r->addExact(n,r.principalMinor!!)})
}
