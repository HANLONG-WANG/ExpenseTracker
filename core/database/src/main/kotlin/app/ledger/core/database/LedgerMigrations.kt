package app.ledger.core.database

import android.content.Context
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
    const val CURRENT_VERSION: Int = LedgerSchemaDefinition.PRIMARY_VERSION

    fun registered(context: Context): List<Migration> = listOf(
        object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                LedgerSchemaDefinition.migratePrimaryV1ToV2(context, db)
            }
        },
    )

    val contracts: List<MigrationContract> = listOf(
        MigrationContract(
            1,
            2,
            listOf(
                MigrationStep(MigrationPhase.EXPAND, "add normalized custom report dashboard and anomaly rule tables"),
                MigrationStep(MigrationPhase.SWITCH, "register primary logical schema v2 contract"),
            ),
        ),
    )
}

object StagingMigrations {
    const val CURRENT_VERSION: Int = LedgerSchemaDefinition.STAGING_VERSION

    /** Staging schemas are independently versioned and disposable, but never opened through destructive fallback. */
    val registered: List<Migration> = emptyList()

    val contracts: List<MigrationContract> = emptyList()
}
