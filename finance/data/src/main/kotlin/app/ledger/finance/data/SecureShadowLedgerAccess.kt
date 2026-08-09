@file:Suppress("MagicNumber", "TooGenericExceptionCaught", "TooManyFunctions")

package app.ledger.finance.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.ledger.core.common.StableId
import app.ledger.core.database.DatabaseIntegrityAudit
import app.ledger.core.database.EncryptedDatabaseFactory
import app.ledger.core.database.LedgerMigrations
import app.ledger.core.security.DeviceLedgerKeyProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class ShadowSnapshot(val expectedLiveHead: ByteArray, val shadowDatabaseName: String)

data class SecureShadowValidation(
    val sqlCipherReadable: Boolean,
    val integrityCheckPassed: Boolean,
    val foreignKeyCheckPassed: Boolean,
    val journalsBalanced: Boolean,
    val projectionsAligned: Boolean,
    val subtypeDetailsComplete: Boolean,
) {
    val isValid: Boolean = sqlCipherReadable && integrityCheckPassed && foreignKeyCheckPassed &&
        journalsBalanced && projectionsAligned && subtypeDetailsComplete
}

/** Crash-recoverable same-filesystem shadow copy and atomic replacement primitive. */
class SecureShadowLedgerAccess(
    context: Context,
    private val keyProvider: DeviceLedgerKeyProvider,
) {
    private val applicationContext = context.applicationContext
    val currentDatabaseSchemaVersion: Int get() = LedgerMigrations.CURRENT_VERSION

    fun createSnapshot(bookId: StableId, operationId: StableId): ShadowSnapshot {
        val shadowName = shadowName(operationId)
        applicationContext.deleteDatabase(shadowName)
        val expectedHead = withPrimary(bookId, write = true) { database ->
            val changed = database.compileStatement("UPDATE book SET state=1 WHERE id=1 AND state=0").executeUpdateDelete()
            require(changed == 1) { "ledger is not writable" }
            database.singleBlob("SELECT bc.uid FROM book b JOIN book_commit bc ON bc.id=b.head_commit_id WHERE b.id=1")
        }
        try {
            withPrimary(bookId, write = false) { database -> database.query("PRAGMA wal_checkpoint(TRUNCATE)").close() }
            val source = applicationContext.getDatabasePath(EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME).toPath()
            val target = applicationContext.getDatabasePath(shadowName).toPath()
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
        } finally {
            withPrimary(bookId, write = true) { database -> database.execSQL("UPDATE book SET state=0 WHERE id=1") }
        }
        withCopy(bookId, shadowName, write = true) { database -> database.execSQL("UPDATE book SET state=0 WHERE id=1") }
        withCopy(bookId, shadowName, write = false) { database ->
            require(
                database.singleBlob("SELECT bc.uid FROM book b JOIN book_commit bc ON bc.id=b.head_commit_id WHERE b.id=1")
                    .contentEquals(expectedHead),
            )
        }
        return ShadowSnapshot(expectedHead.copyOf(), shadowName)
    }

    /** Builds a rollback candidate from the retained pre-exchange safety database. */
    fun createRollbackSnapshot(
        bookId: StableId,
        retainedOperationId: StableId,
        rollbackOperationId: StableId,
    ): ShadowSnapshot {
        val retainedSafety = applicationContext.getDatabasePath(safetyName(retainedOperationId)).toPath()
        require(Files.isRegularFile(retainedSafety)) { "retained safety database is unavailable" }
        val rollbackShadowName = shadowName(rollbackOperationId)
        applicationContext.deleteDatabase(rollbackShadowName)
        val expectedHead = withPrimary(bookId, write = true) { database ->
            val changed = database.compileStatement("UPDATE book SET state=1 WHERE id=1 AND state=0").executeUpdateDelete()
            require(changed == 1) { "ledger is not writable" }
            database.singleBlob("SELECT bc.uid FROM book b JOIN book_commit bc ON bc.id=b.head_commit_id WHERE b.id=1")
        }
        try {
            withPrimary(bookId, write = false) { database -> database.query("PRAGMA wal_checkpoint(TRUNCATE)").close() }
            Files.copy(
                retainedSafety,
                applicationContext.getDatabasePath(rollbackShadowName).toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES,
            )
        } finally {
            withPrimary(bookId, write = true) { database -> database.execSQL("UPDATE book SET state=0 WHERE id=1") }
        }
        withCopy(bookId, rollbackShadowName, write = true) { database -> database.execSQL("UPDATE book SET state=0 WHERE id=1") }
        require(withCopy(bookId, rollbackShadowName, write = false, ::validateDatabase).isValid)
        return ShadowSnapshot(expectedHead.copyOf(), rollbackShadowName)
    }

    fun <T> writeShadow(bookId: StableId, operationId: StableId, block: (SupportSQLiteDatabase) -> T): T = withCopy(bookId, shadowName(operationId), write = true, block)

    fun <T> readShadow(bookId: StableId, operationId: StableId, block: (SupportSQLiteDatabase) -> T): T = withCopy(bookId, shadowName(operationId), write = false, block)

    fun validate(bookId: StableId, operationId: StableId): SecureShadowValidation = readShadow(bookId, operationId, ::validateDatabase)

    suspend fun exchange(bookId: StableId, operationId: StableId, expectedLiveHead: ByteArray): SecureShadowValidation = exchangeMutex.withLock {
        require(expectedLiveHead.size == StableId.BYTE_COUNT)
        val shadowName = shadowName(operationId)
        val safetyName = safetyName(operationId)
        val marker = applicationContext.filesDir.resolve("ledger_exchange_${operationId.bytes.toHex()}.marker").toPath()
        if (Files.exists(marker)) recoverInterruptedExchange(bookId, operationId)
        Files.createFile(marker)
        try {
            withPrimary(bookId, write = true) { database ->
                val currentHead = database.singleBlob(
                    "SELECT bc.uid FROM book b JOIN book_commit bc ON bc.id=b.head_commit_id WHERE b.id=1",
                )
                require(currentHead.contentEquals(expectedLiveHead)) { "live ledger head changed" }
                val changed = database.compileStatement("UPDATE book SET state=1 WHERE id=1 AND state=0").executeUpdateDelete()
                require(changed == 1) { "ledger is not writable" }
                database.query("PRAGMA wal_checkpoint(TRUNCATE)").close()
            }
            applicationContext.deleteDatabase(safetyName)
            Files.copy(
                applicationContext.getDatabasePath(EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME).toPath(),
                applicationContext.getDatabasePath(safetyName).toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES,
            )
            val shadowValidation = validate(bookId, operationId)
            require(shadowValidation.isValid) { "shadow validation failed" }
            try {
                Files.move(
                    applicationContext.getDatabasePath(shadowName).toPath(),
                    applicationContext.getDatabasePath(EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME).toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (unsupported: AtomicMoveNotSupportedException) {
                throw IllegalStateException("atomic database replacement is unsupported", unsupported)
            }
            val reopened = withPrimary(bookId, write = false, ::validateDatabase)
            if (!reopened.isValid) {
                restoreSafety(safetyName)
                error("reopened ledger validation failed")
            }
            reopened
        } catch (failure: Exception) {
            if (applicationContext.getDatabasePath(safetyName).isFile) {
                restoreSafety(safetyName)
                withPrimary(bookId, write = true) { database -> database.execSQL("UPDATE book SET state=0 WHERE id=1") }
            } else {
                runCatching { withPrimary(bookId, write = true) { it.execSQL("UPDATE book SET state=0 WHERE id=1") } }
            }
            throw failure
        } finally {
            Files.deleteIfExists(marker)
        }
    }

    fun recoverInterruptedExchange(bookId: StableId, operationId: StableId): Boolean {
        val marker = applicationContext.filesDir.resolve("ledger_exchange_${operationId.bytes.toHex()}.marker").toPath()
        if (!Files.exists(marker)) return false
        val safety = safetyName(operationId)
        if (applicationContext.getDatabasePath(safety).isFile) restoreSafety(safety)
        withPrimary(bookId, write = true) { database -> database.execSQL("UPDATE book SET state=0 WHERE id=1") }
        Files.deleteIfExists(marker)
        return true
    }

    fun discard(operationId: StableId) {
        applicationContext.deleteDatabase(shadowName(operationId))
    }

    fun discardSafety(operationId: StableId) {
        applicationContext.deleteDatabase(safetyName(operationId))
    }

    private fun restoreSafety(safetyName: String) {
        Files.move(
            applicationContext.getDatabasePath(safetyName).toPath(),
            applicationContext.getDatabasePath(EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME).toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    private fun validateDatabase(database: SupportSQLiteDatabase): SecureShadowValidation {
        val audit = DatabaseIntegrityAudit.run(database)
        val localRevision = database.singleLong("SELECT local_revision FROM book WHERE id=1")
        val projectionAhead = PROJECTION_TABLES.sumOf { table ->
            database.singleLong("SELECT COUNT(*) FROM $table WHERE as_of_local_revision > $localRevision")
        }
        return SecureShadowValidation(
            sqlCipherReadable = audit.capability.sqlCipherVersion.isNotBlank(),
            integrityCheckPassed = audit.integrityCheck == "ok",
            foreignKeyCheckPassed = audit.foreignKeyViolationCount == 0,
            journalsBalanced = audit.unbalancedJournalCount == 0,
            projectionsAligned = projectionAhead == 0L,
            subtypeDetailsComplete = audit.invalidCurrentSubtypeCount == 0,
        )
    }

    private fun <T> withPrimary(bookId: StableId, write: Boolean, block: (SupportSQLiteDatabase) -> T): T = withLedger(bookId, null, write, block)

    private fun <T> withCopy(
        bookId: StableId,
        name: String,
        write: Boolean,
        block: (SupportSQLiteDatabase) -> T,
    ): T = withLedger(bookId, name, write, block)

    private fun <T> withLedger(
        bookId: StableId,
        copyName: String?,
        write: Boolean,
        block: (SupportSQLiteDatabase) -> T,
    ): T = keyProvider.open(bookId).use { keys ->
        keys.databaseDek.useBytes { key ->
            val database = if (copyName == null) {
                EncryptedDatabaseFactory.openPrimary(applicationContext, key)
            } else {
                EncryptedDatabaseFactory.openLedgerCopy(applicationContext, copyName, key)
            }
            try {
                if (write) database.inLedgerTransaction(block) else database.readLedger(block)
            } finally {
                database.close()
            }
        }
    }

    private fun shadowName(operationId: StableId) = "ledger_shadow_${operationId.bytes.toHex()}.db"
    private fun safetyName(operationId: StableId) = "ledger_safety_${operationId.bytes.toHex()}.db"

    private companion object {
        val exchangeMutex = Mutex()
        val PROJECTION_TABLES = listOf(
            "current_transaction_projection",
            "budget_usage_projection",
            "project_usage_projection",
            "goal_balance_projection",
            "credit_statement_projection",
            "credit_account_projection",
            "installment_progress_projection",
            "loan_progress_projection",
            "settlement_position_projection",
        )
    }
}

private fun SupportSQLiteDatabase.singleBlob(sql: String): ByteArray = query(sql).use { cursor ->
    check(cursor.moveToFirst())
    cursor.getBlob(0)
}

private fun SupportSQLiteDatabase.singleLong(sql: String): Long = query(sql).use { cursor ->
    check(cursor.moveToFirst())
    cursor.getLong(0)
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
