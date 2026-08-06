@file:Suppress("MagicNumber", "TooManyFunctions", "LongParameterList", "CyclomaticComplexMethod")

package app.ledger.feature.automation

import app.ledger.core.common.StableId
import app.ledger.finance.application.AutomationSnapshot
import app.ledger.finance.application.BlueprintView
import app.ledger.finance.application.CandidateView
import app.ledger.finance.application.OrdinaryTransactionEntrySnapshot
import app.ledger.finance.application.RecurrenceSeriesView
import app.ledger.finance.domain.MissingDayPolicy
import app.ledger.finance.domain.RecurrenceFrequency
import app.ledger.finance.domain.RecurrenceGenerationMode
import app.ledger.finance.domain.RecurrenceModificationScope
import app.ledger.finance.domain.RecurrenceRule
import app.ledger.finance.domain.RecurrenceStatus
import app.ledger.finance.domain.TransactionKind
import app.ledger.finance.domain.WeekendAdjustment
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

public enum class AutomationPresentation {
    CONTENT,
    EMPTY,
    CREATE,
    EDIT,
    PAUSED,
    EDITING,
    INVALID,
    VALIDATION_ERROR,
    SELECTION,
    INVALID_SOURCE,
    SAVING,
}

public data class BlueprintEditorDraft(
    val id: StableId?,
    val name: String = "",
    val targetKind: TransactionKind = TransactionKind.EXPENSE,
    val categoryId: StableId? = null,
    val primaryAccountId: StableId? = null,
    val secondaryAccountId: StableId? = null,
    val cardId: StableId? = null,
    val merchantId: StableId? = null,
    val projectId: StableId? = null,
    val goalId: StableId? = null,
    val settlementActivityId: StableId? = null,
    val amountExpression: String = "",
    val currency: String = "",
    val noteTemplate: String = "",
    val fixedPlaceId: StableId? = null,
)

public data class RecurrenceEditorDraft(
    val id: StableId?,
    val blueprintId: StableId?,
    val status: RecurrenceStatus = RecurrenceStatus.ACTIVE,
    val rule: RecurrenceRule = RecurrenceRule(
        frequency = RecurrenceFrequency.MONTHLY_DAY,
        interval = 1,
        weekdays = emptySet(),
        monthDay = 1,
        nthWeek = null,
        weekday = null,
        missingDayPolicy = MissingDayPolicy.MOVE_TO_MONTH_END,
        weekendAdjustment = WeekendAdjustment.NONE,
    ),
    val startDate: String = "",
    val endDate: String = "",
    val maxOccurrences: String = "",
    val occurrenceTime: LocalTime = LocalTime.of(9, 0),
    val zoneId: ZoneId,
    val generationMode: RecurrenceGenerationMode = RecurrenceGenerationMode.CANDIDATE,
    val fixedPlaceId: StableId? = null,
    val notifyCandidate: Boolean = true,
)

public data class AutomationFeatureState(
    val snapshot: AutomationSnapshot,
    val entrySnapshot: OrdinaryTransactionEntrySnapshot,
    val screenId: String,
    val presentation: AutomationPresentation,
    val selectedBlueprintId: StableId? = null,
    val selectedSeriesId: StableId? = null,
    val selectedCandidateId: StableId? = null,
    val blueprintDraft: BlueprintEditorDraft? = null,
    val recurrenceDraft: RecurrenceEditorDraft? = null,
    val search: String = "",
    val selectedCandidateIds: Set<StableId> = emptySet(),
    val modificationScope: RecurrenceModificationScope = RecurrenceModificationScope.THIS_OCCURRENCE,
    val validationFields: Set<String> = emptySet(),
    val failureCode: String? = null,
) {
    public val selectedBlueprint: BlueprintView?
        get() = selectedBlueprintId?.let { id -> snapshot.blueprints.singleOrNull { it.id == id } }

    public val selectedSeries: RecurrenceSeriesView?
        get() = selectedSeriesId?.let { id -> snapshot.series.singleOrNull { it.id == id } }

    public val selectedCandidate: CandidateView?
        get() = selectedCandidateId?.let { id -> snapshot.candidates.singleOrNull { it.id == id } }
}

public sealed interface AutomationLoadState {
    public data object Loading : AutomationLoadState
    public data class Content(val state: AutomationFeatureState) : AutomationLoadState
    public data class Failure(val code: String) : AutomationLoadState
}

public enum class BlueprintField { NAME, AMOUNT, CURRENCY, NOTE }
public enum class RecurrenceField { START_DATE, END_DATE, MAX_OCCURRENCES, INTERVAL, MONTH_DAY, NTH_WEEK }

public object AutomationPolicy {
    public fun create(
        snapshot: AutomationSnapshot,
        entrySnapshot: OrdinaryTransactionEntrySnapshot,
        screenId: String,
        blueprintId: StableId?,
        seriesId: StableId?,
        candidateId: StableId?,
        zoneId: ZoneId,
        today: LocalDate,
    ): AutomationFeatureState {
        val blueprint = blueprintId?.let { id -> snapshot.blueprints.singleOrNull { it.id == id } }
        val series = seriesId?.let { id -> snapshot.series.singleOrNull { it.id == id } }
        val candidate = candidateId?.let { id -> snapshot.candidates.singleOrNull { it.id == id } }
        val presentation = when (screenId) {
            "AUT-002" -> if (snapshot.blueprints.isEmpty()) AutomationPresentation.EMPTY else AutomationPresentation.CONTENT
            "AUT-003" -> if (blueprint == null) AutomationPresentation.CREATE else AutomationPresentation.EDIT
            "AUT-004" -> when {
                snapshot.series.isEmpty() -> AutomationPresentation.EMPTY
                snapshot.series.all { it.status == RecurrenceStatus.PAUSED } -> AutomationPresentation.PAUSED
                else -> AutomationPresentation.CONTENT
            }
            "AUT-005" -> if (series == null) AutomationPresentation.CREATE else AutomationPresentation.EDIT
            "AUT-006" -> AutomationPresentation.EDITING
            "AUT-007" -> if (series?.preview.isNullOrEmpty()) AutomationPresentation.EMPTY else AutomationPresentation.CONTENT
            "AUT-008" -> if (snapshot.candidates.isEmpty()) AutomationPresentation.EMPTY else AutomationPresentation.CONTENT
            "AUT-009" -> when {
                candidate == null || candidate.blueprint.status.name != "ACTIVE" -> AutomationPresentation.INVALID_SOURCE
                candidate.validationErrorCode != null -> AutomationPresentation.VALIDATION_ERROR
                else -> AutomationPresentation.EDITING
            }
            else -> AutomationPresentation.CONTENT
        }
        return AutomationFeatureState(
            snapshot = snapshot,
            entrySnapshot = entrySnapshot,
            screenId = screenId,
            presentation = presentation,
            selectedBlueprintId = blueprint?.id ?: blueprintId,
            selectedSeriesId = series?.id ?: seriesId,
            selectedCandidateId = candidate?.id ?: candidateId,
            blueprintDraft = if (screenId == "AUT-003") blueprintDraft(blueprint) else null,
            recurrenceDraft = if (screenId in setOf("AUT-005", "AUT-006", "AUT-010")) recurrenceDraft(series, zoneId, today) else null,
        )
    }

    public fun updateBlueprint(state: AutomationFeatureState, field: BlueprintField, value: String): AutomationFeatureState {
        val current = requireNotNull(state.blueprintDraft)
        val draft = when (field) {
            BlueprintField.NAME -> current.copy(name = value.take(80))
            BlueprintField.AMOUNT -> current.copy(amountExpression = value.take(128))
            BlueprintField.CURRENCY -> current.copy(currency = value.uppercase().take(3))
            BlueprintField.NOTE -> current.copy(noteTemplate = value.take(500))
        }
        return state.copy(blueprintDraft = draft, presentation = AutomationPresentation.EDITING, validationFields = emptySet())
    }

    public fun updateRecurrence(state: AutomationFeatureState, field: RecurrenceField, value: String): AutomationFeatureState {
        val current = requireNotNull(state.recurrenceDraft)
        val draft = when (field) {
            RecurrenceField.START_DATE -> current.copy(startDate = value.take(10))
            RecurrenceField.END_DATE -> current.copy(endDate = value.take(10))
            RecurrenceField.MAX_OCCURRENCES -> current.copy(maxOccurrences = value.filter(Char::isDigit).take(7))
            RecurrenceField.INTERVAL -> current.copy(rule = current.rule.copy(interval = value.toIntOrNull()?.coerceIn(1, 999) ?: 1))
            RecurrenceField.MONTH_DAY -> current.copy(rule = current.rule.copy(monthDay = value.toIntOrNull()?.coerceIn(1, 31)))
            RecurrenceField.NTH_WEEK -> current.copy(rule = current.rule.copy(nthWeek = value.toIntOrNull()?.coerceIn(1, 5)))
        }
        return state.copy(recurrenceDraft = draft, presentation = AutomationPresentation.EDITING, validationFields = emptySet())
    }

    public fun validateBlueprint(state: AutomationFeatureState): AutomationFeatureState {
        val draft = requireNotNull(state.blueprintDraft)
        val invalid = buildSet {
            if (draft.name.isBlank()) add("name")
            if (draft.targetKind in setOf(TransactionKind.EXPENSE, TransactionKind.INCOME) && draft.categoryId == null) add("category")
            if (draft.primaryAccountId == null) add("account")
            if (draft.currency.isNotBlank() && !Regex("[A-Z]{3}").matches(draft.currency)) add("currency")
        }
        return state.copy(
            validationFields = invalid,
            presentation = if (invalid.isEmpty()) AutomationPresentation.SAVING else AutomationPresentation.VALIDATION_ERROR,
        )
    }

    public fun validateRecurrence(state: AutomationFeatureState): AutomationFeatureState {
        val draft = requireNotNull(state.recurrenceDraft)
        val start = runCatching { LocalDate.parse(draft.startDate) }.getOrNull()
        val end = draft.endDate.takeIf(String::isNotBlank)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val invalid = buildSet {
            if (draft.blueprintId == null) add("blueprint")
            if (start == null) add("startDate")
            if (draft.endDate.isNotBlank() && end == null) add("endDate")
            if (start != null && end != null && end < start) add("endDate")
            if (draft.maxOccurrences.isNotBlank() && (draft.maxOccurrences.toIntOrNull() ?: 0) <= 0) add("maxOccurrences")
        }
        return state.copy(
            validationFields = invalid,
            presentation = if (invalid.isEmpty()) AutomationPresentation.SAVING else AutomationPresentation.INVALID,
        )
    }

    private fun blueprintDraft(blueprint: BlueprintView?): BlueprintEditorDraft = BlueprintEditorDraft(
        id = blueprint?.id,
        name = blueprint?.name.orEmpty(),
        targetKind = blueprint?.targetKind ?: TransactionKind.EXPENSE,
        categoryId = blueprint?.categoryId,
        primaryAccountId = blueprint?.primaryAccountId,
        secondaryAccountId = blueprint?.secondaryAccountId,
        cardId = blueprint?.cardId,
        merchantId = blueprint?.merchantId,
        projectId = blueprint?.projectId,
        goalId = blueprint?.goalId,
        settlementActivityId = blueprint?.settlementActivityId,
        amountExpression = blueprint?.amountExpression.orEmpty(),
        currency = blueprint?.currency?.value.orEmpty(),
        noteTemplate = blueprint?.noteTemplate.orEmpty(),
        fixedPlaceId = blueprint?.fixedPlaceId,
    )

    private fun recurrenceDraft(series: RecurrenceSeriesView?, zoneId: ZoneId, today: LocalDate): RecurrenceEditorDraft = RecurrenceEditorDraft(
        id = series?.id,
        blueprintId = series?.blueprintId,
        status = series?.status ?: RecurrenceStatus.ACTIVE,
        rule = series?.rule ?: RecurrenceRule(
            frequency = RecurrenceFrequency.MONTHLY_DAY,
            interval = 1,
            weekdays = emptySet(),
            monthDay = today.dayOfMonth,
            nthWeek = null,
            weekday = null,
            missingDayPolicy = MissingDayPolicy.MOVE_TO_MONTH_END,
            weekendAdjustment = WeekendAdjustment.NONE,
        ),
        startDate = series?.startDate?.toString() ?: today.toString(),
        endDate = series?.endDate?.toString().orEmpty(),
        maxOccurrences = series?.maxOccurrences?.toString().orEmpty(),
        occurrenceTime = series?.occurrenceTime ?: LocalTime.of(9, 0),
        zoneId = series?.zoneId ?: zoneId,
        generationMode = series?.generationMode ?: RecurrenceGenerationMode.CANDIDATE,
        fixedPlaceId = series?.fixedPlaceId,
        notifyCandidate = series?.notifyCandidate ?: true,
    )
}
