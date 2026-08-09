package app.ledger.transfer.data

import androidx.sqlite.db.SupportSQLiteDatabase
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.security.SecurePrimaryLedgerAccess
import app.ledger.transfer.domain.BackupRepositoryId
import app.ledger.transfer.domain.BackupSnapshotId
import app.ledger.transfer.domain.DriveUploadState
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Instant

data class DurableDriveBackupSession(
    val sessionId: StableId,
    val operationId: StableId,
    val snapshotId: BackupSnapshotId,
    val repositoryId: BackupRepositoryId,
    val state: DriveUploadState,
    val current: DriveResumableCheckpoint?,
    val uploadedOpaqueNames: Set<String>,
    val manifestPublished: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)

interface DriveBackupSessionStore {
    fun save(value: DurableDriveBackupSession)
    fun read(sessionId: StableId, operationId: StableId): DurableDriveBackupSession?
}

class SqlCipherDriveCheckpointStore(
    private val bookId: StableId,
    private val access: SecurePrimaryLedgerAccess,
) : DriveBackupSessionStore {
    override fun save(value: DurableDriveBackupSession) {
        val encoded = encode(value)
        val encrypted = try {
            access.seal(bookId, value.operationId, PURPOSE, encoded)
        } finally {
            encoded.fill(0)
        }
        try {
            access.write(bookId) { database ->
                val internalId = database.query("SELECT id FROM drive_upload_session WHERE uid=?", arrayOf(value.sessionId.bytes)).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getLong(0) else database.nextDriveSessionId()
                }
                database.execSQL(
                    "INSERT OR REPLACE INTO drive_upload_session(" +
                        "id,uid,snapshot_id,repository_id,state,remote_session_ciphertext,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",
                    arrayOf(
                        internalId,
                        value.sessionId.bytes,
                        database.requiredId("backup_snapshot", value.snapshotId.value),
                        database.requiredId("backup_repository", value.repositoryId.value),
                        value.state.ordinal,
                        encrypted,
                        value.createdAt.toEpochMilli(),
                        value.updatedAt.toEpochMilli(),
                    ),
                )
            }
        } finally {
            encrypted.fill(0)
        }
    }

    override fun read(sessionId: StableId, operationId: StableId): DurableDriveBackupSession? {
        val stored = access.read(bookId) { database ->
            database.query("SELECT remote_session_ciphertext FROM drive_upload_session WHERE uid=?", arrayOf(sessionId.bytes)).use { cursor ->
                if (cursor.moveToFirst()) cursor.getBlob(0) else null
            }
        } ?: return null
        val plaintext = access.open(bookId, operationId, PURPOSE, stored)
        return try {
            decode(plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun encode(value: DurableDriveBackupSession): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(MAGIC)
            output.id(value.sessionId)
            output.id(value.operationId)
            output.id(value.snapshotId.value)
            output.id(value.repositoryId.value)
            output.writeInt(value.state.ordinal)
            output.writeBoolean(value.current != null)
            value.current?.let { current ->
                output.id(current.operationId)
                output.writeUTF(current.opaqueObjectName)
                output.writeUTF(current.sessionUrl)
                output.writeLong(current.nextByte)
                output.writeLong(current.totalBytes)
                output.writeBoolean(current.remoteFileId != null)
                current.remoteFileId?.let(output::writeUTF)
                output.writeBoolean(current.complete)
            }
            output.writeInt(value.uploadedOpaqueNames.size)
            value.uploadedOpaqueNames.sorted().forEach(output::writeUTF)
            output.writeBoolean(value.manifestPublished)
            output.writeLong(value.createdAt.toEpochMilli())
            output.writeLong(value.updatedAt.toEpochMilli())
        }
        bytes.toByteArray()
    }

    private fun decode(value: ByteArray): DurableDriveBackupSession = DataInputStream(ByteArrayInputStream(value)).use { input ->
        require(input.readInt() == MAGIC)
        val sessionId = input.id()
        val operationId = input.id()
        val snapshotId = BackupSnapshotId(input.id())
        val repositoryId = BackupRepositoryId(input.id())
        val state = DriveUploadState.entries[input.readInt()]
        val current = if (input.readBoolean()) {
            DriveResumableCheckpoint(
                input.id(),
                input.readUTF(),
                input.readUTF(),
                input.readLong(),
                input.readLong(),
                if (input.readBoolean()) input.readUTF() else null,
                input.readBoolean(),
            )
        } else {
            null
        }
        val uploadedCount = input.readInt()
        require(uploadedCount in 0..MAX_OBJECTS)
        val uploaded = buildSet { repeat(uploadedCount) { add(input.readUTF()) } }
        val manifestPublished = input.readBoolean()
        val createdAt = Instant.ofEpochMilli(input.readLong())
        val updatedAt = Instant.ofEpochMilli(input.readLong())
        require(input.read() == -1)
        DurableDriveBackupSession(
            sessionId,
            operationId,
            snapshotId,
            repositoryId,
            state,
            current,
            uploaded,
            manifestPublished,
            createdAt,
            updatedAt,
        )
    }

    private fun DataOutputStream.id(value: StableId) = write(value.bytes)
    private fun DataInputStream.id(): StableId = StableId.fromBytes(ByteArray(StableId.BYTE_COUNT).also(::readFully)).required()

    private companion object {
        const val MAGIC = 0x4c445553
        const val PURPOSE = "drive-upload"
        const val MAX_OBJECTS = 2_000_000
    }
}

private fun SupportSQLiteDatabase.nextDriveSessionId(): Long = query("SELECT COALESCE(MAX(id),0)+1 FROM drive_upload_session").use {
    check(it.moveToFirst())
    it.getLong(0)
}

private fun SupportSQLiteDatabase.requiredId(table: String, uid: StableId): Long = query("SELECT id FROM $table WHERE uid=?", arrayOf(uid.bytes)).use {
    check(it.moveToFirst())
    it.getLong(0)
}

private fun <T> DomainResult<T>.required(): T = (this as? DomainResult.Success)?.value ?: error("invalid Drive checkpoint")
