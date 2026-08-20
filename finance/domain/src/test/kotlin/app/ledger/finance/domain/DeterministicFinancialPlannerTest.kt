package app.ledger.finance.domain

import app.ledger.core.common.CommandId
import app.ledger.core.common.DomainResult
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId

@Suppress("LargeClass")
class DeterministicFinancialPlannerTest {
    @Test
    fun `settlement expense records only self consumption and exact receivable or payable`() {
        val references = PlannerFixtures.references()
        val classification = CategoryAssignment(
            PlannerFixtures.expenseCategoryId,
            CategoryDirection.EXPENSE,
            StatisticalNature.CONSUMPTION_EXPENSE,
        )
        val selfPaysPayload = ExpensePayload(
            classification,
            ExpensePayer.LocalAccount(PlannerFixtures.accountAmount(PlannerFixtures.bankJpyId, 200L, references), null),
            positive(200L, PlannerFixtures.jpy),
            PlannerFixtures.activityId,
            listOf(
                SettlementShare(PlannerFixtures.selfId, 200L, 100L, null, 0L),
                SettlementShare(PlannerFixtures.friendId, 0L, 100L, null, 0L),
            ),
            null,
        )
        val selfUnsigned = RecordExpenseCommand(
            CommandId(stableId(9_100L)),
            hash(0),
            NewTransactionInput(PlannerFixtures.inputContext(), selfPaysPayload),
        )
        val selfCommand = selfUnsigned.copy(payloadHash = CanonicalFinancialHash.command(selfUnsigned))
        val selfPlan = DeterministicFinancialPlanner.plan(
            selfCommand,
            PlannerFixtures.snapshot(
                listOf(PlannerFixtures.sameCurrencyEvidence(AmountRole.PRIMARY, 200L, PlannerFixtures.bankJpyId)),
                seed = 9_200L,
            ),
        ).success()
        selfPlan.journalBundles.single().entry.baseDebitTotalMinor shouldBe 200L
        selfPlan.journalBundles.single().entry.baseCreditTotalMinor shouldBe 200L
        selfPlan.economicEffects.single().baseAmount.minor.value shouldBe 100L
        selfPlan.budgetEffects.single().baseAmount.minor.value shouldBe 100L
        selfPlan.settlementEffects.sumOf(SettlementEffect::signedDeltaMinor) shouldBe 0L

        val otherPaysPayload = selfPaysPayload.copy(
            payer = ExpensePayer.ExternalParticipant(PlannerFixtures.friendId, PlannerFixtures.activityId),
            primaryAmount = positive(160L, PlannerFixtures.jpy),
            settlementShares = listOf(
                SettlementShare(PlannerFixtures.selfId, 0L, 80L, null, 0L),
                SettlementShare(PlannerFixtures.friendId, 160L, 80L, null, 0L),
            ),
        )
        val otherUnsigned = RecordExpenseCommand(
            CommandId(stableId(9_300L)),
            hash(0),
            NewTransactionInput(PlannerFixtures.inputContext(), otherPaysPayload),
        )
        val otherCommand = otherUnsigned.copy(payloadHash = CanonicalFinancialHash.command(otherUnsigned))
        val otherPlan = DeterministicFinancialPlanner.plan(
            otherCommand,
            PlannerFixtures.snapshot(
                listOf(PlannerFixtures.sameCurrencyEvidence(AmountRole.PRIMARY, 160L, null)),
                seed = 9_400L,
            ),
        ).success()
        otherPlan.journalBundles.single().entry.baseDebitTotalMinor shouldBe 80L
        otherPlan.journalBundles.single().entry.baseCreditTotalMinor shouldBe 80L
        otherPlan.economicEffects.single().baseAmount.minor.value shouldBe 80L
        otherPlan.budgetEffects.single().baseAmount.minor.value shouldBe 80L
    }

    @Test
    fun `ordinary expense is deterministic balanced and emits four-layer effects`() {
        val amount = PlannerFixtures.sameCurrencyEvidence(
            AmountRole.PRIMARY,
            1_000L,
            PlannerFixtures.bankJpyId,
        )
        val snapshot = PlannerFixtures.snapshot(
            amountEvidence = listOf(amount),
            sourceBook = book(localRevision = 9L),
        )
        val command = PlannerFixtures.expenseCommand(
            minor = 1_000L,
            context = PlannerFixtures.inputContext(
                project = PlannerFixtures.projectId,
                goal = PlannerFixtures.goalId,
            ),
        )

        val first = DeterministicFinancialPlanner.plan(command, snapshot).success()
        val second = DeterministicFinancialPlanner.plan(command, snapshot).success()

        first shouldBe second
        first.journalBundles shouldHaveSize 1
        first.journalBundles.single().entry.baseDebitTotalMinor shouldBe 1_000L
        first.journalBundles.single().entry.baseCreditTotalMinor shouldBe 1_000L
        first.revisionAmounts shouldHaveSize 3
        first.economicEffects.single().isConsumption.shouldBeTrue()
        first.budgetEffects shouldHaveSize 1
        first.projectEffects shouldHaveSize 1
        first.goalEffects shouldHaveSize 1
        first.commit.rootHash shouldBe CanonicalFinancialHash.commitRoot(
            first.commandId,
            first.payloadHash,
            first.targetLocalRevision,
            listOf(
                first.transactions.single().contentHash,
                first.revisions.single().contentHash,
                CanonicalFinancialHash.evidenceAndEffects(
                    first.revisionAmounts,
                    first.fxRateSnapshots,
                    first.economicEffects,
                    first.budgetEffects,
                    first.projectEffects,
                    first.goalEffects,
                    first.statementEffects,
                    first.loanEffects,
                    first.settlementEffects,
                ),
            ) +
                first.journalBundles.map { it.entry.contentHash },
        )
        val tampered = first.copy(
            economicEffects = first.economicEffects.map { it.copy(isConsumption = !it.isConsumption) },
        )
        (FinancialMutationPlanValidator.validate(command, snapshot, tampered) is DomainResult.Failure)
            .shouldBeTrue()
    }

    @Test
    fun `income transfer opening and adjustment rules preserve their frozen semantics`() {
        val references = PlannerFixtures.references()
        val incomeAmount = PlannerFixtures.accountAmount(PlannerFixtures.bankJpyId, 400L, references)
        val incomeInput = NewTransactionInput(
            PlannerFixtures.inputContext(),
            IncomePayload(
                CategoryAssignment(
                    PlannerFixtures.incomeCategoryId,
                    CategoryDirection.INCOME,
                    StatisticalNature.REGULAR_INCOME,
                ),
                incomeAmount,
                positive(400L, PlannerFixtures.jpy),
            ),
        )
        val income = canonicalIncome(incomeInput)
        val incomePlan = DeterministicFinancialPlanner.plan(
            income,
            PlannerFixtures.snapshot(
                listOf(
                    PlannerFixtures.sameCurrencyEvidence(
                        AmountRole.PRIMARY,
                        400L,
                        PlannerFixtures.bankJpyId,
                    ),
                ),
            ),
        ).success()
        incomePlan.economicEffects.single().nature shouldBe EconomicNature.INCOME
        incomePlan.budgetEffects shouldHaveSize 0

        val transfer = canonicalTransfer(
            TransferPayload(
                PlannerFixtures.accountAmount(PlannerFixtures.bankJpyId, 300L, references),
                PlannerFixtures.accountAmount(PlannerFixtures.bankJpyTwoId, 300L, references),
                null,
            ),
        )
        val transferPlan = DeterministicFinancialPlanner.plan(
            transfer,
            PlannerFixtures.snapshot(
                listOf(
                    PlannerFixtures.sameCurrencyEvidence(AmountRole.OUTGOING, 300L, PlannerFixtures.bankJpyId),
                    PlannerFixtures.sameCurrencyEvidence(AmountRole.INCOMING, 300L, PlannerFixtures.bankJpyTwoId),
                ),
                seed = 11_000L,
            ),
        ).success()
        transferPlan.economicEffects shouldHaveSize 0
        transferPlan.journalBundles.single().entry.baseDebitTotalMinor shouldBe 300L
        userAssetBaseDelta(transferPlan, references) shouldBe 0L

        val opening = canonicalOpening(
            OpeningBalancePayload(
                PlannerFixtures.accountAmount(PlannerFixtures.bankJpyId, 500L, references),
                java.time.LocalDate.of(2026, 7, 31),
                DebitCredit.DEBIT,
            ),
        )
        val openingPlan = DeterministicFinancialPlanner.plan(
            opening,
            PlannerFixtures.snapshot(
                listOf(PlannerFixtures.sameCurrencyEvidence(AmountRole.PRIMARY, 500L, PlannerFixtures.bankJpyId)),
                seed = 12_000L,
            ),
        ).success()
        openingPlan.economicEffects shouldHaveSize 0
        openingPlan.budgetEffects shouldHaveSize 0

        val adjustment = canonicalAdjustment(
            BalanceAdjustmentPayload(
                PlannerFixtures.accountAmount(PlannerFixtures.bankJpyId, 50L, references),
                BalanceAdjustmentDirection.DECREASE,
                null,
            ),
        )
        val adjustmentPlan = DeterministicFinancialPlanner.plan(
            adjustment,
            PlannerFixtures.snapshot(
                listOf(PlannerFixtures.sameCurrencyEvidence(AmountRole.PRIMARY, 50L, PlannerFixtures.bankJpyId)),
                seed = 13_000L,
            ),
        ).success()
        adjustmentPlan.economicEffects shouldHaveSize 0
        adjustmentPlan.journalBundles.single().postings.single {
            it.ledgerAccountId == references.account(PlannerFixtures.bankJpyId)!!.ledger.id
        }.side shouldBe DebitCredit.CREDIT
    }

    @Test
    fun `every credit account mutation emits a statement effect`() {
        val references = PlannerFixtures.references()
        val creditAmount = PlannerFixtures.accountAmount(PlannerFixtures.creditJpyId, 200L, references)
        val evidence = PlannerFixtures.sameCurrencyEvidence(
            AmountRole.PRIMARY,
            200L,
            PlannerFixtures.creditJpyId,
        )
        val income = canonicalIncome(
            NewTransactionInput(
                PlannerFixtures.inputContext(),
                IncomePayload(
                    CategoryAssignment(
                        PlannerFixtures.incomeCategoryId,
                        CategoryDirection.INCOME,
                        StatisticalNature.REGULAR_INCOME,
                    ),
                    creditAmount,
                    positive(200L, PlannerFixtures.jpy),
                ),
            ),
        )
        val opening = canonicalOpening(
            OpeningBalancePayload(creditAmount, java.time.LocalDate.of(2026, 8, 1), DebitCredit.CREDIT),
        )
        val adjustment = canonicalAdjustment(
            BalanceAdjustmentPayload(creditAmount, BalanceAdjustmentDirection.INCREASE, null),
        )

        listOf(income, opening, adjustment).forEachIndexed { index, command ->
            val plan = DeterministicFinancialPlanner.plan(
                command,
                PlannerFixtures.snapshot(listOf(evidence), seed = 13_500L + index * 300L),
            ).success()
            plan.statementEffects.single().kind shouldBe StatementEffectKind.ADJUSTMENT
            plan.statementEffects.single().creditAccountId shouldBe PlannerFixtures.creditJpyId
        }
    }

    @Test
    fun `goal effect requires the payer account bound to the goal`() {
        val context = PlannerFixtures.inputContext(goal = PlannerFixtures.goalId)
        val command = PlannerFixtures.expenseCommand(
            minor = 100L,
            accountId = PlannerFixtures.bankJpyTwoId,
            context = context,
            commandSeed = 13_900L,
        )
        val snapshot = PlannerFixtures.snapshot(
            listOf(
                PlannerFixtures.sameCurrencyEvidence(
                    AmountRole.PRIMARY,
                    100L,
                    PlannerFixtures.bankJpyTwoId,
                ),
            ),
            seed = 13_950L,
        )

        DeterministicFinancialPlanner.plan(command, snapshot) shouldBe
            DomainResult.Failure(DomainViolation.InvalidField("goal.account"))
    }

    @Test
    fun `foreign exchange freezes evidence and uses clearing cost rounding and gain accounts`() {
        val references = PlannerFixtures.references()
        val outgoing = PlannerFixtures.accountAmount(PlannerFixtures.bankUsdId, 1_000L, references)
        val incoming = PlannerFixtures.accountAmount(PlannerFixtures.bankJpyId, 1_490L, references)
        val payload = FxExchangePayload(
            CategoryAssignment(
                PlannerFixtures.expenseCategoryId,
                CategoryDirection.EXPENSE,
                StatisticalNature.CONSUMPTION_EXPENSE,
            ),
            outgoing,
            incoming,
            FxValuationPolicy.PROVIDED_RATE,
            positive(10L, PlannerFixtures.jpy),
        )
        val command = canonicalFx(payload)
        val snapshot = PlannerFixtures.snapshot(
            listOf(
                PlannerFixtures.usdToJpyEvidence(
                    AmountRole.OUTGOING,
                    usdMinor = 1_000L,
                    jpyMinor = 1_500L,
                    accountId = PlannerFixtures.bankUsdId,
                    rate = "150",
                    id = 14_001L,
                ),
                PlannerFixtures.sameCurrencyEvidence(AmountRole.INCOMING, 1_490L, PlannerFixtures.bankJpyId),
            ),
            seed = 14_100L,
        )

        val plan = DeterministicFinancialPlanner.plan(command, snapshot).success()

        plan.journalBundles shouldHaveSize 3
        plan.journalBundles.forEach { it.entry.baseDebitTotalMinor shouldBe it.entry.baseCreditTotalMinor }
        plan.fxRateSnapshots shouldHaveSize 1
        plan.revisionAmounts.single {
            it.role == AmountRole.OUTGOING && it.representation == AmountRepresentation.USER_INPUT
        }.fxRateSnapshotId shouldBe null
        plan.revisionAmounts.single {
            it.role == AmountRole.OUTGOING && it.representation == AmountRepresentation.ACCOUNT
        }.fxRateSnapshotId shouldBe null
        plan.revisionAmounts.single {
            it.role == AmountRole.OUTGOING && it.representation == AmountRepresentation.BASE
        }.fxRateSnapshotId shouldBe FxRateSnapshotId(stableId(14_001L))
        plan.economicEffects.single().component shouldBe EconomicComponent.FX_COST
        plan.economicEffects.single().baseAmount.minor.value shouldBe 10L
        postingCodes(plan, references).contains(SystemLedgerCode.SYSTEM_FX_CLEARING).shouldBeTrue()
        postingCodes(plan, references).contains(SystemLedgerCode.SYSTEM_FX_COST).shouldBeTrue()

        val rounded = PlannerFixtures.usdToJpyEvidence(
            AmountRole.OUTGOING,
            usdMinor = 1_000L,
            jpyMinor = 1_490L,
            accountId = PlannerFixtures.bankUsdId,
            rate = "149.05",
            id = 14_002L,
        )
        rounded.baseAmount.minor.value shouldBe 1_490L
    }

    @Test
    fun `classification is rejected when loan payment or exchange has no expense or income component`() {
        val references = PlannerFixtures.references()
        val classification = CategoryAssignment(
            PlannerFixtures.nonConsumptionCategoryId,
            CategoryDirection.EXPENSE,
            StatisticalNature.NON_CONSUMPTION_EXPENSE,
        )
        val principalOnly = LoanPaymentPayload(
            classification = classification,
            loanContractId = PlannerFixtures.loanContractId,
            payment = PlannerFixtures.accountAmount(PlannerFixtures.bankJpyId, 1_000L, references),
            scheduleRevisionId = null,
            components = LoanPaymentComponents(
                principal = positive(1_000L, PlannerFixtures.jpy),
                interest = null,
                fee = null,
                penalty = null,
            ),
            allocations = emptyList(),
        )
        val loanResult = DeterministicFinancialPlanner.plan(
            canonicalLoanPayment(principalOnly),
            PlannerFixtures.snapshot(
                listOf(
                    PlannerFixtures.sameCurrencyEvidence(AmountRole.OUTGOING, 1_000L, PlannerFixtures.bankJpyId),
                    PlannerFixtures.sameCurrencyEvidence(AmountRole.PRINCIPAL, 1_000L, null),
                ),
                seed = 14_200L,
            ),
        )
        loanResult shouldBe DomainResult.Failure(DomainViolation.InvalidField("loanPayment.classification"))

        val zeroDifference = FxExchangePayload(
            classification,
            PlannerFixtures.accountAmount(PlannerFixtures.bankUsdId, 1_000L, references),
            PlannerFixtures.accountAmount(PlannerFixtures.bankJpyId, 1_500L, references),
            FxValuationPolicy.PROVIDED_RATE,
            null,
        )
        val fxResult = DeterministicFinancialPlanner.plan(
            canonicalFx(zeroDifference),
            PlannerFixtures.snapshot(
                listOf(
                    PlannerFixtures.usdToJpyEvidence(
                        AmountRole.OUTGOING,
                        usdMinor = 1_000L,
                        jpyMinor = 1_500L,
                        accountId = PlannerFixtures.bankUsdId,
                        rate = "150",
                        id = 14_201L,
                    ),
                    PlannerFixtures.sameCurrencyEvidence(AmountRole.INCOMING, 1_500L, PlannerFixtures.bankJpyId),
                ),
                seed = 14_300L,
            ),
        )
        fxResult shouldBe DomainResult.Failure(DomainViolation.InvalidField("fxExchange.classification"))

        val equityDifference = zeroDifference.copy(
            outgoing = PlannerFixtures.accountAmount(PlannerFixtures.bankUsdId, 1_000L, references),
        )
        val equityDifferenceResult = DeterministicFinancialPlanner.plan(
            canonicalFx(equityDifference),
            PlannerFixtures.snapshot(
                listOf(
                    PlannerFixtures.usdToJpyEvidence(
                        AmountRole.OUTGOING,
                        usdMinor = 1_000L,
                        jpyMinor = 1_600L,
                        accountId = PlannerFixtures.bankUsdId,
                        rate = "160",
                        id = 14_202L,
                    ),
                    PlannerFixtures.sameCurrencyEvidence(AmountRole.INCOMING, 1_500L, PlannerFixtures.bankJpyId),
                ),
                seed = 14_400L,
            ),
        )
        equityDifferenceResult shouldBe DomainResult.Failure(DomainViolation.InvalidField("fxExchange.classification"))
    }

    @Test
    fun `credit loan refund and settlement extensions generate their authoritative effects`() {
        val references = PlannerFixtures.references()
        val creditExpense = PlannerFixtures.expenseCommand(
            minor = 600L,
            accountId = PlannerFixtures.creditJpyId,
            commandSeed = 31_000L,
            references = references,
        )
        val creditPlan = DeterministicFinancialPlanner.plan(
            creditExpense,
            PlannerFixtures.snapshot(
                listOf(
                    PlannerFixtures.sameCurrencyEvidence(
                        AmountRole.PRIMARY,
                        600L,
                        PlannerFixtures.creditJpyId,
                    ),
                ),
                seed = 31_100L,
            ),
        ).success()
        creditPlan.statementEffects.single().kind shouldBe StatementEffectKind.CHARGE

        val loanPayload = LoanPaymentPayload(
            classification = CategoryAssignment(
                PlannerFixtures.nonConsumptionCategoryId,
                CategoryDirection.EXPENSE,
                StatisticalNature.NON_CONSUMPTION_EXPENSE,
            ),
            loanContractId = PlannerFixtures.loanContractId,
            payment = PlannerFixtures.accountAmount(PlannerFixtures.bankJpyId, 1_200L, references),
            scheduleRevisionId = null,
            components = LoanPaymentComponents(
                principal = positive(1_000L, PlannerFixtures.jpy),
                interest = positive(100L, PlannerFixtures.jpy),
                fee = positive(50L, PlannerFixtures.jpy),
                penalty = positive(50L, PlannerFixtures.jpy),
            ),
            allocations = emptyList(),
        )
        val loanCommand = canonicalLoanPayment(loanPayload)
        val loanPlan = DeterministicFinancialPlanner.plan(
            loanCommand,
            PlannerFixtures.snapshot(
                listOf(
                    PlannerFixtures.sameCurrencyEvidence(AmountRole.OUTGOING, 1_200L, PlannerFixtures.bankJpyId),
                    PlannerFixtures.sameCurrencyEvidence(AmountRole.PRINCIPAL, 1_000L, null),
                    PlannerFixtures.sameCurrencyEvidence(AmountRole.INTEREST, 100L, null),
                    PlannerFixtures.sameCurrencyEvidence(AmountRole.FEE, 50L, null),
                    PlannerFixtures.sameCurrencyEvidence(AmountRole.PENALTY, 50L, null),
                ),
                seed = 32_000L,
            ),
        ).success()
        loanPlan.loanEffects shouldHaveSize 4
        loanPlan.economicEffects shouldHaveSize 3
        loanPlan.economicEffects.all { !it.isConsumption }.shouldBeTrue()

        val settlementPayload = ExpensePayload(
            CategoryAssignment(
                PlannerFixtures.expenseCategoryId,
                CategoryDirection.EXPENSE,
                StatisticalNature.CONSUMPTION_EXPENSE,
            ),
            ExpensePayer.LocalAccount(
                PlannerFixtures.accountAmount(PlannerFixtures.bankJpyId, 1_000L, references),
                null,
            ),
            positive(1_000L, PlannerFixtures.jpy),
            PlannerFixtures.activityId,
            listOf(
                SettlementShare(PlannerFixtures.selfId, 1_000L, 400L, null, 0L),
                SettlementShare(PlannerFixtures.friendId, 0L, 600L, null, 0L),
            ),
            null,
        )
        val settlementCommand = canonicalExpense(settlementPayload, seed = 33_000L)
        val settlementPlan = DeterministicFinancialPlanner.plan(
            settlementCommand,
            PlannerFixtures.snapshot(
                listOf(
                    PlannerFixtures.sameCurrencyEvidence(AmountRole.PRIMARY, 1_000L, PlannerFixtures.bankJpyId),
                    PlannerFixtures.sameCurrencyEvidence(AmountRole.SELF_SHARE, 400L, null),
                ),
                seed = 33_100L,
            ),
        ).success()
        settlementPlan.economicEffects.single().baseAmount.minor.value shouldBe 400L
        CheckedArithmeticForTest.sum(settlementPlan.settlementEffects.map { it.signedDeltaMinor }) shouldBe 0L
    }

    @Test
    @Suppress("LongMethod")
    fun `refund credit payment loan disbursement and external settlement use closed advanced rules`() {
        val references = PlannerFixtures.references()
        val refundPayload = RefundPayload(
            classification = CategoryAssignment(
                PlannerFixtures.expenseCategoryId,
                CategoryDirection.EXPENSE,
                StatisticalNature.CONSUMPTION_EXPENSE,
            ),
            receivingAmount = PlannerFixtures.accountAmount(PlannerFixtures.bankJpyId, 200L, references),
            receivingCardId = null,
            allocations = emptyList(),
            independent = true,
            allowExcessOverride = false,
            budgetPolicy = RefundBudgetPolicy.RESTORE_REFUND_MONTH,
            projectPolicy = RefundProjectPolicy.DO_NOT_RESTORE,
            goalPolicy = RefundGoalPolicy.DO_NOT_RESTORE,
            accrualPolicy = RefundAccrualPolicy.REFUND_DATE,
        )
        val refundUnsigned = RecordRefundCommand(
            CommandId(stableId(34_000L)),
            hash(0),
            NewTransactionInput(
                PlannerFixtures.inputContext(
                    occurredAt = app.ledger.core.time.EffectiveTime.fromInstant(
                        Instant.parse("2026-08-15T03:00:00Z"),
                        ZoneId.of("Asia/Tokyo"),
                    ),
                ).copy(
                    accrualDate = java.time.LocalDate.of(2026, 7, 10),
                    budgetMonth = java.time.YearMonth.of(2026, 6),
                ),
                refundPayload,
            ),
        )
        val refund = refundUnsigned.copy(payloadHash = CanonicalFinancialHash.command(refundUnsigned))
        val refundPlan = DeterministicFinancialPlanner.plan(
            refund,
            PlannerFixtures.snapshot(
                listOf(PlannerFixtures.sameCurrencyEvidence(AmountRole.REFUND, 200L, PlannerFixtures.bankJpyId)),
                seed = 34_100L,
            ),
        ).success()
        refundPlan.economicEffects.single().nature shouldBe EconomicNature.CONTRA_EXPENSE
        refundPlan.economicEffects.single().accrualDate shouldBe java.time.LocalDate.of(2026, 7, 10)
        refundPlan.budgetEffects.single().kind shouldBe BudgetEffectKind.RESTORE
        refundPlan.budgetEffects.single().targetMonth shouldBe java.time.YearMonth.of(2026, 6)
        refundPlan.journalBundles.single().entry.effectiveAt.instant shouldBe Instant.parse("2026-08-15T03:00:00Z")

        val creditPayload = CreditPaymentPayload(
            payment = PlannerFixtures.accountAmount(PlannerFixtures.bankJpyId, 300L, references),
            creditAccountId = PlannerFixtures.creditJpyId,
            creditAccountAmount = PlannerFixtures.accountAmount(PlannerFixtures.creditJpyId, 300L, references),
            allocations = listOf(CreditPaymentAllocation(null, positive(300L, PlannerFixtures.jpy))),
            generationMode = AutoGenerationMode.FORMAL_TRANSACTION,
        )
        val creditUnsigned = RecordCreditPaymentCommand(
            CommandId(stableId(35_000L)),
            hash(0),
            NewTransactionInput(PlannerFixtures.inputContext(), creditPayload),
        )
        val credit = creditUnsigned.copy(payloadHash = CanonicalFinancialHash.command(creditUnsigned))
        val creditPlan = DeterministicFinancialPlanner.plan(
            credit,
            PlannerFixtures.snapshot(
                listOf(
                    PlannerFixtures.sameCurrencyEvidence(AmountRole.OUTGOING, 300L, PlannerFixtures.bankJpyId),
                    PlannerFixtures.sameCurrencyEvidence(AmountRole.INCOMING, 300L, PlannerFixtures.creditJpyId),
                ),
                seed = 35_100L,
            ),
        ).success()
        creditPlan.economicEffects shouldHaveSize 0
        creditPlan.statementEffects.single().kind shouldBe StatementEffectKind.PAYMENT

        val disbursementPayload = LoanDisbursementPayload(
            PlannerFixtures.loanContractId,
            PlannerFixtures.accountAmount(PlannerFixtures.bankJpyId, 5_000L, references),
            positive(5_000L, PlannerFixtures.jpy),
        )
        val disbursementUnsigned = RecordLoanDisbursementCommand(
            CommandId(stableId(36_000L)),
            hash(0),
            NewTransactionInput(PlannerFixtures.inputContext(), disbursementPayload),
        )
        val disbursement = disbursementUnsigned.copy(
            payloadHash = CanonicalFinancialHash.command(disbursementUnsigned),
        )
        val disbursementPlan = DeterministicFinancialPlanner.plan(
            disbursement,
            PlannerFixtures.snapshot(
                listOf(
                    PlannerFixtures.sameCurrencyEvidence(AmountRole.INCOMING, 5_000L, PlannerFixtures.bankJpyId),
                    PlannerFixtures.sameCurrencyEvidence(AmountRole.PRINCIPAL, 5_000L, null),
                ),
                seed = 36_100L,
            ),
        ).success()
        disbursementPlan.economicEffects shouldHaveSize 0
        disbursementPlan.loanEffects.single().kind shouldBe LoanEffectKind.DISBURSEMENT

        val externalSettlementPayload = SettlementPaymentPayload(
            PlannerFixtures.activityId,
            PlannerFixtures.friendId,
            ParticipantId(stableId(36_500L)),
            positive(100L, PlannerFixtures.jpy),
            localAccountAmount = null,
            selfParticipates = false,
        )
        val settlementUnsigned = RecordSettlementPaymentCommand(
            CommandId(stableId(37_000L)),
            hash(0),
            NewTransactionInput(PlannerFixtures.inputContext(), externalSettlementPayload),
            SettlementPaymentRecord(
                SettlementPaymentRecordId(stableId(37_050L)),
                PlannerFixtures.activityId,
                PlannerFixtures.friendId,
                ParticipantId(stableId(36_500L)),
                positive(100L, PlannerFixtures.jpy),
                PlannerFixtures.inputContext().occurredAt,
                null,
                false,
                BookCommitId(stableId(37_102L)),
                null,
            ),
        )
        val settlement = settlementUnsigned.copy(payloadHash = CanonicalFinancialHash.command(settlementUnsigned))
        val externalPlan = DeterministicFinancialPlanner.plan(
            settlement,
            PlannerFixtures.snapshot(emptyList(), seed = 37_100L),
        ).success()
        externalPlan.journalBundles shouldHaveSize 0
        externalPlan.settlementEffects shouldHaveSize 2
        CheckedArithmeticForTest.sum(externalPlan.settlementEffects.map { it.signedDeltaMinor }) shouldBe 0L
    }

    @Test
    fun `credit payment candidate produces no formal financial facts`() {
        val references = PlannerFixtures.references()
        val payload = CreditPaymentPayload(
            payment = PlannerFixtures.accountAmount(PlannerFixtures.bankJpyId, 300L, references),
            creditAccountId = PlannerFixtures.creditJpyId,
            creditAccountAmount = PlannerFixtures.accountAmount(PlannerFixtures.creditJpyId, 300L, references),
            allocations = listOf(CreditPaymentAllocation(null, positive(300L, PlannerFixtures.jpy))),
            generationMode = AutoGenerationMode.CONFIRMATION_CANDIDATE,
        )
        val unsigned = RecordCreditPaymentCommand(
            CommandId(stableId(38_000L)),
            hash(0),
            NewTransactionInput(PlannerFixtures.inputContext(), payload),
        )
        val command = unsigned.copy(payloadHash = CanonicalFinancialHash.command(unsigned))
        val snapshot = PlannerFixtures.snapshot(
            listOf(
                PlannerFixtures.sameCurrencyEvidence(AmountRole.OUTGOING, 300L, PlannerFixtures.bankJpyId),
                PlannerFixtures.sameCurrencyEvidence(AmountRole.INCOMING, 300L, PlannerFixtures.creditJpyId),
            ),
            seed = 38_100L,
        )

        DeterministicFinancialPlanner.plan(command, snapshot) shouldBe
            DomainResult.Failure(DomainViolation.Invariant("INV-028"))
    }

    @Test
    fun `edit reverses old facts and appends replacement without changing old effective time`() {
        val originalAmount = PlannerFixtures.sameCurrencyEvidence(
            AmountRole.PRIMARY,
            1_000L,
            PlannerFixtures.bankJpyId,
        )
        val originalSnapshot = PlannerFixtures.snapshot(listOf(originalAmount), seed = 40_000L)
        val originalCommand = PlannerFixtures.expenseCommand(1_000L, commandSeed = 40_500L)
        val originalPlan = DeterministicFinancialPlanner.plan(originalCommand, originalSnapshot).success()
        val originalFacts = PlannerFixtures.currentFacts(originalPlan)
        val replacementCommand = editCommand(originalPlan, 1_200L)
        val replacementEvidence = PlannerFixtures.sameCurrencyEvidence(
            AmountRole.PRIMARY,
            1_200L,
            PlannerFixtures.bankJpyId,
        )
        val editSnapshot = PlannerFixtures.snapshot(
            amountEvidence = listOf(replacementEvidence),
            seed = 41_000L,
            currentTransaction = originalPlan.transactions.single(),
            currentRevision = originalPlan.revisions.single(),
            currentFacts = originalFacts,
            sourceBook = PlannerFixtures.nextBook(originalPlan, originalSnapshot.book).copy(
                ruleSetVersion = ruleSetVersion(2),
            ),
        )

        val editPlan = DeterministicFinancialPlanner.plan(replacementCommand, editSnapshot).success()

        editPlan.journalBundles.map { it.entry.role }.toSet() shouldBe
            setOf(JournalEntryRole.APPLY, JournalEntryRole.REVERSE)
        ImmutableFactAudit.validateReversal(originalFacts, editPlan).success() shouldBe Unit
        val reverse = editPlan.journalBundles.single { it.entry.role == JournalEntryRole.REVERSE }
        reverse.entry.effectiveAt shouldBe originalPlan.journalBundles.single().entry.effectiveAt
        reverse.entry.createdCommitId shouldBe editPlan.commit.id
        reverse.entry.ruleSetVersion shouldBe ruleSetVersion(1)
        editPlan.journalBundles.single { it.entry.role == JournalEntryRole.APPLY }
            .entry.ruleSetVersion shouldBe ruleSetVersion(2)
        editPlan.revisions.single().revisionNumber shouldBe 2
        editPlan.revisions.single().previousRevisionId shouldBe originalPlan.revisions.single().id
        editPlan.economicEffects.single { it.polarity == EffectPolarity.APPLY }.baseAmount.minor.value shouldBe 1_200L
    }

    @Test
    fun `restoring a historical version appends RESTORE and reverses only current facts`() {
        val originalEvidence = PlannerFixtures.sameCurrencyEvidence(AmountRole.PRIMARY, 1_000L, PlannerFixtures.bankJpyId)
        val originalSnapshot = PlannerFixtures.snapshot(listOf(originalEvidence), seed = 41_200L)
        val originalCommand = PlannerFixtures.expenseCommand(1_000L, commandSeed = 41_300L)
        val original = DeterministicFinancialPlanner.plan(originalCommand, originalSnapshot).success()
        val editedCommand = editCommand(original, 1_700L)
        val editedEvidence = PlannerFixtures.sameCurrencyEvidence(AmountRole.PRIMARY, 1_700L, PlannerFixtures.bankJpyId)
        val editSnapshot = PlannerFixtures.snapshot(
            listOf(editedEvidence),
            seed = 41_400L,
            currentTransaction = original.transactions.single(),
            currentRevision = original.revisions.single(),
            currentFacts = PlannerFixtures.currentFacts(original),
            sourceBook = PlannerFixtures.nextBook(original, originalSnapshot.book),
        )
        val edited = DeterministicFinancialPlanner.plan(editedCommand, editSnapshot).success()
        val historicalSnapshot = PlannerFixtures.snapshot(
            listOf(originalEvidence),
            seed = 41_500L,
            currentTransaction = edited.transactions.single(),
            currentRevision = edited.revisions.single(),
            currentFacts = PlannerFixtures.currentFacts(edited),
            sourceBook = PlannerFixtures.nextBook(edited, editSnapshot.book),
        )
        val unsigned = RestoreHistoricalRevisionCommand(
            CommandId(stableId(41_600L)),
            edited.revisions.single().id,
            hash(0),
            edited.transactions.single().id,
            original.revisions.single().id,
            originalCommand.input,
            emptyList(),
        )
        val command = unsigned.copy(payloadHash = CanonicalFinancialHash.command(unsigned))

        val restored = DeterministicFinancialPlanner.plan(command, historicalSnapshot).success()

        restored.revisions.single().action shouldBe RevisionAction.RESTORE
        restored.revisions.single().revisionNumber shouldBe 3
        restored.revisions.single().previousRevisionId shouldBe edited.revisions.single().id
        restored.journalBundles.map { it.entry.role }.toSet() shouldBe setOf(JournalEntryRole.REVERSE, JournalEntryRole.APPLY)
        restored.economicEffects.single { it.polarity == EffectPolarity.APPLY }.baseAmount.minor.value shouldBe 1_000L
        (restored.revisions.single().contentHash != original.revisions.single().contentHash).shouldBeTrue()
    }

    @Test
    fun `batch context edits share one commit reverse every prior fact and are deterministic`() {
        val amount = PlannerFixtures.sameCurrencyEvidence(AmountRole.PRIMARY, 1_000L, PlannerFixtures.bankJpyId)
        val firstCreateSnapshot = PlannerFixtures.snapshot(listOf(amount), seed = 42_000L)
        val secondCreateSnapshot = PlannerFixtures.snapshot(listOf(amount), seed = 43_000L)
        val firstCreate = DeterministicFinancialPlanner.plan(
            PlannerFixtures.expenseCommand(1_000L, commandSeed = 42_500L),
            firstCreateSnapshot,
        ).success()
        val secondCreate = DeterministicFinancialPlanner.plan(
            PlannerFixtures.expenseCommand(1_000L, commandSeed = 43_500L),
            secondCreateSnapshot,
        ).success()
        val batchBook = PlannerFixtures.nextBook(firstCreate, firstCreateSnapshot.book)
        val sharedCommit = BookCommitId(stableId(44_900L))
        val sharedCreatedAt = Instant.parse("2026-08-02T00:00:00Z")
        val sharedDevice = DeviceInstanceId(stableId(44_901L))
        fun childSnapshot(plan: FinancialMutationPlan, seed: Long): PlanningSnapshot {
            val scalar = PlannerFixtures.snapshot(
                amountEvidence = listOf(amount),
                seed = seed,
                currentTransaction = plan.transactions.single(),
                currentRevision = plan.revisions.single(),
                currentFacts = PlannerFixtures.currentFacts(plan),
                sourceBook = batchBook,
            )
            return scalar.copy(
                accountingContext = scalar.accountingContext!!.copy(
                    identities = scalar.accountingContext.identities.copy(commitId = sharedCommit),
                    createdAt = sharedCreatedAt,
                    deviceInstanceId = sharedDevice,
                ),
            )
        }
        val snapshots = listOf(childSnapshot(firstCreate, 44_000L), childSnapshot(secondCreate, 45_000L))
        val children = listOf(firstCreate, secondCreate).mapIndexed { index, plan ->
            val revision = plan.revisions.single()
            val replacement = PlannerFixtures.expenseCommand(1_000L, commandSeed = 46_000L + index).input.copy(
                context = PlannerFixtures.inputContext().copy(locationRecordId = LocationRecordId(stableId(46_100L + index))),
            )
            val unsigned = EditTransactionCommand(
                CommandId(stableId(46_200L + index)),
                revision.id,
                hash(0),
                revision.transactionId,
                replacement,
                emptyList(),
                RevisionAction.BULK_EDIT,
            )
            unsigned.copy(payloadHash = CanonicalFinancialHash.command(unsigned))
        }
        val unsignedBatch = BatchFinancialCommand(CommandId(stableId(46_300L)), hash(0), children)
        val batch = unsignedBatch.copy(payloadHash = CanonicalFinancialHash.command(unsignedBatch))
        val root = PlanningSnapshot(
            batchBook,
            null,
            null,
            emptyList(),
            emptySet(),
            emptyList(),
            null,
            emptyList(),
            batchSnapshots = snapshots,
        )

        val first = DeterministicFinancialPlanner.plan(batch, root).success()
        val second = DeterministicFinancialPlanner.plan(batch, root).success()

        first shouldBe second
        first.commit.kind shouldBe CommitKind.BATCH_MUTATION
        first.transactions shouldHaveSize 2
        first.revisions shouldHaveSize 2
        first.revisions.all { it.action == RevisionAction.BULK_EDIT }.shouldBeTrue()
        first.journalBundles.count { it.entry.role == JournalEntryRole.REVERSE } shouldBe 2
        first.journalBundles.count { it.entry.role == JournalEntryRole.APPLY } shouldBe 2
        first.journalBundles.all { it.entry.baseDebitTotalMinor == it.entry.baseCreditTotalMinor }.shouldBeTrue()
        FinancialMutationPlanValidator.validate(batch, root, first).success() shouldBe first
    }

    @Test
    fun `batch creates are one balanced deterministic commit and retain every complete row`() {
        val sharedBook = book()
        val sharedCommit = BookCommitId(stableId(47_900L))
        val sharedCreatedAt = Instant.parse("2026-08-06T01:00:00Z")
        val sharedDevice = DeviceInstanceId(stableId(47_901L))
        val commands = listOf(
            PlannerFixtures.expenseCommand(1_200L, commandSeed = 47_000L),
            PlannerFixtures.expenseCommand(3_400L, commandSeed = 47_100L),
        )
        val snapshots = commands.mapIndexed { index, _ ->
            val scalar = PlannerFixtures.snapshot(
                listOf(
                    PlannerFixtures.sameCurrencyEvidence(
                        AmountRole.PRIMARY,
                        if (index == 0) 1_200L else 3_400L,
                        PlannerFixtures.bankJpyId,
                    ),
                ),
                seed = 47_200L + index * 100L,
                sourceBook = sharedBook,
            )
            scalar.copy(
                accountingContext = scalar.accountingContext!!.copy(
                    identities = scalar.accountingContext.identities.copy(commitId = sharedCommit),
                    createdAt = sharedCreatedAt,
                    deviceInstanceId = sharedDevice,
                ),
            )
        }
        val unsigned = BatchFinancialCommand(CommandId(stableId(47_800L)), hash(0), commands)
        val command = unsigned.copy(payloadHash = CanonicalFinancialHash.command(unsigned))
        val root = PlanningSnapshot(
            sharedBook,
            null,
            null,
            emptyList(),
            emptySet(),
            emptyList(),
            null,
            emptyList(),
            batchSnapshots = snapshots,
        )

        val first = DeterministicFinancialPlanner.plan(command, root).success()
        val replay = DeterministicFinancialPlanner.plan(command, root).success()

        first shouldBe replay
        first.commit.kind shouldBe CommitKind.BATCH_MUTATION
        first.targetLocalRevision shouldBe sharedBook.localRevision.next().success()
        first.transactions shouldHaveSize 2
        first.revisions.shouldHaveSize(2)
        first.revisions.all { it.action == RevisionAction.CREATE }.shouldBeTrue()
        first.journalBundles.shouldHaveSize(2)
        first.journalBundles.all { it.entry.baseDebitTotalMinor == it.entry.baseCreditTotalMinor }.shouldBeTrue()
        first.economicEffects.map { it.baseAmount.minor.value }.sorted() shouldBe listOf(1_200L, 3_400L)
        FinancialMutationPlanValidator.validate(command, root, first).success() shouldBe first
    }

    @Test
    fun `trash nets current facts to zero and restore appends a new revision`() {
        val amount = PlannerFixtures.sameCurrencyEvidence(AmountRole.PRIMARY, 800L, PlannerFixtures.bankJpyId)
        val createSnapshot = PlannerFixtures.snapshot(listOf(amount), seed = 50_000L)
        val createCommand = PlannerFixtures.expenseCommand(800L, commandSeed = 50_500L)
        val createPlan = DeterministicFinancialPlanner.plan(createCommand, createSnapshot).success()
        val facts = PlannerFixtures.currentFacts(createPlan)
        val trashCommand = canonicalTrash(createPlan)
        val trashSnapshot = PlannerFixtures.snapshot(
            listOf(amount),
            seed = 51_000L,
            currentTransaction = createPlan.transactions.single(),
            currentRevision = createPlan.revisions.single(),
            currentFacts = facts,
            sourceBook = PlannerFixtures.nextBook(createPlan, createSnapshot.book),
        )
        val trashPlan = DeterministicFinancialPlanner.plan(trashCommand, trashSnapshot).success()
        val net = FinancialFactNetting.postings(facts.journalBundles + trashPlan.journalBundles).success()

        net.all { it.accountDebitMinusCreditMinor == 0L && it.baseDebitMinusCreditMinor == 0L }.shouldBeTrue()
        trashPlan.transactions.single().lifecycleState shouldBe TransactionLifecycleState.TRASHED
        trashPlan.journalBundles.all { it.entry.role == JournalEntryRole.REVERSE }.shouldBeTrue()

        val restoreCommand = canonicalRestore(trashPlan)
        val restoreBook = PlannerFixtures.nextBook(trashPlan, trashSnapshot.book)
        val restoreSnapshot = PlannerFixtures.snapshot(
            listOf(amount),
            seed = 52_000L,
            currentTransaction = trashPlan.transactions.single(),
            currentRevision = trashPlan.revisions.single(),
            currentFacts = null,
            sourceBook = restoreBook,
        )
        val restorePlan = DeterministicFinancialPlanner.plan(restoreCommand, restoreSnapshot).success()
        restorePlan.revisions.single().action shouldBe RevisionAction.RESTORE
        restorePlan.revisions.single().revisionNumber shouldBe 3
        restorePlan.transactions.single().lifecycleState shouldBe TransactionLifecycleState.ACTIVE
        restorePlan.journalBundles.all { it.entry.role == JournalEntryRole.APPLY }.shouldBeTrue()
    }

    @Test
    fun `candidate source and stale expected revision create no facts`() {
        val candidateCommand = PlannerFixtures.expenseCommand(
            100L,
            context = PlannerFixtures.inputContext(source = TransactionSource.RECURRENCE_CANDIDATE),
            commandSeed = 60_000L,
        )
        val candidate = DeterministicFinancialPlanner.plan(
            candidateCommand,
            PlannerFixtures.snapshot(
                listOf(PlannerFixtures.sameCurrencyEvidence(AmountRole.PRIMARY, 100L, PlannerFixtures.bankJpyId)),
                seed = 60_100L,
            ),
        )
        (candidate is DomainResult.Failure).shouldBeTrue()

        val originalSnapshot = PlannerFixtures.snapshot(
            listOf(PlannerFixtures.sameCurrencyEvidence(AmountRole.PRIMARY, 100L, PlannerFixtures.bankJpyId)),
            seed = 61_000L,
        )
        val original = DeterministicFinancialPlanner.plan(
            PlannerFixtures.expenseCommand(100L, commandSeed = 61_500L),
            originalSnapshot,
        ).success()
        val stale = editCommand(original, 120L).copy(expectedRevisionId = TransactionRevisionId(stableId(99_999L)))
        val staleSnapshot = PlannerFixtures.snapshot(
            listOf(PlannerFixtures.sameCurrencyEvidence(AmountRole.PRIMARY, 120L, PlannerFixtures.bankJpyId)),
            seed = 62_000L,
            currentTransaction = original.transactions.single(),
            currentRevision = original.revisions.single(),
            currentFacts = PlannerFixtures.currentFacts(original),
            sourceBook = PlannerFixtures.nextBook(original, originalSnapshot.book),
        )
        DeterministicFinancialPlanner.plan(stale, staleSnapshot) shouldBe
            DomainResult.Failure(DomainViolation.StaleExpectedRevision)
    }

    @Test
    fun `archived account still permits exact reversal of immutable historical facts`() {
        val amount = PlannerFixtures.sameCurrencyEvidence(AmountRole.PRIMARY, 250L, PlannerFixtures.bankJpyId)
        val createSnapshot = PlannerFixtures.snapshot(listOf(amount), seed = 63_000L)
        val create = DeterministicFinancialPlanner.plan(
            PlannerFixtures.expenseCommand(250L, commandSeed = 63_500L),
            createSnapshot,
        ).success()
        val archivedReferences = PlannerFixtures.references().let { references ->
            references.copy(
                accounts = references.accounts.map { account ->
                    if (account.account.id == PlannerFixtures.bankJpyId) {
                        account.copy(
                            account = account.account.copy(status = EntityStatus.ARCHIVED),
                            ledger = account.ledger.copy(status = EntityStatus.ARCHIVED),
                        )
                    } else {
                        account
                    }
                },
            )
        }
        val trash = canonicalTrash(create)
        val trashSnapshot = PlannerFixtures.snapshot(
            amountEvidence = listOf(amount),
            seed = 64_000L,
            currentTransaction = create.transactions.single(),
            currentRevision = create.revisions.single(),
            currentFacts = PlannerFixtures.currentFacts(create),
            sourceBook = PlannerFixtures.nextBook(create, createSnapshot.book),
            referenceData = archivedReferences,
        )

        val plan = DeterministicFinancialPlanner.plan(trash, trashSnapshot).success()

        plan.journalBundles.all { it.entry.role == JournalEntryRole.REVERSE }.shouldBeTrue()
        plan.journalBundles.flatMap { it.postings }.all { it.reversalOfPostingId != null }.shouldBeTrue()
    }

    @Test
    fun `linked refund cannot exceed frozen refundable balance without explicit override`() {
        val references = PlannerFixtures.references()
        val originalTransactionId = TransactionId(stableId(65_000L))
        val payload = RefundPayload(
            classification = CategoryAssignment(
                PlannerFixtures.expenseCategoryId,
                CategoryDirection.EXPENSE,
                StatisticalNature.CONSUMPTION_EXPENSE,
            ),
            receivingAmount = PlannerFixtures.accountAmount(PlannerFixtures.bankJpyId, 200L, references),
            receivingCardId = null,
            allocations = listOf(
                RefundAllocation(
                    originalTransactionId,
                    TransactionRevisionId(stableId(65_001L)),
                    positive(200L, PlannerFixtures.jpy),
                    positive(200L, PlannerFixtures.jpy),
                ),
            ),
            independent = false,
            allowExcessOverride = false,
            budgetPolicy = RefundBudgetPolicy.DO_NOT_RESTORE,
            projectPolicy = RefundProjectPolicy.DO_NOT_RESTORE,
            goalPolicy = RefundGoalPolicy.DO_NOT_RESTORE,
            accrualPolicy = RefundAccrualPolicy.REFUND_DATE,
        )
        val unsigned = RecordRefundCommand(
            CommandId(stableId(65_100L)),
            hash(0),
            NewTransactionInput(PlannerFixtures.inputContext(), payload),
        )
        val command = unsigned.copy(payloadHash = CanonicalFinancialHash.command(unsigned))
        val snapshot = PlannerFixtures.snapshot(
            listOf(PlannerFixtures.sameCurrencyEvidence(AmountRole.REFUND, 200L, PlannerFixtures.bankJpyId)),
            seed = 65_200L,
        ).copy(
            refundStatuses = listOf(
                RefundStatusProjection(
                    originalTransactionId,
                    positive(500L, PlannerFixtures.jpy),
                    refundedMinor = 400L,
                    remainingMinor = 100L,
                    asOfLocalRevision = localRevision(1L),
                ),
            ),
        )

        DeterministicFinancialPlanner.plan(command, snapshot) shouldBe
            DomainResult.Failure(DomainViolation.Invariant("INV-010"))

        val mismatchedPayload = payload.copy(
            allocations = payload.allocations.map {
                it.copy(
                    amountInOriginalCurrency = positive(150L, PlannerFixtures.jpy),
                    amountInBaseCurrency = positive(150L, PlannerFixtures.jpy),
                )
            },
        )
        val mismatchedUnsigned = command.copy(
            commandId = CommandId(stableId(65_101L)),
            payloadHash = hash(0),
            input = command.input.copy(payload = mismatchedPayload),
        )
        val mismatched = mismatchedUnsigned.copy(
            payloadHash = CanonicalFinancialHash.command(mismatchedUnsigned),
        )
        DeterministicFinancialPlanner.plan(
            mismatched,
            snapshot.copy(
                refundStatuses = listOf(
                    snapshot.refundStatuses.single().copy(refundedMinor = 0L, remainingMinor = 500L),
                ),
            ),
        ) shouldBe DomainResult.Failure(DomainViolation.InvalidField("refund.allocationBaseAmount"))
    }

    @Test
    fun `external-only settlement supports edit trash and restore without journal placeholders`() {
        val createPayload = SettlementPaymentPayload(
            PlannerFixtures.activityId,
            PlannerFixtures.selfId,
            PlannerFixtures.friendId,
            positive(300L, PlannerFixtures.jpy),
            localAccountAmount = null,
            selfParticipates = false,
        )
        val createUnsigned = RecordSettlementPaymentCommand(
            CommandId(stableId(66_000L)),
            hash(0),
            NewTransactionInput(PlannerFixtures.inputContext(), createPayload),
            SettlementPaymentRecord(
                SettlementPaymentRecordId(stableId(66_050L)),
                PlannerFixtures.activityId,
                PlannerFixtures.selfId,
                PlannerFixtures.friendId,
                positive(300L, PlannerFixtures.jpy),
                PlannerFixtures.inputContext().occurredAt,
                null,
                false,
                BookCommitId(stableId(66_102L)),
                null,
            ),
        )
        val createCommand = createUnsigned.copy(payloadHash = CanonicalFinancialHash.command(createUnsigned))
        val createSnapshot = PlannerFixtures.snapshot(emptyList(), seed = 66_100L)
        val create = DeterministicFinancialPlanner.plan(createCommand, createSnapshot).success()
        create.journalBundles shouldHaveSize 0

        val replacement = createCommand.input.copy(
            payload = createPayload.copy(amount = positive(350L, PlannerFixtures.jpy)),
        )
        val editUnsigned = EditTransactionCommand(
            CommandId(stableId(66_200L)),
            create.revisions.single().id,
            hash(0),
            create.transactions.single().id,
            replacement,
            emptyList(),
        )
        val editCommand = editUnsigned.copy(payloadHash = CanonicalFinancialHash.command(editUnsigned))
        val editSnapshot = PlannerFixtures.snapshot(
            emptyList(),
            seed = 66_300L,
            currentTransaction = create.transactions.single(),
            currentRevision = create.revisions.single(),
            currentFacts = PlannerFixtures.currentFacts(create),
            sourceBook = PlannerFixtures.nextBook(create, createSnapshot.book),
        )
        val edit = DeterministicFinancialPlanner.plan(editCommand, editSnapshot).success()
        edit.journalBundles shouldHaveSize 0
        edit.settlementEffects shouldHaveSize 4

        val trashCommand = canonicalTrash(edit)
        val trashSnapshot = PlannerFixtures.snapshot(
            emptyList(),
            seed = 66_400L,
            currentTransaction = edit.transactions.single(),
            currentRevision = edit.revisions.single(),
            currentFacts = PlannerFixtures.currentFacts(edit),
            sourceBook = PlannerFixtures.nextBook(edit, editSnapshot.book),
        )
        val trash = DeterministicFinancialPlanner.plan(trashCommand, trashSnapshot).success()
        trash.journalBundles shouldHaveSize 0
        trash.settlementEffects shouldHaveSize 2

        val restoreCommand = canonicalRestore(trash)
        val restore = DeterministicFinancialPlanner.plan(
            restoreCommand,
            PlannerFixtures.snapshot(
                emptyList(),
                seed = 66_500L,
                currentTransaction = trash.transactions.single(),
                currentRevision = trash.revisions.single(),
                currentFacts = null,
                sourceBook = PlannerFixtures.nextBook(trash, trashSnapshot.book),
            ),
        ).success()
        restore.journalBundles shouldHaveSize 0
        restore.settlementEffects shouldHaveSize 2
        restore.revisions.single().action shouldBe RevisionAction.RESTORE
    }

    @Test
    fun `dependency closure requires one compatible explicit policy`() {
        val evidence = PlannerFixtures.sameCurrencyEvidence(AmountRole.PRIMARY, 500L, PlannerFixtures.bankJpyId)
        val createSnapshot = PlannerFixtures.snapshot(listOf(evidence), seed = 67_000L)
        val create = DeterministicFinancialPlanner.plan(
            PlannerFixtures.expenseCommand(500L, commandSeed = 67_100L),
            createSnapshot,
        ).success()
        val dependency = TransactionDependency(
            create.transactions.single().id,
            TransactionId(stableId(67_200L)),
            TransactionDependencyType.REFUND,
        )
        val replacement = PlannerFixtures.expenseCommand(550L, commandSeed = 67_300L).input
        fun command(policy: DependencyPolicy?, seed: Long): EditTransactionCommand {
            val unsigned = EditTransactionCommand(
                CommandId(stableId(seed)),
                create.revisions.single().id,
                hash(0),
                create.transactions.single().id,
                replacement,
                policy?.let { listOf(DependencyResolution(dependency, it)) }.orEmpty(),
            )
            return unsigned.copy(payloadHash = CanonicalFinancialHash.command(unsigned))
        }
        val snapshot = PlannerFixtures.snapshot(
            listOf(PlannerFixtures.sameCurrencyEvidence(AmountRole.PRIMARY, 550L, PlannerFixtures.bankJpyId)),
            seed = 67_400L,
            currentTransaction = create.transactions.single(),
            currentRevision = create.revisions.single(),
            currentFacts = PlannerFixtures.currentFacts(create),
            sourceBook = PlannerFixtures.nextBook(create, createSnapshot.book),
            dependencies = listOf(dependency),
        )

        DeterministicFinancialPlanner.plan(command(null, 67_500L), snapshot) shouldBe
            DomainResult.Failure(DomainViolation.InvalidField("mutationPlan.dependencies"))
        DeterministicFinancialPlanner.plan(
            command(DependencyPolicy.RecalculateLoanSchedule, 67_501L),
            snapshot,
        ) shouldBe DomainResult.Failure(DomainViolation.InvalidField("mutationPlan.dependencies"))
        DeterministicFinancialPlanner.plan(
            command(DependencyPolicy.ConvertRefundToIndependent, 67_502L),
            snapshot,
        ).success().dependencyResolutions shouldHaveSize 1
    }

    @Test
    fun `cross-zone local date and canonical sensitive content are stable without logging`() {
        val occurred = app.ledger.core.time.EffectiveTime.fromInstant(
            Instant.parse("2026-08-01T01:00:00Z"),
            ZoneId.of("America/Los_Angeles"),
        )
        val context = PlannerFixtures.inputContext(occurredAt = occurred)
        val command = PlannerFixtures.expenseCommand(100L, context = context, commandSeed = 70_000L)
        val changed = command.copy(
            input = command.input.copy(context = context.copy(note = "another private note")),
        )
        CanonicalFinancialHash.command(command) shouldBe command.payloadHash
        (CanonicalFinancialHash.command(changed) != command.payloadHash).shouldBeTrue()

        val plan = DeterministicFinancialPlanner.plan(
            command,
            PlannerFixtures.snapshot(
                listOf(PlannerFixtures.sameCurrencyEvidence(AmountRole.PRIMARY, 100L, PlannerFixtures.bankJpyId)),
                seed = 70_100L,
            ),
        ).success()
        plan.revisions.single().occurredAt.localDate shouldBe java.time.LocalDate.of(2026, 7, 31)
        plan.revisions.single().accrualDate shouldBe java.time.LocalDate.of(2026, 7, 31)
    }

    private fun editCommand(plan: FinancialMutationPlan, minor: Long): EditTransactionCommand {
        val replacement = PlannerFixtures.expenseCommand(minor, commandSeed = 80_000L).input
        val command = EditTransactionCommand(
            CommandId(stableId(80_001L + minor)),
            plan.revisions.single().id,
            hash(0),
            plan.transactions.single().id,
            replacement,
            emptyList(),
        )
        return command.copy(payloadHash = CanonicalFinancialHash.command(command))
    }

    private fun canonicalIncome(input: NewTransactionInput<IncomePayload>): RecordIncomeCommand {
        val command = RecordIncomeCommand(CommandId(stableId(21_000L)), hash(0), input)
        return command.copy(payloadHash = CanonicalFinancialHash.command(command))
    }

    private fun canonicalTransfer(payload: TransferPayload): RecordTransferCommand {
        val command = RecordTransferCommand(
            CommandId(stableId(22_000L)),
            hash(0),
            NewTransactionInput(PlannerFixtures.inputContext(), payload),
        )
        return command.copy(payloadHash = CanonicalFinancialHash.command(command))
    }

    private fun canonicalOpening(payload: OpeningBalancePayload): RecordOpeningBalanceCommand {
        val command = RecordOpeningBalanceCommand(
            CommandId(stableId(23_000L)),
            hash(0),
            NewTransactionInput(PlannerFixtures.inputContext(), payload),
        )
        return command.copy(payloadHash = CanonicalFinancialHash.command(command))
    }

    private fun canonicalAdjustment(payload: BalanceAdjustmentPayload): RecordBalanceAdjustmentCommand {
        val command = RecordBalanceAdjustmentCommand(
            CommandId(stableId(24_000L)),
            hash(0),
            NewTransactionInput(PlannerFixtures.inputContext(), payload),
        )
        return command.copy(payloadHash = CanonicalFinancialHash.command(command))
    }

    private fun canonicalFx(payload: FxExchangePayload): RecordFxExchangeCommand {
        val command = RecordFxExchangeCommand(
            CommandId(stableId(25_000L)),
            hash(0),
            NewTransactionInput(PlannerFixtures.inputContext(), payload),
        )
        return command.copy(payloadHash = CanonicalFinancialHash.command(command))
    }

    private fun canonicalLoanPayment(payload: LoanPaymentPayload): RecordLoanPaymentCommand {
        val command = RecordLoanPaymentCommand(
            CommandId(stableId(26_000L)),
            hash(0),
            NewTransactionInput(PlannerFixtures.inputContext(), payload),
        )
        return command.copy(payloadHash = CanonicalFinancialHash.command(command))
    }

    private fun canonicalExpense(payload: ExpensePayload, seed: Long): RecordExpenseCommand {
        val command = RecordExpenseCommand(
            CommandId(stableId(seed)),
            hash(0),
            NewTransactionInput(PlannerFixtures.inputContext(), payload),
        )
        return command.copy(payloadHash = CanonicalFinancialHash.command(command))
    }

    private fun canonicalTrash(plan: FinancialMutationPlan): MoveTransactionToTrashCommand {
        val command = MoveTransactionToTrashCommand(
            CommandId(stableId(90_000L)),
            plan.revisions.single().id,
            hash(0),
            plan.transactions.single().id,
            Instant.ofEpochSecond(99_999L),
            emptyList(),
        )
        return command.copy(payloadHash = CanonicalFinancialHash.command(command))
    }

    private fun canonicalRestore(plan: FinancialMutationPlan): RestoreTransactionCommand {
        val command = RestoreTransactionCommand(
            CommandId(stableId(90_100L)),
            plan.revisions.single().id,
            hash(0),
            plan.transactions.single().id,
        )
        return command.copy(payloadHash = CanonicalFinancialHash.command(command))
    }

    private fun userAssetBaseDelta(
        plan: FinancialMutationPlan,
        references: PlanningReferenceData,
    ): Long {
        val userLedgerIds = references.accounts.filter { it.ledger.accountClass == LedgerAccountClass.ASSET }
            .map { it.ledger.id }
            .toSet()
        val signed = plan.journalBundles.flatMap { it.postings }.filter { it.ledgerAccountId in userLedgerIds }.map {
            if (it.side == DebitCredit.DEBIT) it.baseAmount.minor.value else Math.negateExact(it.baseAmount.minor.value)
        }
        return app.ledger.core.common.CheckedArithmetic.sum(signed).success()
    }

    private fun postingCodes(
        plan: FinancialMutationPlan,
        references: PlanningReferenceData,
    ): Set<SystemLedgerCode> {
        val ids = plan.journalBundles.flatMap { it.postings }.map { it.ledgerAccountId }.toSet()
        return references.systemLedgers.filter { it.ledger.id in ids }.map { it.code }.toSet()
    }
}

private object CheckedArithmeticForTest {
    fun sum(values: List<Long>): Long = app.ledger.core.common.CheckedArithmetic.sum(values).success()
}
