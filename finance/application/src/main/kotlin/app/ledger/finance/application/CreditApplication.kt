package app.ledger.finance.application

import app.ledger.core.common.CommandId
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.money.CurrencyCode
import app.ledger.finance.domain.AutoGenerationMode
import app.ledger.finance.domain.AutoPaymentEligibility
import app.ledger.finance.domain.CommandReceipt
import app.ledger.finance.domain.CreditPaymentSelection
import app.ledger.finance.domain.CreditStatementStatus
import app.ledger.finance.domain.DueDateRule
import app.ledger.finance.domain.LocalRevision
import app.ledger.finance.domain.StatementAssignmentMode
import app.ledger.finance.domain.StatementDateRule
import app.ledger.finance.domain.WeekendAdjustment
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class CreditMutationIds(
    val bookId: StableId,
    val commandId: CommandId,
    val commitId: StableId,
    val deviceInstanceId: StableId,
) {
    init {
        require(listOf(bookId, commandId.stableId, commitId, deviceInstanceId).toSet().size == CREDIT_MUTATION_ID_COUNT)
    }
}

data class CreditStatementMutationIds(
    val mutation: CreditMutationIds,
    val statementId: StableId,
    val statementRevisionId: StableId,
) {
    init {
        require(statementId !in setOf(mutation.bookId, mutation.commandId.stableId, mutation.commitId, mutation.deviceInstanceId))
        require(statementRevisionId !in setOf(mutation.bookId, mutation.commandId.stableId, mutation.commitId, mutation.deviceInstanceId, statementId))
    }
}

data class CreditTransactionMutationIds(
    val bookId: StableId,
    val commandId: CommandId,
    val transactionId: StableId,
    val revisionId: StableId,
    val commitId: StableId,
    val deviceInstanceId: StableId,
    val factIds: List<StableId>,
    val fxRateSnapshotIds: List<StableId>,
) {
    init {
        require(factIds.isNotEmpty())
        val ids = listOf(bookId, commandId.stableId, transactionId, revisionId, commitId, deviceInstanceId) + factIds + fxRateSnapshotIds
        require(ids.toSet().size == ids.size)
    }
}

data class CreditPaymentAccountView(
    val id: StableId,
    val name: String,
    val currency: CurrencyCode,
    val active: Boolean,
)

data class CreditProfileView(
    val statementRule: StatementDateRule,
    val dueRule: DueDateRule,
    val statementZoneId: ZoneId,
    val standardLimitMinor: Long?,
    val temporaryLimitMinor: Long?,
    val temporaryLimitExpiresOn: LocalDate?,
    val defaultPaymentAccountId: StableId?,
    val autoPaymentMode: AutoGenerationMode,
    val weekendAdjustment: WeekendAdjustment,
    val lastCommitId: StableId,
)

data class CreditStatementView(
    val id: StableId,
    val revisionId: StableId,
    val revisionNumber: Int,
    val cycleStart: LocalDate,
    val cycleEnd: LocalDate,
    val dueDate: LocalDate,
    val estimatedAmountMinor: Long,
    val officialAmountMinor: Long?,
    val differenceMinor: Long?,
    val paidAmountMinor: Long,
    val remainingAmountMinor: Long,
    val status: CreditStatementStatus,
    val sealed: Boolean,
    val transactions: List<CreditStatementTransactionView> = emptyList(),
    val paymentAllocations: List<CreditPaymentAllocationView> = emptyList(),
    val hasAutomaticPayment: Boolean = false,
)

data class CreditStatementTransactionView(
    val transactionId: StableId,
    val localDate: LocalDate,
    val amountMinor: Long,
    val note: String?,
)

data class CreditPaymentAllocationView(
    val transactionId: StableId,
    val localDate: LocalDate,
    val amountMinor: Long,
)

data class CreditAccountView(
    val id: StableId,
    val name: String,
    val currency: CurrencyCode,
    val archived: Boolean,
    val profile: CreditProfileView?,
    val signedLiabilityMinor: Long,
    val debtMinor: Long,
    val positiveBalanceMinor: Long,
    val availableLimitMinor: Long?,
    val unbilledMinor: Long,
    val overdueMinor: Long,
    val statements: List<CreditStatementView>,
    val futureInstallmentMinor: Long = 0L,
    val nextInstallmentDate: LocalDate? = null,
)

data class CreditSnapshot(
    val bookId: StableId,
    val baseCurrency: CurrencyCode,
    val localRevision: LocalRevision,
    val accounts: List<CreditAccountView>,
    val paymentAccounts: List<CreditPaymentAccountView>,
)

data class SaveCreditProfileRequest(
    val ids: CreditMutationIds,
    val accountId: StableId,
    val expectedLastCommitId: StableId?,
    val statementRule: StatementDateRule,
    val dueRule: DueDateRule,
    val statementZoneId: ZoneId,
    val standardLimitMinor: Long?,
    val temporaryLimitMinor: Long?,
    val temporaryLimitExpiresOn: LocalDate?,
    val defaultPaymentAccountId: StableId?,
    val autoPaymentMode: AutoGenerationMode,
    val weekendAdjustment: WeekendAdjustment,
    val limitEffectiveFrom: LocalDate?,
    val changedAt: Instant,
)

data class CreateCreditAccountProfileRequest(
    val account: AccountDraft,
    val profile: SaveCreditProfileRequest,
) {
    init {
        require(account.type == app.ledger.finance.domain.UserAccountType.CREDIT)
        require(account.accountId == profile.accountId)
        require(account.expectedRowVersion == null)
        require(profile.expectedLastCommitId == null)
    }
}

data class SaveCreditStatementRequest(
    val ids: CreditStatementMutationIds,
    val accountId: StableId,
    val expectedRevisionId: StableId?,
    val revisionNumber: Int,
    val cycleStart: LocalDate,
    val cycleEnd: LocalDate,
    val dueDate: LocalDate,
    val estimatedAmountMinor: Long,
    val officialAmountMinor: Long?,
    val officialRecordedAt: Instant?,
    val sealed: Boolean,
    val changedAt: Instant,
) {
    init {
        require(revisionNumber > 0)
        require(cycleEnd >= cycleStart && dueDate >= cycleEnd)
        require(estimatedAmountMinor >= 0L)
        require(officialAmountMinor == null || officialAmountMinor >= 0L)
        require((officialAmountMinor == null) == (officialRecordedAt == null))
    }
}

data class CreditPaymentContext(
    val occurredAt: Instant,
    val zoneId: ZoneId,
    val localDate: LocalDate,
    val amountExpression: String?,
    val note: String?,
    val createdAt: Instant,
)

data class RecordCreditPaymentRequest(
    val ids: CreditTransactionMutationIds,
    val context: CreditPaymentContext,
    val payment: SpecializedAccountAmountDraft,
    val credit: SpecializedAccountAmountDraft,
    val selection: CreditPaymentSelection,
    val sourceOccurrenceId: StableId? = null,
    val generationMode: AutoGenerationMode = AutoGenerationMode.FORMAL_TRANSACTION,
) {
    init {
        require(payment.accountId != credit.accountId)
        require(payment.baseMinor == credit.baseMinor)
    }
}

data class AssignCreditStatementRequest(
    val ids: CreditTransactionMutationIds,
    val expectedRevisionId: StableId,
    val statementId: StableId,
    val mode: StatementAssignmentMode,
    val changedAt: Instant,
) {
    init {
        require(mode != StatementAssignmentMode.AUTOMATIC)
    }
}

data class ReallocateCreditPaymentRequest(
    val ids: CreditTransactionMutationIds,
    val expectedRevisionId: StableId,
    val selection: CreditPaymentSelection,
    val changedAt: Instant,
)

data class CreditAutoPaymentProposal(
    val statementId: StableId,
    val accountId: StableId,
    val defaultPaymentAccountId: StableId?,
    val amountMinor: Long,
    val currency: CurrencyCode,
    val eligibility: AutoPaymentEligibility,
    /** Required wording: bookkeeping only; it is never evidence that a bank payment succeeded. */
    val bookkeepingDisclaimerRequired: Boolean = true,
)

interface CreditApplicationPort {
    suspend fun snapshot(bookId: StableId): DomainResult<CreditSnapshot>

    suspend fun saveProfile(request: SaveCreditProfileRequest): DomainResult<CommandReceipt>

    suspend fun createAccountWithProfile(request: CreateCreditAccountProfileRequest): DomainResult<CommandReceipt>

    suspend fun saveStatement(request: SaveCreditStatementRequest): DomainResult<CommandReceipt>

    suspend fun recordPayment(request: RecordCreditPaymentRequest): DomainResult<CommandReceipt>

    suspend fun assignStatement(request: AssignCreditStatementRequest): DomainResult<CommandReceipt>

    suspend fun reallocatePayment(request: ReallocateCreditPaymentRequest): DomainResult<CommandReceipt>

    suspend fun proposeAutoPayment(bookId: StableId, statementId: StableId, occurrenceId: StableId): DomainResult<CreditAutoPaymentProposal>
}

private const val CREDIT_MUTATION_ID_COUNT = 4
