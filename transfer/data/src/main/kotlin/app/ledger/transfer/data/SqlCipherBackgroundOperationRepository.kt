@file:Suppress("LongMethod", "MagicNumber", "NestedBlockDepth", "TooGenericExceptionCaught")

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
import app.ledger.transfer.domain.ExportFormat
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
                    output.writeInt(parameters.format.ordinal)
                    output.writeBoolean(parameters.includeAttachments)
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
            1 -> OperationParameters.Export(input.id(), ExportFormat.entries[input.readInt()], input.readBoolean())
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
