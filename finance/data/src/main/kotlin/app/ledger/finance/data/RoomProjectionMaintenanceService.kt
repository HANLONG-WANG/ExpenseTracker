package app.ledger.finance.data

import android.database.sqlite.SQLiteFullException
import app.ledger.core.common.DomainResult
import app.ledger.core.database.DatabaseIntegrityAudit
import app.ledger.core.database.LedgerDatabase
import app.ledger.core.database.LedgerMigrations
import app.ledger.finance.application.FinanceDataError
import app.ledger.finance.application.ProjectionAuditResult
import app.ledger.finance.application.ProjectionMaintenancePort
import app.ledger.finance.application.ProjectionVersion
import app.ledger.finance.application.StartupDisposition
import app.ledger.finance.application.StartupIntegrityResult
import app.ledger.finance.domain.LocalRevision

class RoomProjectionMaintenanceService(
    private val database: LedgerDatabase,
) : ProjectionMaintenancePort {
    private val projections = RoomProjectionEngine()

    override suspend fun audit(): DomainResult<ProjectionAuditResult> = protect {
        database.inLedgerTransaction { connection ->
            val version = version(connection)
            val liveHash = projections.canonicalHash(connection)
            val mismatches = projections.mismatchedFamilies(
                connection,
                version.localRevision.value,
                version.valuationRevision.value,
            )
            connection.execSQL("SAVEPOINT p08_projection_audit")
            val rebuiltHash = try {
                projections.rebuildAll(
                    connection,
                    version.localRevision.value,
                    version.valuationRevision.value,
                )
                projections.canonicalHash(connection)
            } finally {
                connection.execSQL("ROLLBACK TO SAVEPOINT p08_projection_audit")
                connection.execSQL("RELEASE SAVEPOINT p08_projection_audit")
            }
            val integrity = DatabaseIntegrityAudit.run(connection)
            if (!integrity.isValid) abort(FinanceDataError.CorruptData)
            DomainResult.Success(
                ProjectionAuditResult(
                    liveHash = liveHash,
                    rebuiltHash = rebuiltHash,
                    version = version,
                    mismatchedFamilies = mismatches,
                ),
            )
        }
    }

    override suspend fun rebuild(): DomainResult<ProjectionAuditResult> = protect {
        database.inLedgerTransaction { connection ->
            val version = version(connection)
            connection.execSQL("UPDATE book SET state = 1 WHERE id = 1")
            projections.rebuildAll(
                connection,
                version.localRevision.value,
                version.valuationRevision.value,
            )
            val hash = projections.canonicalHash(connection)
            val mismatches = projections.mismatchedFamilies(
                connection,
                version.localRevision.value,
                version.valuationRevision.value,
            )
            if (mismatches.isNotEmpty()) abort(FinanceDataError.ProjectionMismatch)
            val integrity = DatabaseIntegrityAudit.run(connection)
            if (!integrity.isValid) abort(FinanceDataError.CorruptData)
            connection.execSQL("UPDATE book SET state = 0 WHERE id = 1 AND state = 1")
            DomainResult.Success(ProjectionAuditResult(hash, hash, version, emptySet()))
        }
    }

    override suspend fun startupCheck(): DomainResult<StartupIntegrityResult> = protect {
        database.readLedger { connection ->
            val book = connection.queryOne(
                "SELECT local_revision, valuation_revision, state FROM book WHERE id = 1",
            ) { cursor ->
                Triple(
                    cursor.getLong(cursor.getColumnIndexOrThrow("local_revision")),
                    cursor.getLong(cursor.getColumnIndexOrThrow("valuation_revision")),
                    cursor.getInt(cursor.getColumnIndexOrThrow("state")),
                )
            } ?: return@readLedger DomainResult.Success(
                StartupIntegrityResult(StartupDisposition.RECOVERY_REQUIRED, setOf("BOOK_MISSING")),
            )
            val reasons = buildSet {
                if (book.third == 1) add("BOOK_MAINTENANCE")
                if (book.third == 2) add("BOOK_RECOVERY_REQUIRED")
                if (
                    projections.mismatchedFamiliesAtStartup(connection, book.first, book.second).isNotEmpty()
                ) {
                    add("PROJECTION_VERSION_MISMATCH")
                }
                val unfinished = connection.queryOne(
                    "SELECT EXISTS(SELECT 1 FROM background_operation " +
                        "WHERE type IN (?,?) AND state IN (?,?) LIMIT 1)",
                    arrayOf(
                        RESTORE_REPLACE_OPERATION_TYPE,
                        RESTORE_MERGE_OPERATION_TYPE,
                        COMMITTING_OPERATION_STATE,
                        ROLLING_BACK_OPERATION_STATE,
                    ),
                ) { it.getInt(0) == 1 } ?: false
                if (unfinished) add("UNFINISHED_OPERATION")
                val registry = connection.queryOne(
                    "SELECT COUNT(*) FROM _room_schema_registry WHERE id = 1 AND logicalSchemaVersion = ?",
                    arrayOf(LedgerMigrations.CURRENT_VERSION),
                ) { it.getLong(0) } ?: 0L
                if (registry != 1L) add("SCHEMA_CONTRACT_MISSING")
            }
            val disposition = when {
                "BOOK_RECOVERY_REQUIRED" in reasons || "SCHEMA_CONTRACT_MISSING" in reasons ->
                    StartupDisposition.RECOVERY_REQUIRED
                reasons.isNotEmpty() -> StartupDisposition.MAINTENANCE_REQUIRED
                else -> StartupDisposition.READY
            }
            DomainResult.Success(StartupIntegrityResult(disposition, reasons))
        }
    }

    override suspend fun enterMaintenance(reasonCode: String): DomainResult<Unit> {
        if (!REASON_CODE.matches(reasonCode)) return DomainResult.Failure(FinanceDataError.CorruptData)
        return protect {
            database.inLedgerTransaction { connection ->
                val updated = connection.compileStatement(
                    "UPDATE book SET state = 1 WHERE id = 1 AND state = 0",
                ).executeUpdateDelete()
                if (updated != 1) abort(FinanceDataError.MaintenanceRequired)
                DomainResult.Success(Unit)
            }
        }
    }

    private fun version(connection: androidx.sqlite.db.SupportSQLiteDatabase): ProjectionVersion = connection.queryOne(
        "SELECT local_revision, valuation_revision FROM book WHERE id = 1",
    ) { cursor ->
        ProjectionVersion(
            LocalRevision.of(cursor.getLong(0)).valueOrAbort(),
            LocalRevision.of(cursor.getLong(1)).valueOrAbort(),
        )
    } ?: abort(FinanceDataError.CorruptData)

    private inline fun <T> protect(block: () -> DomainResult<T>): DomainResult<T> = try {
        block()
    } catch (abort: FinancialPersistenceAbort) {
        DomainResult.Failure(abort.domainError)
    } catch (_: SQLiteFullException) {
        DomainResult.Failure(FinanceDataError.StorageFull)
    } catch (_: ArithmeticException) {
        DomainResult.Failure(FinanceDataError.NumericRangeExceeded)
    } catch (_: Exception) {
        DomainResult.Failure(FinanceDataError.DatabaseUnavailable)
    }

    private companion object {
        // Persisted enum ordinals from transfer/domain OperationModel. Keep the source-contract test in sync.
        const val RESTORE_REPLACE_OPERATION_TYPE = 4
        const val RESTORE_MERGE_OPERATION_TYPE = 5
        const val COMMITTING_OPERATION_STATE = 7
        const val ROLLING_BACK_OPERATION_STATE = 8
        val REASON_CODE = Regex("[A-Z][A-Z0-9_]{2,63}")
    }
}
