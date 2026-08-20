@file:Suppress(
    "CyclomaticComplexMethod",
    "LongMethod",
    "LongParameterList",
    "MagicNumber",
    "ReturnCount",
    "TooManyFunctions",
)

package app.ledger.analytics.data

import android.content.Context
import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import app.ledger.analytics.domain.AnalysisOverview
import app.ledger.analytics.domain.AnalyticsApplicationPort
import app.ledger.analytics.domain.AnalyticsError
import app.ledger.analytics.domain.AnalyticsIntegrityReport
import app.ledger.analytics.domain.AnomalyFinding
import app.ledger.analytics.domain.AnomalyRuleId
import app.ledger.analytics.domain.ConsumptionMapDetail
import app.ledger.analytics.domain.ConsumptionMapFilterOptions
import app.ledger.analytics.domain.ConsumptionMapQuery
import app.ledger.analytics.domain.ConsumptionMapResult
import app.ledger.analytics.domain.DashboardId
import app.ledger.analytics.domain.Dimension
import app.ledger.analytics.domain.DimensionValue
import app.ledger.analytics.domain.DrilldownCursor
import app.ledger.analytics.domain.DrilldownPage
import app.ledger.analytics.domain.DrilldownQueryId
import app.ledger.analytics.domain.DrilldownTransaction
import app.ledger.analytics.domain.FixedReport
import app.ledger.analytics.domain.FixedReportCatalog
import app.ledger.analytics.domain.FixedReportDefinition
import app.ledger.analytics.domain.ForecastKey
import app.ledger.analytics.domain.ForecastResult
import app.ledger.analytics.domain.IntegrityCheckKey
import app.ledger.analytics.domain.IntegrityCheckResult
import app.ledger.analytics.domain.IntegritySeverity
import app.ledger.analytics.domain.Measure
import app.ledger.analytics.domain.MeasureValue
import app.ledger.analytics.domain.QuerySource
import app.ledger.analytics.domain.ReportComparison
import app.ledger.analytics.domain.ReportDefinitionId
import app.ledger.analytics.domain.ReportDerivationPolicy
import app.ledger.analytics.domain.ReportExecution
import app.ledger.analytics.domain.ReportExportFormat
import app.ledger.analytics.domain.ReportExportPayload
import app.ledger.analytics.domain.ReportKey
import app.ledger.analytics.domain.ReportPeriod
import app.ledger.analytics.domain.ReportQueryPlan
import app.ledger.analytics.domain.ReportRow
import app.ledger.analytics.domain.ReportSpec
import app.ledger.analytics.domain.ReportVisualization
import app.ledger.analytics.domain.ReportVisualizationPolicy
import app.ledger.analytics.domain.SaveAnomalyRuleRequest
import app.ledger.analytics.domain.SaveDashboardRequest
import app.ledger.analytics.domain.SaveReportDefinitionRequest
import app.ledger.analytics.domain.SavedAnomalyRule
import app.ledger.analytics.domain.SavedDashboard
import app.ledger.analytics.domain.SavedReportDefinition
import app.ledger.analytics.domain.SavingsRatePolicy
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.common.StableIdSource
import app.ledger.core.common.getOrNull
import app.ledger.core.database.AnalyticsProjectionEngine
import app.ledger.core.database.DatabaseIntegrityAudit
import app.ledger.core.database.EncryptedDatabaseFactory
import app.ledger.core.database.LedgerDatabase
import app.ledger.core.money.CurrencyCode
import app.ledger.core.time.LedgerClock
import app.ledger.finance.domain.LocalRevision
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDate
import java.util.LinkedHashMap

/** Supplies a fresh passphrase copy for one database open. The caller immediately wipes the returned array. */
fun interface TransientAnalyticsDatabasePassphraseProvider {
    fun acquire(bookId: StableId): ByteArray
}

class SecureRoomAnalyticsApplicationPort(
    context: Context,
    private val passphrases: TransientAnalyticsDatabasePassphraseProvider,
    ids: StableIdSource,
    clock: LedgerClock,
) : AnalyticsApplicationPort {
    private val applicationContext = context.applicationContext
    private val drilldowns = DrilldownRegistry()
    private val customAnalytics = CustomAnalyticsStore(ids, clock)
    private val consumptionMaps = ConsumptionMapStore()

    override fun fixedReports(): List<FixedReportDefinition> = FixedReportCatalog.definitions

    override suspend fun plan(bookId: StableId, spec: ReportSpec): DomainResult<ReportQueryPlan> = withDatabase(bookId) { database ->
        database.readLedger { connection ->
            val version = readVersion(connection) ?: return@readLedger DomainResult.Failure(AnalyticsError.DatabaseUnavailable)
            DomainResult.Success(
                ReportQueryPlan(
                    source = ReportQueryPlanner.select(spec),
                    spec = spec,
                    asOfLocalRevision = version.first,
                    asOfValuationRevision = version.second,
                ),
            )
        }
    }

    override suspend fun execute(
        bookId: StableId,
        plan: ReportQueryPlan,
        period: ReportPeriod,
        requestedVisualization: ReportVisualization,
    ): DomainResult<ReportExecution> = withDatabase(bookId) { database ->
        database.readLedger { connection ->
            executeOnConnection(connection, plan, period, requestedVisualization, fixedReport = null)
        }
    }

    override suspend fun overview(bookId: StableId, period: ReportPeriod): DomainResult<AnalysisOverview> = withDatabase(bookId) { database ->
        database.readLedger { connection ->
            val version = readVersion(connection) ?: return@readLedger DomainResult.Failure(AnalyticsError.DatabaseUnavailable)
            val stale = AnalyticsProjectionEngine.staleTables(connection, version.first.value)
            if (stale.isNotEmpty()) return@readLedger DomainResult.Failure(AnalyticsError.StaleProjection)
            val values = mutableMapOf<Int, Long>()
            connection.query(
                "SELECT metric,SUM(amount_base_minor) FROM analytics_daily_total WHERE local_date BETWEEN ? AND ? GROUP BY metric",
                arrayOf(period.start.storageKey(), period.endInclusive.storageKey()),
            ).use { cursor -> while (cursor.moveToNext()) values[cursor.getInt(0)] = cursor.getLong(1) }
            val income = values[AnalyticsProjectionEngine.INCOME_METRIC] ?: 0L
            val expense = values[AnalyticsProjectionEngine.EXPENSE_METRIC] ?: 0L
            val consumption = values[AnalyticsProjectionEngine.CONSUMPTION_METRIC] ?: 0L
            val transactions = values[AnalyticsProjectionEngine.TRANSACTION_COUNT_METRIC] ?: 0L
            val baseCurrency = connection.singleString("SELECT base_currency FROM book WHERE id=1")
                ?.let(CurrencyCode::parse)?.getOrNull()
                ?: return@readLedger DomainResult.Failure(AnalyticsError.DatabaseUnavailable)
            DomainResult.Success(
                AnalysisOverview(
                    period,
                    baseCurrency,
                    income,
                    expense,
                    consumption,
                    Math.subtractExact(income, expense),
                    SavingsRatePolicy.calculate(income, expense),
                    transactions,
                    version.first,
                ),
            )
        }
    }

    override suspend fun executeFixed(
        bookId: StableId,
        report: FixedReport,
        period: ReportPeriod,
        requestedVisualization: ReportVisualization?,
    ): DomainResult<ReportExecution> {
        val definition = FixedReportCatalog.definition(report)
        val planned = plan(bookId, definition.spec)
        if (planned !is DomainResult.Success) return planned.castFailure()
        return withDatabase(bookId) { database ->
            database.readLedger { connection ->
                executeOnConnection(
                    connection,
                    planned.value,
                    period,
                    requestedVisualization ?: definition.defaultVisualization,
                    report,
                )
            }
        }
    }

    override suspend fun executeCustom(
        bookId: StableId,
        spec: ReportSpec,
        period: ReportPeriod,
        requestedVisualization: ReportVisualization,
    ): DomainResult<ReportExecution> {
        val planned = plan(bookId, spec)
        if (planned !is DomainResult.Success) return planned.castFailure()
        return execute(bookId, planned.value, period, requestedVisualization)
    }

    override suspend fun drillDown(
        bookId: StableId,
        queryId: DrilldownQueryId,
        cursor: DrilldownCursor?,
        limit: Int,
    ): DomainResult<DrilldownPage> {
        if (limit !in 1..MAX_DRILLDOWN_PAGE) return DomainResult.Failure(AnalyticsError.InvalidReportSpec)
        val mapSelection = consumptionMaps.selection(queryId)
        val context = drilldowns.get(queryId)
        if (mapSelection == null && context == null) return DomainResult.Failure(AnalyticsError.ExpiredDrilldown)
        return withDatabase(bookId) { database ->
            database.readLedger { connection ->
                if (mapSelection != null) return@readLedger consumptionMaps.drillDown(connection, mapSelection, cursor, limit)
                requireNotNull(context)
                val version = readVersion(connection)?.first ?: return@readLedger DomainResult.Failure(AnalyticsError.DatabaseUnavailable)
                if (version != context.localRevision) return@readLedger DomainResult.Failure(AnalyticsError.ExpiredDrilldown)
                val args = mutableListOf<Any?>(context.period.start.storageKey(), context.period.endInclusive.storageKey())
                val clauses = mutableListOf("ctp.state=0", "ctp.local_date BETWEEN ? AND ?")
                context.dimensions.forEach { value -> appendDimensionFilter(value, context.granularity, clauses, args) }
                if (cursor != null) {
                    clauses += "(ctp.occurred_at < ? OR (ctp.occurred_at = ? AND ctp.transaction_uid < ?))"
                    args += cursor.occurredAtEpochMillis
                    args += cursor.occurredAtEpochMillis
                    args += cursor.transactionId.bytes
                }
                args += Math.addExact(limit, 1)
                val rows = connection.query(
                    DRILLDOWN_SELECT + " WHERE ${clauses.joinToString(" AND ")} ORDER BY ctp.occurred_at DESC,ctp.transaction_uid DESC LIMIT ?",
                    args.toTypedArray(),
                ).use { result -> buildList { while (result.moveToNext()) add(result.toDrilldown()) } }
                val visible = rows.take(limit)
                val next = if (rows.size > limit) {
                    visible.last().let { row ->
                        val occurred = connection.singleLong(
                            "SELECT occurred_at FROM current_transaction_projection WHERE transaction_uid=?",
                            arrayOf(row.transactionId.bytes),
                        ) ?: return@readLedger DomainResult.Failure(AnalyticsError.DatabaseUnavailable)
                        DrilldownCursor(occurred, row.transactionId)
                    }
                } else {
                    null
                }
                DomainResult.Success(DrilldownPage(visible, next))
            }
        }
    }

    override suspend fun integrity(bookId: StableId): DomainResult<AnalyticsIntegrityReport> = withDatabase(bookId) { database ->
        database.inLedgerTransaction { connection -> DomainResult.Success(runIntegrity(connection)) }
    }

    override suspend fun repairAnalyticsProjections(bookId: StableId): DomainResult<AnalyticsIntegrityReport> = withDatabase(bookId) { database ->
        database.inLedgerTransaction { connection ->
            val revision = connection.singleLong("SELECT local_revision FROM book WHERE id=1")
                ?: return@inLedgerTransaction DomainResult.Failure(AnalyticsError.DatabaseUnavailable)
            if (!AnalyticsProjectionEngine.repairInMaintenance(connection, revision)) {
                return@inLedgerTransaction DomainResult.Failure(AnalyticsError.DatabaseUnavailable)
            }
            DomainResult.Success(runIntegrity(connection))
        }
    }

    override fun prepareExport(
        execution: ReportExecution.Content,
        period: ReportPeriod,
        format: ReportExportFormat,
    ): DomainResult<ReportExportPayload> = DomainResult.Success(
        ReportExportPayload(
            format,
            execution.fixedReport?.let { FixedReportCatalog.definition(it).key },
            period,
            execution.plan,
            execution.rows,
            execution.comparison,
        ),
    )

    override suspend fun savedReports(bookId: StableId): DomainResult<List<SavedReportDefinition>> = withDatabase(bookId) { database ->
        database.readLedger(customAnalytics::listReports)
    }

    override suspend fun saveReport(
        bookId: StableId,
        request: SaveReportDefinitionRequest,
    ): DomainResult<SavedReportDefinition> = withDatabase(bookId) { database ->
        database.inLedgerTransaction { connection -> customAnalytics.saveReport(connection, request) }
    }

    override suspend fun copyReport(
        bookId: StableId,
        reportId: ReportDefinitionId,
        copyName: String,
    ): DomainResult<SavedReportDefinition> = withDatabase(bookId) { database ->
        database.inLedgerTransaction { connection -> customAnalytics.copyReport(connection, reportId, copyName) }
    }

    override suspend fun dashboards(bookId: StableId): DomainResult<List<SavedDashboard>> = withDatabase(bookId) { database ->
        database.readLedger(customAnalytics::listDashboards)
    }

    override suspend fun saveDashboard(
        bookId: StableId,
        request: SaveDashboardRequest,
    ): DomainResult<SavedDashboard> = withDatabase(bookId) { database ->
        database.inLedgerTransaction { connection -> customAnalytics.saveDashboard(connection, request) }
    }

    override suspend fun anomalyRules(bookId: StableId): DomainResult<List<SavedAnomalyRule>> = withDatabase(bookId) { database ->
        database.readLedger(customAnalytics::listAnomalyRules)
    }

    override suspend fun saveAnomalyRule(
        bookId: StableId,
        request: SaveAnomalyRuleRequest,
    ): DomainResult<SavedAnomalyRule> = withDatabase(bookId) { database ->
        database.inLedgerTransaction { connection -> customAnalytics.saveAnomalyRule(connection, request) }
    }

    override suspend fun anomalyFindings(
        bookId: StableId,
        period: ReportPeriod,
    ): DomainResult<List<AnomalyFinding>> = withDatabase(bookId) { database ->
        database.readLedger { connection -> customAnalytics.anomalyFindings(connection, period) }
    }

    override suspend fun forecast(
        bookId: StableId,
        key: ForecastKey,
        today: LocalDate,
    ): DomainResult<ForecastResult> = withDatabase(bookId) { database ->
        database.readLedger { connection -> customAnalytics.forecast(connection, key, today) }
    }

    override suspend fun consumptionMap(
        bookId: StableId,
        query: ConsumptionMapQuery,
    ): DomainResult<ConsumptionMapResult> = withDatabase(bookId) { database ->
        database.readLedger { connection -> consumptionMaps.query(connection, query) }
    }

    override suspend fun consumptionMapFilterOptions(bookId: StableId): DomainResult<ConsumptionMapFilterOptions> = withDatabase(bookId) { database ->
        database.readLedger(consumptionMaps::filterOptions)
    }

    override suspend fun consumptionMapDetail(
        bookId: StableId,
        query: ConsumptionMapQuery,
        pointId: StableId,
    ): DomainResult<ConsumptionMapDetail> = withDatabase(bookId) { database ->
        database.readLedger { connection -> consumptionMaps.detail(connection, query, pointId) }
    }

    private fun executeOnConnection(
        connection: SupportSQLiteDatabase,
        plan: ReportQueryPlan,
        period: ReportPeriod,
        requestedVisualization: ReportVisualization,
        fixedReport: FixedReport?,
    ): DomainResult<ReportExecution> {
        val current = readVersion(connection) ?: return DomainResult.Failure(AnalyticsError.DatabaseUnavailable)
        if (current.first != plan.asOfLocalRevision || current.second != plan.asOfValuationRevision) {
            return DomainResult.Success(ReportExecution.StaleProjection(current.first, setOf("report_plan")))
        }
        if (plan.source in setOf(QuerySource.DAILY_ROLLUP, QuerySource.MONTHLY_ROLLUP)) {
            val stale = AnalyticsProjectionEngine.staleTables(connection, current.first.value)
            if (stale.isNotEmpty()) return DomainResult.Success(ReportExecution.StaleProjection(current.first, stale))
        }
        val compiled = try {
            ReportSqlCompiler.compile(plan.source, plan.spec, period.start, period.endInclusive)
        } catch (_: IllegalArgumentException) {
            return DomainResult.Failure(AnalyticsError.InvalidReportSpec)
        }
        val rows = queryRows(connection, compiled, plan, period, current.first, registerDrilldowns = true)
        val comparison = comparisonPeriod(plan.spec.comparison, period)?.let { referencePeriod ->
            val referenceQuery = try {
                ReportSqlCompiler.compile(plan.source, plan.spec, referencePeriod.start, referencePeriod.endInclusive)
            } catch (_: IllegalArgumentException) {
                return DomainResult.Failure(AnalyticsError.InvalidReportSpec)
            }
            ReportComparison(
                requireNotNull(plan.spec.comparison),
                referencePeriod,
                queryRows(connection, referenceQuery, plan, referencePeriod, current.first, registerDrilldowns = false),
            )
        }
        val categoryCount = if (Dimension.CATEGORY in plan.spec.dimensions) rows.size else 0
        val visualization = ReportVisualizationPolicy.resolve(
            requestedVisualization,
            categoryCount,
            plan.spec.dimensions,
            plan.spec.measures,
        )
        val derived = ReportDerivationPolicy.derive(plan.spec.comparison, rows)
        val comparisonRows = comparison?.rows.orEmpty()
        return if ((rows + comparisonRows).isEmpty() || (rows + comparisonRows).all { row -> row.measureValues.all { it.isZero() } }) {
            DomainResult.Success(ReportExecution.Empty(fixedReport, plan, visualization))
        } else {
            DomainResult.Success(
                ReportExecution.Content(fixedReport, plan, rows, visualization, "REPORT_RESULT_READY", comparison, derived),
            )
        }
    }

    private fun queryRows(
        connection: SupportSQLiteDatabase,
        compiled: CompiledReportQuery,
        plan: ReportQueryPlan,
        period: ReportPeriod,
        revision: LocalRevision,
        registerDrilldowns: Boolean,
    ): List<ReportRow> = connection.query(compiled.sql, compiled.arguments).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val dimensions = compiled.dimensions.mapIndexed { index, dimension ->
                    cursor.dimensionValue(connection, "dimension_$index", dimension)
                }
                val measures = compiled.measures.map { measure -> cursor.measureValue(measure) }
                val queryId = if (registerDrilldowns) drilldowns.put(plan.spec, period, dimensions, revision) else null
                add(ReportRow(dimensions, measures, queryId))
            }
        }
    }

    private fun comparisonPeriod(mode: app.ledger.analytics.domain.ComparisonMode?, period: ReportPeriod): ReportPeriod? = when (mode) {
        app.ledger.analytics.domain.ComparisonMode.PREVIOUS_PERIOD -> {
            val days = java.time.temporal.ChronoUnit.DAYS.between(period.start, period.endInclusive)
            val end = period.start.minusDays(1)
            ReportPeriod(end.minusDays(days), end)
        }
        app.ledger.analytics.domain.ComparisonMode.YEAR_OVER_YEAR -> ReportPeriod(period.start.minusYears(1), period.endInclusive.minusYears(1))
        null -> null
        else -> null
    }

    private fun runIntegrity(connection: SupportSQLiteDatabase): AnalyticsIntegrityReport {
        val revision = LocalRevision.of(connection.singleLong("SELECT local_revision FROM book WHERE id=1") ?: 0L).getOrNull()
            ?: error("invalid local revision")
        val databaseAudit = DatabaseIntegrityAudit.run(connection)
        val analyticsAudit = AnalyticsProjectionEngine.audit(connection, revision.value)
        val postingCurrency = connection.singleLong(
            "SELECT COUNT(*) FROM posting p JOIN ledger_account la ON la.id=p.ledger_account_id JOIN book b ON b.id=1 " +
                "WHERE p.account_currency<>la.currency_code OR p.base_currency<>b.base_currency",
        ) ?: 0L
        val journalFacts = connection.singleLong(
            "SELECT COUNT(*) FROM journal_entry je JOIN (SELECT journal_entry_id," +
                "SUM(CASE side WHEN 0 THEN base_amount_minor ELSE 0 END) d," +
                "SUM(CASE side WHEN 1 THEN base_amount_minor ELSE 0 END) c FROM posting GROUP BY journal_entry_id) p ON p.journal_entry_id=je.id " +
                "WHERE p.d<>p.c OR p.d<>je.base_debit_total_minor OR p.c<>je.base_credit_total_minor",
        ) ?: 0L
        val revisionErrors = connection.singleLong(
            "SELECT COUNT(*) FROM business_transaction bt LEFT JOIN transaction_revision tr ON tr.id=bt.current_revision_id " +
                "WHERE tr.id IS NULL OR tr.transaction_id<>bt.id OR tr.resulting_state<>bt.lifecycle_state OR " +
                "tr.revision_no<>(SELECT MAX(r2.revision_no) FROM transaction_revision r2 WHERE r2.transaction_id=bt.id)",
        ) ?: 0L
        val projectionErrors = projectionVersionErrors(connection, revision.value) + analyticsAudit.staleTables.size
        val active = connection.singleLong("SELECT COUNT(*) FROM current_transaction_projection WHERE state=0") ?: 0L
        val fts = connection.singleLong("SELECT COUNT(*) FROM transaction_fts") ?: 0L
        val locationCount = connection.singleLong("SELECT COUNT(*) FROM location_record") ?: 0L
        val locationIndex = connection.singleLong("SELECT COUNT(*) FROM location_rtree") ?: 0L
        val placeCount = connection.singleLong("SELECT COUNT(*) FROM place") ?: 0L
        val placeIndex = connection.singleLong("SELECT COUNT(*) FROM place_rtree") ?: 0L
        val checks = listOf(
            check(IntegrityCheckKey.DATABASE, if (databaseAudit.integrityCheck == "ok") 0L else 1L, "DATABASE_INTEGRITY"),
            check(IntegrityCheckKey.FOREIGN_KEYS, databaseAudit.foreignKeyViolationCount.toLong(), "FOREIGN_KEY_INTEGRITY"),
            check(IntegrityCheckKey.JOURNALS, journalFacts + databaseAudit.unbalancedJournalCount, "JOURNAL_BALANCE"),
            check(IntegrityCheckKey.POSTING_CURRENCIES, postingCurrency, "POSTING_CURRENCY"),
            check(IntegrityCheckKey.REVISIONS, revisionErrors + databaseAudit.invalidCurrentSubtypeCount, "REVISION_CHAIN"),
            check(IntegrityCheckKey.PROJECTIONS, projectionErrors.toLong(), "PROJECTION_VERSION", warning = true),
            check(IntegrityCheckKey.FTS, kotlin.math.abs(active - fts), "FTS_ACTIVE_COUNT", warning = true),
            check(IntegrityCheckKey.RTREE, kotlin.math.abs(locationCount - locationIndex) + kotlin.math.abs(placeCount - placeIndex), "RTREE_COUNT", warning = true),
            check(IntegrityCheckKey.FACT_REBUILD, if (analyticsAudit.liveHash == analyticsAudit.rebuiltHash) 0L else 1L, "FACT_REBUILD_HASH"),
        )
        return AnalyticsIntegrityReport(checks, revision, analyticsAudit.liveHash, analyticsAudit.rebuiltHash)
    }

    private fun projectionVersionErrors(connection: SupportSQLiteDatabase, revision: Long): Int {
        val invalid = connection.singleLong(
            "SELECT COUNT(*) FROM projection_family_state WHERE as_of_local_revision<>? " +
                "OR as_of_valuation_revision<>(SELECT valuation_revision FROM book WHERE id=1)",
            arrayOf(revision),
        ) ?: return PROJECTION_FAMILY_COUNT
        val published = connection.singleLong("SELECT COUNT(*) FROM projection_family_state") ?: 0L
        return (invalid + (PROJECTION_FAMILY_COUNT - published).coerceAtLeast(0L))
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    private fun check(key: IntegrityCheckKey, count: Long, code: String, warning: Boolean = false): IntegrityCheckResult = IntegrityCheckResult(
        key,
        if (count == 0L) {
            IntegritySeverity.PASS
        } else if (warning) {
            IntegritySeverity.WARNING
        } else {
            IntegritySeverity.FAILURE
        },
        count,
        code,
    )

    private fun appendDimensionFilter(
        value: DimensionValue,
        granularity: app.ledger.analytics.domain.TimeGranularity,
        clauses: MutableList<String>,
        arguments: MutableList<Any?>,
    ) {
        when (value) {
            is DimensionValue.Date -> {
                val (start, endInclusive) = dateBucketRange(value.value, granularity)
                clauses += "ctp.local_date BETWEEN ? AND ?"
                arguments += start.storageKey()
                arguments += endInclusive.storageKey()
            }
            is DimensionValue.Currency -> {
                clauses += "ctp.input_currency=?"
                arguments += value.value.value
            }
            is DimensionValue.Entity -> {
                val column = DRILLDOWN_ENTITY_COLUMNS[value.dimension] ?: return
                clauses += "$column=?"
                arguments += value.id.bytes
            }
            is DimensionValue.ClosedKey -> if (value.dimension == Dimension.TRANSACTION_SOURCE) {
                clauses += "CAST(ctp.source_type AS TEXT)=?"
                arguments += value.value
            }
        }
    }

    private fun dateBucketRange(
        start: LocalDate,
        granularity: app.ledger.analytics.domain.TimeGranularity,
    ): Pair<LocalDate, LocalDate> = when (granularity) {
        app.ledger.analytics.domain.TimeGranularity.DAY -> start to start
        app.ledger.analytics.domain.TimeGranularity.WEEK -> start to start.plusDays(6)
        app.ledger.analytics.domain.TimeGranularity.MONTH -> start to start.withDayOfMonth(start.lengthOfMonth())
        app.ledger.analytics.domain.TimeGranularity.QUARTER -> start to start.plusMonths(3).minusDays(1)
        app.ledger.analytics.domain.TimeGranularity.YEAR -> start to start.withMonth(12).withDayOfMonth(31)
    }

    private fun Cursor.dimensionValue(connection: SupportSQLiteDatabase, column: String, dimension: Dimension): DimensionValue {
        val index = getColumnIndexOrThrow(column)
        if (isNull(index)) return DimensionValue.ClosedKey(dimension, "unassigned")
        return when (dimension) {
            Dimension.DATE -> DimensionValue.Date(getInt(index).toLocalDate())
            Dimension.CURRENCY -> DimensionValue.Currency(
                CurrencyCode.parse(getString(index)).getOrNull() ?: error("invalid report currency"),
            )
            Dimension.TRANSACTION_SOURCE -> DimensionValue.ClosedKey(dimension, getString(index))
            else -> {
                val id = StableId.fromBytes(getBlob(index)).getOrNull() ?: error("invalid report id")
                DimensionValue.Entity(dimension, id, resolveEntityLabel(connection, dimension, id))
            }
        }
    }

    private fun resolveEntityLabel(connection: SupportSQLiteDatabase, dimension: Dimension, id: StableId): String {
        val source = ENTITY_LABEL_SOURCES[dimension] ?: return dimension.name
        return connection.query("SELECT ${source.second} FROM ${source.first} WHERE uid=?", arrayOf(id.bytes)).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else dimension.name
        }
    }

    private fun Cursor.measureValue(compiled: CompiledMeasure): MeasureValue {
        if (compiled.measure == Measure.SAVINGS_RATE) {
            val income = getLong(getColumnIndexOrThrow(compiled.valueColumn))
            val expense = getLong(getColumnIndexOrThrow(requireNotNull(compiled.secondaryColumn)))
            return MeasureValue(compiled.measure, null, SavingsRatePolicy.calculate(income, expense) ?: BigDecimal.ZERO)
        }
        val currency = compiled.currencyColumn?.let { column ->
            val index = getColumnIndexOrThrow(column)
            if (isNull(index)) null else CurrencyCode.parse(getString(index)).getOrNull() ?: error("invalid measure currency")
        }
        return MeasureValue(compiled.measure, getLong(getColumnIndexOrThrow(compiled.valueColumn)), null, currency)
    }

    private fun MeasureValue.isZero(): Boolean = minorValue == 0L || decimalValue?.signum() == 0

    private fun Cursor.toDrilldown(): DrilldownTransaction = DrilldownTransaction(
        StableId.fromBytes(getBlob(getColumnIndexOrThrow("transaction_uid"))).getOrNull() ?: error("invalid transaction id"),
        getInt(getColumnIndexOrThrow("local_date")).toLocalDate(),
        TRANSACTION_KINDS.getOrElse(getInt(getColumnIndexOrThrow("kind"))) { "UNKNOWN" },
        getLong(getColumnIndexOrThrow("input_amount_minor")),
        CurrencyCode.parse(getString(getColumnIndexOrThrow("input_currency"))).getOrNull() ?: error("invalid transaction currency"),
        optionalString("category_label"),
        optionalString("account_label"),
        optionalString("card_label"),
        optionalString("merchant_label"),
    )

    private fun Cursor.optionalString(column: String): String? = getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getString)

    private fun readVersion(connection: SupportSQLiteDatabase): Pair<LocalRevision, LocalRevision?>? = connection.query(
        "SELECT local_revision,valuation_revision FROM book WHERE id=1",
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val local = LocalRevision.of(cursor.getLong(0)).getOrNull() ?: return@use null
        val valuation = LocalRevision.of(cursor.getLong(1)).getOrNull() ?: return@use null
        local to valuation
    }

    private inline fun <T> withDatabase(bookId: StableId, block: (LedgerDatabase) -> DomainResult<T>): DomainResult<T> {
        val passphrase = try {
            passphrases.acquire(bookId)
        } catch (_: Exception) {
            return DomainResult.Failure(AnalyticsError.DatabaseUnavailable)
        }
        return try {
            val database = EncryptedDatabaseFactory.openPrimary(applicationContext, passphrase)
            try {
                block(database)
            } finally {
                database.close()
            }
        } catch (_: ArithmeticException) {
            DomainResult.Failure(AnalyticsError.InvalidReportSpec)
        } catch (_: Exception) {
            DomainResult.Failure(AnalyticsError.DatabaseUnavailable)
        } finally {
            passphrase.fill(0)
        }
    }

    private fun SupportSQLiteDatabase.singleLong(sql: String, args: Array<out Any?> = emptyArray()): Long? = query(sql, args).use {
        if (it.moveToFirst()) it.getLong(0) else null
    }

    private fun SupportSQLiteDatabase.singleString(sql: String): String? = query(sql).use {
        if (it.moveToFirst()) it.getString(0) else null
    }

    private fun Int.toLocalDate(): LocalDate = LocalDate.of(this / 10_000, (this / 100) % 100, this % 100)
    private fun LocalDate.storageKey(): Int = year * 10_000 + monthValue * 100 + dayOfMonth

    @Suppress("UNCHECKED_CAST")
    private fun <T> DomainResult<*>.castFailure(): DomainResult<T> = this as DomainResult<T>

    private class DrilldownRegistry {
        private val entries = object : LinkedHashMap<StableId, DrilldownContext>(MAX_DRILLDOWN_CONTEXTS, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<StableId, DrilldownContext>?): Boolean = size > MAX_DRILLDOWN_CONTEXTS
        }

        fun put(
            spec: ReportSpec,
            period: ReportPeriod,
            dimensions: List<DimensionValue>,
            localRevision: LocalRevision,
        ): DrilldownQueryId = synchronized(entries) {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(spec.toString().toByteArray(Charsets.UTF_8))
            digest.update(period.toString().toByteArray(Charsets.UTF_8))
            digest.update(dimensions.toString().toByteArray(Charsets.UTF_8))
            digest.update(localRevision.value.toString().toByteArray(Charsets.UTF_8))
            val id = StableId.fromBytes(digest.digest().copyOf(StableId.BYTE_COUNT)).getOrNull() ?: error("invalid query id")
            entries[id] = DrilldownContext(period, dimensions, localRevision, spec.granularity)
            DrilldownQueryId(id)
        }

        fun get(queryId: DrilldownQueryId): DrilldownContext? = synchronized(entries) { entries[queryId.value] }
    }

    private data class DrilldownContext(
        val period: ReportPeriod,
        val dimensions: List<DimensionValue>,
        val localRevision: LocalRevision,
        val granularity: app.ledger.analytics.domain.TimeGranularity,
    )

    private companion object {
        const val MAX_DRILLDOWN_PAGE = 100
        const val MAX_DRILLDOWN_CONTEXTS = 128
        const val PROJECTION_FAMILY_COUNT = 15
        val TRANSACTION_KINDS = listOf(
            "EXPENSE", "INCOME", "TRANSFER", "REFUND", "CREDIT_PAYMENT", "LOAN_DISBURSEMENT", "LOAN_PAYMENT",
            "BALANCE_ADJUSTMENT", "FX_EXCHANGE", "SETTLEMENT_PAYMENT", "OPENING_BALANCE",
        )
        val DRILLDOWN_ENTITY_COLUMNS = mapOf(
            Dimension.CATEGORY to "c.uid",
            Dimension.MERCHANT to "m.uid",
            Dimension.ACCOUNT to "ua.uid",
            Dimension.CARD to "pc.uid",
            Dimension.PROJECT to "p.uid",
            Dimension.GOAL to "g.uid",
            Dimension.PLACE to "pl.uid",
            Dimension.SETTLEMENT_ACTIVITY to "sa.uid",
            Dimension.PARTICIPANT to "part.uid",
        )
        val ENTITY_LABEL_SOURCES = mapOf(
            Dimension.CATEGORY to ("category" to "name"),
            Dimension.MERCHANT to ("merchant" to "name"),
            Dimension.ACCOUNT to ("user_account" to "name"),
            Dimension.CARD to ("payment_card" to "display_name"),
            Dimension.PROJECT to ("project" to "name"),
            Dimension.GOAL to ("goal" to "name"),
            Dimension.PLACE to ("place" to "name"),
            Dimension.SETTLEMENT_ACTIVITY to ("settlement_activity" to "name"),
            Dimension.PARTICIPANT to ("participant" to "name"),
        )
        const val DRILLDOWN_SELECT = "SELECT ctp.transaction_uid,ctp.local_date,ctp.kind,ctp.input_amount_minor,ctp.input_currency," +
            "c.name AS category_label,ua.name AS account_label,pc.display_name AS card_label,m.name AS merchant_label " +
            "FROM current_transaction_projection ctp LEFT JOIN category c ON c.id=ctp.category_id " +
            "LEFT JOIN merchant m ON m.id=ctp.merchant_id LEFT JOIN user_account ua ON ua.id=ctp.primary_account_id " +
            "LEFT JOIN payment_card pc ON pc.id=ctp.card_id LEFT JOIN project p ON p.id=ctp.project_id " +
            "LEFT JOIN goal g ON g.id=ctp.goal_id LEFT JOIN transaction_revision tr ON tr.id=ctp.current_revision_id " +
            "LEFT JOIN location_record lr ON lr.id=tr.location_record_id LEFT JOIN place pl ON pl.id=lr.place_id " +
            "LEFT JOIN settlement_activity sa ON sa.id=ctp.settlement_activity_id LEFT JOIN participant part ON part.id=ctp.payer_participant_id"
    }
}
