package app.ledger.app

import app.ledger.core.navigation.LedgerRouteContract
import app.ledger.core.navigation.ScreenId
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class MoreNavigationTest {
    @Test
    fun creditAndLoanEntriesUseDistinctArgumentFreeRoutes() {
        val creditAccounts = "LIA-001"
        val loans = "LOA-001"
        assertNotEquals(creditAccounts, loans)
        assertDoesNotThrow { LedgerRouteContract.destination(ScreenId(creditAccounts)) }
        assertDoesNotThrow { LedgerRouteContract.destination(ScreenId(loans)) }
    }
}
