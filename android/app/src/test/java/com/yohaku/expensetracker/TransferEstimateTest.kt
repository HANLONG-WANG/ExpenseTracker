package com.yohaku.expensetracker

import com.yohaku.expensetracker.domain.ledger.*
import com.yohaku.expensetracker.domain.currency.ReferenceRateSnapshot
import com.yohaku.expensetracker.domain.model.*
import org.junit.Test
import org.junit.Assert.*

class TransferEstimateTest {
    @Test fun estimateAndReverseUseExplicitFeePositionAndNeverOverwriteActualSide() {
        val d=EntryDraft(kind=EntryKind.TRANSFER,amount="30000",manualRate="0.05",feeAmount="750",feePosition=TransferFeePosition.INCLUDED_FROM)
        val estimate=estimateTransfer(d,Currency.JPY,Currency.CNY,"TO")
        assertEquals("1462.50",estimate.toAmount)
        assertEquals("30000",estimateTransfer(estimate.copy(amount=""),Currency.JPY,Currency.CNY,"FROM").amount)
        val deducted=estimateTransfer(d.copy(feeAmount="20.00",feeCurrency=Currency.CNY,feePosition=TransferFeePosition.DEDUCTED_TO),Currency.JPY,Currency.CNY,"TO")
        assertEquals("1480.00",deducted.toAmount)
        try{estimateTransfer(d.copy(toConfirmed=true,toAmount="1400.00"),Currency.JPY,Currency.CNY,"TO");fail("Confirmed target overwritten")}catch(_:IllegalArgumentException){}
    }
    @Test fun linkedAmountIsRecomputedOrClearedWhileConfirmedAmountSurvives() {
        val draft=EntryDraft(amount="100",manualRate="160",toAmount="15000",fromConfirmed=true)
        fun update(d:EntryDraft)=refreshTransferEstimate(d,Currency.USD,Currency.JPY,"TO")
        assertEquals("16000",update(draft).toAmount)
        assertEquals("15840",update(draft.copy(feeAmount="1")).toAmount)
        assertEquals("",update(draft.copy(manualRate="")).toAmount)
        assertEquals("15000",update(draft.copy(toConfirmed=true)).toAmount)
    }
    @Test fun unfinishedFeeInputsReturnAnIssueUntilReferenceEstimationCanSucceed() {
        val quote=ReferenceRateSnapshot(Currency.JPY,Currency.CNY,"0.05","2026-10-14","2026-10-14T00:00:00Z")
        val partial=EntryDraft(kind=EntryKind.TRANSFER,amount="1",feeAmount="100",feeCurrency=Currency.JPY)
        val tooSmall=transferEstimateResult(partial,Currency.JPY,Currency.CNY,"TO",quote)
        assertTrue(tooSmall is TransferEstimateResult.Incomplete)
        assertTrue((tooSmall as TransferEstimateResult.Incomplete).message.contains("费用后"))
        assertEquals("",partial.toAmount);assertEquals("",partial.manualRate);assertTrue(partial.referenceRates.isEmpty())
        val foreignFee=partial.copy(amount="10000",feeCurrency=Currency.USD,feeAmount="1",feeAccountAmount="")
        assertTrue(transferEstimateResult(foreignFee,Currency.JPY,Currency.CNY,"TO",quote) is TransferEstimateResult.Incomplete)
        val completed=foreignFee.copy(feeAccountAmount="100")
        val ready=transferEstimateResult(completed,Currency.JPY,Currency.CNY,"TO",quote) as TransferEstimateResult.Ready
        assertEquals("495.00",ready.draft.toAmount)
        assertEquals(applyReferenceTransfer(completed,Currency.JPY,Currency.CNY,quote,"TO"),ready.draft)
        assertEquals(49500L,ready.draft.referenceExpectedToMinor)
        val reverse=transferEstimateResult(completed.copy(amount="",toAmount="495.00",feeAccountAmount=""),Currency.JPY,Currency.CNY,"FROM",quote)
        assertTrue(reverse is TransferEstimateResult.Incomplete)
    }

}
