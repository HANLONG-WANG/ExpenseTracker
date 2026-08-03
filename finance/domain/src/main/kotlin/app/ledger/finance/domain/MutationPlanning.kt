package app.ledger.finance.domain

import app.ledger.core.common.CheckedArithmetic
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
    val revisionAction: RevisionAction = RevisionAction.EDIT,
) : FinancialCommand {
    override val commandType: FinancialCommandType = FinancialCommandType.EDIT_TRANSACTION

    init {
        require(revisionAction == RevisionAction.EDIT || revisionAction == RevisionAction.BULK_EDIT)
    }
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

/** Restores the content of an immutable historical revision by appending a new RESTORE revision. */
data class RestoreHistoricalRevisionCommand(
    override val commandId: CommandId,
    override val expectedRevisionId: TransactionRevisionId,
    override val payloadHash: Hash256,
    val transactionId: TransactionId,
    val sourceRevisionId: TransactionRevisionId,
    val replacement: NewTransactionInput<TransactionPayload>,
    val dependencyResolutions: List<DependencyResolution>,
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
    val accountingContext: AccountingPlanningContext? = null,
    /** Ordered child snapshots for an atomic [BatchFinancialCommand]. Empty for every scalar command. */
    val batchSnapshots: List<PlanningSnapshot> = emptyList(),
)

object FinancialMutationPlanValidator {
    @Suppress("ComplexCondition", "CyclomaticComplexMethod", "ReturnCount")
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
            plan.journalBundles.any {
                it.entry.role == JournalEntryRole.APPLY && it.entry.ruleSetVersion != plan.ruleSetVersion
            } ||
            !hasRequiredWrites(command, plan)
        ) {
            return DomainResult.Failure(DomainViolation.InvalidField("mutationPlan.identityOrRevision"))
        }
        if (command.expectedRevisionId != snapshot.currentRevision?.id) {
            return DomainResult.Failure(DomainViolation.StaleExpectedRevision)
        }
        if (
            command is BatchFinancialCommand &&
            (
                snapshot.batchSnapshots.size != command.commands.size ||
                    command.commands.zip(snapshot.batchSnapshots).any { (child, childSnapshot) ->
                        child.expectedRevisionId != childSnapshot.currentRevision?.id || childSnapshot.batchSnapshots.isNotEmpty()
                    }
                )
        ) {
            return DomainResult.Failure(DomainViolation.InvalidField("planningSnapshot.batchSnapshots"))
        }
        val replacementKind = when (command) {
            is EditTransactionCommand -> command.replacement.payload.kind
            is RestoreHistoricalRevisionCommand -> command.replacement.payload.kind
            else -> null
        }
        if (replacementKind != null && snapshot.currentTransaction?.kind != replacementKind) {
            return DomainResult.Failure(DomainViolation.InvalidField("editTransaction.kind"))
        }
        if (command is PurgeTransactionCommand && !command.eligibility.eligible) {
            return DomainResult.Failure(DomainViolation.InvalidStateTransition("purge"))
        }
        if (
            command is EditTransactionCommand || command is RestoreHistoricalRevisionCommand || command is MoveTransactionToTrashCommand
        ) {
            if (!dependencyPoliciesCover(snapshot.dependencies, plan.dependencyResolutions)) {
                return DomainResult.Failure(DomainViolation.InvalidField("mutationPlan.dependencies"))
            }
        } else if (plan.dependencyResolutions.isNotEmpty()) {
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
        val transactionLifecycle = validateTransactionLifecycle(command, snapshot, plan)
        if (transactionLifecycle is DomainResult.Failure) return transactionLifecycle
        if (command is BatchFinancialCommand) {
            val expectedRoot = CanonicalFinancialHash.commitRoot(
                plan.commandId,
                plan.payloadHash,
                plan.targetLocalRevision,
                plan.transactions.flatMapIndexed { index, transaction ->
                    val revision = plan.revisions.getOrNull(index)
                        ?: return DomainResult.Failure(DomainViolation.Invariant("INV-005"))
                    listOf(transaction.contentHash, revision.contentHash)
                } +
                    listOf(
                        CanonicalFinancialHash.evidenceAndEffects(
                            plan.revisionAmounts,
                            plan.fxRateSnapshots,
                            plan.economicEffects,
                            plan.budgetEffects,
                            plan.projectEffects,
                            plan.goalEffects,
                            plan.statementEffects,
                            plan.loanEffects,
                            plan.settlementEffects,
                        ),
                    ) + plan.journalBundles.map { it.entry.contentHash },
            )
            if (plan.transactions.size != plan.revisions.size || expectedRoot != plan.commit.rootHash) {
                return DomainResult.Failure(DomainViolation.InvalidField("bookCommit.rootHash"))
            }
        }
        return DomainResult.Success(plan)
    }

    @Suppress("ComplexCondition", "ReturnCount")
    private fun validateTransactionLifecycle(
        command: FinancialCommand,
        snapshot: PlanningSnapshot,
        plan: FinancialMutationPlan,
    ): DomainResult<Unit> {
        if (!command.isTransactionLifecycleCommand()) return DomainResult.Success(Unit)
        if (plan.transactions.size != 1 || plan.revisions.size != 1) {
            return DomainResult.Failure(DomainViolation.Invariant("INV-005"))
        }
        val transaction = plan.transactions.single()
        val revision = plan.revisions.single()
        if (
            transaction.currentRevisionId != revision.id ||
            transaction.id != revision.transactionId ||
            transaction.kind != revision.kind ||
            transaction.lifecycleState != revision.resultingState ||
            revision.source == TransactionSource.RECURRENCE_CANDIDATE
        ) {
            return DomainResult.Failure(DomainViolation.Invariant("INV-005"))
        }
        val expectedRoot = CanonicalFinancialHash.commitRoot(
            plan.commandId,
            plan.payloadHash,
            plan.targetLocalRevision,
            listOf(
                transaction.contentHash,
                revision.contentHash,
                CanonicalFinancialHash.evidenceAndEffects(
                    plan.revisionAmounts,
                    plan.fxRateSnapshots,
                    plan.economicEffects,
                    plan.budgetEffects,
                    plan.projectEffects,
                    plan.goalEffects,
                    plan.statementEffects,
                    plan.loanEffects,
                    plan.settlementEffects,
                ),
            ) +
                plan.journalBundles.map { it.entry.contentHash },
        )
        if (expectedRoot != plan.commit.rootHash) {
            return DomainResult.Failure(DomainViolation.InvalidField("bookCommit.rootHash"))
        }
        val roles = plan.journalBundles.map { it.entry.role }.toSet()
        val validRoles = when (command) {
            is RecordTransactionCommand<*>, is RestoreTransactionCommand -> roles.allOrEmpty(JournalEntryRole.APPLY)
            is EditTransactionCommand, is RestoreHistoricalRevisionCommand -> roles.all { it == JournalEntryRole.APPLY || it == JournalEntryRole.REVERSE }
            is MoveTransactionToTrashCommand -> roles.allOrEmpty(JournalEntryRole.REVERSE)
            else -> true
        }
        if (!validRoles) return DomainResult.Failure(DomainViolation.Invariant("INV-008"))
        if (command is EditTransactionCommand || command is RestoreHistoricalRevisionCommand || command is MoveTransactionToTrashCommand) {
            val original = snapshot.accountingContext?.currentFacts
                ?: return DomainResult.Failure(DomainViolation.InvalidField("planningSnapshot.currentFacts"))
            val audit = ImmutableFactAudit.validateReversal(original, plan)
            if (audit is DomainResult.Failure) return audit
        }
        if (command is RecordExpenseCommand || command is RecordIncomeCommand) {
            val primary = plan.revisionAmounts.filter { it.role == AmountRole.PRIMARY }
            val requiredRepresentations = setOf(
                AmountRepresentation.USER_INPUT,
                AmountRepresentation.ACCOUNT,
                AmountRepresentation.BASE,
            )
            if (
                primary.map { it.componentIndex }.toSet() != setOf(0) ||
                primary.map { it.representation }.toSet() != requiredRepresentations
            ) {
                return DomainResult.Failure(DomainViolation.Invariant("INV-004"))
            }
        }
        return DomainResult.Success(Unit)
    }

    private fun hasRequiredWrites(command: FinancialCommand, plan: FinancialMutationPlan): Boolean = when (command) {
        is RecordTransactionCommand<*> ->
            plan.transactions.isNotEmpty() && plan.revisions.isNotEmpty() && plan.hasFinancialFacts()
        is EditTransactionCommand,
        is RestoreHistoricalRevisionCommand,
        is MoveTransactionToTrashCommand,
        is RestoreTransactionCommand,
        -> plan.transactions.isNotEmpty() && plan.revisions.isNotEmpty() && plan.hasFinancialFacts()
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
        return expected == actual &&
            resolutions.map { it.dependency }.toSet().size == resolutions.size &&
            resolutions.all { it.policy.supports(it.dependency.type) }
    }

    @Suppress("ReturnCount")
    private fun refundsWithinBalance(
        command: FinancialCommand,
        statuses: List<RefundStatusProjection>,
    ): Boolean {
        if (command !is RecordRefundCommand || command.input.payload.independent) return true
        if (statuses.map { it.originalTransactionId }.toSet().size != statuses.size) return false
        if (statuses.any { status ->
                val remaining = CheckedArithmetic.subtract(
                    status.grossRefundable.minor.value,
                    status.refundedMinor,
                )
                remaining !is DomainResult.Success || remaining.value != status.remainingMinor
            }
        ) {
            return false
        }
        val remainingByTransaction = statuses.associate { it.originalTransactionId to it.remainingMinor }
        val currencyByTransaction = statuses.associate {
            it.originalTransactionId to it.grossRefundable.currency
        }
        for (allocation in command.input.payload.allocations) {
            val remaining = remainingByTransaction[allocation.originalTransactionId] ?: return false
            if (currencyByTransaction[allocation.originalTransactionId] != allocation.amountInOriginalCurrency.currency) {
                return false
            }
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

private fun FinancialMutationPlan.hasFinancialFacts(): Boolean = journalBundles.isNotEmpty() ||
    economicEffects.isNotEmpty() ||
    budgetEffects.isNotEmpty() ||
    projectEffects.isNotEmpty() ||
    goalEffects.isNotEmpty() ||
    statementEffects.isNotEmpty() ||
    loanEffects.isNotEmpty() ||
    settlementEffects.isNotEmpty()

private fun DependencyPolicy.supports(type: TransactionDependencyType): Boolean = when (this) {
    DependencyPolicy.ReverseDependentTransactions -> true
    DependencyPolicy.ConvertRefundToIndependent -> type == TransactionDependencyType.REFUND
    DependencyPolicy.CancelInstallmentPlan,
    is DependencyPolicy.RebindInstallmentPlan,
    -> type == TransactionDependencyType.INSTALLMENT_PLAN
    DependencyPolicy.RecalculateSettlement,
    DependencyPolicy.ReopenSettledActivity,
    -> type == TransactionDependencyType.SETTLEMENT_ACTIVITY
    DependencyPolicy.RegenerateCreditStatement -> type == TransactionDependencyType.CREDIT_STATEMENT
    DependencyPolicy.RecalculateLoanSchedule -> type == TransactionDependencyType.LOAN_SCHEDULE
}

private fun FinancialCommand.isTransactionLifecycleCommand(): Boolean = when (this) {
    is RecordTransactionCommand<*>,
    is EditTransactionCommand,
    is RestoreHistoricalRevisionCommand,
    is MoveTransactionToTrashCommand,
    is RestoreTransactionCommand,
    -> true
    else -> false
}

private fun Set<JournalEntryRole>.allOrEmpty(expected: JournalEntryRole): Boolean = isEmpty() || all { it == expected }
