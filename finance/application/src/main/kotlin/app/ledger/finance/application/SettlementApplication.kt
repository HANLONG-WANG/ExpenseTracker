package app.ledger.finance.application

import app.ledger.core.common.CommandId
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.money.CurrencyCode
import app.ledger.core.money.FxEvidence
import app.ledger.finance.domain.CommandReceipt
import app.ledger.finance.domain.LocalRevision
import app.ledger.finance.domain.SettlementActivityStatus
import app.ledger.finance.domain.SettlementSplitMethod
import app.ledger.finance.domain.SettlementTransferSuggestion
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class SettlementParticipantView(
    val id: StableId,
    val name: String,
    val isSelf: Boolean,
    val active: Boolean,
)

data class SettlementPositionView(
    val participantId: StableId,
    val paidMinor: Long,
    val owedMinor: Long,
    val settledPaidMinor: Long,
    val settledReceivedMinor: Long,
    val netPositionMinor: Long,
)

data class SettlementPaymentView(
    val id: StableId,
    val payerParticipantId: StableId,
    val payeeParticipantId: StableId,
    val amountMinor: Long,
    val occurredAt: Instant,
    val linkedTransactionId: StableId?,
    val reversalOfId: StableId?,
)

data class SettlementTransactionView(
    val transactionId: StableId,
    val revisionId: StableId,
    val occurredAt: Instant,
    val totalMinor: Long,
    val selfOwedMinor: Long,
    val payerParticipantId: StableId,
    val owedMinorByParticipant: Map<StableId, Long>,
)

data class SettlementActivityView(
    val id: StableId,
    val name: String,
    val description: String?,
    val currency: CurrencyCode,
    val projectId: StableId?,
    val startDate: LocalDate?,
    val endDate: LocalDate?,
    val status: SettlementActivityStatus,
    val requiresAdditionalSettlement: Boolean,
    val lastCommitId: StableId,
    val participants: List<SettlementParticipantView>,
    val positions: List<SettlementPositionView>,
    val payments: List<SettlementPaymentView>,
    val transactions: List<SettlementTransactionView>,
    val suggestions: List<SettlementTransferSuggestion>,
)

data class SettlementAccountOption(
    val id: StableId,
    val name: String,
    val currency: CurrencyCode,
    val active: Boolean,
)

data class SettlementProjectOption(
    val id: StableId,
    val name: String,
    val active: Boolean,
)

data class SettlementSnapshot(
    val bookId: StableId,
    val baseCurrency: CurrencyCode,
    val localRevision: LocalRevision,
    val participants: List<SettlementParticipantView>,
    val activities: List<SettlementActivityView>,
    val accounts: List<SettlementAccountOption>,
    val projects: List<SettlementProjectOption>,
)

data class SettlementMutationIds(
    val bookId: StableId,
    val commandId: CommandId,
    val commitId: StableId,
    val deviceInstanceId: StableId,
    val entityRevisionIds: List<StableId>,
    val settlementLedgerIds: Map<StableId, StableId>,
    val expectedLocalRevision: LocalRevision,
) {
    init {
        require(entityRevisionIds.isNotEmpty())
        val ids = listOf(bookId, commandId.stableId, commitId, deviceInstanceId) + entityRevisionIds + settlementLedgerIds.values
        require(ids.toSet().size == ids.size)
    }
}

data class SettlementParticipantDraft(
    val id: StableId,
    val name: String,
    val isSelf: Boolean,
    val active: Boolean = true,
)

data class SaveSettlementActivityRequest(
    val ids: SettlementMutationIds,
    val activityId: StableId,
    val expectedLastCommitId: StableId?,
    val name: String,
    val description: String?,
    val currency: CurrencyCode,
    val projectId: StableId?,
    val startDate: LocalDate,
    val endDate: LocalDate?,
    val status: SettlementActivityStatus,
    val participants: List<SettlementParticipantDraft>,
    val changedAt: Instant,
) {
    init {
        require(name.isNotBlank())
        require(participants.size >= 2 && participants.map { it.id }.toSet().size == participants.size)
        require(participants.count { it.isSelf && it.active } == 1)
        require(endDate == null || endDate >= startDate)
    }
}

data class SettlementPaymentIds(
    val bookId: StableId,
    val commandId: CommandId,
    val transactionId: StableId,
    val revisionId: StableId,
    val paymentRecordId: StableId,
    val commitId: StableId,
    val deviceInstanceId: StableId,
    val factIds: List<StableId>,
    val fxRateSnapshotIds: List<StableId> = emptyList(),
) {
    init {
        require(factIds.isNotEmpty())
        val ids = listOf(bookId, commandId.stableId, transactionId, revisionId, paymentRecordId, commitId, deviceInstanceId) +
            factIds + fxRateSnapshotIds
        require(ids.toSet().size == ids.size)
    }
}

data class RecordSettlementPaymentRequest(
    val ids: SettlementPaymentIds,
    val activityId: StableId,
    val payerParticipantId: StableId,
    val payeeParticipantId: StableId,
    val amountMinor: Long,
    val accountId: StableId?,
    val accountMinor: Long?,
    val baseMinor: Long?,
    val settlementToAccountEvidence: FxEvidence?,
    val accountToBaseEvidence: FxEvidence?,
    val occurredAt: Instant,
    val zoneId: ZoneId,
    val localDate: LocalDate,
    val note: String?,
    val createdAt: Instant,
) {
    init {
        require(payerParticipantId != payeeParticipantId)
        require(amountMinor > 0L)
        require((accountId == null) == (accountMinor == null && baseMinor == null))
        require(accountMinor == null || accountMinor > 0L)
        require(baseMinor == null || baseMinor > 0L)
        require(note == null || note.isNotBlank())
    }
}

data class SettlementExpenseShareDraft(
    val participantId: StableId,
    val paidMinor: Long,
    val owedMinor: Long,
    val weight: java.math.BigDecimal?,
    val roundingAdjustmentMinor: Long,
)

data class SettlementExpenseIds(
    val bookId: StableId,
    val commandId: CommandId,
    val transactionId: StableId,
    val revisionId: StableId,
    val commitId: StableId,
    val deviceInstanceId: StableId,
    val factIds: List<StableId>,
    val fxRateSnapshotIds: List<StableId> = emptyList(),
) {
    init {
        require(factIds.isNotEmpty())
        val ids = listOf(bookId, commandId.stableId, transactionId, revisionId, commitId, deviceInstanceId) + factIds + fxRateSnapshotIds
        require(ids.toSet().size == ids.size)
    }
}

data class RecordSettlementExpenseRequest(
    val ids: SettlementExpenseIds,
    val categoryId: StableId,
    val activityId: StableId,
    val payerParticipantId: StableId,
    val localAccountId: StableId?,
    val cardId: StableId?,
    val totalMinor: Long,
    val accountMinor: Long?,
    val baseMinor: Long,
    val settlementToAccountEvidence: FxEvidence?,
    val accountToBaseEvidence: FxEvidence?,
    val shares: List<SettlementExpenseShareDraft>,
    val projectId: StableId?,
    val occurredAt: Instant,
    val zoneId: ZoneId,
    val localDate: LocalDate,
    val note: String?,
    val createdAt: Instant,
) {
    init {
        require(totalMinor > 0L && baseMinor > 0L)
        require((localAccountId == null) == (accountMinor == null && cardId == null))
        require(accountMinor == null || accountMinor > 0L)
        require(shares.size >= 2 && shares.map { it.participantId }.toSet().size == shares.size)
        require(note == null || note.isNotBlank())
    }
}

data class SettlementAllocationDraft(
    val method: SettlementSplitMethod,
    val totalMinor: Long,
    val taxMinor: Long,
    val serviceFeeMinor: Long,
)

interface SettlementApplicationPort {
    suspend fun snapshot(bookId: StableId): DomainResult<SettlementSnapshot>

    suspend fun saveActivity(request: SaveSettlementActivityRequest): DomainResult<Unit>

    suspend fun recordPayment(request: RecordSettlementPaymentRequest): DomainResult<CommandReceipt>

    suspend fun recordExpense(request: RecordSettlementExpenseRequest): DomainResult<CommandReceipt>

    suspend fun rebuildAndAudit(bookId: StableId): DomainResult<Unit>
}
