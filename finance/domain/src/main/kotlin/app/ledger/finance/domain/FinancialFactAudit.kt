package app.ledger.finance.domain

import app.ledger.core.common.CheckedArithmetic
import app.ledger.core.common.DomainResult
import java.math.BigInteger

data class PostingNet(
    val ledgerAccountId: LedgerAccountId,
    val accountCurrency: app.ledger.core.money.CurrencyCode,
    val accountDebitMinusCreditMinor: Long,
    val baseCurrency: app.ledger.core.money.CurrencyCode,
    val baseDebitMinusCreditMinor: Long,
)

object FinancialFactNetting {
    @Suppress("ReturnCount")
    fun postings(bundles: List<JournalBundle>): DomainResult<List<PostingNet>> {
        val postings = bundles.flatMap { it.postings }
        val keys = postings.map { it.ledgerAccountId to it.accountAmount.currency }.distinct().sortedBy {
            it.first.value
        }
        val net = mutableListOf<PostingNet>()
        for ((ledgerId, currency) in keys) {
            val matching = postings.filter { it.ledgerAccountId == ledgerId && it.accountAmount.currency == currency }
            val accountWide = signedWide(matching.map { it.side to it.accountAmount.minor.value })
            val baseCurrency = matching.first().baseAmount.currency
            if (matching.any { it.baseAmount.currency != baseCurrency }) {
                return DomainResult.Failure(DomainViolation.Invariant("INV-001"))
            }
            val baseWide = signedWide(matching.map { it.side to it.baseAmount.minor.value })
            val account = CheckedArithmetic.toLongExact(accountWide)
            val base = CheckedArithmetic.toLongExact(baseWide)
            if (account !is DomainResult.Success || base !is DomainResult.Success) {
                return DomainResult.Failure(DomainViolation.NumericOverflow("financialFactNetting"))
            }
            net += PostingNet(ledgerId, currency, account.value, baseCurrency, base.value)
        }
        return DomainResult.Success(net)
    }

    private fun signedWide(values: List<Pair<DebitCredit, Long>>): BigInteger {
        var result = BigInteger.ZERO
        for ((side, value) in values) {
            val wide = BigInteger.valueOf(value)
            result = if (side == DebitCredit.DEBIT) result.add(wide) else result.subtract(wide)
        }
        return result
    }
}

object ImmutableFactAudit {
    @Suppress("ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    fun validateReversal(
        original: CurrentFinancialFacts,
        reversal: FinancialMutationPlan,
    ): DomainResult<Unit> {
        val reverseBundles = reversal.journalBundles.filter { it.entry.role == JournalEntryRole.REVERSE }
        if (reverseBundles.size != original.journalBundles.size) return failure("INV-006")
        for (originalBundle in original.journalBundles) {
            val reversed = reverseBundles.singleOrNull {
                it.entry.reversesEntryId == originalBundle.entry.id
            } ?: return failure("INV-007")
            if (
                reversed.entry.effectiveAt != originalBundle.entry.effectiveAt ||
                reversed.entry.appliesRevisionId != originalBundle.entry.appliesRevisionId ||
                reversed.entry.baseCurrency != originalBundle.entry.baseCurrency ||
                reversed.entry.ruleSetVersion != originalBundle.entry.ruleSetVersion ||
                reversed.postings.size != originalBundle.postings.size
            ) {
                return failure("INV-006")
            }
            for (posting in originalBundle.postings) {
                val reversePosting = reversed.postings.singleOrNull { it.reversalOfPostingId == posting.id }
                    ?: return failure("INV-006")
                if (
                    reversePosting.ledgerAccountId != posting.ledgerAccountId ||
                    reversePosting.side == posting.side ||
                    reversePosting.accountAmount != posting.accountAmount ||
                    reversePosting.baseAmount != posting.baseAmount ||
                    reversePosting.valuationRate != posting.valuationRate ||
                    reversePosting.role != posting.role
                ) {
                    return failure("INV-006")
                }
            }
        }
        for (effect in original.economicEffects) {
            val reverse = reversal.economicEffects.singleOrNull { it.reversalOfId == effect.id }
                ?: return failure("INV-006")
            if (
                reverse.polarity != EffectPolarity.REVERSE ||
                reverse.nature != effect.nature ||
                reverse.component != effect.component ||
                reverse.isConsumption != effect.isConsumption ||
                reverse.baseAmount != effect.baseAmount ||
                reverse.accrualDate != effect.accrualDate ||
                reverse.categoryId != effect.categoryId ||
                reverse.merchantId != effect.merchantId ||
                reverse.projectId != effect.projectId ||
                reverse.ruleSetVersion != effect.ruleSetVersion
            ) {
                return failure("INV-006")
            }
        }
        for (effect in original.budgetEffects) {
            val reverse = reversal.budgetEffects.singleOrNull { it.reversalOfId == effect.id }
                ?: return failure("INV-006")
            if (
                reverse.polarity != EffectPolarity.REVERSE ||
                reverse.kind != effect.kind.reversed() ||
                reverse.targetMonth != effect.targetMonth ||
                reverse.categoryId != effect.categoryId ||
                reverse.rootCategoryId != effect.rootCategoryId ||
                reverse.baseAmount != effect.baseAmount ||
                reverse.ruleSetVersion != effect.ruleSetVersion
            ) {
                return failure("INV-006")
            }
        }
        for (effect in original.projectEffects) {
            val reverse = reversal.projectEffects.singleOrNull { it.reversalOfId == effect.id }
                ?: return failure("INV-006")
            if (
                reverse.polarity != EffectPolarity.REVERSE ||
                reverse.projectId != effect.projectId ||
                reverse.kind != effect.kind.reversed() ||
                reverse.baseAmount != effect.baseAmount ||
                reverse.includedInMonthlyBudgetSnapshot != effect.includedInMonthlyBudgetSnapshot
            ) {
                return failure("INV-006")
            }
        }
        for (effect in original.goalEffects) {
            val reverse = reversal.goalEffects.singleOrNull { it.reversalOfId == effect.id }
                ?: return failure("INV-006")
            if (
                reverse.polarity != EffectPolarity.REVERSE ||
                reverse.goalId != effect.goalId ||
                reverse.kind != effect.kind.reversed() ||
                reverse.amount != effect.amount
            ) {
                return failure("INV-006")
            }
        }
        for (effect in original.statementEffects) {
            val reverse = reversal.statementEffects.singleOrNull { it.reversalOfId == effect.id }
                ?: return failure("INV-006")
            if (
                reverse.polarity != EffectPolarity.REVERSE ||
                reverse.creditAccountId != effect.creditAccountId ||
                reverse.statementId != effect.statementId ||
                reverse.kind != effect.kind ||
                reverse.amount != effect.amount ||
                reverse.manualAssignment != effect.manualAssignment
            ) {
                return failure("INV-006")
            }
        }
        for (effect in original.loanEffects) {
            val reverse = reversal.loanEffects.singleOrNull { it.reversalOfId == effect.id }
                ?: return failure("INV-006")
            if (
                reverse.polarity != EffectPolarity.REVERSE ||
                reverse.loanContractId != effect.loanContractId ||
                reverse.loanTrancheId != effect.loanTrancheId ||
                reverse.scheduleItemId != effect.scheduleItemId ||
                reverse.kind != effect.kind ||
                reverse.amount != effect.amount ||
                reverse.baseAmount != effect.baseAmount
            ) {
                return failure("INV-006")
            }
        }
        for (effect in original.settlementEffects) {
            val reverse = reversal.settlementEffects.singleOrNull { it.reversalOfId == effect.id }
                ?: return failure("INV-006")
            val expectedDelta = CheckedArithmetic.negate(effect.signedDeltaMinor)
            if (
                expectedDelta !is DomainResult.Success ||
                reverse.activityId != effect.activityId ||
                reverse.participantId != effect.participantId ||
                reverse.kind != effect.kind ||
                reverse.signedDeltaMinor != expectedDelta.value ||
                reverse.currency != effect.currency
            ) {
                return failure("INV-006")
            }
        }
        for (allocation in original.refundAllocationFacts) {
            val reference = RefundAllocationReference(allocation.refundRevisionId, allocation.originalTransactionId)
            val reverse = reversal.refundAllocations.singleOrNull { it.reversalOf == reference }
                ?: return failure("INV-006")
            if (
                reverse.refundTransactionId != allocation.refundTransactionId ||
                reverse.originalRevisionId != allocation.originalRevisionId ||
                reverse.amountInOriginalCurrency != allocation.amountInOriginalCurrency ||
                reverse.amountInBaseCurrency != allocation.amountInBaseCurrency
            ) {
                return failure("INV-006")
            }
        }
        if (reversal.effectReversalCount() != original.effectCount()) return failure("INV-006")
        return DomainResult.Success(Unit)
    }

    private fun failure(invariant: String): DomainResult.Failure = DomainResult.Failure(DomainViolation.Invariant(invariant))
}

private fun CurrentFinancialFacts.effectCount(): Int = economicEffects.size + budgetEffects.size + projectEffects.size + goalEffects.size +
    statementEffects.size + loanEffects.size + settlementEffects.size + refundAllocationFacts.size

private fun FinancialMutationPlan.effectReversalCount(): Int = economicEffects.count { it.reversalOfId != null } +
    budgetEffects.count { it.reversalOfId != null } +
    projectEffects.count { it.reversalOfId != null } +
    goalEffects.count { it.reversalOfId != null } +
    statementEffects.count { it.reversalOfId != null } +
    loanEffects.count { it.reversalOfId != null } +
    settlementEffects.count { it.reversalOfId != null } + refundAllocations.count { it.reversalOf != null }

private fun BudgetEffectKind.reversed(): BudgetEffectKind = when (this) {
    BudgetEffectKind.USE -> BudgetEffectKind.RESTORE
    BudgetEffectKind.RESTORE -> BudgetEffectKind.USE
}

private fun ProjectEffectKind.reversed(): ProjectEffectKind = when (this) {
    ProjectEffectKind.USE -> ProjectEffectKind.RESTORE
    ProjectEffectKind.RESTORE -> ProjectEffectKind.USE
    ProjectEffectKind.ADJUST -> ProjectEffectKind.ADJUST
}

private fun GoalEffectKind.reversed(): GoalEffectKind = when (this) {
    GoalEffectKind.ALLOCATE -> GoalEffectKind.RELEASE
    GoalEffectKind.RELEASE -> GoalEffectKind.ALLOCATE
    GoalEffectKind.SPEND -> GoalEffectKind.RESTORE
    GoalEffectKind.RESTORE -> GoalEffectKind.SPEND
    GoalEffectKind.ADJUST -> GoalEffectKind.ADJUST
}
