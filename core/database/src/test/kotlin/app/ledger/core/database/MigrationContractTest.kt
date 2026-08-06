package app.ledger.core.database

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MigrationContractTest {
    @Test
    fun versionTwoRegistersEveryPredecessorWithoutDestructiveFallback() {
        assertEquals(2, LedgerMigrations.CURRENT_VERSION)
        assertEquals(listOf(1 to 2), LedgerMigrations.contracts.map { it.fromVersion to it.toVersion })
        assertEquals(listOf(MigrationPhase.EXPAND, MigrationPhase.SWITCH), LedgerMigrations.contracts.single().steps.map(MigrationStep::phase))
        assertEquals(1, StagingMigrations.CURRENT_VERSION)
        assertTrue(StagingMigrations.registered.isEmpty())
        assertTrue(StagingMigrations.contracts.isEmpty())
    }

    @Test
    fun migrationAcceptsExpandBackfillSwitchContractOrder() {
        val contract = MigrationContract(
            fromVersion = 1,
            toVersion = 2,
            steps = listOf(
                MigrationStep(MigrationPhase.EXPAND, "add nullable replacement column"),
                MigrationStep(MigrationPhase.BACKFILL, "backfill in bounded chunks"),
                MigrationStep(MigrationPhase.SWITCH, "switch verified reads"),
                MigrationStep(MigrationPhase.CONTRACT, "retain old column until a later version"),
            ),
        )
        assertEquals(4, contract.steps.size)
    }

    @Test
    fun futureMigrationRejectsOutOfOrderOrNonAdjacentVersions() {
        assertThrows(IllegalArgumentException::class.java) {
            MigrationContract(
                fromVersion = 1,
                toVersion = 3,
                steps = listOf(
                    MigrationStep(MigrationPhase.EXPAND, "expand"),
                    MigrationStep(MigrationPhase.SWITCH, "switch"),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            MigrationContract(
                fromVersion = 1,
                toVersion = 2,
                steps = listOf(
                    MigrationStep(MigrationPhase.SWITCH, "switch early"),
                    MigrationStep(MigrationPhase.EXPAND, "expand late"),
                ),
            )
        }
    }

    @Test
    fun immutableInventoryIncludesEveryAccountingFactFamily() {
        assertTrue(
            setOf(
                "journal_entry",
                "posting",
                "economic_effect",
                "budget_effect",
                "project_effect",
                "goal_effect",
                "statement_effect",
                "loan_effect",
                "settlement_effect",
            ).all(LedgerSchemaDefinition.immutableTables::contains),
        )
    }
}
