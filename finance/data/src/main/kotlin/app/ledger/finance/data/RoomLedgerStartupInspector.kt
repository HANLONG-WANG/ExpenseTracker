package app.ledger.finance.data

import app.ledger.core.database.DatabaseIntegrityAudit
import app.ledger.core.database.LedgerDatabase
import app.ledger.core.security.LedgerStartupInspector
import app.ledger.core.security.MaintenanceReason
import app.ledger.core.security.RecoveryDiagnosticCode
import app.ledger.core.security.StartupInspection

/** Synchronous startup gate over P08 projections and the P07 authority database. */
public class RoomLedgerStartupInspector : LedgerStartupInspector {
    private val projections = RoomProjectionEngine()

    override fun inspect(database: LedgerDatabase): StartupInspection = try {
        database.readLedger { connection ->
            val book = connection.queryOne(
                "SELECT local_revision, valuation_revision, state FROM book WHERE id = 1",
            ) { cursor -> Triple(cursor.getLong(0), cursor.getLong(1), cursor.getInt(2)) }
                ?: return@readLedger StartupInspection.RecoveryRequired(RecoveryDiagnosticCode.SCHEMA_INVALID)
            val integrity = DatabaseIntegrityAudit.run(connection)
            if (!integrity.isValid || book.third == 2) {
                return@readLedger StartupInspection.RecoveryRequired(RecoveryDiagnosticCode.SCHEMA_INVALID)
            }
            val invalidSubtype = connection.queryOne(
                "SELECT COUNT(*) FROM current_transaction_subtype_audit WHERE has_matching_detail = 0",
            ) { it.getLong(0) } ?: 0L
            if (invalidSubtype > 0L) {
                return@readLedger StartupInspection.RecoveryRequired(RecoveryDiagnosticCode.SCHEMA_INVALID)
            }
            if (book.third == 1) return@readLedger StartupInspection.Maintenance(MaintenanceReason.CONTROLLED_MAINTENANCE)
            if (projections.mismatchedFamilies(connection, book.first, book.second).isNotEmpty()) {
                return@readLedger StartupInspection.Maintenance(MaintenanceReason.PROJECTION_REBUILD)
            }
            val unfinished = connection.queryOne(
                "SELECT COUNT(*) FROM background_operation WHERE state NOT IN (8,9,10)",
            ) { it.getLong(0) } ?: 0L
            if (unfinished > 0L) {
                StartupInspection.Maintenance(MaintenanceReason.UNFINISHED_OPERATION)
            } else {
                StartupInspection.Ready
            }
        }
    } catch (_: Exception) {
        StartupInspection.RecoveryRequired(RecoveryDiagnosticCode.DATABASE_UNAVAILABLE)
    }
}
