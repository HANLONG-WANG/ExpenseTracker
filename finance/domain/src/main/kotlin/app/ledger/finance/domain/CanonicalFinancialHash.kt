package app.ledger.finance.domain

import app.ledger.core.common.CommandId
import app.ledger.core.common.StableId
import app.ledger.core.money.CurrencyCode
import app.ledger.core.time.EffectiveTime
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/** Length-prefixed, locale-independent encoding for command idempotency and immutable facts. */
@Suppress("TooManyFunctions")
object CanonicalFinancialHash {
    fun command(command: FinancialCommand): Hash256 = digest {
        text(command.commandType.name)
        nullableStableId(command.expectedRevisionId?.value)
        when (command) {
            is ApplyInstallmentSettlementCommand -> {
                transactionInput(command.input)
                installmentPlanMutation(command.mutation)
            }
            is RecordTransactionCommand<*> -> transactionInput(command.input)
            is EditTransactionCommand -> {
                stableId(command.transactionId.value)
                text(command.revisionAction.name)
                transactionInput(command.replacement)
                dependencyResolutions(command.dependencyResolutions)
            }
            is RestoreHistoricalRevisionCommand -> {
                stableId(command.transactionId.value)
                stableId(command.sourceRevisionId.value)
                transactionInput(command.replacement)
                dependencyResolutions(command.dependencyResolutions)
            }
            is MoveTransactionToTrashCommand -> {
                stableId(command.transactionId.value)
                instant(command.purgeAfter)
                dependencyResolutions(command.dependencyResolutions)
            }
            is RestoreTransactionCommand -> stableId(command.transactionId.value)
            is PurgeTransactionCommand -> {
                stableId(command.transactionId.value)
                purgeEligibility(command.eligibility)
            }
            is RecordGoalMovementCommand -> {
                goalMovement(command.movement)
                stableId(command.effectId.value)
                long(command.expectedGoalRowVersion.value)
            }
            is RecordBudgetAdjustmentCommand -> budgetAdjustment(command.adjustment)
            is ConfigureBudgetMonthCommand -> budgetMonthMutation(command.mutation)
            is SaveBudgetTemplateCommand -> budgetTemplateMutation(command.mutation)
            is RecordBudgetAdjustmentsCommand -> {
                integer(command.adjustments.size)
                command.adjustments.forEach(::budgetAdjustment)
            }
            is SaveCreditProfileCommand -> creditProfileMutation(command.mutation)
            is SaveCreditStatementCommand -> creditStatementMutation(command.mutation)
            is SaveInstallmentPlanCommand -> installmentPlanMutation(command.mutation)
            is BatchFinancialCommand -> {
                integer(command.commands.size)
                command.commands.forEach { child ->
                    stableId(child.commandId.stableId)
                    hash(child.payloadHash)
                }
            }
        }
    }

    private fun CanonicalWriter.creditProfileMutation(mutation: CreditProfileMutation) {
        val profile = mutation.profile
        stableId(profile.accountId.value)
        when (val rule = profile.statementRule) {
            is StatementDateRule.DayOfMonth -> {
                text("DAY")
                integer(rule.day)
                text(rule.missingDayPolicy.name)
            }
            StatementDateRule.LastDayOfMonth -> text("LAST_DAY")
        }
        when (val rule = profile.paymentDueRule) {
            is DueDateRule.FixedDay -> {
                text("FIXED_DAY")
                integer(rule.day)
                text(rule.missingDayPolicy.name)
            }
            is DueDateRule.DaysAfterStatement -> {
                text("DAYS_AFTER")
                integer(rule.days)
            }
        }
        text(profile.statementZoneId.id)
        optionalLong(profile.standardLimitMinor)
        optionalLong(profile.temporaryLimitMinor)
        optionalLocalDate(profile.temporaryLimitExpiresOn)
        nullableStableId(profile.defaultPaymentAccountId?.value)
        text(profile.autoPaymentMode.name)
        text(profile.weekendAdjustment.name)
        stableId(profile.lastCommitId.value)
        nullableStableId(mutation.expectedLastCommitId?.value)
        boolean(mutation.limitPeriod != null)
        mutation.limitPeriod?.let {
            localDate(it.effectiveFrom)
            optionalLocalDate(it.effectiveTo)
            long(it.limitMinor)
            stableId(it.createdCommitId.value)
        }
    }

    private fun CanonicalWriter.creditStatementMutation(mutation: CreditStatementMutation) {
        val statement = mutation.statement
        val revision = mutation.revision
        stableId(statement.id.value)
        stableId(statement.creditAccountId.value)
        localDate(statement.cycleStart)
        localDate(statement.cycleEnd)
        localDate(statement.dueDate)
        stableId(statement.currentRevisionId.value)
        text(statement.status.name)
        stableId(revision.id.value)
        integer(revision.revisionNumber)
        long(revision.estimatedAmountMinor)
        optionalLong(revision.officialAmountMinor)
        nullableInstant(revision.officialRecordedAt)
        optionalLong(revision.differenceMinor)
        localDate(revision.statementDate)
        localDate(revision.dueDate)
        boolean(revision.sealed)
        stableId(revision.createdCommitId.value)
        nullableStableId(mutation.expectedRevisionId?.value)
    }

    private fun CanonicalWriter.installmentPlanMutation(mutation: InstallmentPlanMutation) {
        val plan = mutation.plan
        val revision = mutation.revision
        stableId(plan.id.value)
        stableId(plan.purchaseTransactionId.value)
        stableId(plan.creditAccountId.value)
        long(plan.originalPrincipalMinor)
        currency(plan.currency)
        integer(plan.termCount)
        stableId(plan.currentRevisionId.value)
        text(plan.status.name)
        nullableStableId(mutation.expectedRevisionId?.value)
        stableId(revision.id.value)
        integer(revision.revisionNumber)
        text(revision.feeRateType.name)
        optionalLong(revision.fixedFeePerTermMinor)
        optionalLong(revision.firstTermFeeMinor)
        decimal(revision.remainingPrincipalRate?.annualDecimal)
        decimal(revision.effectiveAnnualRate?.annualDecimal)
        text(revision.prepaymentPolicy.name)
        optionalLong(revision.prepaymentFeeMinor)
        text(revision.refundPolicy.name)
        text(revision.roundingMode.name)
        stableId(revision.createdCommitId.value)
        val schedule = mutation.scheduleRevision
        stableId(schedule.id.value)
        integer(schedule.revisionNumber)
        text(schedule.reason.name)
        instant(schedule.generatedAt)
        stableId(schedule.createdCommitId.value)
        integer(schedule.items.size)
        schedule.items.forEach { item ->
            stableId(item.id.value)
            integer(item.installmentNumber)
            localDate(item.statementDate)
            long(item.principalMinor)
            long(item.interestMinor)
            long(item.feeMinor)
            long(item.remainingPrincipalMinor)
        }
        long(mutation.currentPrincipalMinor)
        nullableStableId(mutation.settlementTransactionId?.value)
        boolean(mutation.refundAllocation != null)
        mutation.refundAllocation?.let { allocation ->
            stableId(allocation.refundTransactionId.value)
            stableId(allocation.refundRevisionId.value)
            long(allocation.principalMinor)
            long(allocation.feeMinor)
            nullableStableId(allocation.reversalOfId)
        }
    }

    private fun CanonicalWriter.optionalLong(value: Long?) {
        boolean(value != null)
        if (value != null) long(value)
    }

    private fun CanonicalWriter.optionalLocalDate(value: LocalDate?) {
        boolean(value != null)
        if (value != null) localDate(value)
    }

    @Suppress("LongParameterList")
    fun revision(
        id: TransactionRevisionId,
        transactionId: TransactionId,
        revisionNumber: Int,
        action: RevisionAction,
        state: TransactionLifecycleState,
        previousRevisionId: TransactionRevisionId?,
        commitId: BookCommitId,
        createdAt: Instant,
        input: NewTransactionInput<TransactionPayload>,
    ): ContentHash = ContentHash(
        digest {
            text("TRANSACTION_REVISION_V1")
            stableId(id.value)
            stableId(transactionId.value)
            integer(revisionNumber)
            text(action.name)
            text(state.name)
            nullableStableId(previousRevisionId?.value)
            stableId(commitId.value)
            instant(createdAt)
            transactionInput(input)
        },
    )

    @Suppress("LongParameterList")
    fun transaction(
        id: TransactionId,
        kind: TransactionKind,
        currentRevisionId: TransactionRevisionId,
        state: TransactionLifecycleState,
        createdCommitId: BookCommitId,
        lastCommitId: BookCommitId,
        rowVersion: RowVersion,
        trashedAt: Instant?,
        purgeAfter: Instant?,
    ): ContentHash = ContentHash(
        digest {
            text("BUSINESS_TRANSACTION_V1")
            stableId(id.value)
            text(kind.name)
            stableId(currentRevisionId.value)
            text(state.name)
            stableId(createdCommitId.value)
            stableId(lastCommitId.value)
            long(rowVersion.value)
            nullableInstant(trashedAt)
            nullableInstant(purgeAfter)
        },
    )

    @Suppress("LongParameterList")
    fun journal(
        id: JournalEntryId,
        sourceRevisionId: TransactionRevisionId,
        appliesRevisionId: TransactionRevisionId,
        role: JournalEntryRole,
        reversesEntryId: JournalEntryId?,
        effectiveAt: EffectiveTime,
        baseCurrency: CurrencyCode,
        postings: List<Posting>,
        ruleSetVersion: RuleSetVersion,
        commitId: BookCommitId,
    ): ContentHash = ContentHash(
        digest {
            text("JOURNAL_ENTRY_V1")
            stableId(id.value)
            stableId(sourceRevisionId.value)
            stableId(appliesRevisionId.value)
            text(role.name)
            nullableStableId(reversesEntryId?.value)
            effectiveTime(effectiveAt)
            currency(baseCurrency)
            integer(postings.size)
            postings.sortedBy { it.lineNumber }.forEach(::posting)
            integer(ruleSetVersion.value)
            stableId(commitId.value)
        },
    )

    fun commitRoot(
        commandId: CommandId,
        payloadHash: Hash256,
        targetRevision: LocalRevision,
        contentHashes: List<ContentHash>,
    ): Hash256 = digest {
        text("BOOK_COMMIT_ROOT_V1")
        stableId(commandId.stableId)
        hash(payloadHash)
        long(targetRevision.value)
        integer(contentHashes.size)
        contentHashes.forEach { hash(it.value) }
    }

    @Suppress("LongParameterList", "LongMethod")
    fun evidenceAndEffects(
        revisionAmounts: List<RevisionAmount>,
        fxSnapshots: List<FxRateSnapshot>,
        economic: List<EconomicEffect>,
        budget: List<BudgetEffect>,
        project: List<ProjectEffect>,
        goal: List<GoalEffect>,
        statement: List<StatementEffect>,
        loan: List<LoanEffect>,
        settlement: List<SettlementEffect>,
        refundAllocations: List<RefundAllocationFact> = emptyList(),
    ): ContentHash = ContentHash(
        digest {
            text("FINANCIAL_EVIDENCE_AND_EFFECTS_V1")
            integer(revisionAmounts.size)
            revisionAmounts.forEach { amount ->
                stableId(amount.revisionId.value)
                integer(amount.componentIndex)
                text(amount.role.name)
                text(amount.representation.name)
                positiveMoney(amount.money)
                nullableStableId(amount.relatedAccountId?.value)
                nullableStableId(amount.fxRateSnapshotId?.value)
            }
            integer(fxSnapshots.size)
            fxSnapshots.forEach { snapshot ->
                stableId(snapshot.id.value)
                val evidence = snapshot.evidence
                currency(evidence.sourceCurrency)
                currency(evidence.targetCurrency)
                decimal(evidence.rate)
                text(evidence.provider.value)
                nullableInstant(evidence.quotedAt)
                nullableInstant(evidence.fetchedAt)
                text(evidence.source.name)
                boolean(evidence.manuallyOverridden)
                boolean(snapshot.staleAtUse)
                stableId(snapshot.createdCommitId.value)
            }
            integer(economic.size)
            economic.forEach { effect ->
                stableId(effect.id.value)
                stableId(effect.sourceEntryId.value)
                stableId(effect.sourceRevisionId.value)
                nullableStableId(effect.reversalOfId?.value)
                text(effect.polarity.name)
                text(effect.nature.name)
                text(effect.component.name)
                boolean(effect.isConsumption)
                positiveMoney(effect.baseAmount)
                localDate(effect.accrualDate)
                nullableStableId(effect.categoryId?.value)
                nullableStableId(effect.merchantId?.value)
                nullableStableId(effect.projectId?.value)
                integer(effect.ruleSetVersion.value)
            }
            integer(budget.size)
            budget.forEach { effect ->
                stableId(effect.id.value)
                stableId(effect.sourceRevisionId.value)
                nullableStableId(effect.reversalOfId?.value)
                text(effect.polarity.name)
                text(effect.kind.name)
                integer(effect.targetMonth.year)
                integer(effect.targetMonth.monthValue)
                nullableStableId(effect.categoryId?.value)
                nullableStableId(effect.rootCategoryId?.value)
                positiveMoney(effect.baseAmount)
                integer(effect.ruleSetVersion.value)
            }
            integer(project.size)
            project.forEach { effect ->
                stableId(effect.id.value)
                stableId(effect.projectId.value)
                text(effect.kind.name)
                positiveMoney(effect.baseAmount)
                boolean(effect.includedInMonthlyBudgetSnapshot)
                stableId(effect.sourceRevisionId.value)
                nullableStableId(effect.reversalOfId?.value)
                text(effect.polarity.name)
            }
            integer(goal.size)
            goal.forEach { effect ->
                stableId(effect.id.value)
                stableId(effect.goalId.value)
                text(effect.kind.name)
                positiveMoney(effect.amount)
                nullableStableId(effect.sourceRevisionId?.value)
                nullableStableId(effect.goalMovementId?.value)
                nullableStableId(effect.reversalOfId?.value)
                text(effect.polarity.name)
            }
            integer(statement.size)
            statement.forEach { effect ->
                stableId(effect.id.value)
                stableId(effect.creditAccountId.value)
                nullableStableId(effect.statementId?.value)
                stableId(effect.sourceRevisionId.value)
                nullableStableId(effect.reversalOfId?.value)
                text(effect.kind.name)
                text(effect.polarity.name)
                positiveMoney(effect.amount)
                boolean(effect.manualAssignment)
            }
            integer(loan.size)
            loan.forEach { effect ->
                stableId(effect.id.value)
                stableId(effect.loanContractId.value)
                stableId(effect.loanTrancheId.value)
                nullableStableId(effect.scheduleItemId?.value)
                stableId(effect.sourceRevisionId.value)
                nullableStableId(effect.reversalOfId?.value)
                text(effect.kind.name)
                text(effect.polarity.name)
                positiveMoney(effect.amount)
                positiveMoney(effect.baseAmount)
            }
            integer(settlement.size)
            settlement.forEach { effect ->
                stableId(effect.id.value)
                stableId(effect.activityId.value)
                stableId(effect.participantId.value)
                nullableStableId(effect.sourceRevisionId?.value)
                nullableStableId(effect.settlementPaymentRecordId?.value)
                nullableStableId(effect.reversalOfId?.value)
                text(effect.kind.name)
                long(effect.signedDeltaMinor)
                currency(effect.currency)
            }
            integer(refundAllocations.size)
            refundAllocations.forEach { allocation ->
                stableId(allocation.refundTransactionId.value)
                stableId(allocation.refundRevisionId.value)
                stableId(allocation.originalTransactionId.value)
                stableId(allocation.originalRevisionId.value)
                positiveMoney(allocation.amountInOriginalCurrency)
                positiveMoney(allocation.amountInBaseCurrency)
                stableId(allocation.createdCommitId.value)
                boolean(allocation.reversalOf != null)
                allocation.reversalOf?.let { reversal ->
                    stableId(reversal.refundRevisionId.value)
                    stableId(reversal.originalTransactionId.value)
                }
            }
        },
    )

    private fun digest(block: CanonicalWriter.() -> Unit): Hash256 {
        val writer = CanonicalWriter()
        writer.block()
        return Hash256.sha256(writer.bytes())
    }
}

@Suppress("TooManyFunctions")
private class CanonicalWriter {
    private val buffer = ByteArrayOutputStream()
    private val output = DataOutputStream(buffer)

    fun bytes(): ByteArray = buffer.toByteArray()

    fun boolean(value: Boolean) = output.writeBoolean(value)

    fun integer(value: Int) = output.writeInt(value)

    fun long(value: Long) = output.writeLong(value)

    fun text(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        integer(bytes.size)
        output.write(bytes)
    }

    fun nullableText(value: String?) {
        boolean(value != null)
        if (value != null) text(value)
    }

    fun stableId(value: StableId) {
        val bytes = value.bytes
        integer(bytes.size)
        output.write(bytes)
    }

    fun nullableStableId(value: StableId?) {
        boolean(value != null)
        if (value != null) stableId(value)
    }

    fun hash(value: Hash256) {
        val bytes = value.bytes
        integer(bytes.size)
        output.write(bytes)
    }

    fun instant(value: Instant) {
        long(value.epochSecond)
        integer(value.nano)
    }

    fun nullableInstant(value: Instant?) {
        boolean(value != null)
        if (value != null) instant(value)
    }

    fun localDate(value: LocalDate) = long(value.toEpochDay())

    fun nullableYearMonth(value: YearMonth?) {
        boolean(value != null)
        if (value != null) {
            integer(value.year)
            integer(value.monthValue)
        }
    }

    fun currency(value: CurrencyCode) = text(value.value)

    fun decimal(value: BigDecimal?) {
        boolean(value != null)
        if (value != null) text(value.stripTrailingZeros().toPlainString())
    }

    fun positiveMoney(value: PositiveMoney) {
        long(value.minor.value)
        currency(value.currency)
    }

    fun accountAmount(value: AccountAmount) {
        stableId(value.accountId.value)
        positiveMoney(value.amount)
    }

    fun effectiveTime(value: EffectiveTime) {
        instant(value.instant)
        text(value.zoneId.id)
        localDate(value.localDate)
        boolean(value.adjustment != null)
        value.adjustment?.let { adjustment ->
            text(adjustment.kind.name)
            text(adjustment.requestedLocalDateTime.toString())
            text(adjustment.resolvedLocalDateTime.toString())
            long(adjustment.shiftedSeconds)
        }
    }

    fun transactionInput(input: NewTransactionInput<*>) {
        transactionContext(input.context)
        transactionPayload(input.payload)
    }

    private fun transactionContext(context: TransactionContextInput) {
        effectiveTime(context.occurredAt)
        localDate(context.accrualDate)
        nullableYearMonth(context.budgetMonth)
        nullableStableId(context.merchantId?.value)
        nullableStableId(context.projectId?.value)
        nullableStableId(context.goalId?.value)
        nullableStableId(context.locationRecordId?.value)
        nullableText(context.note)
        nullableText(context.amountExpression)
        text(context.source.name)
        nullableStableId(context.sourceReferenceId)
        boolean(context.statementAssignment != null)
        context.statementAssignment?.let { assignment ->
            text(assignment.mode.name)
            nullableStableId(assignment.statementId?.value)
        }
        integer(context.attachmentIds.size)
        context.attachmentIds.forEach { stableId(it.value) }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun transactionPayload(payload: TransactionPayload) {
        text(payload.kind.name)
        categoryAssignment(payload.classification)
        when (payload) {
            is ExpensePayload -> expense(payload)
            is IncomePayload -> {
                accountAmount(payload.receivingAmount)
                positiveMoney(payload.primaryAmount)
            }
            is TransferPayload -> {
                accountAmount(payload.outgoing)
                accountAmount(payload.incoming)
                nullableStableId(payload.sourceCardId?.value)
            }
            is RefundPayload -> refund(payload)
            is CreditPaymentPayload -> creditPayment(payload)
            is LoanDisbursementPayload -> {
                stableId(payload.loanContractId.value)
                accountAmount(payload.receivingAmount)
                positiveMoney(payload.liabilityAmount)
            }
            is LoanPaymentPayload -> loanPayment(payload)
            is BalanceAdjustmentPayload -> {
                accountAmount(payload.accountAmount)
                text(payload.direction.name)
                nullableStableId(payload.checkpointId)
            }
            is FxExchangePayload -> {
                accountAmount(payload.outgoing)
                accountAmount(payload.incoming)
                text(payload.valuationPolicy.name)
                nullableMoney(payload.spreadCost)
            }
            is SettlementPaymentPayload -> {
                stableId(payload.activityId.value)
                stableId(payload.payerParticipantId.value)
                stableId(payload.payeeParticipantId.value)
                positiveMoney(payload.amount)
                boolean(payload.localAccountAmount != null)
                payload.localAccountAmount?.let(::accountAmount)
                boolean(payload.selfParticipates)
            }
            is OpeningBalancePayload -> {
                accountAmount(payload.accountAmount)
                localDate(payload.balanceDate)
                text(payload.side.name)
            }
        }
    }

    private fun expense(payload: ExpensePayload) {
        when (val payer = payload.payer) {
            is ExpensePayer.LocalAccount -> {
                text("LOCAL_ACCOUNT")
                accountAmount(payer.accountAmount)
                nullableStableId(payer.cardId?.value)
            }
            is ExpensePayer.ExternalParticipant -> {
                text("EXTERNAL_PARTICIPANT")
                stableId(payer.participantId.value)
                stableId(payer.activityId.value)
            }
        }
        positiveMoney(payload.primaryAmount)
        nullableStableId(payload.settlementActivityId?.value)
        integer(payload.settlementShares.size)
        payload.settlementShares.forEach(::settlementShare)
        nullableStableId(payload.installmentPlanId?.value)
    }

    private fun refund(payload: RefundPayload) {
        accountAmount(payload.receivingAmount)
        nullableStableId(payload.receivingCardId?.value)
        integer(payload.allocations.size)
        payload.allocations.forEach { allocation ->
            stableId(allocation.originalTransactionId.value)
            stableId(allocation.originalRevisionId.value)
            positiveMoney(allocation.amountInOriginalCurrency)
            positiveMoney(allocation.amountInBaseCurrency)
        }
        boolean(payload.independent)
        boolean(payload.allowExcessOverride)
        text(payload.budgetPolicy.name)
        text(payload.projectPolicy.name)
        text(payload.goalPolicy.name)
        text(payload.accrualPolicy.name)
        nullableStableId(payload.settlementActivityId?.value)
        integer(payload.settlementShares.size)
        payload.settlementShares.forEach(::settlementShare)
    }

    private fun creditPayment(payload: CreditPaymentPayload) {
        accountAmount(payload.payment)
        stableId(payload.creditAccountId.value)
        accountAmount(payload.creditAccountAmount)
        integer(payload.allocations.size)
        payload.allocations.forEach { allocation ->
            nullableStableId(allocation.statementId?.value)
            positiveMoney(allocation.amount)
        }
        text(payload.generationMode.name)
        nullableStableId(payload.installmentPlanId?.value)
        boolean(payload.settlementFee != null)
        payload.settlementFee?.let(::positiveMoney)
    }

    private fun loanPayment(payload: LoanPaymentPayload) {
        stableId(payload.loanContractId.value)
        accountAmount(payload.payment)
        nullableStableId(payload.scheduleRevisionId?.value)
        nullableMoney(payload.components.principal)
        nullableMoney(payload.components.interest)
        nullableMoney(payload.components.fee)
        nullableMoney(payload.components.penalty)
        integer(payload.allocations.size)
        payload.allocations.forEach { allocation ->
            stableId(allocation.paymentTransactionId.value)
            stableId(allocation.paymentRevisionId.value)
            stableId(allocation.trancheId.value)
            nullableStableId(allocation.scheduleItemId?.value)
            text(allocation.component.name)
            positiveMoney(allocation.amount)
            positiveMoney(allocation.baseAmount)
            nullableStableId(allocation.reversalOfId)
        }
    }

    private fun categoryAssignment(value: CategoryAssignment?) {
        boolean(value != null)
        value?.let { assignment ->
            stableId(assignment.categoryId.value)
            text(assignment.direction.name)
            text(assignment.statisticalNatureSnapshot.name)
        }
    }

    private fun settlementShare(value: SettlementShare) {
        stableId(value.participantId.value)
        long(value.paidMinor)
        long(value.owedMinor)
        decimal(value.weight)
        long(value.roundingAdjustmentMinor)
    }

    private fun nullableMoney(value: PositiveMoney?) {
        boolean(value != null)
        value?.let(::positiveMoney)
    }

    fun dependencyResolutions(values: List<DependencyResolution>) {
        integer(values.size)
        values.forEach { resolution ->
            val dependency = resolution.dependency
            stableId(dependency.parentTransactionId.value)
            stableId(dependency.childTransactionId.value)
            text(dependency.type.name)
            when (val policy = resolution.policy) {
                DependencyPolicy.ReverseDependentTransactions -> text("REVERSE_DEPENDENT_TRANSACTIONS")
                DependencyPolicy.ConvertRefundToIndependent -> text("CONVERT_REFUND_TO_INDEPENDENT")
                DependencyPolicy.CancelInstallmentPlan -> text("CANCEL_INSTALLMENT_PLAN")
                is DependencyPolicy.RebindInstallmentPlan -> {
                    text("REBIND_INSTALLMENT_PLAN")
                    stableId(policy.replacementTransactionId.value)
                }
                DependencyPolicy.RecalculateSettlement -> text("RECALCULATE_SETTLEMENT")
                DependencyPolicy.ReopenSettledActivity -> text("REOPEN_SETTLED_ACTIVITY")
                DependencyPolicy.RegenerateCreditStatement -> text("REGENERATE_CREDIT_STATEMENT")
                DependencyPolicy.RecalculateLoanSchedule -> text("RECALCULATE_LOAN_SCHEDULE")
            }
        }
    }

    fun purgeEligibility(value: PurgeEligibility) {
        stableId(value.transactionId.value)
        text(value.lifecycleState.name)
        instant(value.purgeAfter)
        instant(value.evaluatedAt)
        boolean(value.accountCurrencyNetZero)
        boolean(value.baseCurrencyNetZero)
        boolean(value.effectsNetZero)
        boolean(value.dependenciesClosed)
        boolean(value.referencedByOperation)
        boolean(value.attachmentsReadByBackup)
    }

    fun goalMovement(value: GoalMovement) {
        stableId(value.id.value)
        stableId(value.goalId.value)
        text(value.kind.name)
        positiveMoney(value.amount)
        effectiveTime(value.occurredAt)
        nullableStableId(value.sourceTransactionId?.value)
        nullableStableId(value.sourceRecurrenceOccurrenceId?.value)
        nullableStableId(value.reversalOfId?.value)
        stableId(value.createdCommitId.value)
    }

    fun budgetAdjustment(value: BudgetAdjustment) {
        stableId(value.id.value)
        integer(value.month.year)
        integer(value.month.monthValue)
        text(value.scope.name)
        nullableStableId(value.categoryId?.value)
        long(value.amountBaseMinor)
        text(value.kind.name)
        stableId(value.createdCommitId.value)
        nullableStableId(value.reversalOfId?.value)
    }

    fun budgetMonthMutation(value: BudgetMonthMutation) {
        stableId(value.month.id.value)
        integer(value.month.month.year)
        integer(value.month.month.monthValue)
        stableId(value.revision.id.value)
        integer(value.revision.revisionNumber)
        long(value.revision.totalBaseMinor)
        nullableStableId(value.revision.sourceTemplateRevisionId?.value)
        stableId(value.revision.createdCommitId.value)
        nullableStableId(value.expectedRevisionId?.value)
        budgetLimits(value.revision.categoryLimits)
    }

    fun budgetTemplateMutation(value: BudgetTemplateMutation) {
        stableId(value.template.id.value)
        text(value.template.name)
        text(value.template.status.name)
        stableId(value.revision.id.value)
        integer(value.revision.revisionNumber)
        long(value.revision.totalBaseMinor)
        stableId(value.revision.createdCommitId.value)
        nullableStableId(value.expectedRevisionId?.value)
        budgetLimits(value.revision.categoryLimits)
    }

    private fun budgetLimits(values: List<CategoryBudgetLimit>) {
        integer(values.size)
        values.sortedBy { it.categoryId.value }.forEach { value ->
            stableId(value.categoryId.value)
            stableId(value.rootCategoryId.value)
            nullableStableId(value.parentCategoryId?.value)
            integer(value.depth)
            long(value.amountBaseMinor)
        }
    }

    fun posting(value: Posting) {
        stableId(value.id.value)
        integer(value.lineNumber)
        stableId(value.ledgerAccountId.value)
        text(value.side.name)
        positiveMoney(value.accountAmount)
        positiveMoney(value.baseAmount)
        decimal(value.valuationRate)
        text(value.role.name)
        nullableStableId(value.reversalOfPostingId?.value)
    }
}
