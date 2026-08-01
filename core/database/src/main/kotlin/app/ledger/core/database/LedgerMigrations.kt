package app.ledger.core.database

import androidx.room.migration.Migration

enum class MigrationPhase {
    EXPAND,
    BACKFILL,
    SWITCH,
    CONTRACT,
}

data class MigrationStep(
    val phase: MigrationPhase,
    val description: String,
) {
    init {
        require(description.isNotBlank())
    }
}

data class MigrationContract(
    val fromVersion: Int,
    val toVersion: Int,
    val steps: List<MigrationStep>,
) {
    init {
        require(fromVersion > 0 && toVersion == fromVersion + 1)
        require(steps.isNotEmpty())
        val ordinals = steps.map { it.phase.ordinal }
        require(ordinals == ordinals.sorted()) { "migration phases must follow Expand -> Backfill -> Switch -> Contract" }
        require(MigrationPhase.EXPAND in steps.map { it.phase })
        require(MigrationPhase.SWITCH in steps.map { it.phase })
    }
}

object LedgerMigrations {
    const val CURRENT_VERSION: Int = LedgerSchemaDefinition.VERSION

    /** v1 is the first registered primary schema, so there is no predecessor migration yet. */
    val registered: List<Migration> = emptyList()

    val contracts: List<MigrationContract> = emptyList()
}

object StagingMigrations {
    const val CURRENT_VERSION: Int = LedgerSchemaDefinition.VERSION

    /** Staging schemas are independently versioned and disposable, but never opened through destructive fallback. */
    val registered: List<Migration> = emptyList()

    val contracts: List<MigrationContract> = emptyList()
}
