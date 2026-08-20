@file:Suppress("MagicNumber", "MaxLineLength")

package app.ledger.feature.liabilities

import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.money.CurrencyCode
import app.ledger.finance.application.CreditAccountView
import app.ledger.finance.application.CreditPaymentAccountView
import app.ledger.finance.application.CreditProfileView
import app.ledger.finance.application.CreditSnapshot
import app.ledger.finance.application.CreditStatementView
import app.ledger.finance.domain.AutoGenerationMode
import app.ledger.finance.domain.CreditStatementStatus
import app.ledger.finance.domain.DueDateRule
import app.ledger.finance.domain.LocalRevision
import app.ledger.finance.domain.MissingDayPolicy
import app.ledger.finance.domain.StatementDateRule
import app.ledger.finance.domain.WeekendAdjustment
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

internal object CreditDeviceFixtures {
    val accountId: StableId = id(10)
    val paymentId: StableId = id(11)
    val statementId: StableId = id(20)
    val estimatedStatementId: StableId = id(21)
    private val jpy = (CurrencyCode.parse("JPY") as DomainResult.Success).value
    private val revision = (LocalRevision.of(19) as DomainResult.Success).value

    val actions = CreditActions(
        onRetry = {},
        onNavigate = { _, _ -> },
        onFieldChanged = { _, _ -> },
        onNextPaymentAccount = {},
        onNextZone = {},
        onCycleDueRule = {},
        onSelectStatement = {},
        onSelectEarliest = {},
        onSelectUnallocated = {},
        onAssignment = {},
        onToggleAutoPayment = {},
        onToggleSeal = {},
    )

    fun snapshot(
        accounts: List<CreditAccountView> = listOf(account()),
    ) = CreditSnapshot(
        id(1),
        jpy,
        revision,
        accounts,
        listOf(CreditPaymentAccountView(paymentId, "Salary bank", jpy, true)),
    )

    @Suppress("LongParameterList")
    fun account(
        statements: List<CreditStatementView> = listOf(statement(), statement(estimatedStatementId, status = CreditStatementStatus.OPEN, official = null, sealed = false)),
        debt: Long = 900,
        positive: Long = 0,
        overdue: Long = 0,
        limit: Long? = 99_100,
        archived: Boolean = false,
        profile: CreditProfileView? = profile(),
    ) = CreditAccountView(
        accountId,
        "Everyday credit",
        jpy,
        archived,
        profile,
        debt - positive,
        debt,
        positive,
        limit,
        300,
        overdue,
        statements,
    )

    fun profile(mode: AutoGenerationMode = AutoGenerationMode.FORMAL_TRANSACTION) = CreditProfileView(
        StatementDateRule.DayOfMonth(25, MissingDayPolicy.MOVE_TO_MONTH_END),
        DueDateRule.FixedDay(10, MissingDayPolicy.MOVE_TO_MONTH_END),
        ZoneId.of("Asia/Tokyo"),
        100_000,
        20_000,
        LocalDate.of(2026, 8, 31),
        paymentId,
        mode,
        WeekendAdjustment.NEXT_BUSINESS_DAY,
        id(30),
    )

    fun statement(
        id: StableId = statementId,
        status: CreditStatementStatus = CreditStatementStatus.UNPAID,
        official: Long? = 650,
        sealed: Boolean = true,
    ) = CreditStatementView(
        id,
        CreditDeviceFixtures.id(if (id == statementId) 40 else 41),
        2,
        LocalDate.of(2026, 8, 1),
        LocalDate.of(2026, 8, 25),
        LocalDate.of(2026, 9, 10),
        600,
        official,
        official?.minus(600),
        100,
        (official ?: 600) - 100,
        status,
        sealed,
    )

    fun state(
        screen: String,
        presentation: CreditPresentation,
        snapshot: CreditSnapshot = snapshot(),
        accountId: StableId? = CreditDeviceFixtures.accountId,
        statementId: StableId? = CreditDeviceFixtures.statementId,
    ): CreditFeatureState = CreditPolicy.create(snapshot, screen, accountId, statementId).copy(
        presentation = presentation,
        draft = CreditPolicy.create(snapshot, screen, accountId, statementId).draft.copy(
            amount = "500",
            officialAmount = "650",
            date = "2026-09-10",
        ),
        validationFields = if (presentation in setOf(CreditPresentation.VALIDATION_ERROR, CreditPresentation.MISMATCH)) setOf("amount") else emptySet(),
    )

    private fun id(value: Long): StableId = StableId.fromUuid(UUID(0x1919L, value))
}
