package app.ledger.finance.data

import app.ledger.finance.application.FinanceDataError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

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
