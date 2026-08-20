@file:Suppress("LongParameterList", "MagicNumber")

package app.ledger.finance.application

import app.ledger.core.common.CommandId
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.money.CurrencyCode
import app.ledger.core.money.FxEvidence
import app.ledger.finance.domain.BalanceAdjustmentDirection
import app.ledger.finance.domain.CommandReceipt
import app.ledger.finance.domain.FxValuationPolicy
import app.ledger.finance.domain.TransactionKind
import app.ledger.finance.domain.TransactionSource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

public data class SpecializedTransactionSnapshot(
    val references: ReferenceDataSnapshot,
    val valuationRevision: Long,
    val editing: SpecializedTransactionEditView? = null,
)

public data class SpecializedTransactionEditView(
    val transactionId: StableId,
    val revisionId: StableId,
    val kind: TransactionKind,
    val fromAccountId: StableId,
    val toAccountId: StableId?,
    val outgoingMinor: Long,
    val incomingMinor: Long?,
    val outgoingBaseMinor: Long,
    val incomingBaseMinor: Long?,
    val amountExpression: String?,
    val occurredAt: Instant,
    val zoneId: ZoneId,
    val localDate: LocalDate,
    val note: String?,
    val attachmentIds: List<StableId>,
    val direction: BalanceAdjustmentDirection,
    val checkpointId: StableId?,
    val source: TransactionSource,
    val sourceReferenceId: StableId?,
)

public data class SpecializedFxQuote(
    val evidence: FxEvidence,
    val stale: Boolean,
)

public data class SpecializedFxQuoteRequest(
    val bookId: StableId,
    val sourceCurrency: CurrencyCode,
    val targetCurrency: CurrencyCode,
    val effectiveDate: LocalDate,
    val refreshOnline: Boolean,
) {
    init {
        require(sourceCurrency != targetCurrency)
    }
}

public data class SpecializedTransactionWriteIds(
    val bookId: StableId,
    val commandId: CommandId,
    val transactionId: StableId,
    val revisionId: StableId,
    val commitId: StableId,
    val deviceInstanceId: StableId,
    val factIds: List<StableId>,
    val fxRateSnapshotIds: List<StableId>,
    val expectedRevisionId: StableId? = null,
) {
    init {
        require(factIds.isNotEmpty())
        val all = listOf(bookId, commandId.stableId, transactionId, revisionId, commitId, deviceInstanceId) +
            factIds + fxRateSnapshotIds
        require(all.toSet().size == all.size)
    }
}

public data class SpecializedTransactionContext(
    val occurredAt: Instant,
    val zoneId: ZoneId,
    val localDate: LocalDate,
    val amountExpression: String?,
    val note: String?,
    val attachmentIds: List<StableId>,
    val createdAt: Instant,
) {
    init {
        require(note == null || note.isNotBlank())
        require(amountExpression == null || amountExpression.isNotBlank())
        require(attachmentIds.toSet().size == attachmentIds.size)
    }
}

/** Account amount is authoritative; base amount and immutable evidence explain only its historical valuation. */
public data class SpecializedAccountAmountDraft(
    val accountId: StableId,
    val accountMinor: Long,
    val baseMinor: Long,
    val accountToBaseEvidence: FxEvidence?,
) {
    init {
        require(accountMinor > 0L)
        require(baseMinor > 0L)
    }
}

public sealed interface SpecializedTransactionWriteRequest {
    public val ids: SpecializedTransactionWriteIds
    public val context: SpecializedTransactionContext

    public data class Transfer(
        override val ids: SpecializedTransactionWriteIds,
        override val context: SpecializedTransactionContext,
        val outgoing: SpecializedAccountAmountDraft,
        val incoming: SpecializedAccountAmountDraft,
    ) : SpecializedTransactionWriteRequest {
        init {
            require(outgoing.accountId != incoming.accountId)
            require(outgoing.baseMinor == incoming.baseMinor)
        }
    }

    public data class BalanceAdjustment(
        override val ids: SpecializedTransactionWriteIds,
        override val context: SpecializedTransactionContext,
        val amount: SpecializedAccountAmountDraft,
        val direction: BalanceAdjustmentDirection,
        val checkpointId: StableId?,
    ) : SpecializedTransactionWriteRequest

    public data class FxExchange(
        override val ids: SpecializedTransactionWriteIds,
        override val context: SpecializedTransactionContext,
        val outgoing: SpecializedAccountAmountDraft,
        val incoming: SpecializedAccountAmountDraft,
        val valuationPolicy: FxValuationPolicy,
        val spreadCostBaseMinor: Long?,
    ) : SpecializedTransactionWriteRequest {
        init {
            require(outgoing.accountId != incoming.accountId)
            require(spreadCostBaseMinor == null || spreadCostBaseMinor > 0L)
        }
    }

    public data class OpeningBalance(
        override val ids: SpecializedTransactionWriteIds,
        override val context: SpecializedTransactionContext,
        val amount: SpecializedAccountAmountDraft,
        val balanceDate: LocalDate,
    ) : SpecializedTransactionWriteRequest
}

/** Single P14 application boundary; all writes terminate at FinancialMutationCoordinator. */
public interface SpecializedTransactionEntryPort {
    public suspend fun snapshot(bookId: StableId, transactionId: StableId? = null): DomainResult<SpecializedTransactionSnapshot>

    public suspend fun quote(request: SpecializedFxQuoteRequest): DomainResult<SpecializedFxQuote?>

    public suspend fun submit(request: SpecializedTransactionWriteRequest): DomainResult<CommandReceipt>
}
