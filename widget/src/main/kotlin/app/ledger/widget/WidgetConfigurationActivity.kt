@file:Suppress("FunctionNaming", "LongMethod", "LongParameterList", "ktlint:standard:function-naming")

package app.ledger.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.lifecycleScope
import app.ledger.core.common.StableId
import app.ledger.core.designsystem.LedgerBanner
import app.ledger.core.designsystem.LedgerBannerVariant
import app.ledger.core.designsystem.LedgerButton
import app.ledger.core.designsystem.LedgerButtonVariant
import app.ledger.core.designsystem.LedgerCard
import app.ledger.core.designsystem.LedgerChoiceRow
import app.ledger.core.designsystem.LedgerDateFormat
import app.ledger.core.designsystem.LedgerDateFormatterRuntime
import app.ledger.core.designsystem.LedgerEmptyState
import app.ledger.core.designsystem.LedgerLoadingState
import app.ledger.core.designsystem.LedgerScaffold
import app.ledger.core.designsystem.LedgerText
import app.ledger.core.designsystem.LedgerTextRole
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.LedgerToggleRow
import app.ledger.core.designsystem.LedgerTopAppBar
import app.ledger.core.designsystem.LedgerTopAppBarVariant
import app.ledger.core.designsystem.ThemeMode
import app.ledger.finance.application.WidgetQuickDirection
import app.ledger.finance.application.WidgetQuickTarget
import app.ledger.finance.application.WidgetQuickTargetKind
import app.ledger.finance.application.WidgetSnapshotBundle
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Currency
import java.util.Locale

class WidgetConfigurationActivity : ComponentActivity() {
    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        appWidgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED)
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        lifecycleScope.launch {
            val localizedContext = this@WidgetConfigurationActivity.withLanguageTag(LedgerWidgetRuntime.languageTag())
            val ledgerDateFormat = LedgerWidgetRuntime.dateFormat().toLedgerDateFormat()
            setContent {
                CompositionLocalProvider(
                    LocalContext provides localizedContext,
                    LocalConfiguration provides localizedContext.resources.configuration,
                ) {
                    val darkTheme = isSystemInDarkTheme()
                    SideEffect {
                        enableEdgeToEdge(
                            statusBarStyle = if (darkTheme) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
                            navigationBarStyle = if (darkTheme) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
                        )
                    }
                    LedgerTheme(
                        ThemeMode.FOLLOW_SYSTEM,
                        dynamicColor = false,
                        reduceMotion = false,
                        ledgerDateFormat = ledgerDateFormat,
                    ) {
                        WidgetConfigurationFlow(
                            appWidgetId = appWidgetId,
                            onCancel = ::finish,
                            onSave = ::save,
                        )
                    }
                }
            }
        }
    }

    private fun save(configuration: LedgerWidgetConfiguration) {
        lifecycleScope.launch {
            LedgerWidgetRuntime.saveConfiguration(configuration)
            setResult(
                RESULT_OK,
                Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
            )
            runCatching {
                val glanceId = GlanceAppWidgetManager(this@WidgetConfigurationActivity).getGlanceIdBy(appWidgetId)
                LedgerGlanceWidget().update(this@WidgetConfigurationActivity, glanceId)
            }
            finish()
        }
    }
}

private enum class WidgetConfigurationStep { TYPE, DATA, PRIVACY }

private data class WidgetConfigurationData(
    val bookId: StableId,
    val bundle: WidgetSnapshotBundle,
    val quickTargets: List<WidgetQuickTarget>,
)

private data class WidgetSelection(
    val id: StableId,
    val label: String,
    val quickKind: WidgetQuickTargetKind? = null,
    val quickDirection: WidgetQuickDirection? = null,
)

@Composable
internal fun WidgetConfigurationFlow(
    appWidgetId: Int,
    onCancel: () -> Unit,
    onSave: (LedgerWidgetConfiguration) -> Unit,
) {
    var step by rememberSaveable { mutableStateOf(WidgetConfigurationStep.TYPE) }
    var selectedType by rememberSaveable { mutableStateOf<LedgerWidgetType?>(null) }
    var selectionId by rememberSaveable { mutableStateOf<String?>(null) }
    var revealAmounts by rememberSaveable { mutableStateOf(false) }
    var data by remember { mutableStateOf<WidgetConfigurationData?>(null) }
    var loadFinished by remember { mutableStateOf(false) }
    val selection = selectedType?.let { type -> data?.let(type::selections) }
        ?.singleOrNull { it.id.toString() == selectionId }
    LaunchedEffect(Unit) {
        val bookId = LedgerWidgetRuntime.activeBookId()
        if (bookId != null) {
            val bundle = LedgerWidgetRuntime.bundle(bookId)
            if (bundle != null) {
                data = WidgetConfigurationData(bookId, bundle, LedgerWidgetRuntime.quickTargets(bookId))
            }
        }
        loadFinished = true
    }
    LedgerScaffold(
        Modifier.fillMaxSize(),
        topBar = {
            LedgerTopAppBar(
                title = stringResource(step.titleResource()),
                variant = LedgerTopAppBarVariant.BACK,
                onNavigation = {
                    when (step) {
                        WidgetConfigurationStep.TYPE -> onCancel()
                        WidgetConfigurationStep.DATA -> step = WidgetConfigurationStep.TYPE
                        WidgetConfigurationStep.PRIVACY -> step = WidgetConfigurationStep.DATA
                    }
                },
            )
        },
    ) { padding ->
        if (!loadFinished) {
            LedgerLoadingState(Modifier.padding(padding))
        } else if (data == null) {
            LedgerEmptyState(
                stringResource(R.string.widget_locked_title),
                stringResource(R.string.widget_locked),
                stringResource(R.string.widget_close),
                onCancel,
                Modifier.padding(padding),
            )
        } else {
            when (step) {
                WidgetConfigurationStep.TYPE -> WidgetTypeGrid(
                    onSelected = {
                        selectedType = it
                        selectionId = null
                        step = WidgetConfigurationStep.DATA
                    },
                    Modifier.padding(padding),
                )
                WidgetConfigurationStep.DATA -> WidgetDataSelector(
                    requireNotNull(selectedType),
                    requireNotNull(data),
                    selection,
                    onSelection = { selectionId = it.id.toString() },
                    onNext = { step = WidgetConfigurationStep.PRIVACY },
                    onBack = { step = WidgetConfigurationStep.TYPE },
                    modifier = Modifier.padding(padding),
                )
                WidgetConfigurationStep.PRIVACY -> WidgetPrivacy(
                    requireNotNull(selectedType),
                    requireNotNull(data),
                    selection,
                    revealAmounts,
                    onRevealChanged = { revealAmounts = it },
                    onSave = {
                        val selected = selection
                        onSave(
                            LedgerWidgetConfiguration(
                                appWidgetId,
                                requireNotNull(data).bookId,
                                requireNotNull(selectedType),
                                selected?.id,
                                selected?.quickKind,
                                selected?.quickDirection,
                                revealAmounts,
                            ),
                        )
                    },
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }
}

@Composable
private fun WidgetTypeGrid(
    onSelected: (LedgerWidgetType) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier.fillMaxSize().padding(LedgerTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm),
    ) {
        items(LedgerWidgetType.entries) { type ->
            LedgerCard(Modifier.fillMaxWidth(), onClick = { onSelected(type) }) {
                Column(Modifier.fillMaxWidth().padding(LedgerTheme.spacing.sm)) {
                    LedgerText(stringResource(type.titleResource()), LedgerTextRole.SECTION)
                    LedgerText(stringResource(type.descriptionResource()), LedgerTextRole.SUPPORTING)
                }
            }
        }
    }
}

@Composable
private fun WidgetDataSelector(
    type: LedgerWidgetType,
    data: WidgetConfigurationData,
    selected: WidgetSelection?,
    onSelection: (WidgetSelection) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selections = type.selections(data)
    val selectionRequired = type.requiresSelection()
    if (selectionRequired && selections.isEmpty()) {
        LedgerEmptyState(
            stringResource(R.string.widget_no_eligible_title),
            stringResource(R.string.widget_no_data),
            stringResource(R.string.widget_back),
            onBack,
            modifier,
        )
        return
    }
    LazyColumn(
        modifier.fillMaxSize().padding(LedgerTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm),
    ) {
        item {
            WidgetContentPreview(type, data, selected, revealAmounts = false)
        }
        if (!selectionRequired) {
            item { LedgerBanner(stringResource(R.string.widget_no_selection_needed), LedgerBannerVariant.INFO) }
        }
        items(selections, key = { it.id.toString() }) { option ->
            LedgerChoiceRow(option.label, selected?.id == option.id, { onSelection(option) })
        }
        item {
            LedgerButton(
                stringResource(R.string.widget_next),
                onNext,
                Modifier.fillMaxWidth(),
                enabled = !selectionRequired || selected != null,
            )
        }
    }
}

@Composable
private fun WidgetPrivacy(
    type: LedgerWidgetType,
    data: WidgetConfigurationData,
    selection: WidgetSelection?,
    revealAmounts: Boolean,
    onRevealChanged: (Boolean) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier.fillMaxSize().padding(LedgerTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm),
    ) {
        item { LedgerBanner(stringResource(R.string.widget_privacy_boundary), LedgerBannerVariant.WARNING) }
        item {
            LedgerToggleRow(
                stringResource(R.string.widget_reveal_amounts),
                revealAmounts,
                onRevealChanged,
                supportingText = stringResource(R.string.widget_reveal_amounts_supporting),
                enabled = type != LedgerWidgetType.QUICK_ENTRY,
            )
        }
        item {
            WidgetContentPreview(type, data, selection, revealAmounts)
        }
        item {
            LedgerButton(stringResource(R.string.widget_save), onSave, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun WidgetContentPreview(
    type: LedgerWidgetType,
    data: WidgetConfigurationData,
    selection: WidgetSelection?,
    revealAmounts: Boolean,
) {
    val book = data.bundle.book
    val locale = LocalConfiguration.current.locales[0]
    val amountText: (Long, String) -> String = { minor, currency -> previewAmount(minor, currency, revealAmounts, locale) }
    LedgerCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(LedgerTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs),
        ) {
            LedgerText(stringResource(R.string.widget_preview), LedgerTextRole.SECTION)
            LedgerText(stringResource(type.titleResource()), LedgerTextRole.SUPPORTING)
            when (type) {
                LedgerWidgetType.QUICK_ENTRY -> {
                    LedgerText(selection?.label ?: stringResource(R.string.widget_no_data), LedgerTextRole.BODY)
                    LedgerText(stringResource(R.string.widget_open_full_form), LedgerTextRole.SUPPORTING)
                }
                LedgerWidgetType.ACCOUNT -> data.bundle.accounts.singleOrNull { it.accountId == selection?.id }?.let { account ->
                    LedgerText(account.displayName, LedgerTextRole.BODY)
                    LedgerText(amountText(account.balanceMinor, account.currency), LedgerTextRole.DISPLAY)
                    LedgerText(
                        stringResource(R.string.widget_available_value, amountText(account.availableMinor, account.currency)),
                        LedgerTextRole.SUPPORTING,
                    )
                } ?: LedgerText(stringResource(R.string.widget_no_data), LedgerTextRole.BODY)
                LedgerWidgetType.CREDIT_CARD -> data.bundle.creditAccounts.singleOrNull { it.accountId == selection?.id }?.let { credit ->
                    LedgerText(credit.displayName, LedgerTextRole.BODY)
                    LedgerText(amountText(credit.statementRemainingMinor ?: credit.debtMinor, credit.currency), LedgerTextRole.DISPLAY)
                    credit.statementDueDate?.let { due ->
                        LedgerText(stringResource(R.string.widget_due_date_value, due.widgetDate(locale)), LedgerTextRole.SUPPORTING)
                    }
                } ?: LedgerText(stringResource(R.string.widget_no_data), LedgerTextRole.BODY)
                LedgerWidgetType.GOAL -> data.bundle.goals.singleOrNull { it.goalId == selection?.id }?.let { goal ->
                    LedgerText(goal.displayName, LedgerTextRole.BODY)
                    LedgerText(amountText(goal.balanceMinor, goal.currency), LedgerTextRole.DISPLAY)
                    val progress = if (goal.targetMinor > 0L) {
                        NumberFormat.getPercentInstance(locale).format(goal.balanceMinor.toDouble() / goal.targetMinor.toDouble())
                    } else {
                        stringResource(R.string.widget_no_data)
                    }
                    LedgerText(stringResource(R.string.widget_progress_value, progress), LedgerTextRole.SUPPORTING)
                } ?: LedgerText(stringResource(R.string.widget_no_data), LedgerTextRole.BODY)
                LedgerWidgetType.MONTH_CONSUMPTION -> book?.let {
                    LedgerText(amountText(it.monthConsumptionBaseMinor, it.baseCurrency), LedgerTextRole.DISPLAY)
                    LedgerText(
                        stringResource(
                            R.string.widget_previous_month_comparison,
                            amountText(it.monthConsumptionBaseMinor - it.previousMonthConsumptionBaseMinor, it.baseCurrency),
                        ),
                        LedgerTextRole.SUPPORTING,
                    )
                }
                LedgerWidgetType.MONTH_BUDGET -> book?.let {
                    LedgerText(amountText(it.monthBudgetAvailableBaseMinor ?: 0L, it.baseCurrency), LedgerTextRole.DISPLAY)
                    LedgerText(stringResource(R.string.widget_used_value, amountText(it.monthBudgetUsedBaseMinor ?: 0L, it.baseCurrency)), LedgerTextRole.SUPPORTING)
                }
                LedgerWidgetType.TODAY_AVAILABLE -> book?.let {
                    LedgerText(amountText(it.todayAvailableBaseMinor ?: 0L, it.baseCurrency), LedgerTextRole.DISPLAY)
                }
                LedgerWidgetType.CORE_NET_ASSETS -> book?.let {
                    LedgerText(amountText(it.coreNetFinancialAssetsBaseMinor, it.baseCurrency), LedgerTextRole.DISPLAY)
                    LedgerText(
                        stringResource(R.string.widget_snapshot_change, amountText(it.coreNetFinancialAssetsBaseMinor - it.previousCoreNetFinancialAssetsBaseMinor, it.baseCurrency)),
                        LedgerTextRole.SUPPORTING,
                    )
                }
                LedgerWidgetType.FINANCIAL_OVERVIEW -> book?.let {
                    LedgerText(amountText(it.coreNetFinancialAssetsBaseMinor, it.baseCurrency), LedgerTextRole.DISPLAY)
                    LedgerText(
                        stringResource(
                            R.string.widget_overview_line,
                            amountText(it.monthConsumptionBaseMinor, it.baseCurrency),
                            amountText(it.todayAvailableBaseMinor ?: 0L, it.baseCurrency),
                        ),
                        LedgerTextRole.SUPPORTING,
                    )
                }
            }
        }
    }
}

private fun previewAmount(minor: Long, currencyCode: String, reveal: Boolean, locale: Locale): String {
    if (!reveal) return "••••"
    return runCatching {
        val currency = Currency.getInstance(currencyCode)
        val digits = currency.defaultFractionDigits.coerceAtLeast(0)
        NumberFormat.getCurrencyInstance(locale).apply { this.currency = currency }.format(
            BigDecimal.valueOf(minor).movePointLeft(digits).setScale(digits, RoundingMode.UNNECESSARY),
        )
    }.getOrElse { "—" }
}

private fun Int.widgetDate(locale: Locale): String {
    val date = LocalDate.of(this / 10_000, this / 100 % 100, this % 100)
    return LedgerDateFormatterRuntime.formatter(locale).format(date)
}

private fun String.toLedgerDateFormat(): LedgerDateFormat = when (this) {
    "DATE_FORMAT_YEAR_MONTH_DAY" -> LedgerDateFormat.YEAR_MONTH_DAY
    "DATE_FORMAT_DAY_MONTH_YEAR" -> LedgerDateFormat.DAY_MONTH_YEAR
    "DATE_FORMAT_MONTH_DAY_YEAR" -> LedgerDateFormat.MONTH_DAY_YEAR
    else -> LedgerDateFormat.LOCALE_DEFAULT
}

private fun LedgerWidgetType.requiresSelection(): Boolean = this in setOf(
    LedgerWidgetType.QUICK_ENTRY,
    LedgerWidgetType.ACCOUNT,
    LedgerWidgetType.CREDIT_CARD,
    LedgerWidgetType.GOAL,
)

private fun LedgerWidgetType.selections(data: WidgetConfigurationData): List<WidgetSelection> = when (this) {
    LedgerWidgetType.QUICK_ENTRY -> data.quickTargets.map { WidgetSelection(it.id, it.displayName, it.kind, it.direction) }
    LedgerWidgetType.ACCOUNT -> data.bundle.accounts.map { WidgetSelection(it.accountId, it.displayName) }
    LedgerWidgetType.CREDIT_CARD -> data.bundle.creditAccounts.map { WidgetSelection(it.accountId, it.displayName) }
    LedgerWidgetType.GOAL -> data.bundle.goals.map { WidgetSelection(it.goalId, it.displayName) }
    else -> emptyList()
}

private fun WidgetConfigurationStep.titleResource(): Int = when (this) {
    WidgetConfigurationStep.TYPE -> R.string.widget_choose_type
    WidgetConfigurationStep.DATA -> R.string.widget_choose_data
    WidgetConfigurationStep.PRIVACY -> R.string.widget_privacy
}

internal fun LedgerWidgetType.titleResource(): Int = when (this) {
    LedgerWidgetType.QUICK_ENTRY -> R.string.widget_quick_entry
    LedgerWidgetType.MONTH_CONSUMPTION -> R.string.widget_month_consumption
    LedgerWidgetType.MONTH_BUDGET -> R.string.widget_month_budget
    LedgerWidgetType.TODAY_AVAILABLE -> R.string.widget_today_available
    LedgerWidgetType.ACCOUNT -> R.string.widget_account
    LedgerWidgetType.CORE_NET_ASSETS -> R.string.widget_core_net_assets
    LedgerWidgetType.CREDIT_CARD -> R.string.widget_credit_card
    LedgerWidgetType.GOAL -> R.string.widget_goal
    LedgerWidgetType.FINANCIAL_OVERVIEW -> R.string.widget_financial_overview
}

private fun LedgerWidgetType.descriptionResource(): Int = when (this) {
    LedgerWidgetType.QUICK_ENTRY -> R.string.widget_quick_entry_description
    LedgerWidgetType.MONTH_CONSUMPTION -> R.string.widget_month_consumption_description
    LedgerWidgetType.MONTH_BUDGET -> R.string.widget_month_budget_description
    LedgerWidgetType.TODAY_AVAILABLE -> R.string.widget_today_available_description
    LedgerWidgetType.ACCOUNT -> R.string.widget_account_description
    LedgerWidgetType.CORE_NET_ASSETS -> R.string.widget_core_net_assets_description
    LedgerWidgetType.CREDIT_CARD -> R.string.widget_credit_card_description
    LedgerWidgetType.GOAL -> R.string.widget_goal_description
    LedgerWidgetType.FINANCIAL_OVERVIEW -> R.string.widget_financial_overview_description
}
