@file:Suppress("CyclomaticComplexMethod", "LongMethod", "LongParameterList", "MagicNumber", "MaxLineLength", "ReturnCount", "TooManyFunctions")

package app.ledger.analytics.data

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import app.ledger.analytics.domain.AnalyticsAlgorithmVersion
import app.ledger.analytics.domain.AnalyticsError
import app.ledger.analytics.domain.AnomalyRule
import app.ledger.analytics.domain.AnomalyRuleId
import app.ledger.analytics.domain.AnomalyRuleType
import app.ledger.analytics.domain.ComparisonMode
import app.ledger.analytics.domain.CustomReportPolicy
import app.ledger.analytics.domain.Dashboard
import app.ledger.analytics.domain.DashboardId
import app.ledger.analytics.domain.DashboardItem
import app.ledger.analytics.domain.DashboardItemWidth
import app.ledger.analytics.domain.DashboardRevision
import app.ledger.analytics.domain.DashboardRevisionId
import app.ledger.analytics.domain.DefaultDeterministicAnalyticsEngine
import app.ledger.analytics.domain.Dimension
import app.ledger.analytics.domain.FilterExpression
import app.ledger.analytics.domain.FilterField
import app.ledger.analytics.domain.FilterOperator
import app.ledger.analytics.domain.FilterValue
import app.ledger.analytics.domain.ForecastKey
import app.ledger.analytics.domain.ForecastMethod
import app.ledger.analytics.domain.ForecastRequest
import app.ledger.analytics.domain.ForecastResult
import app.ledger.analytics.domain.Measure
import app.ledger.analytics.domain.ReportDefinition
import app.ledger.analytics.domain.ReportDefinitionId
import app.ledger.analytics.domain.ReportDefinitionRevision
import app.ledger.analytics.domain.ReportRevisionId
import app.ledger.analytics.domain.ReportSort
import app.ledger.analytics.domain.ReportSpec
import app.ledger.analytics.domain.ReportVisualization
import app.ledger.analytics.domain.SaveAnomalyRuleRequest
import app.ledger.analytics.domain.SaveDashboardRequest
import app.ledger.analytics.domain.SaveReportDefinitionRequest
import app.ledger.analytics.domain.SavedAnomalyRule
import app.ledger.analytics.domain.SavedDashboard
import app.ledger.analytics.domain.SavedReportDefinition
import app.ledger.analytics.domain.SortDirection
import app.ledger.analytics.domain.TimeGranularity
import app.ledger.analytics.domain.TimeSeriesPoint
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.common.StableIdSource
import app.ledger.core.common.getOrNull
import app.ledger.core.database.AnalyticsProjectionEngine
import app.ledger.core.money.CurrencyCode
import app.ledger.core.time.LedgerClock
import app.ledger.finance.domain.CategoryId
import app.ledger.finance.domain.LocalRevision
import app.ledger.finance.domain.MerchantId
import app.ledger.finance.domain.ParticipantId
import app.ledger.finance.domain.PlaceId
import app.ledger.finance.domain.ProjectId
import app.ledger.finance.domain.UserAccountId
import java.math.BigDecimal
import java.time.LocalDate

internal class CustomAnalyticsStore(
    private val ids: StableIdSource,
    private val clock: LedgerClock,
    private val engine: DefaultDeterministicAnalyticsEngine = DefaultDeterministicAnalyticsEngine(),
) {
    fun listReports(db: SupportSQLiteDatabase): DomainResult<List<SavedReportDefinition>> = DomainResult.Success(
        db.query(
            "SELECT uid FROM analytics_report_definition WHERE archived=0 ORDER BY name COLLATE NOCASE,uid",
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(readReport(db, cursor.stableId(0)) ?: error("missing report")) } },
    )

    fun saveReport(db: SupportSQLiteDatabase, request: SaveReportDefinitionRequest): DomainResult<SavedReportDefinition> {
        val validation = CustomReportPolicy.validate(request.spec, request.visualization)
        if (!validation.valid) return DomainResult.Failure(AnalyticsError.InvalidReportSpec)
        val now = clock.now().toEpochMilli()
        val reportId = request.reportId?.value ?: ids.nextStableId()
        val internalId: Long
        val revisionNumber: Int
        val nextRowVersion: Long
        if (request.reportId == null) {
            internalId = db.nextId("analytics_report_definition")
            revisionNumber = 1
            nextRowVersion = 1L
            db.execSQL(
                "INSERT INTO analytics_report_definition(id,uid,name,current_revision_id,archived,row_version) VALUES(?,?,?,NULL,0,1)",
                arrayOf(internalId, reportId.bytes, request.name.trim()),
            )
        } else {
            val current = db.queryOne(
                "SELECT id,row_version FROM analytics_report_definition WHERE uid=? AND archived=0",
                arrayOf(reportId.bytes),
            ) { it.getLong(0) to it.getLong(1) } ?: return DomainResult.Failure(AnalyticsError.DefinitionNotFound)
            if (current.second != request.expectedRowVersion) return DomainResult.Failure(AnalyticsError.RevisionConflict)
            internalId = current.first
            revisionNumber = db.nextRevision("analytics_report_revision", "report_id", internalId)
            nextRowVersion = Math.addExact(current.second, 1L)
        }
        val revisionId = ids.nextStableId()
        val revisionInternalId = db.nextId("analytics_report_revision")
        insertReportRevision(db, revisionInternalId, revisionId, internalId, revisionNumber, request.spec, request.visualization, now)
        db.execSQL(
            "UPDATE analytics_report_definition SET name=?,current_revision_id=?,row_version=? WHERE id=?",
            arrayOf<Any?>(request.name.trim(), revisionInternalId, nextRowVersion, internalId),
        )
        return DomainResult.Success(requireNotNull(readReport(db, reportId)))
    }

    fun copyReport(db: SupportSQLiteDatabase, reportId: ReportDefinitionId, copyName: String): DomainResult<SavedReportDefinition> {
        val source = readReport(db, reportId.value) ?: return DomainResult.Failure(AnalyticsError.DefinitionNotFound)
        return saveReport(db, SaveReportDefinitionRequest(null, copyName, null, source.revision.spec, source.revision.visualization))
    }

    fun listDashboards(db: SupportSQLiteDatabase): DomainResult<List<SavedDashboard>> = DomainResult.Success(
        db.query("SELECT uid FROM analytics_dashboard WHERE archived=0 ORDER BY name COLLATE NOCASE,uid").use { cursor ->
            buildList { while (cursor.moveToNext()) add(readDashboard(db, cursor.stableId(0)) ?: error("missing dashboard")) }
        },
    )

    fun saveDashboard(db: SupportSQLiteDatabase, request: SaveDashboardRequest): DomainResult<SavedDashboard> {
        if (!reportsExist(db, request.items.map { it.reportId.value })) return DomainResult.Failure(AnalyticsError.DefinitionNotFound)
        if (!dashboardItemsCompatible(db, request.items)) return DomainResult.Failure(AnalyticsError.InvalidReportSpec)
        val dashboardId = request.dashboardId?.value ?: ids.nextStableId()
        val internalId: Long
        val revisionNumber: Int
        val nextRowVersion: Long
        if (request.dashboardId == null) {
            internalId = db.nextId("analytics_dashboard")
            revisionNumber = 1
            nextRowVersion = 1L
            db.execSQL(
                "INSERT INTO analytics_dashboard(id,uid,name,current_revision_id,archived,row_version) VALUES(?,?,?,NULL,0,1)",
                arrayOf(internalId, dashboardId.bytes, request.name.trim()),
            )
        } else {
            val current = db.queryOne(
                "SELECT id,row_version FROM analytics_dashboard WHERE uid=? AND archived=0",
                arrayOf(dashboardId.bytes),
            ) { it.getLong(0) to it.getLong(1) } ?: return DomainResult.Failure(AnalyticsError.DefinitionNotFound)
            if (current.second != request.expectedRowVersion) return DomainResult.Failure(AnalyticsError.RevisionConflict)
            internalId = current.first
            revisionNumber = db.nextRevision("analytics_dashboard_revision", "dashboard_id", internalId)
            nextRowVersion = Math.addExact(current.second, 1L)
        }
        val revisionId = ids.nextStableId()
        val revisionInternalId = db.nextId("analytics_dashboard_revision")
        val now = clock.now().toEpochMilli()
        db.execSQL(
            "INSERT INTO analytics_dashboard_revision(id,uid,dashboard_id,revision_no,created_at) VALUES(?,?,?,?,?)",
            arrayOf<Any?>(revisionInternalId, revisionId.bytes, internalId, revisionNumber, now),
        )
        request.items.forEach { item ->
            val reportInternalId = db.requireId("analytics_report_definition", item.reportId.value)
            db.execSQL(
                "INSERT INTO analytics_dashboard_item(dashboard_revision_id,report_id,sort_order,width) VALUES(?,?,?,?)",
                arrayOf<Any?>(revisionInternalId, reportInternalId, item.sortOrder, item.width.ordinal),
            )
        }
        db.execSQL(
            "UPDATE analytics_dashboard SET name=?,current_revision_id=?,row_version=? WHERE id=?",
            arrayOf<Any?>(request.name.trim(), revisionInternalId, nextRowVersion, internalId),
        )
        return DomainResult.Success(requireNotNull(readDashboard(db, dashboardId)))
    }

    fun listAnomalyRules(db: SupportSQLiteDatabase): DomainResult<List<SavedAnomalyRule>> = DomainResult.Success(readRules(db, enabledOnly = false))

    fun saveAnomalyRule(db: SupportSQLiteDatabase, request: SaveAnomalyRuleRequest): DomainResult<SavedAnomalyRule> {
        val ruleId = request.ruleId?.value ?: ids.nextStableId()
        val internalId: Long
        val revisionNumber: Int
        val nextRowVersion: Long
        if (request.ruleId == null) {
            internalId = db.nextId("analytics_anomaly_rule")
            revisionNumber = 1
            nextRowVersion = 1L
            db.execSQL(
                "INSERT INTO analytics_anomaly_rule(id,uid,current_revision_id,enabled,row_version) VALUES(?,?,NULL,?,1)",
                arrayOf(internalId, ruleId.bytes, request.enabled.toInt()),
            )
        } else {
            val current = db.queryOne(
                "SELECT id,row_version FROM analytics_anomaly_rule WHERE uid=?",
                arrayOf(ruleId.bytes),
            ) { it.getLong(0) to it.getLong(1) } ?: return DomainResult.Failure(AnalyticsError.DefinitionNotFound)
            if (current.second != request.expectedRowVersion) return DomainResult.Failure(AnalyticsError.RevisionConflict)
            internalId = current.first
            revisionNumber = db.nextRevision("analytics_anomaly_rule_revision", "anomaly_rule_id", internalId)
            nextRowVersion = Math.addExact(current.second, 1L)
        }
        val revisionInternalId = db.nextId("analytics_anomaly_rule_revision")
        db.execSQL(
            "INSERT INTO analytics_anomaly_rule_revision(id,uid,anomaly_rule_id,revision_no,rule_type,threshold_decimal,lookback_periods,algorithm_version,created_at) " +
                "VALUES(?,?,?,?,?,?,?,?,?)",
            arrayOf(
                revisionInternalId,
                ids.nextStableId().bytes,
                internalId,
                revisionNumber,
                request.rule.type.ordinal,
                request.rule.threshold.toPlainString(),
                request.rule.lookbackPeriods,
                request.rule.version.value,
                clock.now().toEpochMilli(),
            ),
        )
        db.execSQL(
            "UPDATE analytics_anomaly_rule SET current_revision_id=?,enabled=?,row_version=? WHERE id=?",
            arrayOf<Any?>(revisionInternalId, request.enabled.toInt(), nextRowVersion, internalId),
        )
        return DomainResult.Success(requireNotNull(readRule(db, ruleId)))
    }

    fun anomalyFindings(db: SupportSQLiteDatabase, period: app.ledger.analytics.domain.ReportPeriod): DomainResult<List<app.ledger.analytics.domain.AnomalyFinding>> {
        val revision = db.localRevision() ?: return DomainResult.Failure(AnalyticsError.DatabaseUnavailable)
        val rules = readRules(db, enabledOnly = true)
        if (rules.isEmpty()) return DomainResult.Success(emptyList())
        val findings = buildList {
            rules.forEach { saved ->
                val points = when (saved.rule.type) {
                    AnomalyRuleType.HISTORICAL_MEAN_STANDARD_DEVIATION,
                    AnomalyRuleType.RECENT_MONTH_GROWTH_THRESHOLD,
                    -> monthlyExpenseSeries(db, period, saved.rule.lookbackPeriods)
                    AnomalyRuleType.LARGE_SINGLE_TRANSACTION -> transactionExpenseSeries(db, period)
                    AnomalyRuleType.MERCHANT_FREQUENCY -> frequencySeries(db, period, "merchant")
                    AnomalyRuleType.CATEGORY_FREQUENCY -> frequencySeries(db, period, "category")
                }
                val result = engine.anomalies(points, listOf(saved.rule), revision)
                if (result is DomainResult.Success) addAll(result.value)
            }
        }
        return DomainResult.Success(findings.sortedWith(compareBy({ it.date }, { it.seriesKey }, { it.rule.type.ordinal })))
    }

    fun forecast(db: SupportSQLiteDatabase, key: ForecastKey, today: LocalDate): DomainResult<ForecastResult> {
        val revision = db.localRevision() ?: return DomainResult.Failure(AnalyticsError.DatabaseUnavailable)
        val budgetCurrent = db.query(
            "SELECT COUNT(*) FROM projection_family_state WHERE family=? AND as_of_local_revision=? " +
                "AND as_of_valuation_revision=(SELECT valuation_revision FROM book WHERE id=1)",
            arrayOf<Any>(BUDGET_FAMILY, revision.value),
        ).use { cursor -> cursor.moveToFirst() && cursor.getLong(0) == 1L }
        if (!budgetCurrent) return DomainResult.Failure(AnalyticsError.StaleProjection)
        val through = today.withDayOfMonth(today.lengthOfMonth())
        val earliest = today.minusYears(HISTORICAL_YEARS.toLong()).withDayOfYear(1)
        val observations = db.query(
            "SELECT local_date,amount_base_minor FROM analytics_daily_total WHERE metric=? AND local_date BETWEEN ? AND ? ORDER BY local_date",
            arrayOf<Any?>(AnalyticsProjectionEngine.EXPENSE_METRIC, earliest.storageKey(), today.storageKey()),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(TimeSeriesPoint(cursor.getInt(0).date(), cursor.getLong(1))) } }
        val recurrence = db.query(
            "SELECT occurrence_date,reserved_base_minor FROM budget_future_reservation WHERE occurrence_date>? AND occurrence_date<=? ORDER BY occurrence_date,recurrence_series_id",
            arrayOf<Any?>(today.storageKey(), through.storageKey()),
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val date = cursor.getInt(0).date()
                    put(date, Math.addExact(get(date) ?: 0L, cursor.getLong(1)))
                }
            }
        }
        val method = when (key) {
            ForecastKey.MONTH_END_SPENDING -> ForecastMethod.CURRENT_DAILY_AVERAGE
            ForecastKey.MONTH_END_BALANCE_WITH_RECURRENCE -> ForecastMethod.DAILY_AVERAGE_WITH_RECURRENCE
            ForecastKey.HISTORICAL_SAME_MONTH -> ForecastMethod.HISTORICAL_SAME_MONTH
        }
        val startingBalance = if (key == ForecastKey.MONTH_END_BALANCE_WITH_RECURRENCE) {
            db.queryOne("SELECT core_net_financial_assets_base_minor FROM widget_book_snapshot WHERE id=1") { it.getLong(0) }
                ?: return DomainResult.Failure(AnalyticsError.StaleProjection)
        } else {
            null
        }
        return engine.forecast(
            ForecastRequest(
                method,
                observations,
                recurrence,
                today,
                through,
                AnalyticsAlgorithmVersion(ALGORITHM_VERSION),
                key,
                startingBalance,
            ),
            revision,
        )
    }

    private fun insertReportRevision(
        db: SupportSQLiteDatabase,
        revisionInternalId: Long,
        revisionId: StableId,
        reportInternalId: Long,
        revisionNumber: Int,
        spec: ReportSpec,
        visualization: ReportVisualization,
        createdAt: Long,
    ) {
        db.execSQL(
            "INSERT INTO analytics_report_revision(id,uid,report_id,revision_no,granularity,comparison,visualization,algorithm_version,created_at) " +
                "VALUES(?,?,?,?,?,?,?,?,?)",
            arrayOf(
                revisionInternalId,
                revisionId.bytes,
                reportInternalId,
                revisionNumber,
                spec.granularity.ordinal,
                spec.comparison?.ordinal,
                visualization.ordinal,
                ALGORITHM_VERSION,
                createdAt,
            ),
        )
        spec.measures.forEachIndexed { index, value ->
            db.execSQL("INSERT INTO analytics_report_measure VALUES(?,?,?)", arrayOf<Any?>(revisionInternalId, index, value.ordinal))
        }
        spec.dimensions.forEachIndexed { index, value ->
            db.execSQL("INSERT INTO analytics_report_dimension VALUES(?,?,?)", arrayOf<Any?>(revisionInternalId, index, value.ordinal))
        }
        spec.sorting.forEachIndexed { index, value ->
            val kind = if (value is ReportSort.ByMeasure) 0 else 1
            val target = if (value is ReportSort.ByMeasure) value.measure.ordinal else (value as ReportSort.ByDimension).dimension.ordinal
            db.execSQL("INSERT INTO analytics_report_sort VALUES(?,?,?,?,?)", arrayOf<Any?>(revisionInternalId, index, kind, target, value.direction.ordinal))
        }
        insertFilter(db, revisionInternalId, null, 0, spec.filters)
    }

    private fun insertFilter(
        db: SupportSQLiteDatabase,
        revisionInternalId: Long,
        parentId: Long?,
        order: Int,
        expression: FilterExpression,
    ) {
        val nodeId = db.nextId("analytics_report_filter_node")
        val kind = when (expression) {
            FilterExpression.All -> 0
            is FilterExpression.And -> 1
            is FilterExpression.Or -> 2
            is FilterExpression.Not -> 3
            is FilterExpression.Predicate -> 4
        }
        val predicate = expression as? FilterExpression.Predicate
        db.execSQL(
            "INSERT INTO analytics_report_filter_node(id,report_revision_id,parent_node_id,child_order,node_kind,filter_field,filter_operator) VALUES(?,?,?,?,?,?,?)",
            arrayOf<Any?>(nodeId, revisionInternalId, parentId, order, kind, predicate?.field?.ordinal, predicate?.operator?.ordinal),
        )
        when (expression) {
            FilterExpression.All -> Unit
            is FilterExpression.And -> expression.operands.forEachIndexed { index, child -> insertFilter(db, revisionInternalId, nodeId, index, child) }
            is FilterExpression.Or -> expression.operands.forEachIndexed { index, child -> insertFilter(db, revisionInternalId, nodeId, index, child) }
            is FilterExpression.Not -> insertFilter(db, revisionInternalId, nodeId, 0, expression.operand)
            is FilterExpression.Predicate -> insertFilterValues(db, nodeId, expression.value)
        }
    }

    private fun insertFilterValues(db: SupportSQLiteDatabase, nodeId: Long, value: FilterValue) {
        fun insert(order: Int, kind: Int, first: Long? = null, second: Long? = null, stable: StableId? = null, text: String? = null, flag: Boolean? = null) {
            db.execSQL(
                "INSERT INTO analytics_report_filter_value(filter_node_id,value_order,value_kind,first_long,second_long,stable_uid,text_value,flag_value) VALUES(?,?,?,?,?,?,?,?)",
                arrayOf(nodeId, order, kind, first, second, stable?.bytes, text, flag?.toInt()),
            )
        }
        when (value) {
            is FilterValue.DateRange -> insert(0, 0, value.start.toEpochDay(), value.endInclusive.toEpochDay())
            is FilterValue.AmountRange -> insert(0, 1, value.minimumMinor, value.maximumMinor)
            is FilterValue.Accounts -> value.values.sortedBy { it.value }.forEachIndexed { index, item -> insert(index, 2, stable = item.value) }
            is FilterValue.Categories -> value.values.sortedBy { it.value }.forEachIndexed { index, item -> insert(index, 2, stable = item.value) }
            is FilterValue.Merchants -> value.values.sortedBy { it.value }.forEachIndexed { index, item -> insert(index, 2, stable = item.value) }
            is FilterValue.Projects -> value.values.sortedBy { it.value }.forEachIndexed { index, item -> insert(index, 2, stable = item.value) }
            is FilterValue.Places -> value.values.sortedBy { it.value }.forEachIndexed { index, item -> insert(index, 2, stable = item.value) }
            is FilterValue.Participants -> value.values.sortedBy { it.value }.forEachIndexed { index, item -> insert(index, 2, stable = item.value) }
            is FilterValue.Currencies -> value.values.sortedBy(CurrencyCode::value).forEachIndexed { index, item -> insert(index, 3, text = item.value) }
            is FilterValue.ClosedKeys -> value.values.sorted().forEachIndexed { index, item -> insert(index, 4, text = item) }
            is FilterValue.Flag -> insert(0, 5, flag = value.value)
        }
    }

    private fun readReport(db: SupportSQLiteDatabase, id: StableId): SavedReportDefinition? = db.queryOne(
        "SELECT d.name,d.archived,d.row_version,r.uid,r.revision_no,r.granularity,r.comparison,r.visualization,r.algorithm_version,r.created_at,r.id " +
            "FROM analytics_report_definition d JOIN analytics_report_revision r ON r.id=d.current_revision_id WHERE d.uid=?",
        arrayOf(id.bytes),
    ) { cursor ->
        val revisionInternalId = cursor.getLong(10)
        val spec = readSpec(db, revisionInternalId, TimeGranularity.entries[cursor.getInt(5)], cursor.nullableInt(6)?.let { ComparisonMode.entries[it] })
        val definitionId = ReportDefinitionId(id)
        val revisionId = ReportRevisionId(cursor.stableId(3))
        SavedReportDefinition(
            ReportDefinition(definitionId, cursor.getString(0), revisionId, cursor.getInt(1) != 0, cursor.getLong(2)),
            ReportDefinitionRevision(
                revisionId,
                definitionId,
                cursor.getInt(4),
                spec,
                ReportVisualization.entries[cursor.getInt(7)],
                cursor.getInt(8),
                cursor.getLong(9),
            ),
        )
    }

    private fun readSpec(
        db: SupportSQLiteDatabase,
        revisionId: Long,
        granularity: TimeGranularity,
        comparison: ComparisonMode?,
    ): ReportSpec {
        val measures = db.query("SELECT measure FROM analytics_report_measure WHERE report_revision_id=? ORDER BY sort_order", arrayOf(revisionId))
            .use { cursor -> buildList { while (cursor.moveToNext()) add(Measure.entries[cursor.getInt(0)]) } }
        val dimensions = db.query("SELECT dimension FROM analytics_report_dimension WHERE report_revision_id=? ORDER BY sort_order", arrayOf(revisionId))
            .use { cursor -> buildList { while (cursor.moveToNext()) add(Dimension.entries[cursor.getInt(0)]) } }
        val sorting = db.query(
            "SELECT sort_kind,target_key,direction FROM analytics_report_sort WHERE report_revision_id=? ORDER BY sort_order",
            arrayOf(revisionId),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val direction = SortDirection.entries[cursor.getInt(2)]
                    add(if (cursor.getInt(0) == 0) ReportSort.ByMeasure(Measure.entries[cursor.getInt(1)], direction) else ReportSort.ByDimension(Dimension.entries[cursor.getInt(1)], direction))
                }
            }
        }
        return ReportSpec(measures, dimensions, readFilter(db, revisionId), granularity, sorting, comparison)
    }

    private fun readFilter(db: SupportSQLiteDatabase, revisionId: Long): FilterExpression {
        val nodes = db.query(
            "SELECT id,parent_node_id,child_order,node_kind,filter_field,filter_operator FROM analytics_report_filter_node WHERE report_revision_id=? ORDER BY id",
            arrayOf(revisionId),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(FilterNode(cursor.getLong(0), cursor.nullableLong(1), cursor.getInt(2), cursor.getInt(3), cursor.nullableInt(4), cursor.nullableInt(5)))
            }
        }
        val root = nodes.single { it.parentId == null }
        fun decode(node: FilterNode): FilterExpression {
            val children = nodes.filter { it.parentId == node.id }.sortedBy(FilterNode::order)
            return when (node.kind) {
                0 -> FilterExpression.All
                1 -> FilterExpression.And(children.map(::decode))
                2 -> FilterExpression.Or(children.map(::decode))
                3 -> FilterExpression.Not(decode(children.single()))
                else -> FilterExpression.Predicate(FilterField.entries[requireNotNull(node.field)], FilterOperator.entries[requireNotNull(node.operator)], readFilterValue(db, node.id, FilterField.entries[node.field]))
            }
        }
        return decode(root)
    }

    private fun readFilterValue(db: SupportSQLiteDatabase, nodeId: Long, field: FilterField): FilterValue {
        val values = db.query(
            "SELECT value_kind,first_long,second_long,stable_uid,text_value,flag_value FROM analytics_report_filter_value WHERE filter_node_id=? ORDER BY value_order",
            arrayOf(nodeId),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(FilterStoredValue.from(cursor)) } }
        val first = values.first()
        return when (first.kind) {
            0 -> FilterValue.DateRange(LocalDate.ofEpochDay(requireNotNull(first.first)), LocalDate.ofEpochDay(requireNotNull(first.second)))
            1 -> FilterValue.AmountRange(first.first, first.second)
            2 -> {
                val stable = values.map { requireNotNull(it.stable) }.toSet()
                when (field) {
                    FilterField.ACCOUNT -> FilterValue.Accounts(stable.map(::UserAccountId).toSet())
                    FilterField.CATEGORY -> FilterValue.Categories(stable.map(::CategoryId).toSet())
                    FilterField.MERCHANT -> FilterValue.Merchants(stable.map(::MerchantId).toSet())
                    FilterField.PROJECT -> FilterValue.Projects(stable.map(::ProjectId).toSet())
                    FilterField.PLACE -> FilterValue.Places(stable.map(::PlaceId).toSet())
                    FilterField.PARTICIPANT -> FilterValue.Participants(stable.map(::ParticipantId).toSet())
                    else -> error("stable filter is incompatible with field")
                }
            }
            3 -> FilterValue.Currencies(values.map { CurrencyCode.parse(requireNotNull(it.text)).getOrNull() ?: error("currency") }.toSet())
            4 -> FilterValue.ClosedKeys(values.map { requireNotNull(it.text) }.toSet())
            else -> FilterValue.Flag(requireNotNull(first.flag))
        }
    }

    private fun readDashboard(db: SupportSQLiteDatabase, id: StableId): SavedDashboard? = db.queryOne(
        "SELECT d.name,d.archived,d.row_version,r.uid,r.revision_no,r.created_at,r.id FROM analytics_dashboard d " +
            "JOIN analytics_dashboard_revision r ON r.id=d.current_revision_id WHERE d.uid=?",
        arrayOf(id.bytes),
    ) { cursor ->
        val dashboardId = DashboardId(id)
        val revisionId = DashboardRevisionId(cursor.stableId(3))
        val items = db.query(
            "SELECT rd.uid,i.sort_order,i.width FROM analytics_dashboard_item i JOIN analytics_report_definition rd ON rd.id=i.report_id " +
                "WHERE i.dashboard_revision_id=? ORDER BY i.sort_order",
            arrayOf(cursor.getLong(6)),
        ).use { itemsCursor ->
            buildList {
                while (itemsCursor.moveToNext()) add(DashboardItem(ReportDefinitionId(itemsCursor.stableId(0)), itemsCursor.getInt(1), DashboardItemWidth.entries[itemsCursor.getInt(2)]))
            }
        }
        SavedDashboard(
            Dashboard(dashboardId, cursor.getString(0), items, cursor.getInt(1) != 0, revisionId, cursor.getLong(2)),
            DashboardRevision(revisionId, dashboardId, cursor.getInt(4), items, cursor.getLong(5)),
        )
    }

    private fun readRules(db: SupportSQLiteDatabase, enabledOnly: Boolean): List<SavedAnomalyRule> {
        val where = if (enabledOnly) "WHERE a.enabled=1" else ""
        return db.query("SELECT a.uid FROM analytics_anomaly_rule a $where ORDER BY a.uid").use { cursor ->
            buildList { while (cursor.moveToNext()) add(requireNotNull(readRule(db, cursor.stableId(0)))) }
        }
    }

    private fun readRule(db: SupportSQLiteDatabase, id: StableId): SavedAnomalyRule? = db.queryOne(
        "SELECT a.enabled,a.row_version,r.rule_type,r.threshold_decimal,r.lookback_periods,r.algorithm_version FROM analytics_anomaly_rule a " +
            "JOIN analytics_anomaly_rule_revision r ON r.id=a.current_revision_id WHERE a.uid=?",
        arrayOf(id.bytes),
    ) { cursor ->
        SavedAnomalyRule(
            AnomalyRuleId(id),
            AnomalyRule(AnomalyRuleType.entries[cursor.getInt(2)], BigDecimal(cursor.getString(3)), cursor.getInt(4), AnalyticsAlgorithmVersion(cursor.getInt(5))),
            cursor.getInt(0) != 0,
            cursor.getLong(1),
        )
    }

    private fun monthlyExpenseSeries(db: SupportSQLiteDatabase, period: app.ledger.analytics.domain.ReportPeriod, lookback: Int): List<TimeSeriesPoint> {
        val start = period.start.minusMonths(lookback.toLong()).withDayOfMonth(1)
        return db.query(
            "SELECT year_month,amount_base_minor FROM analytics_monthly_total WHERE metric=? AND year_month BETWEEN ? AND ? ORDER BY year_month",
            arrayOf(AnalyticsProjectionEngine.EXPENSE_METRIC, start.year * 100 + start.monthValue, period.endInclusive.year * 100 + period.endInclusive.monthValue),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(TimeSeriesPoint(cursor.getInt(0).yearMonthDate(), cursor.getLong(1))) } }
    }

    private fun transactionExpenseSeries(db: SupportSQLiteDatabase, period: app.ledger.analytics.domain.ReportPeriod): List<TimeSeriesPoint> = db.query(
        "SELECT ctp.local_date,ctp.transaction_uid,SUM(ee.polarity*ee.base_amount_minor) amount FROM current_transaction_projection ctp " +
            "JOIN economic_effect ee ON ee.source_revision_id=ctp.current_revision_id WHERE ctp.state=0 AND ee.nature=1 AND ctp.local_date BETWEEN ? AND ? " +
            "GROUP BY ctp.transaction_id ORDER BY ctp.local_date,ctp.transaction_uid",
        arrayOf(period.start.storageKey(), period.endInclusive.storageKey()),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(TimeSeriesPoint(cursor.getInt(0).date(), cursor.getLong(2), cursor.stableId(1).toString())) } }

    private fun frequencySeries(
        db: SupportSQLiteDatabase,
        period: app.ledger.analytics.domain.ReportPeriod,
        dimension: String,
    ): List<TimeSeriesPoint> {
        require(dimension == "merchant" || dimension == "category")
        val column = "${dimension}_id"
        val table = dimension
        val start = period.start.minusMonths(DEFAULT_LOOKBACK.toLong()).withDayOfMonth(1)
        return db.query(
            "SELECT (ctp.local_date/100) ym,e.uid,COUNT(*) FROM current_transaction_projection ctp JOIN $table e ON e.id=ctp.$column " +
                "WHERE ctp.state=0 AND ctp.local_date BETWEEN ? AND ? GROUP BY ym,e.id ORDER BY e.uid,ym",
            arrayOf(start.storageKey(), period.endInclusive.storageKey()),
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(TimeSeriesPoint(cursor.getInt(0).yearMonthDate(), 0L, cursor.stableId(1).toString(), cursor.getLong(2))) }
        }
    }

    private fun reportsExist(db: SupportSQLiteDatabase, ids: List<StableId>): Boolean = ids.all { id ->
        db.queryOne("SELECT COUNT(*) FROM analytics_report_definition WHERE uid=? AND archived=0", arrayOf(id.bytes)) { it.getLong(0) } == 1L
    }

    private fun dashboardItemsCompatible(db: SupportSQLiteDatabase, items: List<DashboardItem>): Boolean = items.all { item ->
        item.width == DashboardItemWidth.FULL || db.queryOne(
            "SELECT r.visualization FROM analytics_report_definition d JOIN analytics_report_revision r ON r.id=d.current_revision_id WHERE d.uid=?",
            arrayOf(item.reportId.value.bytes),
        ) { it.getInt(0) } == ReportVisualization.METRIC_CARD.ordinal
    }

    private data class FilterNode(val id: Long, val parentId: Long?, val order: Int, val kind: Int, val field: Int?, val operator: Int?)

    private data class FilterStoredValue(
        val kind: Int,
        val first: Long?,
        val second: Long?,
        val stable: StableId?,
        val text: String?,
        val flag: Boolean?,
    ) {
        companion object {
            fun from(cursor: Cursor): FilterStoredValue = FilterStoredValue(
                cursor.getInt(0),
                cursor.nullableLong(1),
                cursor.nullableLong(2),
                cursor.nullableBlob(3)?.let { StableId.fromBytes(it).getOrNull() ?: error("stable id") },
                cursor.nullableString(4),
                cursor.nullableInt(5)?.let { it != 0 },
            )
        }
    }

    private companion object {
        const val ALGORITHM_VERSION: Int = 1
        const val BUDGET_FAMILY: Int = 4
        const val DEFAULT_LOOKBACK: Int = 12
        const val HISTORICAL_YEARS: Int = 5
    }
}

private fun Boolean.toInt(): Int = if (this) 1 else 0

private fun SupportSQLiteDatabase.nextId(table: String): Long {
    require(TABLE.matches(table))
    return Math.addExact(queryOne("SELECT COALESCE(MAX(id),0) FROM $table") { it.getLong(0) } ?: 0L, 1L)
}

private fun SupportSQLiteDatabase.nextRevision(table: String, ownerColumn: String, ownerId: Long): Int {
    require(TABLE.matches(table) && TABLE.matches(ownerColumn))
    return Math.addExact(queryOne("SELECT COALESCE(MAX(revision_no),0) FROM $table WHERE $ownerColumn=?", arrayOf(ownerId)) { it.getInt(0) } ?: 0, 1)
}

private fun SupportSQLiteDatabase.requireId(table: String, id: StableId): Long {
    require(TABLE.matches(table))
    return queryOne("SELECT id FROM $table WHERE uid=?", arrayOf(id.bytes)) { it.getLong(0) } ?: error("missing stable reference")
}

private fun SupportSQLiteDatabase.localRevision(): LocalRevision? = queryOne("SELECT local_revision FROM book WHERE id=1") { cursor ->
    LocalRevision.of(cursor.getLong(0)).getOrNull()
}

private inline fun <T> SupportSQLiteDatabase.queryOne(sql: String, args: Array<out Any?> = emptyArray(), mapper: (Cursor) -> T): T? = query(sql, args).use {
    if (it.moveToFirst()) mapper(it) else null
}

private fun Cursor.stableId(index: Int): StableId = StableId.fromBytes(getBlob(index)).getOrNull() ?: error("invalid stable id")
private fun Cursor.nullableLong(index: Int): Long? = if (isNull(index)) null else getLong(index)
private fun Cursor.nullableInt(index: Int): Int? = if (isNull(index)) null else getInt(index)
private fun Cursor.nullableBlob(index: Int): ByteArray? = if (isNull(index)) null else getBlob(index)
private fun Cursor.nullableString(index: Int): String? = if (isNull(index)) null else getString(index)
private fun LocalDate.storageKey(): Int = year * 10_000 + monthValue * 100 + dayOfMonth
private fun Int.date(): LocalDate = LocalDate.of(this / 10_000, (this / 100) % 100, this % 100)
private fun Int.yearMonthDate(): LocalDate = LocalDate.of(this / 100, this % 100, 1)
private val TABLE = Regex("[a-z][a-z0-9_]{1,63}")
