package app.ledger.finance.domain

import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.money.CurrencyCode
import app.ledger.core.time.EffectiveTime
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

enum class TransactionKind {
    EXPENSE,
    INCOME,
    TRANSFER,
    REFUND,
    CREDIT_PAYMENT,
    LOAN_DISBURSEMENT,
    LOAN_PAYMENT,
    BALANCE_ADJUSTMENT,
    FX_EXCHANGE,
    SETTLEMENT_PAYMENT,
    OPENING_BALANCE,
}

enum class TransactionLifecycleState {
    ACTIVE,
    TRASHED,
}

data class BusinessTransaction(
    val id: TransactionId,
    val kind: TransactionKind,
    val currentRevisionId: TransactionRevisionId,
    val lifecycleState: TransactionLifecycleState,
    val createdCommitId: BookCommitId,
    val lastCommitId: BookCommitId,
    val rowVersion: RowVersion,
    val trashedAt: Instant?,
    val purgeAfter: Instant?,
    val contentHash: ContentHash,
) : LifecycleRecord<RecordLifecycle.Current> {
    override val lifecycle: RecordLifecycle.Current = RecordLifecycle.Current

    init {
        require(
            (lifecycleState == TransactionLifecycleState.ACTIVE && trashedAt == null && purgeAfter == null) ||
                (lifecycleState == TransactionLifecycleState.TRASHED && trashedAt != null && purgeAfter != null),
        )
    }
}

enum class RevisionAction {
    CREATE,
    EDIT,
    MOVE_TO_TRASH,
    RESTORE,
    BULK_EDIT,
    DEPENDENCY_REWRITE,
}

enum class TransactionSource {
    MANUAL,
    QUICK_TEMPLATE,
    RECURRENCE_AUTO,
    RECURRENCE_CANDIDATE,
    CSV_IMPORT,
    XLSX_IMPORT,
    STRUCTURED_IMPORT,
    SYSTEM_GENERATED,
    MERGE_RESTORE,
    BATCH_OPERATION,
}

enum class StatementAssignmentMode {
    AUTOMATIC,
    PREVIOUS_CYCLE,
    NEXT_CYCLE,
    EXPLICIT_STATEMENT,
}

data class StatementAssignment(
    val mode: StatementAssignmentMode,
    val statementId: CreditStatementId?,
) {
    init {
        require(mode == StatementAssignmentMode.AUTOMATIC || statementId != null)
    }
}

data class TransactionRevision(
    val id: TransactionRevisionId,
    val transactionId: TransactionId,
    val revisionNumber: Int,
    val action: RevisionAction,
    val resultingState: TransactionLifecycleState,
    val previousRevisionId: TransactionRevisionId?,
    val createdCommitId: BookCommitId,
    val createdAt: Instant,
    val occurredAt: EffectiveTime,
    val accrualDate: LocalDate,
    val budgetMonth: YearMonth?,
    val merchantId: MerchantId?,
    val projectId: ProjectId?,
    val goalId: GoalId?,
    val locationRecordId: LocationRecordId?,
    val note: String?,
    val amountExpression: String?,
    val source: TransactionSource,
    val sourceReferenceId: StableId?,
    val statementAssignment: StatementAssignment?,
    val attachmentIds: List<AttachmentId>,
    val payload: TransactionPayload,
    val contentHash: ContentHash,
) : LifecycleRecord<RecordLifecycle.Revision> {
    override val lifecycle: RecordLifecycle.Revision = RecordLifecycle.Revision

    val kind: TransactionKind = payload.kind
    val categoryId: CategoryId? = payload.classification?.categoryId
    val statisticalNatureSnapshot: StatisticalNature? = payload.classification?.statisticalNatureSnapshot

    init {
        require(revisionNumber > 0)
        require((revisionNumber == 1) == (previousRevisionId == null))
        require(attachmentIds.toSet().size == attachmentIds.size)
    }
}

sealed interface TransactionPayload {
    val kind: TransactionKind
    val classification: CategoryAssignment?
}

sealed interface ExpensePayer {
    data class LocalAccount(
        val accountAmount: AccountAmount,
        val cardId: PaymentCardId?,
    ) : ExpensePayer

    data class ExternalParticipant(
        val participantId: ParticipantId,
        val activityId: SettlementActivityId,
    ) : ExpensePayer
}

data class ExpensePayload(
    override val classification: CategoryAssignment,
    val payer: ExpensePayer,
    val primaryAmount: PositiveMoney,
    val settlementActivityId: SettlementActivityId?,
    val settlementShares: List<SettlementShare>,
    val installmentPlanId: InstallmentPlanId?,
) : TransactionPayload {
    override val kind: TransactionKind = TransactionKind.EXPENSE

    init {
        require(classification.direction == CategoryDirection.EXPENSE)
    }
}

data class IncomePayload(
    override val classification: CategoryAssignment,
    val receivingAmount: AccountAmount,
    val primaryAmount: PositiveMoney,
) : TransactionPayload {
    override val kind: TransactionKind = TransactionKind.INCOME

    init {
        require(classification.direction == CategoryDirection.INCOME)
    }
}

data class TransferPayload(
    val outgoing: AccountAmount,
    val incoming: AccountAmount,
    val sourceCardId: PaymentCardId?,
) : TransactionPayload {
    override val kind: TransactionKind = TransactionKind.TRANSFER
    override val classification: CategoryAssignment? = null

    init {
        require(outgoing.accountId != incoming.accountId)
    }
}

enum class RefundBudgetPolicy {
    RESTORE_ORIGINAL_MONTH,
    RESTORE_REFUND_MONTH,
    DO_NOT_RESTORE,
}

enum class RefundProjectPolicy {
    RESTORE_ORIGINAL_PROJECT,
    USE_SELECTED_PROJECT,
    DO_NOT_RESTORE,
}

enum class RefundGoalPolicy {
    RESTORE_ORIGINAL_GOAL,
    USE_SELECTED_GOAL,
    DO_NOT_RESTORE,
}

data class RefundAllocation(
    val originalTransactionId: TransactionId,
    val originalRevisionId: TransactionRevisionId,
    val amountInOriginalCurrency: PositiveMoney,
    val amountInBaseCurrency: PositiveMoney,
)

data class RefundPayload(
    override val classification: CategoryAssignment?,
    val receivingAmount: AccountAmount,
    val receivingCardId: PaymentCardId?,
    val allocations: List<RefundAllocation>,
    val independent: Boolean,
    val allowExcessOverride: Boolean,
    val budgetPolicy: RefundBudgetPolicy,
    val projectPolicy: RefundProjectPolicy,
    val goalPolicy: RefundGoalPolicy,
    val accrualPolicy: RefundAccrualPolicy,
    val settlementActivityId: SettlementActivityId? = null,
    val settlementShares: List<SettlementShare> = emptyList(),
) : TransactionPayload {
    override val kind: TransactionKind = TransactionKind.REFUND

    init {
        require(independent == allocations.isEmpty())
        require(independent || allocations.map { it.originalTransactionId }.toSet().size == allocations.size)
        require(classification == null || classification.direction == CategoryDirection.EXPENSE)
        require((settlementActivityId == null) == settlementShares.isEmpty())
    }
}

/** Natural reference used by the normalized refund fact table, which intentionally has no stable UID column. */
data class RefundAllocationReference(
    val refundRevisionId: TransactionRevisionId,
    val originalTransactionId: TransactionId,
)

/** Immutable allocation fact emitted by the planner; persistence only resolves its natural reference to an internal row ID. */
data class RefundAllocationFact(
    val refundTransactionId: TransactionId,
    val refundRevisionId: TransactionRevisionId,
    val originalTransactionId: TransactionId,
    val originalRevisionId: TransactionRevisionId,
    val amountInOriginalCurrency: PositiveMoney,
    val amountInBaseCurrency: PositiveMoney,
    val createdCommitId: BookCommitId,
    val reversalOf: RefundAllocationReference?,
) : LifecycleRecord<RecordLifecycle.Fact> {
    override val lifecycle: RecordLifecycle.Fact = RecordLifecycle.Fact

    init {
        require(refundTransactionId != originalTransactionId)
        require(reversalOf == null || reversalOf.originalTransactionId == originalTransactionId)
    }
}

enum class RefundAccrualPolicy {
    ORIGINAL_TRANSACTION_DATE,
    REFUND_DATE,
}

enum class AutoGenerationMode {
    FORMAL_TRANSACTION,
    CONFIRMATION_CANDIDATE,
}

data class CreditPaymentAllocation(
    val statementId: CreditStatementId?,
    val amount: PositiveMoney,
)

data class CreditPaymentPayload(
    val payment: AccountAmount,
    val creditAccountId: UserAccountId,
    val creditAccountAmount: AccountAmount,
    val allocations: List<CreditPaymentAllocation>,
    val generationMode: AutoGenerationMode,
) : TransactionPayload {
    override val kind: TransactionKind = TransactionKind.CREDIT_PAYMENT
    override val classification: CategoryAssignment? = null
}

data class LoanDisbursementPayload(
    val loanContractId: LoanContractId,
    val receivingAmount: AccountAmount,
    val liabilityAmount: PositiveMoney,
) : TransactionPayload {
    override val kind: TransactionKind = TransactionKind.LOAN_DISBURSEMENT
    override val classification: CategoryAssignment? = null
}

data class LoanPaymentComponents(
    val principal: PositiveMoney?,
    val interest: PositiveMoney?,
    val fee: PositiveMoney?,
    val penalty: PositiveMoney?,
)

data class LoanPaymentPayload(
    override val classification: CategoryAssignment?,
    val loanContractId: LoanContractId,
    val payment: AccountAmount,
    val scheduleRevisionId: LoanScheduleRevisionId?,
    val components: LoanPaymentComponents,
    val allocations: List<LoanActualAllocation>,
) : TransactionPayload {
    override val kind: TransactionKind = TransactionKind.LOAN_PAYMENT
}

enum class BalanceAdjustmentDirection {
    INCREASE,
    DECREASE,
}

data class BalanceAdjustmentPayload(
    val accountAmount: AccountAmount,
    val direction: BalanceAdjustmentDirection,
    val checkpointId: StableId?,
) : TransactionPayload {
    override val kind: TransactionKind = TransactionKind.BALANCE_ADJUSTMENT
    override val classification: CategoryAssignment? = null
}

enum class FxValuationPolicy {
    EXPLICIT_ACCOUNT_AMOUNTS,
    PROVIDED_RATE,
    IMPLIED_RATE,
}

data class FxExchangePayload(
    override val classification: CategoryAssignment?,
    val outgoing: AccountAmount,
    val incoming: AccountAmount,
    val valuationPolicy: FxValuationPolicy,
    val spreadCost: PositiveMoney?,
) : TransactionPayload {
    override val kind: TransactionKind = TransactionKind.FX_EXCHANGE
}

data class SettlementPaymentPayload(
    val activityId: SettlementActivityId,
    val payerParticipantId: ParticipantId,
    val payeeParticipantId: ParticipantId,
    val amount: PositiveMoney,
    val localAccountAmount: AccountAmount?,
    val selfParticipates: Boolean,
) : TransactionPayload {
    override val kind: TransactionKind = TransactionKind.SETTLEMENT_PAYMENT
    override val classification: CategoryAssignment? = null

    init {
        require(payerParticipantId != payeeParticipantId)
        require(selfParticipates == (localAccountAmount != null))
    }
}

data class OpeningBalancePayload(
    val accountAmount: AccountAmount,
    val balanceDate: LocalDate,
    val side: DebitCredit,
) : TransactionPayload {
    override val kind: TransactionKind = TransactionKind.OPENING_BALANCE
    override val classification: CategoryAssignment? = null
}

enum class AmountRole {
    PRIMARY,
    OUTGOING,
    INCOMING,
    PRINCIPAL,
    INTEREST,
    FEE,
    PENALTY,
    SELF_SHARE,
    OTHER_PARTICIPANT_SHARE,
    REFUND,
    SETTLEMENT,
    FX_SPREAD,
    ROUNDING,
}

enum class AmountRepresentation {
    USER_INPUT,
    ACCOUNT,
    BASE,
    SETTLEMENT,
    STATEMENT,
}

data class RevisionAmount(
    val revisionId: TransactionRevisionId,
    val componentIndex: Int,
    val role: AmountRole,
    val representation: AmountRepresentation,
    val money: PositiveMoney,
    val relatedAccountId: UserAccountId?,
    val fxRateSnapshotId: FxRateSnapshotId?,
) : LifecycleRecord<RecordLifecycle.Revision> {
    override val lifecycle: RecordLifecycle.Revision = RecordLifecycle.Revision

    init {
        require(componentIndex >= 0)
        require(representation == AmountRepresentation.ACCOUNT || relatedAccountId == null)
    }
}

data class FxRateSnapshot(
    val id: FxRateSnapshotId,
    val evidence: app.ledger.core.money.FxEvidence,
    val staleAtUse: Boolean,
    val createdCommitId: BookCommitId,
) : LifecycleRecord<RecordLifecycle.Revision> {
    override val lifecycle: RecordLifecycle.Revision = RecordLifecycle.Revision
}

enum class TransactionDependencyType {
    REFUND,
    INSTALLMENT_PLAN,
    CREDIT_STATEMENT,
    LOAN_SCHEDULE,
    SETTLEMENT_ACTIVITY,
    RECURRENCE_OCCURRENCE,
    ATTACHMENT_REFERENCE,
}

data class TransactionDependency(
    val parentTransactionId: TransactionId,
    val childTransactionId: TransactionId,
    val type: TransactionDependencyType,
) : LifecycleRecord<RecordLifecycle.Projection> {
    override val lifecycle: RecordLifecycle.Projection = RecordLifecycle.Projection
}

sealed interface DependencyPolicy {
    data object ReverseDependentTransactions : DependencyPolicy

    data object ConvertRefundToIndependent : DependencyPolicy

    data object CancelInstallmentPlan : DependencyPolicy

    data class RebindInstallmentPlan(val replacementTransactionId: TransactionId) : DependencyPolicy

    data object RecalculateSettlement : DependencyPolicy

    data object ReopenSettledActivity : DependencyPolicy

    data object RegenerateCreditStatement : DependencyPolicy

    data object RecalculateLoanSchedule : DependencyPolicy
}

data class DependencyResolution(
    val dependency: TransactionDependency,
    val policy: DependencyPolicy,
)

data class RefundStatusProjection(
    val originalTransactionId: TransactionId,
    val grossRefundable: PositiveMoney,
    val refundedMinor: Long,
    val remainingMinor: Long,
    val asOfLocalRevision: LocalRevision,
    val originalLifecycleState: TransactionLifecycleState = TransactionLifecycleState.ACTIVE,
) : LifecycleRecord<RecordLifecycle.Projection> {
    override val lifecycle: RecordLifecycle.Projection = RecordLifecycle.Projection

    init {
        require(refundedMinor >= 0L)
        require(remainingMinor >= 0L)
    }
}

data class AccountBalanceProjection(
    val accountId: UserAccountId,
    val normalBalanceMinor: Long,
    val currency: CurrencyCode,
    val totalDebitMinor: Long,
    val totalCreditMinor: Long,
    val asOfLocalRevision: LocalRevision,
) : LifecycleRecord<RecordLifecycle.Projection> {
    override val lifecycle: RecordLifecycle.Projection = RecordLifecycle.Projection
}
