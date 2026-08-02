package app.ledger.finance.data

import app.ledger.finance.domain.TransactionFilter
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

    private fun emptyFilter(): TransactionFilter = TransactionFilter(
        occurredFrom = null,
        occurredThrough = null,
        kinds = emptySet(),
        accountIds = emptySet(),
        cardIds = emptySet(),
        categoryIds = emptySet(),
        merchantIds = emptySet(),
        projectIds = emptySet(),
        settlementActivityIds = emptySet(),
        participantIds = emptySet(),
        currencies = emptySet(),
        amountRange = null,
        geoRadius = null,
        hasAttachment = null,
        isRefund = null,
        hasInstallment = null,
        sources = emptySet(),
        lifecycleStates = emptySet(),
        searchText = null,
    )
}
