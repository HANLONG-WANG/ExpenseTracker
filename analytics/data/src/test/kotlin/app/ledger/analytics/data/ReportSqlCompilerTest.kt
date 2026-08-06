package app.ledger.analytics.data

import app.ledger.analytics.domain.ComparisonMode
import app.ledger.analytics.domain.Dimension
import app.ledger.analytics.domain.FilterExpression
import app.ledger.analytics.domain.FilterField
import app.ledger.analytics.domain.FilterOperator
import app.ledger.analytics.domain.FilterValue
import app.ledger.analytics.domain.FixedReportCatalog
import app.ledger.analytics.domain.Measure
import app.ledger.analytics.domain.QuerySource
import app.ledger.analytics.domain.ReportSpec
import app.ledger.analytics.domain.TimeGranularity
import app.ledger.core.common.StableId
import app.ledger.finance.domain.CategoryId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import java.util.UUID

class ReportSqlCompilerTest {
    @Test
    fun `all fixed reports compile only from closed sources without OFFSET`() {
        FixedReportCatalog.definitions.forEach { definition ->
            val source = ReportQueryPlanner.select(definition.spec)
            val query = ReportSqlCompiler.compile(source, definition.spec, START, END)
            query.sql.shouldContain("LIMIT 500")
            if (source !in setOf(QuerySource.DAILY_ROLLUP, QuerySource.MONTHLY_ROLLUP)) {
                query.sql.shouldContain("measure IN (")
            }
            query.sql.uppercase().shouldNotContain("OFFSET")
            query.dimensions shouldBe definition.spec.dimensions
            query.measures.map { it.measure } shouldBe definition.spec.measures
        }
    }

    @Test
    fun `user values are bound and never interpolated into SQL`() {
        val category = CategoryId(StableId.fromUuid(UUID(0L, 42L)))
        val spec = spec(
            dimensions = listOf(Dimension.CATEGORY),
            filters = FilterExpression.Predicate(
                FilterField.CATEGORY,
                FilterOperator.IN,
                FilterValue.Categories(setOf(category)),
            ),
        )
        val query = ReportSqlCompiler.compile(QuerySource.ECONOMIC_EFFECTS, spec, START, END)
        query.sql.shouldContain("category_uid IN (?)")
        query.arguments.filterIsInstance<ByteArray>().any { it.contentEquals(category.value.bytes) } shouldBe true
        query.sql.shouldNotContain(category.value.toString())
    }

    @Test
    fun `malicious or unknown closed keys are rejected instead of becoming SQL`() {
        val spec = spec(
            filters = FilterExpression.Predicate(
                FilterField.TRANSACTION_SOURCE,
                FilterOperator.EQUALS,
                FilterValue.ClosedKeys(setOf("CURRENT'); DROP TABLE book;--")),
            ),
        )
        assertThrows<IllegalArgumentException> {
            ReportSqlCompiler.compile(QuerySource.ECONOMIC_EFFECTS, spec, START, END)
        }
    }

    @Test
    fun `planner chooses monthly daily effects and posting plans deterministically`() {
        ReportQueryPlanner.select(spec(Measure.INCOME, listOf(Dimension.DATE), TimeGranularity.MONTH)) shouldBe QuerySource.MONTHLY_ROLLUP
        ReportQueryPlanner.select(spec(Measure.EXPENSE, listOf(Dimension.DATE), TimeGranularity.DAY)) shouldBe QuerySource.DAILY_ROLLUP
        ReportQueryPlanner.select(spec(Measure.CONSUMPTION, listOf(Dimension.CATEGORY))) shouldBe QuerySource.ECONOMIC_EFFECTS
        ReportQueryPlanner.select(spec(Measure.ACCOUNT_BALANCE, listOf(Dimension.ACCOUNT))) shouldBe QuerySource.JOURNAL_POSTINGS
    }

    @Test
    fun `native subledger measures retain currency while base measures never mix units`() {
        val installment = FixedReportCatalog.definitions.single {
            it.report == app.ledger.analytics.domain.FixedReport.INSTALLMENT_BALANCE_FEES
        }
        installment.spec.dimensions shouldBe listOf(Dimension.CURRENCY)
        val installmentQuery = ReportSqlCompiler.compile(
            ReportQueryPlanner.select(installment.spec),
            installment.spec,
            START,
            END,
        )
        installmentQuery.measures.all { it.currencyColumn != null } shouldBe true
        installmentQuery.sql.shouldContain("JOIN installment_plan ip")

        val fx = FixedReportCatalog.definitions.single {
            it.report == app.ledger.analytics.domain.FixedReport.FX_REVALUATION
        }
        val fxQuery = ReportSqlCompiler.compile(ReportQueryPlanner.select(fx.spec), fx.spec, START, END)
        fxQuery.measures.single().currencyColumn shouldBe null
        fxQuery.sql.shouldContain("av.current_base_value_minor-COALESCE")
        fxQuery.sql.shouldContain("p.base_amount_minor")
        fxQuery.sql.shouldNotContain("av.current_base_value_minor-av.balance_minor")
    }

    private fun spec(
        measure: Measure = Measure.EXPENSE,
        dimensions: List<Dimension> = emptyList(),
        granularity: TimeGranularity = TimeGranularity.MONTH,
        filters: FilterExpression = FilterExpression.All,
    ): ReportSpec = ReportSpec(
        listOf(measure),
        dimensions,
        filters,
        granularity,
        emptyList(),
        ComparisonMode.PREVIOUS_PERIOD,
    )

    private companion object {
        val START: LocalDate = LocalDate.of(2026, 1, 1)
        val END: LocalDate = LocalDate.of(2026, 12, 31)
    }
}
