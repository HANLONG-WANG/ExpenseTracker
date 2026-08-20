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
        object : Migration(PRIMARY_V1, PRIMARY_V2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                LedgerSchemaDefinition.migratePrimaryV1ToV2(context, db)
                MigrationPostValidation.validateOrThrow(context, db, PRIMARY_V2)
            }
        },
        object : Migration(PRIMARY_V2, PRIMARY_V3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                LedgerSchemaDefinition.migratePrimaryV2ToV3(context, db)
                MigrationPostValidation.validateOrThrow(context, db, PRIMARY_V3)
            }
        },
        object : Migration(PRIMARY_V3, PRIMARY_V4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                LedgerSchemaDefinition.migratePrimaryV3ToV4(context, db)
                MigrationPostValidation.validateOrThrow(context, db, PRIMARY_V4)
            }
        },
        object : Migration(PRIMARY_V4, PRIMARY_V5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                LedgerSchemaDefinition.migratePrimaryV4ToV5(context, db)
            }
        },
    )

    val contracts: List<MigrationContract> = listOf(
        MigrationContract(
            PRIMARY_V1,
            PRIMARY_V2,
            listOf(
                MigrationStep(MigrationPhase.EXPAND, "add normalized custom report dashboard and anomaly rule tables"),
                MigrationStep(MigrationPhase.SWITCH, "register primary logical schema v2 contract"),
            ),
        ),
        MigrationContract(
            PRIMARY_V2,
            PRIMARY_V3,
            listOf(
                MigrationStep(MigrationPhase.EXPAND, "add complete widget snapshot fields without changing financial facts"),
                MigrationStep(MigrationPhase.BACKFILL, "invalidate old incomplete widget rows for deterministic projection rebuild"),
                MigrationStep(MigrationPhase.SWITCH, "register primary logical schema v3 contract"),
            ),
        ),
        MigrationContract(
            PRIMARY_V3,
            PRIMARY_V4,
            listOf(
                MigrationStep(MigrationPhase.EXPAND, "add constant-size authoritative projection family generations"),
                MigrationStep(MigrationPhase.BACKFILL, "seed every family from the existing book revisions"),
                MigrationStep(MigrationPhase.SWITCH, "validate family generations instead of rewriting unchanged projection rows"),
            ),
        ),
        MigrationContract(
            PRIMARY_V4,
            PRIMARY_V5,
            listOf(
                MigrationStep(MigrationPhase.EXPAND, "add posting valuation provenance, unified budget effect lines and current-entity version metadata"),
                MigrationStep(MigrationPhase.BACKFILL, "derive valuation provenance and current content hashes from immutable evidence"),
                MigrationStep(MigrationPhase.SWITCH, "replace maintenance-gated fact deletion with unconditional append-only triggers"),
                MigrationStep(MigrationPhase.CONTRACT, "remove the obsolete immutable-fact purge guard"),
            ),
        ),
    )

    private const val PRIMARY_V1: Int = 1
    private const val PRIMARY_V2: Int = 2
    private const val PRIMARY_V3: Int = 3
    private const val PRIMARY_V4: Int = 4
    private const val PRIMARY_V5: Int = 5
}

object StagingMigrations {
    const val CURRENT_VERSION: Int = LedgerSchemaDefinition.STAGING_VERSION

    /** Staging schemas are independently versioned and disposable, but never opened through destructive fallback. */
    val registered: List<Migration> = emptyList()

    val contracts: List<MigrationContract> = emptyList()
}
