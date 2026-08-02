package app.ledger.finance.data

import app.ledger.core.common.DomainResult
import app.ledger.core.database.LedgerDatabase
import app.ledger.core.money.CurrencyCode
import app.ledger.finance.application.BookRepository
import app.ledger.finance.application.FinanceDataError
import app.ledger.finance.domain.Book
import app.ledger.finance.domain.BookCommitId
import app.ledger.finance.domain.BookId
import app.ledger.finance.domain.BookState
import app.ledger.finance.domain.EntityType
import app.ledger.finance.domain.LocalRevision
import app.ledger.finance.domain.PurgeTombstone
import app.ledger.finance.domain.RuleSetVersion
import app.ledger.finance.domain.StableEntityReference
import app.ledger.finance.domain.TransactionId
import java.time.ZoneId

class RoomBookRepository(
    private val database: LedgerDatabase,
) : BookRepository {
    override suspend fun current(): DomainResult<Book> = protect {
        database.readLedger { connection ->
            val book = connection.queryOne(
                "SELECT b.uid, b.base_currency, b.default_zone_id, bc.uid AS head_uid, b.local_revision, " +
                    "b.valuation_revision, b.rule_set_version, b.created_at, b.first_financial_commit_at, b.state " +
                    "FROM book b JOIN book_commit bc ON bc.id = b.head_commit_id WHERE b.id = 1",
            ) { cursor ->
                Book(
                    id = BookId(cursor.stableId("uid")),
                    baseCurrency = CurrencyCode.parse(cursor.getString(cursor.getColumnIndexOrThrow("base_currency"))).valueOrAbort(),
                    defaultZoneId = ZoneId.of(cursor.getString(cursor.getColumnIndexOrThrow("default_zone_id"))),
                    headCommitId = BookCommitId(cursor.stableId("head_uid")),
                    localRevision = LocalRevision.of(cursor.getLong(cursor.getColumnIndexOrThrow("local_revision"))).valueOrAbort(),
                    valuationRevision = LocalRevision.of(cursor.getLong(cursor.getColumnIndexOrThrow("valuation_revision"))).valueOrAbort(),
                    ruleSetVersion = RuleSetVersion.of(cursor.getInt(cursor.getColumnIndexOrThrow("rule_set_version"))).valueOrAbort(),
                    createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")).toStoredInstant(),
                    firstFinancialCommitAt = cursor.nullableLong("first_financial_commit_at")?.toStoredInstant(),
                    state = BookState.entries[cursor.getInt(cursor.getColumnIndexOrThrow("state"))],
                )
            } ?: abort(FinanceDataError.CorruptData)
            DomainResult.Success(book)
        }
    }

    override suspend fun purgeTombstone(transactionId: TransactionId): DomainResult<PurgeTombstone?> = protect {
        database.readLedger { connection ->
            val tombstone = connection.queryOne(
                "SELECT pt.entity_uid, bc.uid AS commit_uid, pt.purged_at, pt.purge_generation " +
                    "FROM purge_tombstone pt JOIN book_commit bc ON bc.id = pt.purge_commit_id " +
                    "WHERE pt.entity_type = ? AND pt.entity_uid = ?",
                arrayOf(EntityType.TRANSACTION.ordinal, transactionId.value.bytes),
            ) { cursor ->
                PurgeTombstone(
                    entity = StableEntityReference(EntityType.TRANSACTION, cursor.stableId("entity_uid")),
                    purgeCommitId = BookCommitId(cursor.stableId("commit_uid")),
                    purgedAt = cursor.getLong(cursor.getColumnIndexOrThrow("purged_at")).toStoredInstant(),
                    purgeGeneration = cursor.getLong(cursor.getColumnIndexOrThrow("purge_generation")),
                )
            }
            DomainResult.Success(tombstone)
        }
    }

    private inline fun <T> protect(block: () -> DomainResult<T>): DomainResult<T> = try {
        block()
    } catch (abort: FinancialPersistenceAbort) {
        DomainResult.Failure(abort.domainError)
    } catch (_: Exception) {
        DomainResult.Failure(FinanceDataError.DatabaseUnavailable)
    }
}
