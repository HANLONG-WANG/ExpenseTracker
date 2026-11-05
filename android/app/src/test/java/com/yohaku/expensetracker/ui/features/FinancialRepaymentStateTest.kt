package com.yohaku.expensetracker.ui.features

import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import org.junit.Assert.*
import com.yohaku.expensetracker.domain.model.Currency
import org.junit.Test

class FinancialRepaymentStateTest {
    private val plan=FinancialPlanEntity("b","plan","card","Plan","SINGLE","OPENING",null,"JPY",1000,true,0)
    private val bill=FinancialBillEntity("b","bill",plan.id,1,null,null,null,null,null,null,null,"2026-11-05",1000,0,1000,0)
    private fun row(principal:Long?=1000,fee:Long?=0,pendingPrincipal:Long?=0,pendingFee:Long?=0,pending:Long?=0,cash:Long?=1000)=FinancialStatementRow(plan,bill,
        FinancialBillOutstanding(bill,principal,fee,1000,0,0),pendingPrincipal,pendingFee,pending,cash)
    @Test fun suggestionsUseKnownUnpaidAmountsAndNeverGuessMergedFeesOrUnknowns() {
        assertEquals(RepaymentSuggestion(1000,1000,true),repaymentSuggestion(row(),false))
        assertEquals(RepaymentSuggestion(1100,1000,false),repaymentSuggestion(row(fee=100,cash=1100),false))
        assertEquals(RepaymentSuggestion(1000,null,false),repaymentSuggestion(row(principal=null,fee=null),false))
        assertEquals(RepaymentSuggestion(null,null,false),repaymentSuggestion(row().copy(bill=bill.copy(confirmedAt=null,confirmedDate=null,confirmedPrincipalMinor=null,confirmedFeeMinor=null,confirmedTotalMinor=null)),true))
    }
    @Test fun knownPendingAllocationsAreSubtractedWithoutTreatingUnknownPaymentAsZero() {
        assertEquals(RepaymentSuggestion(600,600,true),repaymentSuggestion(row(pendingPrincipal=400,pending=400,cash=600),false))
        assertEquals(RepaymentSuggestion(null,null,false),repaymentSuggestion(row(pendingPrincipal=null,pendingFee=null,pending=null,cash=null),false))
        assertNull(repaymentSuggestion(row(pendingPrincipal=1000,pending=1000,cash=0),false).paid)
    }
    @Test fun singleTargetSharesComponentsWhileExistingIndependentRowsRemainIntact() {
        val draft=RepaymentDraft(date="2026-11-01",clearingDate="2026-11-01",principal="100",interest="",fee="0",singleTarget=true,rows=listOf(AllocationDraft(billId="bill")))
        assertEquals(AllocationDraft("bill",principal="100",interest="",fee="0"),draft.withSingleAllocation().rows.single())
        val suggestion=RepaymentSuggestion(100,100,true)
        val partial=draft.copy(paid="100").withPaid("40",suggestion,Currency.JPY)
        assertEquals("",partial.rows.single().principal)
        assertEquals("",partial.interest)
        assertTrue(suggestion.differsFromPaid("40",Currency.JPY))
        assertTrue(suggestion.differsFromPaid("110",Currency.JPY))
        assertFalse(suggestion.differsFromPaid("100.0",Currency.JPY))
        assertFalse(suggestion.differsFromPaid("",Currency.JPY))
        val restored=partial.withPaid("100",suggestion,Currency.JPY)
        assertEquals(AllocationDraft("bill",principal="100",interest="0",fee="0"),restored.rows.single())
        val unknown=RepaymentSuggestion(null,null,false)
        assertFalse(unknown.differsFromPaid("40",Currency.JPY))
        assertEquals("",draft.copy(principal="").withPaid("40",unknown,Currency.JPY).principal)
        val edited=draft.copy(singleTarget=false,rows=listOf(AllocationDraft("bill",principal="40",interest="3",fee="2"),AllocationDraft(contractId="contract",principal="50")))
        assertEquals(edited,edited.withSingleAllocation())
        assertEquals(edited.copy(singleTarget=null),edited.copy(singleTarget=null).withSingleAllocation())
    }
    @Test fun dateGroupPrefillsEveryTargetWithoutGuessingUnknownComposition() {
        val context=FinancialDraftContext("REPAY","card")
        assertNotEquals(context.slot(),context.copy(paymentDate="2026-11-05").slot())
        assertNotEquals(context.copy(paymentDate="2026-11-05").slot(),context.copy(paymentDate="2026-12-05").slot())
        val base=RepaymentDraft(date="2026-11-01",clearingDate="2026-11-01")
        val known=row()
        val merged=row(fee=100,cash=1100).copy(bill=bill.copy(id="other"))
        val draft=repaymentDateDraft(base,listOf(known,merged),emptySet(),Currency.JPY,true)
        assertEquals("2100",draft.paid);assertEquals("2000",draft.principal)
        assertEquals("",draft.interest);assertEquals("",draft.fee);assertEquals(false,draft.singleTarget)
        assertEquals(listOf(AllocationDraft("bill",principal="1000",interest="0",fee="0"),AllocationDraft("other",principal="1000")),draft.rows)
        val unknown=repaymentDateDraft(base,listOf(known,row(principal=null,fee=null,cash=null)),emptySet(),Currency.JPY,true)
        assertEquals("",unknown.paid);assertEquals("",unknown.principal)
        assertEquals("",repaymentDateDraft(base,listOf(known),emptySet(),Currency.JPY,false).paid)
    }

}
