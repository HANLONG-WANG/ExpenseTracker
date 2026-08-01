package app.ledger.analytics.domain

import app.ledger.core.common.DomainResult
import app.ledger.finance.domain.LocalRevision
import app.ledger.finance.domain.TransactionId
import java.math.BigDecimal
import java.time.LocalDate

interface AnalyticsQueryPort {
    suspend fun plan(spec: ReportSpec): DomainResult<ReportQueryPlan>

    suspend fun execute(plan: ReportQueryPlan): DomainResult<List<ReportRow>>

    suspend fun drillDown(transactionIds: List<TransactionId>): DomainResult<List<TransactionId>>
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
