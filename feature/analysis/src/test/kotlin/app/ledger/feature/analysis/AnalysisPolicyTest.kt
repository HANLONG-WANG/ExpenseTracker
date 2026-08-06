package app.ledger.feature.analysis

import app.ledger.analytics.domain.ComparisonMode
import app.ledger.analytics.domain.Dimension
import app.ledger.analytics.domain.FilterExpression
import app.ledger.analytics.domain.Measure
import app.ledger.analytics.domain.ReportSpec
import app.ledger.analytics.domain.TimeGranularity
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

class AnalysisPolicyTest {
    @Test
    fun `month navigation preserves whole calendar month`() {
        val current = AnalysisPolicy.initialPeriod(LocalDate.of(2024, 2, 20))
        current.start shouldBe LocalDate.of(2024, 2, 1)
        current.endInclusive shouldBe LocalDate.of(2024, 2, 29)
        AnalysisPolicy.previousPeriod(current).endInclusive shouldBe LocalDate.of(2024, 1, 31)
        AnalysisPolicy.nextPeriod(current).endInclusive shouldBe LocalDate.of(2024, 3, 31)
    }

    @Test
    fun `P25 comparison selector exposes only no comparison month over month and year over year`() {
        val start = spec(null)
        val previous = AnalysisPolicy.cycleComparison(start)
        previous.comparison shouldBe ComparisonMode.PREVIOUS_PERIOD
        val year = AnalysisPolicy.cycleComparison(previous)
        year.comparison shouldBe ComparisonMode.YEAR_OVER_YEAR
        AnalysisPolicy.cycleComparison(year).comparison shouldBe null
    }

    @Test
    fun `all YAML presentation states are closed and distinguish stale from query error`() {
        AnalysisPresentation.entries.toSet() shouldBe setOf(
            AnalysisPresentation.CONTENT,
            AnalysisPresentation.NO_DATA,
            AnalysisPresentation.CALCULATING,
            AnalysisPresentation.ERROR,
            AnalysisPresentation.LOADING,
            AnalysisPresentation.EMPTY,
            AnalysisPresentation.QUERY_ERROR,
            AnalysisPresentation.STALE_REBUILD_REQUIRED,
            AnalysisPresentation.EDITING,
            AnalysisPresentation.INVALID,
            AnalysisPresentation.EXPIRED_QUERY,
            AnalysisPresentation.NOT_RUN,
            AnalysisPresentation.RUNNING,
            AnalysisPresentation.PASSED,
            AnalysisPresentation.WARNINGS,
            AnalysisPresentation.FAILED,
        )
    }

    private fun spec(comparison: ComparisonMode?): ReportSpec = ReportSpec(
        listOf(Measure.EXPENSE),
        listOf(Dimension.DATE),
        FilterExpression.All,
        TimeGranularity.MONTH,
        emptyList(),
        comparison,
    )
}
