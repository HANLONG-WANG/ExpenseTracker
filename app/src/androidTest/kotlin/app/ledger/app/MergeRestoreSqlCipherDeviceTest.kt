@file:Suppress("LongMethod", "MagicNumber")

package app.ledger.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.common.StableIdSource
import app.ledger.core.common.getOrNull
import app.ledger.core.money.CurrencyCode
import app.ledger.core.security.AndroidKeystoreKeys
import app.ledger.core.security.DeviceKeyHierarchy
import app.ledger.core.security.SecurePrimaryLedgerAccess
import app.ledger.core.security.SecurityEnvelopeStore
import app.ledger.finance.application.InitialAccountCommand
import app.ledger.finance.application.InitialCategoryCommand
import app.ledger.finance.application.InitializeLedgerCommand
import app.ledger.finance.application.LedgerGenesisIds
import app.ledger.finance.data.SecureRoomLedgerInitializationPort
import app.ledger.finance.data.SecureRoomMergeRestoreApplicationPort
import app.ledger.finance.data.SecureRoomRestoreLedgerApplicationPort
import app.ledger.finance.domain.CategoryDirection
import app.ledger.finance.domain.StatisticalNature
import app.ledger.finance.domain.SystemLedgerCode
import app.ledger.finance.domain.UserAccountType
import app.ledger.transfer.data.RestoreMaterializationResult
import app.ledger.transfer.domain.BackupRepositoryId
import app.ledger.transfer.domain.BackupSnapshotId
import app.ledger.transfer.domain.CommitGraphMergePlanner
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MergeRestoreSqlCipherDeviceTest {
    private lateinit var context: Context
    private lateinit var keys: DeviceKeyHierarchy
    private lateinit var work: File
    private var originalSettings: ByteArray? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        keys = DeviceKeyHierarchy(AndroidKeystoreKeys(context), SecurityEnvelopeStore(context))
        work = context.noBackupFilesDir.resolve("p31-merge-device")
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
    fun divergentSameBookBranchesCreateTwoParentMergeCommitWithoutTimestampOverwrite() = runBlocking {
        val initialization = SecureRoomLedgerInitializationPort(context, keys)
        initialization.clearLocalBook(BOOK)
        initialization.initialize(
            InitializeLedgerCommand(
                LedgerGenesisIds(
                    BOOK,
                    GENESIS,
                    id(3),
                    id(4),
                    SystemLedgerCode.entries.mapIndexed { index, code -> code to id(100L + index) }.toMap(),
                ),
                currency(),
                ZoneId.of("Asia/Tokyo"),
                Instant.parse("2026-08-10T00:00:00Z"),
            ),
        ).success()
        checkpoint()
        val base = work.resolve("base.db")
        databaseFile().copyTo(base, overwrite = true)
        val target = work.resolve("restore-${OPERATION.hex()}").apply { require(mkdirs()) }
        val keyFile = target.resolve("keys/portable-key-material.envelope").apply { require(parentFile?.mkdirs() == true) }
        keys.open(BOOK).use { opened -> opened.portableKeyMaterial().use { it.useBytes(keyFile::writeBytes) } }

        initialization.createFirstCategory(
            BOOK,
            InitialCategoryCommand(
                INCOMING_CATEGORY,
                INCOMING_HEAD,
                id(201),
                id(203),
                Instant.parse("2026-08-10T02:00:00Z"),
                CategoryDirection.EXPENSE,
                "Incoming category",
                "food",
                StatisticalNature.CONSUMPTION_EXPENSE,
                "record",
                0xff006c4c.toInt(),
            ),
        ).success()
        checkpoint()
        val incomingDatabase = target.resolve("database/ledger.db").apply { require(parentFile?.mkdirs() == true) }
        databaseFile().copyTo(incomingDatabase, overwrite = true)
        target.resolve("settings/source.pb").apply {
            require(parentFile?.mkdirs() == true)
            writeText("incoming-settings")
        }
        target.resolve("attachments").mkdirs()

        require(databaseFile().delete())
        base.copyTo(databaseFile(), overwrite = true)
        initialization.createFirstAccount(
            BOOK,
            InitialAccountCommand(
                LOCAL_ACCOUNT,
                id(301),
                LOCAL_HEAD,
                id(303),
                id(304),
                Instant.parse("2026-08-10T01:00:00Z"),
                UserAccountType.CASH,
                "Local wallet",
                currency(),
                "account",
                0xff006c4c.toInt(),
            ),
        ).success()
        context.filesDir.resolve("ledger_app_settings.pb").writeText("local-settings")

        var generated = 10_000L
        val exchange = SecureRoomRestoreLedgerApplicationPort(context, keys, AndroidRestoreArtifactSwapPort(context))
        val application = SecureRoomMergeRestoreApplicationPort(
            context,
            keys,
            StableIdSource { id(generated++) },
            { Instant.parse("2026-08-10T03:00:00Z") },
            exchange,
        )
        val merge = AndroidMergeLedgerPort(application)
        val materialized = RestoreMaterializationResult(
            BOOK,
            BackupRepositoryId(id(400)),
            BackupSnapshotId(id(401)),
            currentSchemaVersion(),
            incomingDatabase.length() + keyFile.length(),
            3,
            false,
            target,
        )
        val input = merge.inspect(BOOK, OPERATION, materialized).success()
        val plan = CommitGraphMergePlanner.plan(input).success()
        assertEquals(GENESIS, plan.commonAncestor.value)
        assertTrue(plan.conflicts.isEmpty())
        val prepared = merge.applyToShadow(BOOK, OPERATION, plan, emptyMap()).success()
        val exchanged = exchange.exchange(prepared, SAFETY).success()
        assertTrue(exchange.validateLive(BOOK, exchanged.resultingHead).success().isValid)
        exchange.finalizeExchange(BOOK, OPERATION).success()
        SecurePrimaryLedgerAccess(context, keys).read(BOOK) { database ->
            assertEquals(1L, database.scalar("SELECT COUNT(*) FROM user_account WHERE uid=?", arrayOf(LOCAL_ACCOUNT.bytes)))
            assertEquals(1L, database.scalar("SELECT COUNT(*) FROM category WHERE uid=?", arrayOf(INCOMING_CATEGORY.bytes)))
            val parents = database.scalar(
                "SELECT COUNT(*) FROM book_commit_parent p JOIN book_commit c ON c.id=p.commit_id WHERE c.uid=?",
                arrayOf(exchanged.resultingHead.value.bytes),
            )
            assertEquals(2L, parents)
            val parentUids = database.query(
                "SELECT pc.uid FROM book_commit c JOIN book_commit_parent p ON p.commit_id=c.id " +
                    "JOIN book_commit pc ON pc.id=p.parent_commit_id WHERE c.uid=? ORDER BY p.ordinal",
                arrayOf(exchanged.resultingHead.value.bytes),
            ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getBlob(0).toList()) } }
            assertEquals(setOf(LOCAL_HEAD.bytes.toList(), INCOMING_HEAD.bytes.toList()), parentUids.toSet())
        }
        exchange.confirmSafetySnapshotCleanup(OPERATION).success()
        merge.cleanup(OPERATION)
    }

    private fun checkpoint() {
        SecurePrimaryLedgerAccess(context, keys).read(BOOK) { it.query("PRAGMA wal_checkpoint(TRUNCATE)").close() }
    }

    private fun currentSchemaVersion(): Int = SecurePrimaryLedgerAccess(context, keys).read(BOOK) { database ->
        database.query("PRAGMA user_version").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }
    }

    private fun databaseFile(): File = SecurePrimaryLedgerAccess(context, keys).encryptedDatabaseFile()

    private fun androidx.sqlite.db.SupportSQLiteDatabase.scalar(sql: String, args: Array<out Any?>): Long = query(sql, args).use { if (it.moveToFirst()) it.getLong(0) else 0L }
    private fun currency(): CurrencyCode = requireNotNull(CurrencyCode.parse("JPY").getOrNull())
    private fun id(value: Long): StableId = StableId.fromUuid(UUID(32, value))
    private fun StableId.hex(): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun <T> DomainResult<T>.success(): T = when (this) {
        is DomainResult.Success -> value
        is DomainResult.Failure -> throw AssertionError(error.code)
    }

    private companion object {
        val BOOK = StableId.fromUuid(UUID(32, 1))
        val GENESIS = StableId.fromUuid(UUID(32, 2))
        val INCOMING_CATEGORY = StableId.fromUuid(UUID(32, 200))
        val INCOMING_HEAD = StableId.fromUuid(UUID(32, 202))
        val LOCAL_ACCOUNT = StableId.fromUuid(UUID(32, 300))
        val LOCAL_HEAD = StableId.fromUuid(UUID(32, 302))
        val OPERATION = StableId.fromUuid(UUID(32, 500))
        val SAFETY = StableId.fromUuid(UUID(32, 501))
    }
}
