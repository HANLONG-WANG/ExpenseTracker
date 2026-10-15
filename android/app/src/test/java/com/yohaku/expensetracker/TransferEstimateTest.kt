package com.yohaku.expensetracker

import com.yohaku.expensetracker.domain.ledger.estimateTransfer
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
}
