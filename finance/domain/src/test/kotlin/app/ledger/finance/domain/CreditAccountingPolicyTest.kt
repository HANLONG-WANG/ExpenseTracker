package app.ledger.finance.domain

import app.ledger.core.common.CommandId
import app.ledger.core.common.DomainResult
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class CreditAccountingPolicyTest {
    @Test
    fun `calendar resolves short months skipped dates time zone and adjusted due day deterministically`() {
        val moved = CreditCalendarPolicy.cycleContaining(
            LocalDate.of(2026, 2, 10),
            profile(
                statementRule = StatementDateRule.DayOfMonth(31, MissingDayPolicy.MOVE_TO_MONTH_END),
                dueRule = DueDateRule.FixedDay(5, MissingDayPolicy.MOVE_TO_MONTH_END),
                weekend = WeekendAdjustment.NEXT_BUSINESS_DAY,
            ),
        ).success()
        moved shouldBe CreditCycle(
            LocalDate.of(2026, 2, 1),
            LocalDate.of(2026, 2, 28),
            LocalDate.of(2026, 3, 5),
        )

        val skipped = CreditCalendarPolicy.cycleContaining(
            LocalDate.of(2026, 2, 10),
            profile(
                statementRule = StatementDateRule.DayOfMonth(31, MissingDayPolicy.SKIP),
                dueRule = DueDateRule.FixedDay(5, MissingDayPolicy.MOVE_TO_MONTH_END),
                weekend = WeekendAdjustment.NEXT_BUSINESS_DAY,
            ),
        ).success()
        skipped shouldBe CreditCycle(
            LocalDate.of(2026, 2, 1),
            LocalDate.of(2026, 3, 31),
            LocalDate.of(2026, 4, 6),
        )

        val sameNominalDay = CreditCalendarPolicy.cycleContaining(
            LocalDate.of(2026, 8, 15),
            profile(
                statementRule = StatementDateRule.DayOfMonth(15, MissingDayPolicy.MOVE_TO_MONTH_END),
                dueRule = DueDateRule.FixedDay(15, MissingDayPolicy.MOVE_TO_MONTH_END),
                weekend = WeekendAdjustment.NONE,
            ),
        ).success()
        sameNominalDay.dueDate shouldBe LocalDate.of(2026, 9, 15)
    }

    @Test
    fun `statement status and display difference never create an accounting adjustment`() {
        val due = LocalDate.of(2026, 8, 20)
        CreditStatementPolicy.status(1_000, null, 0, due, false, LocalDate.of(2026, 8, 10)) shouldBe CreditStatementStatus.OPEN
        CreditStatementPolicy.status(1_000, 1_100, 0, due, true, LocalDate.of(2026, 8, 10)) shouldBe CreditStatementStatus.SEALED
        CreditStatementPolicy.status(1_000, 1_100, 400, due, true, LocalDate.of(2026, 8, 10)) shouldBe CreditStatementStatus.PARTIALLY_PAID
        CreditStatementPolicy.status(1_000, 1_100, 1_100, due, true, LocalDate.of(2026, 8, 30)) shouldBe CreditStatementStatus.PAID
        CreditStatementPolicy.status(1_000, 1_100, 0, due, true, LocalDate.of(2026, 8, 21)) shouldBe CreditStatementStatus.OVERDUE
        CreditStatementPolicy.difference(1_000, 1_100) shouldBe 100
    }

    @Test
    fun `earliest payment allocates across statements and every active overpayment is rejected`() {
        val first = CreditStatementId(stableId(19_001))
        val second = CreditStatementId(stableId(19_002))
        val statements = listOf(
            PayableCreditStatement(second, LocalDate.of(2026, 9, 20), 500),
            PayableCreditStatement(first, LocalDate.of(2026, 8, 20), 300),
        )
        CreditPaymentAllocationPolicy.allocate(
            positive(700, PlannerFixtures.jpy),
            statements,
            CreditPaymentSelection.EarliestUnpaid,
            activeDebtMinor = 700,
        ).success().map { it.statementId to it.amount.minor.value } shouldContainExactly listOf(first to 300L, second to 400L)

        val specificOverpay = CreditPaymentAllocationPolicy.allocate(
            positive(301, PlannerFixtures.jpy),
            statements,
            CreditPaymentSelection.Specific(first),
            activeDebtMinor = 800,
        )
        (specificOverpay is DomainResult.Failure) shouldBe true

        val totalOverpay = CreditPaymentAllocationPolicy.allocate(
            positive(801, PlannerFixtures.jpy),
            statements,
            CreditPaymentSelection.UnallocatedAdvance,
            activeDebtMinor = 800,
        )
        (totalOverpay is DomainResult.Failure) shouldBe true

        val liabilityOverpay = CreditPaymentAllocationPolicy.allocate(
            positive(700, PlannerFixtures.jpy),
            statements,
            CreditPaymentSelection.EarliestUnpaid,
            activeDebtMinor = 699,
        )
        (liabilityOverpay is DomainResult.Failure) shouldBe true

        CreditPaymentAllocationPolicy.allocate(
            positive(200, PlannerFixtures.jpy),
            emptyList(),
            CreditPaymentSelection.UnallocatedAdvance,
            activeDebtMinor = 200,
        ).success().single().statementId shouldBe null
    }

    @Test
    fun `auto payment formal mode requires all five eligibility facts and candidate mode writes no facts`() {
        CreditAutoPaymentPolicy.evaluate(1_000, 600, true, true, false) shouldBe
            AutoPaymentEligibility(AutoGenerationMode.FORMAL_TRANSACTION, emptySet())
        CreditAutoPaymentPolicy.evaluate(null, 0, false, false, true) shouldBe AutoPaymentEligibility(
            AutoGenerationMode.CONFIRMATION_CANDIDATE,
            AutoPaymentIneligibility.entries.toSet(),
        )

        val references = PlannerFixtures.references()
        val payload = CreditPaymentPayload(
            PlannerFixtures.accountAmount(PlannerFixtures.bankJpyId, 300, references),
            PlannerFixtures.creditJpyId,
            PlannerFixtures.accountAmount(PlannerFixtures.creditJpyId, 300, references),
            listOf(CreditPaymentAllocation(null, positive(300, PlannerFixtures.jpy))),
            AutoGenerationMode.CONFIRMATION_CANDIDATE,
        )
        val unsigned = RecordCreditPaymentCommand(
            CommandId(stableId(19_100)),
            hash(0),
            NewTransactionInput(PlannerFixtures.inputContext(), payload),
        )
        val command = unsigned.copy(payloadHash = CanonicalFinancialHash.command(unsigned))
        val result = DeterministicFinancialPlanner.plan(
            command,
            PlannerFixtures.snapshot(
                listOf(
                    PlannerFixtures.sameCurrencyEvidence(AmountRole.OUTGOING, 300, PlannerFixtures.bankJpyId),
                    PlannerFixtures.sameCurrencyEvidence(AmountRole.INCOMING, 300, PlannerFixtures.creditJpyId),
                ),
                seed = 19_200,
            ),
        )
        (result is DomainResult.Failure) shouldBe true
        (result as DomainResult.Failure).error shouldBe DomainViolation.Invariant("INV-028")
    }

    @Test
    fun `profile and official statement commands are revisioned non financial mutations`() {
        val commitId = BookCommitId(stableId(19_300))
        val profile = profile(lastCommitId = commitId)
        val unsigned = SaveCreditProfileCommand(
            CommandId(stableId(19_301)),
            hash(0),
            CreditProfileMutation(profile, null, CreditLimitPeriod(profile.accountId, LocalDate.of(2026, 8, 1), null, 100_000, commitId)),
        )
        val command = unsigned.copy(payloadHash = CanonicalFinancialHash.command(unsigned))
        val snapshot = PlannerFixtures.snapshot(emptyList(), seed = 19_400).copy(
            accountingContext = null,
            operationContext = PlanningOperationContext(commitId, Instant.parse("2026-08-04T00:00:00Z"), DeviceInstanceId(stableId(19_302))),
        )
        val plan = DeterministicFinancialPlanner.plan(command, snapshot).success()
        plan.creditProfileMutations.shouldContainExactly(command.mutation)
        plan.journalBundles shouldBe emptyList()
        plan.economicEffects shouldBe emptyList()
        plan.statementEffects shouldBe emptyList()
    }

    @Test
    fun `passive positive balance is distinct from active debt and increases available limit`() {
        CreditAccountPosition(
            PlannerFixtures.creditJpyId,
            PlannerFixtures.jpy,
            signedLiabilityMinor = -200,
            debtMinor = 0,
            positiveBalanceMinor = 200,
            effectiveLimitMinor = 1_000,
            availableLimitMinor = 1_200,
        ).positiveBalanceMinor shouldBe 200
    }

    private fun profile(
        statementRule: StatementDateRule = StatementDateRule.DayOfMonth(25, MissingDayPolicy.MOVE_TO_MONTH_END),
        dueRule: DueDateRule = DueDateRule.FixedDay(10, MissingDayPolicy.MOVE_TO_MONTH_END),
        weekend: WeekendAdjustment = WeekendAdjustment.NONE,
        lastCommitId: BookCommitId = BookCommitId(stableId(19_999)),
    ) = CreditAccountProfile(
        PlannerFixtures.creditJpyId,
        statementRule,
        dueRule,
        ZoneId.of("Asia/Tokyo"),
        100_000,
        null,
        null,
        PlannerFixtures.bankJpyId,
        AutoGenerationMode.CONFIRMATION_CANDIDATE,
        weekend,
        lastCommitId,
    )
}
