@file:Suppress("LoopWithTooManyJumpStatements", "MagicNumber", "MaxLineLength")

package app.ledger.transfer.data

import app.ledger.core.security.LedgerTink
import app.ledger.core.security.SecretBytes
import app.ledger.transfer.domain.BackupRepositoryId
import app.ledger.transfer.domain.BackupSnapshotId
import app.ledger.transfer.domain.BackupSnapshotManifest
import java.io.DataInputStream
import java.io.InputStream

/** Reads bounded encrypted manifests without loading any database or attachment object. */
class BackupRepositoryInspector {
    fun readManifest(
        storage: BackupRepositoryStorage,
        repositoryId: BackupRepositoryId,
        snapshotId: BackupSnapshotId,
        repositoryKey: SecretBytes,
    ): BackupSnapshotManifest {
        val primitive = repositoryKey.useBytes(LedgerTink::streamingAead)
        return storage.open(BackupStorageArea.SNAPSHOTS, snapshotId.manifestName()).use { raw ->
            val source = DataInputStream(raw)
            require(ByteArray(MANIFEST_MAGIC.size).also(source::readFully).contentEquals(MANIFEST_MAGIC))
            val envelopeSize = source.readInt()
            require(envelopeSize in 1..MAX_ENVELOPE_BYTES)
            ByteArray(envelopeSize).also(source::readFully).fill(0)
            val associatedData = associatedData(repositoryId, snapshotId)
            try {
                primitive.newDecryptingStream(source, associatedData).use { encrypted ->
                    BackupManifestCodec.decode(encrypted.readBounded(MAX_MANIFEST_BYTES))
                }
            } finally {
                associatedData.fill(0)
            }
        }
    }

    private fun InputStream.readBounded(maxBytes: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0
        try {
            while (true) {
                val count = read(buffer)
                if (count < 0) break
                if (count == 0) continue
                total = Math.addExact(total, count)
                require(total <= maxBytes) { "backup manifest exceeds bound" }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        } finally {
            buffer.fill(0)
        }
    }

    private fun associatedData(repositoryId: BackupRepositoryId, snapshotId: BackupSnapshotId): ByteArray = "ledger-backup-manifest-v1\u0000".toByteArray(Charsets.US_ASCII) + repositoryId.value.bytes + snapshotId.value.bytes

    private fun BackupSnapshotId.manifestName(): String = value.bytes.toHex() + ".manifest"
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        val MANIFEST_MAGIC = byteArrayOf(0x4c, 0x42, 0x53, 0x4d)
        const val MAX_ENVELOPE_BYTES = 1024 * 1024
        const val MAX_MANIFEST_BYTES = 256 * 1024 * 1024
    }
}
