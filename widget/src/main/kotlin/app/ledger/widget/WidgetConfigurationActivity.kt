@file:Suppress("FunctionNaming", "LongMethod", "LongParameterList", "ktlint:standard:function-naming")

package app.ledger.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
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

class WidgetConfigurationActivity : ComponentActivity() {
    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            setContent {
                CompositionLocalProvider(
                    LocalContext provides localizedContext,
                    LocalConfiguration provides localizedContext.resources.configuration,
                ) {
                    LedgerTheme(ThemeMode.FOLLOW_SYSTEM, dynamicColor = false, reduceMotion = false) {
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
            val glanceId = GlanceAppWidgetManager(this@WidgetConfigurationActivity).getGlanceIdBy(appWidgetId)
            LedgerGlanceWidget().update(this@WidgetConfigurationActivity, glanceId)
            setResult(
                RESULT_OK,
                Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
            )
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
    var step by remember { mutableStateOf(WidgetConfigurationStep.TYPE) }
    var selectedType by remember { mutableStateOf<LedgerWidgetType?>(null) }
    var selection by remember { mutableStateOf<WidgetSelection?>(null) }
    var revealAmounts by remember { mutableStateOf(false) }
    var data by remember { mutableStateOf<WidgetConfigurationData?>(null) }
    var loadFinished by remember { mutableStateOf(false) }
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
                        selection = null
                        step = WidgetConfigurationStep.DATA
                    },
                    Modifier.padding(padding),
                )
                WidgetConfigurationStep.DATA -> WidgetDataSelector(
                    requireNotNull(selectedType),
                    requireNotNull(data),
                    selection,
                    onSelection = { selection = it },
                    onNext = { step = WidgetConfigurationStep.PRIVACY },
                    onBack = { step = WidgetConfigurationStep.TYPE },
                    modifier = Modifier.padding(padding),
                )
                WidgetConfigurationStep.PRIVACY -> WidgetPrivacy(
                    requireNotNull(selectedType),
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
            LedgerCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(LedgerTheme.spacing.sm)) {
                    LedgerText(stringResource(R.string.widget_preview), LedgerTextRole.SECTION)
                    LedgerText(stringResource(type.titleResource()), LedgerTextRole.BODY)
                    LedgerText("••••", LedgerTextRole.DISPLAY)
                }
            }
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
            LedgerText(
                selection?.label ?: stringResource(type.titleResource()),
                LedgerTextRole.SECTION,
            )
            LedgerText(
                if (revealAmounts) stringResource(R.string.widget_preview_revealed) else "••••",
                LedgerTextRole.DISPLAY,
            )
        }
        item {
            LedgerButton(stringResource(R.string.widget_save), onSave, Modifier.fillMaxWidth())
        }
    }
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
