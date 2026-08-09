package app.ledger.app

import app.ledger.core.common.StableId
import app.ledger.core.security.SecurePrimaryLedgerAccess
import app.ledger.finance.data.SecureFinancialFactPurgeAccess
import app.ledger.transfer.data.SqlCipherBackupCatalog

internal fun createBackupCatalog(
    bookId: StableId,
    access: SecurePrimaryLedgerAccess,
): SqlCipherBackupCatalog = SqlCipherBackupCatalog(
    bookId,
    access,
    SecureFinancialFactPurgeAccess(access),
)
