@file:Suppress(
    "ComplexCondition",
    "LongMethod",
    "MagicNumber",
    "SwallowedException",
    "TooGenericExceptionCaught",
    "TooManyFunctions",
)

package app.ledger.finance.data

import android.content.Context
import android.database.sqlite.SQLiteFullException
import android.util.AtomicFile
import androidx.sqlite.db.SupportSQLiteDatabase
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.database.EncryptedDatabaseFactory
import app.ledger.core.database.LedgerDatabase
import app.ledger.core.database.LedgerMigrations
import app.ledger.core.security.DeviceKeyHierarchy
import app.ledger.core.security.PreparedDeviceLedgerKeyReplacement
import app.ledger.core.security.SecretBytes
import app.ledger.finance.application.FinanceDataError
import app.ledger.finance.application.FinanceRestoreError
import app.ledger.finance.application.MaterializedRestorePackage
import app.ledger.finance.application.PreparedRestoreLedger
import app.ledger.finance.application.RestoreArtifactSwapPort
import app.ledger.finance.application.RestoreIntegrityReport
import app.ledger.finance.application.RestoreLedgerApplicationPort
import app.ledger.finance.application.RestoreLedgerExchangeResult
import app.ledger.finance.domain.BookCommitId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

enum class RestoreExchangeFailurePoint {
    AFTER_SAFETY_COPY,
    AFTER_DATABASE_SWAP,
    AFTER_KEY_ACTIVATION,
    AFTER_ARTIFACT_SWAP,
    BEFORE_FINAL_VALIDATION,
}

fun interface RestoreExchangeFailureInjector {
    fun checkpoint(point: RestoreExchangeFailurePoint)

    companion object {
        val NONE = RestoreExchangeFailureInjector { }
    }
}

/** SQLCipher migration/rebuild plus crash-recoverable database/key/artifact exchange. */
class SecureRoomRestoreLedgerApplicationPort(
    context: Context,
    private val keyHierarchy: DeviceKeyHierarchy,
    private val artifacts: RestoreArtifactSwapPort,
    private val failureInjector: RestoreExchangeFailureInjector = RestoreExchangeFailureInjector.NONE,
) : RestoreLedgerApplicationPort {
    private val applicationContext = context.applicationContext
    private val sessions = ConcurrentHashMap<StableId, PreparedSession>()

    override suspend fun prepareReplacement(value: MaterializedRestorePackage): DomainResult<PreparedRestoreLedger> = protect {
        val sourceSchemaVersion = value.sourceDatabaseSchemaVersion
        if (sourceSchemaVersion != null && sourceSchemaVersion > LedgerMigrations.CURRENT_VERSION) {
            return@protect DomainResult.Failure(FinanceRestoreError.UnsupportedVersion)
        }
        val databaseSource = File(value.databasePath).canonicalFile
        val keySource = File(value.portableKeyMaterialPath).canonicalFile
        require(databaseSource.isFile && keySource.isFile)
        val packageRoot = databaseSource.parentFile?.parentFile ?: error("invalid restore package")
        require(keySource.toPath().startsWith(packageRoot.toPath()))
        val keyMaterial = SecretBytes.copyOf(keySource.readBounded(MAX_KEY_MATERIAL_BYTES))
        val replacement = try {
            keyHierarchy.preparePortableReplacement(value.bookId, keyMaterial)
        } finally {
            keyMaterial.close()
        }
        val shadowName = shadowName(value.operationId)
        applicationContext.deleteDatabase(shadowName)
        try {
            copyDurably(databaseSource, applicationContext.getDatabasePath(shadowName))
            val restored = replacement.restoredDatabaseDek.useBytes { key ->
                EncryptedDatabaseFactory.openLedgerCopy(applicationContext, shadowName, key)
            }
            val source = try {
                val identity = restored.inLedgerTransaction { database ->
                    val identity = database.bookIdentity()
                    if (identity.bookId != value.bookId) abort(FinanceRestoreError.BookMismatch)
                    val live = runCatching { liveIdentity(value.bookId) }.getOrNull()
                    if (live != null && identity.baseCurrency != live.baseCurrency) {
                        abort(FinanceRestoreError.BaseCurrencyMismatch)
                    }
                    database.execSQL("UPDATE book SET state=1 WHERE id=1")
                    RoomProjectionEngine().rebuildAll(database, identity.localRevision, identity.valuationRevision)
                    database.execSQL("UPDATE book SET state=0 WHERE id=1")
                    identity
                }
                restored.readLedger { database -> database.query("PRAGMA wal_checkpoint(TRUNCATE)").close() }
                restored.readLedger { database ->
                    val report = validate(database, value.bookId, identity.head)
                    if (!report.projectionsValid) abort(FinanceRestoreError.ProjectionFailed(report.projectionFailureCodes))
                    if (!report.isValid) abort(FinanceRestoreError.IntegrityFailed)
                    identity to report
                }
            } finally {
                restored.close()
            }
            artifacts.stage(value)
            val live = runCatching { liveIdentity(value.bookId) }.getOrNull()
            sessions.remove(value.operationId)?.close()
            sessions[value.operationId] = PreparedSession(
                value.bookId,
                replacement,
                source.first.head,
                live?.head,
                live != null,
                true,
            )
            DomainResult.Success(
                PreparedRestoreLedger(value.operationId, source.first.head, live?.head, source.second),
            )
        } catch (error: Exception) {
            replacement.close()
            applicationContext.deleteDatabase(shadowName)
            throw error
        }
    }

    override suspend fun exchange(
        prepared: PreparedRestoreLedger,
        safetySnapshotId: StableId,
    ): DomainResult<RestoreLedgerExchangeResult> = exchangeMutex.withLock {
        val session = sessions[prepared.operationId]
            ?: return@withLock DomainResult.Failure(FinanceRestoreError.RecoveryRequired)
        if (session.sourceHead != prepared.sourceHead || session.expectedLiveHead != prepared.expectedLiveHead) {
            return@withLock DomainResult.Failure(FinanceRestoreError.IntegrityFailed)
        }
        try {
            if (session.liveReadable) {
                val live = liveIdentity(session.bookId)
                if (live.head != prepared.expectedLiveHead) {
                    return@withLock DomainResult.Failure(FinanceRestoreError.LiveHeadChanged)
                }
            }
            writeExchangeMarker(prepared.operationId, session.bookId, EXCHANGE_PHASE_PREPARED, session.liveReadable)
            if (session.liveReadable) checkpointAndLockLive(session.bookId)
            applicationContext.deleteDatabase(safetyName(prepared.operationId))
            copyLiveRecoveryFiles(prepared.operationId)
            failureInjector.checkpoint(RestoreExchangeFailurePoint.AFTER_SAFETY_COPY)
            atomicMove(
                applicationContext.getDatabasePath(shadowName(prepared.operationId)),
                applicationContext.getDatabasePath(EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME),
            )
            deleteLiveSidecars()
            failureInjector.checkpoint(RestoreExchangeFailurePoint.AFTER_DATABASE_SWAP)
            session.keys?.activate(keyRecoveryFile(prepared.operationId))
            if (session.keys != null) failureInjector.checkpoint(RestoreExchangeFailurePoint.AFTER_KEY_ACTIVATION)
            if (session.replaceArtifacts) {
                artifacts.exchange(prepared.operationId)
                failureInjector.checkpoint(RestoreExchangeFailurePoint.AFTER_ARTIFACT_SWAP)
            }
            failureInjector.checkpoint(RestoreExchangeFailurePoint.BEFORE_FINAL_VALIDATION)
            val validation = validateLiveBlocking(session.bookId, prepared.sourceHead)
            if (!validation.isValid) error("post-exchange validation failed")
            DomainResult.Success(RestoreLedgerExchangeResult(prepared.sourceHead, safetySnapshotId))
        } catch (error: Exception) {
            val rolledBack = runCatching {
                rollbackBlocking(session.bookId, prepared.operationId)
            }.isSuccess
            DomainResult.Failure(
                if (rolledBack) FinanceRestoreError.RolledBack else FinanceRestoreError.RecoveryRequired,
            )
        }
    }

    override suspend fun validateLive(
        bookId: StableId,
        expectedHead: BookCommitId,
    ): DomainResult<RestoreIntegrityReport> = protect { DomainResult.Success(validateLiveBlocking(bookId, expectedHead)) }

    override suspend fun finalizeExchange(bookId: StableId, operationId: StableId): DomainResult<Unit> = protect {
        val session = sessions[operationId] ?: return@protect DomainResult.Failure(FinanceRestoreError.RecoveryRequired)
        require(session.bookId == bookId)
        writeExchangeMarker(operationId, bookId, EXCHANGE_PHASE_FINALIZED, session.liveReadable)
        DomainResult.Success(Unit)
    }

    override suspend fun rollback(
        bookId: StableId,
        operationId: StableId,
        safetySnapshotId: StableId,
    ): DomainResult<Unit> = protect {
        require(safetySnapshotId != operationId)
        rollbackBlocking(bookId, operationId)
        DomainResult.Success(Unit)
    }

    override suspend fun recoverInterrupted(bookId: StableId, operationId: StableId): DomainResult<Boolean> = protect {
        val marker = exchangeMarker(operationId)
        val keyMarker = keyRecoveryFile(operationId)
        val storedMarker = marker.takeIf(File::isFile)?.let { readExchangeMarker(operationId) }
        if (storedMarker != null && storedMarker.bookId != bookId) {
            return@protect DomainResult.Failure(FinanceRestoreError.IntegrityFailed)
        }
        if (storedMarker?.phase == EXCHANGE_PHASE_FINALIZED) {
            // A finalized exchange is not an interrupted operation. Its recovery copy remains until
            // the user explicitly confirms cleanup from the restore result UI.
            return@protect DomainResult.Success(true)
        }
        val artifactRecovered = artifacts.recover(operationId)
        if (!marker.isFile && !keyMarker.isFile && !artifactRecovered) return@protect DomainResult.Success(false)
        rollbackBlocking(bookId, operationId)
        DomainResult.Success(true)
    }

    override fun confirmSafetySnapshotCleanup(operationId: StableId): DomainResult<Unit> = protect {
        val marker = exchangeMarker(operationId)
        val stored = marker.takeIf(File::isFile)?.let { readExchangeMarker(operationId) }
            ?: return@protect DomainResult.Failure(FinanceRestoreError.RecoveryRequired)
        if (stored.phase != EXCHANGE_PHASE_FINALIZED) {
            return@protect DomainResult.Failure(FinanceRestoreError.RecoveryRequired)
        }
        sessions.remove(operationId)?.let { session ->
            session.keys?.commit(keyRecoveryFile(operationId))
            session.close()
        }
        applicationContext.deleteDatabase(shadowName(operationId))
        applicationContext.deleteDatabase(safetyName(operationId))
        artifacts.cleanup(operationId)
        AtomicFile(marker).delete()
        AtomicFile(keyRecoveryFile(operationId)).delete()
        DomainResult.Success(Unit)
    }

    override fun cleanup(operationId: StableId) {
        sessions.remove(operationId)?.close()
        applicationContext.deleteDatabase(shadowName(operationId))
        val finalized = runCatching {
            exchangeMarker(operationId).takeIf(File::isFile)?.let { readExchangeMarker(operationId) }?.phase ==
                EXCHANGE_PHASE_FINALIZED
        }.getOrDefault(false)
        if (finalized) return
        applicationContext.deleteDatabase(safetyName(operationId))
        artifacts.cleanup(operationId)
        AtomicFile(exchangeMarker(operationId)).delete()
        AtomicFile(keyRecoveryFile(operationId)).delete()
    }

    fun registerPreparedMerge(
        operationId: StableId,
        bookId: StableId,
        sourceHead: BookCommitId,
        expectedLiveHead: BookCommitId,
    ) {
        require(applicationContext.getDatabasePath(shadowName(operationId)).isFile)
        sessions.remove(operationId)?.close()
        sessions[operationId] = PreparedSession(bookId, null, sourceHead, expectedLiveHead, true, false)
    }

    private fun checkpointAndLockLive(bookId: StableId) {
        keyHierarchy.open(bookId).use { keys ->
            val database = keys.databaseDek.useBytes { EncryptedDatabaseFactory.openPrimary(applicationContext, it) }
            try {
                database.inLedgerTransaction { connection ->
                    val changed = connection.compileStatement("UPDATE book SET state=1 WHERE id=1 AND state=0").executeUpdateDelete()
                    require(changed == 1) { "live ledger is not ready for restore" }
                }
                database.readLedger { connection -> connection.query("PRAGMA wal_checkpoint(TRUNCATE)").close() }
            } finally {
                database.close()
            }
        }
    }

    private fun rollbackBlocking(bookId: StableId, operationId: StableId) {
        val marker = exchangeMarker(operationId)
        val keyRecovery = keyRecoveryFile(operationId)
        val safety = applicationContext.getDatabasePath(safetyName(operationId))
        val artifactRecovered = artifacts.recover(operationId)
        if (!marker.isFile && !keyRecovery.isFile && !safety.isFile && !artifactRecovered) return
        sessions[operationId]?.keys?.rollback()
        keyHierarchy.recoverPortableReplacement(bookId, keyRecovery)
        if (safety.isFile) {
            atomicMove(safety, applicationContext.getDatabasePath(EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME))
        }
        restoreLiveSidecars(operationId)
        val oldLiveReadable = sessions[operationId]?.liveReadable
            ?: marker.takeIf(File::isFile)?.let { readExchangeMarker(operationId) }
                ?.also { require(it.bookId == bookId) }?.liveReadable
            ?: true
        if (oldLiveReadable) {
            keyHierarchy.open(bookId).use { keys ->
                val database = keys.databaseDek.useBytes { EncryptedDatabaseFactory.openPrimary(applicationContext, it) }
                try {
                    database.inLedgerTransaction { connection -> connection.execSQL("UPDATE book SET state=0 WHERE id=1") }
                    val identity = database.readLedger { connection -> connection.bookIdentity() }
                    require(identity.bookId == bookId)
                } finally {
                    database.close()
                }
            }
        }
        AtomicFile(marker).delete()
    }

    private fun validateLiveBlocking(bookId: StableId, expectedHead: BookCommitId): RestoreIntegrityReport = keyHierarchy.open(bookId).use { keys ->
        val database = keys.databaseDek.useBytes { EncryptedDatabaseFactory.openPrimary(applicationContext, it) }
        try {
            database.readLedger { validate(it, bookId, expectedHead) }
        } finally {
            database.close()
        }
    }

    private fun validate(
        database: SupportSQLiteDatabase,
        expectedBookId: StableId,
        expectedHead: BookCommitId,
    ): RestoreIntegrityReport {
        val identity = database.bookIdentity()
        val audit = RoomLedgerIntegrityAudit.run(database)
        val mismatches = audit.mismatchedProjectionFamilies + audit.rebuiltProjectionFamilies
        return RestoreIntegrityReport(
            schemaVersionSupported = database.version == LedgerMigrations.CURRENT_VERSION,
            migrationsApplied = database.version == LedgerMigrations.CURRENT_VERSION,
            sqlCipherReadable = audit.database.capability.sqlCipherVersion.isNotBlank(),
            aeadAndHashesValid = true,
            foreignKeysValid = audit.database.foreignKeyViolationCount == 0,
            journalsBalanced = audit.database.unbalancedJournalCount == 0,
            projectionsValid = audit.projectionRebuildMatches,
            transactionSubtypesValid = audit.database.invalidCurrentSubtypeCount == 0,
            attachmentsValid = true,
            bookIdentityValid = identity.bookId == expectedBookId && identity.head == expectedHead,
            baseCurrencyValid = identity.baseCurrency.matches(Regex("[A-Z]{3}")),
            projectionFailureCodes = mismatches.mapTo(sortedSetOf()) { it.name },
            invariantFailureCodes = audit.failedInvariantIds,
            permanentInvariantStandardValid = audit.standardInventoryMatches,
        )
    }

    private fun liveIdentity(bookId: StableId): LedgerIdentity = keyHierarchy.open(bookId).use { keys ->
        val database = keys.databaseDek.useBytes { EncryptedDatabaseFactory.openPrimary(applicationContext, it) }
        try {
            database.readLedger { connection -> connection.bookIdentity() }
        } finally {
            database.close()
        }
    }

    private fun SupportSQLiteDatabase.bookIdentity(): LedgerIdentity = query(
        "SELECT b.uid,b.base_currency,b.local_revision,b.valuation_revision,bc.uid FROM book b " +
            "JOIN book_commit bc ON bc.id=b.head_commit_id WHERE b.id=1",
    ).use { cursor ->
        check(cursor.moveToFirst())
        LedgerIdentity(
            StableId.fromBytes(cursor.getBlob(0)).required(),
            cursor.getString(1),
            cursor.getLong(2),
            cursor.getLong(3),
            BookCommitId(StableId.fromBytes(cursor.getBlob(4)).required()),
        )
    }

    private fun writeExchangeMarker(operationId: StableId, bookId: StableId, phase: Int, liveReadable: Boolean) {
        val atomic = AtomicFile(exchangeMarker(operationId))
        val output = atomic.startWrite()
        try {
            val data = DataOutputStream(output)
            data.writeInt(EXCHANGE_MARKER_MAGIC)
            data.write(bookId.bytes)
            data.writeInt(phase)
            data.writeBoolean(liveReadable)
            data.flush()
            output.fd.sync()
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }

    private fun readExchangeMarker(operationId: StableId): ExchangeMarker = DataInputStream(
        AtomicFile(exchangeMarker(operationId)).openRead(),
    ).use { input ->
        require(input.readInt() == EXCHANGE_MARKER_MAGIC)
        val bookId = StableId.fromBytes(ByteArray(StableId.BYTE_COUNT).also(input::readFully)).required()
        val phase = input.readInt().also { require(it == EXCHANGE_PHASE_PREPARED || it == EXCHANGE_PHASE_FINALIZED) }
        val liveReadable = input.readBoolean()
        require(input.read() == -1)
        ExchangeMarker(bookId, phase, liveReadable)
    }

    private fun copyDurably(source: File, target: File) {
        val parent = requireNotNull(target.parentFile)
        require(source.isFile && (parent.isDirectory || parent.mkdirs()))
        FileInputStream(source).use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output, COPY_BUFFER_BYTES)
                output.fd.sync()
            }
        }
    }

    private fun copyLiveRecoveryFiles(operationId: StableId) {
        val live = applicationContext.getDatabasePath(EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME)
        val safety = applicationContext.getDatabasePath(safetyName(operationId))
        copyDurably(live, safety)
        DATABASE_SIDECARS.forEach { suffix ->
            val source = File(live.path + suffix)
            if (source.isFile) copyDurably(source, File(safety.path + suffix))
        }
    }

    private fun deleteLiveSidecars() {
        val live = applicationContext.getDatabasePath(EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME)
        DATABASE_SIDECARS.forEach { suffix -> File(live.path + suffix).let { check(!it.exists() || it.delete()) } }
    }

    private fun restoreLiveSidecars(operationId: StableId) {
        val live = applicationContext.getDatabasePath(EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME)
        val safety = applicationContext.getDatabasePath(safetyName(operationId))
        DATABASE_SIDECARS.forEach { suffix ->
            val current = File(live.path + suffix)
            check(!current.exists() || current.delete())
            val previous = File(safety.path + suffix)
            if (previous.isFile) atomicMove(previous, current)
        }
    }

    private fun atomicMove(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (error: AtomicMoveNotSupportedException) {
            throw IOException("same-filesystem atomic ledger exchange is unavailable", error)
        }
    }

    private inline fun <T> protect(block: () -> DomainResult<T>): DomainResult<T> = try {
        block()
    } catch (abort: FinancialPersistenceAbort) {
        DomainResult.Failure(abort.domainError)
    } catch (_: SQLiteFullException) {
        DomainResult.Failure(FinanceDataError.StorageFull)
    } catch (_: Exception) {
        DomainResult.Failure(FinanceRestoreError.IntegrityFailed)
    }

    private fun shadowName(operationId: StableId) = "ledger_shadow_${operationId.hex()}.db"
    private fun safetyName(operationId: StableId) = "ledger_safety_${operationId.hex()}.db"
    private fun exchangeMarker(operationId: StableId) = applicationContext.filesDir.resolve("ledger-restore-${operationId.hex()}.marker")
    private fun keyRecoveryFile(operationId: StableId) = applicationContext.filesDir.resolve("ledger-restore-${operationId.hex()}.key-recovery")

    private data class LedgerIdentity(
        val bookId: StableId,
        val baseCurrency: String,
        val localRevision: Long,
        val valuationRevision: Long,
        val head: BookCommitId,
    )

    private data class PreparedSession(
        val bookId: StableId,
        val keys: PreparedDeviceLedgerKeyReplacement?,
        val sourceHead: BookCommitId,
        val expectedLiveHead: BookCommitId?,
        val liveReadable: Boolean,
        val replaceArtifacts: Boolean,
    ) : AutoCloseable {
        override fun close() = keys?.close() ?: Unit
    }

    private data class ExchangeMarker(val bookId: StableId, val phase: Int, val liveReadable: Boolean)

    private companion object {
        val exchangeMutex = Mutex()
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val MAX_KEY_MATERIAL_BYTES = 256 * 1024
        const val EXCHANGE_MARKER_MAGIC = 0x52535458
        const val EXCHANGE_PHASE_PREPARED = 1
        const val EXCHANGE_PHASE_FINALIZED = 2
        val DATABASE_SIDECARS = listOf("-wal", "-shm", "-journal")
    }
}

private fun File.readBounded(maximum: Int): ByteArray {
    require(length() in 1..maximum.toLong())
    return FileInputStream(this).use { input ->
        val output = ByteArray(length().toInt())
        var offset = 0
        while (offset < output.size) {
            val count = input.read(output, offset, output.size - offset)
            if (count < 0) error("short restored key material")
            if (count > 0) offset += count
        }
        require(input.read() == -1)
        output
    }
}

private fun StableId.hex(): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
private fun <T> DomainResult<T>.required(): T = (this as DomainResult.Success).value
