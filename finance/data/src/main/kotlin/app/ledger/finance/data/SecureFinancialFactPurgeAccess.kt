package app.ledger.finance.data

import androidx.sqlite.db.SupportSQLiteDatabase
import app.ledger.core.common.StableId
import app.ledger.core.security.SecureLedgerFactPurgeAccess
import app.ledger.core.security.SecurePrimaryLedgerAccess

/** Owns the narrowly guarded immutable-fact deletion transaction used by retention and purge workflows. */
class SecureFinancialFactPurgeAccess(
    private val access: SecurePrimaryLedgerAccess,
) : SecureLedgerFactPurgeAccess {
    override fun <T> write(bookId: StableId, block: (SupportSQLiteDatabase) -> T): T = access.write(bookId) { database ->
        val entered = database.compileStatement("UPDATE book SET state=1 WHERE id=1 AND state=0").executeUpdateDelete()
        check(entered == 1) { "ledger is not available for immutable fact purge" }
        database.execSQL("UPDATE _schema_runtime_guard SET allow_fact_purge=1 WHERE id=1")
        try {
            block(database)
        } finally {
            database.execSQL("UPDATE _schema_runtime_guard SET allow_fact_purge=0 WHERE id=1")
            database.execSQL("UPDATE book SET state=0 WHERE id=1 AND state=1")
        }
    }
}
