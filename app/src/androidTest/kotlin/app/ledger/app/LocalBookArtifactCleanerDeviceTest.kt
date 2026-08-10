package app.ledger.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ledger.core.common.StableId
import app.ledger.core.security.DeviceLedgerKeyProvider
import app.ledger.core.security.DeviceLedgerKeys
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalBookArtifactCleanerDeviceTest {
    @Test
    fun g004ClearRemovesEveryAppOwnedDerivedArtifactButLeavesUserControlledExternalFiles() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bookId = StableId.fromBytes(ByteArray(StableId.BYTE_COUNT) { 0x31 }).valueOrNull()
        val operation = "0123456789abcdef0123456789abcdef"
        val roots = listOf(
            context.noBackupFilesDir.resolve("attachment_objects/$bookId"),
            context.noBackupFilesDir.resolve("backup-repositories/$bookId"),
            context.noBackupFilesDir.resolve("pre-restore-safety-v1/$bookId"),
            context.noBackupFilesDir.resolve("restore-work-v1"),
            context.noBackupFilesDir.resolve("backup-progress-v1"),
        )
        roots.forEach { directory ->
            assertTrue(directory.mkdirs() || directory.isDirectory)
            directory.resolve("sensitive-artifact").writeText("ciphertext")
        }
        val descriptors = listOf(
            context.filesDir.resolve("restore-artifacts-$operation.descriptor"),
            context.filesDir.resolve("restore-artifacts-$operation.marker"),
            context.filesDir.resolve(".restore-$operation.rollback"),
        )
        descriptors.forEach { it.writeText("opaque") }
        val databases = listOf("import_$operation.db", "ledger_shadow_$operation.db", "ledger_safety_$operation.db")
        databases.forEach { context.openOrCreateDatabase(it, Context.MODE_PRIVATE, null).close() }
        val external = requireNotNull(context.getExternalFilesDir(null)).resolve("user-controlled-export.csv")
        external.writeText("must survive")

        assertTrue(LocalBookArtifactCleaner(context, UNUSED_KEYS).clear(bookId, null))

        roots.forEach { assertFalse(it.exists()) }
        descriptors.forEach { assertFalse(it.exists()) }
        databases.forEach { assertFalse(context.getDatabasePath(it).exists()) }
        assertTrue(external.isFile)
        assertTrue(external.delete())
    }

    private fun <T> app.ledger.core.common.DomainResult<T>.valueOrNull(): T = (this as app.ledger.core.common.DomainResult.Success).value

    private companion object {
        val UNUSED_KEYS = object : DeviceLedgerKeyProvider {
            override fun initialize(bookId: StableId) = error("not used")
            override fun open(bookId: StableId): DeviceLedgerKeys = error("not used")
            override fun destroyLocal(bookId: StableId) = error("not used")
        }
    }
}
