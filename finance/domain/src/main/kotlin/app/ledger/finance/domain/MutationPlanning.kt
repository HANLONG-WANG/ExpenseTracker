package app.ledger.finance.domain

import app.ledger.core.common.CommandId
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

enum class FinancialCommandType {
    RECORD_EXPENSE,
    RECORD_INCOME,
    RECORD_TRANSFER,
    RECORD_REFUND,
    RECORD_CREDIT_PAYMENT,
    RECORD_LOAN_DISBURSEMENT,
    RECORD_LOAN_PAYMENT,
    RECORD_BALANCE_ADJUSTMENT,
    RECORD_FX_EXCHANGE,
    RECORD_SETTLEMENT_PAYMENT,
    RECORD_OPENING_BALANCE,
    EDIT_TRANSACTION,
    MOVE_TRANSACTION_TO_TRASH,
    RESTORE_TRANSACTION,
    PURGE_TRANSACTION,
    RECORD_GOAL_MOVEMENT,
    RECORD_BUDGET_ADJUSTMENT,
    BATCH_MUTATION,
}

sealed interface FinancialCommand {
    val commandId: CommandId
    val expectedRevisionId: TransactionRevisionId?
    val commandType: FinancialCommandType
    val payloadHash: Hash256
}

data class TransactionContextInput(
    val occurredAt: app.ledger.core.time.EffectiveTime,
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
)

data class NewTransactionInput<out P : TransactionPayload>(
    val context: TransactionContextInput,
    val payload: P,
)

sealed interface RecordTransactionCommand<out P : TransactionPayload> : FinancialCommand {
    val input: NewTransactionInput<P>
    override val expectedRevisionId: TransactionRevisionId?
        get() = null
}

data class RecordExpenseCommand(
    override val commandId: CommandId,
    override val payloadHash: Hash256,
    override val input: NewTransactionInput<ExpensePayload>,
) : RecordTransactionCommand<ExpensePayload> {
    override val commandType: FinancialCommandType = FinancialCommandType.RECORD_EXPENSE
}

data class RecordIncomeCommand(
    override val commandId: CommandId,
    override val payloadHash: Hash256,
    override val input: NewTransactionInput<IncomePayload>,
) : RecordTransactionCommand<IncomePayload> {
    override val commandType: FinancialCommandType = FinancialCommandType.RECORD_INCOME
}

data class RecordTransferCommand(
    override val commandId: CommandId,
    override val payloadHash: Hash256,
    override val input: NewTransactionInput<TransferPayload>,
) : RecordTransactionCommand<TransferPayload> {
    override val commandType: FinancialCommandType = FinancialCommandType.RECORD_TRANSFER
}

data class RecordRefundCommand(
    override val commandId: CommandId,
    override val payloadHash: Hash256,
    override val input: NewTransactionInput<RefundPayload>,
) : RecordTransactionCommand<RefundPayload> {
    override val commandType: FinancialCommandType = FinancialCommandType.RECORD_REFUND
}

data class RecordCreditPaymentCommand(
    override val commandId: CommandId,
    override val payloadHash: Hash256,
    override val input: NewTransactionInput<CreditPaymentPayload>,
) : RecordTransactionCommand<CreditPaymentPayload> {
    override val commandType: FinancialCommandType = FinancialCommandType.RECORD_CREDIT_PAYMENT
}

data class RecordLoanDisbursementCommand(
    override val commandId: CommandId,
    override val payloadHash: Hash256,
    override val input: NewTransactionInput<LoanDisbursementPayload>,
) : RecordTransactionCommand<LoanDisbursementPayload> {
    override val commandType: FinancialCommandType = FinancialCommandType.RECORD_LOAN_DISBURSEMENT
}

data class RecordLoanPaymentCommand(
    override val commandId: CommandId,
    override val payloadHash: Hash256,
    override val input: NewTransactionInput<LoanPaymentPayload>,
) : RecordTransactionCommand<LoanPaymentPayload> {
    override val commandType: FinancialCommandType = FinancialCommandType.RECORD_LOAN_PAYMENT
}

data class RecordBalanceAdjustmentCommand(
    override val commandId: CommandId,
    override val payloadHash: Hash256,
    override val input: NewTransactionInput<BalanceAdjustmentPayload>,
) : RecordTransactionCommand<BalanceAdjustmentPayload> {
    override val commandType: FinancialCommandType = FinancialCommandType.RECORD_BALANCE_ADJUSTMENT
}

data class RecordFxExchangeCommand(
    override val commandId: CommandId,
    override val payloadHash: Hash256,
    override val input: NewTransactionInput<FxExchangePayload>,
) : RecordTransactionCommand<FxExchangePayload> {
    override val commandType: FinancialCommandType = FinancialCommandType.RECORD_FX_EXCHANGE
}

data class RecordSettlementPaymentCommand(
    override val commandId: CommandId,
    override val payloadHash: Hash256,
    override val input: NewTransactionInput<SettlementPaymentPayload>,
) : RecordTransactionCommand<SettlementPaymentPayload> {
    override val commandType: FinancialCommandType = FinancialCommandType.RECORD_SETTLEMENT_PAYMENT
}

data class RecordOpeningBalanceCommand(
    override val commandId: CommandId,
    override val payloadHash: Hash256,
    override val input: NewTransactionInput<OpeningBalancePayload>,
) : RecordTransactionCommand<OpeningBalancePayload> {
    override val commandType: FinancialCommandType = FinancialCommandType.RECORD_OPENING_BALANCE
}

data class EditTransactionCommand(
    override val commandId: CommandId,
    override val expectedRevisionId: TransactionRevisionId,
    override val payloadHash: Hash256,
    val transactionId: TransactionId,
    val replacement: NewTransactionInput<TransactionPayload>,
    val dependencyResolutions: List<DependencyResolution>,
) : FinancialCommand {
    override val commandType: FinancialCommandType = FinancialCommandType.EDIT_TRANSACTION
}

data class MoveTransactionToTrashCommand(
    override val commandId: CommandId,
    override val expectedRevisionId: TransactionRevisionId,
    override val payloadHash: Hash256,
    val transactionId: TransactionId,
    val purgeAfter: Instant,
    val dependencyResolutions: List<DependencyResolution>,
) : FinancialCommand {
    override val commandType: FinancialCommandType = FinancialCommandType.MOVE_TRANSACTION_TO_TRASH
}

data class RestoreTransactionCommand(
    override val commandId: CommandId,
    override val expectedRevisionId: TransactionRevisionId,
    override val payloadHash: Hash256,
    val transactionId: TransactionId,
) : FinancialCommand {
    override val commandType: FinancialCommandType = FinancialCommandType.RESTORE_TRANSACTION
}

data class PurgeEligibility(
    val transactionId: TransactionId,
    val lifecycleState: TransactionLifecycleState,
    val purgeAfter: Instant,
    val evaluatedAt: Instant,
    val accountCurrencyNetZero: Boolean,
    val baseCurrencyNetZero: Boolean,
    val effectsNetZero: Boolean,
    val dependenciesClosed: Boolean,
    val referencedByOperation: Boolean,
    val attachmentsReadByBackup: Boolean,
) {
    val eligible: Boolean = lifecycleState == TransactionLifecycleState.TRASHED &&
        evaluatedAt >= purgeAfter &&
        accountCurrencyNetZero &&
        baseCurrencyNetZero &&
        effectsNetZero &&
        dependenciesClosed &&
        !referencedByOperation &&
        !attachmentsReadByBackup
}

data class PurgeTransactionCommand(
    override val commandId: CommandId,
    override val expectedRevisionId: TransactionRevisionId,
    override val payloadHash: Hash256,
    val transactionId: TransactionId,
    val eligibility: PurgeEligibility,
) : FinancialCommand {
    override val commandType: FinancialCommandType = FinancialCommandType.PURGE_TRANSACTION
}

data class RecordGoalMovementCommand(
    override val commandId: CommandId,
    override val payloadHash: Hash256,
    val movement: GoalMovement,
) : FinancialCommand {
    override val expectedRevisionId: TransactionRevisionId? = null
    override val commandType: FinancialCommandType = FinancialCommandType.RECORD_GOAL_MOVEMENT
}

data class RecordBudgetAdjustmentCommand(
    override val commandId: CommandId,
    override val payloadHash: Hash256,
    val adjustment: BudgetAdjustment,
) : FinancialCommand {
    override val expectedRevisionId: TransactionRevisionId? = null
    override val commandType: FinancialCommandType = FinancialCommandType.RECORD_BUDGET_ADJUSTMENT
}

data class BatchFinancialCommand(
    override val commandId: CommandId,
    override val payloadHash: Hash256,
    val commands: List<FinancialCommand>,
) : FinancialCommand {
    override val expectedRevisionId: TransactionRevisionId? = null
    override val commandType: FinancialCommandType = FinancialCommandType.BATCH_MUTATION

    init {
        require(commands.isNotEmpty())
        require(commands.none { it is BatchFinancialCommand })
        require(commands.map { it.commandId }.toSet().size == commands.size)
    }
}

data class CommitDraft(
    val id: BookCommitId,
    val kind: CommitKind,
    val parentIds: List<BookCommitId>,
    val createdAt: Instant,
    val commandId: CommandId,
    val deviceInstanceId: DeviceInstanceId,
    val rootHash: Hash256,
)

data class JournalBundle(
    val entry: JournalEntry,
    val postings: List<Posting>,
) {
    init {
        require(postings.size == entry.postingCount)
        require(postings.all { it.journalEntryId == entry.id })
    }
}

data class FinancialMutationPlan(
    val commandId: CommandId,
    val commandType: FinancialCommandType,
    val payloadHash: Hash256,
    val expectedRevisionId: TransactionRevisionId?,
    val targetLocalRevision: LocalRevision,
    val commit: CommitDraft,
    val transactions: List<BusinessTransaction>,
    val revisions: List<TransactionRevision>,
    val revisionAmounts: List<RevisionAmount>,
    val fxRateSnapshots: List<FxRateSnapshot>,
    val journalBundles: List<JournalBundle>,
    val economicEffects: List<EconomicEffect>,
    val budgetEffects: List<BudgetEffect>,
    val projectEffects: List<ProjectEffect>,
    val goalEffects: List<GoalEffect>,
    val statementEffects: List<StatementEffect>,
    val loanEffects: List<LoanEffect>,
    val settlementEffects: List<SettlementEffect>,
    val refundAllocations: List<RefundAllocation>,
    val goalMovements: List<GoalMovement>,
    val budgetAdjustments: List<BudgetAdjustment>,
    val purgeTombstones: List<PurgeTombstone>,
    val blobGcCandidates: List<BlobGcCandidate>,
    val dependencyResolutions: List<DependencyResolution>,
    val projectionChanges: ProjectionChangeSet,
    val entityChanges: List<EntityChange>,
    val ruleSetVersion: RuleSetVersion,
)

data class PlanningSnapshot(
    val book: Book,
    val currentTransaction: BusinessTransaction?,
    val currentRevision: TransactionRevision?,
    val dependencies: List<TransactionDependency>,
    val reversedApplyEntryIds: Set<JournalEntryId>,
    val refundStatuses: List<RefundStatusProjection>,
    val budgetRevision: BudgetMonthRevision?,
    val participants: List<Participant>,
)

object FinancialMutationPlanValidator {
    @Suppress("ComplexCondition", "ReturnCount")
    fun validate(
        command: FinancialCommand,
        snapshot: PlanningSnapshot,
        plan: FinancialMutationPlan,
    ): DomainResult<FinancialMutationPlan> {
        val expectedNext = snapshot.book.localRevision.next()
        if (
            expectedNext !is DomainResult.Success ||
            plan.commandId != command.commandId ||
            plan.commandType != command.commandType ||
            plan.payloadHash != command.payloadHash ||
            plan.targetLocalRevision != expectedNext.value ||
            plan.commit.commandId != command.commandId ||
            plan.commit.parentIds != listOf(snapshot.book.headCommitId) ||
            plan.projectionChanges.targetRevision != plan.targetLocalRevision ||
            plan.ruleSetVersion != snapshot.book.ruleSetVersion ||
            plan.journalBundles.any { it.entry.ruleSetVersion != plan.ruleSetVersion } ||
            !hasRequiredWrites(command, plan)
        ) {
            return DomainResult.Failure(DomainViolation.InvalidField("mutationPlan.identityOrRevision"))
        }
        if (command.expectedRevisionId != snapshot.currentRevision?.id) {
            return DomainResult.Failure(DomainViolation.StaleExpectedRevision)
        }
        if (
            command is EditTransactionCommand &&
            snapshot.currentTransaction?.kind != command.replacement.payload.kind
        ) {
            return DomainResult.Failure(DomainViolation.InvalidField("editTransaction.kind"))
        }
        if (command is PurgeTransactionCommand && !command.eligibility.eligible) {
            return DomainResult.Failure(DomainViolation.InvalidStateTransition("purge"))
        }
        if (!dependencyPoliciesCover(snapshot.dependencies, plan.dependencyResolutions)) {
            return DomainResult.Failure(DomainViolation.InvalidField("mutationPlan.dependencies"))
        }
        val amountKeys = plan.revisionAmounts.map {
            listOf(it.revisionId, it.componentIndex, it.role, it.representation)
        }
        if (amountKeys.toSet().size != amountKeys.size) {
            return DomainResult.Failure(DomainViolation.InvalidField("mutationPlan.revisionAmounts"))
        }
        val reversedIds = plan.journalBundles.mapNotNull { it.entry.reversesEntryId }
        if (
            reversedIds.toSet().size != reversedIds.size ||
            reversedIds.any { it in snapshot.reversedApplyEntryIds }
        ) {
            return DomainResult.Failure(DomainViolation.Invariant("INV-007"))
        }
        if (!refundsWithinBalance(command, snapshot.refundStatuses)) {
            return DomainResult.Failure(DomainViolation.Invariant("INV-010"))
        }
        if (snapshot.budgetRevision != null) {
            val budgetResult = BudgetHierarchyPolicy.validate(
                snapshot.budgetRevision.totalBaseMinor,
                snapshot.budgetRevision.categoryLimits,
            )
            if (budgetResult is DomainResult.Failure) return budgetResult
        }
        val settlementGroups = plan.settlementEffects.groupBy { it.activityId to it.currency }
        for (effects in settlementGroups.values) {
            val settlementResult = SettlementSharePolicy.validateEffects(effects)
            if (settlementResult is DomainResult.Failure) return settlementResult
        }
        if (snapshot.participants.count { it.isSelf && it.status == EntityStatus.ACTIVE } > 1) {
            return DomainResult.Failure(DomainViolation.InvalidField("participant.self"))
        }
        return DomainResult.Success(plan)
    }

    private fun hasRequiredWrites(command: FinancialCommand, plan: FinancialMutationPlan): Boolean = when (command) {
        is RecordTransactionCommand<*> -> {
            val externalOnlySettlement = command is RecordSettlementPaymentCommand &&
                !command.input.payload.selfParticipates
            plan.transactions.isNotEmpty() &&
                plan.revisions.isNotEmpty() &&
                (externalOnlySettlement || plan.journalBundles.isNotEmpty())
        }
        is EditTransactionCommand,
        is MoveTransactionToTrashCommand,
        is RestoreTransactionCommand,
        -> plan.transactions.isNotEmpty() && plan.revisions.isNotEmpty() && plan.journalBundles.isNotEmpty()
        is PurgeTransactionCommand -> plan.purgeTombstones.any { tombstone ->
            tombstone.entity.stableId == command.transactionId.value
        } && plan.commit.kind == CommitKind.PURGE
        is RecordGoalMovementCommand -> plan.goalMovements.contains(command.movement) && plan.goalEffects.isNotEmpty()
        is RecordBudgetAdjustmentCommand -> plan.budgetAdjustments.contains(command.adjustment)
        is BatchFinancialCommand ->
            plan.commit.kind == CommitKind.BATCH_MUTATION &&
                (plan.revisions.isNotEmpty() || plan.goalMovements.isNotEmpty() || plan.budgetAdjustments.isNotEmpty())
    }

    private fun dependencyPoliciesCover(
        dependencies: List<TransactionDependency>,
        resolutions: List<DependencyResolution>,
    ): Boolean {
        val expected = dependencies.toSet()
        val actual = resolutions.map { it.dependency }.toSet()
        return expected == actual && resolutions.map { it.dependency }.toSet().size == resolutions.size
    }

    @Suppress("ReturnCount")
    private fun refundsWithinBalance(
        command: FinancialCommand,
        statuses: List<RefundStatusProjection>,
    ): Boolean {
        if (command !is RecordRefundCommand || command.input.payload.independent) return true
        val remainingByTransaction = statuses.associate { it.originalTransactionId to it.remainingMinor }
        for (allocation in command.input.payload.allocations) {
            val remaining = remainingByTransaction[allocation.originalTransactionId] ?: return false
            if (
                allocation.amountInOriginalCurrency.minor.value > remaining &&
                !command.input.payload.allowExcessOverride
            ) {
                return false
            }
        }
        return true
    }
}
