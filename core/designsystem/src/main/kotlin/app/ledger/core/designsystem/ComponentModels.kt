package app.ledger.core.designsystem

import androidx.compose.runtime.Immutable
import app.ledger.core.money.MoneyUiModel

public object LedgerTestTags {
    public const val ROOT: String = "ledger_root"
    public const val TOP_APP_BAR: String = "ledger_top_app_bar"
    public const val BOTTOM_NAVIGATION: String = "ledger_bottom_navigation"
    public const val SAVE: String = "ledger_save"
    public const val AMOUNT: String = "transaction_amount"
    public const val CATEGORY_GRID: String = "category_grid"
    public const val JOURNAL_ROW: String = "journal_transaction_row"
    public const val DATA_TABLE: String = "accessible_data_table"
    public const val CHART: String = "ledger_chart"
    public const val MAP: String = "ledger_map"
    public const val OPERATION: String = "operation_progress"
    public const val SENSITIVE_VALUE: String = "sensitive_value"

    public fun requireStable(value: String): String {
        require(STABLE_TAG.matches(value)) { "test tag must be a stable semantic identifier" }
        require(SENSITIVE_WORDS.none(value::contains)) { "test tag must not describe sensitive content" }
        return value
    }

    private val STABLE_TAG = Regex("[a-z][a-z0-9_]{2,63}")
    private val SENSITIVE_WORDS = setOf("note", "memo", "password", "card_number", "latitude", "longitude", "attachment_name")
}

public sealed interface LedgerLoadState<out T> {
    public data object Initial : LedgerLoadState<Nothing>
    public data object Loading : LedgerLoadState<Nothing>
    public data class Content<T>(val value: T) : LedgerLoadState<T>
    public data object Empty : LedgerLoadState<Nothing>
    public data class Error(val code: UiErrorCode) : LedgerLoadState<Nothing>
}

@JvmInline
public value class UiErrorCode(public val value: String) {
    init {
        require(Regex("[A-Z][A-Z0-9_]{2,47}").matches(value)) { "UI error code must be sanitized" }
    }
}

public enum class LedgerIcon {
    BACK,
    CLOSE,
    MORE,
    SAVE,
    SEARCH,
    CLEAR,
    ADD,
    CHECK,
    ERROR,
    WARNING,
    INFO,
    CHEVRON,
    RECORD,
    JOURNAL,
    ACCOUNT,
    BUDGET,
    ANALYSIS,
    ATTACHMENT,
    LOCATION,
    TRANSFER,
    REFUND,
}

public enum class LedgerButtonVariant { PRIMARY, SECONDARY, TONAL, TEXT, DANGER }
public enum class LedgerTopAppBarVariant { TOP_LEVEL, BACK, SELECTION, MODAL_CLOSE }
public enum class LedgerStatusVariant { NEUTRAL, POSITIVE, INFO, WARNING, DANGER, CANDIDATE, ARCHIVED, DELETED }
public enum class LedgerBannerVariant { INFO, WARNING, DANGER, NEUTRAL }
public enum class AmountSize { HERO, LARGE, MEDIUM, LIST }
public enum class MetricCardVariant { STANDARD, EMPHASIZED }
public enum class LedgerProgressState { NORMAL, WARNING, OVER_LIMIT }
public enum class LedgerTopLevel { RECORD, JOURNAL, ACCOUNTS, BUDGET, ANALYSIS }
public enum class LedgerChartType { LINE, COLUMN, STACKED, PIE, TABLE, PROGRESS }

@Immutable
public data class CategoryTileUiModel(
    val stableKey: String,
    val name: String,
    val accessibleLabel: String,
    val paletteId: String,
    val icon: LedgerIcon,
    val isTopLevel: Boolean,
    val childCount: Int = 0,
    val deleted: Boolean = false,
)

@Immutable
public data class CategoryGroupUiModel(
    val stableKey: String,
    val title: String,
    val categories: List<CategoryTileUiModel>,
)

@Immutable
public data class JournalTransactionUiModel(
    val stableKey: String,
    val categoryOrType: String,
    val summary: String,
    val accountAndCard: String,
    val amount: MoneyUiModel,
    val typeLabel: String,
    val icon: LedgerIcon,
    val badges: List<String> = emptyList(),
    val runningBalance: MoneyUiModel? = null,
    val accessibleText: String,
)

@Immutable
public data class AccountSummaryUiModel(
    val stableKey: String,
    val name: String,
    val typeLabel: String,
    val balance: MoneyUiModel,
    val secondaryValue: String? = null,
    val status: String? = null,
    val archived: Boolean = false,
    val icon: LedgerIcon = LedgerIcon.ACCOUNT,
)

@Immutable
public data class ProgressSummaryUiModel(
    val title: String,
    val valueText: String,
    val progress: Float,
    val statusText: String,
    val accessibleText: String,
    val state: LedgerProgressState,
    val excessText: String? = null,
)

@Immutable
public data class ValidationItemUiModel(
    val stableFieldTag: String,
    val message: String,
)

@Immutable
public data class FilterChipUiModel(
    val stableKey: String,
    val label: String,
)

@Immutable
public data class FilterDimensionUiModel(
    val title: String,
    val chips: List<FilterChipUiModel>,
)

public enum class AttachmentTransferState { READY, IMPORTING, FAILED }

@Immutable
public data class AttachmentUiModel(
    val stableKey: String,
    val displayName: String,
    val sizeText: String,
    val typeLabel: String,
    val progress: Float? = null,
    val state: AttachmentTransferState = AttachmentTransferState.READY,
)

public sealed interface LocationFieldState {
    public data object Locating : LocationFieldState
    public data class Located(val accuracyText: String) : LocationFieldState
    public data object Unavailable : LocationFieldState
    public data object PermissionDenied : LocationFieldState
    public data object ManuallyAdjusted : LocationFieldState
}

@Immutable
public data class LedgerChartSeries(
    val stableSeriesKey: String,
    val label: String,
    val values: List<Double>,
    val pointLabels: List<String>,
)

@Immutable
public data class LedgerChartUiModel(
    val title: String,
    val scope: String,
    val summary: String,
    val type: LedgerChartType,
    val series: List<LedgerChartSeries>,
)

@Immutable
public data class AccessibleTableUiModel(
    val caption: String,
    val columnHeaders: List<String>,
    val rows: List<List<String>>,
)

public enum class MapAvailability { AVAILABLE, LOADING, UNAVAILABLE }

@Immutable
public data class LedgerMapUiModel(
    val summary: String,
    val availability: MapAvailability,
    val attribution: String,
)

public enum class OperationCapability { CANCELABLE, PAUSABLE, NON_CANCELABLE_COMMIT }

@Immutable
public data class OperationProgressUiModel(
    val name: String,
    val phase: String,
    val processedText: String,
    val progress: Float?,
    val capability: OperationCapability,
    val statusExplanation: String,
    val failureCode: UiErrorCode? = null,
)
