@file:Suppress("LongMethod", "MagicNumber", "NestedBlockDepth")

package app.ledger.app

import android.content.Context
import android.database.sqlite.SQLiteFullException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.common.getOrNull
import app.ledger.core.money.CurrencyCode
import app.ledger.core.security.AndroidKeystoreKeys
import app.ledger.core.security.CryptographicRandomSource
import app.ledger.core.security.DeviceKeyHierarchy
import app.ledger.core.security.SecurePrimaryLedgerAccess
import app.ledger.core.security.SecurityEnvelopeStore
import app.ledger.core.time.LedgerClock
import app.ledger.finance.application.InitialAccountCommand
import app.ledger.finance.application.InitializeLedgerCommand
import app.ledger.finance.application.LedgerGenesisIds
import app.ledger.finance.application.MaterializedRestorePackage
import app.ledger.finance.data.RestoreExchangeFailureInjector
import app.ledger.finance.data.RestoreExchangeFailurePoint
import app.ledger.finance.data.SecureRoomLedgerInitializationPort
import app.ledger.finance.data.SecureRoomRestoreLedgerApplicationPort
import app.ledger.finance.domain.BookCommitId
import app.ledger.finance.domain.SystemLedgerCode
import app.ledger.finance.domain.UserAccountType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RestoreExchangeSqlCipherDeviceTest {
    private lateinit var context: Context
    private lateinit var keys: DeviceKeyHierarchy
    private lateinit var work: File
    private var originalSettings: ByteArray? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        keys = DeviceKeyHierarchy(AndroidKeystoreKeys(context), SecurityEnvelopeStore(context))
        work = context.noBackupFilesDir.resolve("p31-restore-device")
        work.deleteRecursively()
        require(work.mkdirs())
        originalSettings = context.filesDir.resolve("ledger_app_settings.pb").takeIf(File::isFile)?.readBytes()
    }

    @After
    fun tearDown() = runBlocking {
        SecureRoomLedgerInitializationPort(context, keys).clearLocalBook(BOOK)
        work.deleteRecursively()
        context.noBackupFilesDir.resolve("attachment_objects/${BOOK.toUuid()}").deleteRecursively()
        val settings = context.filesDir.resolve("ledger_app_settings.pb")
        originalSettings?.let(settings::writeBytes) ?: settings.delete()
        Unit
    }

    @Test
    fun everyExchangeFaultIncludingStorageFullRollsBackDatabaseKeyAndArtifacts() = runBlocking {
        val failures: List<Pair<String, (RestoreExchangeFailurePoint) -> Unit>> =
            RestoreExchangeFailurePoint.entries.map { point ->
                point.name to { actual: RestoreExchangeFailurePoint -> if (actual == point) error("injected $point") }
            } + listOf(
                "STORAGE_FULL" to { actual: RestoreExchangeFailurePoint ->
                    if (actual == RestoreExchangeFailurePoint.AFTER_DATABASE_SWAP) throw SQLiteFullException("injected full")
                },
            )
        failures.forEachIndexed { index, (label, fault) ->
            val operation = id(10_000L + index)
            val source = prepareSourceAndAdvanceLive(operation)
            val artifacts = AndroidRestoreArtifactSwapPort(context)
            val port = SecureRoomRestoreLedgerApplicationPort(context, keys, artifacts, RestoreExchangeFailureInjector(fault))
            val preparation = port.prepareReplacement(source)
            assertTrue("$label prepare -> $preparation", preparation is DomainResult.Success)
            val prepared = preparation.success()
            val result = port.exchange(prepared, id(20_000L + index))
            assertTrue("$label -> $result", result is DomainResult.Failure)
            assertEquals(requireNotNull(prepared.expectedLiveHead), currentHead())
            assertEquals("live-$operation", context.filesDir.resolve("ledger_app_settings.pb").readText())
            assertTrue(port.validateLive(BOOK, requireNotNull(prepared.expectedLiveHead)).success().isValid)
            assertEquals(0L, queryLong("SELECT state FROM book WHERE id=1"))
            port.cleanup(operation)
        }
    }

    @Test
    fun processDeathBeforeFinalizeRollsBackButAfterFinalizeKeepsVerifiedRestore() = runBlocking {
        val rollbackOperation = id(30_000)
        val rollbackSource = prepareSourceAndAdvanceLive(rollbackOperation)
        val first = SecureRoomRestoreLedgerApplicationPort(context, keys, AndroidRestoreArtifactSwapPort(context))
        val rollbackPrepared = first.prepareReplacement(rollbackSource).success()
        first.exchange(rollbackPrepared, id(30_001)).success()
        assertEquals(rollbackPrepared.sourceHead, currentHead())
        val restarted = SecureRoomRestoreLedgerApplicationPort(context, keys, AndroidRestoreArtifactSwapPort(context))
        assertTrue(restarted.recoverInterrupted(BOOK, rollbackOperation).success())
        assertEquals(requireNotNull(rollbackPrepared.expectedLiveHead), currentHead())
        assertEquals("live-$rollbackOperation", context.filesDir.resolve("ledger_app_settings.pb").readText())

        val finalizedOperation = id(31_000)
        val finalizedSource = prepareSourceAndAdvanceLive(finalizedOperation)
        val publishing = SecureRoomRestoreLedgerApplicationPort(context, keys, AndroidRestoreArtifactSwapPort(context))
        val finalizedPrepared = publishing.prepareReplacement(finalizedSource).success()
        publishing.exchange(finalizedPrepared, id(31_001)).success()
        publishing.finalizeExchange(BOOK, finalizedOperation).success()
        val afterRestart = SecureRoomRestoreLedgerApplicationPort(context, keys, AndroidRestoreArtifactSwapPort(context))
        assertTrue(afterRestart.recoverInterrupted(BOOK, finalizedOperation).success())
        assertEquals(finalizedPrepared.sourceHead, currentHead())
        assertEquals("source-$finalizedOperation", context.filesDir.resolve("ledger_app_settings.pb").readText())
        assertTrue(afterRestart.validateLive(BOOK, finalizedPrepared.sourceHead).success().isValid)
        val safetyDatabase = context.getDatabasePath("ledger_safety_${finalizedOperation.hex()}.db")
        val marker = context.filesDir.resolve("ledger-restore-${finalizedOperation.hex()}.marker")
        assertTrue(safetyDatabase.isFile)
        assertTrue(marker.isFile)
        afterRestart.cleanup(finalizedOperation)
        assertTrue(safetyDatabase.isFile)
        assertTrue(marker.isFile)
        afterRestart.confirmSafetySnapshotCleanup(finalizedOperation).success()
        assertFalse(safetyDatabase.exists())
        assertFalse(marker.exists())
    }

    @Test
    fun unsupportedOrCorruptSourceNeverChangesLiveLedger() = runBlocking {
        val operation = id(40_000)
        val source = prepareSourceAndAdvanceLive(operation)
        val live = currentHead()
        val port = SecureRoomRestoreLedgerApplicationPort(context, keys, AndroidRestoreArtifactSwapPort(context))
        assertTrue(
            port.prepareReplacement(source.copy(sourceDatabaseSchemaVersion = currentSchemaVersion() + 1)) is DomainResult.Failure,
        )
        assertEquals(live, currentHead())
        File(source.databasePath).outputStream().use { it.write(ByteArray(4_096) { 0x5a }) }
        assertTrue(port.prepareReplacement(source) is DomainResult.Failure)
        assertEquals(live, currentHead())
        assertEquals("live-$operation", context.filesDir.resolve("ledger_app_settings.pb").readText())
    }

    @Test
    fun unreadableRecoveryGateCanReplaceAndFaultRollsBackExactCorruptBytes() = runBlocking {
        val operation = id(45_000)
        val source = prepareSourceAndAdvanceLive(operation)
        val liveDatabase = databaseFile()
        liveDatabase.outputStream().use { it.write(ByteArray(16_384) { 0x6b }) }
        val corruptHash = liveDatabase.sha256()
        val failing = SecureRoomRestoreLedgerApplicationPort(
            context,
            keys,
            AndroidRestoreArtifactSwapPort(context),
            RestoreExchangeFailureInjector { point ->
                if (point == RestoreExchangeFailurePoint.AFTER_DATABASE_SWAP) error("crash after swap")
            },
        )
        val prepared = failing.prepareReplacement(source).success()
        assertEquals(null, prepared.expectedLiveHead)
        assertTrue(failing.exchange(prepared, id(45_001)) is DomainResult.Failure)
        assertTrue(MessageDigest.isEqual(corruptHash, liveDatabase.sha256()))
        failing.cleanup(operation)

        val recovery = SecureRoomRestoreLedgerApplicationPort(context, keys, AndroidRestoreArtifactSwapPort(context))
        val retry = recovery.prepareReplacement(source).success()
        assertEquals(null, retry.expectedLiveHead)
        recovery.exchange(retry, id(45_002)).success()
        assertTrue(recovery.validateLive(BOOK, retry.sourceHead).success().isValid)
        recovery.finalizeExchange(BOOK, operation).success()
        assertEquals(retry.sourceHead, currentHead())
    }

    @Test
    fun preRestoreQuarantineSnapshotStreamsAndVerifiesDatabaseSettingsAttachmentsAndVaultCiphertext() = runBlocking {
        val operation = id(46_000)
        prepareSourceAndAdvanceLive(operation)
        val attachment = context.noBackupFilesDir.resolve("attachment_objects/${BOOK.toUuid()}/objects/blob.bin")
        require(attachment.parentFile?.isDirectory == true || attachment.parentFile?.mkdirs() == true)
        attachment.writeBytes(ByteArray(256 * 1024) { it.toByte() })
        val vault = context.noBackupFilesDir.resolve("vault-backup-envelopes-v1/$BOOK.envelope")
        require(vault.parentFile?.isDirectory == true || vault.parentFile?.mkdirs() == true)
        vault.writeBytes(ByteArray(4_096) { (it * 3).toByte() })
        val snapshotId = id(46_001)
        val runtime = AppRuntimeSources(
            LedgerClock { Instant.parse("2026-08-10T03:00:00Z") },
            app.ledger.core.common.StableIdSource { snapshotId },
            CryptographicRandomSource { bytes -> bytes.fill(0x31) },
        )
        val result = AndroidPreRestoreSafetySnapshotPort(context, keys, runtime).create(BOOK, operation).success()
        assertEquals(snapshotId, result)
        val root = context.noBackupFilesDir.resolve("pre-restore-safety-v1/$BOOK/$snapshotId")
        assertTrue(root.resolve("manifest.bin").isFile)
        assertTrue(root.listFiles().orEmpty().count { it.name.startsWith("object-") } >= 4)
        root.deleteRecursively()
        vault.delete()
        Unit
    }

    private suspend fun prepareSourceAndAdvanceLive(operationId: StableId): MaterializedRestorePackage {
        SecureRoomLedgerInitializationPort(context, keys).clearLocalBook(BOOK)
        val initialization = SecureRoomLedgerInitializationPort(context, keys)
        initialization.initialize(
            InitializeLedgerCommand(
                LedgerGenesisIds(
                    BOOK,
                    SOURCE_COMMIT,
                    id(3),
                    id(4),
                    SystemLedgerCode.entries.mapIndexed { index, code -> code to id(100L + index) }.toMap(),
                ),
                currency(),
                ZoneId.of("Asia/Tokyo"),
                Instant.parse("2026-08-10T00:00:00Z"),
            ),
        ).success()
        val root = work.resolve(operationId.toString()).apply {
            deleteRecursively()
            require(mkdirs())
        }
        val databaseTarget = root.resolve("database/ledger.db").apply { require(parentFile?.mkdirs() == true) }
        val keyTarget = root.resolve("keys/portable.envelope").apply { require(parentFile?.mkdirs() == true) }
        val settingsTarget = root.resolve("settings/app.pb").apply { require(parentFile?.mkdirs() == true) }
        val attachments = root.resolve("attachments").apply { require(mkdirs()) }
        keys.open(BOOK).use { opened ->
            SecurePrimaryLedgerAccess(context, keys).read(BOOK) { it.query("PRAGMA wal_checkpoint(TRUNCATE)").close() }
            databaseFile().copyTo(databaseTarget, overwrite = true)
            opened.portableKeyMaterial().use { portable -> portable.useBytes(keyTarget::writeBytes) }
        }
        settingsTarget.writeText("source-$operationId")
        context.filesDir.resolve("ledger_app_settings.pb").writeText("live-$operationId")
        initialization.createFirstAccount(
            BOOK,
            InitialAccountCommand(
                ACCOUNT,
                id(501),
                LIVE_COMMIT,
                id(503),
                id(504),
                Instant.parse("2026-08-10T01:00:00Z"),
                UserAccountType.CASH,
                "Wallet",
                currency(),
                "account",
                0xff006c4c.toInt(),
            ),
        ).success()
        return MaterializedRestorePackage(
            BOOK,
            operationId,
            databaseTarget.absolutePath,
            settingsTarget.absolutePath,
            attachments.absolutePath,
            keyTarget.absolutePath,
            null,
            currentSchemaVersion(),
            databaseTarget.length() + keyTarget.length() + settingsTarget.length(),
        )
    }

    private fun currentHead(): BookCommitId = SecurePrimaryLedgerAccess(context, keys).read(BOOK) { connection ->
        connection.query("SELECT c.uid FROM book b JOIN book_commit c ON c.id=b.head_commit_id WHERE b.id=1").use {
            assertTrue(it.moveToFirst())
            BookCommitId(StableId.fromBytes(it.getBlob(0)).success())
        }
    }

    private fun queryLong(sql: String): Long = SecurePrimaryLedgerAccess(context, keys).read(BOOK) { connection ->
        connection.query(sql).use {
            assertTrue(it.moveToFirst())
            it.getLong(0)
        }
    }

    private fun currentSchemaVersion(): Int = queryLong("PRAGMA user_version").toInt()

    private fun databaseFile(): File = SecurePrimaryLedgerAccess(context, keys).encryptedDatabaseFile()

    private fun currency(): CurrencyCode = requireNotNull(CurrencyCode.parse("JPY").getOrNull())
    private fun File.sha256(): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(this).use { input ->
            val buffer = ByteArray(64 * 1024)
            try {
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) digest.update(buffer, 0, count)
                }
            } finally {
                buffer.fill(0)
            }
        }
        return digest.digest()
    }
    private fun id(value: Long): StableId = StableId.fromUuid(UUID(31, value))
    private fun StableId.hex(): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun <T> DomainResult<T>.success(): T = when (this) {
        is DomainResult.Success -> value
        is DomainResult.Failure -> throw AssertionError("expected success, got $error")
    }

    private companion object {
        val BOOK = StableId.fromUuid(UUID(31, 1))
        val SOURCE_COMMIT = StableId.fromUuid(UUID(31, 2))
        val LIVE_COMMIT = StableId.fromUuid(UUID(31, 502))
        val ACCOUNT = StableId.fromUuid(UUID(31, 500))
    }
}
