package app.ledger.app

import android.content.Context
import app.ledger.core.common.StableId
import app.ledger.core.database.EncryptedDatabaseFactory
import app.ledger.core.database.LedgerDatabase
import app.ledger.core.security.DeviceLedgerKeyProvider
import app.ledger.core.security.LedgerAccessMode
import app.ledger.core.security.LedgerDatabaseOperationAccess

/** Isolated SQLCipher access fixture for backup/restore AndroidTest cases. */
internal class DeviceTestLedgerDatabaseAccess(
    context: Context,
    private val keyProvider: DeviceLedgerKeyProvider,
) : LedgerDatabaseOperationAccess {
    private val applicationContext = context.applicationContext

    override suspend fun <T> withCurrentDatabase(
        bookId: StableId,
        mode: LedgerAccessMode,
        block: suspend (LedgerDatabase) -> T,
    ): T = keyProvider.open(bookId).use { keys ->
        val database = keys.databaseDek.useBytes { passphrase ->
            EncryptedDatabaseFactory.openPrimary(applicationContext, passphrase)
        }
        try {
            block(database)
        } finally {
            database.close()
        }
    }
}
