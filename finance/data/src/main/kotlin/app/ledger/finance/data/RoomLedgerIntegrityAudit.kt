package app.ledger.finance.data

import androidx.sqlite.db.SupportSQLiteDatabase
import app.ledger.core.database.DatabaseIntegrityAudit
import app.ledger.core.database.DatabaseIntegrityReport
import app.ledger.finance.application.ProjectionFamily
import app.ledger.finance.domain.PermanentInvariant

internal data class RoomLedgerIntegrityReport(
    val database: DatabaseIntegrityReport,
    val liveProjectionHash: String,
    val rebuiltProjectionHash: String,
    val mismatchedProjectionFamilies: Set<ProjectionFamily>,
    val rebuiltProjectionFamilies: Set<ProjectionFamily>,
    val standardInventoryMatches: Boolean,
) {
    val authoritativeFactsValid: Boolean
        get() = database.isValidIgnoringProjectionInvariants

    val projectionRebuildMatches: Boolean
        get() = liveProjectionHash == rebuiltProjectionHash &&
            mismatchedProjectionFamilies.isEmpty() && rebuiltProjectionFamilies.isEmpty()

    val failedInvariantIds: Set<String>
        get() = database.failedInvariantIds

    val isValid: Boolean
        get() = database.isValid && projectionRebuildMatches && standardInventoryMatches
}

/** Full maintenance/backup/restore audit, including a non-persistent projection rebuild. */
internal object RoomLedgerIntegrityAudit {
    fun run(connection: SupportSQLiteDatabase): RoomLedgerIntegrityReport {
        val databaseReport = DatabaseIntegrityAudit.run(connection)
        val version = connection.query(
            "SELECT local_revision,valuation_revision FROM book WHERE id=1",
        ).use { cursor ->
            check(cursor.moveToFirst()) { "book is missing" }
            cursor.getLong(0) to cursor.getLong(1)
        }
        val projections = RoomProjectionEngine()
        val liveHash = projections.canonicalHash(connection)
        val liveMismatches = projections.mismatchedFamilies(connection, version.first, version.second)
        connection.execSQL("SAVEPOINT full_ledger_integrity_audit")
        val rebuilt = try {
            projections.rebuildAll(connection, version.first, version.second)
            projections.canonicalHash(connection) to
                projections.mismatchedFamilies(connection, version.first, version.second)
        } finally {
            connection.execSQL("ROLLBACK TO SAVEPOINT full_ledger_integrity_audit")
            connection.execSQL("RELEASE SAVEPOINT full_ledger_integrity_audit")
        }
        return RoomLedgerIntegrityReport(
            database = databaseReport,
            liveProjectionHash = liveHash,
            rebuiltProjectionHash = rebuilt.first,
            mismatchedProjectionFamilies = liveMismatches,
            rebuiltProjectionFamilies = rebuilt.second,
            standardInventoryMatches = DatabaseIntegrityAudit.permanentInvariantIds == PermanentInvariant.ids,
        )
    }
}
