@file:Suppress(
    "CyclomaticComplexMethod",
    "LongMethod",
    "MagicNumber",
    "NestedBlockDepth",
    "ReturnCount",
    "TooGenericExceptionCaught",
    "TooManyFunctions",
)

package app.ledger.finance.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.security.DeviceLedgerKeyProvider
import app.ledger.core.security.SecurePrimaryLedgerAccess
import app.ledger.finance.application.ImportFinancialCommitResult
import app.ledger.finance.application.ImportFinancialError
import app.ledger.finance.application.ImportFinancialPage
import app.ledger.finance.application.ImportFinancialUndoRequest
import app.ledger.finance.application.ImportFinancialUndoResult
import app.ledger.finance.application.StructuredImportApplicationPort
import app.ledger.finance.application.StructuredImportCommitRequest
import app.ledger.finance.application.StructuredImportPhase
import app.ledger.finance.domain.Hash256
import java.security.MessageDigest

/** Applies every structured entity to one validated shadow ledger before one atomic exchange. */
class SecureRoomStructuredImportApplicationPort(
    context: Context,
    private val keyProvider: DeviceLedgerKeyProvider,
) : StructuredImportApplicationPort {
    private val applicationContext = context.applicationContext
    private val primaryAccess = SecurePrimaryLedgerAccess(applicationContext, keyProvider)
    private val shadowAccess = SecureShadowLedgerAccess(applicationContext, keyProvider)

    override suspend fun commit(request: StructuredImportCommitRequest): DomainResult<ImportFinancialCommitResult> = try {
        existingRows(request.bookId, request.metadata.sourceFingerprint)?.let { importedRows ->
            return DomainResult.Success(ImportFinancialCommitResult(importedRows, 1, true, replayed = true))
        }
        val snapshot = shadowAccess.createSnapshot(request.bookId, request.metadata.operationId)
        try {
            shadowAccess.writeShadow(request.bookId, request.metadata.operationId) { database -> insertHeader(database, request) }
            val applier = StructuredImportRowApplier(
                applicationContext,
                request.bookId,
                request.metadata.operationId,
                keyProvider,
                snapshot.shadowDatabaseName,
            )
            var importedRows = 0L
            var commitCount = 0
            var firstBatch = true
            suspend fun applyPhase(phase: StructuredImportPhase): DomainResult<Unit> {
                var ordinal = 0L
                while (true) {
                    val rows = when (val loaded = request.entityRows.load(phase, ordinal, PAGE_ROWS)) {
                        is DomainResult.Success -> loaded.value
                        is DomainResult.Failure -> return loaded
                    }
                    if (rows.isEmpty()) break
                    require(rows.size <= PAGE_ROWS)
                    rows.forEach { row ->
                        val commits = when (val applied = applier.apply(row)) {
                            is DomainResult.Success -> applied.value
                            is DomainResult.Failure -> return applied
                        }
                        commits.forEachIndexed { index, applied ->
                            val batch = if (firstBatch) {
                                request.metadata.batchId
                            } else {
                                derived(request.metadata.operationId, "entity:${row.sourceRowNumber}:$index:${applied.commitId}")
                            }
                            firstBatch = false
                            shadowAccess.writeShadow(request.bookId, request.metadata.operationId) { database ->
                                recordCommit(database, request.metadata.importRecordId, batch, applied.commitId, row.sourceRowNumber)
                            }
                            commitCount++
                        }
                        if (request.entityRowsContributeToTotal) importedRows++
                    }
                    ordinal += rows.size
                }
                return DomainResult.Success(Unit)
            }
            when (val before = applyPhase(StructuredImportPhase.BEFORE_TRANSACTIONS)) {
                is DomainResult.Success -> Unit
                is DomainResult.Failure -> return before
            }

            var transactionAfter = 0L
            val transactionSource = request.transactionPages
            while (transactionAfter < request.transactionRowCount) {
                val page = when (val loaded = requireNotNull(transactionSource).load(transactionAfter, PAGE_ROWS)) {
                    is DomainResult.Success -> loaded.value ?: return DomainResult.Failure(ImportFinancialError.PageSequenceInvalid)
                    is DomainResult.Failure -> return loaded
                }
                requirePage(page, transactionAfter, request.transactionRowCount)
                val sideEffect = FinancialCommitSideEffect { database, plan ->
                    insertSourceRows(database, request.metadata.importRecordId, page)
                    page.sourceRows.forEachIndexed { index, sourceRow ->
                        val batch = if (firstBatch && index == 0) {
                            request.metadata.batchId
                        } else {
                            derived(request.metadata.operationId, "transaction:${sourceRow.sourceRowNumber}:$index")
                        }
                        firstBatch = false
                        recordCommit(
                            database,
                            request.metadata.importRecordId,
                            batch,
                            plan.commit.id.value,
                            sourceRow.sourceRowNumber,
                        )
                    }
                }
                val shadowReferences = SecureRoomReferenceDataManagementPort(
                    applicationContext,
                    keyProvider,
                    snapshot.shadowDatabaseName,
                )
                val port = SecureRoomBatchEntryApplicationPort(
                    applicationContext,
                    keyProvider,
                    shadowReferences,
                    databaseName = snapshot.shadowDatabaseName,
                    additionalAfterFinancialSideEffect = sideEffect,
                )
                when (val committed = port.submit(page.submitRequest)) {
                    is DomainResult.Success -> Unit
                    is DomainResult.Failure -> return committed
                }
                transactionAfter = page.lastRowNumber
                importedRows += page.sourceRows.size
                commitCount++
            }
            when (val after = applyPhase(StructuredImportPhase.AFTER_TRANSACTIONS)) {
                is DomainResult.Success -> Unit
                is DomainResult.Failure -> return after
            }
            if (importedRows != request.metadata.totalRows || firstBatch) {
                return DomainResult.Failure(ImportFinancialError.PageSequenceInvalid)
            }
            shadowAccess.writeShadow(request.bookId, request.metadata.operationId) { database ->
                val revision = database.singleLong("SELECT local_revision FROM book WHERE id=1")
                database.execSQL(
                    "UPDATE import_record SET imported_at=?,committed_local_revision=? WHERE uid=? AND imported_at IS NULL",
                    arrayOf(request.metadata.requestedAt.toEpochMilli(), revision, request.metadata.importRecordId.bytes),
                )
            }
            if (!shadowAccess.validate(request.bookId, request.metadata.operationId).isValid) {
                return DomainResult.Failure(ImportFinancialError.ShadowValidationFailed)
            }
            val exchanged = try {
                shadowAccess.exchange(request.bookId, request.metadata.operationId, snapshot.expectedLiveHead)
            } catch (_: IllegalArgumentException) {
                return DomainResult.Failure(ImportFinancialError.LiveHeadChanged)
            }
            if (!exchanged.isValid) return DomainResult.Failure(ImportFinancialError.ShadowValidationFailed)
            DomainResult.Success(ImportFinancialCommitResult(importedRows, commitCount, true, replayed = false))
        } finally {
            shadowAccess.discard(request.metadata.operationId)
        }
    } catch (_: IllegalArgumentException) {
        DomainResult.Failure(ImportFinancialError.PageSequenceInvalid)
    } catch (_: Exception) {
        DomainResult.Failure(ImportFinancialError.AtomicExchangeUnavailable)
    }

    override suspend fun undo(request: ImportFinancialUndoRequest): DomainResult<ImportFinancialUndoResult> = try {
        val audit = structuredAudit(request.bookId, request.batchId)
            ?: return DomainResult.Failure(ImportFinancialError.NotFound)
        if (audit.reversed) {
            return DomainResult.Success(ImportFinancialUndoResult(audit.totalRows, 0, replayed = true))
        }
        if (audit.committedLocalRevision != audit.liveLocalRevision) {
            return DomainResult.Failure(ImportFinancialError.LiveHeadChanged)
        }
        val snapshot = try {
            shadowAccess.createRollbackSnapshot(request.bookId, audit.operationId, request.operationId)
        } catch (_: IllegalArgumentException) {
            return DomainResult.Failure(ImportFinancialError.NotFound)
        }
        try {
            shadowAccess.writeShadow(request.bookId, request.operationId) { database ->
                insertRestoreAudit(database, request, audit)
            }
            if (!shadowAccess.validate(request.bookId, request.operationId).isValid) {
                return DomainResult.Failure(ImportFinancialError.ShadowValidationFailed)
            }
            val exchanged = try {
                shadowAccess.exchange(request.bookId, request.operationId, snapshot.expectedLiveHead)
            } catch (_: IllegalArgumentException) {
                return DomainResult.Failure(ImportFinancialError.LiveHeadChanged)
            }
            if (!exchanged.isValid) return DomainResult.Failure(ImportFinancialError.ShadowValidationFailed)
            shadowAccess.discardSafety(audit.operationId)
            DomainResult.Success(ImportFinancialUndoResult(audit.totalRows, 1, replayed = false))
        } finally {
            shadowAccess.discard(request.operationId)
        }
    } catch (_: Exception) {
        DomainResult.Failure(ImportFinancialError.AtomicExchangeUnavailable)
    }

    private fun insertHeader(database: SupportSQLiteDatabase, request: StructuredImportCommitRequest) {
        val operation = database.query(
            "SELECT id FROM background_operation WHERE uid=?",
            arrayOf(request.metadata.operationId.bytes),
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }
        database.execSQL(
            "INSERT INTO import_record(id,uid,operation_id,format,source_fingerprint,imported_at,committed_local_revision) " +
                "VALUES((SELECT COALESCE(MAX(id),0)+1 FROM import_record),?,?,?,?,NULL,NULL)",
            arrayOf(
                request.metadata.importRecordId.bytes,
                operation,
                request.metadata.formatCode,
                request.metadata.sourceFingerprint.bytes,
            ),
        )
    }

    private fun recordCommit(
        database: SupportSQLiteDatabase,
        importRecordId: StableId,
        batchId: StableId,
        commitId: StableId,
        sourceRowNumber: Long,
    ) {
        database.execSQL(
            "INSERT INTO import_batch_commit(import_record_id,batch_uid,commit_id,first_row_number,last_row_number) " +
                "VALUES((SELECT id FROM import_record WHERE uid=?),?," +
                "(SELECT id FROM book_commit WHERE uid=?),?,?)",
            arrayOf(importRecordId.bytes, batchId.bytes, commitId.bytes, sourceRowNumber, sourceRowNumber),
        )
    }

    private fun insertSourceRows(database: SupportSQLiteDatabase, importRecordId: StableId, page: ImportFinancialPage) {
        val importInternalId = database.query(
            "SELECT id FROM import_record WHERE uid=?",
            arrayOf(importRecordId.bytes),
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }
        database.compileStatement(
            "INSERT INTO import_source_reference(import_record_id,row_number,transaction_id,source_row_hash) " +
                "VALUES(?,?,(SELECT id FROM business_transaction WHERE uid=?),?)",
        ).use { statement ->
            page.sourceRows.forEach { source ->
                statement.bindLong(1, importInternalId)
                statement.bindLong(2, source.sourceRowNumber)
                statement.bindBlob(3, source.transactionId.bytes)
                statement.bindBlob(4, source.sourceRowHash.bytes)
                statement.executeInsert()
                statement.clearBindings()
            }
        }
    }

    private fun requirePage(page: ImportFinancialPage, after: Long, total: Long) {
        require(page.firstRowNumber == after + 1L)
        require(page.lastRowNumber <= total)
        require(page.lastPage == (page.lastRowNumber == total))
        require(page.sourceRows.isNotEmpty())
    }

    private fun existingRows(bookId: StableId, fingerprint: Hash256): Long? = primaryAccess.read(bookId) { database ->
        database.query(
            "SELECT CASE WHEN MAX(CASE WHEN bc.kind=? THEN 1 ELSE 0 END)=1 " +
                "THEN MAX(ib.last_row_number)-MIN(ib.first_row_number)+1 " +
                "ELSE MAX(COUNT(DISTINCT isr.row_number),COUNT(DISTINCT ib.first_row_number)) END FROM import_record ir " +
                "JOIN import_batch_commit ib ON ib.import_record_id=ir.id " +
                "JOIN book_commit bc ON bc.id=ib.commit_id " +
                "LEFT JOIN import_source_reference isr ON isr.import_record_id=ir.id " +
                "WHERE ir.source_fingerprint=? AND ir.imported_at IS NOT NULL GROUP BY ir.id",
            arrayOf(app.ledger.finance.domain.CommitKind.RESTORE.ordinal, fingerprint.bytes),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
    }

    private fun structuredAudit(bookId: StableId, batchId: StableId): StructuredAudit? = primaryAccess.read(bookId) { database ->
        database.query(
            "SELECT ir.uid,bo.uid,ir.format,ir.source_fingerprint,ir.imported_at,ir.committed_local_revision," +
                "CASE WHEN MAX(CASE WHEN bc.kind=? THEN 1 ELSE 0 END)=1 " +
                "THEN MAX(all_batches.last_row_number)-MIN(all_batches.first_row_number)+1 " +
                "ELSE MAX(COUNT(DISTINCT isr.row_number),COUNT(DISTINCT all_batches.first_row_number)) END,b.local_revision," +
                "MAX(CASE WHEN bc.kind=? THEN 1 ELSE 0 END) " +
                "FROM import_batch_commit selected JOIN import_record ir ON ir.id=selected.import_record_id " +
                "JOIN background_operation bo ON bo.id=ir.operation_id " +
                "JOIN import_batch_commit all_batches ON all_batches.import_record_id=ir.id " +
                "JOIN book_commit bc ON bc.id=all_batches.commit_id JOIN book b ON b.id=1 " +
                "LEFT JOIN import_source_reference isr ON isr.import_record_id=ir.id " +
                "WHERE selected.batch_uid=? GROUP BY ir.id",
            arrayOf(
                app.ledger.finance.domain.CommitKind.RESTORE.ordinal,
                app.ledger.finance.domain.CommitKind.RESTORE.ordinal,
                batchId.bytes,
            ),
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                StructuredAudit(
                    StableId.fromBytes(cursor.getBlob(0)).valueOrAbort(),
                    StableId.fromBytes(cursor.getBlob(1)).valueOrAbort(), cursor.getInt(2),
                    Hash256.fromBytes(cursor.getBlob(3)).valueOrAbort(),
                    cursor.getLong(4), cursor.getLong(5), cursor.getLong(6), cursor.getLong(7), cursor.getInt(8) == 1,
                )
            }
        }
    }

    private fun insertRestoreAudit(
        database: SupportSQLiteDatabase,
        request: ImportFinancialUndoRequest,
        audit: StructuredAudit,
    ) {
        val oldHead = database.singleLong("SELECT head_commit_id FROM book WHERE id=1")
        val revision = database.singleLong("SELECT local_revision FROM book WHERE id=1") + 1L
        val commitId = derived(request.operationId, "structured-import-restore")
        val deviceId = derived(request.operationId, "structured-import-restore-device")
        val rootHash = MessageDigest.getInstance("SHA-256").digest(
            audit.fingerprint.bytes + request.requestedAt.toEpochMilli().toString().toByteArray(Charsets.US_ASCII),
        )
        val internalCommit = database.singleLong("SELECT COALESCE(MAX(id),0)+1 FROM book_commit")
        database.execSQL(
            "INSERT INTO book_commit(id,uid,local_revision,kind,command_uid,device_instance_uid,created_at,root_hash) " +
                "VALUES(?,?,?,?,NULL,?,?,?)",
            arrayOf(
                internalCommit,
                commitId.bytes,
                revision,
                app.ledger.finance.domain.CommitKind.RESTORE.ordinal,
                deviceId.bytes,
                request.requestedAt.toEpochMilli(),
                rootHash,
            ),
        )
        database.execSQL(
            "INSERT INTO book_commit_parent(commit_id,parent_commit_id,ordinal) VALUES(?,?,0)",
            arrayOf(internalCommit, oldHead),
        )
        database.execSQL("UPDATE book SET head_commit_id=?,local_revision=? WHERE id=1", arrayOf(internalCommit, revision))
        val operation = database.query("SELECT id FROM background_operation WHERE uid=?", arrayOf(audit.operationId.bytes)).use {
            check(it.moveToFirst())
            it.getLong(0)
        }
        database.execSQL(
            "UPDATE background_operation SET state=9,updated_at=?,progress_current=?,progress_total=? WHERE id=?",
            arrayOf(request.requestedAt.toEpochMilli(), audit.totalRows, audit.totalRows, operation),
        )
        val importInternal = database.singleLong("SELECT COALESCE(MAX(id),0)+1 FROM import_record")
        database.execSQL(
            "INSERT INTO import_record(id,uid,operation_id,format,source_fingerprint,imported_at,committed_local_revision) " +
                "VALUES(?,?,?,?,?,?,?)",
            arrayOf(importInternal, audit.importRecordId.bytes, operation, audit.format, audit.fingerprint.bytes, audit.importedAt, revision),
        )
        database.execSQL(
            "INSERT INTO import_batch_commit(import_record_id,batch_uid,commit_id,first_row_number,last_row_number) VALUES(?,?,?,?,?)",
            arrayOf(importInternal, request.batchId.bytes, internalCommit, 1L, audit.totalRows),
        )
    }

    private fun derived(seed: StableId, label: String): StableId = StableId.fromBytes(
        MessageDigest.getInstance("SHA-256")
            .digest(seed.bytes + label.toByteArray(Charsets.UTF_8))
            .copyOf(StableId.BYTE_COUNT),
    ).valueOrAbort()

    private fun SupportSQLiteDatabase.singleLong(sql: String): Long = query(sql).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getLong(0)
    }

    private companion object {
        const val PAGE_ROWS = 256
    }

    private data class StructuredAudit(
        val importRecordId: StableId,
        val operationId: StableId,
        val format: Int,
        val fingerprint: Hash256,
        val importedAt: Long,
        val committedLocalRevision: Long,
        val totalRows: Long,
        val liveLocalRevision: Long,
        val reversed: Boolean,
    )
}
