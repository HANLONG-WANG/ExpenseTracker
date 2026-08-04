package app.ledger.finance.domain

import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.money.CurrencyMetadata
import app.ledger.core.money.FxConversion
import app.ledger.core.money.FxConverter
import app.ledger.core.money.FxEvidence
import java.time.Instant

data class PlanningAccount(
    val account: AccountSnapshot,
    val ledger: LedgerAccountSnapshot,
) {
    init {
        require(account.ledgerAccountId == ledger.id)
        require(account.currency == ledger.currency)
    }
}

data class PlanningCard(
    val id: PaymentCardId,
    val accountId: UserAccountId,
    val type: CardType,
    val status: EntityStatus,
)

data class PlanningCategory(
    val id: CategoryId,
    val rootId: CategoryId,
    val direction: CategoryDirection,
    val statisticalNature: StatisticalNature,
    val status: CategoryStatus,
)

data class PlanningProject(
    val id: ProjectId,
    val includedInMonthlyBudget: Boolean,
    val status: ProjectStatus,
)

data class PlanningGoal(
    val id: GoalId,
    val accountId: UserAccountId,
    val currency: app.ledger.core.money.CurrencyCode,
    val status: GoalStatus,
)

data class PlanningSystemLedger(
    val code: SystemLedgerCode,
    val ledger: LedgerAccountSnapshot,
) {
    init {
        require(ledger.accountClass in SYSTEM_CLASSES)
    }

    private companion object {
        val SYSTEM_CLASSES = setOf(
            LedgerAccountClass.INCOME,
            LedgerAccountClass.EXPENSE,
            LedgerAccountClass.EQUITY,
            LedgerAccountClass.CLEARING,
        )
    }
}

data class PlanningLoanLedger(
    val contractId: LoanContractId,
    val trancheId: LoanTrancheId,
    val ledger: LedgerAccountSnapshot,
) {
    init {
        require(ledger.accountClass == LedgerAccountClass.LIABILITY)
    }
}

data class PlanningSettlementLedger(
    val activityId: SettlementActivityId,
    val participantId: ParticipantId,
    val ledger: LedgerAccountSnapshot,
) {
    init {
        require(ledger.accountClass == LedgerAccountClass.SETTLEMENT)
    }
}

data class PlanningReferenceData(
    val accounts: List<PlanningAccount>,
    val cards: List<PlanningCard>,
    val categories: List<PlanningCategory>,
    val projects: List<PlanningProject>,
    val goals: List<PlanningGoal>,
    val systemLedgers: List<PlanningSystemLedger>,
    val loanLedgers: List<PlanningLoanLedger>,
    val settlementLedgers: List<PlanningSettlementLedger>,
    /** Archived/owner-specific ledgers needed only to reverse or re-apply immutable history. */
    val historicalLedgers: List<LedgerAccountSnapshot> = emptyList(),
) {
    init {
        require(accounts.map { it.account.id }.toSet().size == accounts.size)
        require(cards.map { it.id }.toSet().size == cards.size)
        require(categories.map { it.id }.toSet().size == categories.size)
        require(projects.map { it.id }.toSet().size == projects.size)
        require(goals.map { it.id }.toSet().size == goals.size)
        require(systemLedgers.map { it.code to it.ledger.currency }.toSet().size == systemLedgers.size)
        require(loanLedgers.map { it.contractId to it.trancheId }.toSet().size == loanLedgers.size)
        require(
            settlementLedgers.map { it.activityId to it.participantId }.toSet().size == settlementLedgers.size,
        )
        require(historicalLedgers.map { it.id }.toSet().size == historicalLedgers.size)
    }

    fun account(id: UserAccountId): PlanningAccount? = accounts.singleOrNull { it.account.id == id }

    fun card(id: PaymentCardId): PlanningCard? = cards.singleOrNull { it.id == id }

    fun category(id: CategoryId): PlanningCategory? = categories.singleOrNull { it.id == id }

    fun project(id: ProjectId): PlanningProject? = projects.singleOrNull { it.id == id }

    fun goal(id: GoalId): PlanningGoal? = goals.singleOrNull { it.id == id }

    fun ledger(id: LedgerAccountId): LedgerAccountSnapshot? = sequenceOf(
        accounts.asSequence().map { it.ledger },
        systemLedgers.asSequence().map { it.ledger },
        loanLedgers.asSequence().map { it.ledger },
        settlementLedgers.asSequence().map { it.ledger },
        historicalLedgers.asSequence(),
    ).flatten().distinctBy { it.id }.singleOrNull { it.id == id }

    fun system(
        code: SystemLedgerCode,
        currency: app.ledger.core.money.CurrencyCode,
    ): LedgerAccountSnapshot? = systemLedgers.singleOrNull {
        it.code == code && it.ledger.currency == currency
    }?.ledger

    fun loan(contractId: LoanContractId): PlanningLoanLedger? = loanLedgers.singleOrNull {
        it.contractId == contractId
    }

    fun loan(contractId: LoanContractId, trancheId: LoanTrancheId): PlanningLoanLedger? = loanLedgers.singleOrNull {
        it.contractId == contractId && it.trancheId == trancheId
    }

    fun settlement(
        activityId: SettlementActivityId,
        participantId: ParticipantId,
    ): PlanningSettlementLedger? = settlementLedgers.singleOrNull {
        it.activityId == activityId && it.participantId == participantId
    }
}

@ConsistentCopyVisibility
data class FrozenFxConversion private constructor(
    val id: FxRateSnapshotId,
    val source: PositiveMoney,
    val target: PositiveMoney,
    val evidence: FxEvidence,
    val staleAtUse: Boolean,
) {
    companion object {
        @Suppress("LongParameterList")
        fun create(
            id: FxRateSnapshotId,
            source: PositiveMoney,
            target: PositiveMoney,
            evidence: FxEvidence,
            sourceMetadata: CurrencyMetadata,
            targetMetadata: CurrencyMetadata,
            staleAtUse: Boolean,
        ): DomainResult<FrozenFxConversion> = when (
            val converted = FxConverter().convert(
                source = source.money,
                targetMetadata = targetMetadata,
                evidence = evidence,
                sourceMetadata = sourceMetadata,
            )
        ) {
            is DomainResult.Failure -> converted
            is DomainResult.Success -> validateConversion(id, source, target, evidence, staleAtUse, converted.value)
        }

        @Suppress("LongParameterList")
        private fun validateConversion(
            id: FxRateSnapshotId,
            source: PositiveMoney,
            target: PositiveMoney,
            evidence: FxEvidence,
            staleAtUse: Boolean,
            converted: FxConversion,
        ): DomainResult<FrozenFxConversion> = if (converted.target != target.money) {
            DomainResult.Failure(DomainViolation.InvalidField("frozenFxConversion.target"))
        } else {
            DomainResult.Success(FrozenFxConversion(id, source, target, evidence, staleAtUse))
        }
    }
}

data class AmountEvidenceKey(
    val role: AmountRole,
    val componentIndex: Int,
) {
    init {
        require(componentIndex >= 0)
    }
}

@ConsistentCopyVisibility
data class FrozenAmountEvidence private constructor(
    val key: AmountEvidenceKey,
    val userInput: PositiveMoney,
    val accountAmount: PositiveMoney,
    val baseAmount: PositiveMoney,
    val relatedAccountId: UserAccountId?,
    val userInputToAccount: FrozenFxConversion?,
    val accountToBase: FrozenFxConversion?,
) {
    companion object {
        @Suppress("LongParameterList", "ComplexCondition")
        fun create(
            key: AmountEvidenceKey,
            userInput: PositiveMoney,
            accountAmount: PositiveMoney,
            baseAmount: PositiveMoney,
            relatedAccountId: UserAccountId?,
            userInputToAccount: FrozenFxConversion?,
            accountToBase: FrozenFxConversion?,
        ): DomainResult<FrozenAmountEvidence> {
            val originalMatches = if (userInput.currency == accountAmount.currency) {
                userInput == accountAmount && userInputToAccount == null
            } else {
                userInputToAccount?.source == userInput && userInputToAccount.target == accountAmount
            }
            val baseMatches = if (accountAmount.currency == baseAmount.currency) {
                accountAmount == baseAmount && accountToBase == null
            } else {
                accountToBase?.source == accountAmount && accountToBase.target == baseAmount
            }
            if (!originalMatches || !baseMatches) {
                return DomainResult.Failure(DomainViolation.InvalidField("frozenAmountEvidence.conversion"))
            }
            return DomainResult.Success(
                FrozenAmountEvidence(
                    key = key,
                    userInput = userInput,
                    accountAmount = accountAmount,
                    baseAmount = baseAmount,
                    relatedAccountId = relatedAccountId,
                    userInputToAccount = userInputToAccount,
                    accountToBase = accountToBase,
                ),
            )
        }
    }
}

data class PlanningIdentitySet(
    val transactionId: TransactionId,
    val revisionId: TransactionRevisionId,
    val commitId: BookCommitId,
    val factIds: List<StableId>,
) {
    init {
        val headerIds = listOf(transactionId.value, revisionId.value, commitId.value)
        require(headerIds.toSet().size == headerIds.size)
        require(factIds.isNotEmpty())
        require(factIds.toSet().size == factIds.size)
        require(factIds.none { it in headerIds })
    }
}

data class CurrentFinancialFacts(
    val journalBundles: List<JournalBundle>,
    val economicEffects: List<EconomicEffect>,
    val budgetEffects: List<BudgetEffect>,
    val projectEffects: List<ProjectEffect>,
    val goalEffects: List<GoalEffect>,
    val statementEffects: List<StatementEffect>,
    val loanEffects: List<LoanEffect>,
    val settlementEffects: List<SettlementEffect>,
    val refundAllocationFacts: List<RefundAllocationFact> = emptyList(),
) {
    init {
        require(journalBundles.isNotEmpty() || settlementEffects.isNotEmpty())
        require(journalBundles.all { it.entry.role == JournalEntryRole.APPLY })
        require(economicEffects.all { it.polarity == EffectPolarity.APPLY })
        require(budgetEffects.all { it.polarity == EffectPolarity.APPLY })
        require(projectEffects.all { it.polarity == EffectPolarity.APPLY })
        require(goalEffects.all { it.polarity == EffectPolarity.APPLY })
        require(statementEffects.all { it.polarity == EffectPolarity.APPLY })
        require(loanEffects.all { it.polarity == EffectPolarity.APPLY })
        require(refundAllocationFacts.all { it.reversalOf == null })
    }
}

data class AccountingPlanningContext(
    val identities: PlanningIdentitySet,
    val createdAt: Instant,
    val deviceInstanceId: DeviceInstanceId,
    val references: PlanningReferenceData,
    val amountEvidence: List<FrozenAmountEvidence>,
    val currentFacts: CurrentFinancialFacts?,
) {
    init {
        require(amountEvidence.map { it.key }.toSet().size == amountEvidence.size)
    }

    fun amount(role: AmountRole, componentIndex: Int = 0): FrozenAmountEvidence? = amountEvidence.singleOrNull { it.key == AmountEvidenceKey(role, componentIndex) }
}
