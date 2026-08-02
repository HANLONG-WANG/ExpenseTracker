@file:Suppress("MagicNumber")

package app.ledger.finance.application

import app.ledger.core.common.CommandId
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.finance.domain.CommandReceipt
import java.time.Instant
import java.time.LocalDate

public data class OpeningBalanceWriteIds(
    val bookId: StableId,
    val commandId: CommandId,
    val transactionId: StableId,
    val transactionRevisionId: StableId,
    val commitId: StableId,
    val deviceInstanceId: StableId,
    val fxRateSnapshotId: StableId,
    val factIds: List<StableId>,
) {
    init {
        val all = listOf(bookId, commandId.stableId, transactionId, transactionRevisionId, commitId, deviceInstanceId, fxRateSnapshotId) + factIds
        require(factIds.isNotEmpty())
        require(all.toSet().size == all.size)
    }
}

public data class OpeningBalanceWriteRequest(
    val ids: OpeningBalanceWriteIds,
    val accountId: StableId,
    val balanceDate: LocalDate,
    val accountMinor: Long,
    val baseMinor: Long?,
    val createdAt: Instant,
) {
    init {
        require(accountMinor > 0L)
        require(baseMinor == null || baseMinor > 0L)
    }
}

/** Financial opening balances are deliberately outside the reference-data mutation port. */
public fun interface OpeningBalanceWritePort {
    public suspend fun record(request: OpeningBalanceWriteRequest): DomainResult<CommandReceipt>
}
