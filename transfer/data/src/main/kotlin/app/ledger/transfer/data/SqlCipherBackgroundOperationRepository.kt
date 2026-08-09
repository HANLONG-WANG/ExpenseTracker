@file:Suppress("LongMethod", "MagicNumber", "NestedBlockDepth", "TooGenericExceptionCaught", "TooManyFunctions")

package app.ledger.transfer.data

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import app.ledger.core.common.DomainError
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.security.SecurePrimaryLedgerAccess
import app.ledger.finance.domain.AttachmentId
import app.ledger.finance.domain.Hash256
import app.ledger.finance.domain.UserAccountId
import app.ledger.transfer.domain.BackgroundOperation
import app.ledger.transfer.domain.BackgroundOperationId
import app.ledger.transfer.domain.BackgroundOperationRepository
import app.ledger.transfer.domain.BackgroundOperationState
import app.ledger.transfer.domain.BackgroundOperationType
import app.ledger.transfer.domain.BackupRepositoryId
import app.ledger.transfer.domain.BackupSnapshotId
import app.ledger.transfer.domain.EncryptedCheckpoint
import app.ledger.transfer.domain.ExportContent
import app.ledger.transfer.domain.ExportDescriptor
import app.ledger.transfer.domain.ExportField
import app.ledger.transfer.domain.ExportFilter
import app.ledger.transfer.domain.ExportFormat
import app.ledger.transfer.domain.ExportReportSnapshot
import app.ledger.transfer.domain.ImportFormat
import app.ledger.transfer.domain.MaintenanceKind
import app.ledger.transfer.domain.OperationCheckpoint
import app.ledger.transfer.domain.OperationParameters
import app.ledger.transfer.domain.OperationProgress
import app.ledger.transfer.domain.RestoreMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Instant

class SqlCipherBackgroundOperationRepository(
    private val bookId: StableId,
    private val access: SecurePrimaryLedgerAccess,
) : BackgroundOperationRepository {
    override suspend fun get(id: BackgroundOperationId): DomainResult<BackgroundOperation?> = protect {
        val stored = access.read(bookId) { database ->
            database.query(
                "SELECT type,state,created_at,started_at,updated_at,progress_current,progress_total," +
                    "checkpoint_version,error_code,cancel_requested,parameters_ciphertext " +
                    "FROM background_operation WHERE uid=?",
                arrayOf(id.value.bytes),
            ).use { cursor -> if (cursor.moveToFirst()) readStored(cursor) else null }
        } ?: return@protect null
        val plaintext = access.open(bookId, id.value, PARAMETERS_PURPOSE, stored.parametersCiphertext)
        try {
            BackgroundOperation.restore(
                id,
                stored.type,
                stored.state,
                stored.createdAt,
                stored.startedAt,
                stored.updatedAt,
                stored.progress,
                stored.checkpointVersion,
                stored.errorCode,
                stored.cancelRequested,
                OperationParameterCodec.decode(plaintext),
            )
        } finally {
            plaintext.fill(0)
        }
    }

    override suspend fun save(operation: BackgroundOperation): DomainResult<Unit> = protectUnit {
        val encoded = OperationParameterCodec.encode(operation.parameters)
        val sealed = try {
            access.seal(bookId, operation.id.value, PARAMETERS_PURPOSE, encoded)
        } finally {
            encoded.fill(0)
        }
        try {
            access.write(bookId) { database ->
                val internalId = database.operationInternalId(operation.id.value) ?: database.nextOperationId()
                database.execSQL(
                    "INSERT OR REPLACE INTO background_operation(" +
                        "id,uid,type,state,created_at,started_at,updated_at,progress_current,progress_total," +
                        "checkpoint_version,error_code,cancel_requested,parameters_ciphertext) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    arrayOf(
                        internalId,
                        operation.id.value.bytes,
                        operation.type.ordinal,
                        operation.state.ordinal,
                        operation.createdAt.toEpochMilli(),
                        operation.startedAt?.toEpochMilli(),
                        operation.updatedAt.toEpochMilli(),
                        operation.progress.current,
                        operation.progress.total,
                        operation.checkpointVersion,
                        operation.errorCode,
                        if (operation.cancelRequested) 1 else 0,
                        sealed,
                    ),
                )
            }
        } finally {
            sealed.fill(0)
        }
    }

    override suspend fun append(checkpoint: OperationCheckpoint): DomainResult<Unit> = protectUnit {
        val sealed = access.seal(bookId, checkpoint.operationId.value, CHECKPOINT_PURPOSE, checkpoint.checkpoint.bytes)
        try {
            access.write(bookId) { database ->
                val operationInternalId = database.operationInternalId(checkpoint.operationId.value)
                    ?: error("operation missing")
                database.execSQL(
                    "INSERT OR REPLACE INTO operation_checkpoint(" +
                        "operation_id,sequence,phase,checkpoint_ciphertext,created_at) VALUES(?,?,?,?,?)",
                    arrayOf(
                        operationInternalId,
                        checkpoint.sequence,
                        checkpoint.phase.ordinal,
                        sealed,
                        checkpoint.createdAt.toEpochMilli(),
                    ),
                )
            }
        } finally {
            sealed.fill(0)
        }
    }

    fun checkpoint(lastStagedRow: Long): EncryptedCheckpoint {
        require(lastStagedRow >= 0L)
        return EncryptedCheckpoint.of(
            ByteArray(Long.SIZE_BYTES) { index ->
                (lastStagedRow ushr ((Long.SIZE_BYTES - 1 - index) * Byte.SIZE_BITS)).toByte()
            },
        ).requireValue()
    }

    private fun readStored(cursor: Cursor): StoredOperation = StoredOperation(
        type = BackgroundOperationType.entries[cursor.getInt(0)],
        state = BackgroundOperationState.entries[cursor.getInt(1)],
        createdAt = Instant.ofEpochMilli(cursor.getLong(2)),
        startedAt = cursor.nullableLong(3)?.let(Instant::ofEpochMilli),
        updatedAt = Instant.ofEpochMilli(cursor.getLong(4)),
        progress = OperationProgress(cursor.getLong(5), cursor.nullableLong(6)),
        checkpointVersion = cursor.getLong(7),
        errorCode = cursor.nullableString(8),
        cancelRequested = cursor.getInt(9) == 1,
        parametersCiphertext = cursor.getBlob(10),
    )

    private inline fun <T> protect(block: () -> T): DomainResult<T> = try {
        DomainResult.Success(block())
    } catch (_: Exception) {
        DomainResult.Failure(OperationPersistenceError.UnavailableOrCorrupt)
    }

    private inline fun protectUnit(block: () -> Unit): DomainResult<Unit> = protect(block)

    private data class StoredOperation(
        val type: BackgroundOperationType,
        val state: BackgroundOperationState,
        val createdAt: Instant,
        val startedAt: Instant?,
        val updatedAt: Instant,
        val progress: OperationProgress,
        val checkpointVersion: Long,
        val errorCode: String?,
        val cancelRequested: Boolean,
        val parametersCiphertext: ByteArray,
    )

    private companion object {
        const val PARAMETERS_PURPOSE = "parameters"
        const val CHECKPOINT_PURPOSE = "checkpoint"
    }
}

sealed interface OperationPersistenceError : DomainError {
    data object UnavailableOrCorrupt : OperationPersistenceError {
        override val code: String = "OPERATION_DATABASE_UNAVAILABLE_OR_CORRUPT"
    }
}

private object OperationParameterCodec {
    fun encode(parameters: OperationParameters): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            when (parameters) {
                is OperationParameters.Import -> {
                    output.writeByte(0)
                    output.id(parameters.sourceHandleId)
                    output.writeInt(parameters.format.ordinal)
                    output.nullableId(parameters.defaultAccountId?.value)
                    output.nullableUtf8(parameters.userCharset)
                    output.writeLong(parameters.headerRowNumber)
                    output.writeBoolean(parameters.commit != null)
                    parameters.commit?.let { commit ->
                        output.id(commit.importRecordId)
                        output.id(commit.batchId)
                        output.writeUTF(commit.baseCurrency)
                        output.writeUTF(commit.zoneId)
                        output.writeLong(commit.totalPreparedRows)
                        output.writeLong(commit.transactionRows)
                        output.write(commit.sourceFingerprint.bytes)
                        output.writeLong(commit.firstSourceRowNumber)
                        output.writeBoolean(commit.useStructuredUndo)
                    }
                }
                is OperationParameters.Export -> {
                    output.writeByte(1)
                    output.id(parameters.destinationHandleId)
                    output.writeByte(EXPORT_CODEC_VERSION)
                    output.exportDescriptor(parameters.descriptor)
                }
                is OperationParameters.FullBackup -> {
                    output.writeByte(2)
                    output.id(parameters.repositoryId.value)
                    output.writeBoolean(parameters.portable)
                }
                is OperationParameters.DriveUpload -> {
                    output.writeByte(3)
                    output.id(parameters.snapshotId.value)
                    output.id(parameters.repositoryId.value)
                }
                is OperationParameters.Restore -> {
                    output.writeByte(4)
                    output.id(parameters.sourceHandleId)
                    output.writeInt(parameters.mode.ordinal)
                }
                is OperationParameters.AttachmentMigration -> {
                    output.writeByte(5)
                    output.writeInt(parameters.attachmentIds.size)
                    parameters.attachmentIds.forEach { output.id(it.value) }
                }
                is OperationParameters.DatabaseMaintenance -> {
                    output.writeByte(6)
                    output.writeInt(parameters.kind.ordinal)
                }
            }
        }
        bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): OperationParameters = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        val value = when (input.readUnsignedByte()) {
            0 -> {
                val source = input.id()
                val format = ImportFormat.entries[input.readInt()]
                val account = input.nullableId()?.let(::UserAccountId)
                if (input.available() == 0) {
                    OperationParameters.Import(source, format, account)
                } else {
                    val charset = input.nullableUtf8()
                    val header = input.readLong()
                    val commit = if (input.available() == 0 || !input.readBoolean()) {
                        null
                    } else {
                        app.ledger.transfer.domain.ImportCommitParameters(
                            importRecordId = input.id(),
                            batchId = input.id(),
                            baseCurrency = input.readUTF(),
                            zoneId = input.readUTF(),
                            totalPreparedRows = input.readLong(),
                            transactionRows = input.readLong(),
                            sourceFingerprint = Hash256.fromBytes(input.readExact(Hash256.BYTE_COUNT)).requireValue(),
                            firstSourceRowNumber = input.readLong(),
                            useStructuredUndo = input.readBoolean(),
                        )
                    }
                    OperationParameters.Import(source, format, account, charset, header, commit)
                }
            }
            1 -> {
                val destination = input.id()
                require(input.readUnsignedByte() == EXPORT_CODEC_VERSION)
                OperationParameters.Export(destination, input.exportDescriptor())
            }
            2 -> OperationParameters.FullBackup(BackupRepositoryId(input.id()), input.readBoolean())
            3 -> OperationParameters.DriveUpload(BackupSnapshotId(input.id()), BackupRepositoryId(input.id()))
            4 -> OperationParameters.Restore(input.id(), RestoreMode.entries[input.readInt()])
            5 -> OperationParameters.AttachmentMigration(List(input.readInt()) { AttachmentId(input.id()) })
            6 -> OperationParameters.DatabaseMaintenance(MaintenanceKind.entries[input.readInt()])
            else -> error("invalid operation parameters")
        }
        require(input.read() == -1)
        value
    }

    private fun DataOutputStream.id(id: StableId) = write(id.bytes)
    private fun DataOutputStream.nullableId(id: StableId?) {
        writeBoolean(id != null)
        if (id != null) id(id)
    }
    private fun DataOutputStream.nullableUtf8(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeUTF(value)
    }
    private fun DataInputStream.id(): StableId = StableId.fromBytes(readExact(StableId.BYTE_COUNT)).requireValue()

    private fun DataInputStream.readExact(byteCount: Int): ByteArray = ByteArray(byteCount).also(::readFully)
    private fun DataInputStream.nullableId(): StableId? = if (readBoolean()) id() else null
    private fun DataInputStream.nullableUtf8(): String? = if (readBoolean()) readUTF() else null

    private fun DataOutputStream.exportDescriptor(value: ExportDescriptor) {
        writeInt(value.content.ordinal)
        writeInt(value.format.ordinal)
        writeUTF(value.fileName)
        writeInt(value.fields.size)
        value.fields.sortedBy(Enum<*>::ordinal).forEach { writeInt(it.ordinal) }
        writeBoolean(value.includeLocationCoordinates)
        writeUTF(value.filterSummary)
        exportFilter(value.filter)
        writeBoolean(value.report != null)
        value.report?.let { exportReport(it) }
        writeBoolean(value.overwriteConfirmed)
    }

    private fun DataInputStream.exportDescriptor(): ExportDescriptor = ExportDescriptor(
        content = ExportContent.entries[readInt()],
        format = ExportFormat.entries[readInt()],
        fileName = readUTF(),
        fields = List(boundedCount(ExportField.entries.size)) { ExportField.entries[readInt()] }.toSet(),
        includeLocationCoordinates = readBoolean(),
        filterSummary = readUTF(),
        filter = exportFilter(),
        report = if (readBoolean()) exportReport() else null,
        overwriteConfirmed = readBoolean(),
    )

    private fun DataOutputStream.exportFilter(value: ExportFilter) {
        nullableInstant(value.occurredFrom)
        nullableInstant(value.occurredThrough)
        nullableInstant(value.createdFrom)
        nullableInstant(value.createdThrough)
        nullableInstant(value.modifiedFrom)
        nullableInstant(value.modifiedThrough)
        intSet(value.kinds)
        idSet(value.accountIds)
        idSet(value.cardIds)
        idSet(value.categoryIds)
        idSet(value.merchantIds)
        idSet(value.projectIds)
        idSet(value.settlementActivityIds)
        idSet(value.participantIds)
        stringSet(value.currencies)
        intSet(value.statisticalNatures)
        intSet(value.lifecycleStates)
        intSet(value.sources)
        nullableLong(value.minimumAccountMinor)
        nullableLong(value.maximumAccountMinor)
        nullableUtf8(value.amountCurrency)
        nullableInt(value.centerLatitudeE7)
        nullableInt(value.centerLongitudeE7)
        nullableInt(value.radiusMeters)
        nullableBoolean(value.hasAttachment)
        nullableBoolean(value.isRefund)
        nullableBoolean(value.hasInstallment)
        nullableBoolean(value.includedInBudget)
        nullableBoolean(value.generatedByRecurrence)
        nullableUtf8(value.searchText)
    }

    private fun DataInputStream.exportFilter(): ExportFilter = ExportFilter(
        occurredFrom = nullableInstant(), occurredThrough = nullableInstant(),
        createdFrom = nullableInstant(), createdThrough = nullableInstant(),
        modifiedFrom = nullableInstant(), modifiedThrough = nullableInstant(),
        kinds = intSet(), accountIds = idSet(), cardIds = idSet(), categoryIds = idSet(), merchantIds = idSet(),
        projectIds = idSet(), settlementActivityIds = idSet(), participantIds = idSet(), currencies = stringSet(),
        statisticalNatures = intSet(), lifecycleStates = intSet(), sources = intSet(),
        minimumAccountMinor = nullableLong(), maximumAccountMinor = nullableLong(), amountCurrency = nullableUtf8(),
        centerLatitudeE7 = nullableInt(), centerLongitudeE7 = nullableInt(), radiusMeters = nullableInt(),
        hasAttachment = nullableBoolean(), isRefund = nullableBoolean(), hasInstallment = nullableBoolean(),
        includedInBudget = nullableBoolean(), generatedByRecurrence = nullableBoolean(), searchText = nullableUtf8(),
    )

    private fun DataOutputStream.exportReport(value: ExportReportSnapshot) {
        writeUTF(value.reportKey)
        writeUTF(value.periodStart)
        writeUTF(value.periodEndInclusive)
        writeInt(value.headers.size)
        value.headers.forEach(::writeUTF)
        writeInt(value.rows.size)
        value.rows.forEach { row -> row.forEach(::writeUTF) }
        writeLong(value.localRevision)
        nullableLong(value.valuationRevision)
    }

    private fun DataInputStream.exportReport(): ExportReportSnapshot {
        val reportKey = readUTF()
        val start = readUTF()
        val end = readUTF()
        val headers = List(boundedCount(MAX_REPORT_COLUMNS)) { readUTF() }
        val rows = List(boundedCount(MAX_REPORT_ROWS)) { List(headers.size) { readUTF() } }
        return ExportReportSnapshot(reportKey, start, end, headers, rows, readLong(), nullableLong())
    }

    private fun DataOutputStream.nullableInstant(value: Instant?) = nullableLong(value?.toEpochMilli())
    private fun DataInputStream.nullableInstant(): Instant? = nullableLong()?.let(Instant::ofEpochMilli)
    private fun DataOutputStream.nullableLong(value: Long?) {
        writeBoolean(value != null)
        if (value != null) writeLong(value)
    }
    private fun DataInputStream.nullableLong(): Long? = if (readBoolean()) readLong() else null
    private fun DataOutputStream.nullableInt(value: Int?) {
        writeBoolean(value != null)
        if (value != null) writeInt(value)
    }
    private fun DataInputStream.nullableInt(): Int? = if (readBoolean()) readInt() else null
    private fun DataOutputStream.nullableBoolean(value: Boolean?) {
        writeBoolean(value != null)
        if (value != null) writeBoolean(value)
    }
    private fun DataInputStream.nullableBoolean(): Boolean? = if (readBoolean()) readBoolean() else null
    private fun DataOutputStream.idSet(values: Set<StableId>) {
        writeInt(values.size)
        values.sorted().forEach { id(it) }
    }
    private fun DataInputStream.idSet(): Set<StableId> = List(boundedCount(MAX_FILTER_VALUES)) { id() }.toSet()
    private fun DataOutputStream.intSet(values: Set<Int>) {
        writeInt(values.size)
        values.sorted().forEach { writeInt(it) }
    }
    private fun DataInputStream.intSet(): Set<Int> = List(boundedCount(MAX_FILTER_VALUES)) { readInt() }.toSet()
    private fun DataOutputStream.stringSet(values: Set<String>) {
        writeInt(values.size)
        values.sorted().forEach { writeUTF(it) }
    }
    private fun DataInputStream.stringSet(): Set<String> = List(boundedCount(MAX_FILTER_VALUES)) { readUTF() }.toSet()
    private fun DataInputStream.boundedCount(maximum: Int): Int = readInt().also { require(it in 0..maximum) }

    private const val EXPORT_CODEC_VERSION = 29
    private const val MAX_FILTER_VALUES = 10_000
    private const val MAX_REPORT_COLUMNS = 64
    private const val MAX_REPORT_ROWS = 100_000
}

private fun SupportSQLiteDatabase.operationInternalId(uid: StableId): Long? = query("SELECT id FROM background_operation WHERE uid=?", arrayOf(uid.bytes)).use { cursor ->
    if (cursor.moveToFirst()) cursor.getLong(0) else null
}

private fun SupportSQLiteDatabase.nextOperationId(): Long = query("SELECT COALESCE(MAX(id),0)+1 FROM background_operation").use { cursor ->
    check(cursor.moveToFirst())
    cursor.getLong(0)
}

private fun Cursor.nullableLong(index: Int): Long? = if (isNull(index)) null else getLong(index)
private fun Cursor.nullableString(index: Int): String? = if (isNull(index)) null else getString(index)
