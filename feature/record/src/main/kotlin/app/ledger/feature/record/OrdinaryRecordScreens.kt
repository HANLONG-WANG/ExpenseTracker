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
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

public data class OrdinaryRecordActions(
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
    val onAddAttachment: () -> Unit,
    val onCancelAttachment: (index: Int) -> Unit,
    val onSave: () -> Unit,
    val onUnsavedDiscard: () -> Unit,
    val onUnsavedKeepEditing: () -> Unit,
    val onReloadConflict: () -> Unit,
    val onCancelConflict: () -> Unit,
)

@Composable
public fun OrdinaryRecordDestination(
    screenId: String,
    state: OrdinaryRecordLoadState,
    actions: OrdinaryRecordActions,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().testTag(LedgerTestTags.RECORD_ROOT)) {
        when (state) {
            OrdinaryRecordLoadState.Loading -> LedgerText(stringResource(R.string.record_loading), LedgerTextRole.SUPPORTING)
            is OrdinaryRecordLoadState.Failure -> LedgerErrorState(UiErrorCode(state.code), stringResource(R.string.record_load_failed), actions.onRetry)
            is OrdinaryRecordLoadState.Content -> RecordContent(screenId, state, actions)
        }
    }
}

@Composable
private fun RecordContent(screenId: String, state: OrdinaryRecordLoadState.Content, actions: OrdinaryRecordActions) {
    when (screenId) {
        "REC-001" -> CategoryFirstHome(state, actions)
        "REC-002" -> CategorySearch(state, actions)
        "REC-003" -> state.editor?.let { OrdinaryEditor(it, actions) } ?: LedgerText(stringResource(R.string.record_loading), LedgerTextRole.SUPPORTING)
        "REC-004" -> state.editor?.let { CategoryPicker(it, actions) } ?: CategoryFirstHome(state, actions)
        "REC-005" -> state.editor?.let { AccountPicker(it, actions) } ?: LedgerText(stringResource(R.string.record_loading), LedgerTextRole.SUPPORTING)
        "REC-006" -> state.editor?.let { CardPicker(it, actions) } ?: LedgerText(stringResource(R.string.record_loading), LedgerTextRole.SUPPORTING)
        "REC-007" -> state.editor?.let { MerchantPicker(it, actions) } ?: LedgerText(stringResource(R.string.record_loading), LedgerTextRole.SUPPORTING)
        "REC-008" -> state.editor?.let { ProjectPicker(it, actions) } ?: LedgerText(stringResource(R.string.record_loading), LedgerTextRole.SUPPORTING)
        "REC-009" -> state.editor?.let { LocationPicker(it, actions) } ?: LedgerText(stringResource(R.string.record_loading), LedgerTextRole.SUPPORTING)
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
    val targets = listOf(
        "REC-013" to R.string.record_other_transfer,
        "CRD-007" to R.string.record_other_credit_payment,
        "REF-001" to R.string.record_other_refund,
        "LOA-007" to R.string.record_other_loan,
        "REC-020" to R.string.record_other_adjustment,
        "REC-021" to R.string.record_other_fx,
    )
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
        targets.forEach { (target, label) -> LedgerButton(stringResource(label), { actions.onNavigate(target, emptyMap(), emptyMap()) }, variant = LedgerButtonVariant.TONAL) }
    }
}

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
        item { LocationField(if (d.locationRecordId == null) LocationFieldState.Locating else LocationFieldState.ManuallyAdjusted, { actions.onNavigate("REC-009", emptyMap(), emptyMap()) }, mapLabel = stringResource(R.string.record_location_adjust)) }
        item { LedgerTextField(d.note, actions.onNote, stringResource(R.string.record_field_note), singleLine = false, hideValueFromSemantics = true) }
        item {
            val attachments = d.attachmentIds.mapIndexed { index, _ -> AttachmentUiModel("attachment_item_$index", stringResource(R.string.record_attachment_attached), "", stringResource(R.string.record_attachment_type), state = AttachmentTransferState.READY) }
            AttachmentField(
                attachments,
                { actions.onNavigate("REC-010", emptyMap(), emptyMap()) },
                {},
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
private fun LocationPicker(state: OrdinaryRecordEditorState, actions: OrdinaryRecordActions) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        LocationField(if (state.draft.locationRecordId == null) LocationFieldState.Locating else LocationFieldState.ManuallyAdjusted, {}, mapLabel = stringResource(R.string.record_location_adjust))
        LedgerBanner(stringResource(R.string.record_location_three_seconds), LedgerBannerVariant.INFO)
        state.snapshot.references.locations.forEach { location ->
            val place = state.snapshot.references.places.singleOrNull { it.id == location.placeId }
            LedgerChoiceRow(place?.name ?: stringResource(R.string.record_saved_location), state.draft.locationRecordId == location.id, { actions.onSelectReference(RecordField.LOCATION, location.id) })
        }
        LedgerButton(stringResource(R.string.record_without_location), { actions.onSelectReference(RecordField.LOCATION, null) }, variant = LedgerButtonVariant.SECONDARY)
    }
}

@Composable
private fun AttachmentPicker(state: OrdinaryRecordEditorState, actions: OrdinaryRecordActions) {
    val attachments = state.draft.attachmentIds.mapIndexed { index, _ -> AttachmentUiModel("attachment_item_$index", stringResource(R.string.record_attachment_attached), "", stringResource(R.string.record_attachment_type)) } +
        if (state.attachmentImporting) listOf(AttachmentUiModel("attachment_import", stringResource(R.string.record_attachment_importing), "", stringResource(R.string.record_attachment_type), state = AttachmentTransferState.IMPORTING)) else emptyList()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
        state.attachmentFailureCode?.let { LedgerBanner(stringResource(R.string.record_attachments_failed, it), LedgerBannerVariant.DANGER) }
        if (attachments.isEmpty()) {
            LedgerEmptyState(stringResource(R.string.record_attachments_empty_title), stringResource(R.string.record_attachments_empty_message), stringResource(R.string.record_attachments_add), actions.onAddAttachment)
        } else {
            AttachmentField(
                attachments,
                actions.onAddAttachment,
                {},
                { model -> attachments.indexOf(model).takeIf { it >= 0 }?.let(actions.onCancelAttachment) },
                addLabel = stringResource(R.string.record_attachments_add),
            )
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
        LedgerText(stringResource(R.string.record_statistical_snapshot, category?.statisticalNature?.name.orEmpty()), LedgerTextRole.BODY)
        LedgerText(stringResource(R.string.record_budget_snapshot, if (project == null) stringResource(R.string.record_budget_category_rule) else project.name), LedgerTextRole.BODY)
        LedgerBanner(stringResource(R.string.record_snapshot_immutable), LedgerBannerVariant.INFO)
    }
}

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
