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
    public const val JOURNAL_SCREEN: String = "journal_screen"
    public const val DATA_TABLE: String = "accessible_data_table"
    public const val CHART: String = "ledger_chart"
    public const val MAP: String = "ledger_map"
    public const val OPERATION: String = "operation_progress"
    public const val SENSITIVE_VALUE: String = "sensitive_value"
    public const val ATTACHMENT_LIST: String = "attachment_list"
    public const val ATTACHMENT_PREVIEW: String = "attachment_preview"
    public const val P12_ACCOUNTS_ROOT: String = "p12_accounts_root"
    public const val P12_MANAGEMENT_ROOT: String = "p12_management_root"
    public const val ATTACHMENT_METADATA: String = "attachment_metadata"
    public const val ATTACHMENT_EXTERNAL_OPEN: String = "attachment_external_open"
    public const val ATTACHMENT_RENAME: String = "attachment_rename"
    public const val LOCATION_EDITOR: String = "location_editor"
    public const val LOCATION_PERMISSION: String = "location_permission"
    public const val MAP_FALLBACK: String = "map_accessible_fallback"
    public const val SESSION_GATE: String = "session_gate"
    public const val ONBOARDING_ROOT: String = "onboarding_root"
    public const val ONBOARDING_PRIMARY: String = "onboarding_primary_action"
    public const val ONBOARDING_SECONDARY: String = "onboarding_secondary_action"
    public const val GLOBAL_BANNER: String = "global_operation_banner"
    public const val RECORD_ROOT: String = "record_root"
    public const val RECORD_EDITOR: String = "record_editor"
    public const val RECORD_CATEGORY: String = "record_category"
    public const val RECORD_ACCOUNT: String = "record_account"
    public const val RECORD_DEFAULT_SOURCE: String = "record_default_source"
    public const val RECORD_VALIDATION: String = "record_validation"
    public const val RECORD_SETTLEMENT: String = "record_settlement"
    public const val RECORD_UNSAVED_DIALOG: String = "record_unsaved_dialog"
    public const val RECORD_REVISION_CONFLICT: String = "record_revision_conflict"
    public const val BATCH_RECORD_ROOT: String = "batch_record_root"
    public const val BATCH_SUMMARY_TABLE: String = "batch_summary_table"
    public const val BATCH_ROW_EDITOR: String = "batch_row_editor"
    public const val BATCH_VALIDATION: String = "batch_validation"
    public const val BATCH_COMMIT: String = "batch_commit"
    public const val BATCH_DISCARD: String = "batch_discard_confirmation"
    public const val ANALYSIS_HOME: String = "analysis_home"
    public const val REPORT_CATALOG: String = "report_catalog"
    public const val REPORT_DETAIL: String = "report_detail"
    public const val REPORT_FILTER: String = "report_filter"
    public const val REPORT_DRILLDOWN: String = "report_drilldown"
    public const val INTEGRITY_REPORT: String = "integrity_report"
    public const val REPORT_EXPORT: String = "report_export"
    public const val REPORT_VISUALIZATION_REASON: String = "report_visualization_reason"
    public const val SPECIALIZED_TRANSACTION_ROOT: String = "specialized_transaction_root"
    public const val SPECIALIZED_TRANSACTION_FORM: String = "specialized_transaction_form"
    public const val EFFECTIVE_RATE_SUMMARY: String = "effective_rate_summary"
    public const val FX_COST_SECTION: String = "fx_cost_section"
    public const val CURRENCY_SETTINGS_ROOT: String = "currency_settings_root"
    public const val REFUND_ROOT: String = "refund_root"
    public const val REFUND_FORM: String = "refund_form"
    public const val REFUND_PICKER: String = "refund_picker"
    public const val REFUND_ORIGINAL: String = "refund_original"
    public const val REFUND_AMOUNT_SUMMARY: String = "refund_amount_summary"
    public const val REFUND_TIME_DIMENSIONS: String = "refund_time_dimensions"
    public const val REFUND_EXCESS_CONFIRMATION: String = "refund_excess_confirmation"
    public const val BUDGET_HOME: String = "budget_home"
    public const val BUDGET_EDITOR: String = "budget_editor"
    public const val BUDGET_CATEGORY_EDITOR: String = "budget_category_editor"
    public const val BUDGET_ADJUSTMENTS: String = "budget_adjustments"
    public const val BUDGET_ADJUSTMENT_EDITOR: String = "budget_adjustment_editor"
    public const val BUDGET_HISTORY: String = "budget_history"
    public const val BUDGET_TEMPLATES: String = "budget_templates"
    public const val BUDGET_TEMPLATE_EDITOR: String = "budget_template_editor"
    public const val BUDGET_DAILY_AVAILABLE: String = "budget_daily_available"
    public const val BUDGET_COMPOSITION: String = "budget_composition"
    public const val BUDGET_CONSTRAINT_METERS: String = "budget_constraint_meters"
    public const val PROJECT_LIST: String = "project_list"
    public const val PROJECT_EDITOR: String = "project_editor"
    public const val PROJECT_DETAIL: String = "project_detail"
    public const val PROJECT_TRANSACTIONS: String = "project_transactions"
    public const val PROJECT_CASHFLOW: String = "project_cashflow"
    public const val PROJECT_STATUS: String = "project_status"
    public const val GOAL_LIST: String = "goal_list"
    public const val GOAL_EDITOR: String = "goal_editor"
    public const val GOAL_DETAIL: String = "goal_detail"
    public const val GOAL_MOVEMENT: String = "goal_movement"
    public const val GOAL_COMPLETION: String = "goal_completion"
    public const val ACCOUNT_AVAILABILITY: String = "account_availability"
    public const val PROJECT_MONTHLY_SNAPSHOT: String = "project_monthly_snapshot"
    public const val CREDIT_PAYMENT: String = "credit_payment"
    public const val CREDIT_ACCOUNT_DETAIL: String = "credit_account_detail"
    public const val CREDIT_PROFILE: String = "credit_profile"
    public const val CREDIT_STATEMENTS: String = "credit_statements"
    public const val CREDIT_STATEMENT_DETAIL: String = "credit_statement_detail"
    public const val CREDIT_OFFICIAL_STATEMENT: String = "credit_official_statement"
    public const val CREDIT_ASSIGNMENT: String = "credit_assignment"
    public const val CREDIT_PAYMENT_ALLOCATION: String = "credit_payment_allocation"
    public const val CREDIT_AUTO_PAYMENT: String = "credit_auto_payment"
    public const val INSTALLMENT_SETUP: String = "installment_setup"
    public const val INSTALLMENT_LIST: String = "installment_list"
    public const val INSTALLMENT_EDITOR: String = "installment_editor"
    public const val INSTALLMENT_DETAIL: String = "installment_detail"
    public const val INSTALLMENT_SCHEDULE: String = "installment_schedule"
    public const val INSTALLMENT_SETTLEMENT: String = "installment_settlement"
    public const val INSTALLMENT_REFUND: String = "installment_refund"
    public const val LIABILITY_HOME: String = "liability_home"
    public const val LOAN_OPERATION: String = "loan_operation"
    public const val LOAN_DISBURSEMENT: String = "loan_disbursement"
    public const val LOAN_PAYMENT: String = "loan_payment"
    public const val LOAN_LIST: String = "loan_list"
    public const val LOAN_WIZARD: String = "loan_wizard"
    public const val LOAN_TRANCHE: String = "loan_tranche"
    public const val LOAN_TERMS: String = "loan_terms"
    public const val LOAN_RATES: String = "loan_rates"
    public const val LOAN_SCHEDULE_PREVIEW: String = "loan_schedule_preview"
    public const val LOAN_DETAIL: String = "loan_detail"
    public const val LOAN_SCHEDULE: String = "loan_schedule"
    public const val LOAN_PAYMENT_DETAIL: String = "loan_payment_detail"
    public const val LOAN_SIMULATION: String = "loan_simulation"
    public const val LOAN_SIMULATION_APPLY: String = "loan_simulation_apply"
    public const val SETTLEMENT_HOME: String = "settlement_home"
    public const val SETTLEMENT_EDITOR: String = "settlement_editor"
    public const val SETTLEMENT_PARTICIPANTS: String = "settlement_participants"
    public const val SETTLEMENT_DETAIL: String = "settlement_detail"
    public const val SETTLEMENT_POSITIONS: String = "settlement_positions"
    public const val SETTLEMENT_PAYMENT: String = "settlement_payment"
    public const val SETTLEMENT_HISTORY: String = "settlement_history"
    public const val SETTLEMENT_ADDITIONAL: String = "settlement_additional"
    public const val AUTOMATION_HUB: String = "automation_hub"
    public const val AUTOMATION_TEMPLATE_PICKER: String = "automation_template_picker"
    public const val AUTOMATION_TEMPLATE_LIST: String = "automation_template_list"
    public const val AUTOMATION_TEMPLATE_EDITOR: String = "automation_template_editor"
    public const val AUTOMATION_SERIES_LIST: String = "automation_series_list"
    public const val AUTOMATION_SERIES_EDITOR: String = "automation_series_editor"
    public const val AUTOMATION_RULE_EDITOR: String = "automation_rule_editor"
    public const val AUTOMATION_PREVIEW: String = "automation_preview"
    public const val AUTOMATION_CANDIDATES: String = "automation_candidates"
    public const val AUTOMATION_CANDIDATE_EDITOR: String = "automation_candidate_editor"
    public const val AUTOMATION_SCOPE: String = "automation_scope"

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
public enum class LedgerTextRole { DISPLAY, TITLE, SECTION, BODY, SUPPORTING, LABEL }

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
    val paletteId: String = "slate",
)

@Immutable
public data class ReferenceDataRowUiModel(
    val stableKey: String,
    val title: String,
    val supportingText: String?,
    val hierarchyLevel: Int = 1,
    val status: LedgerStatusVariant = LedgerStatusVariant.NEUTRAL,
    val icon: LedgerIcon? = null,
    val paletteId: String? = null,
) {
    init {
        require(hierarchyLevel in 1..2)
        LedgerTestTags.requireStable(stableKey)
    }
}

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
