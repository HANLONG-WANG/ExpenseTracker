package app.ledger.feature.journal

import androidx.paging.PagingSource
import androidx.paging.PagingState
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.finance.application.CurrentTransactionCursor
import app.ledger.finance.application.JournalApplicationPort
import app.ledger.finance.application.JournalBulkEditOptions
import app.ledger.finance.application.JournalBulkEditPatch
import app.ledger.finance.application.JournalDependencyView
import app.ledger.finance.application.JournalDetailView
import app.ledger.finance.application.JournalPageRequest
import app.ledger.finance.application.JournalPurgeAssessment
import app.ledger.finance.application.JournalRevisionComparison
import app.ledger.finance.application.JournalRevisionView
import app.ledger.finance.application.JournalSavedFilter
import app.ledger.finance.application.JournalSelectionMode
import app.ledger.finance.application.JournalSelectionSpec
import app.ledger.finance.application.JournalTransactionView
import app.ledger.finance.domain.DependencyPolicy
import app.ledger.finance.domain.DependencyResolution
import app.ledger.finance.domain.TransactionFilter
import app.ledger.finance.domain.TransactionKind

sealed interface JournalLoadState {
    data object Loading : JournalLoadState
    data class Content(
        val filter: TransactionFilter = TransactionFilter(),
        val zoneId: String = "UTC",
        val searchText: String = "",
        val resultCount: Long? = null,
        val presets: List<JournalFilterPreset> = emptyList(),
        val bulkOptions: JournalBulkEditOptions = JournalBulkEditOptions(emptyList(), emptyList(), emptyList(), emptyList(), emptyList()),
        val activePresetId: StableId? = null,
        val selection: JournalSelectionSpec? = null,
        val detail: JournalDetailView? = null,
        val detailLoading: Boolean = false,
        val detailFailureCode: String? = null,
        val history: List<JournalRevisionView> = emptyList(),
        val comparison: JournalRevisionComparison? = null,
        val dependencies: List<JournalDependencyView> = emptyList(),
        val dependencyResolutions: List<DependencyResolution> = emptyList(),
        val purgeAssessment: JournalPurgeAssessment? = null,
        val operation: JournalOperationState = JournalOperationState.IDLE,
    ) : JournalLoadState
    data class Failure(val code: String) : JournalLoadState
}

enum class JournalOperationState { IDLE, VALIDATING, COMMITTING, FAILED, SUCCEEDED }

typealias JournalFilterPreset = JournalSavedFilter

/** All user intents emitted by a Journal screen. No persistence callback crosses the feature boundary. */
sealed interface JournalScreenAction {
    data class Navigate(val screenId: String, val arguments: Map<String, StableId>) : JournalScreenAction
    data class Search(val query: String) : JournalScreenAction
    data class ApplyFilter(val filter: TransactionFilter) : JournalScreenAction
    data class RemoveFilter(val key: String) : JournalScreenAction
    data object Retry : JournalScreenAction
    data class LoadDetail(val transactionId: StableId) : JournalScreenAction
    data class Edit(val transactionId: StableId, val kind: TransactionKind) : JournalScreenAction
    data class OpenAttachment(val attachmentId: StableId) : JournalScreenAction
    data class Select(val transactionId: StableId) : JournalScreenAction
    data object SelectAllMatching : JournalScreenAction
    data object ClearSelection : JournalScreenAction
    data class BulkEdit(val patch: JournalBulkEditPatch) : JournalScreenAction
    data class SaveFilter(val name: String) : JournalScreenAction
    data class ApplyPreset(val presetId: StableId) : JournalScreenAction
    data class CopyPreset(val presetId: StableId) : JournalScreenAction
    data class SetDefaultPreset(val presetId: StableId) : JournalScreenAction
    data class DeletePreset(val presetId: StableId) : JournalScreenAction
    data class ReorderPresets(val presetIds: List<StableId>) : JournalScreenAction
    data class ResolveDependency(val dependency: JournalDependencyView, val policy: DependencyPolicy) : JournalScreenAction
    data class MoveToTrash(
        val transactionId: StableId,
        val expectedRevisionId: StableId,
        val resolutions: List<DependencyResolution>,
    ) : JournalScreenAction
    data class Restore(val transactionId: StableId, val expectedRevisionId: StableId) : JournalScreenAction
    data class CompareRevisions(val transactionId: StableId, val leftRevisionId: StableId, val rightRevisionId: StableId) : JournalScreenAction
    data class RestoreRevision(
        val transactionId: StableId,
        val expectedCurrentRevisionId: StableId,
        val sourceRevisionId: StableId,
        val resolutions: List<DependencyResolution>,
    ) : JournalScreenAction
    data class VerifyPurge(val transactionId: StableId) : JournalScreenAction
    data class PurgeRequested(val transactionId: StableId) : JournalScreenAction
}

internal class JournalActions(
    val onNavigate: (String, Map<String, StableId>) -> Unit,
    val onSearch: (String) -> Unit,
    val onApplyFilter: (TransactionFilter) -> Unit,
    val onRemoveFilter: (String) -> Unit,
    val onRetry: () -> Unit,
    val onLoadDetail: (StableId) -> Unit,
    val onEdit: (StableId, TransactionKind) -> Unit,
    val onOpenAttachment: (StableId) -> Unit,
    val onSelect: (StableId) -> Unit,
    val onSelectAllMatching: () -> Unit,
    val onClearSelection: () -> Unit,
    val onBulkEdit: (JournalBulkEditPatch) -> Unit,
    val onSaveFilter: (String) -> Unit,
    val onApplyPreset: (StableId) -> Unit,
    val onCopyPreset: (StableId) -> Unit,
    val onSetDefaultPreset: (StableId) -> Unit,
    val onDeletePreset: (StableId) -> Unit,
    val onReorderPresets: (List<StableId>) -> Unit,
    val onResolveDependency: (JournalDependencyView, DependencyPolicy) -> Unit,
    val onMoveToTrash: (StableId, StableId, List<DependencyResolution>) -> Unit,
    val onRestore: (StableId, StableId) -> Unit,
    val onCompareRevisions: (StableId, StableId, StableId) -> Unit,
    val onRestoreRevision: (StableId, StableId, StableId, List<DependencyResolution>) -> Unit,
    val onVerifyPurge: (StableId) -> Unit,
    val onPurgeRequested: (StableId) -> Unit,
    val onEdit: (JournalTransactionView) -> Unit = {},
    val onRefund: (StableId) -> Unit = {},
    val onCopyTemplate: (StableId) -> Unit = {},
    val onBack: () -> Unit = {},
)

internal fun journalActions(onAction: (JournalScreenAction) -> Unit): JournalActions = JournalActions(
    onNavigate = { screenId, arguments -> onAction(JournalScreenAction.Navigate(screenId, arguments)) },
    onSearch = { onAction(JournalScreenAction.Search(it)) },
    onApplyFilter = { onAction(JournalScreenAction.ApplyFilter(it)) },
    onRemoveFilter = { onAction(JournalScreenAction.RemoveFilter(it)) },
    onRetry = { onAction(JournalScreenAction.Retry) },
    onLoadDetail = { onAction(JournalScreenAction.LoadDetail(it)) },
    onEdit = { transactionId, kind -> onAction(JournalScreenAction.Edit(transactionId, kind)) },
    onOpenAttachment = { onAction(JournalScreenAction.OpenAttachment(it)) },
    onSelect = { onAction(JournalScreenAction.Select(it)) },
    onSelectAllMatching = { onAction(JournalScreenAction.SelectAllMatching) },
    onClearSelection = { onAction(JournalScreenAction.ClearSelection) },
    onBulkEdit = { onAction(JournalScreenAction.BulkEdit(it)) },
    onSaveFilter = { onAction(JournalScreenAction.SaveFilter(it)) },
    onApplyPreset = { onAction(JournalScreenAction.ApplyPreset(it)) },
    onCopyPreset = { onAction(JournalScreenAction.CopyPreset(it)) },
    onSetDefaultPreset = { onAction(JournalScreenAction.SetDefaultPreset(it)) },
    onDeletePreset = { onAction(JournalScreenAction.DeletePreset(it)) },
    onReorderPresets = { onAction(JournalScreenAction.ReorderPresets(it)) },
    onResolveDependency = { dependency, policy -> onAction(JournalScreenAction.ResolveDependency(dependency, policy)) },
    onMoveToTrash = { transactionId, revisionId, resolutions ->
        onAction(JournalScreenAction.MoveToTrash(transactionId, revisionId, resolutions))
    },
    onRestore = { transactionId, revisionId -> onAction(JournalScreenAction.Restore(transactionId, revisionId)) },
    onCompareRevisions = { transactionId, left, right ->
        onAction(JournalScreenAction.CompareRevisions(transactionId, left, right))
    },
    onRestoreRevision = { transactionId, expectedCurrentRevisionId, sourceRevisionId, resolutions ->
        onAction(JournalScreenAction.RestoreRevision(transactionId, expectedCurrentRevisionId, sourceRevisionId, resolutions))
    },
    onVerifyPurge = { onAction(JournalScreenAction.VerifyPurge(it)) },
    onPurgeRequested = { onAction(JournalScreenAction.PurgeRequested(it)) },
)

class JournalPagingSource(
    private val port: JournalApplicationPort,
    private val bookId: StableId,
    private val filter: TransactionFilter,
    private val accountIdForRunningBalance: StableId? = null,
) : PagingSource<CurrentTransactionCursor, JournalTransactionView>() {
    override suspend fun load(params: LoadParams<CurrentTransactionCursor>): LoadResult<CurrentTransactionCursor, JournalTransactionView> = when (
        val result = port.page(
            JournalPageRequest(
                bookId = bookId,
                filter = filter,
                limit = params.loadSize.coerceIn(1, MAX_PAGE_LOAD_SIZE),
                cursor = params.key,
                runningBalanceAccountId = accountIdForRunningBalance,
            ),
        )
    ) {
        is DomainResult.Success -> LoadResult.Page(result.value.items, prevKey = null, nextKey = result.value.nextCursor)
        is DomainResult.Failure -> LoadResult.Error(JournalQueryException(result.error.code))
    }

    override fun getRefreshKey(state: PagingState<CurrentTransactionCursor, JournalTransactionView>): CurrentTransactionCursor? = state.anchorPosition
        ?.let(state::closestItemToPosition)
        ?.let {
            CurrentTransactionCursor(
                it.occurredAt.toEpochMilli(),
                app.ledger.finance.domain.TransactionId(it.transactionId),
            )
        }
}

private const val MAX_PAGE_LOAD_SIZE = 200

class JournalQueryException(val code: String) : IllegalStateException("Journal query failed: $code")

object JournalSelectionPolicy {
    fun begin(filter: TransactionFilter, selected: StableId): JournalSelectionSpec = JournalSelectionSpec(
        queryFingerprint = fingerprint(filter),
        mode = JournalSelectionMode.EXPLICIT,
        includedIds = setOf(selected),
    )

    fun toggle(spec: JournalSelectionSpec, id: StableId): JournalSelectionSpec = if (spec.mode == JournalSelectionMode.ALL_MATCHING) {
        spec.copy(excludedIds = if (id in spec.excludedIds) spec.excludedIds - id else spec.excludedIds + id)
    } else {
        spec.copy(includedIds = if (id in spec.includedIds) spec.includedIds - id else spec.includedIds + id)
    }

    fun selectAllMatching(filter: TransactionFilter): JournalSelectionSpec = JournalSelectionSpec(
        queryFingerprint = fingerprint(filter),
        mode = JournalSelectionMode.ALL_MATCHING,
    )

    fun fingerprint(filter: TransactionFilter): String = JournalSelectionSpec.fingerprint(filter)
}
