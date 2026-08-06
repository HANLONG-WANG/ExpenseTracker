package app.ledger.analytics.domain

import app.ledger.core.common.DomainError
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.money.CurrencyCode
import app.ledger.finance.domain.LocalRevision
import java.math.BigDecimal
import java.time.LocalDate

interface AnalyticsQueryPort {
    suspend fun plan(bookId: StableId, spec: ReportSpec): DomainResult<ReportQueryPlan>

    suspend fun execute(
        bookId: StableId,
        plan: ReportQueryPlan,
        period: ReportPeriod,
        requestedVisualization: ReportVisualization,
    ): DomainResult<ReportExecution>

    suspend fun drillDown(
        bookId: StableId,
        queryId: DrilldownQueryId,
        cursor: DrilldownCursor?,
        limit: Int,
    ): DomainResult<DrilldownPage>
}

sealed interface AnalyticsError : DomainError {
    data object DatabaseUnavailable : AnalyticsError {
        override val code: String = "ANALYTICS_DATABASE_UNAVAILABLE"
    }

    data object InvalidReportSpec : AnalyticsError {
        override val code: String = "ANALYTICS_INVALID_REPORT_SPEC"
    }

    data object ExpiredDrilldown : AnalyticsError {
        override val code: String = "ANALYTICS_EXPIRED_DRILLDOWN"
    }

    data object StaleProjection : AnalyticsError {
        override val code: String = "ANALYTICS_STALE_PROJECTION"
    }
}

data class ReportPeriod(val start: LocalDate, val endInclusive: LocalDate) {
    init {
        require(endInclusive >= start)
    }
}

data class AnalysisOverview(
    val period: ReportPeriod,
    val baseCurrency: CurrencyCode,
    val incomeMinor: Long,
    val allExpenseMinor: Long,
    val consumptionMinor: Long,
    val netSurplusMinor: Long,
    val savingsRate: BigDecimal?,
    val transactionCount: Long,
    val asOfLocalRevision: LocalRevision,
)

sealed interface ReportExecution {
    data class Content(
        val fixedReport: FixedReport?,
        val plan: ReportQueryPlan,
        val rows: List<ReportRow>,
        val visualization: VisualizationResolution,
        val summaryCode: String,
        val comparison: ReportComparison? = null,
    ) : ReportExecution

    data class Empty(
        val fixedReport: FixedReport?,
        val plan: ReportQueryPlan,
        val visualization: VisualizationResolution,
    ) : ReportExecution

    data class StaleProjection(
        val localRevision: LocalRevision,
        val staleTables: Set<String>,
    ) : ReportExecution
}

data class ReportComparison(
    val mode: ComparisonMode,
    val referencePeriod: ReportPeriod,
    val rows: List<ReportRow>,
) {
    init {
        require(mode == ComparisonMode.PREVIOUS_PERIOD || mode == ComparisonMode.YEAR_OVER_YEAR)
    }
}

data class DrilldownCursor(
    val occurredAtEpochMillis: Long,
    val transactionId: StableId,
)

data class DrilldownTransaction(
    val transactionId: StableId,
    val localDate: LocalDate,
    val kindKey: String,
    val amountMinor: Long,
    val currency: CurrencyCode,
)

data class DrilldownPage(
    val rows: List<DrilldownTransaction>,
    val nextCursor: DrilldownCursor?,
)

enum class IntegritySeverity {
    PASS,
    WARNING,
    FAILURE,
}

enum class IntegrityCheckKey {
    DATABASE,
    FOREIGN_KEYS,
    JOURNALS,
    POSTING_CURRENCIES,
    REVISIONS,
    PROJECTIONS,
    FTS,
    RTREE,
    FACT_REBUILD,
}

data class IntegrityCheckResult(
    val key: IntegrityCheckKey,
    val severity: IntegritySeverity,
    val affectedCount: Long,
    val diagnosticCode: String,
)

data class AnalyticsIntegrityReport(
    val checks: List<IntegrityCheckResult>,
    val localRevision: LocalRevision,
    val liveProjectionHash: String,
    val rebuiltProjectionHash: String,
) {
    init {
        require(checks.map(IntegrityCheckResult::key).toSet() == IntegrityCheckKey.entries.toSet())
    }

    val severity: IntegritySeverity
        get() = if (checks.any { it.severity == IntegritySeverity.FAILURE }) {
            IntegritySeverity.FAILURE
        } else if (checks.any { it.severity == IntegritySeverity.WARNING }) {
            IntegritySeverity.WARNING
        } else {
            IntegritySeverity.PASS
        }
}

enum class ReportExportFormat {
    IMAGE,
    PDF,
    CSV,
    XLSX,
}

data class ReportExportPayload(
    val format: ReportExportFormat,
    val reportKey: ReportKey?,
    val period: ReportPeriod,
    val plan: ReportQueryPlan,
    val rows: List<ReportRow>,
    val comparison: ReportComparison?,
)

interface AnalyticsApplicationPort : AnalyticsQueryPort {
    fun fixedReports(): List<FixedReportDefinition>

    suspend fun overview(bookId: StableId, period: ReportPeriod): DomainResult<AnalysisOverview>

    suspend fun executeFixed(
        bookId: StableId,
        report: FixedReport,
        period: ReportPeriod,
        requestedVisualization: ReportVisualization? = null,
    ): DomainResult<ReportExecution>

    suspend fun executeCustom(
        bookId: StableId,
        spec: ReportSpec,
        period: ReportPeriod,
        requestedVisualization: ReportVisualization,
    ): DomainResult<ReportExecution>

    suspend fun integrity(bookId: StableId): DomainResult<AnalyticsIntegrityReport>

    suspend fun repairAnalyticsProjections(bookId: StableId): DomainResult<AnalyticsIntegrityReport>

    fun prepareExport(
        execution: ReportExecution.Content,
        period: ReportPeriod,
        format: ReportExportFormat,
    ): DomainResult<ReportExportPayload>
}

data class TimeSeriesPoint(
    val date: LocalDate,
    val amountMinor: Long,
)

@JvmInline
value class AnalyticsAlgorithmVersion(val value: Int) {
    init {
        require(value > 0)
    }
}

enum class AnomalyRuleType {
    HISTORICAL_MEAN_STANDARD_DEVIATION,
    RECENT_MONTH_GROWTH_THRESHOLD,
    LARGE_SINGLE_TRANSACTION,
    MERCHANT_FREQUENCY,
    CATEGORY_FREQUENCY,
}

data class AnomalyRule(
    val type: AnomalyRuleType,
    val threshold: BigDecimal,
    val lookbackPeriods: Int,
    val version: AnalyticsAlgorithmVersion,
) {
    init {
        require(threshold.signum() >= 0)
        require(lookbackPeriods > 0)
    }
}

data class AnomalyFinding(
    val rule: AnomalyRule,
    val date: LocalDate,
    val observedMinor: Long,
    val baselineMinor: Long,
    val score: BigDecimal,
    val explanationCode: String,
)

enum class ForecastMethod {
    CURRENT_DAILY_AVERAGE,
    DAILY_AVERAGE_WITH_RECURRENCE,
    HISTORICAL_SAME_MONTH,
}

data class ForecastRequest(
    val method: ForecastMethod,
    val observations: List<TimeSeriesPoint>,
    val futureRecurrenceMinorByDate: Map<LocalDate, Long>,
    val today: LocalDate,
    val throughDate: LocalDate,
    val version: AnalyticsAlgorithmVersion,
) {
    init {
        require(throughDate >= today)
    }
}

data class ForecastResult(
    val method: ForecastMethod,
    val projectedMinor: Long,
    val throughDate: LocalDate,
    val version: AnalyticsAlgorithmVersion,
    val explanationCode: String,
    val basedOnLocalRevision: LocalRevision,
)

interface DeterministicAnalyticsEngine {
    fun anomalies(
        series: List<TimeSeriesPoint>,
        rules: List<AnomalyRule>,
        asOfLocalRevision: LocalRevision,
    ): DomainResult<List<AnomalyFinding>>

    fun forecast(request: ForecastRequest, asOfLocalRevision: LocalRevision): DomainResult<ForecastResult>
}
