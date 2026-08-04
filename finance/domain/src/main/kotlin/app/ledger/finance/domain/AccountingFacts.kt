package app.ledger.finance.domain

import app.ledger.core.common.CheckedArithmetic
import app.ledger.core.common.DomainResult
import app.ledger.core.money.CurrencyCode
import app.ledger.core.time.EffectiveTime
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

enum class JournalEntryRole {
    APPLY,
    REVERSE,
}

enum class PostingRole {
    ASSET,
    LIABILITY,
    INCOME,
    EXPENSE,
    EQUITY,
    SETTLEMENT,
    CLEARING,
    ROUNDING,
}

data class LedgerAccountSnapshot(
    val id: LedgerAccountId,
    val accountClass: LedgerAccountClass,
    val normalSide: DebitCredit,
    val currency: CurrencyCode,
    val status: EntityStatus,
)

@ConsistentCopyVisibility
data class Posting private constructor(
    val id: PostingId,
    val journalEntryId: JournalEntryId,
    val lineNumber: Int,
    val ledgerAccountId: LedgerAccountId,
    val side: DebitCredit,
    val accountAmount: PositiveMoney,
    val baseAmount: PositiveMoney,
    val valuationRate: BigDecimal?,
    val role: PostingRole,
    val reversalOfPostingId: PostingId?,
) : LifecycleRecord<RecordLifecycle.Fact> {
    override val lifecycle: RecordLifecycle.Fact = RecordLifecycle.Fact

    companion object {
        @Suppress("LongParameterList", "ComplexCondition")
        fun create(
            id: PostingId,
            journalEntryId: JournalEntryId,
            lineNumber: Int,
            ledgerAccount: LedgerAccountSnapshot,
            side: DebitCredit,
            accountAmount: PositiveMoney,
            baseAmount: PositiveMoney,
            baseCurrency: CurrencyCode,
            valuationRate: BigDecimal?,
            role: PostingRole,
            reversalOfPostingId: PostingId?,
        ): DomainResult<Posting> {
            if (
                lineNumber < 1 ||
                ledgerAccount.currency != accountAmount.currency ||
                baseAmount.currency != baseCurrency ||
                ledgerAccount.status != EntityStatus.ACTIVE ||
                (valuationRate != null && valuationRate.signum() <= 0)
            ) {
                return DomainResult.Failure(DomainViolation.Invariant("INV-002"))
            }
            return DomainResult.Success(
                Posting(
                    id = id,
                    journalEntryId = journalEntryId,
                    lineNumber = lineNumber,
                    ledgerAccountId = ledgerAccount.id,
                    side = side,
                    accountAmount = accountAmount,
                    baseAmount = baseAmount,
                    valuationRate = valuationRate?.stripTrailingZeros(),
                    role = role,
                    reversalOfPostingId = reversalOfPostingId,
                ),
            )
        }

        /** Exact reversal remains legal when the referenced historical account is now archived. */
        @Suppress("LongParameterList", "ComplexCondition")
        fun reverse(
            id: PostingId,
            journalEntryId: JournalEntryId,
            lineNumber: Int,
            original: Posting,
            ledgerAccount: LedgerAccountSnapshot,
            baseCurrency: CurrencyCode,
        ): DomainResult<Posting> {
            if (
                lineNumber < 1 ||
                ledgerAccount.id != original.ledgerAccountId ||
                ledgerAccount.currency != original.accountAmount.currency ||
                original.baseAmount.currency != baseCurrency
            ) {
                return DomainResult.Failure(DomainViolation.Invariant("INV-002"))
            }
            val reversedSide = when (original.side) {
                DebitCredit.DEBIT -> DebitCredit.CREDIT
                DebitCredit.CREDIT -> DebitCredit.DEBIT
            }
            return DomainResult.Success(
                Posting(
                    id = id,
                    journalEntryId = journalEntryId,
                    lineNumber = lineNumber,
                    ledgerAccountId = ledgerAccount.id,
                    side = reversedSide,
                    accountAmount = original.accountAmount,
                    baseAmount = original.baseAmount,
                    valuationRate = original.valuationRate,
                    role = original.role,
                    reversalOfPostingId = original.id,
                ),
            )
        }

        /** Re-applies an unchanged historical line during a context-only immutable revision edit. */
        @Suppress("LongParameterList", "ComplexCondition")
        fun reapply(
            id: PostingId,
            journalEntryId: JournalEntryId,
            lineNumber: Int,
            original: Posting,
            ledgerAccount: LedgerAccountSnapshot,
            baseCurrency: CurrencyCode,
        ): DomainResult<Posting> {
            if (
                lineNumber < 1 ||
                ledgerAccount.id != original.ledgerAccountId ||
                ledgerAccount.currency != original.accountAmount.currency ||
                original.baseAmount.currency != baseCurrency
            ) {
                return DomainResult.Failure(DomainViolation.Invariant("INV-002"))
            }
            return DomainResult.Success(
                Posting(
                    id = id,
                    journalEntryId = journalEntryId,
                    lineNumber = lineNumber,
                    ledgerAccountId = ledgerAccount.id,
                    side = original.side,
                    accountAmount = original.accountAmount,
                    baseAmount = original.baseAmount,
                    valuationRate = original.valuationRate,
                    role = original.role,
                    reversalOfPostingId = null,
                ),
            )
        }
    }
}

@ConsistentCopyVisibility
data class JournalEntry private constructor(
    val id: JournalEntryId,
    val sourceRevisionId: TransactionRevisionId,
    val appliesRevisionId: TransactionRevisionId,
    val role: JournalEntryRole,
    val reversesEntryId: JournalEntryId?,
    val effectiveAt: EffectiveTime,
    val baseCurrency: CurrencyCode,
    val baseDebitTotalMinor: Long,
    val baseCreditTotalMinor: Long,
    val postingCount: Int,
    val ruleSetVersion: RuleSetVersion,
    val createdCommitId: BookCommitId,
    val contentHash: ContentHash,
) : LifecycleRecord<RecordLifecycle.Fact> {
    override val lifecycle: RecordLifecycle.Fact = RecordLifecycle.Fact

    companion object {
        @Suppress("LongParameterList", "ComplexCondition", "ReturnCount")
        fun create(
            id: JournalEntryId,
            sourceRevisionId: TransactionRevisionId,
            appliesRevisionId: TransactionRevisionId,
            role: JournalEntryRole,
            reversesEntryId: JournalEntryId?,
            effectiveAt: EffectiveTime,
            baseCurrency: CurrencyCode,
            postings: List<Posting>,
            ruleSetVersion: RuleSetVersion,
            createdCommitId: BookCommitId,
            contentHash: ContentHash,
        ): DomainResult<JournalEntry> {
            if (
                postings.size < 2 ||
                postings.map { it.lineNumber }.toSet().size != postings.size ||
                postings.any { it.journalEntryId != id || it.baseAmount.currency != baseCurrency } ||
                (role == JournalEntryRole.REVERSE) != (reversesEntryId != null)
            ) {
                return DomainResult.Failure(DomainViolation.Invariant("INV-001"))
            }
            val debits = postings.filter { it.side == DebitCredit.DEBIT }.map { it.baseAmount.minor.value }
            val credits = postings.filter { it.side == DebitCredit.CREDIT }.map { it.baseAmount.minor.value }
            val debitTotal = CheckedArithmetic.sum(debits)
            val creditTotal = CheckedArithmetic.sum(credits)
            if (debitTotal !is DomainResult.Success || creditTotal !is DomainResult.Success) {
                return DomainResult.Failure(DomainViolation.NumericOverflow("journalEntry.baseTotal"))
            }
            if (debitTotal.value != creditTotal.value) {
                return DomainResult.Failure(DomainViolation.Invariant("INV-001"))
            }
            return DomainResult.Success(
                JournalEntry(
                    id = id,
                    sourceRevisionId = sourceRevisionId,
                    appliesRevisionId = appliesRevisionId,
                    role = role,
                    reversesEntryId = reversesEntryId,
                    effectiveAt = effectiveAt,
                    baseCurrency = baseCurrency,
                    baseDebitTotalMinor = debitTotal.value,
                    baseCreditTotalMinor = creditTotal.value,
                    postingCount = postings.size,
                    ruleSetVersion = ruleSetVersion,
                    createdCommitId = createdCommitId,
                    contentHash = contentHash,
                ),
            )
        }
    }
}

enum class EffectPolarity {
    APPLY,
    REVERSE,
}

enum class EconomicNature {
    INCOME,
    EXPENSE,
    CONTRA_EXPENSE,
    EQUITY,
}

enum class EconomicComponent {
    PRIMARY,
    INTEREST,
    FEE,
    PENALTY,
    FX_COST,
    CASHBACK,
    REFUND,
    SELF_SETTLEMENT_SHARE,
}

data class EconomicEffect(
    val id: EconomicEffectId,
    val sourceEntryId: JournalEntryId,
    val sourceRevisionId: TransactionRevisionId,
    val reversalOfId: EconomicEffectId?,
    val polarity: EffectPolarity,
    val nature: EconomicNature,
    val component: EconomicComponent,
    val isConsumption: Boolean,
    val baseAmount: PositiveMoney,
    val accrualDate: LocalDate,
    val categoryId: CategoryId?,
    val merchantId: MerchantId?,
    val projectId: ProjectId?,
    val ruleSetVersion: RuleSetVersion,
) : LifecycleRecord<RecordLifecycle.Fact> {
    override val lifecycle: RecordLifecycle.Fact = RecordLifecycle.Fact
}

enum class BudgetEffectKind {
    USE,
    RESTORE,
}

data class BudgetEffect(
    val id: BudgetEffectId,
    val sourceRevisionId: TransactionRevisionId,
    val reversalOfId: BudgetEffectId?,
    val polarity: EffectPolarity,
    val kind: BudgetEffectKind,
    val targetMonth: YearMonth,
    val categoryId: CategoryId?,
    val rootCategoryId: CategoryId?,
    val baseAmount: PositiveMoney,
    val ruleSetVersion: RuleSetVersion,
) : LifecycleRecord<RecordLifecycle.Fact> {
    override val lifecycle: RecordLifecycle.Fact = RecordLifecycle.Fact
}

enum class ProjectEffectKind {
    USE,
    RESTORE,
    ADJUST,
}

data class ProjectEffect(
    val id: ProjectEffectId,
    val projectId: ProjectId,
    val kind: ProjectEffectKind,
    val baseAmount: PositiveMoney,
    val includedInMonthlyBudgetSnapshot: Boolean,
    val sourceRevisionId: TransactionRevisionId,
    val reversalOfId: ProjectEffectId?,
    val polarity: EffectPolarity,
) : LifecycleRecord<RecordLifecycle.Fact> {
    override val lifecycle: RecordLifecycle.Fact = RecordLifecycle.Fact
}

enum class GoalEffectKind {
    ALLOCATE,
    RELEASE,
    SPEND,
    RESTORE,
    ADJUST,
}

data class GoalEffect(
    val id: GoalEffectId,
    val goalId: GoalId,
    val kind: GoalEffectKind,
    val amount: PositiveMoney,
    val sourceRevisionId: TransactionRevisionId?,
    val goalMovementId: GoalMovementId?,
    val reversalOfId: GoalEffectId?,
    val polarity: EffectPolarity,
) : LifecycleRecord<RecordLifecycle.Fact> {
    override val lifecycle: RecordLifecycle.Fact = RecordLifecycle.Fact

    init {
        require((sourceRevisionId == null) != (goalMovementId == null))
    }
}

enum class StatementEffectKind {
    CHARGE,
    REFUND,
    PAYMENT,
    ADJUSTMENT,
    INSTALLMENT_POSTING,
}

data class StatementEffect(
    val id: StatementEffectId,
    val creditAccountId: UserAccountId,
    val statementId: CreditStatementId?,
    val sourceRevisionId: TransactionRevisionId,
    val reversalOfId: StatementEffectId?,
    val kind: StatementEffectKind,
    val polarity: EffectPolarity,
    val amount: PositiveMoney,
    val manualAssignment: Boolean,
) : LifecycleRecord<RecordLifecycle.Fact> {
    override val lifecycle: RecordLifecycle.Fact = RecordLifecycle.Fact
}

enum class LoanEffectKind {
    DISBURSEMENT,
    PRINCIPAL_PAYMENT,
    INTEREST_PAYMENT,
    FEE_PAYMENT,
    PENALTY_PAYMENT,
    PREPAYMENT,
}

data class LoanEffect(
    val id: LoanEffectId,
    val loanContractId: LoanContractId,
    val loanTrancheId: LoanTrancheId,
    val scheduleItemId: LoanScheduleItemId?,
    val sourceRevisionId: TransactionRevisionId,
    val reversalOfId: LoanEffectId?,
    val kind: LoanEffectKind,
    val polarity: EffectPolarity,
    val amount: PositiveMoney,
    val baseAmount: PositiveMoney,
) : LifecycleRecord<RecordLifecycle.Fact> {
    override val lifecycle: RecordLifecycle.Fact = RecordLifecycle.Fact
}

enum class SettlementEffectKind {
    PAID_FOR_GROUP,
    OWED_SHARE,
    SETTLEMENT_PAID,
    SETTLEMENT_RECEIVED,
}

data class SettlementEffect(
    val id: SettlementEffectId,
    val activityId: SettlementActivityId,
    val participantId: ParticipantId,
    val sourceRevisionId: TransactionRevisionId?,
    val settlementPaymentRecordId: SettlementPaymentRecordId?,
    val reversalOfId: SettlementEffectId?,
    val kind: SettlementEffectKind,
    val signedDeltaMinor: Long,
    val currency: CurrencyCode,
) : LifecycleRecord<RecordLifecycle.Fact> {
    override val lifecycle: RecordLifecycle.Fact = RecordLifecycle.Fact

    init {
        require((sourceRevisionId == null) != (settlementPaymentRecordId == null))
    }
}

sealed interface ProjectionChange {
    val targetRevision: LocalRevision

    data class CurrentTransaction(
        val transactionId: TransactionId,
        override val targetRevision: LocalRevision,
    ) : ProjectionChange

    data class AccountFromDate(
        val accountId: UserAccountId,
        val fromDate: LocalDate,
        override val targetRevision: LocalRevision,
    ) : ProjectionChange

    data class BudgetFromMonth(
        val month: YearMonth,
        override val targetRevision: LocalRevision,
    ) : ProjectionChange

    data class Project(
        val projectId: ProjectId,
        override val targetRevision: LocalRevision,
    ) : ProjectionChange

    data class Goal(
        val goalId: GoalId,
        override val targetRevision: LocalRevision,
    ) : ProjectionChange

    data class Statement(
        val statementId: CreditStatementId,
        override val targetRevision: LocalRevision,
    ) : ProjectionChange

    data class Loan(
        val loanContractId: LoanContractId,
        override val targetRevision: LocalRevision,
    ) : ProjectionChange

    data class Settlement(
        val activityId: SettlementActivityId,
        override val targetRevision: LocalRevision,
    ) : ProjectionChange

    data class Refund(
        val originalTransactionId: TransactionId,
        override val targetRevision: LocalRevision,
    ) : ProjectionChange

    data class SearchAndMap(
        val transactionId: TransactionId,
        override val targetRevision: LocalRevision,
    ) : ProjectionChange

    data class Widget(
        val bookId: BookId,
        override val targetRevision: LocalRevision,
    ) : ProjectionChange
}

data class ProjectionChangeSet(
    val targetRevision: LocalRevision,
    val changes: List<ProjectionChange>,
) {
    init {
        require(changes.all { it.targetRevision == targetRevision })
    }
}
