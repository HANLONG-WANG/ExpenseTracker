@file:Suppress("MaxLineLength")

package app.ledger.feature.settings

import app.ledger.core.common.StableId
import app.ledger.finance.application.PlaceReferenceView
import app.ledger.finance.application.ReferenceDataSnapshot
import app.ledger.finance.domain.CategoryDirection
import app.ledger.finance.domain.CategoryRemovalStrategy
import app.ledger.finance.domain.StatisticalNature

public enum class ManagementRequiredState(public val screenId: String, public val contractName: String) {
    MGT_001_CONTENT("MGT-001", "content"),
    CAT_001_CONTENT("CAT-001", "content"),
    CAT_001_EMPTY("CAT-001", "empty"),
    CAT_001_SEARCHING("CAT-001", "searching"),
    CAT_002_CREATE("CAT-002", "create"),
    CAT_002_EDIT("CAT-002", "edit"),
    CAT_002_PARENT_LOCKED("CAT-002", "parentLocked"),
    CAT_002_CONTRAST_WARNING("CAT-002", "contrastWarning"),
    CAT_002_VALIDATION_ERROR("CAT-002", "validationError"),
    CAT_003_EDITING("CAT-003", "editing"),
    CAT_004_UNUSED("CAT-004", "unused"),
    CAT_004_USED("CAT-004", "used"),
    CAT_004_HAS_CHILDREN("CAT-004", "hasChildren"),
    CAT_004_PROCESSING("CAT-004", "processing"),
    MER_001_CONTENT("MER-001", "content"),
    MER_001_EMPTY("MER-001", "empty"),
    MER_001_SEARCHING("MER-001", "searching"),
    MER_002_CREATE("MER-002", "create"),
    MER_002_EDIT("MER-002", "edit"),
    MER_002_DUPLICATE_WARNING("MER-002", "duplicateWarning"),
    MER_003_EDITING("MER-003", "editing"),
    MER_003_INVALID("MER-003", "invalid"),
    MER_003_MERGING("MER-003", "merging"),
    PLC_001_CONTENT("PLC-001", "content"),
    PLC_001_EMPTY("PLC-001", "empty"),
    PLC_002_CREATE("PLC-002", "create"),
    PLC_002_EDIT("PLC-002", "edit"),
    PLC_002_MAP_UNAVAILABLE("PLC-002", "mapUnavailable"),
    PLC_003_MERGE("PLC-003", "merge"),
    PLC_003_SPLIT("PLC-003", "split"),
    PLC_003_INVALID("PLC-003", "invalid"),
}

public sealed interface ManagementDataState {
    public data object Loading : ManagementDataState
    public data class Content(public val snapshot: ReferenceDataSnapshot) : ManagementDataState
    public data class Error(public val code: String) : ManagementDataState
}

public data class CategorySubmission(
    val categoryId: StableId?,
    val direction: CategoryDirection,
    val parentId: StableId?,
    val name: String,
    val statisticalNature: StatisticalNature,
    val defaultAccountId: StableId?,
    val defaultCardId: StableId?,
    val defaultMerchantId: StableId?,
    val iconKey: String,
    val colorArgb: Int,
)

public data class MerchantSubmission(val merchantId: StableId?, val name: String, val aliases: Set<String>)
public data class PlaceSubmission(val placeId: StableId?, val name: String, val latitudeE7: Int, val longitudeE7: Int, val merchantId: StableId?)

public sealed interface ManagementScreenAction {
    public data class Navigate(
        val screenId: String,
        val stableArguments: Map<String, StableId>,
        val enumArguments: Map<String, String>,
    ) : ManagementScreenAction
    public data class SaveCategory(val submission: CategorySubmission) : ManagementScreenAction
    public data class ReorderCategories(val direction: CategoryDirection, val ids: List<StableId>) : ManagementScreenAction
    public data class RemoveCategory(
        val categoryId: StableId,
        val expectedRowVersion: Long,
        val strategy: CategoryRemovalStrategy,
        val replacementId: StableId?,
    ) : ManagementScreenAction
    public data class SaveMerchant(val submission: MerchantSubmission) : ManagementScreenAction
    public data class MergeMerchant(val sourceId: StableId, val targetId: StableId) : ManagementScreenAction
    public data class SavePlace(val submission: PlaceSubmission) : ManagementScreenAction
    public data class MergePlace(val sourceId: StableId, val targetId: StableId) : ManagementScreenAction
    public data class SplitPlace(val placeId: StableId, val submission: PlaceSubmission, val transactionIds: List<StableId>) : ManagementScreenAction
    public data object Retry : ManagementScreenAction
}

internal class ManagementActions(
    val onNavigate: (screenId: String, stableArguments: Map<String, StableId>, enumArguments: Map<String, String>) -> Unit,
    val onSaveCategory: (CategorySubmission) -> Unit,
    val onReorderCategories: (CategoryDirection, List<StableId>) -> Unit,
    val onRemoveCategory: (StableId, Long, CategoryRemovalStrategy, StableId?) -> Unit,
    val onSaveMerchant: (MerchantSubmission) -> Unit,
    val onMergeMerchant: (StableId, StableId) -> Unit,
    val onSavePlace: (PlaceSubmission) -> Unit,
    val onMergePlace: (StableId, StableId) -> Unit,
    val onSplitPlace: (StableId, PlaceSubmission, List<StableId>) -> Unit,
    val onRetry: () -> Unit,
)

internal fun managementActions(onAction: (ManagementScreenAction) -> Unit): ManagementActions = ManagementActions(
    onNavigate = { screenId, stable, enums -> onAction(ManagementScreenAction.Navigate(screenId, stable, enums)) },
    onSaveCategory = { onAction(ManagementScreenAction.SaveCategory(it)) },
    onReorderCategories = { direction, ids -> onAction(ManagementScreenAction.ReorderCategories(direction, ids)) },
    onRemoveCategory = { id, version, strategy, replacement ->
        onAction(ManagementScreenAction.RemoveCategory(id, version, strategy, replacement))
    },
    onSaveMerchant = { onAction(ManagementScreenAction.SaveMerchant(it)) },
    onMergeMerchant = { source, target -> onAction(ManagementScreenAction.MergeMerchant(source, target)) },
    onSavePlace = { onAction(ManagementScreenAction.SavePlace(it)) },
    onMergePlace = { source, target -> onAction(ManagementScreenAction.MergePlace(source, target)) },
    onSplitPlace = { id, submission, transactions -> onAction(ManagementScreenAction.SplitPlace(id, submission, transactions)) },
    onRetry = { onAction(ManagementScreenAction.Retry) },
)

public typealias PlaceMapSlot = @androidx.compose.runtime.Composable (
    places: List<PlaceReferenceView>,
    unavailable: Boolean,
    onCoordinateSelected: ((latitudeE7: Int, longitudeE7: Int) -> Unit)?,
) -> Unit
