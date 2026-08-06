@file:Suppress("LongParameterList", "MagicNumber", "ReturnCount", "TooManyFunctions")

package app.ledger.analytics.domain

import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.finance.domain.LocalRevision
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class SavedReportDefinition(
    val definition: ReportDefinition,
    val revision: ReportDefinitionRevision,
)

data class SaveReportDefinitionRequest(
    val reportId: ReportDefinitionId?,
    val name: String,
    val expectedRowVersion: Long?,
    val spec: ReportSpec,
    val visualization: ReportVisualization,
) {
    init {
        require(name.isNotBlank() && name.length <= 80)
        require((reportId == null) == (expectedRowVersion == null))
        require(expectedRowVersion == null || expectedRowVersion > 0L)
    }
}

data class SavedDashboard(
    val dashboard: Dashboard,
    val revision: DashboardRevision,
)

data class SaveDashboardRequest(
    val dashboardId: DashboardId?,
    val name: String,
    val expectedRowVersion: Long?,
    val items: List<DashboardItem>,
) {
    init {
        require(name.isNotBlank() && name.length <= 80)
        require((dashboardId == null) == (expectedRowVersion == null))
        require(expectedRowVersion == null || expectedRowVersion > 0L)
        require(items.size <= 24)
        require(items.map(DashboardItem::reportId).toSet().size == items.size)
        require(items.map(DashboardItem::sortOrder) == items.indices.toList())
    }
}

data class SavedAnomalyRule(
    val id: AnomalyRuleId,
    val rule: AnomalyRule,
    val enabled: Boolean,
    val rowVersion: Long,
) {
    init {
        require(rowVersion > 0L)
    }
}

data class SaveAnomalyRuleRequest(
    val ruleId: AnomalyRuleId?,
    val expectedRowVersion: Long?,
    val rule: AnomalyRule,
    val enabled: Boolean,
) {
    init {
        require((ruleId == null) == (expectedRowVersion == null))
        require(expectedRowVersion == null || expectedRowVersion > 0L)
    }
}

enum class CustomReportIssue {
    SORT_MEASURE_NOT_SELECTED,
    SORT_DIMENSION_NOT_SELECTED,
    VISUALIZATION_INCOMPATIBLE,
}

data class CustomReportValidation(
    val issues: Set<CustomReportIssue>,
    val visualization: VisualizationResolution,
) {
    val valid: Boolean get() = issues.isEmpty()
}

object CustomReportPolicy {
    fun validate(
        spec: ReportSpec,
        requestedVisualization: ReportVisualization,
        categoryCount: Int = 0,
    ): CustomReportValidation {
        val issues = buildSet {
            spec.sorting.forEach { sort ->
                when (sort) {
                    is ReportSort.ByMeasure -> if (sort.measure !in spec.measures) add(CustomReportIssue.SORT_MEASURE_NOT_SELECTED)
                    is ReportSort.ByDimension -> if (sort.dimension !in spec.dimensions) add(CustomReportIssue.SORT_DIMENSION_NOT_SELECTED)
                }
            }
        }.toMutableSet()
        val resolution = ReportVisualizationPolicy.resolve(requestedVisualization, categoryCount, spec.dimensions, spec.measures)
        if (resolution.reason == VisualizationFallbackReason.INCOMPATIBLE_WITH_DIMENSIONS) {
            issues += CustomReportIssue.VISUALIZATION_INCOMPATIBLE
        }
        return CustomReportValidation(issues, resolution)
    }
}

/** Pure deterministic implementation. It receives immutable series and never performs I/O. */
class DefaultDeterministicAnalyticsEngine : DeterministicAnalyticsEngine {
    override fun anomalies(
        series: List<TimeSeriesPoint>,
        rules: List<AnomalyRule>,
        asOfLocalRevision: LocalRevision,
    ): DomainResult<List<AnomalyFinding>> = try {
        val findings = buildList {
            series.groupBy(TimeSeriesPoint::seriesKey).toSortedMap().forEach { (seriesKey, points) ->
                val ordered = points.sortedWith(compareBy(TimeSeriesPoint::date, TimeSeriesPoint::amountMinor, TimeSeriesPoint::occurrenceCount))
                rules.sortedWith(compareBy({ it.type.ordinal }, { it.version.value }, { it.lookbackPeriods }, { it.threshold })).forEach { rule ->
                    addAll(find(ordered, seriesKey, rule))
                }
            }
        }.sortedWith(compareBy(AnomalyFinding::date, AnomalyFinding::seriesKey, { it.rule.type.ordinal }))
        DomainResult.Success(findings)
    } catch (_: ArithmeticException) {
        DomainResult.Failure(AnalyticsError.InvalidReportSpec)
    }

    override fun forecast(
        request: ForecastRequest,
        asOfLocalRevision: LocalRevision,
    ): DomainResult<ForecastResult> = try {
        val result = when (request.method) {
            ForecastMethod.CURRENT_DAILY_AVERAGE -> currentAverage(request, asOfLocalRevision, includeRecurrence = false)
            ForecastMethod.DAILY_AVERAGE_WITH_RECURRENCE -> currentAverage(request, asOfLocalRevision, includeRecurrence = true)
            ForecastMethod.HISTORICAL_SAME_MONTH -> historicalSameMonth(request, asOfLocalRevision)
        }
        DomainResult.Success(result)
    } catch (_: ArithmeticException) {
        DomainResult.Failure(AnalyticsError.InvalidReportSpec)
    } catch (_: IllegalArgumentException) {
        DomainResult.Failure(AnalyticsError.InvalidReportSpec)
    }

    private fun find(points: List<TimeSeriesPoint>, key: String, rule: AnomalyRule): List<AnomalyFinding> = when (rule.type) {
        AnomalyRuleType.HISTORICAL_MEAN_STANDARD_DEVIATION -> standardDeviation(points, key, rule)
        AnomalyRuleType.RECENT_MONTH_GROWTH_THRESHOLD -> growth(points, key, rule, useCount = false)
        AnomalyRuleType.LARGE_SINGLE_TRANSACTION -> largeTransactions(points, key, rule)
        AnomalyRuleType.MERCHANT_FREQUENCY,
        AnomalyRuleType.CATEGORY_FREQUENCY,
        -> growth(points, key, rule, useCount = true)
    }

    private fun standardDeviation(points: List<TimeSeriesPoint>, key: String, rule: AnomalyRule): List<AnomalyFinding> {
        if (points.size < 3) return emptyList()
        val observed = points.last()
        val baseline = points.dropLast(1).takeLast(rule.lookbackPeriods)
        if (baseline.size < 2) return emptyList()
        val mean = average(baseline.map { BigInteger.valueOf(it.amountMinor) })
        val variance = baseline.map { BigDecimal.valueOf(it.amountMinor).subtract(mean).pow(2, MC) }
            .reduce(BigDecimal::add).divide(BigDecimal.valueOf(baseline.size.toLong()), MC)
        val deviation = variance.sqrt(MC)
        val difference = BigDecimal.valueOf(observed.amountMinor).subtract(mean).abs()
        val score = if (deviation.signum() == 0) {
            if (difference.signum() == 0) BigDecimal.ZERO else MAX_SCORE
        } else {
            difference.divide(deviation, SCORE_SCALE, RoundingMode.HALF_EVEN)
        }
        return if (score >= rule.threshold) {
            listOf(finding(rule, observed, mean.setScale(0, RoundingMode.HALF_EVEN).longValueExact(), score, key, baseline))
        } else {
            emptyList()
        }
    }

    private fun growth(points: List<TimeSeriesPoint>, key: String, rule: AnomalyRule, useCount: Boolean): List<AnomalyFinding> {
        if (points.size < 2) return emptyList()
        val observed = points.last()
        val baseline = points.dropLast(1).takeLast(rule.lookbackPeriods)
        if (baseline.isEmpty()) return emptyList()
        val inputs = if (useCount) baseline.map { BigInteger.valueOf(it.occurrenceCount) } else baseline.map { BigInteger.valueOf(it.amountMinor) }
        val mean = average(inputs)
        val observedValue = if (useCount) BigDecimal.valueOf(observed.occurrenceCount) else BigDecimal.valueOf(observed.amountMinor)
        val score = if (mean.signum() == 0) {
            if (observedValue.signum() == 0) BigDecimal.ZERO else MAX_SCORE
        } else {
            observedValue.subtract(mean).divide(mean.abs(), SCORE_SCALE, RoundingMode.HALF_EVEN)
        }
        val baselineMinor = mean.setScale(0, RoundingMode.HALF_EVEN).longValueExact()
        return if (score >= rule.threshold) listOf(finding(rule, observed, baselineMinor, score, key, baseline)) else emptyList()
    }

    private fun largeTransactions(points: List<TimeSeriesPoint>, key: String, rule: AnomalyRule): List<AnomalyFinding> = points.filter { point ->
        BigDecimal(BigInteger.valueOf(point.amountMinor).abs()) >= rule.threshold
    }.map { point ->
        AnomalyFinding(
            rule,
            point.date,
            point.amountMinor,
            rule.threshold.setScale(0, RoundingMode.HALF_EVEN).longValueExact(),
            BigDecimal(BigInteger.valueOf(point.amountMinor).abs()).divide(rule.threshold.max(BigDecimal.ONE), SCORE_SCALE, RoundingMode.HALF_EVEN),
            "ANOMALY_LARGE_TRANSACTION",
            key,
            point.occurrenceCount,
            point.date,
            point.date,
        )
    }

    private fun finding(
        rule: AnomalyRule,
        observed: TimeSeriesPoint,
        baseline: Long,
        score: BigDecimal,
        key: String,
        window: List<TimeSeriesPoint>,
    ): AnomalyFinding = AnomalyFinding(
        rule,
        observed.date,
        observed.amountMinor,
        baseline,
        score,
        "ANOMALY_${rule.type.name}",
        key,
        observed.occurrenceCount,
        window.first().date,
        window.last().date,
    )

    private fun currentAverage(request: ForecastRequest, revision: LocalRevision, includeRecurrence: Boolean): ForecastResult {
        val start = request.today.withDayOfMonth(1)
        val observations = request.observations.filter { it.date in start..request.today }
        require(observations.isNotEmpty())
        val observed = exactLong(observations.map { BigInteger.valueOf(it.amountMinor) })
        val elapsedDays = Math.addExact(ChronoUnit.DAYS.between(start, request.today), 1L)
        val average = BigInteger.valueOf(observed).divide(BigInteger.valueOf(elapsedDays)).longValueExact()
        val futureDays = ChronoUnit.DAYS.between(request.today, request.throughDate)
        val recurring = if (includeRecurrence) {
            exactLong(request.futureRecurrenceMinorByDate.filterKeys { it > request.today && it <= request.throughDate }.values.map(BigInteger::valueOf))
        } else {
            0L
        }
        val futureAverage = Math.multiplyExact(average, futureDays)
        val projected = if (request.key == ForecastKey.MONTH_END_BALANCE_WITH_RECURRENCE) {
            Math.subtractExact(Math.subtractExact(requireNotNull(request.startingBalanceMinor), futureAverage), recurring)
        } else {
            Math.addExact(Math.addExact(observed, futureAverage), recurring)
        }
        return ForecastResult(
            request.method,
            projected,
            request.throughDate,
            request.version,
            if (includeRecurrence) "FORECAST_DAILY_AVERAGE_WITH_RECURRENCE" else "FORECAST_DAILY_AVERAGE",
            revision,
            observed,
            average,
            recurring,
            start,
            request.today,
        )
    }

    private fun historicalSameMonth(request: ForecastRequest, revision: LocalRevision): ForecastResult {
        val totals = request.observations.filter { it.date.year < request.today.year && it.date.month == request.throughDate.month }
            .groupBy { it.date.year }
            .toSortedMap()
            .values
            .map { year -> exactLong(year.map { BigInteger.valueOf(it.amountMinor) }) }
        require(totals.isNotEmpty())
        val projected = BigInteger.valueOf(exactLong(totals.map(BigInteger::valueOf)))
            .divide(BigInteger.valueOf(totals.size.toLong())).longValueExact()
        return ForecastResult(
            request.method,
            projected,
            request.throughDate,
            request.version,
            "FORECAST_HISTORICAL_SAME_MONTH",
            revision,
            observedMinor = 0L,
            dailyAverageMinor = 0L,
            recurrenceIncludedMinor = 0L,
            windowStart = request.observations.minOf(TimeSeriesPoint::date),
            windowEndInclusive = request.observations.maxOf(TimeSeriesPoint::date),
        )
    }

    private fun average(values: List<BigInteger>): BigDecimal = BigDecimal(values.reduce(BigInteger::add)).divide(BigDecimal.valueOf(values.size.toLong()), MC)

    private fun exactLong(values: List<BigInteger>): Long = if (values.isEmpty()) 0L else values.reduce(BigInteger::add).longValueExact()

    private companion object {
        val MC: MathContext = MathContext(24, RoundingMode.HALF_EVEN)
        val MAX_SCORE: BigDecimal = BigDecimal("999999999")
        const val SCORE_SCALE: Int = 8
    }
}

object ReportDerivationPolicy {
    val VERSION: AnalyticsAlgorithmVersion = AnalyticsAlgorithmVersion(1)

    fun derive(mode: ComparisonMode?, rows: List<ReportRow>): ReportDerivedSeries? {
        if (mode !in setOf(ComparisonMode.MOVING_AVERAGE, ComparisonMode.TREND, ComparisonMode.FORECAST)) return null
        val dated = rows.mapNotNull { row ->
            val date = (row.dimensionValues.firstOrNull() as? DimensionValue.Date)?.value ?: return@mapNotNull null
            date to row.measureValues
        }.sortedBy(Pair<LocalDate, List<MeasureValue>>::first)
        if (dated.size < 2) return null
        return when (requireNotNull(mode)) {
            ComparisonMode.MOVING_AVERAGE -> movingAverage(dated)
            ComparisonMode.TREND -> trend(dated, forecast = false)
            ComparisonMode.FORECAST -> trend(dated, forecast = true)
            else -> null
        }
    }

    private fun movingAverage(rows: List<Pair<LocalDate, List<MeasureValue>>>): ReportDerivedSeries {
        val points = rows.mapIndexed { index, row ->
            val window = rows.subList((index - MOVING_WINDOW + 1).coerceAtLeast(0), index + 1)
            DerivedSeriesPoint(row.first, row.second.map { value -> averageValue(value.measure, value.currency, window) })
        }
        return ReportDerivedSeries(ComparisonMode.MOVING_AVERAGE, MOVING_WINDOW, VERSION, points, "REPORT_MOVING_AVERAGE_V1")
    }

    private fun trend(rows: List<Pair<LocalDate, List<MeasureValue>>>, forecast: Boolean): ReportDerivedSeries {
        val measures = rows.first().second.map(MeasureValue::measure)
        val valuesByMeasure = measures.associateWith { measure ->
            rows.map { (_, values) -> values.single { it.measure == measure } }
        }
        val pointCount = if (forecast) rows.size + 1 else rows.size
        val lastDateStep = ChronoUnit.DAYS.between(rows[rows.lastIndex - 1].first, rows.last().first).coerceAtLeast(1L)
        val points = (0 until pointCount).map { index ->
            val date = if (index < rows.size) rows[index].first else rows.last().first.plusDays(lastDateStep)
            val values = measures.map { measure -> linearValue(measure, valuesByMeasure.getValue(measure), index) }
            DerivedSeriesPoint(date, values)
        }
        return ReportDerivedSeries(
            if (forecast) ComparisonMode.FORECAST else ComparisonMode.TREND,
            rows.size,
            VERSION,
            points,
            if (forecast) "REPORT_LINEAR_FORECAST_V1" else "REPORT_LINEAR_TREND_V1",
        )
    }

    private fun averageValue(
        measure: Measure,
        currency: app.ledger.core.money.CurrencyCode?,
        window: List<Pair<LocalDate, List<MeasureValue>>>,
    ): MeasureValue {
        val samples = window.map { (_, values) -> values.single { it.measure == measure } }
        return if (samples.first().minorValue != null) {
            val total = samples.map { BigInteger.valueOf(requireNotNull(it.minorValue)) }.reduce(BigInteger::add)
            MeasureValue(measure, total.divide(BigInteger.valueOf(samples.size.toLong())).longValueExact(), null, currency)
        } else {
            val total = samples.map { requireNotNull(it.decimalValue) }.reduce(BigDecimal::add)
            MeasureValue(measure, null, total.divide(BigDecimal.valueOf(samples.size.toLong()), 8, RoundingMode.HALF_EVEN))
        }
    }

    private fun linearValue(measure: Measure, samples: List<MeasureValue>, x: Int): MeasureValue {
        val n = BigDecimal.valueOf(samples.size.toLong())
        val xs = samples.indices.map { BigDecimal.valueOf(it.toLong()) }
        val ys = samples.map { it.minorValue?.let(BigDecimal::valueOf) ?: requireNotNull(it.decimalValue) }
        val sumX = xs.reduce(BigDecimal::add)
        val sumY = ys.reduce(BigDecimal::add)
        val sumXy = xs.zip(ys).map { (left, right) -> left.multiply(right) }.reduce(BigDecimal::add)
        val sumX2 = xs.map { it.multiply(it) }.reduce(BigDecimal::add)
        val denominator = n.multiply(sumX2).subtract(sumX.multiply(sumX))
        val slope = if (denominator.signum() == 0) BigDecimal.ZERO else n.multiply(sumXy).subtract(sumX.multiply(sumY)).divide(denominator, 16, RoundingMode.HALF_EVEN)
        val intercept = sumY.subtract(slope.multiply(sumX)).divide(n, 16, RoundingMode.HALF_EVEN)
        val predicted = intercept.add(slope.multiply(BigDecimal.valueOf(x.toLong())))
        return if (samples.first().minorValue != null) {
            MeasureValue(measure, predicted.setScale(0, RoundingMode.HALF_EVEN).longValueExact(), null, samples.first().currency)
        } else {
            MeasureValue(measure, null, predicted.setScale(8, RoundingMode.HALF_EVEN))
        }
    }

    private const val MOVING_WINDOW: Int = 3
}

fun StableId.asReportDefinitionId(): ReportDefinitionId = ReportDefinitionId(this)
