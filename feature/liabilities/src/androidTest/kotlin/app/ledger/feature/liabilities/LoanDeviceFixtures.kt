@file:Suppress("MaxLineLength")

package app.ledger.feature.liabilities

import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.money.CurrencyCode
import app.ledger.finance.application.CreditAccountView
import app.ledger.finance.application.LoanAccountOption
import app.ledger.finance.application.LoanContractView
import app.ledger.finance.application.LoanScheduleItemView
import app.ledger.finance.application.LoanSnapshot
import app.ledger.finance.application.LoanTrancheView
import app.ledger.finance.domain.BookCommitId
import app.ledger.finance.domain.InterestRate
import app.ledger.finance.domain.LoanContractId
import app.ledger.finance.domain.LoanPrepaymentPolicy
import app.ledger.finance.domain.LoanPrepaymentSimulation
import app.ledger.finance.domain.LoanRatePeriod
import app.ledger.finance.domain.LoanRateType
import app.ledger.finance.domain.LoanRepaymentMethod
import app.ledger.finance.domain.LoanScheduleItem
import app.ledger.finance.domain.LoanScheduleItemId
import app.ledger.finance.domain.LoanScheduleRevision
import app.ledger.finance.domain.LoanScheduleRevisionId
import app.ledger.finance.domain.LoanScheduleSummary
import app.ledger.finance.domain.LoanSimulationScenario
import app.ledger.finance.domain.LoanStatus
import app.ledger.finance.domain.LoanTermsRevisionId
import app.ledger.finance.domain.LoanTrancheId
import app.ledger.finance.domain.LocalRevision
import app.ledger.finance.domain.PaymentFrequency
import app.ledger.finance.domain.PrepaymentRecalculationStrategy
import app.ledger.finance.domain.ScheduleRevisionReason
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

internal object LoanDeviceFixtures {
    val bookId = id(1)
    val contractId = id(10)
    val trancheId = id(11)
    val secondTrancheId = id(12)
    val transactionId = id(13)
    val simulationId = id(14)
    val creditAccountId = id(15)
    private val jpy = (CurrencyCode.parse("JPY") as DomainResult.Success).value
    private val revision = (LocalRevision.of(28) as DomainResult.Success).value
    private val rate = (InterestRate.of(BigDecimal("0.024")) as DomainResult.Success).value

    val actions = LoanActions(
        onRetry = {}, onNavigate = { _, _, _ -> }, onFieldChanged = { _, _ -> }, onSelectContract = {},
        onSelectTranche = {}, onRepaymentMethod = {}, onStrategy = {}, onPreview = {}, onSave = {}, onSimulate = {}, onApplySimulation = {},
    )

    fun snapshot(contracts: List<LoanContractView> = listOf(contract())) = LoanSnapshot(
        bookId,
        jpy,
        revision,
        contracts,
        listOf(LoanAccountOption(id(30), "Combined loan", jpy, "LOAN", id(31), true)),
        listOf(LoanAccountOption(id(40), "Salary bank", jpy, "BANK", id(41), true)),
    )

    fun creditAccount() = CreditAccountView(
        creditAccountId,
        "Travel credit",
        jpy,
        false,
        null,
        24_000L,
        24_000L,
        0L,
        null,
        8_000L,
        0L,
        emptyList(),
    )

    fun contract(status: LoanStatus = LoanStatus.ACTIVE, multi: Boolean = true): LoanContractView {
        val tranches = listOf(tranche(trancheId, id(31), "Home tranche", 120_000L, status)) +
            if (multi) listOf(tranche(secondTrancheId, id(32), "Renovation tranche", 30_000L, status)) else emptyList()
        return LoanContractView(
            contractId,
            id(30),
            "Home combination loan",
            "Local cooperative",
            jpy,
            LocalDate.of(2026, 1, 15),
            status,
            id(50),
            tranches,
        )
    }

    fun state(
        screen: String,
        presentation: LoanPresentation,
        snapshot: LoanSnapshot = snapshot(),
    ): LoanFeatureState {
        val initial = LoanPolicy.create(
            snapshot,
            screen,
            contractId.takeIf { snapshot.contracts.isNotEmpty() },
            trancheId.takeIf { snapshot.contracts.isNotEmpty() },
            transactionId,
            simulationId,
        )
        return initial.copy(
            presentation = presentation,
            draft = initial.draft.copy(
                amount = "10500",
                principalComponent = "10000",
                interestComponent = "400",
                feeComponent = "80",
                penaltyComponent = "20",
                confirmPhrase = "confirm apply",
            ),
            preview = if (presentation == LoanPresentation.READY || presentation == LoanPresentation.CONTENT && screen == "LOA-006") listOf(preview()) else emptyList(),
            simulation = if (presentation == LoanPresentation.RESULT || screen == "LOA-011") simulation() else null,
            validationFields = if (presentation in setOf(LoanPresentation.INVALID, LoanPresentation.ALLOCATION_ERROR, LoanPresentation.SUM_MISMATCH, LoanPresentation.PRINCIPAL_EXCEEDED)) setOf("amount") else emptySet(),
        )
    }

    fun simulation(): LoanPrepaymentSimulation {
        val after = preview(LoanScheduleRevisionId(id(91)), 90_000L, 3)
        return LoanPrepaymentSimulation(
            LoanContractId(contractId),
            LoanTrancheId(trancheId),
            LoanScheduleRevisionId(id(61)),
            LoanSimulationScenario.PartialPrepayment(10_000L, PrepaymentRecalculationStrategy.SHORTEN_TERM, LocalDate.of(2026, 8, 4)),
            100_000L,
            10_000L,
            100L,
            10_100L,
            LoanScheduleSummary(100_000L, 4_000L, 200L, 104_200L, 4, LocalDate.of(2026, 12, 15)),
            after,
            LoanScheduleSummary(90_000L, 2_700L, 150L, 92_850L, 3, LocalDate.of(2026, 11, 15)),
        )
    }

    private fun tranche(id: StableId, ledgerId: StableId, name: String, principal: Long, status: LoanStatus): LoanTrancheView {
        val schedule = scheduleViews(principal, 4)
        return LoanTrancheView(
            id,
            ledgerId,
            name,
            principal,
            principal,
            0,
            0,
            0,
            0,
            status,
            id(idSeed(id) + 100),
            2,
            LoanRepaymentMethod.EQUAL_PAYMENT,
            LoanRateType.FIXED,
            PaymentFrequency.MONTHLY,
            LoanPrepaymentPolicy.ALLOWED,
            PrepaymentRecalculationStrategy.SHORTEN_TERM,
            null,
            RoundingMode.HALF_EVEN,
            listOf(LoanRatePeriod(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 12, 15), rate, null, null)),
            id(idSeed(id) + 200),
            2,
            2,
            schedule,
        )
    }

    private fun preview(
        scheduleId: LoanScheduleRevisionId = LoanScheduleRevisionId(id(80)),
        principal: Long = 120_000L,
        count: Int = 4,
    ): LoanScheduleRevision {
        val base = principal / count
        var remaining = principal
        val items = (1..count).map { number ->
            val portion = if (number == count) remaining else base
            remaining -= portion
            LoanScheduleItem(
                LoanScheduleItemId(id(800L + number)),
                number,
                LocalDate.of(2026, 8, 15).plusMonths((number - 1).toLong()),
                portion,
                300L,
                50L,
                remaining,
                number == count,
            )
        }
        return LoanScheduleRevision(
            scheduleId,
            LoanTrancheId(trancheId),
            3,
            LoanTermsRevisionId(id(70)),
            ScheduleRevisionReason.PREPAYMENT,
            Instant.parse("2026-08-04T03:00:00Z"),
            BookCommitId(id(71)),
            items,
        )
    }

    private fun scheduleViews(principal: Long, count: Int): List<LoanScheduleItemView> {
        val base = principal / count
        var remaining = principal
        return (1..count).map { number ->
            val portion = if (number == count) remaining else base
            remaining -= portion
            LoanScheduleItemView(number, LocalDate.of(2026, 8, 15).plusMonths((number - 1).toLong()), portion, 300, 50, remaining, 0, 0, 0, 0)
        }
    }

    private fun idSeed(value: StableId): Long = value.toUuid().leastSignificantBits
    private fun id(value: Long): StableId = StableId.fromUuid(UUID(0x2121L, value))
}
