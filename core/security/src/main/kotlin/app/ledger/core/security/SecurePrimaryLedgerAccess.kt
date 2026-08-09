package app.ledger.core.security

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.ledger.core.common.StableId
import app.ledger.core.database.EncryptedDatabaseFactory

/** Privileged transaction gate implemented only by the financial persistence owner. */
interface SecureLedgerFactPurgeAccess {
    fun <T> write(bookId: StableId, block: (SupportSQLiteDatabase) -> T): T
}

/** Narrow infrastructure bridge for encrypted non-financial operation metadata. */
class SecurePrimaryLedgerAccess(
    context: Context,
    private val keyProvider: DeviceLedgerKeyProvider,
) {
    private val applicationContext = context.applicationContext

    fun <T> write(bookId: StableId, block: (SupportSQLiteDatabase) -> T): T = withDatabase(bookId) { database ->
        database.inLedgerTransaction(block)
    }

    fun <T> read(bookId: StableId, block: (SupportSQLiteDatabase) -> T): T = withDatabase(bookId) { database ->
        database.readLedger(block)
    }

    fun seal(bookId: StableId, operationId: StableId, purpose: String, plaintext: ByteArray): ByteArray = keyProvider.open(bookId).use { keys ->
        keys.encryptSecureSettings(plaintext, associatedData(operationId, purpose))
    }

    fun open(bookId: StableId, operationId: StableId, purpose: String, ciphertext: ByteArray): ByteArray = keyProvider.open(bookId).use { keys ->
        keys.decryptSecureSettings(ciphertext, associatedData(operationId, purpose))
    }

    private fun <T> withDatabase(bookId: StableId, block: (app.ledger.core.database.LedgerDatabase) -> T): T = keyProvider.open(bookId).use { keys ->
        keys.databaseDek.useBytes { key ->
            val database = EncryptedDatabaseFactory.openPrimary(applicationContext, key)
            try {
                block(database)
            } finally {
                database.close()
            }
        }
    }

    private fun associatedData(operationId: StableId, purpose: String): ByteArray {
        require(purpose.matches(Regex("[a-z0-9_-]{2,40}")))
        return "ledger-operation-v1\u0000$purpose\u0000".toByteArray(Charsets.US_ASCII) + operationId.bytes
    }
}
