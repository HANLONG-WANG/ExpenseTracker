package app.ledger.transfer.data

import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.security.SecurePrimaryLedgerAccess
import app.ledger.finance.domain.Hash256
import app.ledger.finance.domain.TransactionId
import app.ledger.transfer.domain.DuplicateMatch
import app.ledger.transfer.domain.DuplicateMatchKind
import app.ledger.transfer.domain.ExistingTransactionMatcher
import app.ledger.transfer.domain.ImportFailure

/** Matches against prior durable import source hashes without exposing source content. */
class PrimaryLedgerDuplicateMatcher(
    private val bookId: StableId,
    private val access: SecurePrimaryLedgerAccess,
) : ExistingTransactionMatcher {
    override suspend fun find(rowNumber: Long, canonicalPayloadHash: Hash256): DomainResult<DuplicateMatch?> = try {
        require(rowNumber > 0L)
        DomainResult.Success<DuplicateMatch?>(
            access.read(bookId) { database ->
                database.query(
                    "SELECT bt.uid FROM import_source_reference isr " +
                        "JOIN business_transaction bt ON bt.id=isr.transaction_id " +
                        "WHERE isr.source_row_hash=? AND bt.lifecycle_state=0 ORDER BY isr.row_number LIMIT 1",
                    arrayOf(canonicalPayloadHash.bytes),
                ).use { cursor ->
                    if (!cursor.moveToFirst()) {
                        null
                    } else {
                        DuplicateMatch(
                            TransactionId(StableId.fromBytes(cursor.getBlob(0)).requireValue()),
                            DuplicateMatchKind.CONTENT_HASH,
                            "CONTENT_HASH",
                        )
                    }
                }
            },
        )
    } catch (_: Exception) {
        DomainResult.Failure(ImportFailure.ValidationFailed)
    }
}
