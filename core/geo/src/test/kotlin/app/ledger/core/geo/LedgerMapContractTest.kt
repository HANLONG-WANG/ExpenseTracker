package app.ledger.core.geo

import app.ledger.core.common.StableId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class LedgerMapContractTest {
    @Test
    fun mapPointUsesStableIdentityAndCheckedFixedPointCoordinates() {
        val point = LedgerMapPoint(StableId.fromUuid(UUID(0, 1)), 900_000_000, -1_800_000_000, 0)
        assertEquals(900_000_000, point.latitudeE7)
        assertThrows(IllegalArgumentException::class.java) {
            LedgerMapPoint(StableId.fromUuid(UUID(0, 2)), 900_000_001, 0, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LedgerMapPoint(StableId.fromUuid(UUID(0, 3)), 0, 0, -1)
        }
    }

    @Test
    fun styleConfigurationRejectsInsecureRemoteStylesAndMissingAttribution() {
        assertThrows(IllegalArgumentException::class.java) { LedgerMapStyleSource.Uri("http://tiles.example/style") }
        assertThrows(IllegalArgumentException::class.java) {
            LedgerMapStyleConfiguration(
                LedgerMapStyleSource.Json("{}"),
                LedgerMapStyleSource.Json("{}"),
                "",
            )
        }
    }

    @Test
    fun viewportIsFixedPointBoundedAndAllowsDateLineCrossing() {
        assertEquals(8, LedgerMapViewport(-10, 10, 1_700_000_000, -1_700_000_000, 8).zoomBucket)
        assertThrows(IllegalArgumentException::class.java) {
            LedgerMapViewport(-900_000_001, 10, 0, 0, 8)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LedgerMapViewport(-10, 10, 0, 0, 23)
        }
    }
}
