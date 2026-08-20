@file:Suppress("TooManyFunctions")

package app.ledger.finance.application

import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.money.CurrencyCode
import app.ledger.finance.domain.CommandReceipt
import app.ledger.finance.domain.DependencyResolution
import app.ledger.finance.domain.RevisionAction
import app.ledger.finance.domain.StatisticalNature
import app.ledger.finance.domain.TransactionDependencyType
import app.ledger.finance.domain.TransactionFilter
import app.ledger.finance.domain.TransactionKind
import app.ledger.finance.domain.TransactionLifecycleState
import app.ledger.finance.domain.TransactionSource
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate

data class JournalPageRequest(
    val bookId: StableId,
    val filter: TransactionFilter = TransactionFilter(),
    val limit: Int = 40,
    val cursor: CurrentTransactionCursor? = null,
    val runningBalanceAccountId: StableId? = null,
)

data class JournalTransactionView(
    val transactionId: StableId,
    val revisionId: StableId,
    val kind: TransactionKind,
    val state: TransactionLifecycleState,
    val occurredAt: Instant,
    val localDate: LocalDate,
    val categoryOrType: String,
    val summary: String,
    val accountAndCard: String,
    val amountMinor: Long,
    val currency: CurrencyCode,
    val secondaryAmountMinor: Long?,
    val secondaryCurrency: CurrencyCode?,
    val badges: List<String>,
    val runningBalanceMinor: Long?,
    val source: TransactionSource,
)

data class JournalPage(
    val items: List<JournalTransactionView>,
    val nextCursor: CurrentTransactionCursor?,
)

data class JournalDetailView(
    val transaction: JournalTransactionView,
    val createdAt: Instant,
    val modifiedAt: Instant,
    val zoneId: String,
    val amountExpression: String?,
    val fullNote: String?,
    val merchantName: String?,
    val projectName: String?,
    val locationName: String?,
    val attachmentNames: List<String>,
    val budgetSummary: String?,
    val statisticalNature: String?,
    val fxEvidence: List<JournalFxEvidenceView>,
    val relationshipSummaries: List<String>,
    val accountEffects: List<String>,
    val sourceDescription: String,
    val purgeAfter: Instant?,
    val dependencyCount: Int,
)

data class JournalFxEvidenceView(
    val sourceCurrency: CurrencyCode,
    val targetCurrency: CurrencyCode,
    val rate: String,
    val provider: String,
    val quotedAt: Instant?,
    val manual: Boolean,
    val staleAtUse: Boolean,
)

data class JournalRevisionView(
    val revisionId: StableId,
    val revisionNumber: Int,
    val action: RevisionAction,
    val resultingState: TransactionLifecycleState,
    val createdAt: Instant,
    val occurredAt: Instant,
    val category: String?,
    val account: String?,
    val amountMinor: Long?,
    val currency: CurrencyCode?,
    val changedFields: List<String>,
)

data class JournalRevisionComparison(
    val left: JournalRevisionView,
    val right: JournalRevisionView,
    val changedFields: List<String>,
    val unchangedFields: List<String>,
)

data class JournalDependencyView(
    val parentTransactionId: StableId,
    val childTransactionId: StableId,
    val type: TransactionDependencyType,
    val childState: TransactionLifecycleState,
)

enum class PurgeIneligibilityReason {
    NOT_TRASHED,
    RETENTION_NOT_ELAPSED,
    ACCOUNT_NET_NON_ZERO,
    BASE_NET_NON_ZERO,
    EFFECT_NET_NON_ZERO,
    DEPENDENCIES_OPEN,
    OPERATION_REFERENCE,
    ATTACHMENTS_READ_BY_BACKUP,
}

data class JournalPurgeAssessment(
    val transactionId: StableId,
    val evaluatedAt: Instant,
    val purgeAfter: Instant?,
    val reasons: Set<PurgeIneligibilityReason>,
) {
    val financiallyEligible: Boolean = reasons.isEmpty()
    val canPurgeNow: Boolean = reasons.isEmpty()
}

data class JournalMutationIds(
    val bookId: StableId,
    val commandId: StableId,
    val transactionId: StableId,
    val revisionId: StableId,
    val commitId: StableId,
    val deviceInstanceId: StableId,
    val factIds: List<StableId>,
    val fxRateSnapshotIds: List<StableId>,
)

sealed interface JournalMutationRequest {
    val ids: JournalMutationIds
    val expectedRevisionId: StableId
    val createdAt: Instant

    data class MoveToTrash(
        override val ids: JournalMutationIds,
        override val expectedRevisionId: StableId,
        override val createdAt: Instant,
        val purgeAfter: Instant,
        val dependencyResolutions: List<DependencyResolution> = emptyList(),
    ) : JournalMutationRequest

    data class RestoreFromTrash(
        override val ids: JournalMutationIds,
        override val expectedRevisionId: StableId,
        override val createdAt: Instant,
    ) : JournalMutationRequest

    data class RestoreHistorical(
        override val ids: JournalMutationIds,
        override val expectedRevisionId: StableId,
        override val createdAt: Instant,
        val sourceRevisionId: StableId,
        val dependencyResolutions: List<DependencyResolution> = emptyList(),
    ) : JournalMutationRequest
}

interface JournalApplicationPort {
    suspend fun page(request: JournalPageRequest): DomainResult<JournalPage>
    suspend fun detail(bookId: StableId, transactionId: StableId): DomainResult<JournalDetailView?>
    suspend fun history(bookId: StableId, transactionId: StableId): DomainResult<List<JournalRevisionView>>
    suspend fun compare(
        bookId: StableId,
        transactionId: StableId,
        leftRevisionId: StableId,
        rightRevisionId: StableId,
    ): DomainResult<JournalRevisionComparison>
    suspend fun dependencies(bookId: StableId, transactionId: StableId): DomainResult<List<JournalDependencyView>>
    suspend fun assessPurge(bookId: StableId, transactionId: StableId, now: Instant): DomainResult<JournalPurgeAssessment>
    suspend fun mutate(request: JournalMutationRequest): DomainResult<CommandReceipt>
    suspend fun bulkEdit(request: JournalBulkEditRequest): DomainResult<CommandReceipt>
    suspend fun bulkEditOptions(bookId: StableId): DomainResult<JournalBulkEditOptions>
    suspend fun savedFilters(bookId: StableId): DomainResult<List<JournalSavedFilter>>
    suspend fun mutateSavedFilter(bookId: StableId, command: JournalSavedFilterCommand): DomainResult<List<JournalSavedFilter>>
}

data class JournalBulkOption(val id: StableId, val label: String, val parentId: StableId? = null)

data class JournalBulkEditOptions(
    val accounts: List<JournalBulkOption>,
    val cards: List<JournalBulkOption>,
    val categories: List<JournalBulkOption>,
    val merchants: List<JournalBulkOption>,
    val projects: List<JournalBulkOption>,
    val settlementActivities: List<JournalBulkOption> = emptyList(),
    val participants: List<JournalBulkOption> = emptyList(),
    val currencies: List<CurrencyCode> = emptyList(),
)

enum class JournalSelectionMode { EXPLICIT, ALL_MATCHING }

data class JournalSelectionSpec(
    val queryFingerprint: String,
    val mode: JournalSelectionMode,
    val includedIds: Set<StableId> = emptySet(),
    val excludedIds: Set<StableId> = emptySet(),
) {
    init {
        require(queryFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(mode != JournalSelectionMode.ALL_MATCHING || includedIds.isEmpty())
        require(mode != JournalSelectionMode.EXPLICIT || excludedIds.isEmpty())
    }

    fun contains(id: StableId): Boolean = if (mode == JournalSelectionMode.ALL_MATCHING) id !in excludedIds else id in includedIds
    fun queryChanged(currentFingerprint: String): Boolean = currentFingerprint != queryFingerprint

    companion object {
        fun fingerprint(filter: TransactionFilter): String {
            val canonical = listOf(
                filter.occurredFrom, filter.occurredThrough, filter.createdFrom, filter.createdThrough,
                filter.modifiedFrom, filter.modifiedThrough, filter.kinds.sortedBy(Enum<*>::name),
                filter.accountIds.sortedBy { it.value }, filter.cardIds.sortedBy { it.value }, filter.categoryIds.sortedBy { it.value },
                filter.merchantIds.sortedBy { it.value }, filter.projectIds.sortedBy { it.value }, filter.settlementActivityIds.sortedBy { it.value },
                filter.participantIds.sortedBy { it.value }, filter.currencies.sortedBy { it.value }, filter.statisticalNatures.sortedBy(Enum<*>::name),
                filter.amountRange, filter.geoRadius, filter.hasAttachment, filter.isRefund, filter.hasInstallment,
                filter.includedInBudget, filter.generatedByRecurrence, filter.sources.sortedBy(Enum<*>::name),
                filter.lifecycleStates.sortedBy(Enum<*>::name), filter.searchText?.trim().orEmpty(),
            ).joinToString("\u001f")
            return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
}

enum class JournalBulkEditableField {
    CATEGORY,
    ACCOUNT_AND_CARD,
    MERCHANT,
    PROJECT,
    OCCURRED_TIME,
    NOTE,
    BUDGET_ATTRIBUTE,
    STATISTICAL_NATURE,
}

val forbiddenJournalBulkFields: Set<String> = setOf("amount", "direction", "refundRelation", "settlementShare")

sealed interface JournalFieldUpdate<out T> {
    data object Unchanged : JournalFieldUpdate<Nothing>
    data object Clear : JournalFieldUpdate<Nothing>
    data class Set<T>(val value: T) : JournalFieldUpdate<T>
}

data class JournalAccountCardUpdate(val accountId: StableId, val cardId: StableId?)

data class JournalBulkEditPatch(
    val categoryId: JournalFieldUpdate<StableId> = JournalFieldUpdate.Unchanged,
    val accountAndCard: JournalFieldUpdate<JournalAccountCardUpdate> = JournalFieldUpdate.Unchanged,
    val merchantId: JournalFieldUpdate<StableId> = JournalFieldUpdate.Unchanged,
    val projectId: JournalFieldUpdate<StableId> = JournalFieldUpdate.Unchanged,
    val occurredAt: JournalFieldUpdate<Instant> = JournalFieldUpdate.Unchanged,
    val note: JournalFieldUpdate<String> = JournalFieldUpdate.Unchanged,
    val includedInBudget: JournalFieldUpdate<Boolean> = JournalFieldUpdate.Unchanged,
    val statisticalNature: JournalFieldUpdate<StatisticalNature> = JournalFieldUpdate.Unchanged,
) {
    init {
        require(listOf(categoryId, accountAndCard, merchantId, projectId, occurredAt, note, includedInBudget, statisticalNature).any { it !is JournalFieldUpdate.Unchanged })
        require(note !is JournalFieldUpdate.Set || note.value.length <= MAX_JOURNAL_NOTE_LENGTH)
        require(accountAndCard !is JournalFieldUpdate.Clear)
        require(occurredAt !is JournalFieldUpdate.Clear)
        require(includedInBudget !is JournalFieldUpdate.Clear)
        require(statisticalNature !is JournalFieldUpdate.Clear)
    }
}

private const val MAX_JOURNAL_NOTE_LENGTH = 2_000

data class JournalBulkEditRequest(
    val bookId: StableId,
    val commandId: StableId,
    val commitId: StableId,
    val deviceInstanceId: StableId,
    val selection: JournalSelectionSpec,
    val filter: TransactionFilter,
    val patch: JournalBulkEditPatch,
    val changedAt: Instant,
) {
    init {
        require(!selection.queryChanged(JournalSelectionSpec.fingerprint(filter)))
        require(selection.mode != JournalSelectionMode.EXPLICIT || selection.includedIds.isNotEmpty())
    }
}

data class JournalSavedFilter(
    val id: StableId,
    val name: String,
    val filter: TransactionFilter,
    val naturalLanguageSummary: String,
    val isDefault: Boolean,
    val sortOrder: Int,
)

sealed interface JournalSavedFilterCommand {
    data class Save(val id: StableId, val name: String, val filter: TransactionFilter, val naturalLanguageSummary: String) : JournalSavedFilterCommand
    data class Copy(val sourceId: StableId, val newId: StableId, val newName: String) : JournalSavedFilterCommand
    data class SetDefault(val id: StableId) : JournalSavedFilterCommand
    data class Delete(val id: StableId) : JournalSavedFilterCommand
    data class Reorder(val orderedIds: List<StableId>) : JournalSavedFilterCommand
}
