package app.ledger.analytics.domain

import app.ledger.core.common.StableId
import app.ledger.finance.domain.LocalRevision
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

class ReportContractTest {
    @Test
    fun `report language is closed typed AST with no SQL payload`() {
        FilterExpression::class.java.isSealed.shouldBeTrue()
        FilterValue::class.java.isSealed.shouldBeTrue()
        ReportSort::class.java.isSealed.shouldBeTrue()
        ReportSpec::class.java.declaredFields.none { field ->
            field.name.contains("sql", ignoreCase = true)
        }.shouldBeTrue()

        val spec = ReportSpec(
            measures = listOf(Measure.EXPENSE, Measure.BUDGET_USAGE),
            dimensions = listOf(Dimension.DATE, Dimension.CATEGORY),
            filters = FilterExpression.Predicate(
                FilterField.OCCURRED_DATE,
                FilterOperator.BETWEEN,
                FilterValue.DateRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)),
            ),
            granularity = TimeGranularity.MONTH,
            sorting = listOf(ReportSort.ByMeasure(Measure.EXPENSE, SortDirection.DESCENDING)),
            comparison = ComparisonMode.YEAR_OVER_YEAR,
        )
        spec.measures.size shouldBe 2
    }

    @Test
    fun `all twenty frozen fixed reports are represented`() {
        FixedReport.entries.size shouldBe 20
        FixedReport.entries.toSet().size shouldBe 20
    }

    @Test
    fun `report cache is never used across data or valuation revision`() {
        val cache = VersionedReportCache(
            reportRevisionId = ReportRevisionId(StableId.fromUuid(UUID(0L, 1L))),
            rows = emptyList(),
            bookRevision = LocalRevision.of(10L).success(),
            valuationRevision = LocalRevision.of(4L).success(),
        )

        cache.isUsable(LocalRevision.of(10L).success(), LocalRevision.of(4L).success()).shouldBeTrue()
        cache.isUsable(LocalRevision.of(11L).success(), LocalRevision.of(4L).success()).shouldBeFalse()
        cache.isUsable(LocalRevision.of(10L).success(), LocalRevision.of(5L).success()).shouldBeFalse()
    }

    @Test
    fun `forecast contract requires explicit date algorithm and ledger version`() {
        val request = ForecastRequest(
            method = ForecastMethod.DAILY_AVERAGE_WITH_RECURRENCE,
            observations = listOf(TimeSeriesPoint(LocalDate.of(2026, 8, 1), 100L)),
            futureRecurrenceMinorByDate = mapOf(LocalDate.of(2026, 8, 20) to 200L),
            today = LocalDate.of(2026, 8, 2),
            throughDate = LocalDate.of(2026, 8, 31),
            version = AnalyticsAlgorithmVersion(1),
        )

        request.version.value shouldBe 1
        request.throughDate shouldBe LocalDate.of(2026, 8, 31)
    }

    private fun <T> app.ledger.core.common.DomainResult<T>.success(): T = when (this) {
        is app.ledger.core.common.DomainResult.Success -> value
        is app.ledger.core.common.DomainResult.Failure -> error(error.code)
    }
}
