package app.ledger.analytics.domain

import app.ledger.core.common.StableId
import app.ledger.finance.domain.LocalRevision
import app.ledger.finance.domain.TransactionKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

class ConsumptionMapContractTest {
    @Test
    fun defaultsUseCurrentMonthConsumptionAndHideTransferRepaymentAndLoanKinds() {
        val query = ConsumptionMapQuery(
            ReportPeriod(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)),
            MapViewport.World,
        )
        assertEquals(ConsumptionMapMode.CONSUMPTION, query.mode)
        assertTrue(query.filters.hidesTransfersRepaymentsAndLoans)
        assertFalse(TransactionKind.TRANSFER in query.filters.includedKinds)
        assertTrue(TransactionKind.EXPENSE in query.filters.includedKinds)
        assertEquals(TransactionKind.entries.toSet(), query.filters.withSpecialTransactions(true).includedKinds)
    }

    @Test
    fun viewportSupportsDateLineAndRejectsInvalidCoordinatesOrUnboundedFilterValues() {
        assertTrue(MapViewport(-10, 10, 1_700_000_000, -1_700_000_000, 4).crossesDateLine)
        assertThrows(IllegalArgumentException::class.java) { MapViewport(-900_000_001, 0, 0, 0, 1) }
        assertThrows(IllegalArgumentException::class.java) {
            ConsumptionMapFilters(
                includedKinds = TransactionKind.entries.toSet(),
                accountIds = (0..64).map { app.ledger.finance.domain.UserAccountId(id(it.toLong())) }.toSet(),
            )
        }
    }

    @Test
    fun renderingWeightCannotOverflowAndResultPointPopulationIsBounded() {
        val point = ConsumptionMapPoint(id(1), ConsumptionMapGroupKind.TRANSACTION, null, 0, 0, Long.MIN_VALUE, 2)
        assertEquals(Long.MAX_VALUE, point.renderWeight(ConsumptionMapWeight.BASE_AMOUNT))
        assertEquals(2L, point.renderWeight(ConsumptionMapWeight.TRANSACTION_COUNT))
        assertThrows(IllegalArgumentException::class.java) {
            ConsumptionMapResult(
                ConsumptionMapQuery(
                    ReportPeriod(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)),
                    MapViewport.World,
                ),
                app.ledger.core.money.CurrencyCode.parse("JPY").let { (it as app.ledger.core.common.DomainResult.Success).value },
                List(ConsumptionMapResult.MAX_RENDERED_POINTS + 1) { index -> point.copy(id = id(index.toLong() + 10)) },
                0,
                0,
                LocalRevision.of(1).let { (it as app.ledger.core.common.DomainResult.Success).value },
                true,
            )
        }
    }

    private fun id(seed: Long): StableId = StableId.fromUuid(UUID(0L, seed))
}
