package app.ledger.feature.record

import app.ledger.core.navigation.LedgerRouteContract
import app.ledger.core.navigation.ScreenId
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OtherTransactionNavigationTest {
    @Test
    fun cardsTargetRegisteredArgumentFreeEntryScreens() {
        assertEquals(
            listOf("REC-013", "REC-014", "REC-015", "REC-017", "REC-020", "REC-021"),
            OTHER_TRANSACTION_TARGETS.map { it.screenId },
        )

        OTHER_TRANSACTION_TARGETS.forEach { target ->
            assertDoesNotThrow { LedgerRouteContract.destination(ScreenId(target.screenId)) }
        }
    }
}
