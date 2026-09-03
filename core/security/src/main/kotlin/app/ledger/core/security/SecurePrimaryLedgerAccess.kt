package app.ledger.core.security

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.ledger.core.common.StableId
import app.ledger.core.database.EncryptedDatabaseFactory
import app.ledger.core.database.LedgerDatabase
import kotlinx.coroutines.runBlocking
import java.io.File

/** Narrow infrastructure bridge for encrypted non-financial operation metadata. */
class SecurePrimaryLedgerAccess(
    context: Context,
    private val keyProvider: DeviceLedgerKeyProvider,
    private val databaseAccess: LedgerDatabaseOperationAccess,
) : LedgerDatabaseOperationAccess {
    private val applicationContext = context.applicationContext

    fun <T> write(bookId: StableId, block: (SupportSQLiteDatabase) -> T): T = runBlocking {
        withCurrentDatabase(bookId, LedgerAccessMode.WRITE) { database -> database.inLedgerTransaction(block) }
    }

    fun <T> read(bookId: StableId, block: (SupportSQLiteDatabase) -> T): T = runBlocking {
        withCurrentDatabase(bookId, LedgerAccessMode.READ) { database -> database.readLedger(block) }
    }

    override suspend fun <T> withCurrentDatabase(
        bookId: StableId,
        mode: LedgerAccessMode,
        block: suspend (LedgerDatabase) -> T,
    ): T = databaseAccess.withCurrentDatabase(bookId, mode, block)

    /** Raw encrypted artifact location for streaming backup/restore; callers never receive the database key. */
    fun encryptedDatabaseFile(): File = applicationContext.getDatabasePath(EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME)

    fun seal(bookId: StableId, operationId: StableId, purpose: String, plaintext: ByteArray): ByteArray = keyProvider.open(bookId).use { keys ->
        keys.encryptSecureSettings(plaintext, associatedData(operationId, purpose))
    }

    fun open(bookId: StableId, operationId: StableId, purpose: String, ciphertext: ByteArray): ByteArray = keyProvider.open(bookId).use { keys ->
        keys.decryptSecureSettings(ciphertext, associatedData(operationId, purpose))
    }

    private fun associatedData(operationId: StableId, purpose: String): ByteArray {
        require(purpose.matches(Regex("[a-z0-9_-]{2,40}")))
        return "ledger-operation-v1\u0000$purpose\u0000".toByteArray(Charsets.US_ASCII) + operationId.bytes
    }

    companion object {
        /** Explicit restore/recovery path; the permit proves the process resource is closed. */
        fun forOfflineMaintenance(
            context: Context,
            keyProvider: DeviceLedgerKeyProvider,
            permit: OfflinePrimaryMaintenancePermit,
        ): SecurePrimaryLedgerAccess = SecurePrimaryLedgerAccess(
            context,
            keyProvider,
            OfflineMaintenancePrimaryLedgerOperationAccess(context, keyProvider, permit),
        )
    }
}

private class OfflineMaintenancePrimaryLedgerOperationAccess(
    context: Context,
    private val keyProvider: DeviceLedgerKeyProvider,
    private val permit: OfflinePrimaryMaintenancePermit,
) : LedgerDatabaseOperationAccess {
    private val applicationContext = context.applicationContext

    override suspend fun <T> withCurrentDatabase(
        bookId: StableId,
        mode: LedgerAccessMode,
        block: suspend (LedgerDatabase) -> T,
    ): T {
        check(permit.isValidFor(bookId)) { "offline primary maintenance permit is invalid" }
        return keyProvider.open(bookId).use { keys ->
            val database = keys.databaseDek.useBytes { key ->
                EncryptedDatabaseFactory.openPrimary(applicationContext, key)
            }
            try {
                block(database)
            } finally {
                database.close()
            }
        }
    }
}
