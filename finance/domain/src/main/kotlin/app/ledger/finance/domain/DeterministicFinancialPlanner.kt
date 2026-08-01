@file:Suppress("TooManyFunctions")

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
            is RecordTransactionCommand<*> -> planCreate(command, snapshot)
            is EditTransactionCommand -> planEdit(command, snapshot)
            is MoveTransactionToTrashCommand -> planTrash(command, snapshot)
            is RestoreTransactionCommand -> planRestore(command, snapshot)
            else -> reject("financialCommand.transactionPlannerScope")
        }
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
        return planLifecycle(command, snapshot, command.replacement, RevisionAction.EDIT, TransactionLifecycleState.ACTIVE)
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

    @Suppress("LongMethod")
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
            RevisionAction.MOVE_TO_TRASH,
            -> reverseCurrentFacts(snapshot, revision.id, identities.commitId, cursor)
            else -> MaterializedFacts.empty()
        }
        val ruleResult = if (action == RevisionAction.MOVE_TO_TRASH) {
            AccountingRuleOutput(journals = emptyList(), amounts = context.amountEvidence)
        } else {
            AccountingRuleEngine.plan(snapshot.book, input, snapshot).orReject()
        }
        val applyFacts = if (resultingState == TransactionLifecycleState.ACTIVE) {
            materializeApplyFacts(snapshot, revision, ruleResult, identities.commitId, cursor)
        } else {
            MaterializedFacts.empty()
        }
        val facts = reverseFacts + applyFacts
        val revisionAmounts = materializeRevisionAmounts(revision.id, ruleResult.amounts)
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
            is MoveTransactionToTrashCommand -> command.dependencyResolutions
            else -> emptyList()
        }
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
            refundAllocations = (input.payload as? RefundPayload)?.allocations.orEmpty(),
            goalMovements = emptyList(),
            budgetAdjustments = emptyList(),
            purgeTombstones = emptyList(),
            blobGcCandidates = emptyList(),
            dependencyResolutions = dependencyResolutions,
            projectionChanges = projectionChanges,
            entityChanges = listOf(transactionEntityChange(current, transaction, revision)),
            ruleSetVersion = snapshot.book.ruleSetVersion,
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

private data class MaterializedFacts(
    val journals: List<JournalBundle>,
    val economic: List<EconomicEffect>,
    val budget: List<BudgetEffect>,
    val project: List<ProjectEffect>,
    val goal: List<GoalEffect>,
    val statement: List<StatementEffect>,
    val loan: List<LoanEffect>,
    val settlement: List<SettlementEffect>,
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
        )
    }
}

@Suppress("LongMethod")
private fun materializeApplyFacts(
    snapshot: PlanningSnapshot,
    revision: TransactionRevision,
    output: AccountingRuleOutput,
    commitId: BookCommitId,
    cursor: PlanningIdCursor,
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
            sourceRevisionId = revision.id,
            settlementPaymentRecordId = null,
            reversalOfId = null,
            kind = spec.kind,
            signedDeltaMinor = spec.signedDeltaMinor,
            currency = spec.currency,
        )
    }
    return MaterializedFacts(journals, economic, budget, project, goal, statement, loan, settlement)
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
    return MaterializedFacts(
        reversedEntries.map { it.second },
        economic,
        budget,
        project,
        goal,
        statement,
        loan,
        settlement,
    )
}

private fun materializeRevisionAmounts(
    revisionId: TransactionRevisionId,
    amounts: List<FrozenAmountEvidence>,
): List<RevisionAmount> = amounts.flatMap { evidence ->
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
    return ProjectionChangeSet(target, changes.distinct())
}

private fun TransactionRevision.asInput(): NewTransactionInput<TransactionPayload> = NewTransactionInput(context(), payload)

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
