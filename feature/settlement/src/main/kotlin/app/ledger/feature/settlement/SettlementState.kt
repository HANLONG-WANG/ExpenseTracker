package app.ledger.feature.settlement

import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.money.AmountSemantic
import app.ledger.core.money.AmountVisibility
import app.ledger.core.money.JvmLegalTenderCurrencyCatalog
import app.ledger.core.money.LocaleCurrencyFormatter
import app.ledger.core.money.Money
import app.ledger.core.money.MoneyFormatRequest
import app.ledger.core.money.MoneyUiModel
import app.ledger.finance.application.SettlementActivityView
import app.ledger.finance.application.SettlementSnapshot
import app.ledger.finance.domain.SettlementActivityStatus
import app.ledger.finance.domain.SettlementChargeDistribution
import app.ledger.finance.domain.SettlementRoundingRule
import app.ledger.finance.domain.SettlementSplitMethod
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.Locale

public enum class SettlementPresentation {
    CONTENT,
    EMPTY,
    REQUIRES_ADDITIONAL_SETTLEMENT,
    CREATE,
    EDIT,
    VALIDATION_ERROR,
    OPEN,
    SETTLED,
    RECEIVABLE,
    PAYABLE,
    ZERO,
    SELF_PAYS,
    SELF_RECEIVES,
    EXTERNAL_TO_EXTERNAL,
    SAVING,
    REQUIRED,
    RESOLVED,
}

public enum class SettlementField { NAME, DESCRIPTION, START_DATE, END_DATE, TOTAL, TAX, SERVICE_FEE, NOTE, PARTICIPANT_NAME }

public data class SettlementParticipantDraft(
    val id: StableId,
    val name: String,
    val isSelf: Boolean,
    val included: Boolean = true,
    val value: String = "1",
)

public data class SettlementDraft(
    val name: String = "",
    val description: String = "",
    val startDate: String = "",
    val endDate: String = "",
    val total: String = "",
    val tax: String = "0",
    val serviceFee: String = "0",
    val note: String = "",
    val participantName: String = "",
    val splitMethod: SettlementSplitMethod = SettlementSplitMethod.EQUAL,
    val chargeDistribution: SettlementChargeDistribution = SettlementChargeDistribution.SAME_AS_BASE,
    val roundingRule: SettlementRoundingRule = SettlementRoundingRule.PARTICIPANT_ORDER,
    val payerParticipantId: StableId? = null,
    val payeeParticipantId: StableId? = null,
    val accountId: StableId? = null,
    val projectId: StableId? = null,
    val participants: List<SettlementParticipantDraft> = emptyList(),
)

public data class SettlementFeatureState(
    val snapshot: SettlementSnapshot,
    val selectedActivityId: StableId?,
    val selectedParticipantId: StableId?,
    val presentation: SettlementPresentation,
    val draft: SettlementDraft,
    val validationFields: Set<String> = emptySet(),
) {
    public val activity: SettlementActivityView?
        get() = selectedActivityId?.let { id -> snapshot.activities.singleOrNull { it.id == id } }
}

public sealed interface SettlementLoadState {
    public data object Loading : SettlementLoadState
    public data class Content(val state: SettlementFeatureState) : SettlementLoadState
    public data class Failure(val code: String) : SettlementLoadState
}

public object SettlementPolicy {
    private val catalog = JvmLegalTenderCurrencyCatalog.create()
    private val formatter = LocaleCurrencyFormatter(catalog)

    @Suppress("CyclomaticComplexMethod")
    public fun create(snapshot: SettlementSnapshot, screenId: String, activityId: StableId?, participantId: StableId? = null): SettlementFeatureState {
        val activity = when {
            screenId == "SET-002" && activityId == null -> null
            activityId != null -> snapshot.activities.singleOrNull { it.id == activityId }
            else -> snapshot.activities.firstOrNull()
        }
        val self = activity?.participants?.singleOrNull { it.isSelf } ?: snapshot.participants.singleOrNull { it.isSelf }
        val other = activity?.participants?.firstOrNull { !it.isSelf }
        val selectedPosition = participantId?.let { id -> activity?.positions?.singleOrNull { it.participantId == id } }
            ?: activity?.positions?.singleOrNull { it.participantId == self?.id }
        val presentation = when (screenId) {
            "SET-001" -> when {
                snapshot.activities.isEmpty() -> SettlementPresentation.EMPTY
                snapshot.activities.any(SettlementActivityView::requiresAdditionalSettlement) -> SettlementPresentation.REQUIRES_ADDITIONAL_SETTLEMENT
                else -> SettlementPresentation.CONTENT
            }
            "SET-002" -> if (activityId == null) SettlementPresentation.CREATE else SettlementPresentation.EDIT
            "SET-003", "SET-007" -> if (activity == null || (screenId == "SET-007" && activity.payments.isEmpty())) SettlementPresentation.EMPTY else SettlementPresentation.CONTENT
            "SET-004" -> when (activity?.status) {
                null -> SettlementPresentation.EMPTY
                SettlementActivityStatus.SETTLED -> SettlementPresentation.SETTLED
                SettlementActivityStatus.REQUIRES_ADDITIONAL_SETTLEMENT -> SettlementPresentation.REQUIRES_ADDITIONAL_SETTLEMENT
                else -> SettlementPresentation.OPEN
            }
            "SET-005" -> when {
                activity == null -> SettlementPresentation.ZERO
                selectedPosition?.netPositionMinor?.let { it > 0L } == true -> SettlementPresentation.RECEIVABLE
                selectedPosition?.netPositionMinor?.let { it < 0L } == true -> SettlementPresentation.PAYABLE
                else -> SettlementPresentation.ZERO
            }
            "SET-006" -> SettlementPresentation.SELF_PAYS
            "SET-008" -> if (activity?.requiresAdditionalSettlement == true) SettlementPresentation.REQUIRED else SettlementPresentation.RESOLVED
            else -> SettlementPresentation.CONTENT
        }
        return SettlementFeatureState(
            snapshot,
            activity?.id ?: activityId,
            selectedPosition?.participantId ?: participantId,
            presentation,
            SettlementDraft(
                name = activity?.name.orEmpty(),
                description = activity?.description.orEmpty(),
                startDate = activity?.startDate?.toString().orEmpty(),
                endDate = activity?.endDate?.toString().orEmpty(),
                payerParticipantId = self?.id,
                payeeParticipantId = other?.id,
                accountId = snapshot.accounts.firstOrNull { it.active && it.currency == activity?.currency }?.id,
                projectId = activity?.projectId,
                participants = (activity?.participants ?: snapshot.participants).map {
                    SettlementParticipantDraft(it.id, it.name, it.isSelf, included = it.active)
                },
            ),
        )
    }

    public fun update(state: SettlementFeatureState, field: SettlementField, value: String): SettlementFeatureState {
        val safe = value.take(if (field in setOf(SettlementField.DESCRIPTION, SettlementField.NOTE)) LONG_TEXT_LIMIT else SHORT_TEXT_LIMIT)
        val draft = when (field) {
            SettlementField.NAME -> state.draft.copy(name = safe)
            SettlementField.DESCRIPTION -> state.draft.copy(description = safe)
            SettlementField.START_DATE -> state.draft.copy(startDate = safe.take(DATE_TEXT_LIMIT))
            SettlementField.END_DATE -> state.draft.copy(endDate = safe.take(DATE_TEXT_LIMIT))
            SettlementField.TOTAL -> state.draft.copy(total = safe.take(MONEY_TEXT_LIMIT))
            SettlementField.TAX -> state.draft.copy(tax = safe.take(MONEY_TEXT_LIMIT))
            SettlementField.SERVICE_FEE -> state.draft.copy(serviceFee = safe.take(MONEY_TEXT_LIMIT))
            SettlementField.NOTE -> state.draft.copy(note = safe)
            SettlementField.PARTICIPANT_NAME -> state.draft.copy(participantName = safe)
        }
        return state.copy(draft = draft, presentation = SettlementPresentation.EDIT, validationFields = emptySet())
    }

    public fun validateActivity(state: SettlementFeatureState): SettlementFeatureState {
        val errors = buildSet {
            if (state.draft.name.isBlank()) add("name")
            if (runCatching { LocalDate.parse(state.draft.startDate) }.isFailure) add("startDate")
            val start = runCatching { LocalDate.parse(state.draft.startDate) }.getOrNull()
            val end = state.draft.endDate.takeIf(String::isNotBlank)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val invalidEnd = state.draft.endDate.isNotBlank() && end == null
            val reversedRange = start != null && end != null && end < start
            if (invalidEnd || reversedRange) add("endDate")
            val includedCount = state.draft.participants.count { it.included }
            val selfCount = state.draft.participants.count { it.isSelf && it.included }
            if (includedCount < MINIMUM_PARTICIPANTS || selfCount != REQUIRED_SELF_COUNT) add("participants")
        }
        return state.copy(validationFields = errors, presentation = if (errors.isEmpty()) state.presentation else SettlementPresentation.VALIDATION_ERROR)
    }

    public fun minor(text: String, currency: app.ledger.core.money.CurrencyCode): Long? = runCatching {
        val scale = requireNotNull(catalog.find(currency)).fractionDigits
        BigDecimal(text.trim().replace(',', '.')).movePointRight(scale).setScale(0, RoundingMode.UNNECESSARY).longValueExact().takeIf { it >= 0L }
    }.getOrNull()

    public fun money(
        minor: Long,
        currency: app.ledger.core.money.CurrencyCode,
        locale: Locale,
        semantic: AmountSemantic = AmountSemantic.NEUTRAL,
    ): MoneyUiModel {
        val request = MoneyFormatRequest(Money(minor, currency), locale, semantic, AmountVisibility.VISIBLE)
        return (formatter.format(request) as DomainResult.Success<MoneyUiModel>).value
    }

    private const val LONG_TEXT_LIMIT = 500
    private const val SHORT_TEXT_LIMIT = 80
    private const val DATE_TEXT_LIMIT = 10
    private const val MONEY_TEXT_LIMIT = 32
    private const val MINIMUM_PARTICIPANTS = 2
    private const val REQUIRED_SELF_COUNT = 1
}
