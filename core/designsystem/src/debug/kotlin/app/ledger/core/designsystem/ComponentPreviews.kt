@file:Suppress(
    "ktlint:standard:function-naming",
    "FunctionNaming",
    "MagicNumber",
    "MaxLineLength",
    "TooManyFunctions",
    "UnusedPrivateMember",
)

package app.ledger.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.ledger.core.money.AmountSemantic
import app.ledger.core.money.AmountVisibility
import app.ledger.core.money.MoneyUiModel

@Preview(name = "Light 360", widthDp = 360, showBackground = true)
@Preview(name = "Dark 360", widthDp = 360, showBackground = true, uiMode = 0x20)
@Preview(name = "Compact 200% zh", widthDp = 320, fontScale = 2f, locale = "zh-rCN", showBackground = true)
@Preview(name = "Standard 130% ja", widthDp = 360, fontScale = 1.3f, locale = "ja", showBackground = true)
@Preview(name = "Wide 160% English", widthDp = 480, fontScale = 1.6f, locale = "en", showBackground = true)
@Preview(name = "Wide English", widthDp = 480, locale = "en", showBackground = true)
@Preview(name = "Foldable 200% Japanese", widthDp = 600, fontScale = 2f, locale = "ja", showBackground = true)
private annotation class LedgerComponentPreviews

private object PreviewFixtures {
    val expense = MoneyUiModel("−¥1,250", "negative 1,250 Japanese yen", AmountSemantic.OUTFLOW, AmountVisibility.VISIBLE)
    val income = MoneyUiModel("+¥8,000", "positive 8,000 Japanese yen", AmountSemantic.INFLOW, AmountVisibility.VISIBLE)
    val hidden = MoneyUiModel("••••", "amount hidden", AmountSemantic.NEUTRAL, AmountVisibility.HIDDEN)
    val category = CategoryTileUiModel(
        stableKey = "fictional_lunch",
        name = "午餐 / 昼食 / Lunch and meals",
        accessibleLabel = "Lunch, expense second-level category, not selected",
        paletteId = "teal",
        icon = LedgerIcon.RECORD,
        isTopLevel = false,
    )
    val journal = JournalTransactionUiModel(
        stableKey = "fictional_transaction",
        categoryOrType = "餐饮",
        summary = "示例午餐 / Sample lunch",
        accountAndCard = "示例账户 · 无实体卡",
        amount = expense,
        typeLabel = "支出",
        icon = LedgerIcon.RECORD,
        badges = listOf("附件", "项目"),
        accessibleText = "Expense, meals, fictional lunch, negative 1,250 Japanese yen",
    )
}

@Composable
private fun PreviewFrame(content: @Composable () -> Unit) {
    LedgerTheme(ThemeMode.FOLLOW_SYSTEM, dynamicColor = false, reduceMotion = false) {
        Box(Modifier.fillMaxWidth().background(LedgerTheme.colors.material.background).padding(LedgerTheme.spacing.md)) {
            content()
        }
    }
}

@LedgerComponentPreviews
@Composable
private fun ThemeAndStatePreview() = PreviewFrame {
    Column(verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        LedgerBanner("Local external service status", LedgerBannerVariant.INFO)
        LedgerLoadingState()
        LedgerEmptyState("No entries", "Create the first item to continue.", "Create", {})
        LedgerErrorState(UiErrorCode("LOAD_FAILED"), "Could not load this section.", {})
    }
}

@LedgerComponentPreviews
@Composable
private fun ScaffoldPreview() {
    LedgerTheme(ThemeMode.FOLLOW_SYSTEM, false, false) {
        LedgerScaffold(
            topBar = { LedgerTopAppBar("Quiet Precision", LedgerTopAppBarVariant.TOP_LEVEL) },
            bottomBar = { LedgerNavigationBar(LedgerTopLevel.RECORD, {}) },
            fixedAction = { LedgerSaveFab({}) },
        ) { Column { TextPreview("Scaffold content") } }
    }
}

@LedgerComponentPreviews
@Composable
private fun NavigationAndActionsPreview() = PreviewFrame {
    Column(verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        LedgerTopAppBar("Selection title", LedgerTopAppBarVariant.SELECTION, selectionCount = 3)
        LedgerNavigationBar(LedgerTopLevel.JOURNAL, {}, badges = mapOf(LedgerTopLevel.ANALYSIS to 3))
        Row {
            LedgerSaveFab({})
            LedgerSaveFab({}, compact = true, submitting = true)
        }
        Row {
            LedgerButton("Primary", {})
            LedgerButton("Danger", {}, variant = LedgerButtonVariant.DANGER)
        }
    }
}

@LedgerComponentPreviews
@Composable
private fun AmountMetricBadgePreview() = PreviewFrame {
    Column(verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        AmountText(PreviewFixtures.income, AmountSize.HERO)
        MoneyStack(PreviewFixtures.expense, historicalValuation = PreviewFixtures.hidden, explanation = "Fictional rate evidence")
        MetricCard("Monthly income", PreviewFixtures.income, variant = MetricCardVariant.EMPHASIZED)
        Row {
            StatusBadge("未入账", LedgerStatusVariant.CANDIDATE)
            StatusBadge("Over limit", LedgerStatusVariant.DANGER)
        }
    }
}

@LedgerComponentPreviews
@Composable
private fun CategoryPreview() = PreviewFrame {
    CategoryGrid(
        groups = listOf(CategoryGroupUiModel("fictional_group", "Meals", listOf(PreviewFixtures.category, PreviewFixtures.category.copy(stableKey = "fictional_top", isTopLevel = true)))),
        selectedStableKey = "fictional_lunch",
        onSelect = {},
        modifier = Modifier.height(320.dp),
        onCreate = {},
        createLabel = "Create category",
    )
}

@LedgerComponentPreviews
@Composable
private fun JournalAndAccountPreview() = PreviewFrame {
    Column(verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        JournalTransactionRow(PreviewFixtures.journal, {}, {})
        AccountSummaryCard(AccountSummaryUiModel("fictional_account", "Sample wallet", "Cash", PreviewFixtures.income, "Available ••••"), {})
        ProgressSummary(ProgressSummaryUiModel("Budget", "¥12,000 / ¥10,000", 1.2f, "120%", "Budget 120 percent, over by 2,000 yen", LedgerProgressState.OVER_LIMIT, "Over by ¥2,000"))
    }
}

@LedgerComponentPreviews
@Composable
private fun FormFieldsPreview() = PreviewFrame {
    Column(verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        LedgerTextField("", {}, "Long localized field label", errorText = "Required", required = true)
        SearchField("fictional", {})
        SelectorField("Account", "Sample wallet", {})
        DateTimeZoneField("Occurred at", "2026-08-01 12:30", "Asia/Tokyo", {})
        MoneyExpressionField("1000+250", "1000 + 250", PreviewFixtures.expense, {}, currencyCode = "JPY")
    }
}

@LedgerComponentPreviews
@Composable
private fun FormSectionValidationPreview() = PreviewFrame {
    Column(verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        FormSection("Advanced details", description = "Progressively disclosed") { TextPreview("Section content") }
        ValidationSummary(listOf(ValidationItemUiModel("transaction_amount", "Amount must be positive")), {})
        LedgerChip("Selected filter", {}, selected = true)
        LedgerTabRow(0, listOf("Expense", "Income", "Other"), {})
    }
}

@LedgerComponentPreviews
@Composable
private fun FilterPreview() = PreviewFrame {
    FilterBuilder(
        listOf(FilterDimensionUiModel("Account", listOf(FilterChipUiModel("fictional_wallet", "Sample wallet")))),
        "Any selected account AND this month",
        {},
        {},
        {},
    )
}

@LedgerComponentPreviews
@Composable
private fun AttachmentLocationPreview() = PreviewFrame {
    Column(verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        AttachmentField(listOf(AttachmentUiModel("fictional_attachment", "fictional-receipt.pdf", "12 KB", "PDF", .5f, AttachmentTransferState.IMPORTING)), {}, {}, {}, addLabel = "Add attachment")
        LocationField(LocationFieldState.Unavailable, {}, mapLabel = "Open map")
    }
}

@LedgerComponentPreviews
@Composable
private fun ChartTablePreview() = PreviewFrame {
    val model = LedgerChartUiModel(
        "Monthly values",
        "Fictional range",
        "Values increased in the sample period.",
        LedgerChartType.LINE,
        listOf(LedgerChartSeries("fictional_series", "Sample", listOf(1.0, 2.0), listOf("A", "B"))),
    )
    ChartCard(
        model,
        chart = { Canvas(Modifier.fillMaxSize()) { drawLine(Color(0xFF006B66), start = Offset(0f, center.y), end = Offset(size.width, center.y), strokeWidth = 4f) } },
        dataTable = AccessibleTableUiModel("Exact sample values", listOf("Period", "Value"), listOf(listOf("A", "1"), listOf("B", "2"))),
        tableExpanded = true,
        onToggleTable = {},
    )
}

@LedgerComponentPreviews
@Composable
private fun MapPreview() = PreviewFrame {
    MapPanel(
        LedgerMapUiModel("Fictional map summary", MapAvailability.UNAVAILABLE, "Map data attribution"),
        mapContent = {},
        fallbackContent = { TextPreview("Accessible place list") },
    )
}

@LedgerComponentPreviews
@Composable
private fun OperationPreview() = PreviewFrame {
    OperationProgressPanel(
        OperationProgressUiModel("Import", "Validating", "50 / 100", .5f, OperationCapability.CANCELABLE, "Safe to cancel"),
        onCancel = {},
    )
}

@LedgerComponentPreviews
@Composable
private fun SensitiveAndRiskPreview() = PreviewFrame {
    Column(verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        SensitiveValueField("4111 1111 1111 1111", false, {}, {})
        HighRiskConfirmation("Permanently clear", "Fictional test record", "Cannot be undone", "Other records are unchanged", "CLEAR", "", {}, {}, {})
    }
}

@LedgerComponentPreviews
@Composable
private fun BatchComponentsPreview() = PreviewFrame {
    Column(Modifier.height(420.dp), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        BatchToolbar(listOf("Add row" to {}, "Paste" to {}, "Sort by date" to {}))
        BatchSummaryTable(
            rows = listOf(
                BatchSummaryRowUiModel(
                    "fictional_batch_row",
                    "1",
                    "Meals",
                    "−¥1,250",
                    "Sample wallet",
                    "Sample café",
                    "Aug 20, 2026",
                    "Trip",
                    "Attachment",
                    "Needs review",
                    "Row 1, meals, negative 1,250 Japanese yen, needs review",
                ),
            ),
            headers = listOf("Row", "Category", "Amount", "Account", "Merchant", "Date", "Project", "Details", "Status"),
            onRowClick = {},
            modifier = Modifier.weight(1f),
        )
        BatchCommitBar("Validate", "Commit all", "Discard", {}, {}, {}, committing = false)
        BatchCommitBar("Validating…", "Commit all", "Discard", {}, {}, {}, committing = true)
    }
}

@LedgerComponentPreviews
@Composable
private fun ReferenceAndInteractionPreview() = PreviewFrame {
    Column(verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        ReferenceDataRow(
            ReferenceDataRowUiModel("fictional_reference", "Fictional category", "Second level", 2, LedgerStatusVariant.ARCHIVED, LedgerIcon.RECORD, "teal"),
            {},
        )
        LedgerChoiceRow("Selected choice", true, {})
        LedgerChoiceRow("Disabled choice", false, {}, enabled = false)
        LedgerToggleRow("Enabled setting", true, {})
        LedgerToggleRow("Disabled setting", false, {}, enabled = false)
        Row {
            LedgerIconButton(LedgerIcon.ADD, "Add", {})
            LedgerIconButton(LedgerIcon.SAVE, "Save disabled", {}, enabled = false)
        }
    }
}

@LedgerComponentPreviews
@Composable
private fun DialogPreview() = PreviewFrame {
    LedgerDialog(
        title = "Review irreversible action",
        message = "The selected fictional record will be changed. Other records remain unchanged.",
        confirmLabel = "Confirm",
        onConfirm = {},
        onDismiss = {},
        danger = true,
    )
}

@LedgerComponentPreviews
@Composable
private fun BottomSheetPreview() = PreviewFrame {
    LedgerBottomSheet(onDismiss = {}) {
        Column(Modifier.padding(LedgerTheme.spacing.md), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
            LedgerText("Choose a fictional account", LedgerTextRole.TITLE)
            LedgerChoiceRow("Sample wallet", true, {})
            LedgerChoiceRow("Sample bank", false, {})
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@LedgerComponentPreviews
@Composable
private fun DatePickerPreview() = PreviewFrame {
    LedgerDatePickerDialog(
        state = rememberDatePickerState(initialSelectedDateMillis = 1_786_972_800_000L),
        onConfirm = {},
        onDismiss = {},
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@LedgerComponentPreviews
@Composable
private fun TimePickerPreview() = PreviewFrame {
    LedgerTimePickerDialog(
        state = rememberTimePickerState(initialHour = 12, initialMinute = 30, is24Hour = true),
        onConfirm = {},
        onDismiss = {},
    )
}

@Composable
private fun TextPreview(value: String) {
    androidx.compose.material3.Text(value, style = LedgerTheme.typography.bodyMedium)
}
