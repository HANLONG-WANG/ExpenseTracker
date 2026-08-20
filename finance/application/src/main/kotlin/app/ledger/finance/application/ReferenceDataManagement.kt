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

public interface ReferenceDataManagementPort {
    public suspend fun snapshot(bookId: StableId): DomainResult<ReferenceDataSnapshot>

    /** Entry flows do not need history counts, checkpoints, goals, or account transaction rows. */
    public suspend fun entrySnapshot(bookId: StableId): DomainResult<ReferenceDataSnapshot> = snapshot(bookId)
    public suspend fun mutate(command: ReferenceMutationCommand): DomainResult<Unit>
}
