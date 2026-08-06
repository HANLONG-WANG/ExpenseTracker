package app.ledger.analytics.domain

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.LocalDate

class P25FixedReportContractTest {
    @Test
    fun `catalog contains every frozen report exactly once in five user groups`() {
        FixedReportCatalog.definitions.map { it.report }.shouldContainExactly(FixedReport.entries)
        FixedReportCatalog.definitions.map { it.key }.distinct().size shouldBe 20
        FixedReportCatalog.definitions.map { it.group }.toSet() shouldBe FixedReportGroup.entries.toSet()
        FixedReportCatalog.definitions.forEach { definition ->
            definition.compatibleVisualizations.contains(definition.defaultVisualization) shouldBe true
        }
    }

    @Test
    fun `savings rate is exact income minus all expense divided by income`() = runTest {
        checkAll(Arb.long(1L..9_000_000_000L), Arb.long(0L..9_000_000_000L)) { income, expense ->
            val expected = BigDecimal.valueOf(Math.subtractExact(income, expense))
                .divide(BigDecimal.valueOf(income), 8, java.math.RoundingMode.HALF_EVEN)
            SavingsRatePolicy.calculate(income, expense) shouldBe expected
        }
        SavingsRatePolicy.calculate(0L, 10L) shouldBe null
    }

    @Test
    fun `pie is bounded and incompatible line or map resolves to a reasoned table`() {
        ReportVisualizationPolicy.resolve(ReportVisualization.PIE, 6, listOf(Dimension.CATEGORY)).resolved shouldBe ReportVisualization.PIE
        val overflow = ReportVisualizationPolicy.resolve(ReportVisualization.PIE, 7, listOf(Dimension.CATEGORY))
        overflow.resolved shouldBe ReportVisualization.BAR
        overflow.reason shouldBe VisualizationFallbackReason.TOO_MANY_PIE_CATEGORIES

        ReportVisualizationPolicy.resolve(ReportVisualization.LINE, 0, listOf(Dimension.CATEGORY)).reason shouldBe
            VisualizationFallbackReason.INCOMPATIBLE_WITH_DIMENSIONS
        ReportVisualizationPolicy.resolve(ReportVisualization.MAP, 0, listOf(Dimension.DATE)).resolved shouldBe ReportVisualization.TABLE
    }

    @Test
    fun `AST rejects unbounded measure dimension sort and filter trees`() {
        val base = ReportSpec(
            listOf(Measure.INCOME),
            listOf(Dimension.DATE),
            FilterExpression.All,
            TimeGranularity.MONTH,
            emptyList(),
            ComparisonMode.PREVIOUS_PERIOD,
        )
        base.measures shouldBe listOf(Measure.INCOME)
        assertThrows<IllegalArgumentException> {
            base.copy(measures = Measure.entries.take(9))
        }
        assertThrows<IllegalArgumentException> {
            base.copy(dimensions = Dimension.entries.take(4))
        }
        val many = (1..65).map { index ->
            FilterExpression.Predicate(
                FilterField.OCCURRED_DATE,
                FilterOperator.BETWEEN,
                FilterValue.DateRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, index.coerceAtMost(28))),
            )
        }
        assertThrows<IllegalArgumentException> { base.copy(filters = FilterExpression.And(many)) }
    }
}
