package app.ledger.finance.data

import app.ledger.finance.domain.StatisticalNature
import app.ledger.finance.domain.TransactionFilter
import app.ledger.finance.domain.TransactionKind
import app.ledger.finance.domain.TransactionSource
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class TransactionSqlCompilerTest {
    @Test
    fun `empty filter remains a bounded keyset query without offset`() {
        val compiled = TransactionSqlCompiler.compile(emptyFilter(), null, 51)

        compiled.sql.shouldContain("ORDER BY ctp.occurred_at DESC, ctp.transaction_id DESC LIMIT ?")
        compiled.sql.contains("OFFSET", ignoreCase = true) shouldBe false
        compiled.arguments shouldBe listOf(51)
    }

    @Test
    fun `search text is bound and never interpolated into SQL`() {
        val hostile = "shop' OR 1=1 --"
        val compiled = TransactionSqlCompiler.compile(emptyFilter().copy(searchText = hostile), null, 20)

        compiled.sql.contains(hostile) shouldBe false
        compiled.sql.shouldContain("transaction_fts MATCH ?")
        compiled.arguments.first() shouldBe "\"$hostile\""
    }

    @Test
    fun `complete filters use OR within a dimension and AND across dimensions`() {
        val compiled = TransactionSqlCompiler.compile(
            TransactionFilter(
                occurredFrom = java.time.Instant.EPOCH,
                createdFrom = java.time.Instant.EPOCH,
                modifiedThrough = java.time.Instant.ofEpochSecond(10),
                kinds = setOf(TransactionKind.EXPENSE, TransactionKind.INCOME),
                statisticalNatures = setOf(StatisticalNature.CONSUMPTION_EXPENSE, StatisticalNature.NON_CONSUMPTION_EXPENSE),
                hasAttachment = true,
                includedInBudget = true,
                generatedByRecurrence = true,
                sources = setOf(TransactionSource.MANUAL, TransactionSource.CSV_IMPORT),
            ),
            null,
            41,
        )

        compiled.sql.shouldContain("ctp.kind IN (?,?)")
        compiled.sql.shouldContain("tr.statistical_nature_snapshot IN (?,?)")
        compiled.sql.shouldContain("created.created_at >= ?")
        compiled.sql.shouldContain("modified.created_at <= ?")
        compiled.sql.shouldContain("EXISTS (SELECT 1 FROM budget_effect")
        compiled.sql.shouldContain("ctp.source_type IN (?,?)")
        compiled.sql.contains(" OFFSET ", ignoreCase = true) shouldBe false
    }

    private fun emptyFilter(): TransactionFilter = TransactionFilter()
}
