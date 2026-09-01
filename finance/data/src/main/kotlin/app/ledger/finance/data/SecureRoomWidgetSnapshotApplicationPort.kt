@file:Suppress("MagicNumber", "NestedBlockDepth")

package app.ledger.finance.data

import android.content.Context
import android.database.Cursor
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.common.getOrNull
import app.ledger.core.database.EncryptedDatabaseFactory
import app.ledger.core.security.DeviceLedgerKeyProvider
import app.ledger.finance.application.FinanceDataError
import app.ledger.finance.application.WidgetAccountSnapshot
import app.ledger.finance.application.WidgetBookSnapshot
import app.ledger.finance.application.WidgetCreditSnapshot
import app.ledger.finance.application.WidgetGoalSnapshot
import app.ledger.finance.application.WidgetQuickDirection
import app.ledger.finance.application.WidgetQuickTarget
import app.ledger.finance.application.WidgetQuickTargetKind
import app.ledger.finance.application.WidgetSnapshotApplicationPort
import app.ledger.finance.application.WidgetSnapshotBundle
import app.ledger.finance.application.WidgetSnapshotRefreshApplicationPort
import app.ledger.finance.domain.LocalRevision
import java.time.LocalDate

/** Opens SQLCipher and selects only the four widget snapshot tables for the Glance read path. */
class SecureRoomWidgetSnapshotApplicationPort(
    context: Context,
    private val keyProvider: DeviceLedgerKeyProvider,
    private val databaseName: String = EncryptedDatabaseFactory.PRIMARY_DATABASE_NAME,
) : WidgetSnapshotApplicationPort,
    WidgetSnapshotRefreshApplicationPort {
    private val applicationContext = context.applicationContext

    override suspend fun read(bookId: StableId): DomainResult<WidgetSnapshotBundle> = withDatabase(bookId) { database ->
        database.readLedger { connection ->
            val book = connection.query(
                "SELECT core_net_financial_assets_base_minor,adjusted_net_financial_position_base_minor," +
                    "base_currency,as_of_local_revision,as_of_valuation_revision,snapshot_local_date,month_key," +
                    "month_consumption_base_minor,previous_month_consumption_base_minor," +
                    "month_budget_available_base_minor,month_budget_used_base_minor,today_available_base_minor," +
                    "previous_core_net_financial_assets_base_minor " +
                    "FROM widget_book_snapshot WHERE id=1",
            ).use { cursor -> if (cursor.moveToFirst()) cursor.bookSnapshot() else null }
            val accounts = connection.query(
                "SELECT account_uid,display_name,balance_minor,available_minor,currency_code,as_of_local_revision " +
                    "FROM widget_account_snapshot ORDER BY account_id",
            ).use { cursor -> cursor.rows(Cursor::accountSnapshot) }
            val credit = connection.query(
                "SELECT account_uid,display_name,debt_minor,available_limit_minor,statement_remaining_minor," +
                    "statement_due_date,currency_code,as_of_local_revision FROM widget_credit_snapshot ORDER BY account_id",
            ).use { cursor -> cursor.rows(Cursor::creditSnapshot) }
            val goals = connection.query(
                "SELECT goal_uid,display_name,balance_minor,target_minor,currency_code,as_of_local_revision " +
                    "FROM widget_goal_snapshot ORDER BY goal_id",
            ).use { cursor -> cursor.rows(Cursor::goalSnapshot) }
            WidgetSnapshotBundle(book, accounts, credit, goals)
        }
    }

    /** Configuration-only query. Glance rendering never calls this method. */
    override suspend fun quickTargets(bookId: StableId): DomainResult<List<WidgetQuickTarget>> = withDatabase(bookId) { database ->
        database.readLedger { connection ->
            val categories = connection.query(
                "SELECT uid,direction,name FROM category WHERE status=0 ORDER BY direction,sort_order,id",
            ).use { cursor ->
                cursor.rows {
                    WidgetQuickTarget(
                        stableId(0),
                        WidgetQuickTargetKind.CATEGORY,
                        if (getInt(1) == 0) WidgetQuickDirection.EXPENSE else WidgetQuickDirection.INCOME,
                        getString(2),
                    )
                }
            }
            val templates = connection.query(
                "SELECT tb.uid,tbr.target_kind,tb.name FROM transaction_blueprint tb " +
                    "JOIN transaction_blueprint_revision tbr ON tbr.id=tb.current_revision_id " +
                    "WHERE tb.status=0 AND tbr.target_kind IN (0,1) ORDER BY tb.id",
            ).use { cursor ->
                cursor.rows {
                    WidgetQuickTarget(
                        stableId(0),
                        WidgetQuickTargetKind.TEMPLATE,
                        if (getInt(1) == 1) WidgetQuickDirection.INCOME else WidgetQuickDirection.EXPENSE,
                        getString(2),
                    )
                }
            }
            categories + templates
        }
    }

    override suspend fun refreshIfStale(bookId: StableId, localDate: LocalDate): DomainResult<Boolean> = withDatabase(bookId) { database ->
        database.inLedgerTransaction { connection ->
            val date = localDate.year * 10_000 + localDate.monthValue * 100 + localDate.dayOfMonth
            val snapshotVersion = connection.query(
                "SELECT snapshot_local_date,as_of_local_revision,as_of_valuation_revision FROM widget_book_snapshot WHERE id=1",
            ).use { cursor ->
                if (cursor.moveToFirst()) Triple(cursor.getInt(0), cursor.getLong(1), cursor.getLong(2)) else null
            }
            val revisions = connection.query("SELECT local_revision,valuation_revision FROM book WHERE id=1").use { cursor ->
                check(cursor.moveToFirst()) { "active book row missing" }
                cursor.getLong(0) to cursor.getLong(1)
            }
            if (snapshotVersion == Triple(date, revisions.first, revisions.second)) {
                false
            } else {
                RoomProjectionEngine().rebuildWidgetSnapshot(connection, revisions.first, revisions.second, date)
                true
            }
        }
    }

    private suspend fun <T> withDatabase(bookId: StableId, block: suspend (app.ledger.core.database.LedgerDatabase) -> T): DomainResult<T> = try {
        val keys = keyProvider.open(bookId)
        try {
            val passphrase = keys.databaseDek.useBytes(ByteArray::copyOf)
            try {
                val database = openSelectedLedger(applicationContext, passphrase, databaseName)
                try {
                    DomainResult.Success(block(database))
                } finally {
                    database.close()
                }
            } finally {
                passphrase.fill(0)
            }
        } finally {
            keys.close()
        }
    } catch (_: Exception) {
        DomainResult.Failure(FinanceDataError.DatabaseUnavailable)
    }
}

private fun Cursor.bookSnapshot(): WidgetBookSnapshot = WidgetBookSnapshot(
    getLong(0),
    getLong(1),
    getString(2),
    revision(3),
    revision(4),
    getInt(5),
    getInt(6),
    getLong(7),
    getLong(8),
    nullableLong(9),
    nullableLong(10),
    nullableLong(11),
    getLong(12),
)

private fun Cursor.accountSnapshot(): WidgetAccountSnapshot = WidgetAccountSnapshot(
    stableId(0),
    getString(1),
    getLong(2),
    getLong(3),
    getString(4),
    revision(5),
)

private fun Cursor.creditSnapshot(): WidgetCreditSnapshot = WidgetCreditSnapshot(
    stableId(0),
    getString(1),
    getLong(2),
    nullableLong(3),
    nullableLong(4),
    nullableInt(5),
    getString(6),
    revision(7),
)

private fun Cursor.goalSnapshot(): WidgetGoalSnapshot = WidgetGoalSnapshot(
    stableId(0),
    getString(1),
    getLong(2),
    getLong(3),
    getString(4),
    revision(5),
)

private inline fun <T> Cursor.rows(map: Cursor.() -> T): List<T> = buildList {
    while (moveToNext()) add(map())
}

private fun Cursor.stableId(index: Int): StableId = requireNotNull(StableId.fromBytes(getBlob(index)).getOrNull())
private fun Cursor.revision(index: Int): LocalRevision = requireNotNull(LocalRevision.of(getLong(index)).getOrNull())
private fun Cursor.nullableLong(index: Int): Long? = if (isNull(index)) null else getLong(index)
private fun Cursor.nullableInt(index: Int): Int? = if (isNull(index)) null else getInt(index)
