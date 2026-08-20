@file:Suppress("TooManyFunctions")

package app.ledger.finance.domain

import app.ledger.core.common.CheckedArithmetic
import app.ledger.core.common.DomainError
import app.ledger.core.common.DomainResult
import java.math.BigDecimal
import java.math.BigInteger

internal data class PostingSpec(
    val ledger: LedgerAccountSnapshot,
    val side: DebitCredit,
    val accountAmount: PositiveMoney,
    val baseAmount: PositiveMoney,
    val valuationRate: BigDecimal?,
    val role: PostingRole,
)

internal data class JournalSpec(
    val postings: List<PostingSpec>,
)

internal data class EconomicEffectSpec(
    val journalIndex: Int,
    val nature: EconomicNature,
    val component: EconomicComponent,
    val isConsumption: Boolean,
    val baseAmount: PositiveMoney,
)

internal data class BudgetEffectSpec(
    val kind: BudgetEffectKind,
    val baseAmount: PositiveMoney,
)

internal data class ProjectEffectSpec(
    val kind: ProjectEffectKind,
    val baseAmount: PositiveMoney,
    val includedInMonthlyBudgetSnapshot: Boolean,
)

internal data class GoalEffectSpec(
    val kind: GoalEffectKind,
    val amount: PositiveMoney,
)

internal data class StatementEffectSpec(
    val creditAccountId: UserAccountId,
    val statementId: CreditStatementId?,
    val kind: StatementEffectKind,
    val amount: PositiveMoney,
    val manualAssignment: Boolean,
)

internal data class LoanEffectSpec(
    val contractId: LoanContractId,
    val trancheId: LoanTrancheId,
    val scheduleItemId: LoanScheduleItemId?,
    val kind: LoanEffectKind,
    val amount: PositiveMoney,
    val baseAmount: PositiveMoney,
)

internal data class SettlementEffectSpec(
    val activityId: SettlementActivityId,
    val participantId: ParticipantId,
    val kind: SettlementEffectKind,
    val signedDeltaMinor: Long,
    val currency: app.ledger.core.money.CurrencyCode,
)

internal data class AccountingRuleOutput(
    val journals: List<JournalSpec>,
    val amounts: List<FrozenAmountEvidence>,
    val economic: List<EconomicEffectSpec> = emptyList(),
    val budget: List<BudgetEffectSpec> = emptyList(),
    val project: List<ProjectEffectSpec> = emptyList(),
    val goal: List<GoalEffectSpec> = emptyList(),
    val statement: List<StatementEffectSpec> = emptyList(),
    val loan: List<LoanEffectSpec> = emptyList(),
    val settlement: List<SettlementEffectSpec> = emptyList(),
)

internal object AccountingRuleEngine {
    fun plan(
        book: Book,
        input: NewTransactionInput<TransactionPayload>,
        planning: PlanningSnapshot,
    ): DomainResult<AccountingRuleOutput> = try {
        val context = planning.accountingContext ?: reject("planningSnapshot.accountingContext")
        if (input.context.source == TransactionSource.RECURRENCE_CANDIDATE) {
            rejectInvariant("INV-028")
        }
        val session = RuleSession(book, input, planning, context)
        DomainResult.Success(
            when (val payload = input.payload) {
                is ExpensePayload -> session.expense(payload)
                is IncomePayload -> session.income(payload)
                is TransferPayload -> session.transfer(payload)
                is RefundPayload -> session.refund(payload)
                is CreditPaymentPayload -> session.creditPayment(payload)
                is LoanDisbursementPayload -> session.loanDisbursement(payload)
                is LoanPaymentPayload -> session.loanPayment(payload)
                is BalanceAdjustmentPayload -> session.balanceAdjustment(payload)
                is FxExchangePayload -> session.fxExchange(payload)
                is SettlementPaymentPayload -> session.settlementPayment(payload)
                is OpeningBalancePayload -> session.openingBalance(payload)
            },
        )
    } catch (rejected: RuleRejected) {
        DomainResult.Failure(rejected.violation)
    }
}

@Suppress("LargeClass", "TooManyFunctions")
private class RuleSession(
    private val book: Book,
    private val input: NewTransactionInput<TransactionPayload>,
    private val snapshot: PlanningSnapshot,
    private val context: AccountingPlanningContext,
) {
    private val references: PlanningReferenceData = context.references
    private val baseCurrency = book.baseCurrency

    fun expense(payload: ExpensePayload): AccountingRuleOutput {
        val category = category(payload.classification)
        val primary = amount(AmountRole.PRIMARY, expectedUserInput = payload.primaryAmount)
        if (payload.settlementShares.isNotEmpty()) {
            return settlementExpense(payload, category, primary)
        }
        val payerLine = when (val payer = payload.payer) {
            is ExpensePayer.LocalAccount -> {
                val account = account(payer.accountAmount, setOf(LedgerAccountClass.ASSET, LedgerAccountClass.LIABILITY))
                validateCard(payer.cardId, account.account.id)
                requireAmountAccount(primary, payer.accountAmount)
                PostingSpec(
                    ledger = account.ledger,
                    side = DebitCredit.CREDIT,
                    accountAmount = primary.accountAmount,
                    baseAmount = primary.baseAmount,
                    valuationRate = primary.accountToBase?.evidence?.rate,
                    role = account.ledger.accountClass.postingRole(),
                )
            }
            is ExpensePayer.ExternalParticipant -> {
                val settlement = references.settlement(payer.activityId, payer.participantId)
                    ?: reject("expense.externalParticipantLedger")
                require(primary.accountAmount.currency == settlement.ledger.currency, "expense.settlementCurrency")
                PostingSpec(
                    ledger = settlement.ledger,
                    side = DebitCredit.CREDIT,
                    accountAmount = primary.accountAmount,
                    baseAmount = primary.baseAmount,
                    valuationRate = primary.accountToBase?.evidence?.rate,
                    role = PostingRole.SETTLEMENT,
                )
            }
        }
        val expenseLedger = system(expenseSystemCode(category.statisticalNature), baseCurrency)
        val journal = JournalSpec(
            listOf(
                systemPosting(expenseLedger, DebitCredit.DEBIT, primary.baseAmount, PostingRole.EXPENSE),
                payerLine,
            ),
        )
        return AccountingRuleOutput(
            journals = listOf(journal),
            amounts = usedAmounts(),
            economic = listOf(
                EconomicEffectSpec(
                    journalIndex = 0,
                    nature = EconomicNature.EXPENSE,
                    component = EconomicComponent.PRIMARY,
                    isConsumption = category.statisticalNature == StatisticalNature.CONSUMPTION_EXPENSE,
                    baseAmount = primary.baseAmount,
                ),
            ),
            budget = budgetUse(primary.baseAmount),
            project = projectUse(primary.baseAmount),
            goal = goalSpend(
                primary.accountAmount,
                (payload.payer as? ExpensePayer.LocalAccount)?.accountAmount?.accountId,
            ),
            statement = expenseStatement(payload, primary.accountAmount),
            settlement = settlementShares(payload),
        )
    }

    @Suppress("LongMethod", "ComplexCondition")
    private fun settlementExpense(
        payload: ExpensePayload,
        category: PlanningCategory,
        primary: FrozenAmountEvidence,
    ): AccountingRuleOutput {
        val activityId = payload.settlementActivityId ?: reject("expense.settlementActivity")
        SettlementSharePolicy.validate(primary.userInput.minor.value, payload.settlementShares).orReject()
        val self = selfParticipant()
        val selfIndex = payload.settlementShares.indexOfFirst { it.participantId == self.id }
        require(selfIndex >= 0, "expense.settlementSelf")
        val payerShare = payload.settlementShares.single { it.paidMinor > 0L }
        val owed = payload.settlementShares.map(SettlementShare::owedMinor)
        val baseAllocations = proportionalMinorAllocation(primary.baseAmount.minor.value, owed)
        val accountAllocations = proportionalMinorAllocation(primary.accountAmount.minor.value, owed)
        val selfOwedMinor = owed[selfIndex]
        val selfBaseMinor = baseAllocations[selfIndex]
        val selfAccountMinor = accountAllocations[selfIndex]
        val expenseLedger = system(expenseSystemCode(category.statisticalNature), baseCurrency)
        val debitLines = mutableListOf<PostingSpec>()
        if (selfBaseMinor > 0L) {
            debitLines += systemPosting(
                expenseLedger,
                DebitCredit.DEBIT,
                positive(selfBaseMinor, baseCurrency),
                PostingRole.EXPENSE,
            )
        }
        val localPayer = payload.payer as? ExpensePayer.LocalAccount
        if (localPayer != null) {
            require(payerShare.participantId == self.id, "expense.localSettlementPayer")
            val account = account(localPayer.accountAmount, setOf(LedgerAccountClass.ASSET, LedgerAccountClass.LIABILITY))
            validateCard(localPayer.cardId, account.account.id)
            requireAmountAccount(primary, localPayer.accountAmount)
            payload.settlementShares.forEachIndexed { index, share ->
                if (share.participantId != self.id && share.owedMinor > 0L) {
                    val ledger = references.settlement(activityId, share.participantId)?.ledger
                        ?: reject("expense.settlementParticipantLedger")
                    require(ledger.currency == primary.userInput.currency, "expense.settlementCurrency")
                    debitLines += PostingSpec(
                        ledger,
                        DebitCredit.DEBIT,
                        positive(share.owedMinor, ledger.currency),
                        positive(baseAllocations[index], baseCurrency),
                        null,
                        PostingRole.SETTLEMENT,
                    )
                }
            }
            debitLines += PostingSpec(
                account.ledger,
                DebitCredit.CREDIT,
                primary.accountAmount,
                primary.baseAmount,
                primary.accountToBase?.evidence?.rate,
                account.ledger.accountClass.postingRole(),
            )
        } else {
            val externalPayer = payload.payer as ExpensePayer.ExternalParticipant
            require(
                externalPayer.activityId == activityId && payerShare.participantId == externalPayer.participantId &&
                    externalPayer.participantId != self.id,
                "expense.externalSettlementPayer",
            )
            if (selfOwedMinor > 0L) {
                val ledger = references.settlement(activityId, externalPayer.participantId)?.ledger
                    ?: reject("expense.externalParticipantLedger")
                require(ledger.currency == primary.userInput.currency, "expense.settlementCurrency")
                debitLines += PostingSpec(
                    ledger,
                    DebitCredit.CREDIT,
                    positive(selfOwedMinor, ledger.currency),
                    positive(selfBaseMinor, baseCurrency),
                    null,
                    PostingRole.SETTLEMENT,
                )
            }
        }
        val journals = if (debitLines.isEmpty()) emptyList() else listOf(JournalSpec(debitLines))
        val selfBase = selfBaseMinor.takeIf { it > 0L }?.let { positive(it, baseCurrency) }
        val selfAccount = selfAccountMinor.takeIf { it > 0L }?.let { positive(it, primary.accountAmount.currency) }
        return AccountingRuleOutput(
            journals = journals,
            amounts = usedAmounts(),
            economic = selfBase?.let {
                listOf(
                    EconomicEffectSpec(
                        0,
                        EconomicNature.EXPENSE,
                        EconomicComponent.SELF_SETTLEMENT_SHARE,
                        category.statisticalNature == StatisticalNature.CONSUMPTION_EXPENSE,
                        it,
                    ),
                )
            }.orEmpty(),
            budget = selfBase?.let(::budgetUse).orEmpty(),
            project = selfBase?.let(::projectUse).orEmpty(),
            goal = if (localPayer != null && selfAccount != null) {
                goalSpend(selfAccount, localPayer.accountAmount.accountId)
            } else {
                emptyList()
            },
            statement = localPayer?.let { expenseStatement(payload, primary.accountAmount) }.orEmpty(),
            settlement = settlementShares(payload),
        )
    }

    fun income(payload: IncomePayload): AccountingRuleOutput {
        val category = category(payload.classification)
        val receiving = account(
            payload.receivingAmount,
            setOf(LedgerAccountClass.ASSET, LedgerAccountClass.LIABILITY),
        )
        val primary = amount(AmountRole.PRIMARY, expectedUserInput = payload.primaryAmount)
        requireAmountAccount(primary, payload.receivingAmount)
        val incomeLedger = system(incomeSystemCode(category.statisticalNature), baseCurrency)
        return AccountingRuleOutput(
            journals = listOf(
                JournalSpec(
                    listOf(
                        PostingSpec(
                            receiving.ledger,
                            DebitCredit.DEBIT,
                            primary.accountAmount,
                            primary.baseAmount,
                            primary.accountToBase?.evidence?.rate,
                            receiving.ledger.accountClass.postingRole(),
                        ),
                        systemPosting(incomeLedger, DebitCredit.CREDIT, primary.baseAmount, PostingRole.INCOME),
                    ),
                ),
            ),
            amounts = usedAmounts(),
            economic = listOf(
                EconomicEffectSpec(
                    0,
                    EconomicNature.INCOME,
                    EconomicComponent.PRIMARY,
                    isConsumption = false,
                    primary.baseAmount,
                ),
            ),
            statement = creditStatementEffect(
                receiving,
                StatementEffectKind.ADJUSTMENT,
                primary.accountAmount,
            ),
        )
    }

    fun transfer(payload: TransferPayload): AccountingRuleOutput {
        val outgoingAccount = account(payload.outgoing, setOf(LedgerAccountClass.ASSET))
        val incomingAccount = account(payload.incoming, setOf(LedgerAccountClass.ASSET))
        validateCard(payload.sourceCardId, outgoingAccount.account.id)
        val outgoing = amount(AmountRole.OUTGOING)
        val incoming = amount(AmountRole.INCOMING)
        requireAmountAccount(outgoing, payload.outgoing)
        requireAmountAccount(incoming, payload.incoming)
        require(outgoing.baseAmount == incoming.baseAmount, "transfer.baseAmount")
        return AccountingRuleOutput(
            journals = transferJournals(outgoingAccount, incomingAccount, outgoing, incoming),
            amounts = usedAmounts(),
        )
    }

    fun refund(payload: RefundPayload): AccountingRuleOutput {
        val classification = payload.classification?.let(::category)
        val receiving = account(
            payload.receivingAmount,
            setOf(LedgerAccountClass.ASSET, LedgerAccountClass.LIABILITY),
        )
        validateCard(payload.receivingCardId, receiving.account.id)
        val refund = amount(AmountRole.REFUND)
        requireAmountAccount(refund, payload.receivingAmount)
        validateRefundAllocations(payload, refund)
        val expenseLedger = system(
            expenseSystemCode(
                classification?.statisticalNature ?: StatisticalNature.NON_CONSUMPTION_EXPENSE,
            ),
            baseCurrency,
        )
        val budget = if (payload.budgetPolicy == RefundBudgetPolicy.DO_NOT_RESTORE) {
            emptyList()
        } else {
            budgetRestore(refund.baseAmount)
        }
        return AccountingRuleOutput(
            journals = listOf(
                JournalSpec(
                    listOf(
                        PostingSpec(
                            receiving.ledger,
                            DebitCredit.DEBIT,
                            refund.accountAmount,
                            refund.baseAmount,
                            refund.accountToBase?.evidence?.rate,
                            receiving.ledger.accountClass.postingRole(),
                        ),
                        systemPosting(expenseLedger, DebitCredit.CREDIT, refund.baseAmount, PostingRole.EXPENSE),
                    ),
                ),
            ),
            amounts = usedAmounts(),
            economic = listOf(
                EconomicEffectSpec(
                    0,
                    EconomicNature.CONTRA_EXPENSE,
                    EconomicComponent.REFUND,
                    isConsumption = classification?.statisticalNature == StatisticalNature.CONSUMPTION_EXPENSE,
                    refund.baseAmount,
                ),
            ),
            budget = budget,
            project = projectRestore(refund.baseAmount, payload.projectPolicy),
            goal = goalRestore(refund.accountAmount, receiving.account.id, payload.goalPolicy),
            statement = creditStatementEffect(
                receiving,
                StatementEffectKind.REFUND,
                refund.accountAmount,
            ),
            settlement = refundSettlementShares(payload),
        )
    }

    fun creditPayment(payload: CreditPaymentPayload): AccountingRuleOutput {
        if (payload.generationMode == AutoGenerationMode.CONFIRMATION_CANDIDATE) {
            rejectInvariant("INV-028")
        }
        val paymentAccount = account(payload.payment, setOf(LedgerAccountClass.ASSET))
        val creditAccount = account(payload.creditAccountAmount, setOf(LedgerAccountClass.LIABILITY))
        require(creditAccount.account.type == UserAccountType.CREDIT, "creditPayment.creditAccount")
        require(creditAccount.account.id == payload.creditAccountId, "creditPayment.creditAccountId")
        val outgoing = amount(AmountRole.OUTGOING)
        val incoming = amount(AmountRole.INCOMING)
        requireAmountAccount(outgoing, payload.payment)
        requireAmountAccount(incoming, payload.creditAccountAmount)
        val settlementFee = payload.settlementFee?.let { expected ->
            amount(AmountRole.FEE, expectedUserInput = expected).also { fee ->
                require(fee.accountAmount.currency == paymentAccount.account.currency, "creditPayment.settlementFeeCurrency")
            }
        }
        val expectedOutgoingBase = Math.addExact(
            incoming.baseAmount.minor.value,
            settlementFee?.baseAmount?.minor?.value ?: 0L,
        )
        require(outgoing.baseAmount.minor.value == expectedOutgoingBase, "creditPayment.baseAmount")
        val allocations = creditPaymentAllocations(payload, incoming.accountAmount)
        val journals = if (settlementFee == null) {
            transferJournals(paymentAccount, creditAccount, outgoing, incoming)
        } else {
            listOf(
                JournalSpec(
                    listOf(
                        posting(creditAccount.ledger, DebitCredit.DEBIT, incoming),
                        systemPosting(
                            system(SystemLedgerCode.SYSTEM_EXPENSE_NON_CONSUMPTION, baseCurrency),
                            DebitCredit.DEBIT,
                            settlementFee.baseAmount,
                            PostingRole.EXPENSE,
                        ),
                        posting(paymentAccount.ledger, DebitCredit.CREDIT, outgoing),
                    ),
                ),
            )
        }
        return AccountingRuleOutput(
            journals = journals,
            amounts = usedAmounts(),
            economic = settlementFee?.let { fee ->
                listOf(
                    EconomicEffectSpec(
                        journalIndex = 0,
                        nature = EconomicNature.EXPENSE,
                        component = EconomicComponent.FEE,
                        isConsumption = false,
                        baseAmount = fee.baseAmount,
                    ),
                )
            }.orEmpty(),
            statement = allocations.map { allocation ->
                StatementEffectSpec(
                    creditAccountId = payload.creditAccountId,
                    statementId = allocation.statementId,
                    kind = StatementEffectKind.PAYMENT,
                    amount = allocation.amount,
                    manualAssignment = input.context.statementAssignment?.mode != null &&
                        input.context.statementAssignment.mode != StatementAssignmentMode.AUTOMATIC,
                )
            },
        )
    }

    fun loanDisbursement(payload: LoanDisbursementPayload): AccountingRuleOutput {
        val receiving = account(payload.receivingAmount, setOf(LedgerAccountClass.ASSET))
        val incoming = amount(AmountRole.INCOMING)
        val principal = amount(AmountRole.PRINCIPAL)
        requireAmountAccount(incoming, payload.receivingAmount)
        require(principal.accountAmount == payload.liabilityAmount, "loanDisbursement.liabilityAmount")
        require(incoming.baseAmount == principal.baseAmount, "loanDisbursement.baseAmount")
        val allocations = if (payload.allocations.isEmpty()) {
            val loan = references.loan(payload.loanContractId) ?: reject("loanDisbursement.loanLedger")
            listOf(LoanDisbursementAllocation(loan.trancheId, principal.accountAmount, principal.baseAmount))
        } else {
            payload.allocations
        }
        require(
            CheckedArithmetic.sum(allocations.map { it.amount.minor.value }).orReject() == principal.accountAmount.minor.value,
            "loanDisbursement.allocationAmount",
        )
        require(
            CheckedArithmetic.sum(allocations.map { it.baseAmount.minor.value }).orReject() == principal.baseAmount.minor.value,
            "loanDisbursement.allocationBaseAmount",
        )
        val loanAllocations = allocations.map { allocation ->
            val loan = references.loan(payload.loanContractId, allocation.trancheId)
                ?: reject("loanDisbursement.loanTranche")
            require(allocation.amount.currency == loan.ledger.currency, "loanDisbursement.currency")
            require(allocation.baseAmount.currency == baseCurrency, "loanDisbursement.baseCurrency")
            loan to allocation
        }
        return AccountingRuleOutput(
            journals = listOf(
                JournalSpec(
                    listOf(posting(receiving.ledger, DebitCredit.DEBIT, incoming)) + loanAllocations.map { (loan, allocation) ->
                        PostingSpec(
                            loan.ledger,
                            DebitCredit.CREDIT,
                            allocation.amount,
                            allocation.baseAmount,
                            principal.accountToBase?.evidence?.rate,
                            PostingRole.LIABILITY,
                        )
                    },
                ),
            ),
            amounts = usedAmounts(),
            loan = loanAllocations.map { (loan, allocation) ->
                LoanEffectSpec(
                    payload.loanContractId,
                    loan.trancheId,
                    null,
                    LoanEffectKind.DISBURSEMENT,
                    allocation.amount,
                    allocation.baseAmount,
                )
            },
        )
    }

    fun loanPayment(payload: LoanPaymentPayload): AccountingRuleOutput {
        require(
            payload.classification == null ||
                payload.components.interest != null ||
                payload.components.fee != null ||
                payload.components.penalty != null,
            "loanPayment.classification",
        )
        payload.classification?.let(::category)
        val paymentAccount = account(payload.payment, setOf(LedgerAccountClass.ASSET))
        if (payload.allocations.map { it.trancheId }.distinct().size > 1) {
            return multiTrancheLoanPayment(payload, paymentAccount)
        }
        val allocatedTranche = payload.allocations.map { it.trancheId }.distinct().singleOrNull()
        val loan = if (allocatedTranche == null) {
            references.loan(payload.loanContractId)
        } else {
            references.loan(payload.loanContractId, allocatedTranche)
        } ?: reject("loanPayment.loanLedger")
        val outgoing = amount(AmountRole.OUTGOING)
        requireAmountAccount(outgoing, payload.payment)
        val components = loanComponents(payload, loan)
        require(
            sumBase(components.map { it.amount.baseAmount }) == outgoing.baseAmount.minor.value,
            "loanPayment.components",
        )
        val postings = components.map { component ->
            val ledger = if (component.effectKind == LoanEffectKind.PRINCIPAL_PAYMENT) {
                loan.ledger
            } else {
                system(SystemLedgerCode.SYSTEM_EXPENSE_NON_CONSUMPTION, baseCurrency)
            }
            posting(ledger, DebitCredit.DEBIT, component.amount)
        } + posting(paymentAccount.ledger, DebitCredit.CREDIT, outgoing)
        val economic = components.filter { it.effectKind != LoanEffectKind.PRINCIPAL_PAYMENT }.map { component ->
            EconomicEffectSpec(
                journalIndex = 0,
                nature = EconomicNature.EXPENSE,
                component = component.economicComponent,
                isConsumption = false,
                baseAmount = component.amount.baseAmount,
            )
        }
        return AccountingRuleOutput(
            journals = listOf(JournalSpec(postings)),
            amounts = usedAmounts(),
            economic = economic,
            budget = budgetUseIfClassified(economic.map { it.baseAmount }),
            project = projectUseIfClassified(economic.map { it.baseAmount }),
            loan = components.map { component ->
                LoanEffectSpec(
                    payload.loanContractId,
                    loan.trancheId,
                    component.scheduleItemId,
                    component.effectKind,
                    component.amount.accountAmount,
                    component.amount.baseAmount,
                )
            },
        )
    }

    private fun multiTrancheLoanPayment(
        payload: LoanPaymentPayload,
        paymentAccount: PlanningAccount,
    ): AccountingRuleOutput {
        val outgoing = amount(AmountRole.OUTGOING)
        requireAmountAccount(outgoing, payload.payment)
        val evidence = listOfNotNull(
            payload.components.principal?.let { AmountRole.PRINCIPAL to amount(AmountRole.PRINCIPAL, expectedUserInput = it) },
            payload.components.interest?.let { AmountRole.INTEREST to amount(AmountRole.INTEREST, expectedUserInput = it) },
            payload.components.fee?.let { AmountRole.FEE to amount(AmountRole.FEE, expectedUserInput = it) },
            payload.components.penalty?.let { AmountRole.PENALTY to amount(AmountRole.PENALTY, expectedUserInput = it) },
        ).toMap()
        require(evidence.isNotEmpty(), "loanPayment.components")
        LoanPaymentComponent.entries.forEach { component ->
            val role = AmountRole.valueOf(component.name)
            val componentAllocations = payload.allocations.filter { it.component == component }
            val componentEvidence = evidence[role]
            require(componentAllocations.isNotEmpty() == (componentEvidence != null), "loanPayment.allocationComponent")
            componentEvidence?.let { frozen ->
                require(
                    CheckedArithmetic.sum(componentAllocations.map { it.amount.minor.value }).orReject() ==
                        frozen.accountAmount.minor.value,
                    "loanPayment.allocationAmount",
                )
                require(
                    CheckedArithmetic.sum(componentAllocations.map { it.baseAmount.minor.value }).orReject() ==
                        frozen.baseAmount.minor.value,
                    "loanPayment.allocationBaseAmount",
                )
            }
        }
        require(
            CheckedArithmetic.sum(payload.allocations.map { it.baseAmount.minor.value }).orReject() == outgoing.baseAmount.minor.value,
            "loanPayment.components",
        )
        val resolved = payload.allocations.map { allocation ->
            val loan = references.loan(payload.loanContractId, allocation.trancheId) ?: reject("loanPayment.loanTranche")
            require(allocation.amount.currency == loan.ledger.currency, "loanPayment.componentCurrency")
            require(allocation.baseAmount.currency == baseCurrency, "loanPayment.componentBaseCurrency")
            loan to allocation
        }
        val postings = resolved.map { (loan, allocation) ->
            val ledger = if (allocation.component == LoanPaymentComponent.PRINCIPAL) {
                loan.ledger
            } else {
                system(SystemLedgerCode.SYSTEM_EXPENSE_NON_CONSUMPTION, baseCurrency)
            }
            PostingSpec(
                ledger,
                DebitCredit.DEBIT,
                if (ledger.accountClass == LedgerAccountClass.LIABILITY) allocation.amount else allocation.baseAmount,
                allocation.baseAmount,
                evidence.getValue(AmountRole.valueOf(allocation.component.name)).accountToBase?.evidence?.rate,
                ledger.accountClass.postingRole(),
            )
        } + posting(paymentAccount.ledger, DebitCredit.CREDIT, outgoing)
        val economic = payload.allocations.filter { it.component != LoanPaymentComponent.PRINCIPAL }.map { allocation ->
            EconomicEffectSpec(
                0,
                EconomicNature.EXPENSE,
                when (allocation.component) {
                    LoanPaymentComponent.INTEREST -> EconomicComponent.INTEREST
                    LoanPaymentComponent.FEE -> EconomicComponent.FEE
                    LoanPaymentComponent.PENALTY -> EconomicComponent.PENALTY
                    LoanPaymentComponent.PRINCIPAL -> reject("loanPayment.component")
                },
                isConsumption = false,
                allocation.baseAmount,
            )
        }
        return AccountingRuleOutput(
            journals = listOf(JournalSpec(postings)),
            amounts = usedAmounts(),
            economic = economic,
            budget = budgetUseIfClassified(economic.map { it.baseAmount }),
            project = projectUseIfClassified(economic.map { it.baseAmount }),
            loan = resolved.map { (loan, allocation) ->
                LoanEffectSpec(
                    payload.loanContractId,
                    loan.trancheId,
                    allocation.scheduleItemId,
                    when (allocation.component) {
                        LoanPaymentComponent.PRINCIPAL -> LoanEffectKind.PRINCIPAL_PAYMENT
                        LoanPaymentComponent.INTEREST -> LoanEffectKind.INTEREST_PAYMENT
                        LoanPaymentComponent.FEE -> LoanEffectKind.FEE_PAYMENT
                        LoanPaymentComponent.PENALTY -> LoanEffectKind.PENALTY_PAYMENT
                    },
                    allocation.amount,
                    allocation.baseAmount,
                )
            },
        )
    }

    fun balanceAdjustment(payload: BalanceAdjustmentPayload): AccountingRuleOutput {
        val account = account(
            payload.accountAmount,
            setOf(LedgerAccountClass.ASSET, LedgerAccountClass.LIABILITY),
        )
        val amount = amount(AmountRole.PRIMARY)
        requireAmountAccount(amount, payload.accountAmount)
        val normalSide = account.ledger.normalSide
        val accountSide = if (payload.direction == BalanceAdjustmentDirection.INCREASE) {
            normalSide
        } else {
            normalSide.opposite()
        }
        val adjustment = system(SystemLedgerCode.SYSTEM_BALANCE_ADJUSTMENT, baseCurrency)
        return AccountingRuleOutput(
            journals = listOf(
                JournalSpec(
                    listOf(
                        posting(account.ledger, accountSide, amount),
                        systemPosting(adjustment, accountSide.opposite(), amount.baseAmount, PostingRole.EQUITY),
                    ),
                ),
            ),
            amounts = usedAmounts(),
            statement = creditStatementEffect(
                account,
                StatementEffectKind.ADJUSTMENT,
                amount.accountAmount,
            ),
        )
    }

    fun fxExchange(payload: FxExchangePayload): AccountingRuleOutput {
        val outgoingAccount = account(payload.outgoing, setOf(LedgerAccountClass.ASSET))
        val incomingAccount = account(payload.incoming, setOf(LedgerAccountClass.ASSET))
        val outgoing = amount(AmountRole.OUTGOING)
        val incoming = amount(AmountRole.INCOMING)
        requireAmountAccount(outgoing, payload.outgoing)
        requireAmountAccount(incoming, payload.incoming)
        require(outgoing.accountAmount.currency != incoming.accountAmount.currency, "fxExchange.currency")
        val journals = transferJournals(outgoingAccount, incomingAccount, outgoing, incoming).toMutableList()
        val difference = subtractExact(outgoing.baseAmount.minor.value, incoming.baseAmount.minor.value)
        val hasClassifiableComponent = difference < 0L || (difference > 0L && payload.spreadCost != null)
        require(payload.classification == null || hasClassifiableComponent, "fxExchange.classification")
        payload.classification?.let(::category)
        payload.spreadCost?.let { spread ->
            require(
                difference > 0L &&
                    spread.currency == baseCurrency &&
                    spread.minor.value == difference,
                "fxExchange.spreadCost",
            )
        }
        val economic = mutableListOf<EconomicEffectSpec>()
        if (difference != 0L) {
            journals += fxDifferenceJournal(difference, payload.spreadCost)
            economic += fxEconomicEffect(difference, payload.spreadCost, journals.lastIndex)
        }
        return AccountingRuleOutput(
            journals = journals.toList(),
            amounts = usedAmounts(),
            economic = economic.toList(),
        )
    }

    fun settlementPayment(payload: SettlementPaymentPayload): AccountingRuleOutput {
        val settlementEffects = settlementPaymentEffects(payload)
        if (!payload.selfParticipates) {
            return AccountingRuleOutput(
                journals = emptyList(),
                amounts = emptyList(),
                settlement = settlementEffects,
            )
        }
        val localAmount = payload.localAccountAmount ?: reject("settlementPayment.localAccountAmount")
        val account = account(localAmount, setOf(LedgerAccountClass.ASSET))
        val amount = amount(AmountRole.SETTLEMENT, expectedUserInput = payload.amount)
        requireAmountAccount(amount, localAmount)
        val self = selfParticipant()
        val counterparty = if (payload.payerParticipantId == self.id) {
            payload.payeeParticipantId
        } else {
            require(payload.payeeParticipantId == self.id, "settlementPayment.selfParticipant")
            payload.payerParticipantId
        }
        val settlement = references.settlement(payload.activityId, counterparty)
            ?: reject("settlementPayment.positionLedger")
        require(settlement.ledger.currency == amount.accountAmount.currency, "settlementPayment.currency")
        val selfPays = payload.payerParticipantId == self.id
        return AccountingRuleOutput(
            journals = listOf(
                JournalSpec(
                    listOf(
                        posting(account.ledger, if (selfPays) DebitCredit.CREDIT else DebitCredit.DEBIT, amount),
                        posting(
                            settlement.ledger,
                            if (selfPays) DebitCredit.DEBIT else DebitCredit.CREDIT,
                            amount,
                        ),
                    ),
                ),
            ),
            amounts = usedAmounts(),
            settlement = settlementEffects,
        )
    }

    fun openingBalance(payload: OpeningBalancePayload): AccountingRuleOutput {
        val account = account(
            payload.accountAmount,
            setOf(LedgerAccountClass.ASSET, LedgerAccountClass.LIABILITY),
        )
        require(payload.side == account.ledger.normalSide, "openingBalance.side")
        val amount = amount(AmountRole.PRIMARY)
        requireAmountAccount(amount, payload.accountAmount)
        val equity = system(SystemLedgerCode.SYSTEM_OPENING_EQUITY, baseCurrency)
        return AccountingRuleOutput(
            journals = listOf(
                JournalSpec(
                    listOf(
                        posting(account.ledger, payload.side, amount),
                        systemPosting(equity, payload.side.opposite(), amount.baseAmount, PostingRole.EQUITY),
                    ),
                ),
            ),
            amounts = usedAmounts(),
            statement = creditStatementEffect(
                account,
                StatementEffectKind.ADJUSTMENT,
                amount.accountAmount,
            ),
        )
    }

    private val consumedAmounts = mutableListOf<FrozenAmountEvidence>()

    private fun amount(
        role: AmountRole,
        componentIndex: Int = 0,
        expectedUserInput: PositiveMoney? = null,
    ): FrozenAmountEvidence {
        val amount = context.amount(role, componentIndex) ?: reject("amountEvidence.${role.name}.$componentIndex")
        require(amount.baseAmount.currency == baseCurrency, "amountEvidence.baseCurrency")
        require(expectedUserInput == null || amount.userInput == expectedUserInput, "amountEvidence.userInput")
        if (amount !in consumedAmounts) consumedAmounts += amount
        return amount
    }

    private fun usedAmounts(): List<FrozenAmountEvidence> = consumedAmounts.toList()

    private fun account(amount: AccountAmount, classes: Set<LedgerAccountClass>): PlanningAccount {
        val account = references.account(amount.accountId) ?: reject("account.reference")
        require(account.account.status == EntityStatus.ACTIVE, "account.status")
        require(account.ledger.status == EntityStatus.ACTIVE, "ledgerAccount.status")
        require(account.ledger.accountClass in classes, "ledgerAccount.class")
        return account
    }

    private fun requireAmountAccount(evidence: FrozenAmountEvidence, amount: AccountAmount) {
        require(evidence.relatedAccountId == amount.accountId, "amountEvidence.relatedAccount")
        require(evidence.accountAmount == amount.amount, "amountEvidence.accountAmount")
    }

    private fun category(assignment: CategoryAssignment): PlanningCategory {
        val category = references.category(assignment.categoryId) ?: reject("category.reference")
        require(category.status == CategoryStatus.ACTIVE, "category.status")
        require(category.direction == assignment.direction, "category.direction")
        require(category.statisticalNature == assignment.statisticalNatureSnapshot, "category.statisticalNature")
        return category
    }

    private fun validateCard(cardId: PaymentCardId?, accountId: UserAccountId) {
        if (cardId == null) return
        val card = references.card(cardId) ?: reject("card.reference")
        require(card.status == EntityStatus.ACTIVE && card.accountId == accountId, "card.account")
    }

    private fun system(
        code: SystemLedgerCode,
        currency: app.ledger.core.money.CurrencyCode,
    ): LedgerAccountSnapshot = references.system(code, currency) ?: reject("systemLedger.${code.name}.${currency.value}")

    private fun posting(
        ledger: LedgerAccountSnapshot,
        side: DebitCredit,
        amount: FrozenAmountEvidence,
    ): PostingSpec {
        require(ledger.currency == amount.accountAmount.currency, "posting.accountCurrency")
        return PostingSpec(
            ledger,
            side,
            amount.accountAmount,
            amount.baseAmount,
            amount.accountToBase?.evidence?.rate,
            ledger.accountClass.postingRole(),
        )
    }

    private fun systemPosting(
        ledger: LedgerAccountSnapshot,
        side: DebitCredit,
        baseAmount: PositiveMoney,
        role: PostingRole,
    ): PostingSpec {
        require(ledger.currency == baseAmount.currency, "systemPosting.currency")
        return PostingSpec(ledger, side, baseAmount, baseAmount, null, role)
    }

    private fun transferJournals(
        outgoingAccount: PlanningAccount,
        incomingAccount: PlanningAccount,
        outgoing: FrozenAmountEvidence,
        incoming: FrozenAmountEvidence,
    ): List<JournalSpec> {
        if (outgoing.accountAmount.currency == incoming.accountAmount.currency) {
            return listOf(
                JournalSpec(
                    listOf(
                        posting(incomingAccount.ledger, DebitCredit.DEBIT, incoming),
                        posting(outgoingAccount.ledger, DebitCredit.CREDIT, outgoing),
                    ),
                ),
            )
        }
        val clearing = system(SystemLedgerCode.SYSTEM_FX_CLEARING, baseCurrency)
        return listOf(
            JournalSpec(
                listOf(
                    systemPosting(clearing, DebitCredit.DEBIT, outgoing.baseAmount, PostingRole.CLEARING),
                    posting(outgoingAccount.ledger, DebitCredit.CREDIT, outgoing),
                ),
            ),
            JournalSpec(
                listOf(
                    posting(incomingAccount.ledger, DebitCredit.DEBIT, incoming),
                    systemPosting(clearing, DebitCredit.CREDIT, incoming.baseAmount, PostingRole.CLEARING),
                ),
            ),
        )
    }

    private fun fxDifferenceJournal(difference: Long, spreadCost: PositiveMoney?): JournalSpec {
        val amountMinor = absExact(difference)
        val amount = positive(amountMinor, baseCurrency)
        val clearing = system(SystemLedgerCode.SYSTEM_FX_CLEARING, baseCurrency)
        val isCost = difference > 0L
        val code = when {
            isCost && spreadCost != null -> SystemLedgerCode.SYSTEM_FX_COST
            isCost -> SystemLedgerCode.SYSTEM_FX_ROUNDING
            else -> SystemLedgerCode.SYSTEM_FX_GAIN
        }
        val result = system(code, baseCurrency)
        return if (isCost) {
            JournalSpec(
                listOf(
                    systemPosting(result, DebitCredit.DEBIT, amount, result.accountClass.postingRole()),
                    systemPosting(clearing, DebitCredit.CREDIT, amount, PostingRole.CLEARING),
                ),
            )
        } else {
            JournalSpec(
                listOf(
                    systemPosting(clearing, DebitCredit.DEBIT, amount, PostingRole.CLEARING),
                    systemPosting(result, DebitCredit.CREDIT, amount, result.accountClass.postingRole()),
                ),
            )
        }
    }

    private fun fxEconomicEffect(
        difference: Long,
        spreadCost: PositiveMoney?,
        journalIndex: Int,
    ): EconomicEffectSpec {
        val amount = positive(absExact(difference), baseCurrency)
        if (difference > 0L && spreadCost != null) {
            require(spreadCost == amount, "fxExchange.spreadCost")
            return EconomicEffectSpec(
                journalIndex,
                EconomicNature.EXPENSE,
                EconomicComponent.FX_COST,
                isConsumption = false,
                amount,
            )
        }
        return EconomicEffectSpec(
            journalIndex,
            if (difference > 0L) EconomicNature.EQUITY else EconomicNature.INCOME,
            EconomicComponent.FX_COST,
            isConsumption = false,
            amount,
        )
    }

    private fun budgetUse(amount: PositiveMoney): List<BudgetEffectSpec> = if (
        input.context.budgetMonth != null &&
        input.payload.classification != null &&
        input.context.projectId?.let { references.project(it)?.includedInMonthlyBudget } != false
    ) {
        listOf(BudgetEffectSpec(BudgetEffectKind.USE, amount))
    } else {
        emptyList()
    }

    private fun budgetRestore(amount: PositiveMoney): List<BudgetEffectSpec> = if (
        input.context.budgetMonth != null &&
        input.payload.classification != null &&
        input.context.projectId?.let { references.project(it)?.includedInMonthlyBudget } != false
    ) {
        listOf(BudgetEffectSpec(BudgetEffectKind.RESTORE, amount))
    } else {
        emptyList()
    }

    private fun projectUse(amount: PositiveMoney): List<ProjectEffectSpec> {
        val projectId = input.context.projectId ?: return emptyList()
        val project = references.project(projectId) ?: reject("project.reference")
        require(project.status == ProjectStatus.ACTIVE, "project.status")
        return listOf(ProjectEffectSpec(ProjectEffectKind.USE, amount, project.includedInMonthlyBudget))
    }

    private fun projectRestore(
        amount: PositiveMoney,
        policy: RefundProjectPolicy,
    ): List<ProjectEffectSpec> = if (policy == RefundProjectPolicy.DO_NOT_RESTORE) {
        emptyList()
    } else {
        val projectId = input.context.projectId ?: return emptyList()
        val project = references.project(projectId) ?: reject("project.reference")
        listOf(ProjectEffectSpec(ProjectEffectKind.RESTORE, amount, project.includedInMonthlyBudget))
    }

    private fun goalSpend(
        accountAmount: PositiveMoney,
        accountId: UserAccountId?,
    ): List<GoalEffectSpec> {
        val goalId = input.context.goalId ?: return emptyList()
        val goal = references.goal(goalId) ?: reject("goal.reference")
        require(goal.status == GoalStatus.ACTIVE, "goal.status")
        require(accountId != null && goal.accountId == accountId, "goal.account")
        require(goal.currency == accountAmount.currency, "goal.currency")
        return listOf(GoalEffectSpec(GoalEffectKind.SPEND, accountAmount))
    }

    private fun goalRestore(
        amount: PositiveMoney,
        accountId: UserAccountId,
        policy: RefundGoalPolicy,
    ): List<GoalEffectSpec> = if (policy == RefundGoalPolicy.DO_NOT_RESTORE) {
        emptyList()
    } else {
        val goalId = input.context.goalId ?: return emptyList()
        val goal = references.goal(goalId) ?: reject("goal.reference")
        require(goal.accountId == accountId, "goal.account")
        require(goal.currency == amount.currency, "goal.currency")
        listOf(GoalEffectSpec(GoalEffectKind.RESTORE, amount))
    }

    @Suppress("ReturnCount")
    private fun expenseStatement(
        payload: ExpensePayload,
        amount: PositiveMoney,
    ): List<StatementEffectSpec> {
        val payer = payload.payer as? ExpensePayer.LocalAccount ?: return emptyList()
        val account = references.account(payer.accountAmount.accountId) ?: return emptyList()
        val kind = if (payload.installmentPlanId == null) {
            StatementEffectKind.CHARGE
        } else {
            StatementEffectKind.INSTALLMENT_POSTING
        }
        return creditStatementEffect(account, kind, amount)
    }

    private fun creditStatementEffect(
        account: PlanningAccount,
        kind: StatementEffectKind,
        amount: PositiveMoney,
    ): List<StatementEffectSpec> = if (account.account.type == UserAccountType.CREDIT) {
        listOf(
            StatementEffectSpec(
                account.account.id,
                input.context.statementAssignment?.statementId,
                kind,
                amount,
                input.context.statementAssignment?.mode != null &&
                    input.context.statementAssignment.mode != StatementAssignmentMode.AUTOMATIC,
            ),
        )
    } else {
        emptyList()
    }

    private fun settlementShares(payload: ExpensePayload): List<SettlementEffectSpec> {
        val activityId = payload.settlementActivityId ?: return emptyList()
        if (payload.settlementShares.isEmpty()) reject("expense.settlementShares")
        val currency = payload.primaryAmount.currency
        val effects = mutableListOf<SettlementEffectSpec>()
        payload.settlementShares.forEach { share ->
            if (share.paidMinor > 0L) {
                effects += SettlementEffectSpec(
                    activityId,
                    share.participantId,
                    SettlementEffectKind.PAID_FOR_GROUP,
                    share.paidMinor,
                    currency,
                )
            }
            if (share.owedMinor > 0L) {
                effects += SettlementEffectSpec(
                    activityId,
                    share.participantId,
                    SettlementEffectKind.OWED_SHARE,
                    negateExact(share.owedMinor),
                    currency,
                )
            }
        }
        val signedTotal = CheckedArithmetic.sum(effects.map { it.signedDeltaMinor }).orReject()
        if (signedTotal != 0L) rejectInvariant("INV-022")
        return effects.toList()
    }

    private fun refundSettlementShares(payload: RefundPayload): List<SettlementEffectSpec> {
        val activityId = payload.settlementActivityId ?: return emptyList()
        if (payload.settlementShares.isEmpty()) reject("refund.settlementShares")
        val currency = payload.allocations.firstOrNull()?.amountInOriginalCurrency?.currency
            ?: payload.receivingAmount.amount.currency
        val effects = mutableListOf<SettlementEffectSpec>()
        payload.settlementShares.forEach { share ->
            if (share.paidMinor > 0L) {
                effects += SettlementEffectSpec(
                    activityId,
                    share.participantId,
                    SettlementEffectKind.PAID_FOR_GROUP,
                    negateExact(share.paidMinor),
                    currency,
                )
            }
            if (share.owedMinor > 0L) {
                effects += SettlementEffectSpec(
                    activityId,
                    share.participantId,
                    SettlementEffectKind.OWED_SHARE,
                    share.owedMinor,
                    currency,
                )
            }
        }
        val signedTotal = CheckedArithmetic.sum(effects.map { it.signedDeltaMinor }).orReject()
        require(signedTotal == 0L, "refund.settlementBalance")
        return effects
    }

    private fun settlementPaymentEffects(payload: SettlementPaymentPayload): List<SettlementEffectSpec> = listOf(
        SettlementEffectSpec(
            payload.activityId,
            payload.payerParticipantId,
            SettlementEffectKind.SETTLEMENT_PAID,
            payload.amount.minor.value,
            payload.amount.currency,
        ),
        SettlementEffectSpec(
            payload.activityId,
            payload.payeeParticipantId,
            SettlementEffectKind.SETTLEMENT_RECEIVED,
            negateExact(payload.amount.minor.value),
            payload.amount.currency,
        ),
    )

    private fun validateRefundAllocations(
        payload: RefundPayload,
        refund: FrozenAmountEvidence,
    ) {
        if (payload.independent) {
            require(payload.allocations.isEmpty(), "refund.allocations")
            return
        }
        require(payload.allocations.isNotEmpty(), "refund.allocations")
        require(
            payload.allocations.all { it.amountInBaseCurrency.currency == baseCurrency },
            "refund.allocationBaseCurrency",
        )
        require(
            sumBase(payload.allocations.map { it.amountInBaseCurrency }) == refund.baseAmount.minor.value,
            "refund.allocationBaseAmount",
        )
    }

    private fun creditPaymentAllocations(
        payload: CreditPaymentPayload,
        paidAmount: PositiveMoney,
    ): List<CreditPaymentAllocation> {
        require(payload.allocations.isNotEmpty(), "creditPayment.allocations")
        require(
            payload.allocations.all { it.amount.currency == paidAmount.currency },
            "creditPayment.allocationCurrency",
        )
        val allocated = CheckedArithmetic.sum(payload.allocations.map { it.amount.minor.value }).orReject()
        require(allocated == paidAmount.minor.value, "creditPayment.allocationAmount")
        return payload.allocations
    }

    private fun selfParticipant(): Participant = snapshot.participants.singleOrNull {
        it.isSelf && it.status == EntityStatus.ACTIVE
    } ?: reject("participant.self")

    private data class LoanComponentDraft(
        val amount: FrozenAmountEvidence,
        val effectKind: LoanEffectKind,
        val economicComponent: EconomicComponent,
        val scheduleItemId: LoanScheduleItemId?,
    )

    private fun loanComponents(
        payload: LoanPaymentPayload,
        loan: PlanningLoanLedger,
    ): List<LoanComponentDraft> {
        val values = listOfNotNull(
            payload.components.principal?.let {
                loanComponent(payload, loan, AmountRole.PRINCIPAL, it, LoanEffectKind.PRINCIPAL_PAYMENT, EconomicComponent.PRIMARY)
            },
            payload.components.interest?.let {
                loanComponent(payload, loan, AmountRole.INTEREST, it, LoanEffectKind.INTEREST_PAYMENT, EconomicComponent.INTEREST)
            },
            payload.components.fee?.let {
                loanComponent(payload, loan, AmountRole.FEE, it, LoanEffectKind.FEE_PAYMENT, EconomicComponent.FEE)
            },
            payload.components.penalty?.let {
                loanComponent(payload, loan, AmountRole.PENALTY, it, LoanEffectKind.PENALTY_PAYMENT, EconomicComponent.PENALTY)
            },
        )
        if (values.isEmpty()) reject("loanPayment.components")
        return values
    }

    @Suppress("LongParameterList")
    private fun loanComponent(
        payload: LoanPaymentPayload,
        loan: PlanningLoanLedger,
        role: AmountRole,
        expected: PositiveMoney,
        effectKind: LoanEffectKind,
        economicComponent: EconomicComponent,
    ): LoanComponentDraft {
        val amount = amount(role, expectedUserInput = expected)
        require(amount.accountAmount.currency == loan.ledger.currency, "loanPayment.componentCurrency")
        val scheduleItemId = payload.allocations.firstOrNull { allocation ->
            allocation.trancheId == loan.trancheId && allocation.component.name == role.name
        }?.scheduleItemId
        return LoanComponentDraft(amount, effectKind, economicComponent, scheduleItemId)
    }

    private fun budgetUseIfClassified(amounts: List<PositiveMoney>): List<BudgetEffectSpec> {
        if (input.payload.classification == null || input.context.budgetMonth == null || amounts.isEmpty()) return emptyList()
        return listOf(BudgetEffectSpec(BudgetEffectKind.USE, positive(sumBase(amounts), baseCurrency)))
    }

    private fun projectUseIfClassified(amounts: List<PositiveMoney>): List<ProjectEffectSpec> {
        if (input.payload.classification == null || amounts.isEmpty()) return emptyList()
        return projectUse(positive(sumBase(amounts), baseCurrency))
    }

    private fun sumBase(amounts: List<PositiveMoney>): Long {
        require(amounts.all { it.currency == baseCurrency }, "amount.baseCurrency")
        return CheckedArithmetic.sum(amounts.map { it.minor.value }).orReject()
    }
}

private fun LedgerAccountClass.postingRole(): PostingRole = when (this) {
    LedgerAccountClass.ASSET -> PostingRole.ASSET
    LedgerAccountClass.LIABILITY -> PostingRole.LIABILITY
    LedgerAccountClass.INCOME -> PostingRole.INCOME
    LedgerAccountClass.EXPENSE -> PostingRole.EXPENSE
    LedgerAccountClass.EQUITY -> PostingRole.EQUITY
    LedgerAccountClass.SETTLEMENT -> PostingRole.SETTLEMENT
    LedgerAccountClass.CLEARING -> PostingRole.CLEARING
}

private fun DebitCredit.opposite(): DebitCredit = when (this) {
    DebitCredit.DEBIT -> DebitCredit.CREDIT
    DebitCredit.CREDIT -> DebitCredit.DEBIT
}

private fun expenseSystemCode(nature: StatisticalNature): SystemLedgerCode = when (nature) {
    StatisticalNature.CONSUMPTION_EXPENSE -> SystemLedgerCode.SYSTEM_EXPENSE_CONSUMPTION
    StatisticalNature.NON_CONSUMPTION_EXPENSE -> SystemLedgerCode.SYSTEM_EXPENSE_NON_CONSUMPTION
    else -> reject("expense.statisticalNature")
}

private fun incomeSystemCode(nature: StatisticalNature): SystemLedgerCode = when (nature) {
    StatisticalNature.REGULAR_INCOME -> SystemLedgerCode.SYSTEM_INCOME_REGULAR
    StatisticalNature.NON_RECURRING_INCOME -> SystemLedgerCode.SYSTEM_INCOME_NON_RECURRING
    else -> reject("income.statisticalNature")
}

private fun proportionalMinorAllocation(totalMinor: Long, weights: List<Long>): List<Long> {
    require(totalMinor > 0L && weights.isNotEmpty() && weights.all { it >= 0L }, "settlement.allocation")
    val weightTotal = CheckedArithmetic.sum(weights).orReject()
    require(weightTotal > 0L, "settlement.weightTotal")
    val total = BigInteger.valueOf(totalMinor)
    val denominator = BigInteger.valueOf(weightTotal)
    val allocated = weights.map { weight ->
        CheckedArithmetic.toLongExact(total.multiply(BigInteger.valueOf(weight)).divide(denominator)).orReject()
    }.toMutableList()
    val allocatedTotal = CheckedArithmetic.sum(allocated).orReject()
    var residual = Math.subtractExact(totalMinor, allocatedTotal)
    var index = 0
    while (residual > 0L) {
        if (weights[index] > 0L) {
            allocated[index] = Math.addExact(allocated[index], 1L)
            residual--
        }
        index = (index + 1) % weights.size
    }
    return allocated
}

private fun positive(minor: Long, currency: app.ledger.core.money.CurrencyCode): PositiveMoney = when (
    val result = PositiveMoney.from(app.ledger.core.money.Money(minor, currency))
) {
    is DomainResult.Success -> result.value
    is DomainResult.Failure -> throw RuleRejected(result.error)
}

private fun subtractExact(left: Long, right: Long): Long = CheckedArithmetic.subtract(left, right).orReject()

private fun negateExact(value: Long): Long = CheckedArithmetic.negate(value).orReject()

private fun absExact(value: Long): Long = CheckedArithmetic.abs(value).orReject()

private fun <T> DomainResult<T>.orReject(): T = when (this) {
    is DomainResult.Success -> value
    is DomainResult.Failure -> throw RuleRejected(error)
}

private class RuleRejected(val violation: DomainError) : RuntimeException(null, null, false, false)

private fun reject(field: String): Nothing = throw RuleRejected(DomainViolation.InvalidField(field))

private fun rejectInvariant(id: String): Nothing = throw RuleRejected(DomainViolation.Invariant(id))

private fun require(condition: Boolean, field: String) {
    if (!condition) reject(field)
}
