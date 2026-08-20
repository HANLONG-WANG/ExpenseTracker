@file:Suppress("LongMethod", "LongParameterList", "MagicNumber", "ReturnCount", "TooManyFunctions")

package app.ledger.analytics.data

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import app.ledger.analytics.domain.ConsumptionMapAggregation
import app.ledger.analytics.domain.ConsumptionMapCategoryComposition
import app.ledger.analytics.domain.ConsumptionMapDetail
import app.ledger.analytics.domain.ConsumptionMapFilterOption
import app.ledger.analytics.domain.ConsumptionMapFilterOptions
import app.ledger.analytics.domain.ConsumptionMapFilters
import app.ledger.analytics.domain.ConsumptionMapGroupKind
import app.ledger.analytics.domain.ConsumptionMapMode
import app.ledger.analytics.domain.ConsumptionMapPoint
import app.ledger.analytics.domain.ConsumptionMapPresentation
import app.ledger.analytics.domain.ConsumptionMapQuery
import app.ledger.analytics.domain.ConsumptionMapResult
import app.ledger.analytics.domain.ConsumptionMapWeight
import app.ledger.analytics.domain.DrilldownCursor
import app.ledger.analytics.domain.DrilldownPage
import app.ledger.analytics.domain.DrilldownQueryId
import app.ledger.analytics.domain.DrilldownTransaction
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.common.getOrNull
import app.ledger.core.money.CurrencyCode
import app.ledger.finance.domain.CategoryId
import app.ledger.finance.domain.LocalRevision
import java.security.MessageDigest
import java.time.LocalDate
import java.util.LinkedHashMap
import kotlin.math.roundToInt

internal class ConsumptionMapStore {
    private val selections = object : LinkedHashMap<StableId, MapSelection>(MAX_SELECTIONS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<StableId, MapSelection>?): Boolean = size > MAX_SELECTIONS
    }

    fun query(database: SupportSQLiteDatabase, query: ConsumptionMapQuery): DomainResult<ConsumptionMapResult> {
        val version = database.version() ?: return DomainResult.Failure(app.ledger.analytics.domain.AnalyticsError.DatabaseUnavailable)
        val currency = database.query("SELECT base_currency FROM book WHERE id=1").use { cursor ->
            if (!cursor.moveToFirst()) return DomainResult.Failure(app.ledger.analytics.domain.AnalyticsError.DatabaseUnavailable)
            CurrencyCode.parse(cursor.getString(0)).getOrNull()
                ?: return DomainResult.Failure(app.ledger.analytics.domain.AnalyticsError.DatabaseUnavailable)
        }
        val compiled = compile(query)
        val summary = database.query(
            "${compiled.withMetric} SELECT COALESCE(SUM(map_amount_minor),0),COUNT(*) FROM map_events",
            compiled.arguments.toTypedArray(),
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0) to cursor.getLong(1)
        }
        val grouping = grouping(query)
        val rows = database.query(
            "${compiled.withMetric} SELECT ${grouping.idExpression} group_uid,${grouping.kindExpression} group_kind," +
                "${grouping.labelExpression} group_label,ROUND(AVG(lat_e7)) latitude_e7,ROUND(AVG(lon_e7)) longitude_e7," +
                "SUM(map_amount_minor) base_amount_minor,COUNT(*) transaction_count FROM map_events " +
                "GROUP BY ${grouping.idExpression},${grouping.kindExpression},${grouping.labelExpression} " +
                "ORDER BY ${if (query.weight == ConsumptionMapWeight.BASE_AMOUNT) "ABS(SUM(map_amount_minor))" else "COUNT(*)"} DESC,group_uid LIMIT ?",
            (compiled.arguments + (ConsumptionMapResult.MAX_RENDERED_POINTS + 1)).toTypedArray(),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.mapPoint()) } }
        return DomainResult.Success(
            ConsumptionMapResult(
                query,
                currency,
                rows.take(ConsumptionMapResult.MAX_RENDERED_POINTS),
                summary.first,
                summary.second,
                version,
                rows.size > ConsumptionMapResult.MAX_RENDERED_POINTS,
            ),
        )
    }

    fun filterOptions(database: SupportSQLiteDatabase): DomainResult<ConsumptionMapFilterOptions> = DomainResult.Success(
        ConsumptionMapFilterOptions(
            options(database, "SELECT uid,name FROM user_account WHERE status=0 ORDER BY sort_order,name LIMIT ?"),
            options(database, "SELECT uid,name FROM category WHERE status=0 ORDER BY sort_order,name LIMIT ?"),
            options(database, "SELECT uid,name FROM merchant WHERE status=0 ORDER BY name LIMIT ?"),
            options(database, "SELECT uid,name FROM place WHERE status=0 ORDER BY name LIMIT ?"),
            options(database, "SELECT uid,name FROM project WHERE status=0 ORDER BY name LIMIT ?"),
        ),
    )

    fun detail(
        database: SupportSQLiteDatabase,
        query: ConsumptionMapQuery,
        pointId: StableId,
    ): DomainResult<ConsumptionMapDetail> {
        val map = query(database, query)
        if (map !is DomainResult.Success) return map.castFailure()
        val compiled = compile(query)
        val grouping = grouping(query)
        val selectionClause = "${grouping.idExpression}=?"
        val selectionArguments = compiled.arguments + pointId.bytes
        val point = database.query(
            "${compiled.withMetric} SELECT ${grouping.idExpression} group_uid,${grouping.kindExpression} group_kind," +
                "${grouping.labelExpression} group_label,ROUND(AVG(lat_e7)) latitude_e7,ROUND(AVG(lon_e7)) longitude_e7," +
                "SUM(map_amount_minor) base_amount_minor,COUNT(*) transaction_count FROM map_events WHERE $selectionClause " +
                "GROUP BY ${grouping.idExpression},${grouping.kindExpression},${grouping.labelExpression}",
            selectionArguments.toTypedArray(),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.mapPoint() else null }
            ?: return DomainResult.Failure(app.ledger.analytics.domain.AnalyticsError.DefinitionNotFound)
        val categories = database.query(
            "${compiled.withMetric} SELECT category_uid,category_name,SUM(map_amount_minor),COUNT(*) FROM map_events " +
                "WHERE $selectionClause GROUP BY category_uid,category_name ORDER BY ABS(SUM(map_amount_minor)) DESC LIMIT ?",
            (selectionArguments + ConsumptionMapDetail.MAX_CATEGORY_ROWS).toTypedArray(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        ConsumptionMapCategoryComposition(
                            if (cursor.isNull(0)) null else CategoryId(cursor.stableId(0)),
                            if (cursor.isNull(1)) null else cursor.getString(1),
                            cursor.getLong(2),
                            cursor.getLong(3),
                        ),
                    )
                }
            }
        }
        val previews = transactionRows(database, compiled, grouping, pointId, null, ConsumptionMapDetail.MAX_PREVIEW_ROWS).rows
        val queryId = register(MapSelection(query, pointId, map.value.asOfLocalRevision))
        return DomainResult.Success(
            ConsumptionMapDetail(point, map.value.baseCurrency, categories, previews, queryId, map.value.asOfLocalRevision),
        )
    }

    fun selection(queryId: DrilldownQueryId): MapSelection? = synchronized(selections) { selections[queryId.value] }

    fun drillDown(
        database: SupportSQLiteDatabase,
        selection: MapSelection,
        cursor: DrilldownCursor?,
        limit: Int,
    ): DomainResult<DrilldownPage> {
        val current = database.version()
            ?: return DomainResult.Failure(app.ledger.analytics.domain.AnalyticsError.DatabaseUnavailable)
        if (current != selection.asOfLocalRevision) {
            return DomainResult.Failure(app.ledger.analytics.domain.AnalyticsError.ExpiredDrilldown)
        }
        val compiled = compile(selection.query)
        return DomainResult.Success(transactionRows(database, compiled, grouping(selection.query), selection.pointId, cursor, limit))
    }

    private fun transactionRows(
        database: SupportSQLiteDatabase,
        compiled: CompiledMap,
        grouping: Grouping,
        pointId: StableId,
        cursor: DrilldownCursor?,
        limit: Int,
    ): DrilldownPage {
        val clauses = mutableListOf("${grouping.idExpression}=?")
        val args = compiled.arguments.toMutableList().apply { add(pointId.bytes) }
        if (cursor != null) {
            clauses += "(occurred_at<? OR (occurred_at=? AND transaction_uid<?))"
            args += cursor.occurredAtEpochMillis
            args += cursor.occurredAtEpochMillis
            args += cursor.transactionId.bytes
        }
        args += Math.addExact(limit, 1)
        val rows = database.query(
            "${compiled.withMetric} SELECT transaction_uid,local_date,transaction_kind,input_amount_minor,input_currency," +
                "category_name,primary_account_name,card_name,merchant_name,occurred_at " +
                "FROM map_events WHERE ${clauses.joinToString(" AND ")} ORDER BY occurred_at DESC,transaction_uid DESC LIMIT ?",
            args.toTypedArray(),
        ).use { result ->
            buildList {
                while (result.moveToNext()) {
                    add(
                        TransactionWithTime(
                            DrilldownTransaction(
                                result.stableId(0),
                                result.getInt(1).toLocalDate(),
                                TRANSACTION_KIND_KEYS.getOrElse(result.getInt(2)) { "UNKNOWN" },
                                result.getLong(3),
                                CurrencyCode.parse(result.getString(4)).getOrNull() ?: error("invalid transaction currency"),
                                result.optionalString(5),
                                result.optionalString(6),
                                result.optionalString(7),
                                result.optionalString(8),
                            ),
                            result.getLong(9),
                        ),
                    )
                }
            }
        }
        val visible = rows.take(limit)
        val next = if (rows.size > limit) visible.last().let { DrilldownCursor(it.occurredAt, it.row.transactionId) } else null
        return DrilldownPage(visible.map(TransactionWithTime::row), next)
    }

    private fun register(selection: MapSelection): DrilldownQueryId = synchronized(selections) {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(selection.query.toString().toByteArray(Charsets.UTF_8))
        digest.update(selection.pointId.bytes)
        digest.update(selection.asOfLocalRevision.value.toString().toByteArray(Charsets.UTF_8))
        val id = StableId.fromBytes(digest.digest().copyOf(StableId.BYTE_COUNT)).getOrNull() ?: error("invalid map query id")
        selections[id] = selection
        DrilldownQueryId(id)
    }

    private fun options(database: SupportSQLiteDatabase, sql: String): List<ConsumptionMapFilterOption> = database.query(
        sql,
        arrayOf(ConsumptionMapFilterOptions.MAX_OPTIONS_PER_DIMENSION),
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(ConsumptionMapFilterOption(cursor.stableId(0), cursor.getString(1)))
        }
    }

    private fun compile(query: ConsumptionMapQuery): CompiledMap {
        val args = mutableListOf<Any?>()
        val clauses = mutableListOf("ctp.state=0", "ctp.has_location=1", "ctp.local_date BETWEEN ? AND ?")
        args += query.period.start.storageKey()
        args += query.period.endInclusive.storageKey()
        val viewport = query.viewport
        clauses += "lrt.max_lat>=? AND lrt.min_lat<=?"
        args += viewport.minimumLatitudeE7 / E7_SCALE
        args += viewport.maximumLatitudeE7 / E7_SCALE
        clauses += "lr.lat_e7 BETWEEN ? AND ?"
        args += viewport.minimumLatitudeE7
        args += viewport.maximumLatitudeE7
        if (viewport.crossesDateLine) {
            clauses += "(lrt.max_lon>=? OR lrt.min_lon<=?)"
        } else {
            clauses += "lrt.max_lon>=? AND lrt.min_lon<=?"
        }
        args += viewport.minimumLongitudeE7 / E7_SCALE
        args += viewport.maximumLongitudeE7 / E7_SCALE
        if (viewport.crossesDateLine) {
            clauses += "(lr.lon_e7>=? OR lr.lon_e7<=?)"
        } else {
            clauses += "lr.lon_e7 BETWEEN ? AND ?"
        }
        args += viewport.minimumLongitudeE7
        args += viewport.maximumLongitudeE7
        appendInFilter(clauses, args, "ctp.kind", query.filters.includedKinds.map { it.ordinal })
        appendUidFilter(clauses, args, "pa.uid", "sa.uid", query.filters.accountIds.map { it.value })
        appendInFilter(clauses, args, "c.uid", query.filters.categoryIds.map { it.value.bytes })
        appendInFilter(clauses, args, "m.uid", query.filters.merchantIds.map { it.value.bytes })
        appendInFilter(clauses, args, "pl.uid", query.filters.placeIds.map { it.value.bytes })
        appendInFilter(clauses, args, "pr.uid", query.filters.projectIds.map { it.value.bytes })
        val metric = metricExpression(query.mode)
        val amountClauses = mutableListOf("map_amount_minor<>0")
        query.filters.minimumBaseAmountMinor?.let {
            amountClauses += "ABS(map_amount_minor)>=?"
            args += it
        }
        query.filters.maximumBaseAmountMinor?.let {
            amountClauses += "ABS(map_amount_minor)<=?"
            args += it
        }
        return CompiledMap(
            "WITH map_candidates AS (" +
                "SELECT ctp.transaction_id,ctp.transaction_uid,ctp.current_revision_id,ctp.local_date,ctp.kind transaction_kind," +
                "ctp.occurred_at,ctp.input_amount_minor,ctp.input_currency,lr.uid location_uid,lr.lat_e7,lr.lon_e7," +
                "c.uid category_uid,c.name category_name,m.uid merchant_uid,m.name merchant_name,pl.uid place_uid,pl.name place_name," +
                "pa.name primary_account_name,pc.display_name card_name," +
                "$metric map_amount_minor FROM location_rtree lrt JOIN location_record lr ON lr.id=lrt.location_id " +
                "JOIN transaction_revision tr ON tr.location_record_id=lr.id JOIN current_transaction_projection ctp ON ctp.current_revision_id=tr.id " +
                "LEFT JOIN category c ON c.id=ctp.category_id LEFT JOIN merchant m ON m.id=ctp.merchant_id " +
                "LEFT JOIN place pl ON pl.id=lr.place_id LEFT JOIN project pr ON pr.id=ctp.project_id " +
                "LEFT JOIN user_account pa ON pa.id=ctp.primary_account_id LEFT JOIN user_account sa ON sa.id=ctp.secondary_account_id " +
                "LEFT JOIN payment_card pc ON pc.id=ctp.card_id " +
                "WHERE ${clauses.joinToString(" AND ")}),map_events AS (SELECT * FROM map_candidates WHERE ${amountClauses.joinToString(" AND ")})",
            args,
        )
    }

    private fun metricExpression(mode: ConsumptionMapMode): String = when (mode) {
        ConsumptionMapMode.CONSUMPTION -> economicMetric("ee.is_consumption=1")
        ConsumptionMapMode.ALL_EXPENSES -> economicMetric("1=1")
        ConsumptionMapMode.CASH_FLOW ->
            "COALESCE((SELECT SUM(CASE WHEN p.side=la.normal_side THEN p.base_amount_minor ELSE -p.base_amount_minor END) " +
                "FROM journal_entry je JOIN posting p ON p.journal_entry_id=je.id JOIN ledger_account la ON la.id=p.ledger_account_id " +
                "JOIN user_account ua ON ua.ledger_account_id=la.id AND ua.type IN (0,1) WHERE je.source_revision_id=ctp.current_revision_id),0)"
        ConsumptionMapMode.ALL_LOCATED_TRANSACTIONS ->
            "COALESCE(ctp.economic_base_minor,(SELECT MAX(p.base_amount_minor) FROM journal_entry je " +
                "JOIN posting p ON p.journal_entry_id=je.id WHERE je.source_revision_id=ctp.current_revision_id),0)"
    }

    private fun economicMetric(extra: String): String = "COALESCE((SELECT SUM(CASE ee.nature WHEN 1 THEN ee.polarity*ee.base_amount_minor " +
        "WHEN 2 THEN -ee.polarity*ee.base_amount_minor ELSE 0 END) FROM economic_effect ee " +
        "WHERE ee.source_revision_id=ctp.current_revision_id AND ee.nature IN (1,2) AND $extra),0)"

    private fun grouping(query: ConsumptionMapQuery): Grouping = if (query.presentation == ConsumptionMapPresentation.SINGLE_POINTS) {
        Grouping("transaction_uid", ConsumptionMapGroupKind.TRANSACTION.ordinal.toString(), "COALESCE(merchant_name,place_name)")
    } else {
        when (query.aggregation) {
            ConsumptionMapAggregation.MERCHANT -> Grouping(
                "COALESCE(merchant_uid,location_uid)",
                "CASE WHEN merchant_uid IS NULL THEN ${ConsumptionMapGroupKind.RECORDED_LOCATION.ordinal} ELSE ${ConsumptionMapGroupKind.MERCHANT.ordinal} END",
                "merchant_name",
            )
            ConsumptionMapAggregation.PLACE -> Grouping(
                "COALESCE(place_uid,location_uid)",
                "CASE WHEN place_uid IS NULL THEN ${ConsumptionMapGroupKind.RECORDED_LOCATION.ordinal} ELSE ${ConsumptionMapGroupKind.PLACE.ordinal} END",
                "place_name",
            )
        }
    }

    private fun appendInFilter(clauses: MutableList<String>, args: MutableList<Any?>, column: String, values: List<Any>) {
        if (values.isEmpty()) return
        clauses += "$column IN (${values.joinToString(",") { "?" }})"
        args.addAll(values)
    }

    private fun appendUidFilter(
        clauses: MutableList<String>,
        args: MutableList<Any?>,
        primaryPrefix: String,
        secondarySuffix: String,
        values: List<StableId>,
    ) {
        if (values.isEmpty()) return
        val placeholders = values.joinToString(",") { "?" }
        clauses += "($primaryPrefix IN ($placeholders) OR $secondarySuffix IN ($placeholders))"
        args.addAll(values.map(StableId::bytes))
        args.addAll(values.map(StableId::bytes))
    }

    private fun Cursor.mapPoint(): ConsumptionMapPoint = ConsumptionMapPoint(
        stableId(getColumnIndexOrThrow("group_uid")),
        ConsumptionMapGroupKind.entries[getInt(getColumnIndexOrThrow("group_kind"))],
        getColumnIndexOrThrow("group_label").let { if (isNull(it)) null else getString(it) },
        getDouble(getColumnIndexOrThrow("latitude_e7")).roundToInt(),
        getDouble(getColumnIndexOrThrow("longitude_e7")).roundToInt(),
        getLong(getColumnIndexOrThrow("base_amount_minor")),
        getLong(getColumnIndexOrThrow("transaction_count")),
    )

    private fun Cursor.stableId(index: Int): StableId = StableId.fromBytes(getBlob(index)).getOrNull() ?: error("invalid stable id")

    private fun Cursor.optionalString(index: Int): String? = if (isNull(index)) null else getString(index)

    private fun SupportSQLiteDatabase.version(): LocalRevision? = query("SELECT local_revision FROM book WHERE id=1").use { cursor ->
        if (!cursor.moveToFirst()) null else LocalRevision.of(cursor.getLong(0)).getOrNull()
    }

    private fun LocalDate.storageKey(): Int = year * 10_000 + monthValue * 100 + dayOfMonth
    private fun Int.toLocalDate(): LocalDate = LocalDate.of(this / 10_000, (this / 100) % 100, this % 100)

    @Suppress("UNCHECKED_CAST")
    private fun <T> DomainResult<*>.castFailure(): DomainResult<T> = this as DomainResult<T>

    private data class CompiledMap(val withMetric: String, val arguments: List<Any?>)
    private data class Grouping(val idExpression: String, val kindExpression: String, val labelExpression: String)
    private data class TransactionWithTime(val row: DrilldownTransaction, val occurredAt: Long)

    data class MapSelection(
        val query: ConsumptionMapQuery,
        val pointId: StableId,
        val asOfLocalRevision: LocalRevision,
    )

    private companion object {
        const val E7_SCALE = 10_000_000.0
        const val MAX_SELECTIONS = 128
        val TRANSACTION_KIND_KEYS = listOf(
            "EXPENSE", "INCOME", "TRANSFER", "REFUND", "CREDIT_PAYMENT", "LOAN_DISBURSEMENT", "LOAN_PAYMENT",
            "BALANCE_ADJUSTMENT", "FX_EXCHANGE", "SETTLEMENT_PAYMENT", "OPENING_BALANCE",
        )
    }
}
