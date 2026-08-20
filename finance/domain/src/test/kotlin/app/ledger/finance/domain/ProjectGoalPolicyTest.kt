package app.ledger.finance.domain

import app.ledger.core.common.CommandId
import app.ledger.core.common.DomainResult
import app.ledger.core.money.Money
import app.ledger.core.time.EffectiveTime
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ProjectGoalPolicyTest {
    @Test
    fun `goal balance reconstruction and negative account availability remain exact warnings`() = runTest {
        checkAll(iterations = 1_000, Arb.long(1L..1_000_000L), Arb.long(1L..1_000_000L)) { allocated, spent ->
            val balance = GoalBalancePolicy.rebuild(
                listOf(
                    GoalBalanceDelta(GoalEffectKind.ALLOCATE, allocated, EffectPolarity.APPLY),
                    GoalBalanceDelta(GoalEffectKind.SPEND, spent, EffectPolarity.APPLY),
                ),
            ).success()
            balance shouldBe allocated - spent
            val availability = GoalBalancePolicy.availability(allocated / 2L, allocated).success()
            availability.first shouldBe -(allocated - allocated / 2L)
            availability.second.shouldBeTrue()
        }
    }

    @Test
    fun `project monthly inclusion is frozen into each new effect and self share drives usage`() {
        val included = planProjectExpense(included = true)
        included.projectEffects.single().baseAmount.minor.value shouldBe 400L
        included.projectEffects.single().includedInMonthlyBudgetSnapshot.shouldBeTrue()
        included.budgetEffects shouldHaveSize 1

        val excluded = planProjectExpense(included = false)
        excluded.projectEffects.single().baseAmount.minor.value shouldBe 400L
        excluded.projectEffects.single().includedInMonthlyBudgetSnapshot.shouldBeFalse()
        excluded.budgetEffects.shouldBeEmpty()
    }

    @Test
    fun `goal movement creates no journal or posting and cannot reuse stale goal row`() {
        val goal = Goal(
            PlannerFixtures.goalId,
            PlannerFixtures.bankJpyId,
            "Emergency",
            10_000L,
            PlannerFixtures.jpy,
            LocalDate.of(2027, 1, 1),
            1_000L,
            GoalStatus.ACTIVE,
            BookCommitId(stableId(30_001)),
            RowVersion.of(3L).success(),
        )
        val movement = GoalMovement(
            GoalMovementId(stableId(30_002)),
            goal.id,
            GoalMovementKind.ALLOCATE,
            PositiveMoney.from(Money(2_000L, goal.currency)).success(),
            EffectiveTime.fromInstant(Instant.parse("2026-08-04T03:00:00Z"), ZoneId.of("Asia/Tokyo")),
            null,
            null,
            null,
            BookCommitId(stableId(30_003)),
        )
        val draft = RecordGoalMovementCommand(
            CommandId(stableId(30_004)),
            hash(0),
            movement,
            GoalEffectId(stableId(30_005)),
            goal.rowVersion,
        )
        val command = draft.copy(payloadHash = CanonicalFinancialHash.command(draft))
        val snapshot = PlanningSnapshot(
            book(), null, null, emptyList(), emptySet(), emptyList(), null, emptyList(),
            operationContext = PlanningOperationContext(movement.createdCommitId, Instant.parse("2026-08-04T03:00:01Z"), DeviceInstanceId(stableId(30_006))),
            goal = goal,
        )
        val plan = DeterministicFinancialPlanner.plan(command, snapshot).success()
        plan.journalBundles.shouldBeEmpty()
        plan.transactions.shouldBeEmpty()
        plan.entityChanges.shouldBeEmpty()
        plan.goalMovements shouldBe listOf(movement)
        plan.goalEffects.single().kind shouldBe GoalEffectKind.ALLOCATE

        val stale = command.copy(expectedGoalRowVersion = RowVersion.of(2L).success()).let { it.copy(payloadHash = CanonicalFinancialHash.command(it)) }
        (DeterministicFinancialPlanner.plan(stale, snapshot) is DomainResult.Failure).shouldBeTrue()
    }

    @Test
    fun `archived project is not selectable but can be explicitly reopened`() {
        ProjectStatusPolicy.canSelectForNewTransaction(ProjectStatus.ARCHIVED).shouldBeFalse()
        ProjectStatusPolicy.transition(ProjectStatus.ACTIVE, ProjectStatus.ARCHIVED).success() shouldBe ProjectStatus.ARCHIVED
        ProjectStatusPolicy.transition(ProjectStatus.ARCHIVED, ProjectStatus.ACTIVE).success() shouldBe ProjectStatus.ACTIVE
        (ProjectStatusPolicy.transition(ProjectStatus.ARCHIVED, ProjectStatus.COMPLETED) is DomainResult.Failure).shouldBeTrue()
    }

    @Test
    fun `transfers and loan principal never consume project budget while real loan charges do`() {
        val references = PlannerFixtures.references()
        val projectContext = PlannerFixtures.inputContext(project = PlannerFixtures.projectId)
        val transferPayload = TransferPayload(
            PlannerFixtures.accountAmount(PlannerFixtures.bankJpyId, 800L, references),
            PlannerFixtures.accountAmount(PlannerFixtures.bankJpyTwoId, 800L, references),
            null,
        )
        val unsignedTransfer = RecordTransferCommand(CommandId(stableId(31_000L)), hash(0), NewTransactionInput(projectContext, transferPayload))
        val transfer = unsignedTransfer.copy(payloadHash = CanonicalFinancialHash.command(unsignedTransfer))
        val transferPlan = DeterministicFinancialPlanner.plan(
            transfer,
            PlannerFixtures.snapshot(
                listOf(
                    PlannerFixtures.sameCurrencyEvidence(AmountRole.OUTGOING, 800L, PlannerFixtures.bankJpyId),
                    PlannerFixtures.sameCurrencyEvidence(AmountRole.INCOMING, 800L, PlannerFixtures.bankJpyTwoId),
                ),
                seed = 31_100L,
            ),
        ).success()
        transferPlan.projectEffects.shouldBeEmpty()

        val loanPayload = LoanPaymentPayload(
            CategoryAssignment(
                PlannerFixtures.nonConsumptionCategoryId,
                CategoryDirection.EXPENSE,
                StatisticalNature.NON_CONSUMPTION_EXPENSE,
            ),
            PlannerFixtures.loanContractId,
            PlannerFixtures.accountAmount(PlannerFixtures.bankJpyId, 1_200L, references),
            null,
            LoanPaymentComponents(
                positive(1_000L, PlannerFixtures.jpy),
                positive(100L, PlannerFixtures.jpy),
                positive(50L, PlannerFixtures.jpy),
                positive(50L, PlannerFixtures.jpy),
            ),
            emptyList(),
        )
        val unsignedLoan = RecordLoanPaymentCommand(CommandId(stableId(32_000L)), hash(0), NewTransactionInput(projectContext, loanPayload))
        val loan = unsignedLoan.copy(payloadHash = CanonicalFinancialHash.command(unsignedLoan))
        val loanPlan = DeterministicFinancialPlanner.plan(
            loan,
            PlannerFixtures.snapshot(
                listOf(
                    PlannerFixtures.sameCurrencyEvidence(AmountRole.OUTGOING, 1_200L, PlannerFixtures.bankJpyId),
                    PlannerFixtures.sameCurrencyEvidence(AmountRole.PRINCIPAL, 1_000L, null),
                    PlannerFixtures.sameCurrencyEvidence(AmountRole.INTEREST, 100L, null),
                    PlannerFixtures.sameCurrencyEvidence(AmountRole.FEE, 50L, null),
                    PlannerFixtures.sameCurrencyEvidence(AmountRole.PENALTY, 50L, null),
                ),
                seed = 32_100L,
            ),
        ).success()
        loanPlan.projectEffects.single().baseAmount.minor.value shouldBe 200L
    }

    private fun planProjectExpense(included: Boolean): FinancialMutationPlan {
        val references = PlannerFixtures.references().copy(
            projects = listOf(PlanningProject(PlannerFixtures.projectId, included, ProjectStatus.ACTIVE)),
        )
        val command = PlannerFixtures.expenseCommand(
            1_000L,
            context = PlannerFixtures.inputContext(project = PlannerFixtures.projectId).copy(
                statementAssignment = null,
            ),
            references = references,
        )
        val evidence = listOf(
            PlannerFixtures.sameCurrencyEvidence(AmountRole.PRIMARY, 1_000L, PlannerFixtures.bankJpyId),
            PlannerFixtures.sameCurrencyEvidence(AmountRole.SELF_SHARE, 400L, PlannerFixtures.bankJpyId),
        )
        val sharePayload = command.input.payload.copy(
            settlementActivityId = PlannerFixtures.activityId,
            settlementShares = listOf(
                SettlementShare(PlannerFixtures.selfId, 1_000L, 400L, null, 0L),
                SettlementShare(PlannerFixtures.friendId, 0L, 600L, null, 0L),
            ),
        )
        val withShares = command.copy(input = command.input.copy(payload = sharePayload)).let { it.copy(payloadHash = CanonicalFinancialHash.command(it)) }
        return DeterministicFinancialPlanner.plan(withShares, PlannerFixtures.snapshot(evidence, referenceData = references)).success()
    }

    private fun <T> DomainResult<T>.success(): T = when (this) {
        is DomainResult.Success -> value
        is DomainResult.Failure -> error(error.toString())
    }
}
