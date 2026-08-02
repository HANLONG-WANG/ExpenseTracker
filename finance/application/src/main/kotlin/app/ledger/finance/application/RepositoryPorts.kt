package app.ledger.finance.application

import app.ledger.core.common.DomainResult
import app.ledger.core.money.CurrencyCode
import app.ledger.core.money.FxEvidence
import app.ledger.core.time.EffectiveTime
import app.ledger.finance.domain.AccountAvailabilityProjection
import app.ledger.finance.domain.AccountBalanceCheckpoint
import app.ledger.finance.domain.AccountBalanceProjection
import app.ledger.finance.domain.Attachment
import app.ledger.finance.domain.AttachmentId
import app.ledger.finance.domain.BlobId
import app.ledger.finance.domain.Book
import app.ledger.finance.domain.BudgetMonth
import app.ledger.finance.domain.BudgetMonthRevision
import app.ledger.finance.domain.Category
import app.ledger.finance.domain.CreditAccountProfile
import app.ledger.finance.domain.CreditStatement
import app.ledger.finance.domain.CreditStatementProjection
import app.ledger.finance.domain.EncryptedBlob
import app.ledger.finance.domain.Goal
import app.ledger.finance.domain.GoalBalanceProjection
import app.ledger.finance.domain.GoalId
import app.ledger.finance.domain.Hash256
import app.ledger.finance.domain.InstallmentPlan
import app.ledger.finance.domain.LoanContract
import app.ledger.finance.domain.LoanProgressProjection
import app.ledger.finance.domain.LoanSimulation
import app.ledger.finance.domain.LoanSimulationItem
import app.ledger.finance.domain.Merchant
import app.ledger.finance.domain.Participant
import app.ledger.finance.domain.PaymentCard
import app.ledger.finance.domain.Place
import app.ledger.finance.domain.Project
import app.ledger.finance.domain.PurgeTombstone
import app.ledger.finance.domain.RecurrenceCandidate
import app.ledger.finance.domain.RecurrenceOccurrence
import app.ledger.finance.domain.RecurrenceSeries
import app.ledger.finance.domain.RefundStatusProjection
import app.ledger.finance.domain.SettlementActivity
import app.ledger.finance.domain.SettlementPositionProjection
import app.ledger.finance.domain.TransactionBlueprint
import app.ledger.finance.domain.TransactionFilter
import app.ledger.finance.domain.TransactionId
import app.ledger.finance.domain.TransactionRevision
import app.ledger.finance.domain.UserAccount
import app.ledger.finance.domain.UserAccountId
import java.io.InputStream
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

data class KeysetPageRequest(
    val limit: Int,
    val beforeOccurredAt: Instant?,
    val beforeTransactionId: TransactionId?,
) {
    init {
        require(limit in 1..MAX_PAGE_SIZE)
        require((beforeOccurredAt == null) == (beforeTransactionId == null))
    }
}

data class TransactionPage(
    val revisions: List<TransactionRevision>,
    val next: KeysetPageRequest?,
)

interface BookRepository {
    suspend fun current(): DomainResult<Book>

    suspend fun purgeTombstone(transactionId: TransactionId): DomainResult<PurgeTombstone?>
}

interface AccountRepository {
    suspend fun account(id: UserAccountId): DomainResult<UserAccount?>

    suspend fun activeAccounts(): DomainResult<List<UserAccount>>

    suspend fun cards(accountId: UserAccountId): DomainResult<List<PaymentCard>>

    suspend fun balance(accountId: UserAccountId): DomainResult<AccountBalanceProjection?>

    suspend fun availability(accountId: UserAccountId): DomainResult<AccountAvailabilityProjection?>

    suspend fun checkpoints(accountId: UserAccountId): DomainResult<List<AccountBalanceCheckpoint>>
}

interface ClassificationRepository {
    suspend fun categories(): DomainResult<List<Category>>

    suspend fun merchants(): DomainResult<List<Merchant>>

    suspend fun places(): DomainResult<List<Place>>
}

interface TransactionQueryRepository {
    suspend fun currentRevision(transactionId: TransactionId): DomainResult<TransactionRevision?>

    suspend fun history(transactionId: TransactionId): DomainResult<List<TransactionRevision>>

    suspend fun page(filter: TransactionFilter, request: KeysetPageRequest): DomainResult<TransactionPage>

    suspend fun refundStatus(originalTransactionId: TransactionId): DomainResult<RefundStatusProjection?>
}

interface PlanningRepository {
    suspend fun budget(month: YearMonth): DomainResult<Pair<BudgetMonth, BudgetMonthRevision>?>

    suspend fun projects(): DomainResult<List<Project>>

    suspend fun goals(): DomainResult<List<Goal>>

    suspend fun goalBalance(goalId: GoalId): DomainResult<GoalBalanceProjection?>
}

interface LiabilityRepository {
    suspend fun creditProfile(accountId: UserAccountId): DomainResult<CreditAccountProfile?>

    suspend fun creditStatements(accountId: UserAccountId): DomainResult<List<CreditStatement>>

    suspend fun creditStatementProjection(statement: CreditStatement): DomainResult<CreditStatementProjection?>

    suspend fun installmentPlans(accountId: UserAccountId): DomainResult<List<InstallmentPlan>>

    suspend fun loanContracts(): DomainResult<List<LoanContract>>

    suspend fun loanProgress(contract: LoanContract): DomainResult<LoanProgressProjection?>

    suspend fun loanSimulation(simulation: LoanSimulation): DomainResult<List<LoanSimulationItem>>
}

interface SettlementRepository {
    suspend fun participants(): DomainResult<List<Participant>>

    suspend fun activities(): DomainResult<List<SettlementActivity>>

    suspend fun positions(activity: SettlementActivity): DomainResult<List<SettlementPositionProjection>>
}

interface AutomationRepository {
    suspend fun blueprints(): DomainResult<List<TransactionBlueprint>>

    suspend fun series(): DomainResult<List<RecurrenceSeries>>

    suspend fun dueOccurrences(through: Instant): DomainResult<List<RecurrenceOccurrence>>

    suspend fun candidates(): DomainResult<List<RecurrenceCandidate>>
}

interface AttachmentRepository {
    suspend fun attachment(id: AttachmentId): DomainResult<Attachment?>

    suspend fun blob(id: BlobId): DomainResult<EncryptedBlob?>

    suspend fun attachments(transactionId: TransactionId): DomainResult<List<Attachment>>
}

data class FxRateRequest(
    val sourceCurrency: CurrencyCode,
    val targetCurrency: CurrencyCode,
    val effectiveAt: EffectiveTime,
    val allowCached: Boolean,
)

fun interface FxEvidencePort {
    suspend fun evidence(request: FxRateRequest): DomainResult<FxEvidence?>
}

data class CapturedLocation(
    val latitudeE7: Int,
    val longitudeE7: Int,
    val accuracyMillimeters: Int?,
    val capturedAt: Instant,
    val provider: CapturedLocationProvider,
) {
    init {
        require(latitudeE7 in MIN_LATITUDE_E7..MAX_LATITUDE_E7)
        require(longitudeE7 in MIN_LONGITUDE_E7..MAX_LONGITUDE_E7)
        require(accuracyMillimeters == null || accuracyMillimeters >= 0)
    }

    private companion object {
        const val MIN_LATITUDE_E7 = -900_000_000
        const val MAX_LATITUDE_E7 = 900_000_000
        const val MIN_LONGITUDE_E7 = -1_800_000_000
        const val MAX_LONGITUDE_E7 = 1_800_000_000
    }
}

enum class CapturedLocationProvider {
    FUSED,
    GPS,
    NETWORK,
}

fun interface ForegroundLocationPort {
    suspend fun capture(deadline: Instant): DomainResult<CapturedLocation?>
}

data class AttachmentImportRequest(
    val displayName: String,
    val mimeType: String?,
    val extension: String?,
    val declaredSize: Long?,
    val content: AttachmentContentSource,
) {
    init {
        require(declaredSize == null || declaredSize >= 0L)
    }
}

/** Reopenable plaintext source consumed only by the encrypted attachment infrastructure. */
fun interface AttachmentContentSource {
    fun openStream(): InputStream
}

data class AttachmentImportReceipt(
    val attachmentId: AttachmentId,
    val blobId: BlobId,
    val plaintextSize: Long,
    val plaintextHash: Hash256,
)

interface AttachmentObjectPort {
    suspend fun import(request: AttachmentImportRequest): DomainResult<AttachmentImportReceipt>

    suspend fun removeUnreferenced(blobId: BlobId): DomainResult<Unit>
}

data class MerchantLocationSuggestionQuery(
    val merchantName: String?,
    val latitudeE7: Int,
    val longitudeE7: Int,
    val radiusMeters: Int,
    val asOfDate: LocalDate,
)

interface LocalSuggestionPort {
    suspend fun merchantSuggestions(query: MerchantLocationSuggestionQuery): DomainResult<List<Merchant>>

    suspend fun placeSuggestions(query: MerchantLocationSuggestionQuery): DomainResult<List<Place>>
}

fun interface CanonicalContentHashPort {
    fun hash(bytes: ByteArray): Hash256
}

private const val MAX_PAGE_SIZE = 200
