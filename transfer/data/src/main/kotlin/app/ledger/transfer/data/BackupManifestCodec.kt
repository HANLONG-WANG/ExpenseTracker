package app.ledger.transfer.data

import app.ledger.core.common.StableId
import app.ledger.finance.domain.Hash256
import app.ledger.finance.domain.LocalRevision
import app.ledger.transfer.domain.BackupFormatContract
import app.ledger.transfer.domain.BackupManifestObject
import app.ledger.transfer.domain.BackupObjectId
import app.ledger.transfer.domain.BackupObjectKind
import app.ledger.transfer.domain.BackupRepositoryHeader
import app.ledger.transfer.domain.BackupRepositoryId
import app.ledger.transfer.domain.BackupSnapshotId
import app.ledger.transfer.domain.BackupSnapshotManifest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Instant

object BackupManifestCodec {
    fun encode(value: BackupSnapshotManifest): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(MANIFEST_MAGIC)
            output.writeInt(value.schemaVersion)
            output.id(value.snapshotId.value)
            output.id(value.repositoryId.value)
            output.id(value.bookId)
            output.writeLong(value.localRevision.value)
            output.writeLong(value.createdAt.toEpochMilli())
            output.safeUtf(value.applicationVersion)
            output.writeInt(value.databaseSchemaVersion)
            output.writeBoolean(value.includesSettings)
            output.writeBoolean(value.includesAttachments)
            output.writeBoolean(value.includesHistory)
            output.writeBoolean(value.includesVault)
            output.writeLong(value.logicalBytes)
            output.writeLong(value.physicalIncrementBytes)
            output.writeInt(value.objects.size)
            value.objects.forEach { item ->
                output.id(item.id.value)
                output.writeInt(item.kind.ordinal)
                output.safeUtf(item.storageName)
                output.safeUtf(item.logicalName)
                output.write(item.plaintextHash.bytes)
                output.writeLong(item.plaintextSize)
                output.writeLong(item.ordinal)
            }
        }
        bytes.toByteArray()
    }

    fun decode(encoded: ByteArray): BackupSnapshotManifest = DataInputStream(ByteArrayInputStream(encoded)).use { input ->
        require(input.readInt() == MANIFEST_MAGIC)
        val version = input.readInt()
        require(version == BackupFormatContract.MANIFEST_SCHEMA_VERSION)
        val snapshotId = BackupSnapshotId(input.id())
        val repositoryId = BackupRepositoryId(input.id())
        val bookId = input.id()
        val revision = LocalRevision.of(input.readLong()).required()
        val createdAt = Instant.ofEpochMilli(input.readLong())
        val appVersion = input.safeUtf()
        val databaseVersion = input.readInt()
        val includesSettings = input.readBoolean()
        val includesAttachments = input.readBoolean()
        val includesHistory = input.readBoolean()
        val includesVault = input.readBoolean()
        val logicalBytes = input.readLong()
        val physicalBytes = input.readLong()
        val count = input.readInt()
        require(count in 1..MAX_OBJECTS)
        val objects = List(count) {
            BackupManifestObject(
                BackupObjectId(input.id()),
                BackupObjectKind.entries[input.readInt()],
                input.safeUtf(),
                input.safeUtf(),
                Hash256.fromBytes(ByteArray(Hash256.BYTE_COUNT).also(input::readFully)).required(),
                input.readLong(),
                input.readLong(),
            )
        }
        require(input.read() == -1)
        BackupSnapshotManifest(
            version,
            snapshotId,
            repositoryId,
            bookId,
            revision,
            createdAt,
            appVersion,
            databaseVersion,
            includesSettings,
            includesAttachments,
            includesHistory,
            includesVault,
            logicalBytes,
            physicalBytes,
            objects,
        )
    }

    fun encodeHeader(value: BackupRepositoryHeader): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(HEADER_MAGIC)
            output.writeInt(value.schemaVersion)
            output.id(value.repositoryId.value)
            output.writeLong(value.createdAt.toEpochMilli())
            output.writeInt(value.recoveryKeyEnvelope.size)
            output.write(value.recoveryKeyEnvelope)
        }
        bytes.toByteArray()
    }

    fun decodeHeader(encoded: ByteArray): BackupRepositoryHeader = DataInputStream(ByteArrayInputStream(encoded)).use { input ->
        require(input.readInt() == HEADER_MAGIC)
        val version = input.readInt()
        val repositoryId = BackupRepositoryId(input.id())
        val createdAt = Instant.ofEpochMilli(input.readLong())
        val envelopeSize = input.readInt()
        require(envelopeSize in 1..MAX_ENVELOPE_BYTES)
        val envelope = ByteArray(envelopeSize).also(input::readFully)
        require(input.read() == -1)
        BackupRepositoryHeader(repositoryId, version, createdAt, envelope)
    }

    private fun DataOutputStream.id(value: StableId) = write(value.bytes)
    private fun DataInputStream.id(): StableId = StableId.fromBytes(ByteArray(StableId.BYTE_COUNT).also(::readFully)).required()

    private fun DataOutputStream.safeUtf(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_STRING_BYTES)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.safeUtf(): String {
        val size = readInt()
        require(size in 1..MAX_STRING_BYTES)
        return ByteArray(size).also(::readFully).toString(Charsets.UTF_8)
    }

    private fun <T> app.ledger.core.common.DomainResult<T>.required(): T = (this as? app.ledger.core.common.DomainResult.Success)?.value ?: error("invalid backup manifest value")

    private const val MANIFEST_MAGIC = 0x4c424d46
    private const val HEADER_MAGIC = 0x4c425248
    private const val MAX_OBJECTS = 2_000_000
    private const val MAX_STRING_BYTES = 1024
    private const val MAX_ENVELOPE_BYTES = 1024 * 1024
}
