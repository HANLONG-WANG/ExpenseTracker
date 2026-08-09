@file:Suppress(
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
import app.ledger.core.common.CommandId
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.common.StableIdSource
import app.ledger.core.security.DeviceLedgerKeyProvider
import app.ledger.core.security.SecurePrimaryLedgerAccess
import app.ledger.finance.application.BatchUndoRequest
import app.ledger.finance.application.BatchUndoRowIds
import app.ledger.finance.application.FinanceDataError
import app.ledger.finance.application.ImportFinancialApplicationPort
import app.ledger.finance.application.ImportFinancialAuditView
import app.ledger.finance.application.ImportFinancialCommitRequest
import app.ledger.finance.application.ImportFinancialCommitResult
import app.ledger.finance.application.ImportFinancialError
import app.ledger.finance.application.ImportFinancialHistoryItem
import app.ledger.finance.application.ImportFinancialPage
import app.ledger.finance.application.ImportFinancialUndoRequest
import app.ledger.finance.application.ImportFinancialUndoResult
import app.ledger.finance.application.ReferenceDataManagementPort
import app.ledger.finance.domain.FinancialMutationPlan
import app.ledger.finance.domain.Hash256
import java.time.Instant

/** P28 financial import adapter: one coordinator transaction for small sets, shadow-ledger pages for huge sets. */
class SecureRoomImportFinancialApplicationPort(
    context: Context,
    private val keyProvider: DeviceLedgerKeyProvider,
    private val referenceDataPort: ReferenceDataManagementPort,
    private val stableIds: StableIdSource,
    private val failureInjector: FinancialCommitFailureInjector = FinancialCommitFailureInjector.NONE,
) : ImportFinancialApplicationPort {
    private val applicationContext = context.applicationContext
    private val primaryAccess = SecurePrimaryLedgerAccess(applicationContext, keyProvider)
    private val shadowAccess = SecureShadowLedgerAccess(applicationContext, keyProvider)

    override suspend fun commit(request: ImportFinancialCommitRequest): DomainResult<ImportFinancialCommitResult> = try {
        existing(request.bookId, request.metadata.sourceFingerprint)?.let { audit ->
            return DomainResult.Success(
                ImportFinancialCommitResult(audit.importedRows, 1, audit.importedRows > request.shadowThresholdRows, replayed = true),
            )
        }
        if (request.metadata.totalRows <= request.shadowThresholdRows) commitSmall(request) else commitShadow(request)
    } catch (_: IllegalArgumentException) {
        DomainResult.Failure(ImportFinancialError.PageSequenceInvalid)
    } catch (_: Exception) {
        DomainResult.Failure(ImportFinancialError.AtomicExchangeUnavailable)
    }

    override suspend fun undo(request: ImportFinancialUndoRequest): DomainResult<ImportFinancialUndoResult> = try {
        val audit = auditBlockingPrimary(request.bookId, request.batchId)
            ?: return DomainResult.Failure(ImportFinancialError.NotFound)
        if (audit.reversed) {
            return DomainResult.Success(ImportFinancialUndoResult(audit.importedRows, 0, replayed = true))
        }
        val snapshot = shadowAccess.createSnapshot(request.bookId, request.operationId)
        try {
            val batchIds = shadowAccess.readShadow(request.bookId, request.operationId) { database ->
                database.query(
                    "SELECT ib.batch_uid FROM import_batch_commit ib JOIN import_record ir ON ir.id=ib.import_record_id " +
                        "WHERE ir.uid=? ORDER BY ib.first_row_number",
                    arrayOf(audit.importRecordId.bytes),
                ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.stableId(0)) } }
            }
            if (batchIds.isEmpty() || batchIds.first() != request.batchId) {
                return DomainResult.Failure(ImportFinancialError.NotFound)
            }
            var reversedRows = 0L
            batchIds.forEach { originalId ->
                val port = SecureRoomBatchEntryApplicationPort(
                    applicationContext,
                    keyProvider,
                    referenceDataPort,
                    databaseName = snapshot.shadowDatabaseName,
                )
                val batchAudit = when (val result = port.audit(request.bookId, CommandId(originalId))) {
                    is DomainResult.Success -> result.value ?: return DomainResult.Failure(ImportFinancialError.NotFound)
                    is DomainResult.Failure -> return result
                }
                if (!batchAudit.fullyReversed) {
                    val undo = BatchUndoRequest(
                        request.bookId,
                        CommandId(originalId),
                        CommandId(stableIds.nextStableId()),
                        stableIds.nextStableId(),
                        stableIds.nextStableId(),
                        request.requestedAt,
                        batchAudit.transactionIds.map { transactionId ->
                            BatchUndoRowIds(
                                transactionId,
                                stableIds.nextStableId(),
                                List(UNDO_FACT_ID_CAPACITY) { stableIds.nextStableId() },
                            )
                        },
                    )
                    when (val result = port.undo(undo)) {
                        is DomainResult.Success -> reversedRows += batchAudit.transactionIds.size
                        is DomainResult.Failure -> return result
                    }
                }
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
            shadowAccess.discardSafety(request.operationId)
            DomainResult.Success(ImportFinancialUndoResult(reversedRows, batchIds.size, replayed = false))
        } finally {
            shadowAccess.discard(request.operationId)
        }
    } catch (_: Exception) {
        DomainResult.Failure(ImportFinancialError.AtomicExchangeUnavailable)
    }

    override suspend fun audit(bookId: StableId, batchId: StableId): DomainResult<ImportFinancialAuditView?> = try {
        DomainResult.Success(primaryAccess.read(bookId) { database -> auditBlocking(database, batchId) })
    } catch (_: Exception) {
        DomainResult.Failure(FinanceDataError.DatabaseUnavailable)
    }

    override suspend fun history(bookId: StableId): DomainResult<List<ImportFinancialHistoryItem>> = try {
        DomainResult.Success(
            primaryAccess.read(bookId) { database ->
                database.query(
                    "SELECT ir.uid,first_batch.batch_uid,bo.uid,ir.imported_at," +
                        "CASE WHEN MAX(CASE WHEN all_commits.kind=? THEN 1 ELSE 0 END)=1 " +
                        "THEN (MAX(all_batches.last_row_number)-MIN(all_batches.first_row_number)+1) " +
                        "ELSE MAX(COUNT(DISTINCT isr.row_number),COUNT(DISTINCT all_batches.first_row_number)) END," +
                        "COUNT(DISTINCT isr.row_number),COUNT(DISTINCT CASE WHEN bt.lifecycle_state=1 THEN isr.row_number END)," +
                        "MAX(CASE WHEN all_commits.kind=? THEN 1 ELSE 0 END) " +
                        "FROM import_record ir JOIN background_operation bo ON bo.id=ir.operation_id " +
                        "JOIN import_batch_commit first_batch ON first_batch.rowid=(SELECT x.rowid FROM import_batch_commit x " +
                        "WHERE x.import_record_id=ir.id ORDER BY x.first_row_number,x.rowid LIMIT 1) " +
                        "JOIN import_batch_commit all_batches ON all_batches.import_record_id=ir.id " +
                        "JOIN book_commit all_commits ON all_commits.id=all_batches.commit_id " +
                        "LEFT JOIN import_source_reference isr ON isr.import_record_id=ir.id " +
                        "LEFT JOIN business_transaction bt ON bt.id=isr.transaction_id " +
                        "WHERE ir.imported_at IS NOT NULL GROUP BY ir.id ORDER BY ir.imported_at DESC",
                    arrayOf(
                        app.ledger.finance.domain.CommitKind.RESTORE.ordinal,
                        app.ledger.finance.domain.CommitKind.RESTORE.ordinal,
                    ),
                ).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            add(
                                ImportFinancialHistoryItem(
                                    cursor.stableId(0),
                                    cursor.stableId(1),
                                    cursor.stableId(2),
                                    Instant.ofEpochMilli(cursor.getLong(3)),
                                    cursor.getLong(4),
                                    cursor.getInt(7) == 1 || (cursor.getLong(5) > 0L && cursor.getLong(5) == cursor.getLong(6)),
                                ),
                            )
                        }
                    }
                }
            },
        )
    } catch (_: Exception) {
        DomainResult.Failure(FinanceDataError.DatabaseUnavailable)
    }

    private suspend fun commitSmall(request: ImportFinancialCommitRequest): DomainResult<ImportFinancialCommitResult> {
        val page = when (val loaded = request.pages.load(0L, request.metadata.totalRows.toInt())) {
            is DomainResult.Success -> loaded.value ?: return DomainResult.Failure(ImportFinancialError.EmptyPreparedSet)
            is DomainResult.Failure -> return loaded
        }
        if (!page.lastPage || page.firstRowNumber != 1L || page.lastRowNumber != request.metadata.totalRows) {
            return DomainResult.Failure(ImportFinancialError.PageSequenceInvalid)
        }
        if (page.submitRequest.commandId.stableId != request.metadata.batchId) {
            return DomainResult.Failure(ImportFinancialError.PageSequenceInvalid)
        }
        val metadata = FinancialCommitSideEffect { database, plan ->
            insertHeader(database, request, replace = false)
            insertSourceRows(database, request.metadata.importRecordId, page)
            recordBatchCommit(database, request.metadata.importRecordId, page, plan)
            finalizeImport(database, request, plan)
        }
        val port = SecureRoomBatchEntryApplicationPort(
            applicationContext,
            keyProvider,
            referenceDataPort,
            failureInjector,
            additionalAfterFinancialSideEffect = metadata,
        )
        return when (val committed = port.submit(page.submitRequest)) {
            is DomainResult.Success -> DomainResult.Success(
                ImportFinancialCommitResult(request.metadata.totalRows, 1, usedShadowLedger = false, replayed = false),
            )
            is DomainResult.Failure -> committed
        }
    }

    private suspend fun commitShadow(request: ImportFinancialCommitRequest): DomainResult<ImportFinancialCommitResult> {
        val operationId = request.metadata.operationId
        val snapshot = shadowAccess.createSnapshot(request.bookId, operationId)
        try {
            shadowAccess.writeShadow(request.bookId, operationId) { database -> insertHeader(database, request, replace = false) }
            var after = 0L
            var pageCount = 0
            while (after < request.metadata.totalRows) {
                val page = when (val loaded = request.pages.load(after, SHADOW_PAGE_ROWS)) {
                    is DomainResult.Success -> loaded.value ?: return DomainResult.Failure(ImportFinancialError.PageSequenceInvalid)
                    is DomainResult.Failure -> return loaded
                }
                requireValidPage(page, after, request.metadata.totalRows)
                if (after == 0L && page.submitRequest.commandId.stableId != request.metadata.batchId) {
                    return DomainResult.Failure(ImportFinancialError.PageSequenceInvalid)
                }
                val sideEffect = FinancialCommitSideEffect { database, plan ->
                    insertSourceRows(database, request.metadata.importRecordId, page)
                    recordBatchCommit(database, request.metadata.importRecordId, page, plan)
                    if (page.lastPage) finalizeImport(database, request, plan)
                }
                val port = SecureRoomBatchEntryApplicationPort(
                    applicationContext,
                    keyProvider,
                    referenceDataPort,
                    failureInjector,
                    databaseName = snapshot.shadowDatabaseName,
                    additionalAfterFinancialSideEffect = sideEffect,
                )
                when (val committed = port.submit(page.submitRequest)) {
                    is DomainResult.Success -> Unit
                    is DomainResult.Failure -> return committed
                }
                after = page.lastRowNumber
                pageCount++
            }
            if (!shadowAccess.validate(request.bookId, operationId).isValid) {
                return DomainResult.Failure(ImportFinancialError.ShadowValidationFailed)
            }
            val exchanged = try {
                shadowAccess.exchange(request.bookId, operationId, snapshot.expectedLiveHead)
            } catch (_: IllegalArgumentException) {
                return DomainResult.Failure(ImportFinancialError.LiveHeadChanged)
            }
            if (!exchanged.isValid) return DomainResult.Failure(ImportFinancialError.ShadowValidationFailed)
            shadowAccess.discardSafety(operationId)
            return DomainResult.Success(
                ImportFinancialCommitResult(request.metadata.totalRows, pageCount, usedShadowLedger = true, replayed = false),
            )
        } finally {
            shadowAccess.discard(operationId)
        }
    }

    private fun requireValidPage(page: ImportFinancialPage, after: Long, total: Long) {
        require(page.firstRowNumber == after + 1L)
        require(page.lastRowNumber <= total)
        require(page.lastPage == (page.lastRowNumber == total))
        require(page.lastRowNumber - page.firstRowNumber + 1L <= SHADOW_PAGE_ROWS)
    }

    private fun insertHeader(
        database: SupportSQLiteDatabase,
        request: ImportFinancialCommitRequest,
        replace: Boolean,
    ) {
        val operationInternalId = database.singleLong(
            "SELECT id FROM background_operation WHERE uid=?",
            request.metadata.operationId.bytes,
        )
        val existing = database.nullableLong(
            "SELECT id FROM import_record WHERE source_fingerprint=?",
            request.metadata.sourceFingerprint.bytes,
        )
        if (existing != null) {
            if (!replace) error("source already imported")
            return
        }
        database.execSQL(
            "INSERT INTO import_record(id,uid,operation_id,format,source_fingerprint,imported_at,committed_local_revision) " +
                "VALUES((SELECT COALESCE(MAX(id),0)+1 FROM import_record),?,?,?,?,NULL,NULL)",
            arrayOf(
                request.metadata.importRecordId.bytes,
                operationInternalId,
                request.metadata.formatCode,
                request.metadata.sourceFingerprint.bytes,
            ),
        )
    }

    private fun insertSourceRows(database: SupportSQLiteDatabase, importRecordId: StableId, page: ImportFinancialPage) {
        val importInternalId = database.singleLong("SELECT id FROM import_record WHERE uid=?", importRecordId.bytes)
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

    private fun finalizeImport(
        database: SupportSQLiteDatabase,
        request: ImportFinancialCommitRequest,
        plan: FinancialMutationPlan,
    ) {
        val importInternalId = database.singleLong("SELECT id FROM import_record WHERE uid=?", request.metadata.importRecordId.bytes)
        database.execSQL(
            "UPDATE import_record SET imported_at=?,committed_local_revision=? WHERE id=? AND imported_at IS NULL",
            arrayOf(request.metadata.requestedAt.toEpochMilli(), plan.targetLocalRevision.value, importInternalId),
        )
    }

    private fun recordBatchCommit(
        database: SupportSQLiteDatabase,
        importRecordId: StableId,
        page: ImportFinancialPage,
        plan: FinancialMutationPlan,
    ) {
        val importInternalId = database.singleLong("SELECT id FROM import_record WHERE uid=?", importRecordId.bytes)
        val commitInternalId = database.singleLong("SELECT id FROM book_commit WHERE uid=?", plan.commit.id.value.bytes)
        database.execSQL(
            "INSERT INTO import_batch_commit(import_record_id,batch_uid,commit_id,first_row_number,last_row_number) VALUES(?,?,?,?,?)",
            arrayOf(
                importInternalId,
                page.submitRequest.commandId.stableId.bytes,
                commitInternalId,
                page.firstRowNumber,
                page.lastRowNumber,
            ),
        )
    }

    private fun existing(bookId: StableId, fingerprint: Hash256): ImportFinancialAuditView? = primaryAccess.read(bookId) { database ->
        database.query(
            "SELECT ib.batch_uid FROM import_record ir JOIN import_batch_commit ib ON ib.import_record_id=ir.id " +
                "WHERE ir.source_fingerprint=? AND ir.imported_at IS NOT NULL ORDER BY ib.first_row_number LIMIT 1",
            arrayOf(fingerprint.bytes),
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                when (val result = auditBlocking(database, cursor.stableId(0))) {
                    null -> error("corrupt import audit")
                    else -> result
                }
            }
        }
    }

    private fun auditBlocking(database: SupportSQLiteDatabase, batchId: StableId): ImportFinancialAuditView? = database.query(
        "SELECT ir.uid,ib.batch_uid,bo.uid,ir.imported_at,ir.source_fingerprint," +
            "CASE WHEN MAX(CASE WHEN all_commits.kind=? THEN 1 ELSE 0 END)=1 " +
            "THEN (MAX(all_batches.last_row_number)-MIN(all_batches.first_row_number)+1) " +
            "ELSE MAX(COUNT(DISTINCT isr.row_number),COUNT(DISTINCT all_batches.first_row_number)) END," +
            "COUNT(DISTINCT isr.row_number),COUNT(DISTINCT CASE WHEN bt.lifecycle_state=1 THEN isr.row_number END)," +
            "MAX(CASE WHEN all_commits.kind=? THEN 1 ELSE 0 END) " +
            "FROM import_batch_commit ib JOIN import_record ir ON ir.id=ib.import_record_id " +
            "JOIN background_operation bo ON bo.id=ir.operation_id " +
            "JOIN import_batch_commit all_batches ON all_batches.import_record_id=ir.id " +
            "JOIN book_commit all_commits ON all_commits.id=all_batches.commit_id " +
            "LEFT JOIN import_source_reference isr ON isr.import_record_id=ir.id " +
            "LEFT JOIN business_transaction bt ON bt.id=isr.transaction_id WHERE ib.batch_uid=? GROUP BY ir.id,ib.batch_uid,bo.uid",
        arrayOf(
            app.ledger.finance.domain.CommitKind.RESTORE.ordinal,
            app.ledger.finance.domain.CommitKind.RESTORE.ordinal,
            batchId.bytes,
        ),
    ).use { cursor ->
        if (!cursor.moveToFirst()) {
            null
        } else {
            ImportFinancialAuditView(
                cursor.stableId(0),
                cursor.stableId(1),
                cursor.stableId(2),
                Instant.ofEpochMilli(cursor.getLong(3)),
                cursor.getLong(5),
                Hash256.fromBytes(cursor.getBlob(4)).valueOrAbort(),
                cursor.getInt(8) == 1 || (cursor.getLong(6) > 0L && cursor.getLong(6) == cursor.getLong(7)),
            )
        }
    }

    private fun auditBlockingPrimary(bookId: StableId, batchId: StableId): ImportFinancialAuditView? = primaryAccess.read(bookId) { database -> auditBlocking(database, batchId) }

    private companion object {
        const val SHADOW_PAGE_ROWS = 512
        const val UNDO_FACT_ID_CAPACITY = 251
    }
}

private fun android.database.Cursor.stableId(index: Int): StableId = StableId.fromBytes(getBlob(index)).valueOrAbort()

private fun SupportSQLiteDatabase.singleLong(sql: String, argument: ByteArray): Long = query(sql, arrayOf(argument)).use { cursor ->
    check(cursor.moveToFirst())
    cursor.getLong(0)
}

private fun SupportSQLiteDatabase.nullableLong(sql: String, argument: ByteArray): Long? = query(sql, arrayOf(argument)).use { cursor ->
    if (cursor.moveToFirst()) cursor.getLong(0) else null
}
