@file:Suppress("ReturnCount")

package app.ledger.core.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.common.StableIdSource
import app.ledger.core.database.EncryptedDatabaseFactory
import app.ledger.core.database.LedgerDatabase
import app.ledger.core.security.AndroidKeystoreKeys
import app.ledger.core.security.DeviceKeyHierarchy
import app.ledger.core.security.DeviceLedgerKeys
import app.ledger.core.security.SecurityEnvelopeStore
import app.ledger.finance.application.AttachmentContentSource
import app.ledger.finance.application.AttachmentImportRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

@RunWith(AndroidJUnit4::class)
class EncryptedAttachmentInfrastructureDeviceTest {
    private lateinit var context: Context
    private lateinit var hierarchy: DeviceKeyHierarchy
    private lateinit var keys: DeviceLedgerKeys
    private lateinit var database: LedgerDatabase

    @Before
    fun prepare() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME)
        File(context.noBackupFilesDir, "attachment_objects/${BOOK_ID.toUuid()}").deleteRecursively()
        hierarchy = DeviceKeyHierarchy(AndroidKeystoreKeys(context), SecurityEnvelopeStore(context))
        hierarchy.destroyLocal(BOOK_ID)
        hierarchy.initialize(BOOK_ID)
        keys = hierarchy.open(BOOK_ID)
        database = keys.databaseDek.useBytes { EncryptedDatabaseFactory.openPrimary(context, it) }
        database.openHelper.writableDatabase
    }

    @After
    fun cleanUp() {
        database.close()
        keys.close()
        hierarchy.destroyLocal(BOOK_ID)
        context.deleteDatabase(EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME)
        File(context.noBackupFilesDir, "attachment_objects/${BOOK_ID.toUuid()}").deleteRecursively()
    }

    @Test
    fun largeImportStreamsEncryptsDeduplicatesRenamesAndGarbageCollects() = runBlocking {
        val bytes = deterministicBytes(LARGE_FILE_BYTES)
        val store = store(SequentialStableIdSource())
        val progress = mutableListOf<AttachmentImportProgress>()

        val first = store.import(request("statement-secret.bin", bytes), progress::add).success()
        val second = store.import(request("copy.bin", bytes)).success()

        assertEquals(bytes.size.toLong(), first.plaintextSize)
        assertEquals(first.blobId, second.blobId)
        assertTrue(progress.zipWithNext().all { (left, right) -> left.processedBytes <= right.processedBytes })
        assertEquals(bytes.size.toLong(), progress.last().processedBytes)
        store.openOriginal(first.attachmentId).use { decrypted ->
            assertArrayEquals(bytes, decrypted.plaintext.readBytes())
        }
        val stored = checkNotNull(store.storedObject(first.attachmentId))
        assertFalse(stored.storageName.contains("statement"))
        assertFalse(stored.storageName.contains(first.plaintextHash.bytes.toHex()))
        assertNotEquals(bytes.toList(), store.privateStorage().objectFile(stored.storageName).readBytes().toList())
        assertFalse(store.privateStorage().objectFile(stored.storageName).readBytes().containsSubsequence(SENSITIVE_SENTINEL))
        assertEquals(1, encryptedObjectFiles(store).size)

        val renamed = store.rename(first.attachmentId, "renamed/private.txt").success()
        assertEquals("renamed private.txt", renamed.displayName)
        assertEquals(1, encryptedObjectFiles(store).size)

        store.discardUncommittedAttachment(first.attachmentId).success()
        assertEquals(0, store.runGarbageCollection().deletedObjects)
        store.discardUncommittedAttachment(second.attachmentId).success()
        store.removeUnreferenced(first.blobId).success()
        assertTrue(encryptedObjectFiles(store).isEmpty())
    }

    @Test
    fun cancellationDatabaseFailureAndInterruptedProcessLeaveNoReferencedMissingObject() = runBlocking {
        val duplicateAttachmentId = stableId(0x2002)
        val ids = QueueStableIdSource(
            stableId(0x2001),
            duplicateAttachmentId,
            stableId(0x2003),
            duplicateAttachmentId,
            stableId(0x2004),
            stableId(0x2005),
        )
        val store = store(ids)
        store.import(request("first.bin", byteArrayOf(1, 2, 3))).success()
        val beforeObjects = encryptedObjectFiles(store).map(File::getName).toSet()

        val databaseFailure = store.import(request("second.bin", byteArrayOf(4, 5, 6)))
        assertEquals(AttachmentInfrastructureError.DATABASE_FAILURE, databaseFailure.failure())
        assertEquals(beforeObjects, encryptedObjectFiles(store).map(File::getName).toSet())
        assertDatabaseHasNoMissingObject(store)

        assertThrows(CancellationException::class.java) {
            runBlocking {
                store.import(
                    AttachmentImportRequest(
                        displayName = "cancel.bin",
                        mimeType = "application/octet-stream",
                        extension = "bin",
                        declaredSize = null,
                        content = AttachmentContentSource { CancellingInputStream() },
                    ),
                )
            }
        }
        assertEquals(0L, databaseCount("SELECT COUNT(*) FROM attachment WHERE display_name = 'cancel.bin'"))

        val staging = store.privateStorage().newStagingFile().apply { writeBytes(byteArrayOf(9, 8, 7)) }
        val orphanName = store.privateStorage().nextStorageName()
        store.privateStorage().moveIntoObjectStore(staging, orphanName)
        val recovery = store.recoverInterruptedImports()
        assertEquals(1, recovery.deletedOrphanObjects)
        assertFalse(store.privateStorage().objectFile(orphanName).exists())
        assertDatabaseHasNoMissingObject(store)
    }

    @Test
    fun thumbnailAndProviderRemainEncryptedPrivateOneTimeAndLockAware() = runBlocking {
        val png = createPng()
        val store = store(SequentialStableIdSource())
        val receipt = store.import(request("private-image.png", png, "image/png")).success()
        assertTrue(store.generateEncryptedThumbnail(receipt.attachmentId).success())
        val stored = checkNotNull(store.storedObject(receipt.attachmentId))
        val encryptedThumbnail = store.privateStorage().thumbnail(stored.storageName)
        assertTrue(encryptedThumbnail.isFile)
        assertFalse(encryptedThumbnail.readBytes().copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE))
        store.openThumbnail(receipt.attachmentId).use { decrypted ->
            assertNotNull(BitmapFactory.decodeStream(decrypted?.plaintext))
        }

        val imageLoader = SecureAttachmentImageLoader(context, store)
        val runtime = SecureAttachmentProviderRuntime(
            elapsedRealtimeMillis = { android.os.SystemClock.elapsedRealtime() },
        )
        SecureAttachmentProviderProcess.install(runtime)
        try {
            runtime.onBookReady(store, imageLoader)
            val confirmation = checkNotNull(SecureAttachmentExternalOpen(context, runtime).beginConfirmation(receipt.attachmentId))
            val authorization = confirmation.authorize()
            assertEquals("content", authorization.intent.data?.scheme)
            assertTrue(authorization.intent.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            val uri = checkNotNull(authorization.intent.data)
            context.contentResolver.query(uri, null, null, null, null).use { cursor ->
                assertTrue(checkNotNull(cursor).moveToFirst())
                assertEquals("private-image.png", cursor.getString(cursor.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME)))
            }
            assertArrayEquals(png, context.contentResolver.openInputStream(uri)?.use(InputStream::readBytes))
            assertThrows(Exception::class.java) { context.contentResolver.openInputStream(uri) }

            val lockedGrant = checkNotNull(SecureAttachmentExternalOpen(context, runtime).beginConfirmation(receipt.attachmentId)).authorize()
            runtime.onApplicationLocked()
            assertThrows(Exception::class.java) { context.contentResolver.openInputStream(checkNotNull(lockedGrant.intent.data)) }
            assertEquals(null, SecureAttachmentExternalOpen(context, runtime).beginConfirmation(receipt.attachmentId))
        } finally {
            SecureAttachmentProviderProcess.uninstall(runtime)
            runtime.close()
        }

        val attachmentRoot = File(context.noBackupFilesDir, "attachment_objects/${BOOK_ID.toUuid()}")
        assertTrue(attachmentRoot.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath))
        assertFalse(attachmentRoot.walkTopDown().filter(File::isFile).any { it.readBytes().containsSubsequence(SENSITIVE_SENTINEL) })
    }

    private fun store(source: StableIdSource): EncryptedAttachmentObjectStore = EncryptedAttachmentObjectStore(
        context = context,
        bookId = BOOK_ID,
        keys = keys,
        database = database,
        stableIdSource = source,
        clock = CLOCK,
    )

    private fun request(name: String, bytes: ByteArray, mimeType: String = "application/octet-stream") = AttachmentImportRequest(
        displayName = name,
        mimeType = mimeType,
        extension = name.substringAfterLast('.', "").ifBlank { null },
        declaredSize = bytes.size.toLong(),
        content = AttachmentContentSource { ByteArrayInputStream(bytes) },
    )

    private fun encryptedObjectFiles(store: EncryptedAttachmentObjectStore): List<File> {
        val first = store.privateStorage().nextStorageName()
        return store.privateStorage().objectFile(first).parentFile?.listFiles().orEmpty().filter { it.extension == "blob" }
    }

    private fun assertDatabaseHasNoMissingObject(store: EncryptedAttachmentObjectStore) {
        database.readLedger { connection ->
            connection.query("SELECT storage_name FROM encrypted_blob").use { cursor ->
                while (cursor.moveToNext()) assertTrue(store.privateStorage().objectFile(cursor.getString(0)).isFile)
            }
        }
    }

    private fun databaseCount(sql: String): Long = database.readLedger { connection ->
        connection.query(sql).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getLong(0)
        }
    }

    private fun createPng(): ByteArray = ByteArrayOutputStream().use { output ->
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(0xff126e82.toInt())
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private class SequentialStableIdSource : StableIdSource {
        private val next = AtomicLong(0x1000)
        override fun nextStableId(): StableId = stableId(next.incrementAndGet())
    }

    private class QueueStableIdSource(vararg values: StableId) : StableIdSource {
        private val queue = ArrayDeque(values.toList())
        override fun nextStableId(): StableId = queue.removeFirst()
    }

    private class CancellingInputStream : InputStream() {
        private var first = true
        override fun read(): Int = throw CancellationException("test cancellation")
        override fun read(destination: ByteArray, offset: Int, length: Int): Int {
            if (!first) throw CancellationException("test cancellation")
            first = false
            destination[offset] = 1
            return 1
        }
    }

    private fun deterministicBytes(size: Int): ByteArray = ByteArray(size) { index -> (index * 31).toByte() }.also { value ->
        SENSITIVE_SENTINEL.copyInto(value, destinationOffset = size / 2)
    }

    private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
        if (needle.isEmpty()) return true
        for (start in 0..size - needle.size) {
            var equal = true
            for (offset in needle.indices) {
                if (this[start + offset] != needle[offset]) {
                    equal = false
                    break
                }
            }
            if (equal) return true
        }
        return false
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun <T> DomainResult<T>.success(): T = when (this) {
        is DomainResult.Success -> value
        is DomainResult.Failure -> error("expected success but was ${error.code}")
    }

    private fun DomainResult<*>.failure() = when (this) {
        is DomainResult.Success -> error("expected failure")
        is DomainResult.Failure -> error
    }

    private companion object {
        val BOOK_ID: StableId = stableId(0xA010)
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-08-02T00:00:00Z"), ZoneOffset.UTC)
        val SENSITIVE_SENTINEL: ByteArray = "P10-plaintext-sensitive-marker".toByteArray(StandardCharsets.UTF_8)
        val PNG_SIGNATURE: ByteArray = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        const val LARGE_FILE_BYTES = 5 * 1024 * 1024

        fun stableId(value: Long): StableId = StableId.fromUuid(UUID(0L, value))
    }
}
