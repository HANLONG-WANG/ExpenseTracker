@file:Suppress("MagicNumber", "MaxLineLength")

package app.ledger.feature.liabilities

import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.money.CurrencyCode
import app.ledger.finance.application.CreditPaymentAccountView
import app.ledger.finance.application.InstallmentPlanView
import app.ledger.finance.application.InstallmentPurchaseView
import app.ledger.finance.application.InstallmentSnapshot
import app.ledger.finance.domain.BookCommitId
import app.ledger.finance.domain.InstallmentFeeRateType
import app.ledger.finance.domain.InstallmentPlanId
import app.ledger.finance.domain.InstallmentPlanRevision
import app.ledger.finance.domain.InstallmentPlanRevisionId
import app.ledger.finance.domain.InstallmentPrepaymentPolicy
import app.ledger.finance.domain.InstallmentProgress
import app.ledger.finance.domain.InstallmentRefundPolicy
import app.ledger.finance.domain.InstallmentScheduleItem
import app.ledger.finance.domain.InstallmentScheduleItemId
import app.ledger.finance.domain.InstallmentScheduleRevision
import app.ledger.finance.domain.InstallmentScheduleRevisionId
import app.ledger.finance.domain.InstallmentSettlementSimulation
import app.ledger.finance.domain.InstallmentStatus
import app.ledger.finance.domain.LocalRevision
import app.ledger.finance.domain.ScheduleRevisionReason
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

internal object InstallmentDeviceFixtures {
    val bookId = id(1)
    val planId = id(10)
    val purchaseId = id(11)
    val creditAccountId = id(12)
    val paymentAccountId = id(13)
    private val jpy = (CurrencyCode.parse("JPY") as DomainResult.Success).value
    private val localRevision = (LocalRevision.of(20) as DomainResult.Success).value

    val actions: (InstallmentScreenAction) -> Unit = {}

    fun snapshot(plans: List<InstallmentPlanView> = listOf(plan())) = InstallmentSnapshot(
        bookId,
        jpy,
        localRevision,
        plans,
        listOf(InstallmentPurchaseView(purchaseId, creditAccountId, "Daily credit", jpy, 1_200, LocalDate.of(2026, 8, 4), plans.isNotEmpty())),
        listOf(CreditPaymentAccountView(paymentAccountId, "Salary bank", jpy, true)),
    )

    fun plan(
        status: InstallmentStatus = InstallmentStatus.ACTIVE,
        refundedPrincipal: Long = 0,
        refundedFee: Long = 0,
    ): InstallmentPlanView {
        val settled = status == InstallmentStatus.SETTLED
        val revision = revision()
        val schedule = if (settled) schedule(empty = true, revisionNumber = 2) else schedule()
        return InstallmentPlanView(
            planId, purchaseId, creditAccountId, "Daily credit", jpy, 1_200,
            if (settled) 0 else 1_200 - refundedPrincipal, 3, status,
            revision, schedule,
            if (settled) InstallmentProgress(0, 0, 0, 30, 0, null) else InstallmentProgress(1_200 - refundedPrincipal, 0, 1_200 - refundedPrincipal, 0, 30, LocalDate.of(2026, 9, 25)),
            refundedPrincipal, refundedFee,
        )
    }

    fun state(
        screen: String,
        presentation: InstallmentPresentation,
        snapshot: InstallmentSnapshot = snapshot(),
        selectedPlanId: StableId? = if (screen in setOf("INS-003", "INS-004", "INS-005", "INS-006") || presentation == InstallmentPresentation.EDIT) planId else null,
    ): InstallmentFeatureState {
        val initial = InstallmentPolicy.create(snapshot, screen, selectedPlanId, if (selectedPlanId == null) purchaseId else null)
        return initial.copy(
            presentation = presentation,
            previewSchedule = if (presentation == InstallmentPresentation.PREVIEW) schedule() else null,
            simulation = if (presentation == InstallmentPresentation.CALCULATED) simulation() else null,
            draft = initial.draft.copy(
                firstStatementDate = "2026-09-25",
                settlementDate = "2026-10-01",
                feeModel = InstallmentFeeRateType.FIXED_PER_TERM,
                feeValue = "10",
                prepaymentFee = "5",
            ),
            validationFields = if (presentation == InstallmentPresentation.INVALID) setOf("termCount") else emptySet(),
        )
    }

    fun refundedSnapshot() = snapshot(listOf(plan(refundedPrincipal = 200, refundedFee = 10)))

    private fun revision() = InstallmentPlanRevision(
        InstallmentPlanRevisionId(id(20)), InstallmentPlanId(planId), 1, InstallmentFeeRateType.FIXED_PER_TERM,
        10, null, null, null, InstallmentPrepaymentPolicy.ALLOWED_WITH_FEE, 5,
        InstallmentRefundPolicy.REBUILD_SCHEDULE, RoundingMode.HALF_EVEN, BookCommitId(id(21)),
    )

    private fun schedule(empty: Boolean = false, revisionNumber: Int = 1): InstallmentScheduleRevision {
        val items = if (empty) {
            emptyList()
        } else {
            listOf(
                InstallmentScheduleItem(InstallmentScheduleItemId(id(31)), 1, LocalDate.of(2026, 9, 25), 400, 0, 10, 800),
                InstallmentScheduleItem(InstallmentScheduleItemId(id(32)), 2, LocalDate.of(2026, 10, 25), 400, 0, 10, 400),
                InstallmentScheduleItem(InstallmentScheduleItemId(id(33)), 3, LocalDate.of(2026, 11, 25), 400, 0, 10, 0),
            )
        }
        return InstallmentScheduleRevision(
            InstallmentScheduleRevisionId(id(30L + revisionNumber)),
            InstallmentPlanId(planId),
            revisionNumber,
            if (empty) ScheduleRevisionReason.PREPAYMENT else ScheduleRevisionReason.INITIAL,
            Instant.parse("2026-08-04T03:00:00Z"),
            BookCommitId(id(21)),
            items,
        )
    }

    private fun simulation() = InstallmentSettlementSimulation(
        InstallmentPlanId(planId), InstallmentScheduleRevisionId(id(31)), LocalDate.of(2026, 10, 1),
        800, 0, 20, 5, 805, 15, true,
    )

    private fun id(value: Long): StableId = StableId.fromUuid(UUID(0x2020L, value))
}
