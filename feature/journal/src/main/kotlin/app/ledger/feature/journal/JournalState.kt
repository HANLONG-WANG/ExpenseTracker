package app.ledger.feature.journal

import androidx.paging.PagingSource
import androidx.paging.PagingState
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.finance.application.CurrentTransactionCursor
import app.ledger.finance.application.DEFAULT_INTERACTIVE_LOAD_TIMEOUT_MILLIS
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
import kotlinx.coroutines.withTimeoutOrNull

sealed interface JournalLoadState {
    data object Loading : JournalLoadState
    data class Content(
        val filter: TransactionFilter = TransactionFilter(),
        val zoneId: String = "UTC",
        val searchText: String = "",
        val searchPending: Boolean = false,
        val searchResultReady: Boolean = false,
        val pagingEpoch: Int = 0,
        val pageLoadedEpoch: Int? = null,
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

enum class JournalOperationState { IDLE, VALIDATING, COMMITTING, NO_CHANGES, FAILED, SUCCEEDED }

typealias JournalFilterPreset = JournalSavedFilter

data class JournalActions(
    val onNavigate: (String, Map<String, StableId>) -> Unit,
    val onSearch: (String) -> Unit,
    val onApplyFilter: (TransactionFilter) -> Unit,
    val onRemoveFilter: (String) -> Unit,
    val onRetry: () -> Unit,
    val onLoadDetail: (StableId) -> Unit,
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
    val onEditById: (StableId, TransactionKind) -> Unit = { _, _ -> },
    val onOpenAttachment: (StableId) -> Unit = {},
    val onEdit: (JournalTransactionView) -> Unit = {},
    val onRefund: (StableId) -> Unit = {},
    val onCopyTemplate: (StableId) -> Unit = {},
    val onPagePresented: () -> Unit = {},
    val onBack: () -> Unit = {},
)

class JournalPagingSource(
    private val port: JournalApplicationPort,
    private val bookId: StableId,
    private val filter: TransactionFilter,
    private val accountIdForRunningBalance: StableId? = null,
    private val onPageLoadStarted: () -> Unit = {},
    private val onPageLoaded: (successful: Boolean) -> Unit = {},
) : PagingSource<CurrentTransactionCursor, JournalTransactionView>() {
    override suspend fun load(params: LoadParams<CurrentTransactionCursor>): LoadResult<CurrentTransactionCursor, JournalTransactionView> {
        var completionReported = false
        onPageLoadStarted()
        return try {
            val result = withTimeoutOrNull(DEFAULT_INTERACTIVE_LOAD_TIMEOUT_MILLIS) {
                port.page(
                    JournalPageRequest(
                        bookId = bookId,
                        filter = filter,
                        limit = params.loadSize.coerceIn(1, MAX_PAGE_LOAD_SIZE),
                        cursor = params.key,
                        runningBalanceAccountId = accountIdForRunningBalance,
                    ),
                )
            }
            val loaded = when (result) {
                null -> LoadResult.Error(JournalQueryException("JOURNAL_PAGE_TIMEOUT"))
                is DomainResult.Success -> LoadResult.Page(result.value.items, prevKey = null, nextKey = result.value.nextCursor)
                is DomainResult.Failure -> LoadResult.Error(JournalQueryException(result.error.code))
            }
            onPageLoaded(loaded is LoadResult.Page)
            completionReported = true
            loaded
        } finally {
            if (!completionReported) onPageLoaded(false)
        }
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
