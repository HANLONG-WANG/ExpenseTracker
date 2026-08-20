package app.ledger.finance.data

import app.ledger.core.database.LedgerDatabase
import app.ledger.core.security.LedgerStartupInspector
import app.ledger.core.security.MaintenanceReason
import app.ledger.core.security.RecoveryDiagnosticCode
import app.ledger.core.security.StartupInspection

/** Synchronous startup gate over P08 projections and the P07 authority database. */
public class RoomLedgerStartupInspector : LedgerStartupInspector {
    override fun inspect(database: LedgerDatabase): StartupInspection = try {
        database.readLedger { connection ->
            val bookState = connection.queryOne(
                "SELECT state FROM book WHERE id = 1",
            ) { cursor -> cursor.getInt(0) }
                ?: return@readLedger StartupInspection.RecoveryRequired(RecoveryDiagnosticCode.SCHEMA_INVALID)
            if (bookState == 2) {
                return@readLedger StartupInspection.RecoveryRequired(RecoveryDiagnosticCode.SCHEMA_INVALID)
            }
            if (bookState == 1) return@readLedger StartupInspection.Maintenance(MaintenanceReason.CONTROLLED_MAINTENANCE)
            val integrity = RoomLedgerIntegrityAudit.run(connection)
            if (!integrity.authoritativeFactsValid || !integrity.standardInventoryMatches) {
                return@readLedger StartupInspection.RecoveryRequired(RecoveryDiagnosticCode.SCHEMA_INVALID)
            }
            if (!integrity.database.isValid || !integrity.projectionRebuildMatches) {
                return@readLedger StartupInspection.Maintenance(MaintenanceReason.PROJECTION_REBUILD)
            }
            val unfinished = connection.queryOne(
                // Staged imports, exports and backups do not mutate the live ledger and must not
                // gate startup. Only an interrupted atomic restore publication/rollback is an
                // exclusive maintenance condition; ordinary durable work resumes in WorkManager.
                "SELECT EXISTS(SELECT 1 FROM background_operation " +
                    "WHERE type IN (4,5) AND state IN (7,8) LIMIT 1)",
            ) { it.getInt(0) == 1 } ?: false
            if (unfinished) {
                StartupInspection.Maintenance(MaintenanceReason.UNFINISHED_OPERATION)
            } else {
                StartupInspection.Ready
            }
        }
    } catch (_: Exception) {
        StartupInspection.RecoveryRequired(RecoveryDiagnosticCode.DATABASE_UNAVAILABLE)
    }
}
