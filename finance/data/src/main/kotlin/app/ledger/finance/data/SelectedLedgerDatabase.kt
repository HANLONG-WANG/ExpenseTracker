package app.ledger.finance.data

import android.content.Context
import app.ledger.core.database.EncryptedDatabaseFactory
import app.ledger.core.database.LedgerDatabase

/** Opens either the live ledger or an opaque same-key shadow selected by an application adapter. */
internal fun openSelectedLedger(
    context: Context,
    passphrase: ByteArray,
    databaseName: String,
): LedgerDatabase = if (databaseName == EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME) {
    EncryptedDatabaseFactory.openPrimary(context, passphrase)
} else {
    EncryptedDatabaseFactory.openLedgerCopy(context, databaseName, passphrase)
}
