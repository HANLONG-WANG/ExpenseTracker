@file:Suppress("MatchingDeclarationName", "ktlint:standard:filename")

package app.ledger.finance.data

import android.content.Context
import app.ledger.core.database.LedgerDatabase
import app.ledger.core.security.DeviceLedgerKeyProvider
import app.ledger.core.security.LedgerAccessMode
import app.ledger.core.security.LedgerDatabaseOperationAccess

/** Explicit copy-only access for import/shadow work; the selected live primary is rejected. */
internal class OfflineSelectedLedgerDatabaseAccess(
    context: Context,
    private val keyProvider: DeviceLedgerKeyProvider,
    private val databaseName: String,
) : LedgerDatabaseOperationAccess {
    private val applicationContext = context.applicationContext

    init {
        require(databaseName != app.ledger.core.database.EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME) {
            "offline access cannot target the live primary"
        }
    }

    override suspend fun <T> withCurrentDatabase(
        bookId: app.ledger.core.common.StableId,
        mode: LedgerAccessMode,
        block: suspend (LedgerDatabase) -> T,
    ): T = keyProvider.open(bookId).use { keys ->
        val database = keys.databaseDek.useBytes { passphrase ->
            app.ledger.core.database.EncryptedDatabaseFactory.openLedgerCopy(applicationContext, databaseName, passphrase)
        }
        try {
            block(database)
        } finally {
            database.close()
        }
    }
}
