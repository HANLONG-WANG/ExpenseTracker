package app.ledger.app

import app.ledger.core.common.StableId
import app.ledger.feature.vault.VaultCardSummary
import app.ledger.finance.application.CardReferenceView

/** Keeps the Vault's private cache aligned with the authoritative reference-data snapshot. */
internal object VaultCardSnapshotPolicy {
    fun synchronize(
        sourceCards: List<CardReferenceView>,
        knownSecretCardIds: Set<StableId>,
    ): Map<StableId, VaultCardSummary> = sourceCards.associateTo(linkedMapOf()) { card ->
        card.id to VaultCardSummary(
            cardId = card.id,
            displayName = card.displayName,
            lastFour = card.lastFour,
            hasSecret = card.id in knownSecretCardIds,
        )
    }

    fun mayApplySecretLookup(
        requestedBookId: StableId,
        activeBookId: StableId?,
        requestedGeneration: Long,
        activeGeneration: Long,
    ): Boolean = requestedBookId == activeBookId && requestedGeneration == activeGeneration
}

/** Distinguishes a system credential UI covering the Activity from a real app-background event. */
internal object VaultAuthenticationLifecyclePolicy {
    fun preservePendingOnActivityStop(
        systemPromptActive: Boolean,
        hasPendingAuthentication: Boolean,
    ): Boolean = systemPromptActive && hasPendingAuthentication
}
