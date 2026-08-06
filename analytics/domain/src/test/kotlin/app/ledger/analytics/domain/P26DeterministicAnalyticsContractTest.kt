package app.ledger.analytics.domain

import app.ledger.core.common.DomainResult
import app.ledger.finance.domain.LocalRevision
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class P26DeterministicAnalyticsContractTest {
    private val engine = DefaultDeterministicAnalyticsEngine()
    private val revision = LocalRevision.of(42).success()

    @Test
    fun `equal anomaly inputs and algorithm version produce equal disclosed results`() {
        val series = listOf(
            TimeSeriesPoint(LocalDate.of(2026, 5, 1), 100, "food"),
            TimeSeriesPoint(LocalDate.of(2026, 6, 1), 100, "food"),
            TimeSeriesPoint(LocalDate.of(2026, 7, 1), 100, "food"),
            TimeSeriesPoint(LocalDate.of(2026, 8, 1), 500, "food"),
        )
        val rule = AnomalyRule(
            AnomalyRuleType.HISTORICAL_MEAN_STANDARD_DEVIATION,
            BigDecimal("2.0"),
            3,
            AnalyticsAlgorithmVersion(1),
        )
        val first = engine.anomalies(series, listOf(rule), revision).success()
        val second = engine.anomalies(series.reversed(), listOf(rule), revision).success()

        second shouldBe first
        first.single().apply {
            explanationCode shouldBe "ANOMALY_HISTORICAL_MEAN_STANDARD_DEVIATION"
            baselineMinor shouldBe 100L
            seriesKey shouldBe "food"
            windowStart shouldBe LocalDate.of(2026, 5, 1)
            windowEndInclusive shouldBe LocalDate.of(2026, 7, 1)
            this.rule.version shouldBe AnalyticsAlgorithmVersion(1)
        }
    }

    @Test
    fun `month end forecast uses exact integer path and recurrence only when selected`() {
        val observations = listOf(
            TimeSeriesPoint(LocalDate.of(2026, 8, 1), 100),
            TimeSeriesPoint(LocalDate.of(2026, 8, 2), 200),
        )
        val base = ForecastRequest(
            ForecastMethod.CURRENT_DAILY_AVERAGE,
            observations,
            mapOf(LocalDate.of(2026, 8, 10) to 50L),
            LocalDate.of(2026, 8, 2),
            LocalDate.of(2026, 8, 31),
            AnalyticsAlgorithmVersion(1),
        )
        val current = engine.forecast(base, revision).success()
        current.observedMinor shouldBe 300L
        current.dailyAverageMinor shouldBe 150L
        current.projectedMinor shouldBe 4_650L
        current.recurrenceIncludedMinor shouldBe 0L

        val recurring = engine.forecast(
            base.copy(method = ForecastMethod.DAILY_AVERAGE_WITH_RECURRENCE, key = ForecastKey.MONTH_END_SPENDING),
            revision,
        ).success()
        recurring.projectedMinor shouldBe 4_700L
        recurring.recurrenceIncludedMinor shouldBe 50L
    }

    @Test
    fun `historical same month model is deterministic and never fills missing years with zero`() {
        val request = ForecastRequest(
            ForecastMethod.HISTORICAL_SAME_MONTH,
            listOf(
                TimeSeriesPoint(LocalDate.of(2024, 8, 1), 1_000),
                TimeSeriesPoint(LocalDate.of(2024, 8, 2), 2_000),
                TimeSeriesPoint(LocalDate.of(2025, 8, 1), 5_000),
            ),
            emptyMap(),
            LocalDate.of(2026, 8, 6),
            LocalDate.of(2026, 8, 31),
            AnalyticsAlgorithmVersion(1),
            ForecastKey.HISTORICAL_SAME_MONTH,
        )
        engine.forecast(request, revision).success().projectedMinor shouldBe 4_000L
        (engine.forecast(request.copy(observations = emptyList()), revision) is DomainResult.Failure) shouldBe true
    }

    @Test
    fun `derived moving average trend and forecast are exact versioned series`() {
        fun rows(mode: ComparisonMode) = listOf(100L, 200L, 400L).mapIndexed { index, value ->
            ReportRow(
                listOf(DimensionValue.Date(LocalDate.of(2026, 8, index + 1))),
                listOf(MeasureValue(Measure.EXPENSE, value, null)),
                null,
            )
        }.let { ReportDerivationPolicy.derive(mode, it)!! }

        rows(ComparisonMode.MOVING_AVERAGE).points.map { it.values.single().minorValue } shouldBe listOf(100L, 150L, 233L)
        rows(ComparisonMode.TREND).algorithmVersion shouldBe AnalyticsAlgorithmVersion(1)
        rows(ComparisonMode.FORECAST).points.size shouldBe 4
        rows(ComparisonMode.FORECAST).points.last().values.single().minorValue shouldBe 533L
    }

    @Test
    fun `visualization policy explains invalid choices and pie overflow`() {
        val spec = ReportSpec(
            listOf(Measure.EXPENSE),
            listOf(Dimension.CATEGORY),
            FilterExpression.All,
            TimeGranularity.MONTH,
            emptyList(),
            null,
        )
        CustomReportPolicy.validate(spec, ReportVisualization.LINE).issues shouldBe setOf(CustomReportIssue.VISUALIZATION_INCOMPATIBLE)
        CustomReportPolicy.validate(spec, ReportVisualization.PIE, 7).visualization.apply {
            resolved shouldBe ReportVisualization.BAR
            reason shouldBe VisualizationFallbackReason.TOO_MANY_PIE_CATEGORIES
        }
    }

    private fun <T> DomainResult<T>.success(): T = when (this) {
        is DomainResult.Success -> value
        is DomainResult.Failure -> error(error.code)
    }
}
