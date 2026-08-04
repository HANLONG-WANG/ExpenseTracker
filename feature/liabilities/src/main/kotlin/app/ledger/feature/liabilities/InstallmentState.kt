package app.ledger.feature.liabilities

import app.ledger.core.common.StableId
import app.ledger.finance.application.InstallmentPlanView
import app.ledger.finance.application.InstallmentSnapshot
import app.ledger.finance.domain.InstallmentFeeRateType
import app.ledger.finance.domain.InstallmentRefundPolicy
import app.ledger.finance.domain.InstallmentScheduleRevision
import app.ledger.finance.domain.InstallmentSettlementSimulation
import app.ledger.finance.domain.InstallmentStatus

public enum class InstallmentPresentation {
    EDITING,
    PREVIEW,
    INVALID,
    SAVING,
    CONTENT,
    EMPTY,
    COMPLETED,
    CREATE,
    EDIT,
    ACTIVE,
    REFUND_ADJUSTED,
    CALCULATED,
    REQUIRES_DECISION,
}

public data class InstallmentDraft(
    val termCount: String = "12",
    val firstStatementDate: String = "",
    val feeModel: InstallmentFeeRateType = InstallmentFeeRateType.NONE,
    val feeValue: String = "",
    val firstTermFee: String = "",
    val annualRate: String = "",
    val prepaymentFee: String = "",
    val settlementDate: String = "",
    val refundPolicy: InstallmentRefundPolicy = InstallmentRefundPolicy.REBUILD_SCHEDULE,
    val confirmPhrase: String = "",
)

public data class InstallmentFeatureState(
    val snapshot: InstallmentSnapshot,
    val selectedPlanId: StableId?,
    val selectedPurchaseId: StableId?,
    val presentation: InstallmentPresentation,
    val draft: InstallmentDraft,
    val simulation: InstallmentSettlementSimulation? = null,
    val previewSchedule: InstallmentScheduleRevision? = null,
    val validationFields: Set<String> = emptySet(),
    val confirmed: Boolean = false,
) {
    public val plan: InstallmentPlanView?
        get() = selectedPlanId?.let { id -> snapshot.plans.singleOrNull { it.id == id } }
}

public sealed interface InstallmentLoadState {
    public data object Loading : InstallmentLoadState
    public data class Content(val state: InstallmentFeatureState) : InstallmentLoadState
    public data class Failure(val code: String) : InstallmentLoadState
}

public object InstallmentPolicy {
    public fun create(
        snapshot: InstallmentSnapshot,
        screenId: String,
        planId: StableId?,
        purchaseId: StableId?,
    ): InstallmentFeatureState {
        val plan = planId?.let { id -> snapshot.plans.singleOrNull { it.id == id } }
        val purchase = snapshot.purchases.singleOrNull { it.transactionId == purchaseId }
            ?: snapshot.purchases.firstOrNull { !it.alreadyLinked }
        val presentation = when (screenId) {
            "REC-027" -> InstallmentPresentation.EDITING
            "INS-001" -> when {
                snapshot.plans.isEmpty() -> InstallmentPresentation.EMPTY
                snapshot.plans.all { it.status == InstallmentStatus.SETTLED } -> InstallmentPresentation.COMPLETED
                else -> InstallmentPresentation.CONTENT
            }
            "INS-002" -> if (planId == null) InstallmentPresentation.CREATE else InstallmentPresentation.EDIT
            "INS-003" -> when {
                plan?.status == InstallmentStatus.SETTLED -> InstallmentPresentation.COMPLETED
                (plan?.refundedPrincipalMinor ?: 0L) > 0L -> InstallmentPresentation.REFUND_ADJUSTED
                else -> InstallmentPresentation.ACTIVE
            }
            "INS-004", "INS-006" -> InstallmentPresentation.CONTENT
            "INS-005" -> InstallmentPresentation.EDITING
            else -> InstallmentPresentation.CONTENT
        }
        val revision = plan?.currentRevision
        return InstallmentFeatureState(
            snapshot,
            plan?.id ?: planId,
            purchase?.transactionId ?: purchaseId,
            presentation,
            InstallmentDraft(
                termCount = plan?.termCount?.toString() ?: "12",
                firstStatementDate = plan?.currentSchedule?.items?.firstOrNull()?.statementDate?.toString()
                    ?: purchase?.purchaseDate?.plusMonths(1)?.toString().orEmpty(),
                feeModel = revision?.feeRateType ?: InstallmentFeeRateType.NONE,
                feeValue = revision?.fixedFeePerTermMinor?.toString()
                    ?: revision?.remainingPrincipalRate?.annualDecimal?.toPlainString().orEmpty(),
                firstTermFee = revision?.firstTermFeeMinor?.toString().orEmpty(),
                annualRate = revision?.effectiveAnnualRate?.annualDecimal?.toPlainString().orEmpty(),
                prepaymentFee = revision?.prepaymentFeeMinor?.toString().orEmpty(),
                settlementDate = plan?.progress?.nextStatementDate?.minusDays(1)?.toString().orEmpty(),
                refundPolicy = revision?.refundPolicy ?: InstallmentRefundPolicy.REBUILD_SCHEDULE,
            ),
        )
    }

    public fun update(state: InstallmentFeatureState, field: InstallmentField, value: String): InstallmentFeatureState {
        val draft = when (field) {
            InstallmentField.TERM_COUNT -> state.draft.copy(termCount = value.take(MAX_SHORT))
            InstallmentField.FIRST_STATEMENT_DATE -> state.draft.copy(firstStatementDate = value.take(MAX_DATE))
            InstallmentField.FEE_VALUE -> state.draft.copy(feeValue = value.take(MAX_AMOUNT))
            InstallmentField.FIRST_TERM_FEE -> state.draft.copy(firstTermFee = value.take(MAX_AMOUNT))
            InstallmentField.ANNUAL_RATE -> state.draft.copy(annualRate = value.take(MAX_RATE))
            InstallmentField.PREPAYMENT_FEE -> state.draft.copy(prepaymentFee = value.take(MAX_AMOUNT))
            InstallmentField.SETTLEMENT_DATE -> state.draft.copy(settlementDate = value.take(MAX_DATE))
            InstallmentField.CONFIRM_PHRASE -> state.draft.copy(confirmPhrase = value.take(MAX_CONFIRM))
        }
        return state.copy(
            draft = draft,
            presentation = InstallmentPresentation.EDITING,
            previewSchedule = null,
            validationFields = emptySet(),
        )
    }

    private const val MAX_SHORT = 3
    private const val MAX_DATE = 10
    private const val MAX_AMOUNT = 40
    private const val MAX_RATE = 24
    private const val MAX_CONFIRM = 24
}

public enum class InstallmentField {
    TERM_COUNT,
    FIRST_STATEMENT_DATE,
    FEE_VALUE,
    FIRST_TERM_FEE,
    ANNUAL_RATE,
    PREPAYMENT_FEE,
    SETTLEMENT_DATE,
    CONFIRM_PHRASE,
}
