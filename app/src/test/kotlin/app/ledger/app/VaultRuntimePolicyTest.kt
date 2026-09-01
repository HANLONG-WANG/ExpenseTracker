package app.ledger.app

import app.ledger.core.common.StableId
import app.ledger.finance.application.CardReferenceView
import app.ledger.finance.domain.CardType
import app.ledger.finance.domain.EntityStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class VaultRuntimePolicyTest {
    @Test
    fun newlyAddedPhysicalCardAppearsInTheNextReferenceSnapshot() {
        val existing = card(1, "Existing card")
        val added = card(2, "New card")

        val initial = VaultCardSnapshotPolicy.synchronize(listOf(existing), setOf(existing.id))
        val refreshed = VaultCardSnapshotPolicy.synchronize(
            listOf(existing, added),
            initial.values.filter { it.hasSecret }.mapTo(linkedSetOf()) { it.cardId },
        )

        assertEquals(listOf(existing.id, added.id), refreshed.keys.toList())
        assertTrue(refreshed.getValue(existing.id).hasSecret)
        assertFalse(refreshed.getValue(added.id).hasSecret)
        assertEquals("New card", refreshed.getValue(added.id).displayName)
    }

    @Test
    fun onlyAnActiveSystemPromptPreservesPendingAuthenticationAcrossActivityStop() {
        assertTrue(VaultAuthenticationLifecyclePolicy.preservePendingOnActivityStop(true, true))
        assertFalse(VaultAuthenticationLifecyclePolicy.preservePendingOnActivityStop(false, true))
        assertFalse(VaultAuthenticationLifecyclePolicy.preservePendingOnActivityStop(true, false))
    }

    @Test
    fun staleSecretLookupCannotOverwriteANewerCardSnapshot() {
        val book = id(200)

        assertTrue(VaultCardSnapshotPolicy.mayApplySecretLookup(book, book, 2, 2))
        assertFalse(VaultCardSnapshotPolicy.mayApplySecretLookup(book, book, 1, 2))
        assertFalse(VaultCardSnapshotPolicy.mayApplySecretLookup(book, id(201), 2, 2))
    }

    private fun card(seed: Long, name: String): CardReferenceView = CardReferenceView(
        id = id(seed),
        accountId = id(100),
        type = CardType.DEBIT,
        displayName = name,
        lastFour = "%04d".format(seed),
        status = EntityStatus.ACTIVE,
        replacementOfId = null,
        iconKey = "card",
        colorArgb = 0,
        sortOrder = seed.toInt(),
        rowVersion = 1,
        historicalTransactionCount = 0,
    )

    private fun id(seed: Long): StableId = StableId.fromUuid(UUID(0, seed))
}
