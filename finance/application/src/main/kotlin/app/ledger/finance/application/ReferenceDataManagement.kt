@file:Suppress("LongParameterList", "MaxLineLength", "MagicNumber")

package app.ledger.finance.application

import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.money.CurrencyCode
import app.ledger.finance.domain.CardType
import app.ledger.finance.domain.CategoryDirection
import app.ledger.finance.domain.CategoryRemovalStrategy
import app.ledger.finance.domain.CategoryStatus
import app.ledger.finance.domain.EntityStatus
import app.ledger.finance.domain.GoalStatus
import app.ledger.finance.domain.ProjectStatus
import app.ledger.finance.domain.StatisticalNature
import app.ledger.finance.domain.UserAccountType
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

public data class ReferenceMutationIds(
    val bookId: StableId,
    val expectedLocalRevision: Long,
    val commitId: StableId,
    val entityRevisionIds: List<StableId>,
    val deviceInstanceId: StableId,
    val changedAt: Instant,
) {
    init {
        require(expectedLocalRevision > 0L)
        require(entityRevisionIds.isNotEmpty())
        require((listOf(bookId, commitId, deviceInstanceId) + entityRevisionIds).toSet().size == entityRevisionIds.size + 3)
    }
}

public data class AccountDraft(
    val accountId: StableId,
    val ledgerAccountId: StableId,
    val expectedRowVersion: Long?,
    val type: UserAccountType,
    val name: String,
    val currency: CurrencyCode,
    val institutionName: String?,
    val branchName: String?,
    val accountNumber: String?,
    val openedOn: LocalDate?,
    val iconKey: String,
    val colorArgb: Int,
    val sortOrder: Int,
)

public data class CardDraft(
    val cardId: StableId,
    val expectedRowVersion: Long?,
    val accountId: StableId,
    val type: CardType,
    val displayName: String,
    val lastFour: String?,
    val replacementOfId: StableId?,
    val iconKey: String,
    val colorArgb: Int,
    val sortOrder: Int,
)

public data class CategoryDraft(
    val categoryId: StableId,
    val expectedRowVersion: Long?,
    val direction: CategoryDirection,
    val parentId: StableId?,
    val name: String,
    val normalizedName: String,
    val iconKey: String,
    val colorArgb: Int,
    val sortOrder: Int,
    val statisticalNature: StatisticalNature,
    val defaultAccountId: StableId?,
    val defaultCardId: StableId?,
    val defaultMerchantId: StableId?,
)

public data class MerchantDraft(
    val merchantId: StableId,
    val expectedRowVersion: Long?,
    val name: String,
    val normalizedName: String,
    val aliases: Set<String>,
)

public data class PlaceDraft(
    val placeId: StableId,
    val expectedRowVersion: Long?,
    val name: String,
    val latitudeE7: Int,
    val longitudeE7: Int,
    val merchantId: StableId?,
)

public data class ProjectDraft(
    val projectId: StableId,
    val expectedRowVersion: Long?,
    val name: String,
    val description: String?,
    val startDate: LocalDate,
    val endDate: LocalDate?,
    val budgetBaseMinor: Long,
    val includedInMonthlyBudget: Boolean,
    val goalId: StableId?,
    val status: ProjectStatus,
)

public data class GoalDraft(
    val goalId: StableId,
    val expectedRowVersion: Long?,
    val accountId: StableId,
    val name: String,
    val targetAmountMinor: Long,
    val dueDate: LocalDate?,
    val suggestedMonthlyAmountMinor: Long?,
    val status: GoalStatus,
)

public sealed interface ReferenceMutation {
    public data class SaveAccount(val draft: AccountDraft) : ReferenceMutation
    public data class ArchiveAccount(val accountId: StableId, val expectedRowVersion: Long) : ReferenceMutation
    public data class DeleteEmptyAccount(val accountId: StableId, val expectedRowVersion: Long) : ReferenceMutation
    public data class SaveCard(val draft: CardDraft) : ReferenceMutation
    public data class ArchiveCard(val cardId: StableId, val expectedRowVersion: Long) : ReferenceMutation
    public data class ReplaceCard(val oldCardId: StableId, val oldRowVersion: Long, val replacement: CardDraft) : ReferenceMutation
    public data class SaveCategory(val draft: CategoryDraft) : ReferenceMutation
    public data class ReorderCategories(val direction: CategoryDirection, val orderedIds: List<StableId>) : ReferenceMutation
    public data class RemoveCategory(
        val categoryId: StableId,
        val expectedRowVersion: Long,
        val strategy: CategoryRemovalStrategy,
        val targetCategoryId: StableId?,
    ) : ReferenceMutation
    public data class SaveMerchant(val draft: MerchantDraft) : ReferenceMutation
    public data class MergeMerchant(val sourceId: StableId, val targetId: StableId) : ReferenceMutation
    public data class SavePlace(val draft: PlaceDraft) : ReferenceMutation
    public data class SaveLocation(val draft: OrdinaryLocationDraft) : ReferenceMutation
    public data class MergePlace(val sourceId: StableId, val targetId: StableId) : ReferenceMutation
    public data class SplitPlace(
        val sourceId: StableId,
        val newPlace: PlaceDraft,
        val locationRecordIds: List<StableId>,
        val replacementLocationRecordIds: List<StableId>,
    ) : ReferenceMutation {
        init {
            require(locationRecordIds.isNotEmpty())
            require(locationRecordIds.size == replacementLocationRecordIds.size)
            require((locationRecordIds + replacementLocationRecordIds).toSet().size == locationRecordIds.size * 2)
        }
    }
    public data class SaveCheckpoint(
        val checkpointId: StableId,
        val accountId: StableId,
        val asOf: Instant,
        val asOfLocalDate: LocalDate,
        val observedMinor: Long,
        val note: String?,
    ) : ReferenceMutation
    public data class SaveProject(val draft: ProjectDraft) : ReferenceMutation
    public data class ChangeProjectStatus(
        val projectId: StableId,
        val expectedRowVersion: Long,
        val status: ProjectStatus,
    ) : ReferenceMutation
    public data class SaveGoal(val draft: GoalDraft) : ReferenceMutation
}

public data class ReferenceMutationCommand(val ids: ReferenceMutationIds, val mutation: ReferenceMutation)

public data class AccountReferenceView(
    val id: StableId,
    val type: UserAccountType,
    val name: String,
    val currency: CurrencyCode,
    val status: EntityStatus,
    val institutionName: String?,
    val branchName: String?,
    val openedOn: LocalDate?,
    val iconKey: String,
    val colorArgb: Int,
    val sortOrder: Int,
    val rowVersion: Long,
    val balanceMinor: Long,
    val currentBaseValueMinor: Long?,
    val valuationQuotedAt: Instant?,
    val hasFinancialPostings: Boolean,
    val cardCount: Long,
    val currentValuationRate: BigDecimal? = null,
    val accountNumber: String? = null,
)

public data class CardReferenceView(
    val id: StableId,
    val accountId: StableId,
    val type: CardType,
    val displayName: String,
    val lastFour: String?,
    val status: EntityStatus,
    val replacementOfId: StableId?,
    val iconKey: String,
    val colorArgb: Int,
    val sortOrder: Int,
    val rowVersion: Long,
    val historicalTransactionCount: Long,
)

public data class CategoryReferenceView(
    val id: StableId,
    val direction: CategoryDirection,
    val parentId: StableId?,
    val depth: Int,
    val name: String,
    val iconKey: String,
    val colorArgb: Int,
    val sortOrder: Int,
    val status: CategoryStatus,
    val statisticalNature: StatisticalNature,
    val defaultAccountId: StableId?,
    val defaultCardId: StableId?,
    val defaultMerchantId: StableId?,
    val rowVersion: Long,
    val historicalTransactionCount: Long,
    val childCount: Long,
)

public data class MerchantReferenceView(
    val id: StableId,
    val name: String,
    val aliases: List<String>,
    val status: EntityStatus,
    val mergedIntoId: StableId?,
    val rowVersion: Long,
    val currentTransactionCount: Long,
    val placeCount: Long,
)

public data class PlaceReferenceView(
    val id: StableId,
    val name: String,
    val latitudeE7: Int,
    val longitudeE7: Int,
    val merchantId: StableId?,
    val status: EntityStatus,
    val mergedIntoId: StableId?,
    val rowVersion: Long,
    val locationRecordCount: Long,
)

public data class LocationReferenceView(
    val id: StableId,
    val latitudeE7: Int,
    val longitudeE7: Int,
    val capturedAt: Instant,
    val placeId: StableId?,
    val currentTransactionCount: Long,
)

public data class CheckpointReferenceView(
    val id: StableId,
    val accountId: StableId,
    val asOf: Instant,
    val asOfLocalDate: LocalDate,
    val observedMinor: Long,
    val calculatedMinor: Long,
    val differenceMinor: Long,
    val adjustmentTransactionId: StableId?,
)

public data class AccountTransactionReferenceView(
    val transactionId: StableId,
    val revisionId: StableId,
    val accountId: StableId,
    val localDate: LocalDate,
    val occurredAt: Instant,
    val kind: app.ledger.finance.domain.TransactionKind,
    val impactMinor: Long,
    val runningBalanceMinor: Long,
    val currency: CurrencyCode,
)

public data class AccountGoalReferenceView(
    val id: StableId,
    val accountId: StableId,
    val name: String,
    val balanceMinor: Long,
    val targetMinor: Long,
    val currency: CurrencyCode,
)

public data class ReferenceDataSnapshot(
    val bookId: StableId,
    val baseCurrency: CurrencyCode,
    val localRevision: Long,
    val accounts: List<AccountReferenceView>,
    val cards: List<CardReferenceView>,
    val categories: List<CategoryReferenceView>,
    val merchants: List<MerchantReferenceView>,
    val places: List<PlaceReferenceView>,
    val locations: List<LocationReferenceView>,
    val checkpoints: List<CheckpointReferenceView>,
    val accountTransactions: List<AccountTransactionReferenceView>,
    val accountGoals: List<AccountGoalReferenceView>,
    val coreNetFinancialAssetsMinor: Long?,
    val adjustedNetFinancialPositionMinor: Long?,
    val valuationMissing: Boolean,
    val valuationRevision: Long = 0L,
)

/** Minimum stable identity and monetary context required to render an empty entry editor. */
public data class EntryCoreReferences(
    val bookId: StableId,
    val baseCurrency: CurrencyCode,
    val localRevision: Long,
    val valuationRevision: Long,
    val accounts: List<AccountReferenceView>,
    val cards: List<CardReferenceView>,
    val categories: List<CategoryReferenceView>,
)

/** Compatibility envelope for feature contracts while their optional pickers move to paged APIs. */
public fun EntryCoreReferences.toBoundedReferenceSnapshot(
    merchants: List<MerchantReferenceView> = emptyList(),
    places: List<PlaceReferenceView> = emptyList(),
    locations: List<LocationReferenceView> = emptyList(),
): ReferenceDataSnapshot = ReferenceDataSnapshot(
    bookId = bookId,
    baseCurrency = baseCurrency,
    localRevision = localRevision,
    valuationRevision = valuationRevision,
    accounts = accounts,
    cards = cards,
    categories = categories,
    merchants = merchants,
    places = places,
    locations = locations,
    checkpoints = emptyList(),
    accountTransactions = emptyList(),
    accountGoals = emptyList(),
    coreNetFinancialAssetsMinor = null,
    adjustedNetFinancialPositionMinor = null,
    valuationMissing = accounts.any { it.currency != baseCurrency && it.currentBaseValueMinor == null },
)

public enum class ReferenceSuggestionKind { MERCHANT, PLACE }

public data class ReferenceSuggestion(
    val id: StableId,
    val kind: ReferenceSuggestionKind,
    val label: String,
)

public data class ReferenceSuggestionPage(
    val items: List<ReferenceSuggestion>,
    val nextOffset: Int?,
)

public data class RecentEntryDefaults(
    val merchantIds: List<StableId>,
    val placeIds: List<StableId>,
    val projectIds: List<StableId>,
    val templateIds: List<StableId>,
)

public data class AccountSummarySnapshot(
    val bookId: StableId,
    val baseCurrency: CurrencyCode,
    val localRevision: Long,
    val valuationRevision: Long,
    val accounts: List<AccountReferenceView>,
    val cards: List<CardReferenceView>,
    val coreNetFinancialAssetsMinor: Long?,
    val adjustedNetFinancialPositionMinor: Long?,
    val valuationMissing: Boolean,
)

public data class OrderedReferenceCursor(val sortOrder: Int, val id: StableId)

public data class NamedReferenceCursor(val name: String, val id: StableId)

public data class LocationReferenceCursor(val capturedAt: Instant, val id: StableId)

public data class ReferenceManagementPage<T, C>(val items: List<T>, val nextCursor: C?)

public data class AccountHistoryCursor(val occurredAt: Instant, val transactionId: StableId)

public data class AccountHistoryPage(
    val transactions: List<AccountTransactionReferenceView>,
    val checkpoints: List<CheckpointReferenceView>,
    val goals: List<AccountGoalReferenceView>,
    val nextCursor: AccountHistoryCursor?,
)

@Suppress("TooManyFunctions")
public interface ReferenceDataManagementPort {
    public suspend fun snapshot(bookId: StableId): DomainResult<ReferenceDataSnapshot>

    /** Entry flows do not need history counts, checkpoints, goals, or account transaction rows. */
    public suspend fun entrySnapshot(bookId: StableId): DomainResult<ReferenceDataSnapshot> = snapshot(bookId)

    public suspend fun entryCoreReferences(bookId: StableId): DomainResult<EntryCoreReferences> = entrySnapshot(bookId).mapSuccess { value ->
        EntryCoreReferences(
            value.bookId,
            value.baseCurrency,
            value.localRevision,
            value.valuationRevision,
            value.accounts.filter { it.status == EntityStatus.ACTIVE },
            value.cards.filter { it.status == EntityStatus.ACTIVE },
            value.categories.filter { it.status == CategoryStatus.ACTIVE },
        )
    }

    public suspend fun suggestions(
        bookId: StableId,
        query: String,
        offset: Int = 0,
        limit: Int = 20,
    ): DomainResult<ReferenceSuggestionPage> {
        require(offset >= 0 && limit in 1..50)
        val normalized = query.trim().lowercase()
        return entrySnapshot(bookId).mapSuccess { value ->
            val matches = buildList {
                value.merchants.filter { it.status == EntityStatus.ACTIVE && it.name.lowercase().contains(normalized) }
                    .forEach { add(ReferenceSuggestion(it.id, ReferenceSuggestionKind.MERCHANT, it.name)) }
                value.places.filter { it.status == EntityStatus.ACTIVE && it.name.lowercase().contains(normalized) }
                    .forEach { add(ReferenceSuggestion(it.id, ReferenceSuggestionKind.PLACE, it.name)) }
            }.sortedWith(compareBy<ReferenceSuggestion>({ it.label }, { it.id.toString() }))
            val page = matches.drop(offset).take(limit)
            ReferenceSuggestionPage(page, (offset + page.size).takeIf { it < matches.size })
        }
    }

    public suspend fun recentEntryDefaults(bookId: StableId): DomainResult<RecentEntryDefaults> = DomainResult.Success(RecentEntryDefaults(emptyList(), emptyList(), emptyList(), emptyList()))

    public suspend fun accountSummary(bookId: StableId): DomainResult<AccountSummarySnapshot> = entrySnapshot(bookId).mapSuccess { value ->
        AccountSummarySnapshot(
            value.bookId,
            value.baseCurrency,
            value.localRevision,
            value.valuationRevision,
            value.accounts.filter { it.status == EntityStatus.ACTIVE },
            value.cards.filter { it.status == EntityStatus.ACTIVE },
            value.coreNetFinancialAssetsMinor,
            value.adjustedNetFinancialPositionMinor,
            value.valuationMissing,
        )
    }

    public suspend fun accountHistory(
        bookId: StableId,
        accountId: StableId,
        cursor: AccountHistoryCursor? = null,
        limit: Int = 40,
    ): DomainResult<AccountHistoryPage> {
        require(limit in 1..50)
        return snapshot(bookId).mapSuccess { value ->
            val matching = value.accountTransactions
                .filter { it.accountId == accountId }
                .filter { item ->
                    cursor == null || item.occurredAt < cursor.occurredAt ||
                        item.occurredAt == cursor.occurredAt && item.transactionId.toString() > cursor.transactionId.toString()
                }
                .take(limit + 1)
            val page = matching.take(limit)
            AccountHistoryPage(
                page,
                value.checkpoints.filter { it.accountId == accountId }.take(limit),
                value.accountGoals.filter { it.accountId == accountId }.take(limit),
                page.lastOrNull()?.takeIf { matching.size > limit }?.let { AccountHistoryCursor(it.occurredAt, it.transactionId) },
            )
        }
    }

    public suspend fun categoryPage(
        bookId: StableId,
        direction: CategoryDirection,
        cursor: OrderedReferenceCursor? = null,
        limit: Int = 40,
    ): DomainResult<ReferenceManagementPage<CategoryReferenceView, OrderedReferenceCursor>> {
        require(limit in 1..50)
        return snapshot(bookId).mapSuccess { value ->
            val matching = value.categories
                .filter { it.direction == direction }
                .sortedWith(compareBy(CategoryReferenceView::sortOrder, { it.id.toString() }))
                .filter { item ->
                    cursor == null || item.sortOrder > cursor.sortOrder ||
                        item.sortOrder == cursor.sortOrder && item.id.toString() > cursor.id.toString()
                }
                .take(limit + 1)
            val page = matching.take(limit)
            ReferenceManagementPage(
                page,
                page.lastOrNull()?.takeIf { matching.size > limit }?.let { OrderedReferenceCursor(it.sortOrder, it.id) },
            )
        }
    }

    public suspend fun merchantPage(
        bookId: StableId,
        cursor: NamedReferenceCursor? = null,
        limit: Int = 40,
    ): DomainResult<ReferenceManagementPage<MerchantReferenceView, NamedReferenceCursor>> = namedPage(bookId, cursor, limit, ReferenceDataSnapshot::merchants, MerchantReferenceView::name, MerchantReferenceView::id)

    public suspend fun placePage(
        bookId: StableId,
        cursor: NamedReferenceCursor? = null,
        limit: Int = 40,
    ): DomainResult<ReferenceManagementPage<PlaceReferenceView, NamedReferenceCursor>> = namedPage(bookId, cursor, limit, ReferenceDataSnapshot::places, PlaceReferenceView::name, PlaceReferenceView::id)

    public suspend fun locationPage(
        bookId: StableId,
        cursor: LocationReferenceCursor? = null,
        limit: Int = 40,
    ): DomainResult<ReferenceManagementPage<LocationReferenceView, LocationReferenceCursor>> {
        require(limit in 1..50)
        return snapshot(bookId).mapSuccess { value ->
            val matching = value.locations
                .sortedWith(compareByDescending(LocationReferenceView::capturedAt).thenBy { it.id.toString() })
                .filter { item ->
                    cursor == null || item.capturedAt < cursor.capturedAt ||
                        item.capturedAt == cursor.capturedAt && item.id.toString() > cursor.id.toString()
                }
                .take(limit + 1)
            val page = matching.take(limit)
            ReferenceManagementPage(
                page,
                page.lastOrNull()?.takeIf { matching.size > limit }?.let { LocationReferenceCursor(it.capturedAt, it.id) },
            )
        }
    }

    public suspend fun mutate(command: ReferenceMutationCommand): DomainResult<Unit>

    private suspend fun <T> namedPage(
        bookId: StableId,
        cursor: NamedReferenceCursor?,
        limit: Int,
        items: (ReferenceDataSnapshot) -> List<T>,
        name: (T) -> String,
        id: (T) -> StableId,
    ): DomainResult<ReferenceManagementPage<T, NamedReferenceCursor>> {
        require(limit in 1..50)
        return snapshot(bookId).mapSuccess { value ->
            val matching = items(value)
                .sortedWith(compareBy(name, { id(it).toString() }))
                .filter { item ->
                    cursor == null || name(item) > cursor.name ||
                        name(item) == cursor.name && id(item).toString() > cursor.id.toString()
                }
                .take(limit + 1)
            val page = matching.take(limit)
            ReferenceManagementPage(
                page,
                page.lastOrNull()?.takeIf { matching.size > limit }?.let { NamedReferenceCursor(name(it), id(it)) },
            )
        }
    }
}

private inline fun <T, R> DomainResult<T>.mapSuccess(transform: (T) -> R): DomainResult<R> = when (this) {
    is DomainResult.Success -> DomainResult.Success(transform(value))
    is DomainResult.Failure -> this
}
