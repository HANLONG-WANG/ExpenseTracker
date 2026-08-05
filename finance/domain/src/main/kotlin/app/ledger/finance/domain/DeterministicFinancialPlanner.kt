@file:Suppress("TooManyFunctions", "LargeClass")

package app.ledger.finance.domain

import app.ledger.core.common.CheckedArithmetic
import app.ledger.core.common.DomainError
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import java.time.LocalDate

/** Pure rule planner: it reads no clock, random source, network service or persistence API. */
@Suppress("TooManyFunctions")
object DeterministicFinancialPlanner {
    fun plan(
        command: FinancialCommand,
        snapshot: PlanningSnapshot,
    ): DomainResult<FinancialMutationPlan> = try {
        requireReadySnapshot(command, snapshot)
        require(
            CanonicalFinancialHash.command(command) == command.payloadHash,
            "financialCommand.payloadHash",
        )
        when (command) {
            is ApplyLoanPaymentCommand -> planCreate(command, snapshot)
            is ApplyInstallmentSettlementCommand -> planCreate(command, snapshot)
            is RecordTransactionCommand<*> -> planCreate(command, snapshot)
            is RecordGoalMovementCommand -> planGoalMovement(command, snapshot)
            is EditTransactionCommand -> planEdit(command, snapshot)
            is RestoreHistoricalRevisionCommand -> planHistoricalRestore(command, snapshot)
            is MoveTransactionToTrashCommand -> planTrash(command, snapshot)
            is RestoreTransactionCommand -> planRestore(command, snapshot)
            is BatchFinancialCommand -> planBatch(command, snapshot)
            is ConfigureBudgetMonthCommand,
            is SaveBudgetTemplateCommand,
            is RecordBudgetAdjustmentCommand,
            is RecordBudgetAdjustmentsCommand,
            -> planBudgetMutation(command, snapshot)
            is SaveCreditProfileCommand,
            is SaveCreditStatementCommand,
            -> planCreditMutation(command, snapshot)
            is SaveInstallmentPlanCommand -> planInstallmentMutation(command, snapshot)
            is SaveLoanContractCommand -> planLoanContractMutation(command, snapshot)
            else -> reject("financialCommand.transactionPlannerScope")
        }
    } catch (rejected: PlannerRejected) {
        DomainResult.Failure(rejected.violation)
    }

    private fun planGoalMovement(
        command: RecordGoalMovementCommand,
        snapshot: PlanningSnapshot,
    ): DomainResult<FinancialMutationPlan> = try {
        val operation = snapshot.operationContext ?: reject("planningSnapshot.operationContext")
        val goal = snapshot.goal ?: reject("planningSnapshot.goal")
        val target = snapshot.book.localRevision.next().orReject()
        require(goal.id == command.movement.goalId, "goalMovement.goalId")
        require(goal.rowVersion == command.expectedGoalRowVersion, "goalMovement.rowVersion")
        require(goal.status == GoalStatus.ACTIVE, "goalMovement.goalStatus")
        require(goal.currency == command.movement.amount.currency, "goalMovement.currency")
        require(command.movement.createdCommitId == operation.commitId, "goalMovement.createdCommitId")
        require(command.movement.sourceTransactionId == null, "goalMovement.sourceTransactionId")
        if (command.movement.kind == GoalMovementKind.RELEASE) {
            val balance = snapshot.goalBalanceMinor ?: reject("planningSnapshot.goalBalanceMinor")
            require(command.movement.amount.minor.value <= balance, "goalMovement.releaseAmount")
        }
        val kind = when (command.movement.kind) {
            GoalMovementKind.ALLOCATE -> GoalEffectKind.ALLOCATE
            GoalMovementKind.RELEASE -> GoalEffectKind.RELEASE
            GoalMovementKind.ADJUST -> GoalEffectKind.ADJUST
        }
        val effect = GoalEffect(
            id = command.effectId,
            goalId = goal.id,
            kind = kind,
            amount = command.movement.amount,
            sourceRevisionId = null,
            goalMovementId = command.movement.id,
            reversalOfId = null,
            polarity = EffectPolarity.APPLY,
        )
        val plan = FinancialMutationPlan(
            commandId = command.commandId,
            commandType = command.commandType,
            payloadHash = command.payloadHash,
            expectedRevisionId = null,
            targetLocalRevision = target,
            commit = CommitDraft(
                operation.commitId,
                CommitKind.USER_MUTATION,
                listOf(snapshot.book.headCommitId),
                operation.createdAt,
                command.commandId,
                operation.deviceInstanceId,
                CanonicalFinancialHash.commitRoot(command.commandId, command.payloadHash, target, emptyList()),
            ),
            transactions = emptyList(),
            revisions = emptyList(),
            revisionAmounts = emptyList(),
            fxRateSnapshots = emptyList(),
            journalBundles = emptyList(),
            economicEffects = emptyList(),
            budgetEffects = emptyList(),
            projectEffects = emptyList(),
            goalEffects = listOf(effect),
            statementEffects = emptyList(),
            loanEffects = emptyList(),
            settlementEffects = emptyList(),
            refundAllocations = emptyList(),
            goalMovements = listOf(command.movement),
            budgetAdjustments = emptyList(),
            purgeTombstones = emptyList(),
            blobGcCandidates = emptyList(),
            dependencyResolutions = emptyList(),
            projectionChanges = ProjectionChangeSet(
                target,
                listOf(ProjectionChange.Goal(goal.id, target), ProjectionChange.Widget(snapshot.book.id, target)),
            ),
            entityChanges = emptyList(),
            ruleSetVersion = snapshot.book.ruleSetVersion,
        )
        FinancialMutationPlanValidator.validate(command, snapshot, plan)
    } catch (rejected: PlannerRejected) {
        DomainResult.Failure(rejected.violation)
    }

    private fun planBudgetMutation(
        command: FinancialCommand,
        snapshot: PlanningSnapshot,
    ): DomainResult<FinancialMutationPlan> = try {
        val operation = snapshot.operationContext ?: reject("planningSnapshot.operationContext")
        val target = snapshot.book.localRevision.next().orReject()
        val monthMutations = if (command is ConfigureBudgetMonthCommand) listOf(command.mutation) else emptyList()
        val templateMutations = if (command is SaveBudgetTemplateCommand) listOf(command.mutation) else emptyList()
        val adjustments = when (command) {
            is RecordBudgetAdjustmentCommand -> listOf(command.adjustment)
            is RecordBudgetAdjustmentsCommand -> command.adjustments
            else -> emptyList()
        }
        require(monthMutations.all { it.revision.createdCommitId == operation.commitId }, "budgetMonth.createdCommitId")
        require(templateMutations.all { it.revision.createdCommitId == operation.commitId }, "budgetTemplate.createdCommitId")
        require(adjustments.all { it.createdCommitId == operation.commitId }, "budgetAdjustment.createdCommitId")
        val affectedMonth = monthMutations.firstOrNull()?.month?.month ?: adjustments.firstOrNull()?.month
        val plan = FinancialMutationPlan(
            commandId = command.commandId,
            commandType = command.commandType,
            payloadHash = command.payloadHash,
            expectedRevisionId = null,
            targetLocalRevision = target,
            commit = CommitDraft(
                operation.commitId,
                CommitKind.USER_MUTATION,
                listOf(snapshot.book.headCommitId),
                operation.createdAt,
                command.commandId,
                operation.deviceInstanceId,
                CanonicalFinancialHash.commitRoot(command.commandId, command.payloadHash, target, emptyList()),
            ),
            transactions = emptyList(), revisions = emptyList(), revisionAmounts = emptyList(), fxRateSnapshots = emptyList(),
            journalBundles = emptyList(), economicEffects = emptyList(), budgetEffects = emptyList(), projectEffects = emptyList(),
            goalEffects = emptyList(), statementEffects = emptyList(), loanEffects = emptyList(), settlementEffects = emptyList(),
            refundAllocations = emptyList(), goalMovements = emptyList(), budgetAdjustments = adjustments,
            purgeTombstones = emptyList(), blobGcCandidates = emptyList(), dependencyResolutions = emptyList(),
            projectionChanges = ProjectionChangeSet(
                target,
                affectedMonth?.let { listOf(ProjectionChange.BudgetFromMonth(it, target)) }.orEmpty(),
            ),
            entityChanges = emptyList(),
            ruleSetVersion = snapshot.book.ruleSetVersion,
            budgetMonthMutations = monthMutations,
            budgetTemplateMutations = templateMutations,
        )
        FinancialMutationPlanValidator.validate(command, snapshot, plan)
    } catch (rejected: PlannerRejected) {
        DomainResult.Failure(rejected.violation)
    }

    @Suppress("LongMethod")
    private fun planBatch(
        command: BatchFinancialCommand,
        snapshot: PlanningSnapshot,
    ): DomainResult<FinancialMutationPlan> = try {
        require(command.commands.size == snapshot.batchSnapshots.size, "planningSnapshot.batchSnapshots")
        val childPlans = command.commands.zip(snapshot.batchSnapshots).map { (child, childSnapshot) ->
            require(
                child is EditTransactionCommand || child is MoveTransactionToTrashCommand,
                "batchFinancialCommand.childType",
            )
            require(childSnapshot.book == snapshot.book, "batchFinancialCommand.book")
            plan(child, childSnapshot).orReject()
        }
        val commitIds = childPlans.map { it.commit.id }.toSet()
        val createdAt = childPlans.map { it.commit.createdAt }.toSet()
        val devices = childPlans.map { it.commit.deviceInstanceId }.toSet()
        require(commitIds.size == 1 && createdAt.size == 1 && devices.size == 1, "batchFinancialCommand.commitIdentity")
        val targetRevision = snapshot.book.localRevision.next().orReject()
        val transactions = childPlans.flatMap(FinancialMutationPlan::transactions)
        val revisions = childPlans.flatMap(FinancialMutationPlan::revisions)
        require(transactions.map { it.id }.toSet().size == transactions.size, "batchFinancialCommand.transactions")
        require(revisions.map { it.id }.toSet().size == revisions.size, "batchFinancialCommand.revisions")
        val revisionAmounts = childPlans.flatMap(FinancialMutationPlan::revisionAmounts)
        val fxRateSnapshots = childPlans.flatMap(FinancialMutationPlan::fxRateSnapshots)
        val journals = childPlans.flatMap(FinancialMutationPlan::journalBundles)
        val economic = childPlans.flatMap(FinancialMutationPlan::economicEffects)
        val budget = childPlans.flatMap(FinancialMutationPlan::budgetEffects)
        val project = childPlans.flatMap(FinancialMutationPlan::projectEffects)
        val goal = childPlans.flatMap(FinancialMutationPlan::goalEffects)
        val statement = childPlans.flatMap(FinancialMutationPlan::statementEffects)
        val loan = childPlans.flatMap(FinancialMutationPlan::loanEffects)
        val settlement = childPlans.flatMap(FinancialMutationPlan::settlementEffects)
        val refundAllocations = childPlans.flatMap(FinancialMutationPlan::refundAllocations)
        val evidenceHash = CanonicalFinancialHash.evidenceAndEffects(
            revisionAmounts,
            fxRateSnapshots,
            economic,
            budget,
            project,
            goal,
            statement,
            loan,
            settlement,
            refundAllocations,
        )
        val rootHash = CanonicalFinancialHash.commitRoot(
            command.commandId,
            command.payloadHash,
            targetRevision,
            transactions.flatMapIndexed { index, transaction ->
                listOf(transaction.contentHash, revisions[index].contentHash)
            } + listOf(evidenceHash) + journals.map { it.entry.contentHash },
        )
        val plan = FinancialMutationPlan(
            commandId = command.commandId,
            commandType = command.commandType,
            payloadHash = command.payloadHash,
            expectedRevisionId = null,
            targetLocalRevision = targetRevision,
            commit = CommitDraft(
                id = commitIds.single(),
                kind = CommitKind.BATCH_MUTATION,
                parentIds = listOf(snapshot.book.headCommitId),
                createdAt = createdAt.single(),
                commandId = command.commandId,
                deviceInstanceId = devices.single(),
                rootHash = rootHash,
            ),
            transactions = transactions,
            revisions = revisions,
            revisionAmounts = revisionAmounts,
            fxRateSnapshots = fxRateSnapshots,
            journalBundles = journals,
            economicEffects = economic,
            budgetEffects = budget,
            projectEffects = project,
            goalEffects = goal,
            statementEffects = statement,
            loanEffects = loan,
            settlementEffects = settlement,
            refundAllocations = refundAllocations,
            goalMovements = childPlans.flatMap(FinancialMutationPlan::goalMovements),
            budgetAdjustments = childPlans.flatMap(FinancialMutationPlan::budgetAdjustments),
            purgeTombstones = childPlans.flatMap(FinancialMutationPlan::purgeTombstones),
            blobGcCandidates = childPlans.flatMap(FinancialMutationPlan::blobGcCandidates),
            // Dependency policies are validated on the corresponding child snapshot. They are
            // execution instructions, not immutable financial facts of the aggregate batch.
            dependencyResolutions = emptyList(),
            projectionChanges = ProjectionChangeSet(
                targetRevision,
                childPlans.flatMap { it.projectionChanges.changes }.distinct(),
            ),
            entityChanges = childPlans.flatMap(FinancialMutationPlan::entityChanges),
            ruleSetVersion = snapshot.book.ruleSetVersion,
        )
        FinancialMutationPlanValidator.validate(command, snapshot, plan)
    } catch (rejected: PlannerRejected) {
        DomainResult.Failure(rejected.violation)
    }

    private fun planCreate(
        command: RecordTransactionCommand<*>,
        snapshot: PlanningSnapshot,
    ): DomainResult<FinancialMutationPlan> {
        require(snapshot.currentTransaction == null && snapshot.currentRevision == null, "create.currentTransaction")
        val input: NewTransactionInput<TransactionPayload> = command.input
        return planLifecycle(command, snapshot, input, RevisionAction.CREATE, TransactionLifecycleState.ACTIVE)
    }

    private fun planEdit(
        command: EditTransactionCommand,
        snapshot: PlanningSnapshot,
    ): DomainResult<FinancialMutationPlan> {
        val transaction = currentTransaction(command.transactionId, snapshot, TransactionLifecycleState.ACTIVE)
        require(transaction.kind == command.replacement.payload.kind, "editTransaction.kind")
        return planLifecycle(command, snapshot, command.replacement, command.revisionAction, TransactionLifecycleState.ACTIVE)
    }

    private fun planTrash(
        command: MoveTransactionToTrashCommand,
        snapshot: PlanningSnapshot,
    ): DomainResult<FinancialMutationPlan> {
        currentTransaction(command.transactionId, snapshot, TransactionLifecycleState.ACTIVE)
        val revision = snapshot.currentRevision ?: reject("trash.currentRevision")
        return planLifecycle(
            command,
            snapshot,
            revision.asInput(),
            RevisionAction.MOVE_TO_TRASH,
            TransactionLifecycleState.TRASHED,
        )
    }

    private fun planRestore(
        command: RestoreTransactionCommand,
        snapshot: PlanningSnapshot,
    ): DomainResult<FinancialMutationPlan> {
        currentTransaction(command.transactionId, snapshot, TransactionLifecycleState.TRASHED)
        val revision = snapshot.currentRevision ?: reject("restore.currentRevision")
        require(snapshot.accountingContext?.currentFacts == null, "restore.currentFacts")
        return planLifecycle(
            command,
            snapshot,
            revision.asInput(),
            RevisionAction.RESTORE,
            TransactionLifecycleState.ACTIVE,
        )
    }

    private fun planHistoricalRestore(
        command: RestoreHistoricalRevisionCommand,
        snapshot: PlanningSnapshot,
    ): DomainResult<FinancialMutationPlan> {
        val transaction = currentTransaction(command.transactionId, snapshot, TransactionLifecycleState.ACTIVE)
        require(transaction.kind == command.replacement.payload.kind, "restoreHistoricalRevision.kind")
        require(command.sourceRevisionId != snapshot.currentRevision?.id, "restoreHistoricalRevision.current")
        return planLifecycle(command, snapshot, command.replacement, RevisionAction.RESTORE, TransactionLifecycleState.ACTIVE)
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun planLifecycle(
        command: FinancialCommand,
        snapshot: PlanningSnapshot,
        input: NewTransactionInput<TransactionPayload>,
        action: RevisionAction,
        resultingState: TransactionLifecycleState,
    ): DomainResult<FinancialMutationPlan> = try {
        val context = snapshot.accountingContext ?: reject("planningSnapshot.accountingContext")
        val identities = context.identities
        val current = snapshot.currentTransaction
        val previousRevision = snapshot.currentRevision
        if (current != null) require(identities.transactionId == current.id, "planningIdentity.transactionId")
        val targetRevision = snapshot.book.localRevision.next().orReject()
        val rowVersion = current?.rowVersion?.next()?.orReject() ?: RowVersion.of(1L).orReject()
        val revisionNumber = nextRevisionNumber(previousRevision)
        val revisionHash = CanonicalFinancialHash.revision(
            id = identities.revisionId,
            transactionId = identities.transactionId,
            revisionNumber = revisionNumber,
            action = action,
            state = resultingState,
            previousRevisionId = previousRevision?.id,
            commitId = identities.commitId,
            createdAt = context.createdAt,
            input = input,
        )
        val revision = input.toRevision(
            id = identities.revisionId,
            transactionId = identities.transactionId,
            revisionNumber = revisionNumber,
            action = action,
            state = resultingState,
            previousRevisionId = previousRevision?.id,
            commitId = identities.commitId,
            createdAt = context.createdAt,
            contentHash = revisionHash,
        )
        val transaction = buildTransaction(
            current = current,
            revision = revision,
            state = resultingState,
            commitId = identities.commitId,
            rowVersion = rowVersion,
            trashedAt = if (resultingState == TransactionLifecycleState.TRASHED) context.createdAt else null,
            purgeAfter = (command as? MoveTransactionToTrashCommand)?.purgeAfter,
        )
        val cursor = PlanningIdCursor(identities.factIds)
        val reverseFacts = when (action) {
            RevisionAction.EDIT,
            RevisionAction.BULK_EDIT,
            RevisionAction.RESTORE,
            RevisionAction.MOVE_TO_TRASH,
            -> if (action == RevisionAction.RESTORE && current?.lifecycleState == TransactionLifecycleState.TRASHED) {
                MaterializedFacts.empty()
            } else {
                reverseCurrentFacts(snapshot, revision.id, identities.commitId, cursor)
            }
            else -> MaterializedFacts.empty()
        }
        val editAction = action == RevisionAction.EDIT || action == RevisionAction.BULK_EDIT
        val locationOnlyEdit = editAction && previousRevision?.isLocationOnlyReplacement(input) == true
        val categoryOnlyEdit = editAction && previousRevision?.isCategoryOnlyReplacement(input) == true
        val ruleResult = if (action == RevisionAction.MOVE_TO_TRASH || locationOnlyEdit || categoryOnlyEdit) {
            AccountingRuleOutput(journals = emptyList(), amounts = context.amountEvidence)
        } else {
            AccountingRuleEngine.plan(snapshot.book, input, snapshot).orReject()
        }
        val settlementPaymentRecord = (command as? RecordSettlementPaymentCommand)?.paymentRecord
        settlementPaymentRecord?.let { record ->
            val payload = input.payload as? SettlementPaymentPayload ?: reject("settlementPayment.payload")
            require(record.activityId == payload.activityId, "settlementPayment.activityId")
            require(record.payerParticipantId == payload.payerParticipantId, "settlementPayment.payer")
            require(record.payeeParticipantId == payload.payeeParticipantId, "settlementPayment.payee")
            require(record.amount == payload.amount, "settlementPayment.amount")
            require(record.occurredAt == input.context.occurredAt, "settlementPayment.occurredAt")
            require(record.selfParticipates == payload.selfParticipates, "settlementPayment.selfParticipates")
            require(record.createdCommitId == identities.commitId, "settlementPayment.createdCommitId")
            require(
                if (payload.selfParticipates) {
                    record.linkedTransactionId == identities.transactionId
                } else {
                    record.linkedTransactionId == null
                },
                "settlementPayment.linkedTransactionId",
            )
        }
        val applyFacts = if (resultingState == TransactionLifecycleState.ACTIVE) {
            if (locationOnlyEdit || categoryOnlyEdit) {
                cloneCurrentApplyFacts(
                    snapshot,
                    revision,
                    identities.commitId,
                    cursor,
                    if (categoryOnlyEdit) revision.payload.classification else null,
                )
            } else {
                materializeApplyFacts(
                    snapshot,
                    revision,
                    ruleResult,
                    identities.commitId,
                    cursor,
                    settlementPaymentRecord?.id,
                )
            }
        } else {
            MaterializedFacts.empty()
        }
        val facts = reverseFacts + applyFacts
        val revisionAmounts = materializeRevisionAmounts(revision.id, revision.payload, ruleResult.amounts)
        val fxSnapshots = materializeFxSnapshots(identities.commitId, ruleResult.amounts)
        val projectionChanges = projectionChanges(snapshot, transaction, revision, facts, targetRevision)
        val evidenceAndEffectsHash = CanonicalFinancialHash.evidenceAndEffects(
            revisionAmounts,
            fxSnapshots,
            facts.economic,
            facts.budget,
            facts.project,
            facts.goal,
            facts.statement,
            facts.loan,
            facts.settlement,
            facts.refundAllocations,
        )
        val contentHashes = listOf(transaction.contentHash, revision.contentHash, evidenceAndEffectsHash) +
            facts.journals.map { it.entry.contentHash }
        val rootHash = CanonicalFinancialHash.commitRoot(
            command.commandId,
            command.payloadHash,
            targetRevision,
            contentHashes,
        )
        val dependencyResolutions = when (command) {
            is EditTransactionCommand -> command.dependencyResolutions
            is RestoreHistoricalRevisionCommand -> command.dependencyResolutions
            is MoveTransactionToTrashCommand -> command.dependencyResolutions
            else -> emptyList()
        }
        val installmentMutations = (command as? ApplyInstallmentSettlementCommand)?.let { listOf(it.mutation) }.orEmpty()
        val loanMutations = (command as? ApplyLoanPaymentCommand)?.let { listOf(it.mutation) }.orEmpty()
        require(
            installmentMutations.all { mutation ->
                mutation.revision.createdCommitId == identities.commitId &&
                    mutation.scheduleRevision.createdCommitId == identities.commitId &&
                    mutation.settlementTransactionId == identities.transactionId
            },
            "installmentSettlement.mutation",
        )
        require(
            loanMutations.flatMap { it.tranches }.all { mutation ->
                mutation.termsRevision.createdCommitId == identities.commitId &&
                    mutation.scheduleRevision.createdCommitId == identities.commitId
            } && loanMutations.all { it.contract.lastCommitId == identities.commitId },
            "loanPayment.mutation",
        )
        val plan = FinancialMutationPlan(
            commandId = command.commandId,
            commandType = command.commandType,
            payloadHash = command.payloadHash,
            expectedRevisionId = command.expectedRevisionId,
            targetLocalRevision = targetRevision,
            commit = CommitDraft(
                id = identities.commitId,
                kind = CommitKind.USER_MUTATION,
                parentIds = listOf(snapshot.book.headCommitId),
                createdAt = context.createdAt,
                commandId = command.commandId,
                deviceInstanceId = context.deviceInstanceId,
                rootHash = rootHash,
            ),
            transactions = listOf(transaction),
            revisions = listOf(revision),
            revisionAmounts = revisionAmounts,
            fxRateSnapshots = fxSnapshots,
            journalBundles = facts.journals,
            economicEffects = facts.economic,
            budgetEffects = facts.budget,
            projectEffects = facts.project,
            goalEffects = facts.goal,
            statementEffects = facts.statement,
            loanEffects = facts.loan,
            settlementEffects = facts.settlement,
            refundAllocations = facts.refundAllocations,
            goalMovements = emptyList(),
            budgetAdjustments = emptyList(),
            purgeTombstones = emptyList(),
            blobGcCandidates = emptyList(),
            dependencyResolutions = dependencyResolutions,
            projectionChanges = ProjectionChangeSet(
                targetRevision,
                projectionChanges.changes +
                    installmentMutations.map { ProjectionChange.Installment(it.plan.id, targetRevision) } +
                    loanMutations.map { ProjectionChange.Loan(it.contract.id, targetRevision) },
            ),
            entityChanges = listOf(transactionEntityChange(current, transaction, revision)) +
                installmentMutations.map { mutation ->
                    EntityChange(
                        identities.commitId,
                        StableEntityReference(EntityType.INSTALLMENT_PLAN, mutation.plan.id.value),
                        EntityChangeOperation.UPDATE,
                        null,
                        ContentHash(command.payloadHash),
                        EntityRevisionId(mutation.revision.id.value),
                    )
                } + loanMutations.map { mutation ->
                    EntityChange(
                        identities.commitId,
                        StableEntityReference(EntityType.LOAN, mutation.contract.id.value),
                        EntityChangeOperation.UPDATE,
                        null,
                        ContentHash(command.payloadHash),
                        EntityRevisionId(mutation.tranches.first().termsRevision.id.value),
                    )
                },
            ruleSetVersion = snapshot.book.ruleSetVersion,
            installmentPlanMutations = installmentMutations,
            loanContractMutations = loanMutations,
            settlementPaymentRecords = settlementPaymentRecord?.let(::listOf).orEmpty(),
        )
        FinancialMutationPlanValidator.validate(command, snapshot, plan)
    } catch (rejected: PlannerRejected) {
        DomainResult.Failure(rejected.violation)
    }

    private fun requireReadySnapshot(command: FinancialCommand, snapshot: PlanningSnapshot) {
        require(snapshot.book.state == BookState.READY, "book.state")
        if (command.expectedRevisionId != snapshot.currentRevision?.id) {
            throw PlannerRejected(DomainViolation.StaleExpectedRevision)
        }
        val transaction = snapshot.currentTransaction
        val revision = snapshot.currentRevision
        require((transaction == null) == (revision == null), "planningSnapshot.current")
        if (transaction != null && revision != null) {
            require(transaction.currentRevisionId == revision.id, "planningSnapshot.currentRevision")
            require(transaction.id == revision.transactionId, "planningSnapshot.transactionId")
            require(transaction.lifecycleState == revision.resultingState, "planningSnapshot.lifecycleState")
        }
    }

    private fun currentTransaction(
        transactionId: TransactionId,
        snapshot: PlanningSnapshot,
        expectedState: TransactionLifecycleState,
    ): BusinessTransaction {
        val transaction = snapshot.currentTransaction ?: reject("planningSnapshot.currentTransaction")
        require(transaction.id == transactionId, "financialCommand.transactionId")
        require(transaction.lifecycleState == expectedState, "businessTransaction.lifecycleState")
        return transaction
    }

    private fun nextRevisionNumber(previous: TransactionRevision?): Int = if (previous == null) {
        1
    } else {
        try {
            Math.addExact(previous.revisionNumber, 1)
        } catch (_: ArithmeticException) {
            throw PlannerRejected(DomainViolation.NumericOverflow("transactionRevision.revisionNumber"))
        }
    }

    @Suppress("LongParameterList")
    private fun buildTransaction(
        current: BusinessTransaction?,
        revision: TransactionRevision,
        state: TransactionLifecycleState,
        commitId: BookCommitId,
        rowVersion: RowVersion,
        trashedAt: java.time.Instant?,
        purgeAfter: java.time.Instant?,
    ): BusinessTransaction {
        val createdCommitId = current?.createdCommitId ?: commitId
        val hash = CanonicalFinancialHash.transaction(
            id = revision.transactionId,
            kind = revision.kind,
            currentRevisionId = revision.id,
            state = state,
            createdCommitId = createdCommitId,
            lastCommitId = commitId,
            rowVersion = rowVersion,
            trashedAt = trashedAt,
            purgeAfter = purgeAfter,
        )
        return BusinessTransaction(
            id = revision.transactionId,
            kind = revision.kind,
            currentRevisionId = revision.id,
            lifecycleState = state,
            createdCommitId = createdCommitId,
            lastCommitId = commitId,
            rowVersion = rowVersion,
            trashedAt = trashedAt,
            purgeAfter = purgeAfter,
            contentHash = hash,
        )
    }

    private fun transactionEntityChange(
        before: BusinessTransaction?,
        after: BusinessTransaction,
        revision: TransactionRevision,
    ): EntityChange = EntityChange(
        commitId = after.lastCommitId,
        entity = StableEntityReference(EntityType.TRANSACTION, after.id.value),
        operation = if (before == null) EntityChangeOperation.CREATE else EntityChangeOperation.UPDATE,
        beforeHash = before?.contentHash,
        afterHash = after.contentHash,
        entityRevisionId = EntityRevisionId(revision.id.value),
    )
}

private fun planCreditMutation(
    command: FinancialCommand,
    snapshot: PlanningSnapshot,
): DomainResult<FinancialMutationPlan> = try {
    val operation = snapshot.operationContext ?: reject("planningSnapshot.operationContext")
    val target = snapshot.book.localRevision.next().orReject()
    val profiles = (command as? SaveCreditProfileCommand)?.let { listOf(it.mutation) }.orEmpty()
    val statements = (command as? SaveCreditStatementCommand)?.let { listOf(it.mutation) }.orEmpty()
    require(profiles.all { it.profile.lastCommitId == operation.commitId }, "creditProfile.lastCommitId")
    require(
        profiles.all { it.limitPeriod?.createdCommitId == null || it.limitPeriod.createdCommitId == operation.commitId },
        "creditLimitPeriod.createdCommitId",
    )
    require(statements.all { it.revision.createdCommitId == operation.commitId }, "creditStatement.createdCommitId")
    val entity = profiles.firstOrNull()?.profile?.accountId?.value?.let { StableEntityReference(EntityType.ACCOUNT, it) }
        ?: statements.single().statement.id.value.let { StableEntityReference(EntityType.CREDIT_STATEMENT, it) }
    val plan = FinancialMutationPlan(
        commandId = command.commandId,
        commandType = command.commandType,
        payloadHash = command.payloadHash,
        expectedRevisionId = null,
        targetLocalRevision = target,
        commit = CommitDraft(
            operation.commitId,
            CommitKind.USER_MUTATION,
            listOf(snapshot.book.headCommitId),
            operation.createdAt,
            command.commandId,
            operation.deviceInstanceId,
            CanonicalFinancialHash.commitRoot(command.commandId, command.payloadHash, target, emptyList()),
        ),
        transactions = emptyList(), revisions = emptyList(), revisionAmounts = emptyList(), fxRateSnapshots = emptyList(),
        journalBundles = emptyList(), economicEffects = emptyList(), budgetEffects = emptyList(), projectEffects = emptyList(),
        goalEffects = emptyList(), statementEffects = emptyList(), loanEffects = emptyList(), settlementEffects = emptyList(),
        refundAllocations = emptyList(), goalMovements = emptyList(), budgetAdjustments = emptyList(),
        purgeTombstones = emptyList(), blobGcCandidates = emptyList(), dependencyResolutions = emptyList(),
        projectionChanges = ProjectionChangeSet(
            target,
            statements.map { ProjectionChange.Statement(it.statement.id, target) } +
                listOf(ProjectionChange.Widget(snapshot.book.id, target)),
        ),
        entityChanges = listOf(
            EntityChange(
                operation.commitId,
                entity,
                if (profiles.singleOrNull()?.expectedLastCommitId == null && statements.singleOrNull()?.expectedRevisionId == null) {
                    EntityChangeOperation.CREATE
                } else {
                    EntityChangeOperation.UPDATE
                },
                null,
                ContentHash(command.payloadHash),
                null,
            ),
        ),
        ruleSetVersion = snapshot.book.ruleSetVersion,
        creditProfileMutations = profiles,
        creditStatementMutations = statements,
    )
    FinancialMutationPlanValidator.validate(command, snapshot, plan)
} catch (rejected: PlannerRejected) {
    DomainResult.Failure(rejected.violation)
}

private fun planInstallmentMutation(
    command: SaveInstallmentPlanCommand,
    snapshot: PlanningSnapshot,
): DomainResult<FinancialMutationPlan> = try {
    val operation = snapshot.operationContext ?: reject("planningSnapshot.operationContext")
    val mutation = command.mutation
    val target = snapshot.book.localRevision.next().orReject()
    require(mutation.revision.createdCommitId == operation.commitId, "installmentPlan.createdCommitId")
    require(mutation.scheduleRevision.createdCommitId == operation.commitId, "installmentSchedule.createdCommitId")
    require(mutation.refundAllocation?.let { it.planId == mutation.plan.id } != false, "installmentRefund.planId")
    val plan = FinancialMutationPlan(
        commandId = command.commandId,
        commandType = command.commandType,
        payloadHash = command.payloadHash,
        expectedRevisionId = null,
        targetLocalRevision = target,
        commit = CommitDraft(
            operation.commitId,
            CommitKind.USER_MUTATION,
            listOf(snapshot.book.headCommitId),
            operation.createdAt,
            command.commandId,
            operation.deviceInstanceId,
            CanonicalFinancialHash.commitRoot(command.commandId, command.payloadHash, target, emptyList()),
        ),
        transactions = emptyList(), revisions = emptyList(), revisionAmounts = emptyList(), fxRateSnapshots = emptyList(),
        journalBundles = emptyList(), economicEffects = emptyList(), budgetEffects = emptyList(), projectEffects = emptyList(),
        goalEffects = emptyList(), statementEffects = emptyList(), loanEffects = emptyList(), settlementEffects = emptyList(),
        refundAllocations = emptyList(), goalMovements = emptyList(), budgetAdjustments = emptyList(),
        purgeTombstones = emptyList(), blobGcCandidates = emptyList(), dependencyResolutions = emptyList(),
        projectionChanges = ProjectionChangeSet(
            target,
            listOf(ProjectionChange.Installment(mutation.plan.id, target), ProjectionChange.Widget(snapshot.book.id, target)),
        ),
        entityChanges = listOf(
            EntityChange(
                operation.commitId,
                StableEntityReference(EntityType.INSTALLMENT_PLAN, mutation.plan.id.value),
                if (mutation.expectedRevisionId == null) EntityChangeOperation.CREATE else EntityChangeOperation.UPDATE,
                null,
                ContentHash(command.payloadHash),
                EntityRevisionId(mutation.revision.id.value),
            ),
        ),
        ruleSetVersion = snapshot.book.ruleSetVersion,
        installmentPlanMutations = listOf(mutation),
    )
    FinancialMutationPlanValidator.validate(command, snapshot, plan)
} catch (rejected: PlannerRejected) {
    DomainResult.Failure(rejected.violation)
}

private fun planLoanContractMutation(
    command: SaveLoanContractCommand,
    snapshot: PlanningSnapshot,
): DomainResult<FinancialMutationPlan> = try {
    val operation = snapshot.operationContext ?: reject("planningSnapshot.operationContext")
    val mutation = command.mutation
    val target = snapshot.book.localRevision.next().orReject()
    require(mutation.contract.lastCommitId == operation.commitId, "loanContract.lastCommitId")
    require(
        mutation.tranches.all {
            it.termsRevision.createdCommitId == operation.commitId &&
                it.scheduleRevision.createdCommitId == operation.commitId
        },
        "loanContract.createdCommitId",
    )
    val plan = FinancialMutationPlan(
        commandId = command.commandId,
        commandType = command.commandType,
        payloadHash = command.payloadHash,
        expectedRevisionId = null,
        targetLocalRevision = target,
        commit = CommitDraft(
            operation.commitId,
            CommitKind.USER_MUTATION,
            listOf(snapshot.book.headCommitId),
            operation.createdAt,
            command.commandId,
            operation.deviceInstanceId,
            CanonicalFinancialHash.commitRoot(command.commandId, command.payloadHash, target, emptyList()),
        ),
        transactions = emptyList(), revisions = emptyList(), revisionAmounts = emptyList(), fxRateSnapshots = emptyList(),
        journalBundles = emptyList(), economicEffects = emptyList(), budgetEffects = emptyList(), projectEffects = emptyList(),
        goalEffects = emptyList(), statementEffects = emptyList(), loanEffects = emptyList(), settlementEffects = emptyList(),
        refundAllocations = emptyList(), goalMovements = emptyList(), budgetAdjustments = emptyList(),
        purgeTombstones = emptyList(), blobGcCandidates = emptyList(), dependencyResolutions = emptyList(),
        projectionChanges = ProjectionChangeSet(
            target,
            listOf(ProjectionChange.Loan(mutation.contract.id, target), ProjectionChange.Widget(snapshot.book.id, target)),
        ),
        entityChanges = listOf(
            EntityChange(
                operation.commitId,
                StableEntityReference(EntityType.LOAN, mutation.contract.id.value),
                if (mutation.expectedLastCommitId == null) EntityChangeOperation.CREATE else EntityChangeOperation.UPDATE,
                null,
                ContentHash(command.payloadHash),
                EntityRevisionId(mutation.tranches.first().termsRevision.id.value),
            ),
        ),
        ruleSetVersion = snapshot.book.ruleSetVersion,
        loanContractMutations = listOf(mutation),
    )
    FinancialMutationPlanValidator.validate(command, snapshot, plan)
} catch (rejected: PlannerRejected) {
    DomainResult.Failure(rejected.violation)
}

private data class MaterializedFacts(
    val journals: List<JournalBundle>,
    val economic: List<EconomicEffect>,
    val budget: List<BudgetEffect>,
    val project: List<ProjectEffect>,
    val goal: List<GoalEffect>,
    val statement: List<StatementEffect>,
    val loan: List<LoanEffect>,
    val settlement: List<SettlementEffect>,
    val refundAllocations: List<RefundAllocationFact>,
) {
    operator fun plus(other: MaterializedFacts): MaterializedFacts = MaterializedFacts(
        journals + other.journals,
        economic + other.economic,
        budget + other.budget,
        project + other.project,
        goal + other.goal,
        statement + other.statement,
        loan + other.loan,
        settlement + other.settlement,
        refundAllocations + other.refundAllocations,
    )

    companion object {
        fun empty(): MaterializedFacts = MaterializedFacts(
            emptyList(),
            emptyList(),
            emptyList(),
            emptyList(),
            emptyList(),
            emptyList(),
            emptyList(),
            emptyList(),
            emptyList(),
        )
    }
}

@Suppress("LongMethod", "LongParameterList")
private fun materializeApplyFacts(
    snapshot: PlanningSnapshot,
    revision: TransactionRevision,
    output: AccountingRuleOutput,
    commitId: BookCommitId,
    cursor: PlanningIdCursor,
    settlementPaymentRecordId: SettlementPaymentRecordId? = null,
): MaterializedFacts {
    val journals = output.journals.map { spec ->
        materializeJournal(snapshot, revision, spec, commitId, cursor)
    }
    val context = revision.context()
    val references = snapshot.accountingContext?.references ?: reject("planningSnapshot.accountingContext")
    val category = revision.payload.classification?.let { assignment ->
        references.category(assignment.categoryId) ?: reject("category.reference")
    }
    val economic = output.economic.map { spec ->
        EconomicEffect(
            id = EconomicEffectId(cursor.next()),
            sourceEntryId = journals.getOrNull(spec.journalIndex)?.entry?.id ?: reject("economicEffect.sourceEntry"),
            sourceRevisionId = revision.id,
            reversalOfId = null,
            polarity = EffectPolarity.APPLY,
            nature = spec.nature,
            component = spec.component,
            isConsumption = spec.isConsumption,
            baseAmount = spec.baseAmount,
            accrualDate = revision.accrualDate,
            categoryId = revision.categoryId,
            merchantId = revision.merchantId,
            projectId = revision.projectId,
            ruleSetVersion = snapshot.book.ruleSetVersion,
        )
    }
    val budget = output.budget.map { spec ->
        BudgetEffect(
            id = BudgetEffectId(cursor.next()),
            sourceRevisionId = revision.id,
            reversalOfId = null,
            polarity = EffectPolarity.APPLY,
            kind = spec.kind,
            targetMonth = revision.budgetMonth ?: reject("budgetEffect.targetMonth"),
            categoryId = revision.categoryId,
            rootCategoryId = category?.rootId,
            baseAmount = spec.baseAmount,
            ruleSetVersion = snapshot.book.ruleSetVersion,
        )
    }
    val project = output.project.map { spec ->
        ProjectEffect(
            id = ProjectEffectId(cursor.next()),
            projectId = context.projectId ?: reject("projectEffect.projectId"),
            kind = spec.kind,
            baseAmount = spec.baseAmount,
            includedInMonthlyBudgetSnapshot = spec.includedInMonthlyBudgetSnapshot,
            sourceRevisionId = revision.id,
            reversalOfId = null,
            polarity = EffectPolarity.APPLY,
        )
    }
    val goal = output.goal.map { spec ->
        GoalEffect(
            id = GoalEffectId(cursor.next()),
            goalId = context.goalId ?: reject("goalEffect.goalId"),
            kind = spec.kind,
            amount = spec.amount,
            sourceRevisionId = revision.id,
            goalMovementId = null,
            reversalOfId = null,
            polarity = EffectPolarity.APPLY,
        )
    }
    val statement = output.statement.map { spec ->
        StatementEffect(
            id = StatementEffectId(cursor.next()),
            creditAccountId = spec.creditAccountId,
            statementId = spec.statementId,
            sourceRevisionId = revision.id,
            reversalOfId = null,
            kind = spec.kind,
            polarity = EffectPolarity.APPLY,
            amount = spec.amount,
            manualAssignment = spec.manualAssignment,
        )
    }
    val loan = output.loan.map { spec ->
        LoanEffect(
            id = LoanEffectId(cursor.next()),
            loanContractId = spec.contractId,
            loanTrancheId = spec.trancheId,
            scheduleItemId = spec.scheduleItemId,
            sourceRevisionId = revision.id,
            reversalOfId = null,
            kind = spec.kind,
            polarity = EffectPolarity.APPLY,
            amount = spec.amount,
            baseAmount = spec.baseAmount,
        )
    }
    val settlement = output.settlement.map { spec ->
        SettlementEffect(
            id = SettlementEffectId(cursor.next()),
            activityId = spec.activityId,
            participantId = spec.participantId,
            sourceRevisionId = if (settlementPaymentRecordId == null) revision.id else null,
            settlementPaymentRecordId = settlementPaymentRecordId,
            reversalOfId = null,
            kind = spec.kind,
            signedDeltaMinor = spec.signedDeltaMinor,
            currency = spec.currency,
        )
    }
    val refundAllocations = (revision.payload as? RefundPayload)?.allocations.orEmpty().map { allocation ->
        RefundAllocationFact(
            refundTransactionId = revision.transactionId,
            refundRevisionId = revision.id,
            originalTransactionId = allocation.originalTransactionId,
            originalRevisionId = allocation.originalRevisionId,
            amountInOriginalCurrency = allocation.amountInOriginalCurrency,
            amountInBaseCurrency = allocation.amountInBaseCurrency,
            createdCommitId = commitId,
            reversalOf = null,
        )
    }
    return MaterializedFacts(journals, economic, budget, project, goal, statement, loan, settlement, refundAllocations)
}

private fun materializeJournal(
    snapshot: PlanningSnapshot,
    revision: TransactionRevision,
    spec: JournalSpec,
    commitId: BookCommitId,
    cursor: PlanningIdCursor,
): JournalBundle {
    val entryId = JournalEntryId(cursor.next())
    val postings = spec.postings.mapIndexed { index, posting ->
        Posting.create(
            id = PostingId(cursor.next()),
            journalEntryId = entryId,
            lineNumber = index + 1,
            ledgerAccount = posting.ledger,
            side = posting.side,
            accountAmount = posting.accountAmount,
            baseAmount = posting.baseAmount,
            baseCurrency = snapshot.book.baseCurrency,
            valuationRate = posting.valuationRate,
            role = posting.role,
            reversalOfPostingId = null,
        ).orReject()
    }
    val hash = CanonicalFinancialHash.journal(
        entryId,
        revision.id,
        revision.id,
        JournalEntryRole.APPLY,
        null,
        revision.occurredAt,
        snapshot.book.baseCurrency,
        postings,
        snapshot.book.ruleSetVersion,
        commitId,
    )
    val entry = JournalEntry.create(
        id = entryId,
        sourceRevisionId = revision.id,
        appliesRevisionId = revision.id,
        role = JournalEntryRole.APPLY,
        reversesEntryId = null,
        effectiveAt = revision.occurredAt,
        baseCurrency = snapshot.book.baseCurrency,
        postings = postings,
        ruleSetVersion = snapshot.book.ruleSetVersion,
        createdCommitId = commitId,
        contentHash = hash,
    ).orReject()
    return JournalBundle(entry, postings)
}

/**
 * A location-record split changes descriptive context only. The old APPLY facts are first reversed,
 * then reproduced byte-for-byte in economic meaning with fresh immutable identities. This keeps
 * archived ledgers usable for historical correction and never consults a current FX rate.
 */
@Suppress("LongMethod")
private fun cloneCurrentApplyFacts(
    snapshot: PlanningSnapshot,
    revision: TransactionRevision,
    commitId: BookCommitId,
    cursor: PlanningIdCursor,
    replacementClassification: CategoryAssignment? = null,
): MaterializedFacts {
    val context = snapshot.accountingContext ?: reject("planningSnapshot.accountingContext")
    val current = context.currentFacts ?: reject("planningSnapshot.currentFacts")
    val clonedEntries = current.journalBundles.map { original ->
        val entryId = JournalEntryId(cursor.next())
        val postings = original.postings.sortedBy { it.lineNumber }.mapIndexed { index, posting ->
            val ledger = replacementClassification?.replacementSystemLedger(posting, snapshot, context.references)
                ?: context.references.ledger(posting.ledgerAccountId)
                ?: reject("posting.ledgerAccount")
            Posting.reapply(
                id = PostingId(cursor.next()),
                journalEntryId = entryId,
                lineNumber = index + 1,
                original = posting,
                ledgerAccount = ledger,
                baseCurrency = snapshot.book.baseCurrency,
            ).orReject()
        }
        val hash = CanonicalFinancialHash.journal(
            entryId,
            revision.id,
            revision.id,
            JournalEntryRole.APPLY,
            null,
            revision.occurredAt,
            snapshot.book.baseCurrency,
            postings,
            original.entry.ruleSetVersion,
            commitId,
        )
        val entry = JournalEntry.create(
            id = entryId,
            sourceRevisionId = revision.id,
            appliesRevisionId = revision.id,
            role = JournalEntryRole.APPLY,
            reversesEntryId = null,
            effectiveAt = revision.occurredAt,
            baseCurrency = snapshot.book.baseCurrency,
            postings = postings,
            ruleSetVersion = original.entry.ruleSetVersion,
            createdCommitId = commitId,
            contentHash = hash,
        ).orReject()
        original.entry.id to JournalBundle(entry, postings)
    }
    fun clonedEntryId(original: JournalEntryId): JournalEntryId = clonedEntries.singleOrNull { it.first == original }
        ?.second?.entry?.id ?: reject("effect.sourceEntry")
    return MaterializedFacts(
        journals = clonedEntries.map { it.second },
        economic = current.economicEffects.map { effect ->
            effect.copy(
                id = EconomicEffectId(cursor.next()),
                sourceEntryId = clonedEntryId(effect.sourceEntryId),
                sourceRevisionId = revision.id,
                reversalOfId = null,
                polarity = EffectPolarity.APPLY,
                isConsumption = replacementClassification?.let {
                    it.statisticalNatureSnapshot == StatisticalNature.CONSUMPTION_EXPENSE
                } ?: effect.isConsumption,
                categoryId = replacementClassification?.categoryId ?: effect.categoryId,
            )
        },
        budget = current.budgetEffects.map { effect ->
            val category = replacementClassification?.let { assignment ->
                context.references.category(assignment.categoryId) ?: reject("category.reference")
            }
            effect.copy(
                id = BudgetEffectId(cursor.next()),
                sourceRevisionId = revision.id,
                reversalOfId = null,
                polarity = EffectPolarity.APPLY,
                categoryId = replacementClassification?.categoryId ?: effect.categoryId,
                rootCategoryId = category?.rootId ?: effect.rootCategoryId,
            )
        },
        project = current.projectEffects.map { effect ->
            effect.copy(id = ProjectEffectId(cursor.next()), sourceRevisionId = revision.id, reversalOfId = null, polarity = EffectPolarity.APPLY)
        },
        goal = current.goalEffects.map { effect ->
            effect.copy(id = GoalEffectId(cursor.next()), sourceRevisionId = revision.id, goalMovementId = null, reversalOfId = null, polarity = EffectPolarity.APPLY)
        },
        statement = current.statementEffects.map { effect ->
            effect.copy(id = StatementEffectId(cursor.next()), sourceRevisionId = revision.id, reversalOfId = null, polarity = EffectPolarity.APPLY)
        },
        loan = current.loanEffects.map { effect ->
            effect.copy(id = LoanEffectId(cursor.next()), sourceRevisionId = revision.id, reversalOfId = null, polarity = EffectPolarity.APPLY)
        },
        settlement = current.settlementEffects.map { effect ->
            effect.copy(id = SettlementEffectId(cursor.next()), sourceRevisionId = revision.id, settlementPaymentRecordId = null, reversalOfId = null)
        },
        refundAllocations = current.refundAllocationFacts.map { allocation ->
            allocation.copy(refundRevisionId = revision.id, createdCommitId = commitId, reversalOf = null)
        },
    )
}

@Suppress("LongMethod")
private fun reverseCurrentFacts(
    snapshot: PlanningSnapshot,
    sourceRevisionId: TransactionRevisionId,
    commitId: BookCommitId,
    cursor: PlanningIdCursor,
): MaterializedFacts {
    val context = snapshot.accountingContext ?: reject("planningSnapshot.accountingContext")
    val current = context.currentFacts ?: reject("planningSnapshot.currentFacts")
    val reversedEntries = mutableListOf<Pair<JournalEntryId, JournalBundle>>()
    current.journalBundles.forEach { original ->
        require(original.entry.id !in snapshot.reversedApplyEntryIds, "journalEntry.alreadyReversed")
        val entryId = JournalEntryId(cursor.next())
        val postings = original.postings.sortedBy { it.lineNumber }.mapIndexed { index, posting ->
            val ledger = context.references.ledger(posting.ledgerAccountId) ?: reject("posting.ledgerAccount")
            Posting.reverse(
                id = PostingId(cursor.next()),
                journalEntryId = entryId,
                lineNumber = index + 1,
                original = posting,
                ledgerAccount = ledger,
                baseCurrency = snapshot.book.baseCurrency,
            ).orReject()
        }
        val hash = CanonicalFinancialHash.journal(
            entryId,
            sourceRevisionId,
            original.entry.appliesRevisionId,
            JournalEntryRole.REVERSE,
            original.entry.id,
            original.entry.effectiveAt,
            snapshot.book.baseCurrency,
            postings,
            original.entry.ruleSetVersion,
            commitId,
        )
        val entry = JournalEntry.create(
            id = entryId,
            sourceRevisionId = sourceRevisionId,
            appliesRevisionId = original.entry.appliesRevisionId,
            role = JournalEntryRole.REVERSE,
            reversesEntryId = original.entry.id,
            effectiveAt = original.entry.effectiveAt,
            baseCurrency = snapshot.book.baseCurrency,
            postings = postings,
            ruleSetVersion = original.entry.ruleSetVersion,
            createdCommitId = commitId,
            contentHash = hash,
        ).orReject()
        reversedEntries += original.entry.id to JournalBundle(entry, postings)
    }
    fun reverseEntryId(original: JournalEntryId): JournalEntryId = reversedEntries.singleOrNull {
        it.first == original
    }?.second?.entry?.id ?: reject("effect.sourceEntry")
    val economic = current.economicEffects.map { effect ->
        effect.copy(
            id = EconomicEffectId(cursor.next()),
            sourceEntryId = reverseEntryId(effect.sourceEntryId),
            sourceRevisionId = sourceRevisionId,
            reversalOfId = effect.id,
            polarity = EffectPolarity.REVERSE,
        )
    }
    val budget = current.budgetEffects.map { effect ->
        effect.copy(
            id = BudgetEffectId(cursor.next()),
            sourceRevisionId = sourceRevisionId,
            reversalOfId = effect.id,
            polarity = EffectPolarity.REVERSE,
            kind = effect.kind.opposite(),
        )
    }
    val project = current.projectEffects.map { effect ->
        effect.copy(
            id = ProjectEffectId(cursor.next()),
            sourceRevisionId = sourceRevisionId,
            reversalOfId = effect.id,
            polarity = EffectPolarity.REVERSE,
            kind = effect.kind.opposite(),
        )
    }
    val goal = current.goalEffects.map { effect ->
        effect.copy(
            id = GoalEffectId(cursor.next()),
            sourceRevisionId = sourceRevisionId,
            goalMovementId = null,
            reversalOfId = effect.id,
            polarity = EffectPolarity.REVERSE,
            kind = effect.kind.opposite(),
        )
    }
    val statement = current.statementEffects.map { effect ->
        effect.copy(
            id = StatementEffectId(cursor.next()),
            sourceRevisionId = sourceRevisionId,
            reversalOfId = effect.id,
            polarity = EffectPolarity.REVERSE,
        )
    }
    val loan = current.loanEffects.map { effect ->
        effect.copy(
            id = LoanEffectId(cursor.next()),
            sourceRevisionId = sourceRevisionId,
            reversalOfId = effect.id,
            polarity = EffectPolarity.REVERSE,
        )
    }
    val settlement = current.settlementEffects.map { effect ->
        effect.copy(
            id = SettlementEffectId(cursor.next()),
            sourceRevisionId = sourceRevisionId,
            settlementPaymentRecordId = null,
            reversalOfId = effect.id,
            signedDeltaMinor = CheckedArithmetic.negate(effect.signedDeltaMinor).orReject(),
        )
    }
    val refundAllocations = current.refundAllocationFacts.map { allocation ->
        allocation.copy(
            refundRevisionId = sourceRevisionId,
            createdCommitId = commitId,
            reversalOf = RefundAllocationReference(allocation.refundRevisionId, allocation.originalTransactionId),
        )
    }
    return MaterializedFacts(
        reversedEntries.map { it.second },
        economic,
        budget,
        project,
        goal,
        statement,
        loan,
        settlement,
        refundAllocations,
    )
}

private fun materializeRevisionAmounts(
    revisionId: TransactionRevisionId,
    payload: TransactionPayload,
    amounts: List<FrozenAmountEvidence>,
): List<RevisionAmount> {
    val materialized = amounts.flatMap { evidence ->
        listOf(
            RevisionAmount(
                revisionId,
                evidence.key.componentIndex,
                evidence.key.role,
                AmountRepresentation.USER_INPUT,
                evidence.userInput,
                null,
                null,
            ),
            RevisionAmount(
                revisionId,
                evidence.key.componentIndex,
                evidence.key.role,
                AmountRepresentation.ACCOUNT,
                evidence.accountAmount,
                evidence.relatedAccountId,
                evidence.userInputToAccount?.id,
            ),
            RevisionAmount(
                revisionId,
                evidence.key.componentIndex,
                evidence.key.role,
                AmountRepresentation.BASE,
                evidence.baseAmount,
                null,
                evidence.accountToBase?.id,
            ),
        )
    }
    return if (materialized.isEmpty() && payload is SettlementPaymentPayload && !payload.selfParticipates) {
        listOf(
            RevisionAmount(
                revisionId = revisionId,
                componentIndex = 0,
                role = AmountRole.SETTLEMENT,
                representation = AmountRepresentation.SETTLEMENT,
                money = payload.amount,
                relatedAccountId = null,
                fxRateSnapshotId = null,
            ),
        )
    } else {
        materialized
    }
}

private fun materializeFxSnapshots(
    commitId: BookCommitId,
    amounts: List<FrozenAmountEvidence>,
): List<FxRateSnapshot> {
    val conversions = amounts.flatMap { evidence ->
        listOfNotNull(evidence.userInputToAccount, evidence.accountToBase)
    }
    val unique = mutableListOf<FrozenFxConversion>()
    conversions.forEach { conversion ->
        val existing = unique.singleOrNull { it.id == conversion.id }
        require(existing == null || existing == conversion, "fxRateSnapshot.id")
        if (existing == null) unique += conversion
    }
    return unique.map { conversion ->
        FxRateSnapshot(conversion.id, conversion.evidence, conversion.staleAtUse, commitId)
    }
}

private fun projectionChanges(
    snapshot: PlanningSnapshot,
    transaction: BusinessTransaction,
    revision: TransactionRevision,
    facts: MaterializedFacts,
    target: LocalRevision,
): ProjectionChangeSet {
    val references = snapshot.accountingContext?.references ?: reject("planningSnapshot.accountingContext")
    val changes = mutableListOf<ProjectionChange>(
        ProjectionChange.CurrentTransaction(transaction.id, target),
        ProjectionChange.SearchAndMap(transaction.id, target),
        ProjectionChange.Widget(snapshot.book.id, target),
    )
    facts.journals.flatMap { it.postings }.forEach { posting ->
        val account = references.accounts.singleOrNull { it.ledger.id == posting.ledgerAccountId }
        if (account != null) {
            changes += ProjectionChange.AccountFromDate(account.account.id, revision.occurredAt.localDate, target)
        }
    }
    if (facts.budget.isNotEmpty()) {
        changes += ProjectionChange.BudgetFromMonth(
            revision.budgetMonth ?: reject("projectionChange.budgetMonth"),
            target,
        )
    }
    facts.project.map { it.projectId }.forEach { changes += ProjectionChange.Project(it, target) }
    facts.goal.map { it.goalId }.forEach { changes += ProjectionChange.Goal(it, target) }
    facts.statement.mapNotNull { it.statementId }.forEach { changes += ProjectionChange.Statement(it, target) }
    facts.loan.map { it.loanContractId }.forEach { changes += ProjectionChange.Loan(it, target) }
    facts.settlement.map { it.activityId }.forEach { changes += ProjectionChange.Settlement(it, target) }
    facts.refundAllocations.map { it.originalTransactionId }.forEach { changes += ProjectionChange.Refund(it, target) }
    return ProjectionChangeSet(target, changes.distinct())
}

private fun TransactionRevision.asInput(): NewTransactionInput<TransactionPayload> = NewTransactionInput(context(), payload)

private fun TransactionRevision.isLocationOnlyReplacement(replacement: NewTransactionInput<TransactionPayload>): Boolean = payload == replacement.payload &&
    locationRecordId != replacement.context.locationRecordId &&
    context().copy(locationRecordId = replacement.context.locationRecordId) == replacement.context

@Suppress("ReturnCount")
private fun TransactionRevision.isCategoryOnlyReplacement(replacement: NewTransactionInput<TransactionPayload>): Boolean {
    val before = payload.classification ?: return false
    val after = replacement.payload.classification ?: return false
    if (before == after || context() != replacement.context) return false
    val normalizedReplacement = when (val value = replacement.payload) {
        is ExpensePayload -> value.copy(classification = before)
        is IncomePayload -> value.copy(classification = before)
        is RefundPayload -> value.copy(classification = before)
        is LoanPaymentPayload -> value.copy(classification = before)
        is FxExchangePayload -> value.copy(classification = before)
        else -> return false
    }
    return payload == normalizedReplacement && before.direction == after.direction
}

@Suppress("ReturnCount")
private fun CategoryAssignment.replacementSystemLedger(
    posting: Posting,
    snapshot: PlanningSnapshot,
    references: PlanningReferenceData,
): LedgerAccountSnapshot? {
    val code = when (direction) {
        CategoryDirection.EXPENSE -> when (posting.role) {
            PostingRole.EXPENSE -> when (statisticalNatureSnapshot) {
                StatisticalNature.CONSUMPTION_EXPENSE -> SystemLedgerCode.SYSTEM_EXPENSE_CONSUMPTION
                StatisticalNature.NON_CONSUMPTION_EXPENSE -> SystemLedgerCode.SYSTEM_EXPENSE_NON_CONSUMPTION
                else -> reject("expense.statisticalNature")
            }
            else -> return null
        }
        CategoryDirection.INCOME -> when (posting.role) {
            PostingRole.INCOME -> when (statisticalNatureSnapshot) {
                StatisticalNature.REGULAR_INCOME -> SystemLedgerCode.SYSTEM_INCOME_REGULAR
                StatisticalNature.NON_RECURRING_INCOME -> SystemLedgerCode.SYSTEM_INCOME_NON_RECURRING
                else -> reject("income.statisticalNature")
            }
            else -> return null
        }
    }
    return references.system(code, snapshot.book.baseCurrency) ?: reject("systemLedger.$code")
}

private fun TransactionRevision.context(): TransactionContextInput = TransactionContextInput(
    occurredAt = occurredAt,
    accrualDate = accrualDate,
    budgetMonth = budgetMonth,
    merchantId = merchantId,
    projectId = projectId,
    goalId = goalId,
    locationRecordId = locationRecordId,
    note = note,
    amountExpression = amountExpression,
    source = source,
    sourceReferenceId = sourceReferenceId,
    statementAssignment = statementAssignment,
    attachmentIds = attachmentIds,
)

@Suppress("LongParameterList")
private fun NewTransactionInput<TransactionPayload>.toRevision(
    id: TransactionRevisionId,
    transactionId: TransactionId,
    revisionNumber: Int,
    action: RevisionAction,
    state: TransactionLifecycleState,
    previousRevisionId: TransactionRevisionId?,
    commitId: BookCommitId,
    createdAt: java.time.Instant,
    contentHash: ContentHash,
): TransactionRevision = TransactionRevision(
    id = id,
    transactionId = transactionId,
    revisionNumber = revisionNumber,
    action = action,
    resultingState = state,
    previousRevisionId = previousRevisionId,
    createdCommitId = commitId,
    createdAt = createdAt,
    occurredAt = context.occurredAt,
    accrualDate = context.accrualDate,
    budgetMonth = context.budgetMonth,
    merchantId = context.merchantId,
    projectId = context.projectId,
    goalId = context.goalId,
    locationRecordId = context.locationRecordId,
    note = context.note,
    amountExpression = context.amountExpression,
    source = context.source,
    sourceReferenceId = context.sourceReferenceId,
    statementAssignment = context.statementAssignment,
    attachmentIds = context.attachmentIds.toList(),
    payload = payload,
    contentHash = contentHash,
)

private fun BudgetEffectKind.opposite(): BudgetEffectKind = when (this) {
    BudgetEffectKind.USE -> BudgetEffectKind.RESTORE
    BudgetEffectKind.RESTORE -> BudgetEffectKind.USE
}

private fun ProjectEffectKind.opposite(): ProjectEffectKind = when (this) {
    ProjectEffectKind.USE -> ProjectEffectKind.RESTORE
    ProjectEffectKind.RESTORE -> ProjectEffectKind.USE
    ProjectEffectKind.ADJUST -> ProjectEffectKind.ADJUST
}

private fun GoalEffectKind.opposite(): GoalEffectKind = when (this) {
    GoalEffectKind.ALLOCATE -> GoalEffectKind.RELEASE
    GoalEffectKind.RELEASE -> GoalEffectKind.ALLOCATE
    GoalEffectKind.SPEND -> GoalEffectKind.RESTORE
    GoalEffectKind.RESTORE -> GoalEffectKind.SPEND
    GoalEffectKind.ADJUST -> GoalEffectKind.ADJUST
}

private class PlanningIdCursor(private val ids: List<StableId>) {
    private var index: Int = 0

    fun next(): StableId {
        if (index >= ids.size) reject("planningIdentity.factIds")
        val value = ids[index]
        index = Math.addExact(index, 1)
        return value
    }
}

private class PlannerRejected(val violation: DomainError) : RuntimeException(null, null, false, false)

private fun reject(field: String): Nothing = throw PlannerRejected(DomainViolation.InvalidField(field))

private fun require(condition: Boolean, field: String) {
    if (!condition) reject(field)
}

private fun <T> DomainResult<T>.orReject(): T = when (this) {
    is DomainResult.Success -> value
    is DomainResult.Failure -> throw PlannerRejected(error)
}
