package app.ledger.finance.domain

import app.ledger.core.money.CurrencyCode
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

data class TransactionBlueprint(
    val id: TransactionBlueprintId,
    val name: String,
    val currentRevisionId: TransactionBlueprintRevisionId,
    val status: EntityStatus,
    val display: DisplayStyle,
) : LifecycleRecord<RecordLifecycle.Current> {
    override val lifecycle: RecordLifecycle.Current = RecordLifecycle.Current
}

data class TransactionBlueprintRevision(
    val id: TransactionBlueprintRevisionId,
    val blueprintId: TransactionBlueprintId,
    val revisionNumber: Int,
    val targetKind: TransactionKind,
    val categoryId: CategoryId?,
    val primaryAccountId: UserAccountId?,
    val secondaryAccountId: UserAccountId?,
    val cardId: PaymentCardId?,
    val merchantId: MerchantId?,
    val fixedAmountExpression: String?,
    val currency: CurrencyCode?,
    val projectId: ProjectId?,
    val goalId: GoalId?,
    val settlementActivityId: SettlementActivityId?,
    val settlementShareRule: BlueprintSettlementShareRule?,
    val noteTemplate: String?,
    val fixedPlaceId: PlaceId?,
    val createdCommitId: BookCommitId,
) : LifecycleRecord<RecordLifecycle.Revision> {
    override val lifecycle: RecordLifecycle.Revision = RecordLifecycle.Revision

    init {
        require(revisionNumber > 0)
    }
}

sealed interface BlueprintSettlementShareRule {
    data object Equal : BlueprintSettlementShareRule

    data class FixedAmounts(val amounts: Map<ParticipantId, Long>) : BlueprintSettlementShareRule

    data class Percentages(val percentages: Map<ParticipantId, Percentage>) : BlueprintSettlementShareRule

    data class Weights(val weights: Map<ParticipantId, BigDecimal>) : BlueprintSettlementShareRule

    data class Exclude(val participantIds: Set<ParticipantId>) : BlueprintSettlementShareRule
}

enum class RecurrenceStatus {
    ACTIVE,
    PAUSED,
    COMPLETED,
    CANCELLED,
    ARCHIVED,
}

data class RecurrenceSeries(
    val id: RecurrenceSeriesId,
    val blueprintId: TransactionBlueprintId,
    val currentRevisionId: RecurrenceSeriesRevisionId,
    val status: RecurrenceStatus,
) : LifecycleRecord<RecordLifecycle.Current> {
    override val lifecycle: RecordLifecycle.Current = RecordLifecycle.Current
}

enum class RecurrenceFrequency {
    DAILY,
    BUSINESS_DAYS,
    WEEKLY,
    MONTHLY_DAY,
    MONTHLY_LAST_DAY,
    MONTHLY_NTH_WEEKDAY,
    MONTH_INTERVAL,
    YEARLY,
    CUSTOM_INTERVAL,
}

enum class RecurrenceGenerationMode {
    FORMAL_TRANSACTION,
    CANDIDATE,
}

enum class RecurrenceModificationScope {
    THIS_OCCURRENCE,
    THIS_AND_FUTURE,
    ENTIRE_SERIES,
}

data class RecurrenceRule(
    val frequency: RecurrenceFrequency,
    val interval: Int,
    val weekdays: Set<DayOfWeek>,
    val monthDay: Int?,
    val nthWeek: Int?,
    val weekday: DayOfWeek?,
    val missingDayPolicy: MissingDayPolicy,
    val weekendAdjustment: WeekendAdjustment,
) {
    init {
        require(interval > 0)
        require(monthDay == null || monthDay in 1..MAX_MONTH_DAY)
        require(nthWeek == null || nthWeek in 1..MAX_NTH_WEEK)
        require((nthWeek == null) == (weekday == null))
    }
}

data class RecurrenceSeriesRevision(
    val id: RecurrenceSeriesRevisionId,
    val seriesId: RecurrenceSeriesId,
    val revisionNumber: Int,
    val rule: RecurrenceRule,
    val startDate: LocalDate,
    val endDate: LocalDate?,
    val maxOccurrences: Int?,
    val occurrenceTime: LocalTime,
    val zoneId: ZoneId,
    val generationMode: RecurrenceGenerationMode,
    val fixedPlaceId: PlaceId?,
    val notifyCandidate: Boolean,
    val createdCommitId: BookCommitId,
) : LifecycleRecord<RecordLifecycle.Revision> {
    override val lifecycle: RecordLifecycle.Revision = RecordLifecycle.Revision

    init {
        require(revisionNumber > 0)
        require(endDate == null || endDate >= startDate)
        require(maxOccurrences == null || maxOccurrences > 0)
    }
}

enum class RecurrenceExceptionAction {
    SKIP,
    MOVE,
    OVERRIDE_BLUEPRINT,
}

data class RecurrenceException(
    val seriesId: RecurrenceSeriesId,
    val occurrenceLocalDate: LocalDate,
    val action: RecurrenceExceptionAction,
    val overrideBlueprintRevisionId: TransactionBlueprintRevisionId?,
    val overrideInstant: Instant?,
) : LifecycleRecord<RecordLifecycle.Revision> {
    override val lifecycle: RecordLifecycle.Revision = RecordLifecycle.Revision
}

enum class RecurrenceOccurrenceStatus {
    PENDING,
    CANDIDATE_CREATED,
    TRANSACTION_CREATED,
    SKIPPED,
    FAILED,
    CANCELLED,
}

data class RecurrenceOccurrenceKey(
    val seriesId: RecurrenceSeriesId,
    val seriesRevisionId: RecurrenceSeriesRevisionId,
    val occurrenceInstant: Instant,
)

data class RecurrenceOccurrence(
    val id: RecurrenceOccurrenceId,
    val key: RecurrenceOccurrenceKey,
    val localDate: LocalDate,
    val status: RecurrenceOccurrenceStatus,
    val candidateId: RecurrenceCandidateId?,
    val transactionId: TransactionId?,
    val errorCode: String?,
) : LifecycleRecord<RecordLifecycle.Fact> {
    override val lifecycle: RecordLifecycle.Fact = RecordLifecycle.Fact

    init {
        require(
            when (status) {
                RecurrenceOccurrenceStatus.CANDIDATE_CREATED -> candidateId != null && transactionId == null
                RecurrenceOccurrenceStatus.TRANSACTION_CREATED -> transactionId != null && candidateId == null
                RecurrenceOccurrenceStatus.FAILED -> errorCode != null && candidateId == null && transactionId == null
                else -> candidateId == null && transactionId == null && errorCode == null
            },
        )
    }
}

enum class RecurrenceCandidateStatus {
    PENDING_CONFIRMATION,
    ACCEPTED,
    REJECTED,
    INVALID,
}

/** Candidate is Current-only and cannot carry Journal, Posting, effects, or a FinancialCommand. */
data class RecurrenceCandidate(
    val id: RecurrenceCandidateId,
    val occurrenceId: RecurrenceOccurrenceId,
    val blueprintRevisionId: TransactionBlueprintRevisionId,
    val createdAt: Instant,
    val status: RecurrenceCandidateStatus,
    val validationErrorCode: String?,
) : LifecycleRecord<RecordLifecycle.Current> {
    override val lifecycle: RecordLifecycle.Current = RecordLifecycle.Current
}

data class EncryptedBlob(
    val id: BlobId,
    val storageName: String,
    val plaintextSha256: Hash256,
    val plaintextSize: Long,
    val mimeType: String?,
    val extension: String?,
    val wrappedDataKey: EncryptedField,
    val encryptionVersion: Int,
    val referenceCountProjection: Long,
    val createdAt: Instant,
) : LifecycleRecord<RecordLifecycle.Current> {
    override val lifecycle: RecordLifecycle.Current = RecordLifecycle.Current

    init {
        require(plaintextSize >= 0L)
        require(encryptionVersion > 0)
        require(referenceCountProjection >= 0L)
    }
}

enum class AttachmentStatus {
    ACTIVE,
    TRASHED_REFERENCE,
    ARCHIVED,
}

data class Attachment(
    val id: AttachmentId,
    val blobId: BlobId,
    val displayName: String,
    val importedAt: Instant,
    val status: AttachmentStatus,
) : LifecycleRecord<RecordLifecycle.Current> {
    override val lifecycle: RecordLifecycle.Current = RecordLifecycle.Current
}

enum class BlobGcReason {
    NO_CURRENT_REFERENCE,
    NO_HISTORY_REFERENCE,
    PURGED_CHAIN,
}

data class BlobGcCandidate(
    val blobId: BlobId,
    val eligibleAfter: Instant,
    val reason: BlobGcReason,
    val lastCheckedAt: Instant?,
) : LifecycleRecord<RecordLifecycle.Operation> {
    override val lifecycle: RecordLifecycle.Operation = RecordLifecycle.Operation
}

private const val MAX_MONTH_DAY = 31
private const val MAX_NTH_WEEK = 5
