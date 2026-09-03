package app.ledger.finance.data

import android.content.Context
import app.ledger.core.common.StableId
import app.ledger.core.database.EncryptedDatabaseFactory
import app.ledger.core.database.LedgerDatabase
import app.ledger.core.security.DeviceLedgerKeyProvider
import app.ledger.core.security.LedgerAccessMode
import app.ledger.core.security.LedgerSecureSettings
import app.ledger.core.security.LedgerSessionOperationAccess

/**
 * Explicit AndroidTest fixture for legacy port-level integration tests.
 *
 * Production code must obtain the live primary database from the process book session. These
 * isolated tests instead open and close the primary around each operation so they do not need to
 * duplicate application lifecycle setup merely to exercise an individual adapter.
 */
internal class DeviceTestLedgerDatabaseAccess(
    context: Context,
    private val keyProvider: DeviceLedgerKeyProvider,
) : LedgerSessionOperationAccess {
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

    override suspend fun <T> withCurrentSecureSettings(
        bookId: StableId,
        mode: LedgerAccessMode,
        block: suspend (LedgerSecureSettings) -> T,
    ): T = keyProvider.open(bookId).use { keys -> block(keys) }
}
