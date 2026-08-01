package app.ledger.core.database

import androidx.sqlite.db.SupportSQLiteDatabase

enum class WalCheckpointMode {
    PASSIVE,
    RESTART,
    TRUNCATE,
}

data class WalCheckpointResult(
    val busyConnections: Long,
    val walFrames: Long,
    val checkpointedFrames: Long,
)

object DatabaseMaintenance {
    fun checkpoint(database: SupportSQLiteDatabase, mode: WalCheckpointMode): WalCheckpointResult = database.query("PRAGMA wal_checkpoint(${mode.name})").use { cursor ->
        check(cursor.moveToFirst()) { "wal_checkpoint returned no result" }
        WalCheckpointResult(
            busyConnections = cursor.getLong(0),
            walFrames = cursor.getLong(1),
            checkpointedFrames = cursor.getLong(2),
        )
    }

    fun incrementalVacuum(database: SupportSQLiteDatabase, pageLimit: Int) {
        require(pageLimit in 1..MAX_INCREMENTAL_VACUUM_PAGES)
        database.execSQL("PRAGMA incremental_vacuum($pageLimit)")
    }

    fun optimize(database: SupportSQLiteDatabase) {
        database.execSQL("PRAGMA optimize")
    }

    private const val MAX_INCREMENTAL_VACUUM_PAGES = 4096
}
