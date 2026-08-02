package app.ledger.finance.domain

import app.ledger.core.common.DomainResult
import app.ledger.core.money.Money

public sealed interface ReferenceDataViolation : app.ledger.core.common.DomainError {
    public data class InvalidField(public val field: String) : ReferenceDataViolation {
        override val code: String = "REFERENCE_INVALID_FIELD"
    }

    public data object CurrencyLocked : ReferenceDataViolation {
        override val code: String = "ACCOUNT_CURRENCY_LOCKED"
    }

    public data object AccountHasHistory : ReferenceDataViolation {
        override val code: String = "ACCOUNT_HAS_HISTORY"
    }

    public data object CardAccountIncompatible : ReferenceDataViolation {
        override val code: String = "CARD_ACCOUNT_INCOMPATIBLE"
    }

    public data object CategoryParentLocked : ReferenceDataViolation {
        override val code: String = "CATEGORY_PARENT_LOCKED"
    }

    public data object CategoryDirectionMismatch : ReferenceDataViolation {
        override val code: String = "CATEGORY_DIRECTION_MISMATCH"
    }

    public data object MergeCycle : ReferenceDataViolation {
        override val code: String = "REFERENCE_MERGE_CYCLE"
    }

    public data object StaleRevision : ReferenceDataViolation {
        override val code: String = "REFERENCE_STALE_REVISION"
    }
}

public enum class CategoryRemovalStrategy {
    REASSIGN,
    ARCHIVE,
    TOMBSTONE,
}

public data class AccountUsage(
    val postingCount: Long,
    val currentCardCount: Long,
    val activeAccountCount: Long,
) {
    init {
        require(postingCount >= 0L && currentCardCount >= 0L && activeAccountCount >= 0L)
    }

    public val hasHistory: Boolean = postingCount > 0L
    public val isLastActiveAccount: Boolean = activeAccountCount == 1L
}

public data class AccountLifecycleDecision(
    val canPermanentlyDelete: Boolean,
    val canArchive: Boolean,
    val requiresLastAccountWarning: Boolean,
)

public object ReferenceDataPolicies {
    public fun accountLifecycle(usage: AccountUsage): AccountLifecycleDecision = AccountLifecycleDecision(
        canPermanentlyDelete = !usage.hasHistory && usage.currentCardCount == 0L,
        canArchive = true,
        requiresLastAccountWarning = usage.isLastActiveAccount,
    )

    public fun validateCurrencyChange(
        account: UserAccount,
        proposed: app.ledger.core.money.CurrencyCode,
    ): DomainResult<Unit> = if (account.currency != proposed && account.hasFinancialPostings) {
        DomainResult.Failure(ReferenceDataViolation.CurrencyLocked)
    } else {
        DomainResult.Success(Unit)
    }

    public fun validateCard(type: CardType, accountType: UserAccountType): DomainResult<Unit> {
        val valid = type == CardType.DEBIT && accountType == UserAccountType.BANK ||
            type != CardType.DEBIT && accountType == UserAccountType.CREDIT
        return if (valid) DomainResult.Success(Unit) else DomainResult.Failure(ReferenceDataViolation.CardAccountIncompatible)
    }

    public fun validateCategoryParent(
        direction: CategoryDirection,
        parent: Category?,
        previousParentId: CategoryId?,
        editingSecondLevel: Boolean,
    ): DomainResult<Unit> = when {
        parent != null && (parent.direction != direction || parent.depth != 1 || parent.status != CategoryStatus.ACTIVE) ->
            DomainResult.Failure(ReferenceDataViolation.CategoryDirectionMismatch)
        editingSecondLevel && previousParentId != parent?.id ->
            DomainResult.Failure(ReferenceDataViolation.CategoryParentLocked)
        else -> DomainResult.Success(Unit)
    }

    public fun checkpointDifference(observed: Money, calculated: Money): DomainResult<Money> = when {
        observed.currency != calculated.currency -> DomainResult.Failure(ReferenceDataViolation.InvalidField("checkpoint.currency"))
        else -> app.ledger.core.common.CheckedArithmetic.subtract(observed.minor, calculated.minor).let { result ->
            when (result) {
                is DomainResult.Failure -> result
                is DomainResult.Success -> DomainResult.Success(Money(result.value, observed.currency))
            }
        }
    }
}
