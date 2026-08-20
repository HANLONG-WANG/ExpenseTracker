package app.ledger.finance.application

import app.ledger.core.common.CommandId
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.money.CurrencyCode
import app.ledger.finance.domain.CommandReceipt
import app.ledger.finance.domain.EntityStatus
import app.ledger.finance.domain.PlannedRecurrenceOccurrence
import app.ledger.finance.domain.RecurrenceCandidateStatus
import app.ledger.finance.domain.RecurrenceExceptionAction
import app.ledger.finance.domain.RecurrenceGenerationMode
import app.ledger.finance.domain.RecurrenceModificationScope
import app.ledger.finance.domain.RecurrenceOccurrenceStatus
import app.ledger.finance.domain.RecurrenceRule
import app.ledger.finance.domain.RecurrenceStatus
import app.ledger.finance.domain.TransactionKind
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

data class AutomationMutationIds(
    val bookId: StableId,
    val commandId: CommandId,
    val commitId: StableId,
    val entityRevisionId: StableId,
    val deviceInstanceId: StableId,
    val expectedLocalRevision: Long,
    val changedAt: Instant,
) {
    init {
        require(expectedLocalRevision >= 0L)
        require(setOf(bookId, commandId.stableId, commitId, entityRevisionId, deviceInstanceId).size == AUTOMATION_FIXED_ID_COUNT)
    }
}

data class BlueprintDraft(
    val id: StableId,
    val revisionId: StableId,
    val expectedRevisionId: StableId?,
    val name: String,
    val iconKey: String,
    val colorArgb: Int,
    val status: EntityStatus,
    val targetKind: TransactionKind,
    val categoryId: StableId?,
    val primaryAccountId: StableId?,
    val secondaryAccountId: StableId?,
    val cardId: StableId?,
    val merchantId: StableId?,
    val projectId: StableId?,
    val goalId: StableId?,
    val settlementActivityId: StableId?,
    val amountExpression: String?,
    val currency: CurrencyCode?,
    val noteTemplate: String?,
    val fixedPlaceId: StableId?,
) {
    init {
        require(name.isNotBlank())
        require(iconKey.isNotBlank())
        require(amountExpression == null || amountExpression.isNotBlank())
        require(noteTemplate == null || noteTemplate.isNotBlank())
        require(expectedRevisionId != revisionId)
    }
}

data class BlueprintView(
    val id: StableId,
    val revisionId: StableId,
    val revisionNumber: Int,
    val name: String,
    val iconKey: String,
    val colorArgb: Int,
    val status: EntityStatus,
    val targetKind: TransactionKind,
    val categoryId: StableId?,
    val primaryAccountId: StableId?,
    val secondaryAccountId: StableId?,
    val cardId: StableId?,
    val merchantId: StableId?,
    val projectId: StableId?,
    val goalId: StableId?,
    val settlementActivityId: StableId?,
    val amountExpression: String?,
    val currency: CurrencyCode?,
    val noteTemplate: String?,
    val fixedPlaceId: StableId?,
)

data class RecurrenceSeriesDraft(
    val id: StableId,
    val revisionId: StableId,
    val expectedRevisionId: StableId?,
    val blueprintId: StableId,
    val status: RecurrenceStatus,
    val rule: RecurrenceRule,
    val startDate: LocalDate,
    val endDate: LocalDate?,
    val maxOccurrences: Int?,
    val occurrenceTime: LocalTime,
    val zoneId: ZoneId,
    val generationMode: RecurrenceGenerationMode,
    val fixedPlaceId: StableId?,
    val notifyCandidate: Boolean,
) {
    init {
        require(endDate == null || endDate >= startDate)
        require(maxOccurrences == null || maxOccurrences > 0)
        require(expectedRevisionId != revisionId)
    }
}

data class RecurrenceSeriesView(
    val id: StableId,
    val revisionId: StableId,
    val revisionNumber: Int,
    val blueprintId: StableId,
    val blueprintName: String,
    val status: RecurrenceStatus,
    val rule: RecurrenceRule,
    val startDate: LocalDate,
    val endDate: LocalDate?,
    val maxOccurrences: Int?,
    val occurrenceTime: LocalTime,
    val zoneId: ZoneId,
    val generationMode: RecurrenceGenerationMode,
    val fixedPlaceId: StableId?,
    val notifyCandidate: Boolean,
    val preview: List<PlannedRecurrenceOccurrence>,
)

data class RecurrenceExceptionDraft(
    val localDate: LocalDate,
    val action: RecurrenceExceptionAction,
    val overrideBlueprintRevisionId: StableId?,
    val overrideInstant: Instant?,
)

data class CandidateView(
    val id: StableId,
    val occurrenceId: StableId,
    val seriesId: StableId,
    val blueprint: BlueprintView,
    val occurrenceInstant: Instant,
    val localDate: LocalDate,
    val status: RecurrenceCandidateStatus,
    val validationErrorCode: String?,
)

data class OccurrenceView(
    val id: StableId,
    val seriesId: StableId,
    val seriesRevisionId: StableId,
    val occurrenceInstant: Instant,
    val localDate: LocalDate,
    val status: RecurrenceOccurrenceStatus,
    val candidateId: StableId?,
    val transactionId: StableId?,
    val errorCode: String?,
)

data class AutomationSnapshot(
    val bookId: StableId,
    val localRevision: Long,
    val blueprints: List<BlueprintView>,
    val series: List<RecurrenceSeriesView>,
    val candidates: List<CandidateView>,
    val occurrences: List<OccurrenceView>,
)

data class SaveBlueprintRequest(val ids: AutomationMutationIds, val draft: BlueprintDraft)

data class SaveRecurrenceRequest(
    val ids: AutomationMutationIds,
    val draft: RecurrenceSeriesDraft,
    val exceptions: List<RecurrenceExceptionDraft> = emptyList(),
) {
    init {
        require(exceptions.map(RecurrenceExceptionDraft::localDate).toSet().size == exceptions.size)
    }
}

data class ModifyOccurrenceRequest(
    val ids: AutomationMutationIds,
    val seriesId: StableId,
    val occurrenceLocalDate: LocalDate,
    val scope: RecurrenceModificationScope,
    val replacement: RecurrenceSeriesDraft,
)

data class CatchUpResult(
    val createdCandidates: Int,
    val createdTransactions: Int,
    val skipped: Int,
    val failed: Int,
) {
    init {
        require(listOf(createdCandidates, createdTransactions, skipped, failed).all { it >= 0 })
    }
}

/** Request passed to the composition root; it contains no DAO, Entity or mutable fact. */
data class FormalOccurrenceRequest(
    val bookId: StableId,
    val occurrenceId: StableId,
    val seriesId: StableId,
    val seriesRevisionId: StableId,
    val blueprint: BlueprintView,
    val occurrenceInstant: Instant,
    val localDate: LocalDate,
    val zoneId: ZoneId,
)

fun interface FormalOccurrenceGenerator {
    suspend fun generate(request: FormalOccurrenceRequest): DomainResult<StableId>
}

interface AutomationApplicationPort {
    suspend fun snapshot(bookId: StableId): DomainResult<AutomationSnapshot>

    suspend fun saveBlueprint(request: SaveBlueprintRequest): DomainResult<CommandReceipt>

    suspend fun saveSeries(request: SaveRecurrenceRequest): DomainResult<CommandReceipt>

    suspend fun modifyOccurrence(request: ModifyOccurrenceRequest): DomainResult<CommandReceipt>

    suspend fun catchUp(operationId: StableId, through: Instant): DomainResult<CatchUpResult>

    suspend fun retryOccurrence(operationId: StableId, occurrenceId: StableId): DomainResult<CatchUpResult>

    suspend fun confirmCandidate(bookId: StableId, candidateId: StableId): DomainResult<CandidateView>

    suspend fun completeCandidate(bookId: StableId, candidateId: StableId, transactionId: StableId): DomainResult<Unit>

    suspend fun skipCandidate(bookId: StableId, candidateId: StableId, changedAt: Instant): DomainResult<Unit>
}

private const val AUTOMATION_FIXED_ID_COUNT = 5
