package app.ledger.core.security

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.ledger.core.common.StableId
import app.ledger.core.database.EncryptedDatabaseFactory
import java.security.MessageDigest

/** Opens one independently keyed SQLCipher staging database without exposing the primary ledger key. */
class SecureImportStagingAccess(
    context: Context,
    private val keyProvider: DeviceLedgerKeyProvider,
) {
    private val applicationContext = context.applicationContext

    fun <T> write(
        bookId: StableId,
        operationFileName: String,
        block: (SupportSQLiteDatabase) -> T,
    ): T = withDatabase(bookId, operationFileName) { database -> database.inStagingTransaction(block) }

    fun <T> read(
        bookId: StableId,
        operationFileName: String,
        block: (SupportSQLiteDatabase) -> T,
    ): T = withDatabase(bookId, operationFileName) { database -> database.readStaging(block) }

    fun create(bookId: StableId, operationFileName: String) {
        write(bookId, operationFileName) { Unit }
    }

    fun destroy(operationFileName: String): Boolean {
        require(STAGING_NAME.matches(operationFileName))
        return applicationContext.deleteDatabase(operationFileName)
    }

    fun databaseExists(operationFileName: String): Boolean {
        require(STAGING_NAME.matches(operationFileName))
        return applicationContext.getDatabasePath(operationFileName).isFile
    }

    private fun <T> withDatabase(
        bookId: StableId,
        operationFileName: String,
        block: (app.ledger.core.database.ImportStagingDatabase) -> T,
    ): T = keyProvider.open(bookId).use { keys ->
        keys.databaseDek.useBytes { primaryKey ->
            val stagingKey = MessageDigest.getInstance("SHA-256").digest(
                STAGING_KEY_CONTEXT + operationFileName.toByteArray(Charsets.US_ASCII) + primaryKey,
            )
            try {
                val database = EncryptedDatabaseFactory.openImportStaging(applicationContext, operationFileName, stagingKey)
                try {
                    block(database)
                } finally {
                    database.close()
                }
            } finally {
                stagingKey.fill(0)
            }
        }
    }

    private companion object {
        val STAGING_NAME = Regex("import_[0-9a-f]{32}\\.db")
        val STAGING_KEY_CONTEXT = "ledger-import-staging-v1\u0000".toByteArray(Charsets.US_ASCII)
    }
}
