package app.ledger.finance.data

import app.ledger.finance.application.FinanceDataError
import org.junit.Assert.assertEquals
import org.junit.Test

class SqlSupportTest {
    @Test
    fun sqliteNumericRangeCauseIsPreservedAcrossWrapperExceptions() {
        val wrappedOverflow = IllegalStateException("query failed", IllegalArgumentException("integer overflow"))

        assertEquals(FinanceDataError.NumericRangeExceeded, wrappedOverflow.toFinanceDatabaseError())
        assertEquals(
            FinanceDataError.DatabaseUnavailable,
            IllegalStateException("datatype mismatch").toFinanceDatabaseError(),
        )
    }
}
