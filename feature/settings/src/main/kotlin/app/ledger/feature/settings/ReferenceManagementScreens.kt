@file:Suppress(
    "ktlint:standard:function-naming",
    "LongMethod",
    "LongParameterList",
    "TooManyFunctions",
    "CyclomaticComplexMethod",
    "FunctionNaming",
    "MaxLineLength",
    "MagicNumber",
    "NestedBlockDepth",
)

package app.ledger.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.ledger.core.common.StableId
import app.ledger.core.common.getOrNull
import app.ledger.core.designsystem.LedgerBanner
import app.ledger.core.designsystem.LedgerBannerVariant
import app.ledger.core.designsystem.LedgerButton
import app.ledger.core.designsystem.LedgerButtonVariant
import app.ledger.core.designsystem.LedgerCard
import app.ledger.core.designsystem.LedgerChoiceRow
import app.ledger.core.designsystem.LedgerChoiceSelector
import app.ledger.core.designsystem.LedgerDialog
import app.ledger.core.designsystem.LedgerModalDialog
import app.ledger.core.designsystem.LedgerEmptyState
import app.ledger.core.designsystem.LedgerErrorState
import app.ledger.core.designsystem.LedgerHaptic
import app.ledger.core.designsystem.LedgerIcon
import app.ledger.core.designsystem.LedgerLoadingState
import app.ledger.core.designsystem.LedgerReferenceDisplayDefaults
import app.ledger.core.designsystem.LedgerScaffold
import app.ledger.core.designsystem.LedgerStatusVariant
import app.ledger.core.designsystem.LedgerTabRow
import app.ledger.core.designsystem.LedgerTestTags
import app.ledger.core.designsystem.LedgerText
import app.ledger.core.designsystem.LedgerTextField
import app.ledger.core.designsystem.LedgerTextRole
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.LedgerDateFormatterRuntime
import app.ledger.core.designsystem.LedgerToggleRow
import app.ledger.core.designsystem.ReferenceDataRow
import app.ledger.core.designsystem.ReferenceDataRowUiModel
import app.ledger.core.designsystem.ReferenceDisplayStyleIcons
import app.ledger.core.designsystem.ReferenceDisplayStylePicker
import app.ledger.core.designsystem.rememberLedgerRetainedState
import app.ledger.core.designsystem.SearchField
import app.ledger.core.designsystem.performLedgerHaptic
import app.ledger.core.designsystem.SelectorField
import app.ledger.core.designsystem.UiErrorCode
import app.ledger.finance.application.CategoryReferenceView
import app.ledger.finance.application.MerchantReferenceView
import app.ledger.finance.application.PlaceReferenceView
import app.ledger.finance.application.ReferenceDataSnapshot
import app.ledger.finance.domain.CategoryDirection
import app.ledger.finance.domain.CategoryRemovalStrategy
import app.ledger.finance.domain.CategoryStatus
import app.ledger.finance.domain.EntityStatus
import app.ledger.finance.domain.StatisticalNature
import kotlinx.coroutines.delay
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
public fun ReferenceManagementDestination(
    screenId: String,
    encodedArguments: Map<String, String>,
    dataState: ManagementDataState,
    actions: ManagementActions,
    placeMap: PlaceMapSlot,
    pending: Boolean,
    stateOverride: ManagementRequiredState? = null,
    modifier: Modifier = Modifier,
) {
    require(screenId in SUPPORTED_SCREENS)
    require(stateOverride == null || stateOverride.screenId == screenId)
    val snapshot = (dataState as? ManagementDataState.Content)?.snapshot
    val state = stateOverride?.contractName ?: actualState(screenId, encodedArguments, snapshot, pending)
    val rootModifier = modifier
        .fillMaxSize()
        .testTag(LedgerTestTags.P12_MANAGEMENT_ROOT)
        .padding(vertical = LedgerTheme.spacing.xs)
        .let { base ->
            if (screenId in VERTICALLY_SCROLLABLE_SCREENS) {
                base.verticalScroll(rememberScrollState())
            } else {
                base
            }
        }
    Column(
        rootModifier,
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm),
    ) {
        when {
            dataState is ManagementDataState.Loading && stateOverride == null -> LedgerLoadingState(Modifier.fillMaxSize())
            dataState is ManagementDataState.Error && stateOverride == null -> LedgerErrorState(UiErrorCode(dataState.code.sanitizeCode()), stringResource(R.string.management_load_failed), actions.onRetry)
            screenId == "MGT-001" -> ManagementHub(actions)
            screenId == "CAT-001" -> CategoryList(snapshot, encodedArguments.direction(), state, actions)
            screenId == "CAT-002" -> CategoryEditor(snapshot, encodedArguments.direction(), encodedArguments.stableId("categoryId"), state, actions)
            screenId == "CAT-003" -> CategoryReorder(snapshot, encodedArguments.direction(), actions)
            screenId == "CAT-004" -> CategoryRemoval(snapshot, encodedArguments.requireStableId("categoryId"), state, actions)
            screenId == "MER-001" -> MerchantList(snapshot, state, actions)
            screenId == "MER-002" -> MerchantEditor(snapshot, encodedArguments.stableId("merchantId"), state, actions)
            screenId == "MER-003" -> MerchantMerge(snapshot, state, actions)
            screenId == "PLC-001" -> PlaceList(snapshot, state, actions, placeMap)
            screenId == "PLC-002" -> PlaceEditor(snapshot, encodedArguments.stableId("placeId"), state, actions, placeMap)
            screenId == "PLC-003" -> PlaceMergeSplit(snapshot, encodedArguments.requireStableId("placeId"), state, actions, placeMap)
        }
    }
}

@Composable
private fun ManagementHub(actions: ManagementActions) {
    HubRow(stringResource(R.string.management_categories_expense), stringResource(R.string.management_categories_body)) { actions.onNavigate("CAT-001", emptyMap(), mapOf("direction" to "EXPENSE")) }
    HubRow(stringResource(R.string.management_categories_income), stringResource(R.string.management_categories_body)) { actions.onNavigate("CAT-001", emptyMap(), mapOf("direction" to "INCOME")) }
    HubRow(stringResource(R.string.management_merchants), stringResource(R.string.management_merchants_body)) { actions.onNavigate("MER-001", emptyMap(), emptyMap()) }
    HubRow(stringResource(R.string.management_places), stringResource(R.string.management_places_body)) { actions.onNavigate("PLC-001", emptyMap(), emptyMap()) }
}

@Composable
private fun HubRow(title: String, body: String, onClick: () -> Unit) {
    LedgerCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Column(Modifier.padding(LedgerTheme.spacing.sm)) {
            LedgerText(title, LedgerTextRole.SECTION)
            LedgerText(body, LedgerTextRole.SUPPORTING)
        }
    }
}

@Composable
private fun ColumnScope.CategoryList(snapshot: ReferenceDataSnapshot?, direction: CategoryDirection, state: String, actions: ManagementActions) {
    var query by remember(direction) { mutableStateOf("") }
    var searchPending by remember(direction) { mutableStateOf(false) }
    LaunchedEffect(query) {
        searchPending = query.isNotBlank()
        if (searchPending) delay(SEARCH_FEEDBACK_MILLIS)
        searchPending = false
    }
    val categories = snapshot?.categories.orEmpty().filter { it.direction == direction }.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
    LedgerTabRow(
        if (direction == CategoryDirection.EXPENSE) 0 else 1,
        listOf(stringResource(R.string.management_expense), stringResource(R.string.management_income)),
        onSelected = { index -> actions.onNavigate("CAT-001", emptyMap(), mapOf("direction" to if (index == 0) "EXPENSE" else "INCOME")) },
    )
    SearchField(query, { query = it.take(MAX_NAME) }, onClear = { query = "" })
    if (state == "searching" || searchPending) {
        LedgerLoadingState(Modifier.fillMaxWidth(), stringResource(R.string.management_searching_categories))
    }
    if (state == "empty" || categories.isEmpty()) {
        LedgerEmptyState(stringResource(R.string.management_categories_empty), stringResource(R.string.management_categories_empty_body), stringResource(R.string.management_add_category), { actions.onNavigate("CAT-002", emptyMap(), mapOf("direction" to direction.name)) })
        return
    }
    LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
        items(categories, key = { it.id.toString() }) { category -> CategoryRow(category) { actions.onNavigate("CAT-002", mapOf("categoryId" to category.id), mapOf("direction" to direction.name)) } }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                LedgerButton(stringResource(R.string.management_add_category), { actions.onNavigate("CAT-002", emptyMap(), mapOf("direction" to direction.name)) }, Modifier.weight(1f))
                LedgerButton(stringResource(R.string.management_reorder), { actions.onNavigate("CAT-003", emptyMap(), mapOf("direction" to direction.name)) }, Modifier.weight(1f), variant = LedgerButtonVariant.SECONDARY)
            }
        }
    }
}

@Composable
private fun CategoryRow(category: CategoryReferenceView, onClick: (() -> Unit)?) {
    ReferenceDataRow(
        ReferenceDataRowUiModel(
            stableKey = "category_item",
            title = category.name,
            supportingText = stringResource(R.string.management_category_usage, category.historicalTransactionCount),
            hierarchyLevel = category.depth,
            status = when (category.status) {
                CategoryStatus.ACTIVE -> LedgerStatusVariant.NEUTRAL
                CategoryStatus.ARCHIVED -> LedgerStatusVariant.ARCHIVED
                CategoryStatus.DELETED_TOMBSTONE -> LedgerStatusVariant.DELETED
            },
            icon = LedgerIcon.entries.firstOrNull { it.name.equals(category.iconKey, ignoreCase = true) } ?: LedgerIcon.RECORD,
            paletteId = LedgerReferenceDisplayDefaults.paletteId(category.colorArgb),
        ),
        onClick,
    )
}

@Composable
private fun CategoryEditor(snapshot: ReferenceDataSnapshot?, direction: CategoryDirection, categoryId: StableId?, state: String, actions: ManagementActions) {
    val existing = snapshot?.categories?.singleOrNull { it.id == categoryId }
    var name by rememberLedgerRetainedState("category.name") { existing?.name.orEmpty() }
    var parentId by rememberLedgerRetainedState<StableId?>("category.parent") { existing?.parentId }
    var nature by rememberLedgerRetainedState("category.nature") { existing?.statisticalNature ?: direction.defaultNature() }
    var selectedIcon by rememberLedgerRetainedState("category.icon") {
            ReferenceDisplayStyleIcons.firstOrNull { it.name.equals(existing?.iconKey, ignoreCase = true) }
                ?: LedgerIcon.RECORD
    }
    var selectedColor by rememberLedgerRetainedState("category.color") {
        existing?.colorArgb ?: LedgerReferenceDisplayDefaults.COLOR_ARGB
    }
    var selectedPalette by rememberLedgerRetainedState("category.palette") {
        LedgerReferenceDisplayDefaults.paletteId(selectedColor)
    }
    var defaultAccountId by rememberLedgerRetainedState<StableId?>("category.account") { existing?.defaultAccountId }
    var defaultCardId by rememberLedgerRetainedState<StableId?>("category.card") { existing?.defaultCardId }
    var defaultMerchantId by rememberLedgerRetainedState<StableId?>("category.merchant") { existing?.defaultMerchantId }
    var attempted by rememberLedgerRetainedState("category.attempted") { false }
    var chooser by remember(categoryId) { mutableStateOf<CategoryEditorChooser?>(null) }
    val parents = snapshot?.categories.orEmpty().filter { it.direction == direction && it.depth == 1 && it.status == CategoryStatus.ACTIVE && it.id != categoryId }
    val accounts = snapshot?.accounts.orEmpty().filter { it.status == EntityStatus.ACTIVE }
    val cards = snapshot?.cards.orEmpty().filter { card ->
        card.status == EntityStatus.ACTIVE && (defaultAccountId == null || card.accountId == defaultAccountId)
    }
    val merchants = snapshot?.merchants.orEmpty().filter { it.status == EntityStatus.ACTIVE }
    val selectedPaletteOption = LedgerTheme.colors.categoryPalette.single { it.id == selectedPalette }
    val contrast = contrastRatio(selectedPaletteOption.container, selectedPaletteOption.foreground)
    val contrastWarning = contrast < MIN_TEXT_CONTRAST
    val valid = name.isNotBlank() && !contrastWarning && state != "validationError"
    LedgerScaffold(
        modifier = Modifier.fillMaxSize(),
        formContent = true,
        fixedAction = {
            ManagementSaveBar(true) {
                attempted = true
                if (valid) actions.onSaveCategory(
                    CategorySubmission(
                        categoryId,
                        direction,
                        parentId,
                        name.trim(),
                        nature,
                        defaultAccountId,
                        defaultCardId,
                        defaultMerchantId,
                        selectedIcon.name.lowercase(),
                        selectedColor,
                    ),
                )
            }
        },
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(LedgerTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm),
        ) {
            if (state == "parentLocked" || existing?.depth == 2) LedgerBanner(stringResource(R.string.management_parent_locked), LedgerBannerVariant.INFO)
            if (state == "contrastWarning" || contrastWarning) LedgerBanner(stringResource(R.string.management_contrast_warning), LedgerBannerVariant.WARNING)
            if (state == "validationError" || attempted && !valid) LedgerBanner(stringResource(R.string.management_validation), LedgerBannerVariant.DANGER)
            LedgerTextField(name, { name = it.take(MAX_NAME) }, stringResource(R.string.management_name), errorText = stringResource(R.string.management_validation).takeIf { attempted && name.isBlank() }, required = true)
            SelectorField(stringResource(R.string.management_parent), parents.singleOrNull { it.id == parentId }?.name ?: stringResource(R.string.management_no_parent), { chooser = CategoryEditorChooser.PARENT }, enabled = existing?.depth != 2)
            LedgerChoiceRow(direction.firstNatureLabel(), nature == direction.defaultNature(), { nature = direction.defaultNature() })
            LedgerChoiceRow(direction.secondNatureLabel(), nature != direction.defaultNature(), { nature = direction.alternateNature() })
            LedgerBanner(stringResource(R.string.management_statistical_snapshot), LedgerBannerVariant.INFO)
            if (existing != null && nature != existing.statisticalNature) {
                LedgerBanner(stringResource(R.string.management_recalculation_risk), LedgerBannerVariant.WARNING)
                LedgerButton(
                    stringResource(R.string.management_open_bulk_recalculation),
                    { actions.onNavigate("JRN-005", emptyMap(), emptyMap()) },
                    Modifier.fillMaxWidth(),
                    LedgerButtonVariant.DANGER,
                )
            }
            SelectorField(
                stringResource(R.string.management_default_account),
                accounts.singleOrNull { it.id == defaultAccountId }?.name ?: stringResource(R.string.management_none),
                { chooser = CategoryEditorChooser.ACCOUNT },
            )
            SelectorField(stringResource(R.string.management_default_card), cards.singleOrNull { it.id == defaultCardId }?.displayName ?: stringResource(R.string.management_none), { chooser = CategoryEditorChooser.CARD }, enabled = cards.isNotEmpty())
            SelectorField(stringResource(R.string.management_default_merchant), merchants.singleOrNull { it.id == defaultMerchantId }?.name ?: stringResource(R.string.management_none), { chooser = CategoryEditorChooser.MERCHANT }, enabled = merchants.isNotEmpty())
            ReferenceDisplayStylePicker(
                selectedIcon = selectedIcon,
                selectedPaletteId = selectedPalette,
                iconSectionLabel = stringResource(R.string.management_appearance_icon),
                colorSectionLabel = stringResource(R.string.management_appearance_color),
                onIconSelected = { selectedIcon = it },
                onPaletteSelected = { palette, color -> selectedPalette = palette; selectedColor = color },
            )
            ContrastPreview(selectedPalette, contrast)
        }
    }
    chooser?.let { target ->
        val choices = when (target) {
            CategoryEditorChooser.PARENT -> parents.map { it.id to it.name }
            CategoryEditorChooser.ACCOUNT -> accounts.map { it.id to it.name }
            CategoryEditorChooser.CARD -> cards.map { it.id to it.displayName }
            CategoryEditorChooser.MERCHANT -> merchants.map { it.id to it.name }
        }
        SearchableReferenceChooser(
            title = stringResource(
                when (target) {
                    CategoryEditorChooser.PARENT -> R.string.management_parent
                    CategoryEditorChooser.ACCOUNT -> R.string.management_default_account
                    CategoryEditorChooser.CARD -> R.string.management_default_card
                    CategoryEditorChooser.MERCHANT -> R.string.management_default_merchant
                },
            ),
            choices = choices,
            selectedId = when (target) {
                CategoryEditorChooser.PARENT -> parentId
                CategoryEditorChooser.ACCOUNT -> defaultAccountId
                CategoryEditorChooser.CARD -> defaultCardId
                CategoryEditorChooser.MERCHANT -> defaultMerchantId
            },
            noneLabel = stringResource(if (target == CategoryEditorChooser.PARENT) R.string.management_no_parent else R.string.management_none),
            onSelected = { selected ->
                when (target) {
                    CategoryEditorChooser.PARENT -> parentId = selected
                    CategoryEditorChooser.ACCOUNT -> {
                        defaultAccountId = selected
                        if (defaultCardId != null && snapshot?.cards?.singleOrNull { it.id == defaultCardId }?.accountId != selected) defaultCardId = null
                    }
                    CategoryEditorChooser.CARD -> defaultCardId = selected
                    CategoryEditorChooser.MERCHANT -> defaultMerchantId = selected
                }
                chooser = null
            },
            onDismiss = { chooser = null },
        )
    }
}

private enum class CategoryEditorChooser { PARENT, ACCOUNT, CARD, MERCHANT }

@Composable
private fun ColumnScope.CategoryReorder(snapshot: ReferenceDataSnapshot?, direction: CategoryDirection, actions: ManagementActions) {
    var ordered by remember(snapshot, direction) { mutableStateOf(snapshot?.categories.orEmpty().filter { it.direction == direction && it.status == CategoryStatus.ACTIVE }.sortedBy { it.sortOrder }) }
    LedgerBanner(stringResource(R.string.management_reorder_explanation), LedgerBannerVariant.INFO)
    LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
        itemsIndexed(ordered, key = { _, category -> category.id.toString() }) { index, category ->
            val haptic = LocalHapticFeedback.current
            val dragLabel = stringResource(R.string.management_drag_category)
            var dragDistance by remember(category.id) { mutableFloatStateOf(0f) }
            Column(verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xxs)) {
                LedgerText(
                    "⋮⋮",
                    LedgerTextRole.LABEL,
                    Modifier
                        .draggable(
                            orientation = Orientation.Vertical,
                            state = rememberDraggableState { delta ->
                                dragDistance += delta
                                if (dragDistance > REORDER_DRAG_THRESHOLD && index < ordered.lastIndex) {
                                    haptic.performLedgerHaptic(LedgerHaptic.SELECTION)
                                    ordered = ordered.moved(index, index + 1)
                                    dragDistance = 0f
                                } else if (dragDistance < -REORDER_DRAG_THRESHOLD && index > 0) {
                                    haptic.performLedgerHaptic(LedgerHaptic.SELECTION)
                                    ordered = ordered.moved(index, index - 1)
                                    dragDistance = 0f
                                }
                            },
                        )
                        .semantics { contentDescription = dragLabel },
                )
                CategoryRow(category, null)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                    LedgerButton(
                        stringResource(R.string.management_move_up),
                        { ordered = ordered.moved(index, index - 1) },
                        Modifier.weight(1f),
                        variant = LedgerButtonVariant.TEXT,
                        enabled = index > 0,
                    )
                    LedgerButton(
                        stringResource(R.string.management_move_down),
                        { ordered = ordered.moved(index, index + 1) },
                        Modifier.weight(1f),
                        variant = LedgerButtonVariant.TEXT,
                        enabled = index < ordered.lastIndex,
                    )
                }
            }
        }
        item { LedgerButton(stringResource(R.string.management_save_order), { actions.onReorderCategories(direction, ordered.map { it.id }) }, Modifier.fillMaxWidth()) }
    }
}

private fun <T> List<T>.moved(from: Int, to: Int): List<T> = if (from !in indices || to !in indices || from == to) {
    this
} else {
    toMutableList().also { list -> list.add(to, list.removeAt(from)) }
}

@Composable
private fun CategoryRemoval(snapshot: ReferenceDataSnapshot?, categoryId: StableId, state: String, actions: ManagementActions) {
    val category = snapshot?.categories?.singleOrNull { it.id == categoryId } ?: return
    var target by remember(categoryId) { mutableStateOf<StableId?>(null) }
    var confirmingTombstone by remember(categoryId) { mutableStateOf(false) }
    var tombstonePhrase by remember(categoryId) { mutableStateOf("") }
    val processing = state == "processing"
    if (state == "hasChildren" || category.childCount > 0) LedgerBanner(stringResource(R.string.management_has_children), LedgerBannerVariant.WARNING)
    if (state == "processing") LedgerLoadingState(label = stringResource(R.string.management_processing))
    LedgerText(stringResource(R.string.management_category_usage, category.historicalTransactionCount), LedgerTextRole.BODY)
    val candidates = snapshot.categories.filter { it.direction == category.direction && it.status == CategoryStatus.ACTIVE && it.id != category.id }
    if (candidates.isNotEmpty()) {
        LedgerChoiceSelector(
            stringResource(R.string.management_target),
            candidates.indexOfFirst { it.id == target },
            candidates.map { it.name },
            { target = candidates[it].id },
            placeholder = stringResource(R.string.management_target),
        )
    }
    LedgerButton(stringResource(R.string.management_reassign), { actions.onRemoveCategory(category.id, category.rowVersion, CategoryRemovalStrategy.REASSIGN, target) }, Modifier.fillMaxWidth(), enabled = target != null && category.childCount == 0L && !processing)
    LedgerButton(stringResource(R.string.management_archive_category), { actions.onRemoveCategory(category.id, category.rowVersion, CategoryRemovalStrategy.ARCHIVE, null) }, Modifier.fillMaxWidth(), variant = LedgerButtonVariant.SECONDARY, enabled = category.childCount == 0L && !processing)
    LedgerButton(
        stringResource(R.string.management_delete_tombstone),
        {
            tombstonePhrase = ""
            confirmingTombstone = true
        },
        Modifier.fillMaxWidth(),
        variant = LedgerButtonVariant.DANGER,
        enabled = category.childCount == 0L && !processing,
    )
    if (confirmingTombstone) {
        val requiredPhrase = stringResource(R.string.management_delete_tombstone_phrase, category.name)
        LedgerDialog(
            title = stringResource(R.string.management_delete_tombstone_title),
            message = stringResource(R.string.management_delete_tombstone_scope, category.name, category.historicalTransactionCount),
            confirmLabel = stringResource(R.string.management_delete_tombstone),
            onConfirm = {
                confirmingTombstone = false
                tombstonePhrase = ""
                actions.onRemoveCategory(category.id, category.rowVersion, CategoryRemovalStrategy.TOMBSTONE, null)
            },
            onDismiss = {
                confirmingTombstone = false
                tombstonePhrase = ""
            },
            danger = true,
            confirmEnabled = tombstonePhrase == requiredPhrase && category.childCount == 0L && !processing,
        ) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs),
            ) {
                LedgerText(stringResource(R.string.management_delete_tombstone_consequence), LedgerTextRole.BODY)
                LedgerText(stringResource(R.string.management_delete_tombstone_unaffected), LedgerTextRole.SUPPORTING)
                if (category.childCount > 0L) LedgerBanner(stringResource(R.string.management_has_children), LedgerBannerVariant.DANGER)
                LedgerTextField(
                    tombstonePhrase,
                    { tombstonePhrase = it },
                    stringResource(R.string.management_delete_tombstone_confirm_label),
                    supportingText = requiredPhrase,
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.MerchantList(snapshot: ReferenceDataSnapshot?, state: String, actions: ManagementActions) {
    var query by remember { mutableStateOf("") }
    val merchants = snapshot?.merchants.orEmpty().filter { query.isBlank() || it.name.contains(query, true) || it.aliases.any { alias -> alias.contains(query, true) } }
    SearchField(query, { query = it.take(MAX_NAME) }, onClear = { query = "" })
    if (state == "empty" || merchants.isEmpty()) {
        LedgerEmptyState(stringResource(R.string.management_merchants_empty), stringResource(R.string.management_merchants_empty_body), stringResource(R.string.management_add_merchant), { actions.onNavigate("MER-002", emptyMap(), emptyMap()) })
        return
    }
    LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
        items(merchants, key = { it.id.toString() }) { merchant -> MerchantRow(merchant) { actions.onNavigate("MER-002", mapOf("merchantId" to merchant.id), emptyMap()) } }
        item { LedgerButton(stringResource(R.string.management_merge_merchants), { actions.onNavigate("MER-003", emptyMap(), emptyMap()) }, Modifier.fillMaxWidth(), variant = LedgerButtonVariant.SECONDARY) }
        item { LedgerButton(stringResource(R.string.management_add_merchant), { actions.onNavigate("MER-002", emptyMap(), emptyMap()) }, Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun MerchantRow(merchant: MerchantReferenceView, onClick: () -> Unit) {
    ReferenceDataRow(ReferenceDataRowUiModel("merchant_item", merchant.name, stringResource(R.string.management_merchant_counts, merchant.currentTransactionCount, merchant.placeCount), status = if (merchant.status == EntityStatus.ARCHIVED) LedgerStatusVariant.ARCHIVED else LedgerStatusVariant.NEUTRAL), onClick)
}

@Composable
private fun MerchantEditor(snapshot: ReferenceDataSnapshot?, merchantId: StableId?, state: String, actions: ManagementActions) {
    val existing = snapshot?.merchants?.singleOrNull { it.id == merchantId }
    var name by rememberLedgerRetainedState("merchant.name") { existing?.name.orEmpty() }
    var aliases by rememberLedgerRetainedState("merchant.aliases") { existing?.aliases.orEmpty() }
    var newAlias by rememberLedgerRetainedState("merchant.newAlias") { "" }
    var attempted by rememberLedgerRetainedState("merchant.attempted") { false }
    val duplicate = snapshot?.merchants.orEmpty().any { it.id != merchantId && it.name.equals(name.trim(), true) }
    val places = snapshot?.places.orEmpty().filter { it.merchantId == merchantId }
    val valid = name.isNotBlank() && !duplicate && state != "duplicateWarning"
    LedgerScaffold(
        modifier = Modifier.fillMaxSize(),
        formContent = true,
        fixedAction = { ManagementSaveBar(true) { attempted = true; if (valid) actions.onSaveMerchant(MerchantSubmission(merchantId, name.trim(), aliases.map(String::trim).filter(String::isNotBlank).toSet())) } },
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(LedgerTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm),
        ) {
            if (state == "duplicateWarning" || duplicate) LedgerBanner(stringResource(R.string.management_duplicate_merchant), LedgerBannerVariant.WARNING)
            if (attempted && !valid) LedgerBanner(stringResource(R.string.management_validation), LedgerBannerVariant.DANGER)
            LedgerTextField(name, { name = it.take(MAX_NAME) }, stringResource(R.string.management_merchant_name), errorText = stringResource(R.string.management_validation).takeIf { attempted && name.isBlank() }, required = true)
            LedgerText(stringResource(R.string.management_aliases), LedgerTextRole.SECTION)
            if (aliases.isEmpty()) LedgerText(stringResource(R.string.management_aliases_empty), LedgerTextRole.SUPPORTING)
            aliases.forEachIndexed { index, alias ->
                val removeDescription = stringResource(R.string.management_remove_alias_target, alias)
                Row(
                    Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = alias },
                    horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs),
                ) {
                    LedgerText(alias, LedgerTextRole.BODY, Modifier.weight(1f))
                    LedgerButton(
                        stringResource(R.string.management_remove_alias),
                        { aliases = aliases.filterIndexed { candidateIndex, _ -> candidateIndex != index } },
                        modifier = Modifier.semantics { contentDescription = removeDescription },
                        variant = LedgerButtonVariant.TEXT,
                        compact = true,
                    )
                }
            }
            LedgerTextField(newAlias, { newAlias = it.take(MAX_NAME) }, stringResource(R.string.management_new_alias), supportingText = stringResource(R.string.management_aliases_body))
            LedgerButton(
                stringResource(R.string.management_add_alias),
                {
                    val candidate = newAlias.trim()
                    if (candidate.isNotEmpty() && aliases.none { it.equals(candidate, true) }) aliases = aliases + candidate
                    newAlias = ""
                },
                Modifier.fillMaxWidth(),
                LedgerButtonVariant.SECONDARY,
                enabled = newAlias.isNotBlank() && aliases.none { it.equals(newAlias.trim(), true) },
            )
            LedgerText(stringResource(R.string.management_places_count, places.size), LedgerTextRole.SECTION)
            places.forEach { place -> PlaceRow(place) { actions.onNavigate("PLC-002", mapOf("placeId" to place.id), emptyMap()) } }
        }
    }
}

@Composable
private fun MerchantMerge(snapshot: ReferenceDataSnapshot?, state: String, actions: ManagementActions) {
    val active = snapshot?.merchants.orEmpty().filter { it.status == EntityStatus.ACTIVE }
    var source by rememberLedgerRetainedState<StableId?>("merchantMerge.source") { null }
    var target by rememberLedgerRetainedState<StableId?>("merchantMerge.target") { null }
    if (state == "invalid" || source == target && source != null) LedgerBanner(stringResource(R.string.management_merge_invalid), LedgerBannerVariant.DANGER)
    if (state == "merging") LedgerLoadingState(label = stringResource(R.string.management_merging))
    if (active.isNotEmpty()) {
        LedgerChoiceSelector(stringResource(R.string.management_source), active.indexOfFirst { it.id == source }, active.map { it.name }, { source = active[it].id }, placeholder = stringResource(R.string.management_source))
        LedgerChoiceSelector(stringResource(R.string.management_target), active.indexOfFirst { it.id == target }, active.map { it.name }, { target = active[it].id }, placeholder = stringResource(R.string.management_target))
    }
    val impact = active.singleOrNull { it.id == source }
    LedgerBanner(stringResource(R.string.management_merge_impact, impact?.currentTransactionCount ?: 0, impact?.placeCount ?: 0), LedgerBannerVariant.INFO)
    LedgerButton(stringResource(R.string.management_confirm_merge), { if (source != null && target != null) actions.onMergeMerchant(requireNotNull(source), requireNotNull(target)) }, Modifier.fillMaxWidth(), enabled = source != null && target != null && source != target)
}

@Composable
private fun ColumnScope.PlaceList(snapshot: ReferenceDataSnapshot?, state: String, actions: ManagementActions, map: PlaceMapSlot) {
    var query by remember { mutableStateOf("") }
    val places = snapshot?.places.orEmpty().filter { query.isBlank() || it.name.contains(query, true) }
    SearchField(query, { query = it.take(MAX_NAME) }, onClear = { query = "" })
    map(places.map { it.toMapPoint() }, false) { _, _ -> }
    if (state == "empty" || places.isEmpty()) {
        LedgerEmptyState(stringResource(R.string.management_places_empty), stringResource(R.string.management_places_empty_body), stringResource(R.string.management_add_place), { actions.onNavigate("PLC-002", emptyMap(), emptyMap()) })
    } else {
        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
            items(places, key = { it.id.toString() }) { place -> PlaceRow(place) { actions.onNavigate("PLC-002", mapOf("placeId" to place.id), emptyMap()) } }
            item { LedgerButton(stringResource(R.string.management_add_place), { actions.onNavigate("PLC-002", emptyMap(), emptyMap()) }, Modifier.fillMaxWidth()) }
        }
    }
}

@Composable
private fun PlaceRow(place: PlaceReferenceView, onClick: () -> Unit) {
    ReferenceDataRow(ReferenceDataRowUiModel("place_item", place.name, stringResource(R.string.management_location_count, place.locationRecordCount), status = if (place.status == EntityStatus.ARCHIVED) LedgerStatusVariant.ARCHIVED else LedgerStatusVariant.NEUTRAL), onClick)
}

@Composable
private fun PlaceEditor(snapshot: ReferenceDataSnapshot?, placeId: StableId?, state: String, actions: ManagementActions, map: PlaceMapSlot) {
    val existing = snapshot?.places?.singleOrNull { it.id == placeId }
    var name by rememberLedgerRetainedState("place.name") { existing?.name.orEmpty() }
    var latitudeText by rememberLedgerRetainedState("place.latitude") {
        (existing?.latitudeE7 ?: DEFAULT_PIN_LATITUDE_E7).toCoordinateText(Locale.ROOT)
    }
    var longitudeText by rememberLedgerRetainedState("place.longitude") {
        (existing?.longitudeE7 ?: DEFAULT_PIN_LONGITUDE_E7).toCoordinateText(Locale.ROOT)
    }
    var merchant by rememberLedgerRetainedState<StableId?>("place.merchant") { existing?.merchantId }
    var attempted by rememberLedgerRetainedState("place.attempted") { false }
    var chooseMerchant by remember(placeId) { mutableStateOf(false) }
    val parsedLatitude = latitudeText.coordinateE7OrNull(LATITUDE_RANGE)
    val parsedLongitude = longitudeText.coordinateE7OrNull(LONGITUDE_RANGE)
    val latitude = parsedLatitude ?: existing?.latitudeE7 ?: DEFAULT_PIN_LATITUDE_E7
    val longitude = parsedLongitude ?: existing?.longitudeE7 ?: DEFAULT_PIN_LONGITUDE_E7
    val valid = name.isNotBlank() && parsedLatitude != null && parsedLongitude != null
    val pin = ManagementMapPoint(existing?.id ?: DRAFT_MAP_ID, name.ifBlank { stringResource(R.string.management_new_place_pin) }, latitude, longitude, existing?.locationRecordCount ?: 0L, draft = existing == null)
    LedgerScaffold(
        modifier = Modifier.fillMaxSize(),
        formContent = true,
        fixedAction = { ManagementSaveBar(true) { attempted = true; if (valid) actions.onSavePlace(PlaceSubmission(placeId, name.trim(), requireNotNull(parsedLatitude), requireNotNull(parsedLongitude), merchant)) } },
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(LedgerTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm),
        ) {
            if (state == "mapUnavailable") LedgerBanner(stringResource(R.string.management_map_unavailable), LedgerBannerVariant.INFO)
            if (attempted && !valid) LedgerBanner(stringResource(R.string.management_validation), LedgerBannerVariant.DANGER)
            LedgerTextField(name, { name = it.take(MAX_NAME) }, stringResource(R.string.management_place_name), errorText = stringResource(R.string.management_validation).takeIf { attempted && name.isBlank() }, required = true)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
                LedgerTextField(
                    latitudeText,
                    { latitudeText = it.take(MAX_COORDINATE_TEXT) },
                    stringResource(R.string.management_latitude_e7),
                    Modifier.weight(1f),
                    errorText = stringResource(R.string.management_coordinate_invalid).takeIf { attempted && parsedLatitude == null },
                    required = true,
                    keyboardType = KeyboardType.Decimal,
                )
                LedgerTextField(
                    longitudeText,
                    { longitudeText = it.take(MAX_COORDINATE_TEXT) },
                    stringResource(R.string.management_longitude_e7),
                    Modifier.weight(1f),
                    errorText = stringResource(R.string.management_coordinate_invalid).takeIf { attempted && parsedLongitude == null },
                    required = true,
                    keyboardType = KeyboardType.Decimal,
                )
            }
            MapPinEditor(pin, state == "mapUnavailable", map) { lat, lon ->
                latitudeText = lat.toCoordinateText(Locale.ROOT)
                longitudeText = lon.toCoordinateText(Locale.ROOT)
            }
            SelectorField(stringResource(R.string.management_optional_merchant), snapshot?.merchants?.singleOrNull { it.id == merchant }?.name ?: stringResource(R.string.management_none), { chooseMerchant = true })
            LedgerBanner(stringResource(R.string.management_no_reverse_geocoding), LedgerBannerVariant.INFO)
        }
    }
    if (chooseMerchant) {
        SearchableReferenceChooser(
            title = stringResource(R.string.management_optional_merchant),
            choices = snapshot?.merchants.orEmpty().filter { it.status == EntityStatus.ACTIVE }.map { it.id to it.name },
            selectedId = merchant,
            noneLabel = stringResource(R.string.management_none),
            onSelected = { merchant = it; chooseMerchant = false },
            onDismiss = { chooseMerchant = false },
        )
    }
}

@Composable
private fun SearchableReferenceChooser(
    title: String,
    choices: List<Pair<StableId, String>>,
    selectedId: StableId?,
    noneLabel: String,
    onSelected: (StableId?) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember(title) { mutableStateOf("") }
    val filtered = remember(choices, query) { choices.filter { query.isBlank() || it.second.contains(query, ignoreCase = true) } }
    LedgerModalDialog(title, onDismiss = onDismiss) {
        SearchField(query, { query = it.take(MAX_NAME) }, onClear = { query = "" })
        LazyColumn(Modifier.fillMaxWidth()) {
            item { LedgerChoiceRow(noneLabel, selectedId == null, { onSelected(null) }) }
            items(filtered, key = { it.first.toString() }) { (id, label) ->
                LedgerChoiceRow(label, selectedId == id, { onSelected(id) })
            }
        }
    }
}

@Composable
private fun PlaceMergeSplit(snapshot: ReferenceDataSnapshot?, sourceId: StableId, state: String, actions: ManagementActions, map: PlaceMapSlot) {
    val source = snapshot?.places?.singleOrNull { it.id == sourceId } ?: return
    val split = state == "split"
    var selectedTab by remember(state) { mutableStateOf(if (split) 1 else 0) }
    LedgerTabRow(
        selectedTab,
        listOf(stringResource(R.string.management_merge), stringResource(R.string.management_split)),
        onSelected = { selectedTab = it },
    )
    val relevantLocations = snapshot.locations.filter { it.placeId == sourceId }
    val locale = LocalLocale.current.platformLocale
    map(
        listOf(source.toMapPoint()) + relevantLocations.map { location ->
            ManagementMapPoint(
                location.id,
                stringResource(R.string.management_location_record, location.capturedAt.localized(locale)),
                location.latitudeE7,
                location.longitudeE7,
                location.currentTransactionCount,
            )
        },
        false,
    ) { _, _ -> }
    if (selectedTab == 0) {
        var target by rememberLedgerRetainedState<StableId?>("placeMerge.target") { null }
        val targets = snapshot.places.filter { it.id != sourceId && it.status == EntityStatus.ACTIVE }
        if (targets.isNotEmpty()) {
            LedgerChoiceSelector(stringResource(R.string.management_target), targets.indexOfFirst { it.id == target }, targets.map { it.name }, { target = targets[it].id }, placeholder = stringResource(R.string.management_target))
        }
        LedgerBanner(stringResource(R.string.management_place_merge_history), LedgerBannerVariant.INFO)
        LedgerButton(stringResource(R.string.management_confirm_merge), { target?.let { actions.onMergePlace(sourceId, it) } }, Modifier.fillMaxWidth(), enabled = target != null && state != "invalid")
    } else {
        var selection by rememberLedgerRetainedState("placeSplit.selection") { emptySet<StableId>() }
        var name by rememberLedgerRetainedState("placeSplit.name") { "" }
        relevantLocations.forEach { location ->
            LedgerToggleRow(
                stringResource(R.string.management_location_record, location.capturedAt.localized(locale)),
                location.id in selection,
                { checked -> selection = if (checked) selection + location.id else selection - location.id },
                supportingText = stringResource(
                    R.string.management_location_record_details,
                    location.latitudeE7.toCoordinateText(locale),
                    location.longitudeE7.toCoordinateText(locale),
                    location.currentTransactionCount,
                ),
            )
        }
        LedgerTextField(name, { name = it.take(MAX_NAME) }, stringResource(R.string.management_new_place_name), required = true)
        LedgerBanner(stringResource(R.string.management_split_revision_notice), LedgerBannerVariant.INFO)
        LedgerButton(stringResource(R.string.management_confirm_split), { actions.onSplitPlace(sourceId, PlaceSubmission(null, name.trim(), source.latitudeE7, source.longitudeE7, source.merchantId), selection.toList()) }, Modifier.fillMaxWidth(), enabled = name.isNotBlank() && selection.isNotEmpty() && state != "invalid")
    }
}

@Composable
private fun ContrastPreview(paletteId: String, contrastRatio: Double) {
    val palette = LedgerTheme.colors.categoryPalette.single { it.id == paletteId }
    LedgerCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().background(palette.container).padding(LedgerTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs),
        ) {
            BasicText(stringResource(R.string.management_contrast_preview), style = LedgerTheme.typography.titleSmall.copy(color = palette.foreground))
            BasicText(stringResource(R.string.management_contrast_ratio, contrastRatio), style = LedgerTheme.typography.bodyMedium.copy(color = palette.foreground))
        }
    }
}

@Composable
private fun MapPinEditor(
    pin: ManagementMapPoint,
    unavailable: Boolean,
    map: PlaceMapSlot,
    onPinMoved: (Int, Int) -> Unit,
) {
    val locale = LocalLocale.current.platformLocale
    LedgerText(stringResource(R.string.management_map_pin_editor), LedgerTextRole.SECTION)
    LedgerText(stringResource(R.string.management_map_pin_instructions), LedgerTextRole.SUPPORTING)
    map(listOf(pin), unavailable, onPinMoved)
    LedgerText(
        stringResource(
            R.string.management_pin_coordinates,
            pin.latitudeE7.toCoordinateText(locale),
            pin.longitudeE7.toCoordinateText(locale),
        ),
        LedgerTextRole.BODY,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
        LedgerButton(stringResource(R.string.management_pin_north), { onPinMoved((pin.latitudeE7 + PIN_NUDGE_E7).coerceIn(LATITUDE_RANGE), pin.longitudeE7) }, Modifier.weight(1f), LedgerButtonVariant.SECONDARY, compact = true)
        LedgerButton(stringResource(R.string.management_pin_south), { onPinMoved((pin.latitudeE7 - PIN_NUDGE_E7).coerceIn(LATITUDE_RANGE), pin.longitudeE7) }, Modifier.weight(1f), LedgerButtonVariant.SECONDARY, compact = true)
        LedgerButton(stringResource(R.string.management_pin_west), { onPinMoved(pin.latitudeE7, (pin.longitudeE7 - PIN_NUDGE_E7).coerceIn(LONGITUDE_RANGE)) }, Modifier.weight(1f), LedgerButtonVariant.SECONDARY, compact = true)
        LedgerButton(stringResource(R.string.management_pin_east), { onPinMoved(pin.latitudeE7, (pin.longitudeE7 + PIN_NUDGE_E7).coerceIn(LONGITUDE_RANGE)) }, Modifier.weight(1f), LedgerButtonVariant.SECONDARY, compact = true)
    }
}

@Composable
private fun ManagementSaveBar(enabled: Boolean, onSave: () -> Unit) {
    LedgerCard(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().padding(LedgerTheme.spacing.sm)) {
            LedgerButton(stringResource(R.string.management_save), onSave, Modifier.fillMaxWidth().testTag(LedgerTestTags.SAVE), enabled = enabled)
        }
    }
}

private fun PlaceReferenceView.toMapPoint(): ManagementMapPoint =
    ManagementMapPoint(id, name, latitudeE7, longitudeE7, locationRecordCount)

private fun Int.toCoordinateText(locale: Locale): String = String.format(locale, "%.5f", this / E7_DIVISOR)

private fun String.coordinateE7OrNull(range: IntRange): Int? = runCatching {
    BigDecimal(trim()).multiply(BigDecimal.valueOf(E7_DIVISOR.toLong())).setScale(0, RoundingMode.HALF_UP).intValueExact()
}.getOrNull()?.takeIf { it in range }

@Composable
private fun java.time.Instant.localized(locale: Locale): String = LedgerDateFormatterRuntime.dateTimeFormatter(locale)
    .withZone(LedgerTheme.timeZone)
    .format(this)

private fun contrastRatio(background: Color, foreground: Color): Double {
    val light = maxOf(background.luminance(), foreground.luminance()).toDouble()
    val dark = minOf(background.luminance(), foreground.luminance()).toDouble()
    return (light + CONTRAST_OFFSET) / (dark + CONTRAST_OFFSET)
}

private fun actualState(screenId: String, args: Map<String, String>, snapshot: ReferenceDataSnapshot?, pending: Boolean): String = when (screenId) {
    "MGT-001" -> "content"
    "CAT-001" -> if (snapshot?.categories.orEmpty().none { it.direction == args.direction() }) "empty" else "content"
    "CAT-002" -> if (args.containsKey("categoryId")) "edit" else "create"
    "CAT-003" -> "editing"
    "CAT-004" -> if (pending) {
        "processing"
    } else {
        snapshot?.categories?.singleOrNull { it.id == args.stableId("categoryId") }?.let {
            if (it.childCount > 0) {
                "hasChildren"
            } else if (it.historicalTransactionCount > 0) {
                "used"
            } else {
                "unused"
            }
        } ?: "unused"
    }
    "MER-001" -> if (snapshot?.merchants.isNullOrEmpty()) "empty" else "content"
    "MER-002" -> if (args.containsKey("merchantId")) "edit" else "create"
    "MER-003" -> if (pending) "merging" else "editing"
    "PLC-001" -> if (snapshot?.places.isNullOrEmpty()) "empty" else "content"
    "PLC-002" -> if (args.containsKey("placeId")) "edit" else "create"
    "PLC-003" -> "merge"
    else -> error("unsupported P12 management screen")
}

private fun Map<String, String>.direction(): CategoryDirection = if (get("direction") == "INCOME") CategoryDirection.INCOME else CategoryDirection.EXPENSE
private fun Map<String, String>.stableId(name: String): StableId? = get(name)?.let { StableId.parse(it).getOrNull() }
private fun Map<String, String>.requireStableId(name: String): StableId = requireNotNull(stableId(name))
private fun String.sanitizeCode(): String = uppercase().replace(Regex("[^A-Z0-9_]"), "_").take(48).let { if (it.firstOrNull()?.isLetter() == true) it else "REFERENCE_LOAD_FAILED" }
private fun CategoryDirection.defaultNature(): StatisticalNature = if (this == CategoryDirection.EXPENSE) StatisticalNature.CONSUMPTION_EXPENSE else StatisticalNature.REGULAR_INCOME
private fun CategoryDirection.alternateNature(): StatisticalNature = if (this == CategoryDirection.EXPENSE) StatisticalNature.NON_CONSUMPTION_EXPENSE else StatisticalNature.NON_RECURRING_INCOME

@Composable private fun CategoryDirection.firstNatureLabel(): String = stringResource(if (this == CategoryDirection.EXPENSE) R.string.management_consumption else R.string.management_regular_income)

@Composable private fun CategoryDirection.secondNatureLabel(): String = stringResource(if (this == CategoryDirection.EXPENSE) R.string.management_non_consumption else R.string.management_non_recurring_income)

private val SUPPORTED_SCREENS = setOf("MGT-001", "CAT-001", "CAT-002", "CAT-003", "CAT-004", "MER-001", "MER-002", "MER-003", "PLC-001", "PLC-002", "PLC-003")
private val VERTICALLY_SCROLLABLE_SCREENS = setOf("MGT-001", "CAT-004", "MER-003", "PLC-003")
private const val MAX_NAME = 80
private const val SEARCH_FEEDBACK_MILLIS = 150L
private const val REORDER_DRAG_THRESHOLD = 72f
private const val DEFAULT_PIN_LATITUDE_E7 = 356_812_360
private const val DEFAULT_PIN_LONGITUDE_E7 = 1_397_671_250
private const val PIN_NUDGE_E7 = 1_000
private const val E7_DIVISOR = 10_000_000.0
private const val MAX_COORDINATE_TEXT = 18
private const val MIN_TEXT_CONTRAST = 4.5
private const val CONTRAST_OFFSET = 0.05
private val DRAFT_MAP_ID = StableId.fromBytes(ByteArray(StableId.BYTE_COUNT) { 0x55.toByte() }).getOrNull() ?: error("draft map id")
private val LATITUDE_RANGE = -900_000_000..900_000_000
private val LONGITUDE_RANGE = -1_800_000_000..1_800_000_000

private fun Int.coordinateText(): String = BigDecimal.valueOf(toLong(), 7).stripTrailingZeros().toPlainString()

private fun String.coordinateE7OrNull(): Int? = runCatching {
    BigDecimal(trim()).movePointRight(7).setScale(0, RoundingMode.HALF_UP).intValueExact()
}.getOrNull()

private fun String.filterCoordinate(): String = filterIndexed { index, character ->
    character.isDigit() || character == '.' || character == '-' && index == 0
}
