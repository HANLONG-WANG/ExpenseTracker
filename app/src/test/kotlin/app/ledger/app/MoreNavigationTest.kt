package app.ledger.app

import app.ledger.core.navigation.LedgerRouteContract
import app.ledger.core.navigation.ScreenId
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MoreNavigationTest {
    @Test
    fun creditEntryUsesArgumentFreeUnifiedLiabilityRoute() {
        assertEquals("LIA-001", MORE_CREDIT_DESTINATION)
        assertDoesNotThrow {
            LedgerRouteContract.destination(ScreenId(MORE_CREDIT_DESTINATION))
        }
    }
}
