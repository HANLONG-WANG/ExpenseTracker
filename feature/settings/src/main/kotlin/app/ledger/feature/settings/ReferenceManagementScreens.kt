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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.ledger.core.common.StableId
import app.ledger.core.common.getOrNull
import app.ledger.core.designsystem.LedgerBanner
import app.ledger.core.designsystem.LedgerBannerVariant
import app.ledger.core.designsystem.LedgerButton
import app.ledger.core.designsystem.LedgerButtonVariant
import app.ledger.core.designsystem.LedgerCard
import app.ledger.core.designsystem.LedgerChoiceRow
import app.ledger.core.designsystem.LedgerEmptyState
import app.ledger.core.designsystem.LedgerErrorState
import app.ledger.core.designsystem.LedgerIcon
import app.ledger.core.designsystem.LedgerLoadingState
import app.ledger.core.designsystem.LedgerReferenceDisplayDefaults
import app.ledger.core.designsystem.LedgerSaveFab
import app.ledger.core.designsystem.LedgerStatusVariant
import app.ledger.core.designsystem.LedgerTabRow
import app.ledger.core.designsystem.LedgerTestTags
import app.ledger.core.designsystem.LedgerText
import app.ledger.core.designsystem.LedgerTextField
import app.ledger.core.designsystem.LedgerTextRole
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.LedgerToggleRow
import app.ledger.core.designsystem.ReferenceDataRow
import app.ledger.core.designsystem.ReferenceDataRowUiModel
import app.ledger.core.designsystem.ReferenceDisplayStyleIcons
import app.ledger.core.designsystem.ReferenceDisplayStylePicker
import app.ledger.core.designsystem.SearchField
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

@Composable
public fun ReferenceManagementDestination(
    screenId: String,
    encodedArguments: Map<String, String>,
    dataState: ManagementDataState,
    onAction: (ManagementScreenAction) -> Unit,
    placeMap: PlaceMapSlot,
    pending: Boolean,
    stateOverride: ManagementRequiredState? = null,
    modifier: Modifier = Modifier,
) {
    val actions = managementActions(onAction)
    require(screenId in SUPPORTED_SCREENS)
    require(stateOverride == null || stateOverride.screenId == screenId)
    val snapshot = (dataState as? ManagementDataState.Content)?.snapshot
    val state = stateOverride?.contractName ?: actualState(screenId, encodedArguments, snapshot, pending)
    Column(
        modifier.fillMaxSize().testTag(LedgerTestTags.P12_MANAGEMENT_ROOT)
            .padding(vertical = LedgerTheme.spacing.xs),
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
private fun CategoryList(snapshot: ReferenceDataSnapshot?, direction: CategoryDirection, state: String, actions: ManagementActions) {
    var query by remember(direction) { mutableStateOf("") }
    val categories = snapshot?.categories.orEmpty().filter { it.direction == direction }.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
    LedgerTabRow(
        if (direction == CategoryDirection.EXPENSE) 0 else 1,
        listOf(stringResource(R.string.management_expense), stringResource(R.string.management_income)),
        onSelected = { index -> actions.onNavigate("CAT-001", emptyMap(), mapOf("direction" to if (index == 0) "EXPENSE" else "INCOME")) },
    )
    SearchField(query, { query = it.take(MAX_NAME) }, onClear = { query = "" })
    if (state == "empty" || categories.isEmpty()) {
        LedgerEmptyState(stringResource(R.string.management_categories_empty), stringResource(R.string.management_categories_empty_body), stringResource(R.string.management_add_category), { actions.onNavigate("CAT-002", emptyMap(), mapOf("direction" to direction.name)) })
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
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
private fun CategoryRow(category: CategoryReferenceView, onClick: () -> Unit) {
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
    var name by remember(categoryId) { mutableStateOf(existing?.name.orEmpty()) }
    var parentId by remember(categoryId) { mutableStateOf(existing?.parentId) }
    var nature by remember(categoryId) { mutableStateOf(existing?.statisticalNature ?: direction.defaultNature()) }
    var selectedIcon by remember(categoryId) {
        mutableStateOf(
            ReferenceDisplayStyleIcons.firstOrNull { it.name.equals(existing?.iconKey, ignoreCase = true) }
                ?: LedgerIcon.RECORD,
        )
    }
    var selectedColor by remember(categoryId) {
        mutableStateOf(existing?.colorArgb ?: LedgerReferenceDisplayDefaults.COLOR_ARGB)
    }
    var selectedPalette by remember(categoryId) {
        mutableStateOf(LedgerReferenceDisplayDefaults.paletteId(selectedColor))
    }
    var defaultAccountId by remember(categoryId) { mutableStateOf(existing?.defaultAccountId) }
    var defaultCardId by remember(categoryId) { mutableStateOf(existing?.defaultCardId) }
    var defaultMerchantId by remember(categoryId) { mutableStateOf(existing?.defaultMerchantId) }
    val parents = snapshot?.categories.orEmpty().filter { it.direction == direction && it.depth == 1 && it.status == CategoryStatus.ACTIVE && it.id != categoryId }
    val accounts = snapshot?.accounts.orEmpty().filter { it.status == EntityStatus.ACTIVE }
    val cards = snapshot?.cards.orEmpty().filter { card ->
        card.status == EntityStatus.ACTIVE && (defaultAccountId == null || card.accountId == defaultAccountId)
    }
    val merchants = snapshot?.merchants.orEmpty().filter { it.status == EntityStatus.ACTIVE }
    if (state == "parentLocked" || existing?.depth == 2) LedgerBanner(stringResource(R.string.management_parent_locked), LedgerBannerVariant.INFO)
    if (state == "contrastWarning") LedgerBanner(stringResource(R.string.management_contrast_warning), LedgerBannerVariant.WARNING)
    if (state == "validationError") LedgerBanner(stringResource(R.string.management_validation), LedgerBannerVariant.DANGER)
    LedgerTextField(name, { name = it.take(MAX_NAME) }, stringResource(R.string.management_name), required = true)
    SelectorField(stringResource(R.string.management_parent), parents.singleOrNull { it.id == parentId }?.name ?: stringResource(R.string.management_no_parent), { parentId = if (parentId == null) parents.firstOrNull()?.id else null }, enabled = existing?.depth != 2)
    LedgerChoiceRow(direction.firstNatureLabel(), nature == direction.defaultNature(), { nature = direction.defaultNature() })
    LedgerChoiceRow(direction.secondNatureLabel(), nature != direction.defaultNature(), { nature = direction.alternateNature() })
    LedgerBanner(stringResource(R.string.management_statistical_snapshot), LedgerBannerVariant.INFO)
    SelectorField(
        stringResource(R.string.management_default_account),
        accounts.singleOrNull { it.id == defaultAccountId }?.name ?: stringResource(R.string.management_none),
        {
            defaultAccountId = nextSelectableId(defaultAccountId, accounts.map { it.id })
            if (defaultCardId != null && snapshot?.cards?.singleOrNull { it.id == defaultCardId }?.accountId != defaultAccountId) {
                defaultCardId = null
            }
        },
    )
    SelectorField(
        stringResource(R.string.management_default_card),
        cards.singleOrNull { it.id == defaultCardId }?.displayName ?: stringResource(R.string.management_none),
        { defaultCardId = nextSelectableId(defaultCardId, cards.map { it.id }) },
        enabled = cards.isNotEmpty(),
    )
    SelectorField(
        stringResource(R.string.management_default_merchant),
        merchants.singleOrNull { it.id == defaultMerchantId }?.name ?: stringResource(R.string.management_none),
        { defaultMerchantId = nextSelectableId(defaultMerchantId, merchants.map { it.id }) },
        enabled = merchants.isNotEmpty(),
    )
    ReferenceDisplayStylePicker(
        selectedIcon = selectedIcon,
        selectedPaletteId = selectedPalette,
        iconSectionLabel = stringResource(R.string.management_appearance_icon),
        colorSectionLabel = stringResource(R.string.management_appearance_color),
        onIconSelected = { selectedIcon = it },
        onPaletteSelected = { palette, color ->
            selectedPalette = palette
            selectedColor = color
        },
    )
    LedgerSaveFab(
        {
            actions.onSaveCategory(
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
        },
        enabled = name.isNotBlank(),
    )
}

private fun nextSelectableId(current: StableId?, choices: List<StableId>): StableId? = when {
    choices.isEmpty() -> null
    current == null -> choices.first()
    else -> choices.getOrNull(choices.indexOf(current) + 1)
}

@Composable
private fun CategoryReorder(snapshot: ReferenceDataSnapshot?, direction: CategoryDirection, actions: ManagementActions) {
    var ordered by remember(snapshot, direction) { mutableStateOf(snapshot?.categories.orEmpty().filter { it.direction == direction && it.status == CategoryStatus.ACTIVE }.sortedBy { it.sortOrder }) }
    LedgerBanner(stringResource(R.string.management_reorder_explanation), LedgerBannerVariant.INFO)
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
        itemsIndexed(ordered, key = { _, category -> category.id.toString() }) { index, category ->
            Column(verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xxs)) {
                CategoryRow(category) { }
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
    if (state == "hasChildren" || category.childCount > 0) LedgerBanner(stringResource(R.string.management_has_children), LedgerBannerVariant.WARNING)
    if (state == "processing") LedgerLoadingState(label = stringResource(R.string.management_processing))
    LedgerText(stringResource(R.string.management_category_usage, category.historicalTransactionCount), LedgerTextRole.BODY)
    snapshot.categories.filter { it.direction == category.direction && it.status == CategoryStatus.ACTIVE && it.id != category.id }.forEach { candidate -> LedgerChoiceRow(candidate.name, target == candidate.id, { target = candidate.id }) }
    LedgerButton(stringResource(R.string.management_reassign), { actions.onRemoveCategory(category.id, category.rowVersion, CategoryRemovalStrategy.REASSIGN, target) }, Modifier.fillMaxWidth(), enabled = target != null && category.childCount == 0L)
    LedgerButton(stringResource(R.string.management_archive_category), { actions.onRemoveCategory(category.id, category.rowVersion, CategoryRemovalStrategy.ARCHIVE, null) }, Modifier.fillMaxWidth(), variant = LedgerButtonVariant.SECONDARY, enabled = category.childCount == 0L)
    LedgerButton(stringResource(R.string.management_delete_tombstone), { actions.onRemoveCategory(category.id, category.rowVersion, CategoryRemovalStrategy.TOMBSTONE, null) }, Modifier.fillMaxWidth(), variant = LedgerButtonVariant.DANGER, enabled = category.childCount == 0L)
}

@Composable
private fun MerchantList(snapshot: ReferenceDataSnapshot?, state: String, actions: ManagementActions) {
    var query by remember { mutableStateOf("") }
    val merchants = snapshot?.merchants.orEmpty().filter { query.isBlank() || it.name.contains(query, true) || it.aliases.any { alias -> alias.contains(query, true) } }
    SearchField(query, { query = it.take(MAX_NAME) }, onClear = { query = "" })
    if (state == "empty" || merchants.isEmpty()) {
        LedgerEmptyState(stringResource(R.string.management_merchants_empty), stringResource(R.string.management_merchants_empty_body), stringResource(R.string.management_add_merchant), { actions.onNavigate("MER-002", emptyMap(), emptyMap()) })
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
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
    var name by remember(merchantId) { mutableStateOf(existing?.name.orEmpty()) }
    var aliases by remember(merchantId) { mutableStateOf(existing?.aliases?.joinToString(", ").orEmpty()) }
    val duplicate = snapshot?.merchants.orEmpty().any { it.id != merchantId && it.name.equals(name.trim(), true) }
    if (state == "duplicateWarning" || duplicate) LedgerBanner(stringResource(R.string.management_duplicate_merchant), LedgerBannerVariant.WARNING)
    LedgerTextField(name, { name = it.take(MAX_NAME) }, stringResource(R.string.management_merchant_name), required = true)
    LedgerTextField(aliases, { aliases = it.take(MAX_ALIASES) }, stringResource(R.string.management_aliases), supportingText = stringResource(R.string.management_aliases_body))
    val places = snapshot?.places.orEmpty().filter { it.merchantId == merchantId }
    LedgerText(stringResource(R.string.management_places_count, places.size), LedgerTextRole.SECTION)
    places.forEach { place ->
        PlaceRow(place) { actions.onNavigate("PLC-002", mapOf("placeId" to place.id), emptyMap()) }
    }
    LedgerSaveFab({ actions.onSaveMerchant(MerchantSubmission(merchantId, name.trim(), aliases.split(',').map(String::trim).filter(String::isNotEmpty).toSet())) }, enabled = name.isNotBlank() && !duplicate)
}

@Composable
private fun MerchantMerge(snapshot: ReferenceDataSnapshot?, state: String, actions: ManagementActions) {
    val active = snapshot?.merchants.orEmpty().filter { it.status == EntityStatus.ACTIVE }
    var source by remember { mutableStateOf<StableId?>(null) }
    var target by remember { mutableStateOf<StableId?>(null) }
    if (state == "invalid" || source == target && source != null) LedgerBanner(stringResource(R.string.management_merge_invalid), LedgerBannerVariant.DANGER)
    if (state == "merging") LedgerLoadingState(label = stringResource(R.string.management_merging))
    LedgerText(stringResource(R.string.management_source), LedgerTextRole.SECTION)
    active.forEach { LedgerChoiceRow(it.name, source == it.id, { source = it.id }) }
    LedgerText(stringResource(R.string.management_target), LedgerTextRole.SECTION)
    active.forEach { LedgerChoiceRow(it.name, target == it.id, { target = it.id }) }
    val impact = active.singleOrNull { it.id == source }
    LedgerBanner(stringResource(R.string.management_merge_impact, impact?.currentTransactionCount ?: 0, impact?.placeCount ?: 0), LedgerBannerVariant.INFO)
    LedgerButton(stringResource(R.string.management_confirm_merge), { if (source != null && target != null) actions.onMergeMerchant(requireNotNull(source), requireNotNull(target)) }, Modifier.fillMaxWidth(), enabled = source != null && target != null && source != target)
}

@Composable
private fun PlaceList(snapshot: ReferenceDataSnapshot?, state: String, actions: ManagementActions, map: PlaceMapSlot) {
    var query by remember { mutableStateOf("") }
    val places = snapshot?.places.orEmpty().filter { query.isBlank() || it.name.contains(query, true) }
    SearchField(query, { query = it.take(MAX_NAME) }, onClear = { query = "" })
    map(places, false)
    if (state == "empty" || places.isEmpty()) {
        LedgerEmptyState(stringResource(R.string.management_places_empty), stringResource(R.string.management_places_empty_body), stringResource(R.string.management_add_place), { actions.onNavigate("PLC-002", emptyMap(), emptyMap()) })
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.xs)) {
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
    var name by remember(placeId) { mutableStateOf(existing?.name.orEmpty()) }
    var latitude by remember(placeId) { mutableStateOf(existing?.latitudeE7?.toString().orEmpty()) }
    var longitude by remember(placeId) { mutableStateOf(existing?.longitudeE7?.toString().orEmpty()) }
    var merchant by remember(placeId) { mutableStateOf(existing?.merchantId) }
    if (state == "mapUnavailable") LedgerBanner(stringResource(R.string.management_map_unavailable), LedgerBannerVariant.INFO)
    map(listOfNotNull(existing), state == "mapUnavailable")
    LedgerTextField(name, { name = it.take(MAX_NAME) }, stringResource(R.string.management_place_name), required = true)
    LedgerTextField(latitude, { latitude = it.filter { char -> char.isDigit() || char == '-' }.take(COORDINATE_LENGTH) }, stringResource(R.string.management_latitude_e7), required = true)
    LedgerTextField(longitude, { longitude = it.filter { char -> char.isDigit() || char == '-' }.take(COORDINATE_LENGTH) }, stringResource(R.string.management_longitude_e7), required = true)
    SelectorField(stringResource(R.string.management_optional_merchant), snapshot?.merchants?.singleOrNull { it.id == merchant }?.name ?: stringResource(R.string.management_none), { merchant = if (merchant == null) snapshot?.merchants?.firstOrNull { it.status == EntityStatus.ACTIVE }?.id else null })
    LedgerBanner(stringResource(R.string.management_no_reverse_geocoding), LedgerBannerVariant.INFO)
    LedgerSaveFab({
        val lat = latitude.toIntOrNull() ?: return@LedgerSaveFab
        val lon = longitude.toIntOrNull() ?: return@LedgerSaveFab
        actions.onSavePlace(PlaceSubmission(placeId, name.trim(), lat, lon, merchant))
    }, enabled = name.isNotBlank() && latitude.toIntOrNull() in LATITUDE_RANGE && longitude.toIntOrNull() in LONGITUDE_RANGE)
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
    map(listOf(source), false)
    if (selectedTab == 0) {
        var target by remember { mutableStateOf<StableId?>(null) }
        snapshot.places.filter { it.id != sourceId && it.status == EntityStatus.ACTIVE }.forEach { LedgerChoiceRow(it.name, target == it.id, { target = it.id }) }
        LedgerBanner(stringResource(R.string.management_place_merge_history), LedgerBannerVariant.INFO)
        LedgerButton(stringResource(R.string.management_confirm_merge), { target?.let { actions.onMergePlace(sourceId, it) } }, Modifier.fillMaxWidth(), enabled = target != null && state != "invalid")
    } else {
        var selection by remember(sourceId) { mutableStateOf(emptySet<StableId>()) }
        var name by remember(sourceId) { mutableStateOf("") }
        relevantLocations.forEach { location -> LedgerToggleRow(stringResource(R.string.management_location_record, location.capturedAt.toString()), location.id in selection, { checked -> selection = if (checked) selection + location.id else selection - location.id }, supportingText = stringResource(R.string.management_location_transactions, location.currentTransactionCount)) }
        LedgerTextField(name, { name = it.take(MAX_NAME) }, stringResource(R.string.management_new_place_name), required = true)
        LedgerBanner(stringResource(R.string.management_split_revision_notice), LedgerBannerVariant.INFO)
        LedgerButton(stringResource(R.string.management_confirm_split), { actions.onSplitPlace(sourceId, PlaceSubmission(null, name.trim(), source.latitudeE7, source.longitudeE7, source.merchantId), selection.toList()) }, Modifier.fillMaxWidth(), enabled = name.isNotBlank() && selection.isNotEmpty() && state != "invalid")
    }
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
private const val MAX_NAME = 80
private const val MAX_ALIASES = 600
private const val COORDINATE_LENGTH = 11
private val LATITUDE_RANGE = -900_000_000..900_000_000
private val LONGITUDE_RANGE = -1_800_000_000..1_800_000_000
