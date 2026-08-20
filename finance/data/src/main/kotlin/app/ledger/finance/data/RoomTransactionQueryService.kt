@file:Suppress("LongMethod", "TooManyFunctions")

package app.ledger.finance.data

import androidx.sqlite.db.SupportSQLiteDatabase
import app.ledger.core.common.DomainResult
import app.ledger.core.database.LedgerDatabase
import app.ledger.core.money.CurrencyCode
import app.ledger.finance.application.CurrentTransactionCursor
import app.ledger.finance.application.CurrentTransactionPage
import app.ledger.finance.application.CurrentTransactionQueryPort
import app.ledger.finance.application.FinanceDataError
import app.ledger.finance.application.GeoTransactionCandidate
import app.ledger.finance.application.GeoTransactionQueryPort
import app.ledger.finance.domain.CategoryId
import app.ledger.finance.domain.CurrentTransactionProjection
import app.ledger.finance.domain.GeoRadiusFilter
import app.ledger.finance.domain.GoalId
import app.ledger.finance.domain.LocalRevision
import app.ledger.finance.domain.MerchantId
import app.ledger.finance.domain.ParticipantId
import app.ledger.finance.domain.PaymentCardId
import app.ledger.finance.domain.ProjectId
import app.ledger.finance.domain.SettlementActivityId
import app.ledger.finance.domain.StatisticalNature
import app.ledger.finance.domain.TransactionFilter
import app.ledger.finance.domain.TransactionId
import app.ledger.finance.domain.TransactionKind
import app.ledger.finance.domain.TransactionLifecycleState
import app.ledger.finance.domain.TransactionRevisionId
import app.ledger.finance.domain.TransactionSource
import app.ledger.finance.domain.UserAccountId
import java.time.Instant
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class RoomTransactionQueryService(
    private val database: LedgerDatabase,
) : CurrentTransactionQueryPort,
    GeoTransactionQueryPort {
    override suspend fun page(
        filter: TransactionFilter,
        limit: Int,
        cursor: CurrentTransactionCursor?,
    ): DomainResult<CurrentTransactionPage> {
        if (limit !in 1..MAX_PAGE_SIZE) return DomainResult.Failure(FinanceDataError.CorruptData)
        return protect {
            database.readLedger { connection ->
                val compiled = TransactionSqlCompiler.compile(filter, cursor, limit + 1)
                val mapped = connection.queryList(compiled.sql, compiled.arguments.toTypedArray(), ::mapProjection)
                val items = mapped.take(limit)
                val next = if (mapped.size > limit) {
                    items.lastOrNull()?.let { item ->
                        CurrentTransactionCursor(item.occurredAt.toEpochMilli(), item.transactionId)
                    }
                } else {
                    null
                }
                DomainResult.Success(CurrentTransactionPage(items, next))
            }
        }
    }

    override suspend fun withinRadius(
        filter: TransactionFilter,
        limit: Int,
    ): DomainResult<List<GeoTransactionCandidate>> {
        val radius = filter.geoRadius
        return when {
            radius == null -> DomainResult.Success(emptyList())
            limit !in 1..MAX_PAGE_SIZE -> DomainResult.Failure(FinanceDataError.CorruptData)
            else -> queryWithinRadius(filter, limit, radius)
        }
    }

    private fun queryWithinRadius(
        filter: TransactionFilter,
        limit: Int,
        radius: GeoRadiusFilter,
    ): DomainResult<List<GeoTransactionCandidate>> = protect {
        database.readLedger { connection ->
            val bounds = GeoBounds.forRadius(
                radius.center.latitudeE7 / E7_SCALE,
                radius.center.longitudeE7 / E7_SCALE,
                radius.radiusMeters,
            )
            val base = TransactionSqlCompiler.compile(filter.copy(geoRadius = null), cursor = null, limit = GEO_CANDIDATE_LIMIT)
            val boundingClause = if (bounds.crossesDateLine) {
                "(lrt.max_lon >= ? OR lrt.min_lon <= ?)"
            } else {
                "lrt.max_lon >= ? AND lrt.min_lon <= ?"
            }
            val unordered = base.sql.substringBefore(" ORDER BY ")
            val conjunction = if (" WHERE " in unordered) " AND " else " WHERE "
            val sql = unordered + conjunction +
                "lrt.max_lat >= ? AND lrt.min_lat <= ? AND $boundingClause" +
                " ORDER BY ctp.occurred_at DESC, ctp.transaction_id DESC LIMIT ?"
            val args = base.arguments.dropLast(1).toMutableList<Any?>().apply {
                add(bounds.minimumLatitude)
                add(bounds.maximumLatitude)
                add(bounds.minimumLongitude)
                add(bounds.maximumLongitude)
                add(GEO_CANDIDATE_LIMIT)
            }
            val rows = connection.queryList(
                sql.replace(
                    "LEFT JOIN location_record lr ON lr.id = tr.location_record_id",
                    "JOIN location_record lr ON lr.id = tr.location_record_id JOIN location_rtree lrt ON lrt.location_id = lr.id",
                ),
                args.toTypedArray(),
            ) { cursor ->
                val projection = mapProjection(cursor)
                val latitude = cursor.getInt(cursor.getColumnIndexOrThrow("latitude_e7")) / E7_SCALE
                val longitude = cursor.getInt(cursor.getColumnIndexOrThrow("longitude_e7")) / E7_SCALE
                projection.transactionId to haversineMeters(
                    radius.center.latitudeE7 / E7_SCALE,
                    radius.center.longitudeE7 / E7_SCALE,
                    latitude,
                    longitude,
                )
            }
            DomainResult.Success(
                rows.asSequence()
                    .filter { (_, distance) -> distance <= radius.radiusMeters }
                    .sortedBy { (_, distance) -> distance }
                    .take(limit)
                    .map { (transactionId, distance) -> GeoTransactionCandidate(transactionId, distance.roundToInt()) }
                    .toList(),
            )
        }
    }

    private fun mapProjection(cursor: android.database.Cursor): CurrentTransactionProjection = CurrentTransactionProjection(
        transactionId = TransactionId(cursor.stableId("transaction_uid")),
        kind = TransactionKind.entries[cursor.getInt(cursor.getColumnIndexOrThrow("kind"))],
        state = TransactionLifecycleState.entries[cursor.getInt(cursor.getColumnIndexOrThrow("state"))],
        currentRevisionId = TransactionRevisionId(cursor.stableId("current_revision_uid")),
        occurredAt = Instant.ofEpochMilli(cursor.getLong(cursor.getColumnIndexOrThrow("occurred_at"))),
        localDate = cursor.getInt(cursor.getColumnIndexOrThrow("local_date")).toStoredLocalDate(),
        primaryAccountId = cursor.nullableStableId("primary_account_uid")?.let(::UserAccountId),
        secondaryAccountId = cursor.nullableStableId("secondary_account_uid")?.let(::UserAccountId),
        cardId = cursor.nullableStableId("card_uid")?.let(::PaymentCardId),
        categoryId = cursor.nullableStableId("category_uid")?.let(::CategoryId),
        merchantId = cursor.nullableStableId("merchant_uid")?.let(::MerchantId),
        projectId = cursor.nullableStableId("project_uid")?.let(::ProjectId),
        goalId = cursor.nullableStableId("goal_uid")?.let(::GoalId),
        settlementActivityId = cursor.nullableStableId("settlement_activity_uid")?.let(::SettlementActivityId),
        payerParticipantId = cursor.nullableStableId("payer_participant_uid")?.let(::ParticipantId),
        inputAmountMinor = cursor.getLong(cursor.getColumnIndexOrThrow("input_amount_minor")),
        inputCurrency = CurrencyCode.parse(cursor.getString(cursor.getColumnIndexOrThrow("input_currency"))).valueOrAbort(),
        accountAmountMinor = cursor.getLong(cursor.getColumnIndexOrThrow("account_amount_minor")),
        accountCurrency = CurrencyCode.parse(cursor.getString(cursor.getColumnIndexOrThrow("account_currency"))).valueOrAbort(),
        economicBaseMinor = cursor.nullableLong("economic_base_minor"),
        notePreview = cursor.nullableString("note_preview"),
        hasAttachment = cursor.getInt(cursor.getColumnIndexOrThrow("has_attachment")) == 1,
        hasLocation = cursor.getInt(cursor.getColumnIndexOrThrow("has_location")) == 1,
        isRefund = cursor.getInt(cursor.getColumnIndexOrThrow("is_refund")) == 1,
        isRefunded = cursor.getInt(cursor.getColumnIndexOrThrow("is_refunded")) == 1,
        hasInstallment = cursor.getInt(cursor.getColumnIndexOrThrow("has_installment")) == 1,
        source = TransactionSource.entries[cursor.getInt(cursor.getColumnIndexOrThrow("source_type"))],
        asOfLocalRevision = LocalRevision.of(cursor.getLong(cursor.getColumnIndexOrThrow("projection_generation"))).valueOrAbort(),
    )

    private inline fun <T> protect(block: () -> DomainResult<T>): DomainResult<T> = try {
        block()
    } catch (abort: FinancialPersistenceAbort) {
        DomainResult.Failure(abort.domainError)
    } catch (_: ArithmeticException) {
        DomainResult.Failure(FinanceDataError.NumericRangeExceeded)
    } catch (_: Exception) {
        DomainResult.Failure(FinanceDataError.DatabaseUnavailable)
    }
}

internal data class CompiledTransactionQuery(
    val sql: String,
    val arguments: List<Any?>,
)

internal object TransactionSqlCompiler {
    fun compile(filter: TransactionFilter, cursor: CurrentTransactionCursor?, limit: Int): CompiledTransactionQuery {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<Any?>()
        filter.occurredFrom?.let {
            clauses += "ctp.occurred_at >= ?"
            args += it.toEpochMilli()
        }
        filter.occurredThrough?.let {
            clauses += "ctp.occurred_at <= ?"
            args += it.toEpochMilli()
        }
        instantRange("created.created_at", filter.createdFrom, filter.createdThrough, clauses, args)
        instantRange("modified.created_at", filter.modifiedFrom, filter.modifiedThrough, clauses, args)
        enumSet("ctp.kind", filter.kinds.map(TransactionKind::ordinal), clauses, args)
        stableAccountSet(filter.accountIds.map { it.value }, clauses, args)
        stableSet("ctp.card_id", "payment_card", filter.cardIds.map { it.value }, clauses, args)
        stableSet("ctp.category_id", "category", filter.categoryIds.map { it.value }, clauses, args)
        stableSet("ctp.merchant_id", "merchant", filter.merchantIds.map { it.value }, clauses, args)
        stableSet("ctp.project_id", "project", filter.projectIds.map { it.value }, clauses, args)
        stableSet("ctp.settlement_activity_id", "settlement_activity", filter.settlementActivityIds.map { it.value }, clauses, args)
        stableSet("ctp.payer_participant_id", "participant", filter.participantIds.map { it.value }, clauses, args)
        textSet("ctp.input_currency", filter.currencies.map { it.value }, clauses, args, includeAccountCurrency = true)
        filter.amountRange?.minimumAccountMinor?.let {
            clauses += "ctp.account_amount_minor >= ?"
            args += it
        }
        filter.amountRange?.maximumAccountMinor?.let {
            clauses += "ctp.account_amount_minor <= ?"
            args += it
        }
        boolean("ctp.has_attachment", filter.hasAttachment, clauses, args)
        boolean("ctp.is_refund", filter.isRefund, clauses, args)
        boolean("ctp.has_installment", filter.hasInstallment, clauses, args)
        enumSet("tr.statistical_nature_snapshot", filter.statisticalNatures.map(StatisticalNature::ordinal), clauses, args)
        filter.includedInBudget?.let { included ->
            clauses += if (included) {
                "EXISTS (SELECT 1 FROM budget_effect be WHERE be.source_revision_id=tr.id AND be.polarity=1)"
            } else {
                "NOT EXISTS (SELECT 1 FROM budget_effect be WHERE be.source_revision_id=tr.id AND be.polarity=1)"
            }
        }
        filter.generatedByRecurrence?.let { generated ->
            val recurrenceSources = listOf(TransactionSource.RECURRENCE_AUTO.ordinal, TransactionSource.RECURRENCE_CANDIDATE.ordinal)
            clauses += if (generated) "ctp.source_type IN (?,?)" else "ctp.source_type NOT IN (?,?)"
            args.addAll(recurrenceSources)
        }
        enumSet("ctp.source_type", filter.sources.map(TransactionSource::ordinal), clauses, args)
        enumSet("ctp.state", filter.lifecycleStates.map(TransactionLifecycleState::ordinal), clauses, args)
        filter.searchText?.trim()?.takeIf(String::isNotEmpty)?.let { search ->
            if (search.codePointCount(0, search.length) < FTS_TRIGRAM_LENGTH) {
                val pattern = "%${search.escapeLikePattern()}%"
                clauses += "ctp.transaction_id IN (SELECT transaction_id FROM transaction_fts WHERE " +
                    listOf(
                        "category_name", "merchant_name", "merchant_aliases", "note", "project_name",
                        "settlement_activity_name", "participant_names", "attachment_names",
                    ).joinToString(" OR ") { "$it LIKE ? ESCAPE '\\'" } + ")"
                repeat(FTS_TEXT_COLUMN_COUNT) { args += pattern }
            } else {
                val literal = "\"${search.replace("\"", "\"\"")}\""
                clauses += "ctp.transaction_id IN (SELECT transaction_id FROM transaction_fts WHERE transaction_fts MATCH ?)"
                args += literal
            }
        }
        cursor?.let {
            clauses += "(ctp.occurred_at < ? OR (ctp.occurred_at = ? AND ctp.transaction_id < (SELECT id FROM business_transaction WHERE uid = ?)))"
            args += it.occurredAtEpochMillis
            args += it.occurredAtEpochMillis
            args += it.transactionId.value.bytes
        }
        val where = if (clauses.isEmpty()) "" else " WHERE " + clauses.joinToString(" AND ")
        args += limit
        return CompiledTransactionQuery(BASE_SELECT + where + " ORDER BY ctp.occurred_at DESC, ctp.transaction_id DESC LIMIT ?", args)
    }

    private fun enumSet(column: String, values: List<Int>, clauses: MutableList<String>, args: MutableList<Any?>) {
        if (values.isNotEmpty()) {
            clauses += "$column IN (${values.joinToString(",") { "?" }})"
            args.addAll(values)
        }
    }

    private fun String.escapeLikePattern(): String = replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private const val FTS_TRIGRAM_LENGTH = 3
    private const val FTS_TEXT_COLUMN_COUNT = 8

    private fun stableSet(
        column: String,
        table: String,
        values: List<app.ledger.core.common.StableId>,
        clauses: MutableList<String>,
        args: MutableList<Any?>,
    ) {
        if (values.isNotEmpty()) {
            val placeholders = values.joinToString(",") { "?" }
            clauses += "$column IN (SELECT id FROM $table WHERE uid IN ($placeholders))"
            args.addAll(values.map { it.bytes })
        }
    }

    private fun stableAccountSet(
        values: List<app.ledger.core.common.StableId>,
        clauses: MutableList<String>,
        args: MutableList<Any?>,
    ) {
        if (values.isNotEmpty()) {
            val placeholders = values.joinToString(",") { "?" }
            val match = "SELECT id FROM user_account WHERE uid IN ($placeholders)"
            clauses += "(ctp.primary_account_id IN ($match) OR ctp.secondary_account_id IN ($match))"
            args.addAll(values.map { it.bytes })
            args.addAll(values.map { it.bytes })
        }
    }

    private fun textSet(
        column: String,
        values: List<String>,
        clauses: MutableList<String>,
        args: MutableList<Any?>,
        includeAccountCurrency: Boolean,
    ) {
        if (values.isNotEmpty()) {
            val placeholders = values.joinToString(",") { "?" }
            clauses += if (includeAccountCurrency) {
                "($column IN ($placeholders) OR ctp.account_currency IN ($placeholders))"
            } else {
                "$column IN ($placeholders)"
            }
            args.addAll(values)
            if (includeAccountCurrency) args.addAll(values)
        }
    }

    private fun boolean(column: String, value: Boolean?, clauses: MutableList<String>, args: MutableList<Any?>) {
        value?.let {
            clauses += "$column = ?"
            args += it.toSqlInt()
        }
    }

    private fun instantRange(
        column: String,
        from: Instant?,
        through: Instant?,
        clauses: MutableList<String>,
        args: MutableList<Any?>,
    ) {
        from?.let {
            clauses += "$column >= ?"
            args += it.toEpochMilli()
        }
        through?.let {
            clauses += "$column <= ?"
            args += it.toEpochMilli()
        }
    }

    val BASE_SELECT = """
        SELECT ctp.*,(SELECT as_of_local_revision FROM projection_family_state WHERE family=0) AS projection_generation,
          tr.uid AS current_revision_uid,
          pa.uid AS primary_account_uid, sa.uid AS secondary_account_uid, pc.uid AS card_uid,
          c.uid AS category_uid, m.uid AS merchant_uid, pr.uid AS project_uid, g.uid AS goal_uid,
          act.uid AS settlement_activity_uid, part.uid AS payer_participant_uid,
          lr.lat_e7 AS latitude_e7, lr.lon_e7 AS longitude_e7
        FROM current_transaction_projection ctp
        JOIN business_transaction bt ON bt.id = ctp.transaction_id
        JOIN transaction_revision tr ON tr.id = ctp.current_revision_id
        JOIN book_commit created ON created.id = bt.created_commit_id
        JOIN book_commit modified ON modified.id = bt.last_commit_id
        LEFT JOIN user_account pa ON pa.id = ctp.primary_account_id
        LEFT JOIN user_account sa ON sa.id = ctp.secondary_account_id
        LEFT JOIN payment_card pc ON pc.id = ctp.card_id
        LEFT JOIN category c ON c.id = ctp.category_id
        LEFT JOIN merchant m ON m.id = ctp.merchant_id
        LEFT JOIN project pr ON pr.id = ctp.project_id
        LEFT JOIN goal g ON g.id = ctp.goal_id
        LEFT JOIN settlement_activity act ON act.id = ctp.settlement_activity_id
        LEFT JOIN participant part ON part.id = ctp.payer_participant_id
        LEFT JOIN location_record lr ON lr.id = tr.location_record_id
    """.trimIndent()
}

private data class GeoBounds(
    val minimumLatitude: Double,
    val maximumLatitude: Double,
    val minimumLongitude: Double,
    val maximumLongitude: Double,
    val crossesDateLine: Boolean,
) {
    companion object {
        fun forRadius(latitude: Double, longitude: Double, radiusMeters: Int): GeoBounds {
            val angular = radiusMeters / EARTH_RADIUS_METERS
            val latitudeDelta = Math.toDegrees(angular)
            val longitudeDelta = Math.toDegrees(angular / cos(Math.toRadians(latitude)).coerceAtLeast(MIN_COSINE))
            val minimumLongitude = normalizeLongitude(longitude - longitudeDelta)
            val maximumLongitude = normalizeLongitude(longitude + longitudeDelta)
            return GeoBounds(
                minimumLatitude = (latitude - latitudeDelta).coerceAtLeast(MINIMUM_LATITUDE),
                maximumLatitude = (latitude + latitudeDelta).coerceAtMost(MAXIMUM_LATITUDE),
                minimumLongitude = minimumLongitude,
                maximumLongitude = maximumLongitude,
                crossesDateLine = minimumLongitude > maximumLongitude,
            )
        }

        private fun normalizeLongitude(value: Double): Double {
            var normalized = value
            while (normalized < MINIMUM_LONGITUDE) normalized += FULL_CIRCLE_DEGREES
            while (normalized > MAXIMUM_LONGITUDE) normalized -= FULL_CIRCLE_DEGREES
            return normalized
        }
    }
}

private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val latitudeDelta = Math.toRadians(lat2 - lat1)
    val longitudeDelta = Math.toRadians(lon2 - lon1)
    val a = sin(latitudeDelta / HALF) * sin(latitudeDelta / HALF) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
        sin(longitudeDelta / HALF) * sin(longitudeDelta / HALF)
    return EARTH_RADIUS_METERS * HALF * asin(sqrt(a.coerceIn(0.0, 1.0)))
}

private const val MAX_PAGE_SIZE = 200
private const val GEO_CANDIDATE_LIMIT = 2_000
private const val E7_SCALE = 10_000_000.0
private const val EARTH_RADIUS_METERS = 6_371_008.8
private const val MIN_COSINE = 0.000001
private const val HALF = 2.0
private const val MINIMUM_LATITUDE = -90.0
private const val MAXIMUM_LATITUDE = 90.0
private const val MINIMUM_LONGITUDE = -180.0
private const val MAXIMUM_LONGITUDE = 180.0
private const val FULL_CIRCLE_DEGREES = 360.0
