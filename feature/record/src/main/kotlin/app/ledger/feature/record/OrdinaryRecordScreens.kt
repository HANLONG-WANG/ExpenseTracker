@file:Suppress(
    "ktlint:standard:function-naming",
    "FunctionNaming",
    "LongMethod",
    "LongParameterList",
    "TooManyFunctions",
    "MagicNumber",
    "MaxLineLength",
    "CyclomaticComplexMethod",
    "MatchingDeclarationName",
)

package app.ledger.feature.record

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import app.ledger.core.common.StableId
import app.ledger.core.designsystem.AttachmentField
import app.ledger.core.designsystem.AttachmentTransferState
import app.ledger.core.designsystem.AttachmentUiModel
import app.ledger.core.designsystem.CategoryGrid
import app.ledger.core.designsystem.CategoryGroupUiModel
import app.ledger.core.designsystem.CategoryTileUiModel
import app.ledger.core.designsystem.DateTimeZoneField
import app.ledger.core.designsystem.FormSection
import app.ledger.core.designsystem.LedgerBanner
import app.ledger.core.designsystem.LedgerBannerVariant
import app.ledger.core.designsystem.LedgerButton
import app.ledger.core.designsystem.LedgerButtonVariant
import app.ledger.core.designsystem.LedgerCard
import app.ledger.core.designsystem.LedgerChoiceRow
import app.ledger.core.designsystem.LedgerDateTimePickerFlow
import app.ledger.core.designsystem.LedgerEmptyState
import app.ledger.core.designsystem.LedgerErrorState
import app.ledger.core.designsystem.LedgerIcon
import app.ledger.core.designsystem.LedgerIconButton
import app.ledger.core.designsystem.LedgerReferenceDisplayDefaults
import app.ledger.core.designsystem.LedgerTabRow
import app.ledger.core.designsystem.LedgerTestTags
import app.ledger.core.designsystem.LedgerText
import app.ledger.core.designsystem.LedgerTextField
import app.ledger.core.designsystem.LedgerTextRole
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.LedgerToggleRow
import app.ledger.core.designsystem.LocationField
import app.ledger.core.designsystem.LocationFieldState
import app.ledger.core.designsystem.MoneyExpressionField
import app.ledger.core.designsystem.ReferenceDataRow
import app.ledger.core.designsystem.ReferenceDataRowUiModel
import app.ledger.core.designsystem.SearchField
import app.ledger.core.designsystem.SelectorField
import app.ledger.core.designsystem.UiErrorCode
import app.ledger.core.designsystem.ValidationItemUiModel
import app.ledger.core.designsystem.ValidationSummary
import app.ledger.finance.application.CategoryReferenceView
import app.ledger.finance.application.OrdinaryDirection
import app.ledger.finance.application.OrdinaryTransactionEntrySnapshot
import app.ledger.finance.domain.CategoryStatus
import app.ledger.finance.domain.EntityStatus
import app.ledger.finance.domain.SettlementChargeDistribution
import app.ledger.finance.domain.SettlementRoundingRule
import app.ledger.finance.domain.SettlementSplitMethod
import app.ledger.finance.domain.StatisticalNature
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

public data class OrdinaryRecordScreenUiState(
    val loadState: OrdinaryRecordLoadState,
    val submitting: Boolean = false,
)

public sealed interface OrdinaryRecordScreenAction {
    public data object Retry : OrdinaryRecordScreenAction
    public data class SelectTab(val tab: RecordTab) : OrdinaryRecordScreenAction
    public data class Search(val query: String) : OrdinaryRecordScreenAction
    public data class Navigate(
        val screenId: String,
        val stableArguments: Map<String, StableId>,
        val enumArguments: Map<String, String>,
    ) : OrdinaryRecordScreenAction
    public data class OpenEditor(
        val mode: RecordEditorMode,
        val direction: OrdinaryDirection,
        val categoryId: StableId?,
        val sourceId: StableId?,
    ) : OrdinaryRecordScreenAction
    public data class Expression(val value: String) : OrdinaryRecordScreenAction
    public data class Operator(val value: String) : OrdinaryRecordScreenAction
    public data class SelectCategory(val categoryId: StableId) : OrdinaryRecordScreenAction
    public data class SelectAccount(val accountId: StableId) : OrdinaryRecordScreenAction
    public data class SelectCard(val cardId: StableId?) : OrdinaryRecordScreenAction
    public data class SelectReference(val field: RecordField, val id: StableId?) : OrdinaryRecordScreenAction
    public data class Note(val value: String) : OrdinaryRecordScreenAction
    public data class SettlementEnabled(val enabled: Boolean) : OrdinaryRecordScreenAction
    public data class SettlementActivity(val activityId: StableId) : OrdinaryRecordScreenAction
    public data class SettlementPayer(val participantId: StableId) : OrdinaryRecordScreenAction
    public data class SettlementSplitMethodChanged(val method: SettlementSplitMethod) : OrdinaryRecordScreenAction
    public data class SettlementChargeDistributionChanged(val distribution: SettlementChargeDistribution) : OrdinaryRecordScreenAction
    public data class SettlementRoundingRuleChanged(val rule: SettlementRoundingRule) : OrdinaryRecordScreenAction
    public data class SettlementParticipantIncluded(val participantId: StableId) : OrdinaryRecordScreenAction
    public data class SettlementAllocationInput(val participantId: StableId, val value: String) : OrdinaryRecordScreenAction
    public data class SettlementChargeInput(val participantId: StableId, val value: String) : OrdinaryRecordScreenAction
    public data class SettlementTax(val value: String) : OrdinaryRecordScreenAction
    public data class SettlementServiceFee(val value: String) : OrdinaryRecordScreenAction
    public data class OccurredAt(val dateMillis: Long, val hour: Int, val minute: Int) : OrdinaryRecordScreenAction
    public data class ManualLocation(val latitudeE7: Int, val longitudeE7: Int) : OrdinaryRecordScreenAction
    public data object AddAttachment : OrdinaryRecordScreenAction
    public data class ReuseAttachment(val attachmentId: StableId) : OrdinaryRecordScreenAction
    public data class OpenAttachment(val attachmentId: StableId) : OrdinaryRecordScreenAction
    public data class CancelAttachment(val index: Int) : OrdinaryRecordScreenAction
    public data object Save : OrdinaryRecordScreenAction
    public data object UnsavedDiscard : OrdinaryRecordScreenAction
    public data object UnsavedKeepEditing : OrdinaryRecordScreenAction
    public data object ReloadConflict : OrdinaryRecordScreenAction
    public data object CancelConflict : OrdinaryRecordScreenAction
}

internal class OrdinaryRecordActions(
    val onRetry: () -> Unit,
    val onTab: (RecordTab) -> Unit,
    val onSearch: (String) -> Unit,
    val onNavigate: (String, Map<String, StableId>, Map<String, String>) -> Unit,
    val onOpenEditor: (RecordEditorMode, OrdinaryDirection, StableId?, StableId?) -> Unit,
    val onExpression: (String) -> Unit,
    val onOperator: (String) -> Unit,
    val onSelectCategory: (StableId) -> Unit,
    val onSelectAccount: (StableId) -> Unit,
    val onSelectCard: (StableId?) -> Unit,
    val onSelectReference: (RecordField, StableId?) -> Unit,
    val onNote: (String) -> Unit,
    val onSettlementEnabled: (Boolean) -> Unit,
    val onSettlementActivity: (StableId) -> Unit,
    val onSettlementPayer: (StableId) -> Unit,
    val onSettlementSplitMethod: (SettlementSplitMethod) -> Unit,
    val onSettlementChargeDistribution: (SettlementChargeDistribution) -> Unit,
    val onSettlementRoundingRule: (SettlementRoundingRule) -> Unit,
    val onSettlementParticipantIncluded: (StableId) -> Unit,
    val onSettlementAllocationInput: (StableId, String) -> Unit,
    val onSettlementChargeInput: (StableId, String) -> Unit,
    val onSettlementTax: (String) -> Unit,
    val onSettlementServiceFee: (String) -> Unit,
    val onOccurredAt: (dateMillis: Long, hour: Int, minute: Int) -> Unit,
    val onManualLocation: (latitudeE7: Int, longitudeE7: Int) -> Unit,
    val onAddAttachment: () -> Unit,
    val onReuseAttachment: (StableId) -> Unit,
    val onOpenAttachment: (StableId) -> Unit,
    val onCancelAttachment: (index: Int) -> Unit,
    val onSave: () -> Unit,
    val onUnsavedDiscard: () -> Unit,
    val onUnsavedKeepEditing: () -> Unit,
    val onReloadConflict: () -> Unit,
    val onCancelConflict: () -> Unit,
)

internal fun ordinaryRecordActions(onAction: (OrdinaryRecordScreenAction) -> Unit): OrdinaryRecordActions = OrdinaryRecordActions(
    onRetry = { onAction(OrdinaryRecordScreenAction.Retry) },
    onTab = { onAction(OrdinaryRecordScreenAction.SelectTab(it)) },
    onSearch = { onAction(OrdinaryRecordScreenAction.Search(it)) },
    onNavigate = { screenId, stable, enums -> onAction(OrdinaryRecordScreenAction.Navigate(screenId, stable, enums)) },
    onOpenEditor = { mode, direction, categoryId, sourceId ->
        onAction(OrdinaryRecordScreenAction.OpenEditor(mode, direction, categoryId, sourceId))
    },
    onExpression = { onAction(OrdinaryRecordScreenAction.Expression(it)) },
    onOperator = { onAction(OrdinaryRecordScreenAction.Operator(it)) },
    onSelectCategory = { onAction(OrdinaryRecordScreenAction.SelectCategory(it)) },
    onSelectAccount = { onAction(OrdinaryRecordScreenAction.SelectAccount(it)) },
    onSelectCard = { onAction(OrdinaryRecordScreenAction.SelectCard(it)) },
    onSelectReference = { field, id -> onAction(OrdinaryRecordScreenAction.SelectReference(field, id)) },
    onNote = { onAction(OrdinaryRecordScreenAction.Note(it)) },
    onSettlementEnabled = { onAction(OrdinaryRecordScreenAction.SettlementEnabled(it)) },
    onSettlementActivity = { onAction(OrdinaryRecordScreenAction.SettlementActivity(it)) },
    onSettlementPayer = { onAction(OrdinaryRecordScreenAction.SettlementPayer(it)) },
    onSettlementSplitMethod = { onAction(OrdinaryRecordScreenAction.SettlementSplitMethodChanged(it)) },
    onSettlementChargeDistribution = { onAction(OrdinaryRecordScreenAction.SettlementChargeDistributionChanged(it)) },
    onSettlementRoundingRule = { onAction(OrdinaryRecordScreenAction.SettlementRoundingRuleChanged(it)) },
    onSettlementParticipantIncluded = { onAction(OrdinaryRecordScreenAction.SettlementParticipantIncluded(it)) },
    onSettlementAllocationInput = { id, value -> onAction(OrdinaryRecordScreenAction.SettlementAllocationInput(id, value)) },
    onSettlementChargeInput = { id, value -> onAction(OrdinaryRecordScreenAction.SettlementChargeInput(id, value)) },
    onSettlementTax = { onAction(OrdinaryRecordScreenAction.SettlementTax(it)) },
    onSettlementServiceFee = { onAction(OrdinaryRecordScreenAction.SettlementServiceFee(it)) },
    onOccurredAt = { dateMillis, hour, minute -> onAction(OrdinaryRecordScreenAction.OccurredAt(dateMillis, hour, minute)) },
    onManualLocation = { latitudeE7, longitudeE7 -> onAction(OrdinaryRecordScreenAction.ManualLocation(latitudeE7, longitudeE7)) },
    onAddAttachment = { onAction(OrdinaryRecordScreenAction.AddAttachment) },
    onReuseAttachment = { onAction(OrdinaryRecordScreenAction.ReuseAttachment(it)) },
    onOpenAttachment = { onAction(OrdinaryRecordScreenAction.OpenAttachment(it)) },
    onCancelAttachment = { onAction(OrdinaryRecordScreenAction.CancelAttachment(it)) },
    onSave = { onAction(OrdinaryRecordScreenAction.Save) },
    onUnsavedDiscard = { onAction(OrdinaryRecordScreenAction.UnsavedDiscard) },
    onUnsavedKeepEditing = { onAction(OrdinaryRecordScreenAction.UnsavedKeepEditing) },
    onReloadConflict = { onAction(OrdinaryRecordScreenAction.ReloadConflict) },
    onCancelConflict = { onAction(OrdinaryRecordScreenAction.CancelConflict) },
)

public data class RecordLocationMapPoint(
    val id: StableId,
    val latitudeE7: Int,
    val longitudeE7: Int,
    val selected: Boolean,
)

public data class RecordLocationMapRow(
    val id: StableId,
    val label: String,
    val coordinates: String,
)

public data class RecordLocationMapModel(
    val unavailable: Boolean,
    val summary: String,
    val caption: String,
    val nameHeader: String,
    val coordinateHeader: String,
    val showListLabel: String,
    val hideListLabel: String,
    val points: List<RecordLocationMapPoint>,
    val rows: List<RecordLocationMapRow>,
)

@Composable
public fun OrdinaryRecordDestination(
    screenId: String,
    uiState: OrdinaryRecordScreenUiState,
    onAction: (OrdinaryRecordScreenAction) -> Unit,
    modifier: Modifier = Modifier,
    locationMap: @Composable (
        model: RecordLocationMapModel,
        onPointSelected: (StableId) -> Unit,
        onCoordinateSelected: (Int, Int) -> Unit,
        onFailure: () -> Unit,
    ) -> Unit = { model, onPointSelected, _, _ ->
        Column(verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
            model.rows.forEach { row -> LedgerChoiceRow(row.label, model.points.singleOrNull { it.id == row.id }?.selected == true, { onPointSelected(row.id) }, supportingText = row.coordinates) }
        }
    },
) {
    val state = uiState.loadState
    val actions = ordinaryRecordActions(onAction)
    Box(modifier.fillMaxSize().testTag(LedgerTestTags.RECORD_ROOT)) {
        when (state) {
            OrdinaryRecordLoadState.Loading -> LedgerText(stringResource(R.string.record_loading), LedgerTextRole.SUPPORTING)
            is OrdinaryRecordLoadState.Failure -> LedgerErrorState(UiErrorCode(state.code), stringResource(R.string.record_load_failed), actions.onRetry)
            is OrdinaryRecordLoadState.Content -> RecordContent(screenId, state, actions, locationMap)
        }
    }
}

/** Convenience for previews/tests; production callers should collect [OrdinaryRecordScreenUiState]. */
@Composable
public fun OrdinaryRecordDestination(
    screenId: String,
    state: OrdinaryRecordLoadState,
    onAction: (OrdinaryRecordScreenAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    OrdinaryRecordDestination(screenId, OrdinaryRecordScreenUiState(state), onAction, modifier)
}

@Composable
private fun RecordContent(
    screenId: String,
    state: OrdinaryRecordLoadState.Content,
    actions: OrdinaryRecordActions,
    locationMap: @Composable (RecordLocationMapModel, (StableId) -> Unit, (Int, Int) -> Unit, () -> Unit) -> Unit,
) {
    when (screenId) {
        "REC-001" -> CategoryFirstHome(state, actions)
        "REC-002" -> CategorySearch(state, actions)
        "REC-003" -> state.editor?.let { OrdinaryEditor(it, actions) } ?: LedgerText(stringResource(R.string.record_loading), LedgerTextRole.SUPPORTING)
        "REC-004" -> state.editor?.let { CategoryPicker(it, actions) } ?: CategoryFirstHome(state, actions)
        "REC-005" -> state.editor?.let { AccountPicker(it, actions) } ?: LedgerText(stringResource(R.string.record_loading), LedgerTextRole.SUPPORTING)
        "REC-006" -> state.editor?.let { CardPicker(it, actions) } ?: LedgerText(stringResource(R.string.record_loading), LedgerTextRole.SUPPORTING)
        "REC-007" -> state.editor?.let { MerchantPicker(it, actions) } ?: LedgerText(stringResource(R.string.record_loading), LedgerTextRole.SUPPORTING)
        "REC-008" -> state.editor?.let { ProjectPicker(it, actions) } ?: LedgerText(stringResource(R.string.record_loading), LedgerTextRole.SUPPORTING)
        "REC-009" -> state.editor?.let { LocationPicker(it, actions, locationMap) } ?: LedgerText(stringResource(R.string.record_loading), LedgerTextRole.SUPPORTING)
        "REC-010" -> state.editor?.let { AttachmentPicker(it, actions) } ?: LedgerText(stringResource(R.string.record_loading), LedgerTextRole.SUPPORTING)
        "REC-011" -> state.editor?.let { SettlementAllocation(it, actions) } ?: LedgerText(stringResource(R.string.record_loading), LedgerTextRole.SUPPORTING)
        "REC-012" -> state.editor?.let { AdvancedSemantics(it) } ?: LedgerText(stringResource(R.string.record_loading), LedgerTextRole.SUPPORTING)
        "REC-026" -> QuickTemplatePicker(state, actions)
        else -> LedgerErrorState(UiErrorCode("RECORD_ROUTE_UNKNOWN"), stringResource(R.string.record_load_failed), actions.onRetry)
    }
}

@Composable
private fun CategoryFirstHome(state: OrdinaryRecordLoadState.Content, actions: OrdinaryRecordActions) {
    val labels = listOf(stringResource(R.string.record_tab_expense), stringResource(R.string.record_tab_income), stringResource(R.string.record_tab_other))
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        LedgerTabRow(state.tab.ordinal, labels, { actions.onTab(RecordTab.entries[it]) })
        if (state.tab == RecordTab.OTHER) {
            OtherTransactionCards(actions)
            return@Column
        }
        val direction = state.tab.toDirection()
        val templates = state.snapshot.templates.filter { it.direction == direction }.take(MAX_QUICK_TEMPLATES)
        if (templates.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                items(templates, key = { it.id.toString() }) { template ->
                    LedgerButton(template.name, { actions.onOpenEditor(RecordEditorMode.TEMPLATE, direction, template.categoryId, template.id) }, compact = true, variant = LedgerButtonVariant.TONAL)
                }
                item { LedgerButton(stringResource(R.string.record_all_templates), { actions.onNavigate("REC-026", emptyMap(), emptyMap()) }, compact = true, variant = LedgerButtonVariant.TEXT) }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            LedgerIconButton(LedgerIcon.SEARCH, stringResource(R.string.record_search_category), { actions.onNavigate("REC-002", emptyMap(), mapOf("direction" to direction.name)) })
        }
        val categories = activeCategories(state.snapshot, direction)
        if (categories.isEmpty()) {
            LedgerEmptyState(
                stringResource(R.string.record_no_categories),
                stringResource(R.string.record_no_categories_body),
                stringResource(if (direction == OrdinaryDirection.EXPENSE) R.string.record_create_expense_category else R.string.record_create_income_category),
                { actions.onNavigate("CAT-002", emptyMap(), mapOf("direction" to direction.name)) },
            )
        } else {
            CategoryGrid(
                groups = categoryGroups(categories),
                selectedStableKey = state.selectedCategoryId?.toString(),
                onSelect = { tile -> categories.single { it.id.toString() == tile.stableKey }.let { actions.onOpenEditor(RecordEditorMode.CREATE, direction, it.id, null) } },
                modifier = Modifier.fillMaxWidth().weight(1f),
                onCreate = { actions.onNavigate("CAT-002", emptyMap(), mapOf("direction" to direction.name)) },
                createLabel = stringResource(R.string.record_create_category),
            )
        }
    }
}

@Composable
private fun QuickTemplatePicker(state: OrdinaryRecordLoadState.Content, actions: OrdinaryRecordActions) {
    val templates = state.snapshot.templates.filter { it.name.contains(state.search, ignoreCase = true) }
    LazyColumn(
        Modifier.fillMaxSize().testTag(LedgerTestTags.AUTOMATION_TEMPLATE_PICKER),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm),
    ) {
        item {
            SearchField(
                value = state.search,
                onValueChange = actions.onSearch,
                placeholder = stringResource(R.string.record_template_search),
                onClear = { actions.onSearch("") },
            )
        }
        if (templates.isEmpty()) {
            item {
                LedgerEmptyState(
                    title = stringResource(R.string.record_template_empty),
                    explanation = stringResource(R.string.record_template_empty_body),
                    primaryAction = stringResource(R.string.record_template_create),
                    onPrimaryAction = { actions.onNavigate("AUT-003", emptyMap(), emptyMap()) },
                )
            }
        } else {
            items(templates, key = { it.id.toString() }) { template ->
                LedgerCard(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        actions.onOpenEditor(
                            RecordEditorMode.TEMPLATE,
                            template.direction,
                            template.categoryId,
                            template.id,
                        )
                    },
                ) {
                    Column(Modifier.fillMaxWidth().padding(LedgerTheme.spacing.sm)) {
                        LedgerText(template.name, LedgerTextRole.BODY)
                        template.amountExpression?.let { LedgerText(it, LedgerTextRole.SUPPORTING) }
                    }
                }
            }
        }
    }
}

@Composable
private fun OtherTransactionCards(actions: OrdinaryRecordActions) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
        OTHER_TRANSACTION_TARGETS.forEach { (target, label) -> LedgerButton(stringResource(label), { actions.onNavigate(target, emptyMap(), emptyMap()) }, variant = LedgerButtonVariant.TONAL) }
    }
}

internal val OTHER_TRANSACTION_TARGETS = listOf(
    "REC-013" to R.string.record_other_transfer,
    "REC-014" to R.string.record_other_credit_payment,
    "REC-015" to R.string.record_other_refund,
    "REC-017" to R.string.record_other_loan,
    "REC-020" to R.string.record_other_adjustment,
    "REC-021" to R.string.record_other_fx,
    "REC-023" to R.string.batch_entry_title,
)

@Composable
private fun CategorySearch(state: OrdinaryRecordLoadState.Content, actions: OrdinaryRecordActions) {
    val direction = if (state.tab == RecordTab.INCOME) OrdinaryDirection.INCOME else OrdinaryDirection.EXPENSE
    val filtered = activeCategories(state.snapshot, direction).filter { state.search.isBlank() || it.name.contains(state.search, ignoreCase = true) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        SearchField(state.search, actions.onSearch, placeholder = stringResource(R.string.record_search_category), onClear = { actions.onSearch("") })
        if (filtered.isEmpty()) {
            LedgerEmptyState(stringResource(R.string.record_search_empty), stringResource(R.string.record_search_empty_body), stringResource(R.string.record_create_category), { actions.onNavigate("CAT-002", emptyMap(), mapOf("direction" to direction.name)) })
        } else {
            LazyColumn {
                items(filtered, key = { it.id.toString() }) { category -> ReferenceDataRow(category.toRow(), { actions.onOpenEditor(RecordEditorMode.CREATE, direction, category.id, null) }) }
            }
        }
    }
}

@Composable
private fun OrdinaryEditor(state: OrdinaryRecordEditorState, actions: OrdinaryRecordActions) {
    var showDateTimePicker by remember { mutableStateOf(false) }
    val d = state.draft
    val refs = state.snapshot.references
    val category = refs.categories.singleOrNull { it.id == d.categoryId }
    val account = refs.accounts.singleOrNull { it.id == d.accountId }
    val card = refs.cards.singleOrNull { it.id == d.cardId }
    val merchant = refs.merchants.singleOrNull { it.id == d.merchantId }
    val project = state.snapshot.projects.singleOrNull { it.id == d.projectId }
    LazyColumn(
        Modifier.fillMaxSize().imePadding().testTag(LedgerTestTags.RECORD_EDITOR),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.md),
    ) {
        if (state.presentation == RecordEditorPresentation.SAVE_ERROR) item { LedgerBanner(stringResource(R.string.record_save_failed, state.sanitizedFailureCode.orEmpty()), LedgerBannerVariant.DANGER) }
        if (state.presentation == RecordEditorPresentation.REVISION_CONFLICT) item { RevisionConflict(actions) }
        if (state.errors.isNotEmpty()) {
            item {
                ValidationSummary(state.errors.map { ValidationItemUiModel("record_${it.field.name.lowercase()}", validationMessage(it.code)) }, {}, Modifier.testTag(LedgerTestTags.RECORD_VALIDATION))
            }
        }
        item { SelectorField(stringResource(R.string.record_field_category), category?.name ?: stringResource(R.string.record_not_selected), { actions.onNavigate("REC-004", d.categoryId?.let { mapOf("selectedId" to it) }.orEmpty(), mapOf("direction" to d.direction.name)) }, Modifier.testTag(LedgerTestTags.RECORD_CATEGORY)) }
        item { MoneyExpressionField(d.expression, d.normalizedExpression, d.result, actions.onExpression, currencyCode = d.currencyCode, errorText = state.errors.firstOrNull { it.field == RecordField.AMOUNT }?.let { validationMessage(it.code) }, roundingExplanation = stringResource(R.string.record_amount_rounding), onOperator = actions.onOperator) }
        item { SelectorField(stringResource(R.string.record_field_account), account?.name ?: stringResource(R.string.record_not_selected), { actions.onNavigate("REC-005", emptyMap(), emptyMap()) }, Modifier.testTag(LedgerTestTags.RECORD_ACCOUNT), supportingText = originText(d.origins[RecordField.ACCOUNT])) }
        item { SelectorField(stringResource(R.string.record_field_card), card?.displayName ?: stringResource(R.string.record_no_card), { actions.onNavigate("REC-006", d.accountId?.let { mapOf("accountId" to it) }.orEmpty(), emptyMap()) }, supportingText = originText(d.origins[RecordField.CARD]), enabled = d.accountId != null) }
        item { SelectorField(stringResource(R.string.record_field_merchant), merchant?.name ?: stringResource(R.string.record_not_selected), { actions.onNavigate("REC-007", emptyMap(), emptyMap()) }) }
        item {
            DateTimeZoneField(
                stringResource(R.string.record_field_time),
                DATE_TIME_FORMAT.format(d.occurredAt.atZone(d.zoneId)),
                d.zoneId.id,
                { showDateTimePicker = true },
            )
        }
        item { SelectorField(stringResource(R.string.record_field_project), project?.name ?: stringResource(R.string.record_not_selected), { actions.onNavigate("REC-008", emptyMap(), emptyMap()) }) }
        item {
            FormSection(stringResource(R.string.record_field_settlement), Modifier.testTag(LedgerTestTags.RECORD_SETTLEMENT), expanded = d.settlementEnabled) {
                LedgerToggleRow(stringResource(R.string.record_join_settlement), d.settlementEnabled, actions.onSettlementEnabled, supportingText = stringResource(R.string.record_settlement_collapsed_note))
                if (d.settlementEnabled) {
                    val activity = state.snapshot.settlementActivities.singleOrNull { it.id == d.settlementActivityId }
                    SelectorField(stringResource(R.string.record_settlement_activity), activity?.name ?: stringResource(R.string.record_not_selected), { actions.onNavigate("REC-011", d.settlementActivityId?.let { mapOf("activityId" to it) }.orEmpty(), emptyMap()) })
                }
            }
        }
        item { LocationField(if (d.locationRecordId == null) LocationFieldState.ReadyAtSave else LocationFieldState.ManuallyAdjusted, { actions.onNavigate("REC-009", emptyMap(), emptyMap()) }, mapLabel = stringResource(R.string.record_location_adjust)) }
        item { LedgerTextField(d.note, actions.onNote, stringResource(R.string.record_field_note), singleLine = false, hideValueFromSemantics = true) }
        item {
            val attachments = d.attachmentIds.mapIndexed { index, _ -> AttachmentUiModel("attachment_item_$index", stringResource(R.string.record_attachment_attached), "", stringResource(R.string.record_attachment_type), state = AttachmentTransferState.READY) }
            AttachmentField(
                attachments,
                { actions.onNavigate("REC-010", emptyMap(), emptyMap()) },
                { model -> attachments.indexOf(model).takeIf { it >= 0 }?.let { d.attachmentIds.getOrNull(it) }?.let(actions.onOpenAttachment) },
                { model -> attachments.indexOf(model).takeIf { it >= 0 }?.let(actions.onCancelAttachment) },
                addLabel = stringResource(R.string.record_attachments_add),
            )
        }
        item { FormSection(stringResource(R.string.record_advanced), expanded = false, onToggle = { actions.onNavigate("REC-012", emptyMap(), emptyMap()) }) { LedgerText(stringResource(R.string.record_advanced_summary), LedgerTextRole.SUPPORTING) } }
        item { LedgerText(stringResource(R.string.record_fixed_save_hint), LedgerTextRole.SUPPORTING, Modifier.padding(bottom = LedgerTheme.spacing.xxl)) }
    }
    if (showDateTimePicker) {
        val local = d.occurredAt.atZone(d.zoneId)
        val pickerDateMillis = local.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        LedgerDateTimePickerFlow(
            initialDateMillis = pickerDateMillis,
            initialHour = local.hour,
            initialMinute = local.minute,
            onConfirm = { dateMillis, hour, minute ->
                actions.onOccurredAt(dateMillis, hour, minute)
                showDateTimePicker = false
            },
            onDismiss = { showDateTimePicker = false },
        )
    }
    if (state.showUnsavedDialog) {
        app.ledger.core.designsystem.LedgerDialog(stringResource(R.string.record_unsaved_title), stringResource(R.string.record_unsaved_message), stringResource(R.string.record_discard), actions.onUnsavedDiscard, actions.onUnsavedKeepEditing, Modifier.testTag(LedgerTestTags.RECORD_UNSAVED_DIALOG), danger = true)
    }
}

@Composable
private fun RevisionConflict(actions: OrdinaryRecordActions) {
    Column(Modifier.fillMaxWidth().testTag(LedgerTestTags.RECORD_REVISION_CONFLICT), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
        LedgerBanner(stringResource(R.string.record_revision_conflict), LedgerBannerVariant.WARNING)
        Row(horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
            LedgerButton(stringResource(R.string.record_reload), actions.onReloadConflict)
            LedgerButton(stringResource(R.string.record_cancel_edit), actions.onCancelConflict, variant = LedgerButtonVariant.SECONDARY)
        }
    }
}

@Composable
private fun CategoryPicker(state: OrdinaryRecordEditorState, actions: OrdinaryRecordActions) {
    var query by remember { mutableStateOf("") }
    val all = activeCategories(state.snapshot, state.draft.direction)
    val categories = all.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        SearchField(query, { query = it }, placeholder = stringResource(R.string.record_search_category), onClear = { query = "" })
        if (categories.isEmpty()) {
            LedgerEmptyState(
                stringResource(if (all.isEmpty()) R.string.record_no_categories else R.string.record_search_empty),
                stringResource(if (all.isEmpty()) R.string.record_no_categories_body else R.string.record_search_empty_body),
                stringResource(R.string.record_create_category),
                { actions.onNavigate("CAT-002", emptyMap(), mapOf("direction" to state.draft.direction.name)) },
            )
        } else {
            CategoryGrid(categoryGroups(categories), state.draft.categoryId?.toString(), { tile -> actions.onSelectCategory(categories.single { it.id.toString() == tile.stableKey }.id) }, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun AccountPicker(state: OrdinaryRecordEditorState, actions: OrdinaryRecordActions) {
    var query by remember { mutableStateOf("") }
    val rows = state.snapshot.references.accounts
        .filter { it.status == EntityStatus.ACTIVE && (query.isBlank() || it.name.contains(query, ignoreCase = true)) }
        .map { ReferenceDataRowUiModel("account_item", it.name, it.currency.value, icon = LedgerIcon.ACCOUNT) to it.id }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        SearchField(query, { query = it }, placeholder = stringResource(R.string.record_search_account), onClear = { query = "" })
        if (rows.isEmpty()) {
            LedgerEmptyState(
                stringResource(R.string.record_accounts_empty),
                stringResource(R.string.record_accounts_empty_body),
                stringResource(R.string.record_create_account),
                { actions.onNavigate("ACC-002", emptyMap(), emptyMap()) },
            )
        } else {
            SelectionList(rows, state.draft.accountId, { id -> if (id != null) actions.onSelectAccount(id) })
        }
    }
}

@Composable
private fun CardPicker(state: OrdinaryRecordEditorState, actions: OrdinaryRecordActions) {
    val cards = state.snapshot.references.cards.filter { it.accountId == state.draft.accountId && it.status == EntityStatus.ACTIVE }
    Column(Modifier.fillMaxSize()) {
        LedgerChoiceRow(stringResource(R.string.record_no_card), state.draft.cardId == null, { actions.onSelectCard(null) })
        cards.forEach { card -> LedgerChoiceRow(card.displayName, state.draft.cardId == card.id, { actions.onSelectCard(card.id) }, supportingText = card.lastFour?.let { stringResource(R.string.record_card_last_four, it) }) }
    }
}

@Composable
private fun MerchantPicker(state: OrdinaryRecordEditorState, actions: OrdinaryRecordActions) {
    var query by remember { mutableStateOf("") }
    val rows = state.snapshot.references.merchants
        .filter { merchant -> merchant.status == EntityStatus.ACTIVE && (query.isBlank() || merchant.name.contains(query, true) || merchant.aliases.any { it.contains(query, true) }) }
        .map { ReferenceDataRowUiModel("merchant_item", it.name, it.aliases.joinToString(), icon = LedgerIcon.RECORD) to it.id }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        SearchField(query, { query = it }, placeholder = stringResource(R.string.record_search_merchant), onClear = { query = "" })
        SelectionList(rows, state.draft.merchantId, { actions.onSelectReference(RecordField.MERCHANT, it) }, allowNone = true)
        if (rows.isEmpty()) LedgerText(stringResource(R.string.record_merchants_empty), LedgerTextRole.SUPPORTING)
        LedgerButton(stringResource(R.string.record_create_merchant), { actions.onNavigate("MER-002", emptyMap(), emptyMap()) }, variant = LedgerButtonVariant.SECONDARY)
    }
}

@Composable
private fun ProjectPicker(state: OrdinaryRecordEditorState, actions: OrdinaryRecordActions) {
    var query by remember { mutableStateOf("") }
    val selected = state.snapshot.projects.singleOrNull { it.id == state.draft.projectId }
    val rows = state.snapshot.projects
        .filter { it.active && (query.isBlank() || it.name.contains(query, true)) }
        .map { ReferenceDataRowUiModel("project_item", it.name, null, icon = LedgerIcon.BUDGET) to it.id }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        SearchField(query, { query = it }, placeholder = stringResource(R.string.record_search_project), onClear = { query = "" })
        if (selected != null && !selected.active) LedgerBanner(stringResource(R.string.record_project_archived_warning), LedgerBannerVariant.WARNING)
        SelectionList(rows, state.draft.projectId, { actions.onSelectReference(RecordField.PROJECT, it) }, allowNone = true)
        if (rows.isEmpty()) LedgerText(stringResource(R.string.record_projects_empty), LedgerTextRole.SUPPORTING)
    }
}

@Composable
private fun LocationPicker(
    state: OrdinaryRecordEditorState,
    actions: OrdinaryRecordActions,
    locationMap: @Composable (RecordLocationMapModel, (StableId) -> Unit, (Int, Int) -> Unit, () -> Unit) -> Unit,
) {
    val manual = state.draft.newLocation
    val selectedSaved = state.snapshot.references.locations.singleOrNull { it.id == state.draft.locationRecordId }
    val selectedLatitudeE7 = manual?.latitudeE7 ?: selectedSaved?.latitudeE7
    val selectedLongitudeE7 = manual?.longitudeE7 ?: selectedSaved?.longitudeE7
    var latitude by remember(selectedLatitudeE7) { mutableStateOf(selectedLatitudeE7?.coordinateText().orEmpty()) }
    var longitude by remember(selectedLongitudeE7) { mutableStateOf(selectedLongitudeE7?.coordinateText().orEmpty()) }
    var mapUnavailable by remember { mutableStateOf(false) }
    val latitudeE7 = latitude.coordinateE7OrNull(LATITUDE_RANGE)
    val longitudeE7 = longitude.coordinateE7OrNull(LONGITUDE_RANGE)
    val savedPoints = state.snapshot.references.locations.map { location ->
        RecordLocationMapPoint(location.id, location.latitudeE7, location.longitudeE7, selected = location.id == state.draft.locationRecordId)
    }
    val points = if (manual == null) savedPoints else savedPoints.filterNot { it.id == manual.id } + RecordLocationMapPoint(manual.id, manual.latitudeE7, manual.longitudeE7, selected = true)
    val rows = state.snapshot.references.locations.map { location ->
        val place = state.snapshot.references.places.singleOrNull { it.id == location.placeId }
        RecordLocationMapRow(location.id, place?.name ?: stringResource(R.string.record_saved_location), "${location.latitudeE7.coordinateText()}, ${location.longitudeE7.coordinateText()}")
    }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        item {
            LocationField(
                if (state.draft.locationRecordId == null) LocationFieldState.ReadyAtSave else LocationFieldState.ManuallyAdjusted,
                {},
                mapLabel = stringResource(R.string.record_location_adjust),
            )
        }
        item {
            locationMap(
                RecordLocationMapModel(
                    unavailable = mapUnavailable,
                    summary = if (mapUnavailable) stringResource(R.string.record_location_map_unavailable) else stringResource(R.string.record_location_map_summary),
                    caption = stringResource(R.string.record_location_map_caption),
                    nameHeader = stringResource(R.string.record_location_map_name),
                    coordinateHeader = stringResource(R.string.record_location_map_coordinates),
                    showListLabel = stringResource(R.string.record_location_show_list),
                    hideListLabel = stringResource(R.string.record_location_hide_list),
                    points = points,
                    rows = rows,
                ),
                { id -> if (id != manual?.id) actions.onSelectReference(RecordField.LOCATION, id) },
                actions.onManualLocation,
                { mapUnavailable = true },
            )
        }
        item {
            LedgerTextField(
                latitude,
                { latitude = it.filterCoordinateInput() },
                stringResource(R.string.record_location_latitude),
                errorText = if (latitude.isNotBlank() && latitudeE7 == null) stringResource(R.string.record_location_coordinate_invalid) else null,
                keyboardType = KeyboardType.Decimal,
            )
        }
        item {
            LedgerTextField(
                longitude,
                { longitude = it.filterCoordinateInput() },
                stringResource(R.string.record_location_longitude),
                errorText = if (longitude.isNotBlank() && longitudeE7 == null) stringResource(R.string.record_location_coordinate_invalid) else null,
                keyboardType = KeyboardType.Decimal,
            )
        }
        item {
            LedgerButton(
                stringResource(R.string.record_location_apply_coordinates),
                { actions.onManualLocation(requireNotNull(latitudeE7), requireNotNull(longitudeE7)) },
                Modifier.fillMaxWidth(),
                enabled = latitudeE7 != null && longitudeE7 != null,
            )
        }
        item { LedgerBanner(stringResource(R.string.record_location_three_seconds), LedgerBannerVariant.INFO) }
        items(state.snapshot.references.locations, key = { it.id.toString() }) { location ->
            val place = state.snapshot.references.places.singleOrNull { it.id == location.placeId }
            LedgerChoiceRow(place?.name ?: stringResource(R.string.record_saved_location), state.draft.locationRecordId == location.id, { actions.onSelectReference(RecordField.LOCATION, location.id) })
        }
        item { LedgerButton(stringResource(R.string.record_without_location), { actions.onSelectReference(RecordField.LOCATION, null) }, Modifier.fillMaxWidth(), variant = LedgerButtonVariant.SECONDARY) }
    }
}

@Composable
private fun AttachmentPicker(state: OrdinaryRecordEditorState, actions: OrdinaryRecordActions) {
    val attachments = state.draft.attachmentIds.mapIndexed { index, id ->
        val metadata = state.availableAttachments.singleOrNull { it.id == id }
        AttachmentUiModel(
            "attachment_item_$index",
            metadata?.displayName ?: stringResource(R.string.record_attachment_attached),
            metadata?.sizeBytes?.let { stringResource(R.string.record_attachment_size, it) }.orEmpty(),
            metadata?.mimeType ?: stringResource(R.string.record_attachment_type),
        )
    } +
        if (state.attachmentImporting) listOf(AttachmentUiModel("attachment_import", stringResource(R.string.record_attachment_importing), "", stringResource(R.string.record_attachment_type), state = AttachmentTransferState.IMPORTING)) else emptyList()
    val reusable = state.availableAttachments.filterNot { it.id in state.draft.attachmentIds }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        state.attachmentFailureCode?.let { code -> item { LedgerBanner(stringResource(R.string.record_attachments_failed, code), LedgerBannerVariant.DANGER) } }
        item {
            AttachmentField(
                attachments,
                actions.onAddAttachment,
                { model -> attachments.indexOf(model).takeIf { it >= 0 }?.let { state.draft.attachmentIds.getOrNull(it) }?.let(actions.onOpenAttachment) },
                { model -> attachments.indexOf(model).takeIf { it >= 0 }?.let(actions.onCancelAttachment) },
                addLabel = stringResource(R.string.record_attachments_add),
            )
        }
        if (reusable.isNotEmpty()) {
            item { LedgerText(stringResource(R.string.record_attachments_reuse_title), LedgerTextRole.SECTION) }
            items(reusable, key = { it.id.toString() }) { attachment ->
                LedgerChoiceRow(
                    attachment.displayName,
                    false,
                    { actions.onReuseAttachment(attachment.id) },
                    supportingText = stringResource(R.string.record_attachment_reuse_summary, attachment.mimeType, attachment.sizeBytes),
                )
            }
        } else if (attachments.isEmpty()) {
            item { LedgerText(stringResource(R.string.record_attachments_empty_message), LedgerTextRole.SUPPORTING) }
        }
    }
}

@Composable
private fun SettlementAllocation(state: OrdinaryRecordEditorState, actions: OrdinaryRecordActions) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        if (state.snapshot.settlementActivities.none { it.active }) {
            LedgerEmptyState(stringResource(R.string.record_settlement_empty), stringResource(R.string.record_settlement_empty_body), stringResource(R.string.record_continue_without_settlement), { actions.onSettlementEnabled(false) })
            return@Column
        }
        state.snapshot.settlementActivities.filter { it.active }.forEach { activity ->
            LedgerChoiceRow(activity.name, state.draft.settlementActivityId == activity.id, { actions.onSettlementActivity(activity.id) }, supportingText = activity.currency.value)
        }
        val selectedActivity = state.snapshot.settlementActivities.singleOrNull { it.id == state.draft.settlementActivityId }
        if (selectedActivity != null && selectedActivity.currency.value != state.draft.currencyCode) {
            LedgerBanner(stringResource(R.string.record_settlement_currency_mismatch), LedgerBannerVariant.DANGER)
        }
        if (selectedActivity != null) {
            FormSection(stringResource(R.string.record_settlement_payer)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                    selectedActivity.participants.forEach { participant ->
                        LedgerButton(
                            participant.name,
                            { actions.onSettlementPayer(participant.id) },
                            variant = if (participant.id == state.draft.settlementPayerParticipantId) LedgerButtonVariant.TONAL else LedgerButtonVariant.TEXT,
                        )
                    }
                }
            }
            FormSection(stringResource(R.string.record_settlement_split_method)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                    SettlementSplitMethod.entries.forEach { method ->
                        LedgerButton(splitMethodLabel(method), { actions.onSettlementSplitMethod(method) }, variant = if (method == state.draft.settlementSplitMethod) LedgerButtonVariant.TONAL else LedgerButtonVariant.TEXT)
                    }
                }
            }
            LedgerTextField(
                OrdinaryRecordPolicy.settlementAmountInput(state.draft.settlementTaxMinor, state.draft.currencyCode),
                actions.onSettlementTax,
                stringResource(R.string.record_settlement_tax),
                Modifier.fillMaxWidth(),
            )
            LedgerTextField(
                OrdinaryRecordPolicy.settlementAmountInput(state.draft.settlementServiceFeeMinor, state.draft.currencyCode),
                actions.onSettlementServiceFee,
                stringResource(R.string.record_settlement_service_fee),
                Modifier.fillMaxWidth(),
            )
            FormSection(stringResource(R.string.record_settlement_charge_distribution)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                    SettlementChargeDistribution.entries.forEach { distribution ->
                        LedgerButton(chargeDistributionLabel(distribution), { actions.onSettlementChargeDistribution(distribution) }, variant = if (distribution == state.draft.settlementChargeDistribution) LedgerButtonVariant.TONAL else LedgerButtonVariant.TEXT)
                    }
                }
            }
            FormSection(stringResource(R.string.record_settlement_rounding)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                    SettlementRoundingRule.entries.forEach { rule ->
                        LedgerButton(roundingRuleLabel(rule), { actions.onSettlementRoundingRule(rule) }, variant = if (rule == state.draft.settlementRoundingRule) LedgerButtonVariant.TONAL else LedgerButtonVariant.TEXT)
                    }
                }
            }
            selectedActivity.participants.forEach { participant ->
                val locked = participant.isSelf || participant.id == state.draft.settlementPayerParticipantId
                LedgerToggleRow(
                    participant.name,
                    participant.id in state.draft.settlementIncludedParticipantIds,
                    { _ -> if (!locked) actions.onSettlementParticipantIncluded(participant.id) },
                    supportingText = if (locked) stringResource(R.string.record_settlement_required_participant) else stringResource(R.string.record_settlement_exclusion_support),
                )
                if (state.draft.settlementSplitMethod != SettlementSplitMethod.EQUAL && participant.id in state.draft.settlementIncludedParticipantIds) {
                    LedgerTextField(
                        state.draft.settlementAllocationInputs[participant.id].orEmpty(),
                        { actions.onSettlementAllocationInput(participant.id, it) },
                        allocationInputLabel(state.draft.settlementSplitMethod),
                        Modifier.fillMaxWidth(),
                    )
                }
                if (state.draft.settlementChargeDistribution == SettlementChargeDistribution.SPECIFIED && participant.id in state.draft.settlementIncludedParticipantIds) {
                    LedgerTextField(
                        state.draft.settlementChargeInputs[participant.id].orEmpty(),
                        { actions.onSettlementChargeInput(participant.id, it) },
                        stringResource(R.string.record_settlement_specified_charge),
                        Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        val paid = state.draft.settlementShares.sumOf { it.paidMinor }
        val owed = state.draft.settlementShares.sumOf { it.owedMinor }
        val total = state.draft.resultMinor ?: 0L
        val selfIds = state.snapshot.settlementActivities.flatMap { it.participants }.filter { it.isSelf }.mapTo(hashSetOf()) { it.id }
        val selfOwed = state.draft.settlementShares.filter { it.participantId in selfIds }.sumOf { it.owedMinor }
        val othersOwed = Math.subtractExact(owed, selfOwed)
        LedgerBanner(stringResource(if (paid == total && owed == total && total > 0) R.string.record_settlement_balanced else R.string.record_settlement_imbalanced), if (paid == total && owed == total && total > 0) LedgerBannerVariant.INFO else LedgerBannerVariant.DANGER)
        LedgerText(stringResource(R.string.record_settlement_self_summary, selfOwed), LedgerTextRole.BODY)
        LedgerText(stringResource(R.string.record_settlement_others_summary, othersOwed), LedgerTextRole.BODY)
        state.draft.settlementShares.forEach { share ->
            val participant = state.snapshot.settlementActivities.flatMap { it.participants }.singleOrNull { it.id == share.participantId }
            LedgerText(stringResource(R.string.record_settlement_share, participant?.name.orEmpty(), share.owedMinor), LedgerTextRole.BODY)
        }
    }
}

@Composable
private fun splitMethodLabel(method: SettlementSplitMethod): String = stringResource(
    when (method) {
        SettlementSplitMethod.EQUAL -> R.string.record_settlement_equal
        SettlementSplitMethod.FIXED_AMOUNT -> R.string.record_settlement_fixed
        SettlementSplitMethod.PERCENTAGE -> R.string.record_settlement_percentage
        SettlementSplitMethod.WEIGHT -> R.string.record_settlement_weight
    },
)

@Composable
private fun chargeDistributionLabel(distribution: SettlementChargeDistribution): String = stringResource(
    when (distribution) {
        SettlementChargeDistribution.SAME_AS_BASE -> R.string.record_settlement_charge_same
        SettlementChargeDistribution.EQUAL -> R.string.record_settlement_charge_equal
        SettlementChargeDistribution.PAYER -> R.string.record_settlement_charge_payer
        SettlementChargeDistribution.SPECIFIED -> R.string.record_settlement_charge_specified
    },
)

@Composable
private fun roundingRuleLabel(rule: SettlementRoundingRule): String = stringResource(
    when (rule) {
        SettlementRoundingRule.PARTICIPANT_ORDER -> R.string.record_settlement_rounding_order
        SettlementRoundingRule.PAYER -> R.string.record_settlement_rounding_payer
        SettlementRoundingRule.SELF -> R.string.record_settlement_rounding_self
        SettlementRoundingRule.LARGEST_SHARE -> R.string.record_settlement_rounding_largest
    },
)

@Composable
private fun allocationInputLabel(method: SettlementSplitMethod): String = stringResource(
    when (method) {
        SettlementSplitMethod.FIXED_AMOUNT -> R.string.record_settlement_fixed_amount
        SettlementSplitMethod.PERCENTAGE -> R.string.record_settlement_percentage_value
        SettlementSplitMethod.WEIGHT -> R.string.record_settlement_weight_value
        SettlementSplitMethod.EQUAL -> R.string.record_settlement_equal
    },
)

@Composable
private fun AdvancedSemantics(state: OrdinaryRecordEditorState) {
    val category = state.snapshot.references.categories.singleOrNull { it.id == state.draft.categoryId }
    val project = state.snapshot.projects.singleOrNull { it.id == state.draft.projectId }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        LedgerText(stringResource(R.string.record_advanced), LedgerTextRole.TITLE)
        LedgerText(stringResource(R.string.record_statistical_snapshot, category?.statisticalNature?.label().orEmpty()), LedgerTextRole.BODY)
        LedgerText(stringResource(R.string.record_budget_snapshot, if (project == null) stringResource(R.string.record_budget_category_rule) else project.name), LedgerTextRole.BODY)
        LedgerBanner(stringResource(R.string.record_snapshot_immutable), LedgerBannerVariant.INFO)
    }
}

@Composable
private fun StatisticalNature.label(): String = stringResource(
    when (this) {
        StatisticalNature.CONSUMPTION_EXPENSE -> R.string.record_nature_consumption_expense
        StatisticalNature.NON_CONSUMPTION_EXPENSE -> R.string.record_nature_non_consumption_expense
        StatisticalNature.REGULAR_INCOME -> R.string.record_nature_regular_income
        StatisticalNature.NON_RECURRING_INCOME -> R.string.record_nature_non_recurring_income
    },
)

@Composable
private fun SelectionList(rows: List<Pair<ReferenceDataRowUiModel, StableId>>, selected: StableId?, onSelect: (StableId?) -> Unit, allowNone: Boolean = false) {
    LazyColumn(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
        if (allowNone) item { LedgerChoiceRow(stringResource(R.string.record_not_selected), selected == null, { onSelect(null) }) }
        items(rows, key = { it.second.toString() }) { (row, id) -> ReferenceDataRow(row, { onSelect(id) }) }
    }
}

private fun activeCategories(snapshot: OrdinaryTransactionEntrySnapshot, direction: OrdinaryDirection): List<CategoryReferenceView> = snapshot.references.categories.filter { it.direction.name == direction.name && it.status == CategoryStatus.ACTIVE }.sortedWith(compareBy(CategoryReferenceView::sortOrder, CategoryReferenceView::name))

private fun categoryGroups(categories: List<CategoryReferenceView>): List<CategoryGroupUiModel> = categories.filter { it.depth == 1 }.map { root ->
    val group = listOf(root) + categories.filter { it.parentId == root.id }
    CategoryGroupUiModel(root.id.toString(), root.name, group.map { it.toTile() })
}

private fun CategoryReferenceView.toTile() = CategoryTileUiModel(id.toString(), name, name, LedgerReferenceDisplayDefaults.paletteId(colorArgb), LedgerIcon.entries.firstOrNull { it.name.equals(iconKey, true) } ?: LedgerIcon.RECORD, depth == 1, childCount.toInt())
private fun CategoryReferenceView.toRow() = ReferenceDataRowUiModel("category_item", name, null, depth, icon = LedgerIcon.entries.firstOrNull { it.name.equals(iconKey, true) } ?: LedgerIcon.RECORD, paletteId = LedgerReferenceDisplayDefaults.paletteId(colorArgb))
private fun RecordTab.toDirection() = if (this == RecordTab.INCOME) OrdinaryDirection.INCOME else OrdinaryDirection.EXPENSE
private fun Int.coordinateText(): String = BigDecimal.valueOf(toLong(), COORDINATE_SCALE).stripTrailingZeros().toPlainString()
private fun String.coordinateE7OrNull(range: IntRange): Int? = runCatching {
    BigDecimal(trim()).movePointRight(COORDINATE_SCALE).setScale(0, RoundingMode.UNNECESSARY).intValueExact()
}.getOrNull()?.takeIf { it in range }
private fun String.filterCoordinateInput(): String = filter { it.isDigit() || it == '-' || it == '.' }.take(COORDINATE_INPUT_LENGTH)

@Composable
private fun validationMessage(code: String): String = when (code) {
    "CATEGORY_REQUIRED" -> stringResource(R.string.record_error_category)
    "AMOUNT_INVALID" -> stringResource(R.string.record_error_amount)
    "ACCOUNT_REQUIRED" -> stringResource(R.string.record_error_account)
    "SETTLEMENT_IMBALANCED" -> stringResource(R.string.record_error_settlement)
    "SETTLEMENT_CURRENCY_MISMATCH" -> stringResource(R.string.record_settlement_currency_mismatch)
    else -> stringResource(R.string.record_error_generic)
}

@Composable
private fun originText(origin: RecordFieldOrigin?): String? = origin?.let { stringResource(R.string.record_default_explanation, stringResource(origin.source.labelResource())) }

private fun RecordDefaultSource.labelResource(): Int = when (this) {
    RecordDefaultSource.MANUAL -> R.string.record_default_manual
    RecordDefaultSource.EDIT_SNAPSHOT -> R.string.record_default_edit
    RecordDefaultSource.TEMPLATE -> R.string.record_default_template
    RecordDefaultSource.CATEGORY -> R.string.record_default_category
    RecordDefaultSource.RECENT_COMPATIBLE -> R.string.record_default_recent
    RecordDefaultSource.CASH_FALLBACK -> R.string.record_default_cash
    RecordDefaultSource.FIRST_ACTIVE_ACCOUNT -> R.string.record_default_first
    RecordDefaultSource.NONE -> R.string.record_default_none
}

private val DATE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private const val MAX_QUICK_TEMPLATES = 5
private const val COORDINATE_SCALE = 7
private const val COORDINATE_INPUT_LENGTH = 18
private val LATITUDE_RANGE = -900_000_000..900_000_000
private val LONGITUDE_RANGE = -1_800_000_000..1_800_000_000
