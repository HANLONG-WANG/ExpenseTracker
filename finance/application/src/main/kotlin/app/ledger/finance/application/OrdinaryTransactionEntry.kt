@file:Suppress("MagicNumber")

package app.ledger.finance.application

import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.money.CurrencyCode
import app.ledger.finance.domain.CommandReceipt
import app.ledger.finance.domain.TransactionSource
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Read model used by the category-first ordinary transaction flow. */
public data class OrdinaryTransactionEntrySnapshot(
    val references: ReferenceDataSnapshot,
    val projects: List<OrdinaryProjectView>,
    val settlementActivities: List<OrdinarySettlementActivityView>,
    val templates: List<OrdinaryTemplateView>,
    val recentDefaults: List<OrdinaryRecentDefaultView>,
    val editing: OrdinaryTransactionEditView?,
)

public data class OrdinaryProjectView(
    val id: StableId,
    val name: String,
    val active: Boolean,
)

public data class OrdinaryParticipantView(
    val id: StableId,
    val name: String,
    val isSelf: Boolean,
)

public data class OrdinarySettlementActivityView(
    val id: StableId,
    val name: String,
    val currency: CurrencyCode,
    val participants: List<OrdinaryParticipantView>,
    val active: Boolean,
)

/** A blueprint never contains an actual occurrence time, captured location, attachment, or live FX quote. */
public data class OrdinaryTemplateView(
    val id: StableId,
    val name: String,
    val direction: OrdinaryDirection,
    val categoryId: StableId?,
    val accountId: StableId?,
    val cardId: StableId?,
    val merchantId: StableId?,
    val projectId: StableId?,
    val settlementActivityId: StableId?,
    val amountExpression: String?,
    val currency: CurrencyCode?,
    val noteTemplate: String?,
    val fixedPlaceId: StableId?,
)

public data class OrdinaryRecentDefaultView(
    val direction: OrdinaryDirection,
    val categoryId: StableId,
    val accountId: StableId,
    val cardId: StableId?,
    val occurredAt: Instant,
)

public data class OrdinaryTransactionEditView(
    val transactionId: StableId,
    val revisionId: StableId,
    val direction: OrdinaryDirection,
    val categoryId: StableId,
    val expression: String?,
    val userMinor: Long,
    val userCurrency: CurrencyCode,
    val accountMinor: Long,
    val accountId: StableId,
    val cardId: StableId?,
    val merchantId: StableId?,
    val occurredAt: Instant,
    val zoneId: ZoneId,
    val projectId: StableId?,
    val settlementActivityId: StableId?,
    val settlementShares: List<OrdinarySettlementShareDraft>,
    val locationRecordId: StableId?,
    val note: String?,
    val attachmentIds: List<StableId>,
    val source: TransactionSource,
    val sourceReferenceId: StableId?,
)

public enum class OrdinaryDirection { EXPENSE, INCOME }

public enum class OrdinaryLocationProvider { FUSED, GPS, NETWORK, MANUAL }

public data class OrdinaryLocationDraft(
    val id: StableId,
    val latitudeE7: Int,
    val longitudeE7: Int,
    val accuracyMillimeters: Int?,
    val capturedAt: Instant,
    val provider: OrdinaryLocationProvider,
    val placeId: StableId?,
) {
    init {
        require(latitudeE7 in -900_000_000..900_000_000)
        require(longitudeE7 in -1_800_000_000..1_800_000_000)
        require(accuracyMillimeters == null || accuracyMillimeters >= 0)
    }
}

public data class OrdinarySettlementShareDraft(
    val participantId: StableId,
    val paidMinor: Long,
    val owedMinor: Long,
    val weight: BigDecimal?,
    val roundingAdjustmentMinor: Long,
) {
    init {
        require(paidMinor >= 0L && owedMinor >= 0L)
        require(weight == null || weight.signum() > 0)
    }
}

/** Stable identifiers are supplied once so retrying a submission is idempotent. */
public data class OrdinaryTransactionWriteIds(
    val bookId: StableId,
    val commandId: StableId,
    val transactionId: StableId,
    val revisionId: StableId,
    val commitId: StableId,
    val deviceInstanceId: StableId,
    val factIds: List<StableId>,
    val fxRateSnapshotIds: List<StableId>,
) {
    init {
        require(factIds.isNotEmpty())
        val all = listOf(bookId, commandId, transactionId, revisionId, commitId, deviceInstanceId) + factIds + fxRateSnapshotIds
        require(all.toSet().size == all.size)
    }
}

/**
 * All three amounts are authoritative integer-minor evidence. Rates are derived and frozen by the
 * data adapter; no floating-point value crosses this API.
 */
public data class OrdinaryAmountDraft(
    val expression: String,
    val userMinor: Long,
    val userCurrency: CurrencyCode,
    val accountMinor: Long,
    val baseMinor: Long,
) {
    init {
        require(expression.isNotBlank())
        require(userMinor > 0L && accountMinor > 0L && baseMinor > 0L)
    }
}

public data class OrdinaryTransactionWriteRequest(
    val ids: OrdinaryTransactionWriteIds,
    val expectedRevisionId: StableId?,
    val direction: OrdinaryDirection,
    val categoryId: StableId,
    val amount: OrdinaryAmountDraft,
    val accountId: StableId,
    val cardId: StableId?,
    val merchantId: StableId?,
    val occurredAt: Instant,
    val zoneId: ZoneId,
    val localDate: LocalDate,
    val projectId: StableId?,
    val settlementActivityId: StableId?,
    val settlementShares: List<OrdinarySettlementShareDraft>,
    val locationRecordId: StableId?,
    val newLocation: OrdinaryLocationDraft?,
    val note: String?,
    val attachmentIds: List<StableId>,
    val source: TransactionSource,
    val sourceReferenceId: StableId?,
    val createdAt: Instant,
) {
    init {
        require(expectedRevisionId == null || expectedRevisionId != ids.revisionId)
        require(newLocation == null || newLocation.id == locationRecordId)
        require(attachmentIds.toSet().size == attachmentIds.size)
        require(note == null || note.isNotBlank())
        require(settlementActivityId != null || settlementShares.isEmpty())
    }
}

/** The sole application port used by REC-001—REC-012 for ordinary financial reads and writes. */
public interface OrdinaryTransactionEntryPort {
    public suspend fun snapshot(bookId: StableId, transactionId: StableId? = null): DomainResult<OrdinaryTransactionEntrySnapshot>

    public suspend fun submit(request: OrdinaryTransactionWriteRequest): DomainResult<CommandReceipt>
}
