package app.ledger.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.common.getOrNull
import app.ledger.core.money.CurrencyCode
import app.ledger.core.security.AndroidKeystoreKeys
import app.ledger.core.security.DeviceKeyHierarchy
import app.ledger.core.security.LedgerTink
import app.ledger.core.security.SecurePrimaryLedgerAccess
import app.ledger.core.security.SecurityEnvelopeStore
import app.ledger.finance.application.InitializeLedgerCommand
import app.ledger.finance.application.LedgerGenesisIds
import app.ledger.finance.data.SecureRoomLedgerInitializationPort
import app.ledger.finance.data.SecureShadowLedgerAccess
import app.ledger.finance.domain.BookCommitId
import app.ledger.finance.domain.Hash256
import app.ledger.finance.domain.LocalRevision
import app.ledger.finance.domain.SystemLedgerCode
import app.ledger.transfer.data.BackupRepositoryInspector
import app.ledger.transfer.data.BackupStorageArea
import app.ledger.transfer.data.FileBackupRepositoryStorage
import app.ledger.transfer.data.ManagedBackupInput
import app.ledger.transfer.data.ManagedBackupRepositoryEngine
import app.ledger.transfer.data.ReopenableBackupSource
import app.ledger.transfer.domain.BackupObjectKind
import app.ledger.transfer.domain.BackupProgress
import app.ledger.transfer.domain.BackupProgressObserver
import app.ledger.transfer.domain.BackupRepositoryId
import app.ledger.transfer.domain.BackupRepositoryKind
import app.ledger.transfer.domain.BackupRetentionPolicy
import app.ledger.transfer.domain.BackupSnapshotId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ManagedBackupSqlCipherDeviceTest {
    private lateinit var context: Context
    private lateinit var keys: DeviceKeyHierarchy
    private lateinit var databaseAccess: DeviceTestLedgerDatabaseAccess
    private lateinit var root: File

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        keys = DeviceKeyHierarchy(AndroidKeystoreKeys(context), SecurityEnvelopeStore(context))
        databaseAccess = DeviceTestLedgerDatabaseAccess(context, keys)
        SecureRoomLedgerInitializationPort(context, keys).clearLocalBook(BOOK).success()
        root = context.noBackupFilesDir.resolve("p30-device-repository")
        root.deleteRecursively()
        SecureRoomLedgerInitializationPort(context, keys).initialize(
            InitializeLedgerCommand(
                LedgerGenesisIds(
                    BOOK,
                    COMMIT,
                    id(3),
                    id(4),
                    SystemLedgerCode.entries.mapIndexed { index, code -> code to id(100L + index) }.toMap(),
                ),
                requireNotNull(CurrencyCode.parse("JPY").getOrNull()),
                ZoneId.of("Asia/Tokyo"),
                Instant.parse("2026-08-09T00:00:00Z"),
            ),
        ).success()
    }

    @After
    fun tearDown() = runBlocking {
        root.deleteRecursively()
        SecureRoomLedgerInitializationPort(context, keys).clearLocalBook(BOOK).success()
    }

    @Test
    fun realSqlCipherCatalogPublishesOnlyAfterEncryptedObjectAndManifestVerification() = runBlocking {
        val shadowAccess = SecureShadowLedgerAccess(context, keys, databaseAccess)
        val shadow = shadowAccess.createSnapshot(BOOK, OPERATION)
        val database = context.getDatabasePath(shadow.shadowDatabaseName)
        assertTrue(database.isFile)
        val storage = FileBackupRepositoryStorage(root)
        val catalog = createBackupCatalog(BOOK, SecurePrimaryLedgerAccess(context, keys, databaseAccess))
        var next = 1_000L
        val engine = ManagedBackupRepositoryEngine { id(next++) }
        val repositoryKey = LedgerTink.generateStreamingAeadKeyset()
        try {
            var lastProgress: BackupProgress? = null
            val input = ManagedBackupInput(
                BOOK,
                REPOSITORY,
                BackupRepositoryKind.APP_PRIVATE,
                HANDLE,
                SNAPSHOT,
                BookCommitId(COMMIT),
                LocalRevision.of(1).success(),
                Instant.parse("2026-08-09T01:00:00Z"),
                "1.0-device",
                2,
                database.source("database/ledger.db", BackupObjectKind.DATABASE_CHUNK),
                listOf("settings-日本語".toByteArray().source("settings/app.pb", BackupObjectKind.SETTINGS)),
                emptyList(),
                byteArrayOf(1, 2, 3).source("keys/portable.envelope", BackupObjectKind.KEY_ENVELOPE),
                null,
            )
            val result = engine.create(
                input,
                storage,
                catalog,
                repositoryKey,
                byteArrayOf(9, 8, 7),
                BackupRetentionPolicy(),
                progress = BackupProgressObserver { lastProgress = it },
            )
            assertTrue("$result lastProgress=$lastProgress", result is DomainResult.Success)
            assertEquals(listOf(SNAPSHOT), catalog.completeSnapshots(REPOSITORY).map { it.id })
            val manifest = BackupRepositoryInspector().readManifest(storage, REPOSITORY, SNAPSHOT, repositoryKey)
            assertEquals(BOOK, manifest.bookId)
            assertEquals(database.length() + "settings-日本語".toByteArray().size + 3L, manifest.logicalBytes)
            assertTrue(storage.names(BackupStorageArea.OBJECTS).isNotEmpty())
            assertEquals(1, storage.names(BackupStorageArea.SNAPSHOTS).size)
            assertFalse(root.walkTopDown().any { it.name.contains("partial") || it.name.contains("previous") })
            assertFalse(root.walkTopDown().filter(File::isFile).any { it.readBytes().contains("settings-日本語".toByteArray()) })

            val originalObjects = storage.names(BackupStorageArea.OBJECTS)
            val secondInput = input.copy(
                snapshotId = SECOND_SNAPSHOT,
                createdAt = Instant.parse("2026-08-10T01:00:00Z"),
            )
            val second = engine.create(
                secondInput,
                storage,
                catalog,
                repositoryKey,
                byteArrayOf(9, 8, 7),
                BackupRetentionPolicy(maximumSnapshots = 1),
            )
            assertTrue(second is DomainResult.Success)
            assertEquals(0L, (second as DomainResult.Success).value.physicalIncrementBytes)
            assertEquals(originalObjects, storage.names(BackupStorageArea.OBJECTS))
            assertEquals(listOf(SECOND_SNAPSHOT), catalog.completeSnapshots(REPOSITORY).map { it.id })
            assertFalse(storage.exists(BackupStorageArea.SNAPSHOTS, SNAPSHOT.manifestName()))
            assertEquals(SECOND_SNAPSHOT, BackupRepositoryInspector().readManifest(storage, REPOSITORY, SECOND_SNAPSHOT, repositoryKey).snapshotId)

            val retry = engine.create(
                secondInput,
                storage,
                catalog,
                repositoryKey,
                byteArrayOf(9, 8, 7),
                BackupRetentionPolicy(maximumSnapshots = 1),
            )
            assertTrue(retry is DomainResult.Success)
            assertEquals(listOf(SECOND_SNAPSHOT), catalog.completeSnapshots(REPOSITORY).map { it.id })
            assertEquals(1, storage.names(BackupStorageArea.SNAPSHOTS).size)
        } finally {
            repositoryKey.close()
            shadowAccess.discard(OPERATION)
        }
    }

    private fun File.source(name: String, kind: BackupObjectKind): ReopenableBackupSource {
        val expectedSize = length()
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(this).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return ReopenableBackupSource(name, kind, expectedSize, Hash256.fromBytes(digest.digest()).success()) { FileInputStream(this) }
    }

    private fun ByteArray.source(name: String, kind: BackupObjectKind): ReopenableBackupSource {
        val stored = copyOf()
        return ReopenableBackupSource(
            name,
            kind,
            stored.size.toLong(),
            Hash256.fromBytes(MessageDigest.getInstance("SHA-256").digest(stored)).success(),
        ) { ByteArrayInputStream(stored) }
    }

    private fun ByteArray.contains(needle: ByteArray): Boolean = indices.any { start ->
        start + needle.size <= size && needle.indices.all { offset -> this[start + offset] == needle[offset] }
    }

    private fun BackupSnapshotId.manifestName(): String = value.bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) } + ".manifest"

    private fun id(index: Long): StableId = StableId.fromUuid(UUID(30, index))
    private fun <T> DomainResult<T>.success(): T = (this as DomainResult.Success).value

    private companion object {
        val BOOK = StableId.fromUuid(UUID(30, 900))
        val COMMIT = StableId.fromUuid(UUID(30, 901))
        val REPOSITORY = BackupRepositoryId(StableId.fromUuid(UUID(30, 902)))
        val HANDLE = StableId.fromUuid(UUID(30, 903))
        val SNAPSHOT = BackupSnapshotId(StableId.fromUuid(UUID(30, 904)))
        val OPERATION = StableId.fromUuid(UUID(30, 905))
        val SECOND_SNAPSHOT = BackupSnapshotId(StableId.fromUuid(UUID(30, 906)))
    }
}
