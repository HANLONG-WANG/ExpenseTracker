package app.ledger.feature.journal

import app.ledger.core.common.StableId
import app.ledger.finance.application.JournalBulkEditableField
import app.ledger.finance.application.JournalSelectionMode
import app.ledger.finance.application.forbiddenJournalBulkFields
import app.ledger.finance.domain.TransactionFilter
import app.ledger.finance.domain.TransactionKind
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

class JournalSelectionPolicyTest {
    @Test
    fun `all matching selection for five hundred thousand rows stores only exceptions`() {
        val filter = TransactionFilter(kinds = setOf(TransactionKind.EXPENSE))
        var selection = JournalSelectionPolicy.selectAllMatching(filter)

        repeat(12) { selection = JournalSelectionPolicy.toggle(selection, id(it.toLong())) }

        selection.mode shouldBe JournalSelectionMode.ALL_MATCHING
        selection.includedIds.size shouldBe 0
        selection.excludedIds.size shouldBe 12
        (selection.excludedIds.size < 500_000) shouldBe true
        selection.queryChanged(JournalSelectionPolicy.fingerprint(filter)) shouldBe false
        selection.queryChanged(JournalSelectionPolicy.fingerprint(filter.copy(kinds = setOf(TransactionKind.INCOME)))) shouldBe true
    }

    @Test
    fun `bulk edit surface exposes only the frozen allowed fields`() {
        JournalBulkEditableField.entries.toSet() shouldContainExactlyInAnyOrder setOf(
            JournalBulkEditableField.CATEGORY,
            JournalBulkEditableField.ACCOUNT_AND_CARD,
            JournalBulkEditableField.MERCHANT,
            JournalBulkEditableField.PROJECT,
            JournalBulkEditableField.OCCURRED_TIME,
            JournalBulkEditableField.NOTE,
            JournalBulkEditableField.BUDGET_ATTRIBUTE,
            JournalBulkEditableField.STATISTICAL_NATURE,
        )
        forbiddenJournalBulkFields shouldContainExactlyInAnyOrder setOf("amount", "direction", "refundRelation", "settlementShare")
    }

    private fun id(value: Long): StableId = StableId.fromUuid(UUID(0L, value + 1L))
}
