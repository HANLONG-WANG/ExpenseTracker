package app.ledger.transfer.data

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.security.LedgerTink
import app.ledger.finance.domain.Hash256
import app.ledger.transfer.domain.BackupObjectKind
import app.ledger.transfer.domain.BackupRepositoryId
import app.ledger.transfer.domain.BackupSnapshotId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SafBackupRepositoryDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val tree = DocumentsContract.buildTreeDocumentUri(P29TestDocumentsProvider.AUTHORITY, P29TestDocumentsProvider.ROOT_ID)

    @Before
    fun setUp() {
        P29TestDocumentsProvider.permissionRevoked = false
        context.contentResolver.acquireContentProviderClient(P29TestDocumentsProvider.AUTHORITY)?.close()
        context.grantUriPermission(context.packageName, tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        requireNotNull(DocumentFile.fromTreeUri(context, tree)).listFiles().forEach(DocumentFile::delete)
    }

    @After
    fun tearDown() {
        P29TestDocumentsProvider.permissionRevoked = false
        context.revokeUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    @Test
    fun repositoryAndPortablePublicationAreAtomicAndRevocationIsExplicit() {
        val repository = SafBackupRepositoryStorage(context, tree)
        repository.writeAtomically(BackupStorageArea.OBJECTS, OBJECT_NAME) { it.write("encrypted-object-v1".toByteArray()) }
        assertEquals("encrypted-object-v1", repository.open(BackupStorageArea.OBJECTS, OBJECT_NAME).bufferedReader().use { it.readText() })
        repository.writeAtomically(BackupStorageArea.OBJECTS, OBJECT_NAME) { it.write("encrypted-object-v2".toByteArray()) }
        assertEquals("encrypted-object-v2", repository.open(BackupStorageArea.OBJECTS, OBJECT_NAME).bufferedReader().use { it.readText() })
        assertTrue(repository.names(BackupStorageArea.OBJECTS).none { it.contains("partial") || it.contains("previous") })

        val key = LedgerTink.generateStreamingAeadKeyset()
        try {
            val bytes = "SQLCipher-日本語-history".repeat(1_000).toByteArray()
            val input = PortableBackupInput(
                BOOK,
                REPOSITORY,
                SNAPSHOT,
                byteArrayOf(1, 2, 3),
                bytes.source("database/ledger.db", BackupObjectKind.DATABASE_CHUNK),
                listOf("settings".toByteArray().source("settings/app.pb", BackupObjectKind.SETTINGS)),
                emptyList(),
                byteArrayOf(4, 5).source("keys/portable.envelope", BackupObjectKind.KEY_ENVELOPE),
                null,
            )
            val destination = SafPortableBackupDestination(context, tree, "ledger.ledger-backup")
            assertTrue(destination.writeAndPublish { output -> PortableBackupWriter().write(input, key, output) } is DomainResult.Success)
            val root = requireNotNull(DocumentFile.fromTreeUri(context, tree))
            assertTrue(requireNotNull(root.findFile("ledger.ledger-backup")).length() > 0L)
            assertFalse(root.listFiles().any { it.name?.contains("partial") == true || it.name?.contains("previous") == true })
        } finally {
            key.close()
        }

        P29TestDocumentsProvider.permissionRevoked = true
        assertTrue(runCatching { repository.open(BackupStorageArea.OBJECTS, OBJECT_NAME) }.exceptionOrNull() is SecurityException)
    }

    private fun ByteArray.source(name: String, kind: BackupObjectKind): ReopenableBackupSource {
        val stored = copyOf()
        val hash = Hash256.fromBytes(MessageDigest.getInstance("SHA-256").digest(stored)).success()
        return ReopenableBackupSource(name, kind, stored.size.toLong(), hash) { ByteArrayInputStream(stored) }
    }

    private fun <T> DomainResult<T>.success(): T = (this as DomainResult.Success).value

    private companion object {
        const val OBJECT_NAME = "0123456789abcdef0123456789abcdef.object"
        val BOOK = StableId.fromUuid(UUID(30, 500))
        val REPOSITORY = BackupRepositoryId(StableId.fromUuid(UUID(30, 501)))
        val SNAPSHOT = BackupSnapshotId(StableId.fromUuid(UUID(30, 502)))
    }
}
