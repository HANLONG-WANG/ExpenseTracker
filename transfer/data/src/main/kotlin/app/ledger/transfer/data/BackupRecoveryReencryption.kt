@file:Suppress("LongParameterList", "LoopWithTooManyJumpStatements", "MagicNumber")

package app.ledger.transfer.data

import app.ledger.core.common.DomainResult
import app.ledger.core.security.SecretBytes
import app.ledger.transfer.domain.BackupFailure
import app.ledger.transfer.domain.BackupRepositoryHeader
import app.ledger.transfer.domain.BackupRepositoryId
import app.ledger.transfer.domain.BackupSnapshotId
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream

/** Re-publishes only recovery envelopes; encrypted database/attachment objects are never decrypted. */
class BackupRecoveryReencryption {
    suspend fun rewriteAccessibleHistory(
        storage: BackupRepositoryStorage,
        repositoryId: BackupRepositoryId,
        snapshotIds: List<BackupSnapshotId>,
        repositoryKey: SecretBytes,
        newRecoveryEnvelope: ByteArray,
        cancelled: () -> Boolean = { false },
        progress: suspend (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): DomainResult<Int> = try {
        require(newRecoveryEnvelope.isNotEmpty())
        snapshotIds.forEachIndexed { index, snapshotId ->
            if (cancelled()) return DomainResult.Failure(BackupFailure.Cancelled)
            val name = snapshotId.manifestName()
            storage.open(BackupStorageArea.SNAPSHOTS, name).use { raw ->
                val source = DataInputStream(raw)
                val magic = ByteArray(MANIFEST_MAGIC.size).also(source::readFully)
                require(magic.contentEquals(MANIFEST_MAGIC))
                val oldEnvelopeSize = source.readInt()
                require(oldEnvelopeSize in 1..MAX_ENVELOPE_BYTES)
                ByteArray(oldEnvelopeSize).also(source::readFully).fill(0)
                storage.writeAtomically(BackupStorageArea.SNAPSHOTS, name) { rawOutput ->
                    val output = DataOutputStream(rawOutput)
                    output.write(MANIFEST_MAGIC)
                    output.writeInt(newRecoveryEnvelope.size)
                    output.write(newRecoveryEnvelope)
                    source.copyTo(output, COPY_BUFFER_BYTES)
                    output.flush()
                }
            }
            BackupRepositoryInspector().readManifest(storage, repositoryId, snapshotId, repositoryKey)
            progress(index + 1, snapshotIds.size)
        }
        if (cancelled()) return DomainResult.Failure(BackupFailure.Cancelled)
        val currentHeader = storage.open(BackupStorageArea.ROOT, REPOSITORY_HEADER).use { source ->
            BackupManifestCodec.decodeHeader(source.readBounded(MAX_ENVELOPE_BYTES + HEADER_OVERHEAD_BYTES))
        }
        require(currentHeader.repositoryId == repositoryId)
        val updated = BackupRepositoryHeader(
            repositoryId,
            currentHeader.schemaVersion,
            currentHeader.createdAt,
            newRecoveryEnvelope.copyOf(),
        )
        storage.writeAtomically(BackupStorageArea.ROOT, REPOSITORY_HEADER) { output ->
            output.write(BackupManifestCodec.encodeHeader(updated))
        }
        DomainResult.Success(snapshotIds.size)
    } catch (_: SecurityException) {
        DomainResult.Failure(BackupFailure.PermissionRevoked)
    } catch (_: Exception) {
        DomainResult.Failure(BackupFailure.RepositoryUnavailable)
    }

    private fun BackupSnapshotId.manifestName(): String = value.bytes.toHex() + ".manifest"
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun InputStream.readBounded(maxBytes: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var total = 0
        try {
            while (true) {
                val count = read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total = Math.addExact(total, count)
                require(total <= maxBytes) { "backup repository header exceeds bound" }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        } finally {
            buffer.fill(0)
        }
    }

    private companion object {
        val MANIFEST_MAGIC = byteArrayOf(0x4c, 0x42, 0x53, 0x4d)
        const val REPOSITORY_HEADER = "repository-header.header"
        const val MAX_ENVELOPE_BYTES = 1024 * 1024
        const val HEADER_OVERHEAD_BYTES = 64
        const val COPY_BUFFER_BYTES = 64 * 1024
    }
}
