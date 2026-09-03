@file:Suppress(
    "LongMethod",
    "MagicNumber",
    "MaxLineLength",
    "NestedBlockDepth",
    "ReturnCount",
    "TooGenericExceptionCaught",
)

package app.ledger.finance.data

import android.content.Context
import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.database.EncryptedDatabaseFactory
import app.ledger.core.database.LedgerDatabase
import app.ledger.core.database.LedgerMigrations
import app.ledger.core.security.DeviceLedgerKeyProvider
import app.ledger.core.security.LedgerAccessMode
import app.ledger.core.security.LedgerDatabaseOperationAccess
import app.ledger.finance.application.CurrentTransactionCursor
import app.ledger.finance.application.FinanceDataError
import app.ledger.finance.application.JournalApplicationPort
import app.ledger.finance.application.JournalPageRequest
import app.ledger.finance.application.JournalTransactionView
import app.ledger.finance.application.LedgerExportBookMetadata
import app.ledger.finance.application.LedgerExportCursor
import app.ledger.finance.application.LedgerExportPage
import app.ledger.finance.application.LedgerExportQueryPort
import app.ledger.finance.application.LedgerWorkbookSheet
import app.ledger.finance.domain.TransactionFilter
import app.ledger.finance.domain.TransactionId

/** Read-only, field-allowlisted SQLCipher export adapter. It has no vault table or generic table/column entry point. */
class SecureRoomLedgerExportQueryPort(
    private val databaseAccess: LedgerDatabaseOperationAccess,
    private val journal: JournalApplicationPort,
) : LedgerExportQueryPort {

    override suspend fun metadata(bookId: StableId): DomainResult<LedgerExportBookMetadata> = withDatabase(bookId) { database ->
        database.readLedger { db ->
            db.query("SELECT local_revision,valuation_revision FROM book WHERE id=1").use { cursor ->
                check(cursor.moveToFirst())
                LedgerExportBookMetadata(cursor.getLong(0), cursor.getLong(1), LedgerMigrations.CURRENT_VERSION)
            }
        }
    }

    override suspend fun currentTransactions(
        bookId: StableId,
        filter: TransactionFilter,
        headers: List<String>,
        cursor: LedgerExportCursor?,
        limit: Int,
    ): DomainResult<LedgerExportPage> {
        if (headers.isEmpty() || headers.any { it !in TRANSACTION_HEADERS } || limit !in 1..PAGE_LIMIT) {
            return DomainResult.Failure(FinanceDataError.CorruptData)
        }
        val page = when (
            val loaded = journal.page(
                JournalPageRequest(
                    bookId,
                    filter,
                    limit.coerceAtMost(CURRENT_QUERY_PAGE_LIMIT),
                    cursor?.let { CurrentTransactionCursor(it.orderValue, TransactionId(it.stableTieBreaker)) },
                ),
            )
        ) {
            is DomainResult.Success -> loaded.value
            is DomainResult.Failure -> return loaded
        }
        return withDatabase(bookId) { database ->
            database.readLedger { db ->
                val rows = page.items.map { item ->
                    val supplemental = supplemental(db, item.transactionId)
                    headers.map { header -> transactionValue(header, item, supplemental) }
                }
                LedgerExportPage(
                    headers,
                    rows,
                    page.nextCursor?.let { LedgerExportCursor(it.occurredAtEpochMillis, it.transactionId.value) },
                )
            }
        }
    }

    override suspend fun workbookSheet(
        bookId: StableId,
        sheet: LedgerWorkbookSheet,
        includeLocationCoordinates: Boolean,
        afterInternalId: Long,
        limit: Int,
    ): DomainResult<LedgerExportPage> {
        if (afterInternalId < 0L || limit !in 1..PAGE_LIMIT) return DomainResult.Failure(FinanceDataError.CorruptData)
        val spec = workbookSpec(sheet, includeLocationCoordinates)
        return withDatabase(bookId) { database ->
            database.readLedger { db ->
                db.query("${spec.select} WHERE ${spec.keyColumn} > ? ORDER BY ${spec.keyColumn} LIMIT ?", arrayOf<Any?>(afterInternalId, limit)).use { cursor ->
                    val headers = (1 until cursor.columnCount).map(cursor::getColumnName)
                    val rows = ArrayList<List<String>>(limit)
                    var lastId = afterInternalId
                    var lastUid = ZERO_ID
                    while (cursor.moveToNext()) {
                        lastId = cursor.getLong(0)
                        lastUid = cursor.stableIdOrZero(1)
                        rows += (1 until cursor.columnCount).map { index -> cursor.exportString(index) }
                    }
                    LedgerExportPage(
                        headers,
                        rows,
                        if (rows.size == limit) LedgerExportCursor(lastId, lastUid) else null,
                    )
                }
            }
        }
    }

    private suspend fun <T> withDatabase(bookId: StableId, block: suspend (LedgerDatabase) -> T): DomainResult<T> = try {
        DomainResult.Success(databaseAccess.withCurrentDatabase(bookId, LedgerAccessMode.READ, block))
    } catch (_: Exception) {
        DomainResult.Failure(FinanceDataError.DatabaseUnavailable)
    }

    private data class Supplemental(
        val zoneId: String,
        val note: String,
        val merchant: String,
        val project: String,
        val settlement: String,
        val place: String,
        val latitudeE7: String,
        val longitudeE7: String,
        val attachments: String,
    )

    private fun supplemental(db: SupportSQLiteDatabase, transactionId: StableId): Supplemental {
        val base = db.query(
            "SELECT tr.zone_id,COALESCE(tr.note,''),COALESCE(m.name,''),COALESCE(p.name,''),COALESCE(sa.name,'')," +
                "COALESCE(pl.name,''),lr.lat_e7,lr.lon_e7 FROM business_transaction bt JOIN transaction_revision tr ON tr.id=bt.current_revision_id " +
                "LEFT JOIN merchant m ON m.id=tr.merchant_id LEFT JOIN project p ON p.id=tr.project_id " +
                "LEFT JOIN expense_revision_detail ed ON ed.revision_id=tr.id LEFT JOIN settlement_payment_revision_detail spd ON spd.revision_id=tr.id " +
                "LEFT JOIN settlement_activity sa ON sa.id=COALESCE(ed.settlement_activity_id,spd.activity_id) " +
                "LEFT JOIN location_record lr ON lr.id=tr.location_record_id LEFT JOIN place pl ON pl.id=lr.place_id WHERE bt.uid=?",
            arrayOf(transactionId.bytes),
        ).use { cursor ->
            if (!cursor.moveToFirst()) error("transaction projection without current revision")
            Supplemental(
                cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getString(4), cursor.getString(5),
                if (cursor.isNull(6)) "" else cursor.getLong(6).toString(), if (cursor.isNull(7)) "" else cursor.getLong(7).toString(), "",
            )
        }
        val attachments = db.query(
            "SELECT a.display_name FROM business_transaction bt JOIN transaction_revision tr ON tr.id=bt.current_revision_id " +
                "JOIN transaction_revision_attachment tra ON tra.revision_id=tr.id JOIN attachment a ON a.id=tra.attachment_id " +
                "WHERE bt.uid=? ORDER BY tra.sort_order",
            arrayOf(transactionId.bytes),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }.joinToString(" | ") }
        return base.copy(attachments = attachments)
    }

    private fun transactionValue(header: String, item: JournalTransactionView, extra: Supplemental): String = when (header) {
        "transaction_id" -> item.transactionId.toString()
        "transaction_type" -> item.kind.name
        "state" -> item.state.name
        "occurred_at" -> item.occurredAt.toString()
        "local_date" -> item.localDate.toString()
        "time_zone" -> extra.zoneId
        "amount_minor" -> item.amountMinor.toString()
        "currency" -> item.currency.value
        "original_amount_minor" -> item.secondaryAmountMinor?.toString().orEmpty()
        "original_currency" -> item.secondaryCurrency?.value.orEmpty()
        "account" -> item.accountAndCard.substringBefore(" · ")
        "card_display_name" -> item.accountAndCard.substringAfter(" · ", "")
        "category" -> item.categoryOrType
        "merchant" -> extra.merchant
        "project" -> extra.project
        "settlement_activity" -> extra.settlement
        "place" -> extra.place
        "note" -> extra.note
        "attachment_references" -> extra.attachments
        "source" -> item.source.name
        "latitude_e7" -> extra.latitudeE7
        "longitude_e7" -> extra.longitudeE7
        else -> error("closed export header")
    }

    private data class WorkbookSpec(val select: String, val keyColumn: String = "id")

    private fun workbookSpec(sheet: LedgerWorkbookSheet, coordinates: Boolean): WorkbookSpec = when (sheet) {
        LedgerWorkbookSheet.ACCOUNTS -> WorkbookSpec("SELECT ua.id,ua.uid AS account_id,ua.type AS account_type,ua.name,ua.currency_code,COALESCE(ua.institution_name,'') AS institution,COALESCE(ua.branch_name,'') AS branch,ua.status,ua.sort_order FROM user_account ua")
        LedgerWorkbookSheet.CARDS -> WorkbookSpec("SELECT pc.id,pc.uid AS card_id,ua.uid AS account_id,pc.card_type,pc.display_name,COALESCE(pc.last_four,'') AS last_four,pc.status,pc.sort_order FROM payment_card pc JOIN user_account ua ON ua.id=pc.account_id", "pc.id")
        LedgerWorkbookSheet.CATEGORIES -> WorkbookSpec("SELECT c.id,c.uid AS category_id,c.direction,COALESCE(parent.uid,X'') AS parent_id,c.depth,c.name,c.icon_key,c.color_argb,c.sort_order,c.status FROM category c LEFT JOIN category parent ON parent.id=c.parent_id", "c.id")
        LedgerWorkbookSheet.MERCHANTS -> WorkbookSpec("SELECT m.id,m.uid AS merchant_id,m.name,m.status,COALESCE(parent.uid,X'') AS merged_into_id FROM merchant m LEFT JOIN merchant parent ON parent.id=m.merged_into_id", "m.id")
        LedgerWorkbookSheet.PLACES -> if (coordinates) WorkbookSpec("SELECT p.id,p.uid AS place_id,p.name,p.center_lat_e7,p.center_lon_e7,p.status,COALESCE(m.uid,X'') AS merchant_id FROM place p LEFT JOIN merchant m ON m.id=p.merchant_id", "p.id") else WorkbookSpec("SELECT p.id,p.uid AS place_id,p.name,p.status,COALESCE(m.uid,X'') AS merchant_id FROM place p LEFT JOIN merchant m ON m.id=p.merchant_id", "p.id")
        LedgerWorkbookSheet.PROJECTS -> WorkbookSpec("SELECT p.id,p.uid AS project_id,p.name,COALESCE(p.description,'') AS description,p.start_date,p.end_date,p.budget_base_minor,p.included_in_monthly_budget,p.status FROM project p", "p.id")
        LedgerWorkbookSheet.SETTLEMENTS -> WorkbookSpec("SELECT s.id,s.uid AS settlement_id,s.name,COALESCE(s.description,'') AS description,s.settlement_currency,s.start_date,s.end_date,s.status,s.requires_additional_settlement FROM settlement_activity s", "s.id")
        LedgerWorkbookSheet.TRANSACTIONS -> transactionWorkbookSpec(coordinates)
        LedgerWorkbookSheet.CREDIT_STATEMENTS -> WorkbookSpec("SELECT s.id,s.uid AS statement_id,ua.uid AS account_id,s.cycle_start,s.cycle_end,s.due_date,s.status FROM credit_statement s JOIN user_account ua ON ua.id=s.credit_account_id", "s.id")
        LedgerWorkbookSheet.INSTALLMENTS -> WorkbookSpec("SELECT p.id,p.uid AS installment_id,bt.uid AS purchase_transaction_id,ua.uid AS account_id,p.currency_code,p.original_principal_minor,p.term_count,p.status FROM installment_plan p JOIN business_transaction bt ON bt.id=p.purchase_transaction_id JOIN user_account ua ON ua.id=p.credit_account_id", "p.id")
        LedgerWorkbookSheet.LOANS -> WorkbookSpec("SELECT l.id,l.uid AS loan_id,ua.uid AS account_id,l.name,COALESCE(l.lender,'') AS lender,l.currency_code,l.disbursement_date,l.status FROM loan_contract l JOIN user_account ua ON ua.id=l.display_account_id", "l.id")
        LedgerWorkbookSheet.BUDGETS -> WorkbookSpec("SELECT b.id,b.uid AS budget_id,b.name,b.status,COALESCE(r.total_base_minor,0) AS total_base_minor FROM budget_template b LEFT JOIN budget_template_revision r ON r.id=b.current_revision_id", "b.id")
        LedgerWorkbookSheet.GOALS -> WorkbookSpec("SELECT g.id,g.uid AS goal_id,ua.uid AS account_id,g.name,g.target_amount_minor,g.due_date,g.suggested_monthly_minor,g.status FROM goal g JOIN user_account ua ON ua.id=g.account_id", "g.id")
        LedgerWorkbookSheet.RECURRENCES -> WorkbookSpec("SELECT s.id,s.uid AS recurrence_id,b.uid AS blueprint_id,s.status,r.frequency,r.interval_value,r.start_date,r.end_date,r.zone_id,r.generation_mode FROM recurrence_series s JOIN transaction_blueprint b ON b.id=s.blueprint_id LEFT JOIN recurrence_series_revision r ON r.id=s.current_revision_id", "s.id")
        LedgerWorkbookSheet.LOCATIONS -> if (coordinates) WorkbookSpec("SELECT l.id,l.uid AS location_id,l.lat_e7,l.lon_e7,l.accuracy_mm,l.captured_at,l.source,COALESCE(p.uid,X'') AS place_id FROM location_record l LEFT JOIN place p ON p.id=l.place_id", "l.id") else WorkbookSpec("SELECT l.id,l.uid AS location_id,l.accuracy_mm,l.captured_at,l.source,COALESCE(p.uid,X'') AS place_id FROM location_record l LEFT JOIN place p ON p.id=l.place_id", "l.id")
    }

    private fun transactionWorkbookSpec(coordinates: Boolean): WorkbookSpec {
        val location = if (coordinates) ",lr.lat_e7 AS latitude_e7,lr.lon_e7 AS longitude_e7" else ""
        return WorkbookSpec(
            "SELECT ctp.transaction_id,bt.uid AS transaction_id,bt.kind,bt.lifecycle_state,tr.occurred_at,tr.local_date,tr.zone_id," +
                "ctp.account_amount_minor,ctp.account_currency,ctp.input_amount_minor,ctp.input_currency,COALESCE(ua.name,'') AS account," +
                "COALESCE(pc.display_name,'') AS card_display_name,COALESCE(c.name,'') AS category,COALESCE(m.name,'') AS merchant," +
                "COALESCE(p.name,'') AS project,COALESCE(pl.name,'') AS place,COALESCE(tr.note,'') AS note,ctp.source_type$location " +
                "FROM current_transaction_projection ctp JOIN business_transaction bt ON bt.id=ctp.transaction_id JOIN transaction_revision tr ON tr.id=ctp.current_revision_id " +
                "LEFT JOIN user_account ua ON ua.id=ctp.primary_account_id LEFT JOIN payment_card pc ON pc.id=ctp.card_id LEFT JOIN category c ON c.id=ctp.category_id " +
                "LEFT JOIN merchant m ON m.id=ctp.merchant_id LEFT JOIN project p ON p.id=ctp.project_id LEFT JOIN location_record lr ON lr.id=tr.location_record_id LEFT JOIN place pl ON pl.id=lr.place_id",
            "ctp.transaction_id",
        )
    }

    private companion object {
        const val PAGE_LIMIT = 512
        const val CURRENT_QUERY_PAGE_LIMIT = 200
        val ZERO_ID: StableId = StableId.fromBytes(ByteArray(StableId.BYTE_COUNT)).let { result ->
            when (result) {
                is DomainResult.Success -> result.value
                is DomainResult.Failure -> error("StableId zero")
            }
        }
        val TRANSACTION_HEADERS = setOf(
            "transaction_id", "transaction_type", "state", "occurred_at", "local_date", "time_zone", "amount_minor", "currency",
            "original_amount_minor", "original_currency", "account", "card_display_name", "category", "merchant", "project",
            "settlement_activity", "place", "note", "attachment_references", "source", "latitude_e7", "longitude_e7",
        )
    }
}

private fun Cursor.exportString(index: Int): String = when (getType(index)) {
    Cursor.FIELD_TYPE_NULL -> ""
    Cursor.FIELD_TYPE_BLOB -> getBlob(index).let { bytes ->
        if (bytes.size == StableId.BYTE_COUNT) {
            StableId.fromBytes(bytes).let { result ->
                when (result) {
                    is DomainResult.Success -> result.value.toString()
                    is DomainResult.Failure -> ""
                }
            }
        } else {
            ""
        }
    }
    Cursor.FIELD_TYPE_FLOAT -> getDouble(index).toString()
    Cursor.FIELD_TYPE_INTEGER -> getLong(index).toString()
    else -> getString(index)
}

private fun Cursor.stableIdOrZero(index: Int): StableId {
    if (isNull(index) || getType(index) != Cursor.FIELD_TYPE_BLOB) return StableId.fromBytes(ByteArray(StableId.BYTE_COUNT)).let { (it as DomainResult.Success).value }
    return StableId.fromBytes(getBlob(index)).let { result -> (result as? DomainResult.Success)?.value }
        ?: StableId.fromBytes(ByteArray(StableId.BYTE_COUNT)).let { (it as DomainResult.Success).value }
}
