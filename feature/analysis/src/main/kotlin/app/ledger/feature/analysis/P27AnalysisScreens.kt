@file:Suppress("FunctionNaming", "LongMethod", "TooManyFunctions", "ktlint:standard:function-naming")

package app.ledger.feature.analysis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import app.ledger.analytics.domain.ConsumptionMapAggregation
import app.ledger.analytics.domain.ConsumptionMapDetail
import app.ledger.analytics.domain.ConsumptionMapFilterOption
import app.ledger.analytics.domain.ConsumptionMapFilterOptions
import app.ledger.analytics.domain.ConsumptionMapGroupKind
import app.ledger.analytics.domain.ConsumptionMapMode
import app.ledger.analytics.domain.ConsumptionMapPresentation
import app.ledger.analytics.domain.ConsumptionMapResult
import app.ledger.analytics.domain.ConsumptionMapWeight
import app.ledger.core.designsystem.AccessibleDataTable
import app.ledger.core.designsystem.AccessibleTableUiModel
import app.ledger.core.designsystem.FilterChipUiModel
import app.ledger.core.designsystem.FilterDimensionUiModel
import app.ledger.core.designsystem.FilterBuilderActionBar
import app.ledger.core.designsystem.LedgerBanner
import app.ledger.core.designsystem.LedgerBannerVariant
import app.ledger.core.designsystem.LedgerButton
import app.ledger.core.designsystem.LedgerButtonVariant
import app.ledger.core.designsystem.LedgerCard
import app.ledger.core.designsystem.LedgerChoiceSelector
import app.ledger.core.designsystem.LedgerEmptyState
import app.ledger.core.designsystem.LedgerLoadingState
import app.ledger.core.designsystem.LedgerScaffold
import app.ledger.core.designsystem.LedgerTestTags
import app.ledger.core.designsystem.LedgerText
import app.ledger.core.designsystem.LedgerTextRole
import app.ledger.core.designsystem.LedgerDateFormatterRuntime
import app.ledger.core.designsystem.LedgerTheme
import app.ledger.core.designsystem.SelectorField
import app.ledger.core.money.LocaleNumberFormatter
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun ConsumptionMapScreen(
    state: AnalysisFeatureState,
    actions: AnalysisActions,
    mapContent: @Composable (ConsumptionMapResult, Boolean) -> Unit,
) {
    if (state.presentation == AnalysisPresentation.LOADING) {
        LedgerLoadingState(
            Modifier.fillMaxSize().testTag(LedgerTestTags.CONSUMPTION_MAP),
            stringResource(R.string.analysis_map_loading),
        )
        return
    }
    val result = state.consumptionMap
    if (result == null || state.presentation == AnalysisPresentation.NO_LOCATION_DATA) {
        LedgerEmptyState(
            stringResource(R.string.analysis_map_empty),
            stringResource(R.string.analysis_map_empty_body),
            stringResource(R.string.analysis_retry),
            actions.onRetry,
            Modifier.fillMaxSize().testTag(LedgerTestTags.CONSUMPTION_MAP),
        )
        return
    }
    val unavailable = state.presentation == AnalysisPresentation.MAP_UNAVAILABLE
    LedgerScaffold(
        Modifier.fillMaxSize().testTag(LedgerTestTags.CONSUMPTION_MAP),
        fixedAction = { FilterBuilderActionBar(actions.onResetMapFilters, actions.onRetry) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(LedgerTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.md),
        ) {
            item { MapControls(result, state.consumptionMapFilterOptions, actions) }
            item {
                LedgerBanner(
                    stringResource(R.string.analysis_map_historical_fx),
                    LedgerBannerVariant.INFO,
                )
            }
            item { mapContent(result, unavailable) }
            if (result.resultLimited) {
                item { LedgerBanner(stringResource(R.string.analysis_map_viewport_limited), LedgerBannerVariant.NEUTRAL) }
            }
            if (unavailable) {
                item { LedgerText(stringResource(R.string.analysis_map_list_alternative), LedgerTextRole.SECTION) }
                items(result.points, key = { it.id.toString() }) { point ->
                    MapPointRow(result, point, actions)
                }
            }
        }
    }
}

@Composable
private fun MapControls(
    result: ConsumptionMapResult,
    filterOptions: ConsumptionMapFilterOptions?,
    actions: AnalysisActions,
) {
    val query = result.query
    val locale = LocalLocale.current.platformLocale
    val accountSelection = query.filters.accountIds.map { it.value }.toSet()
    val categorySelection = query.filters.categoryIds.map { it.value }.toSet()
    val merchantSelection = query.filters.merchantIds.map { it.value }.toSet()
    val placeSelection = query.filters.placeIds.map { it.value }.toSet()
    val projectSelection = query.filters.projectIds.map { it.value }.toSet()
    val selectedDimensions = listOfNotNull(
        selectedFilterDimension(
            stringResource(R.string.analysis_map_filter_account),
            "account",
            filterOptions?.accounts.orEmpty(),
            accountSelection,
        ),
        selectedFilterDimension(
            stringResource(R.string.analysis_category),
            "category",
            filterOptions?.categories.orEmpty(),
            categorySelection,
        ),
        selectedFilterDimension(
            stringResource(R.string.analysis_map_filter_merchant),
            "merchant",
            filterOptions?.merchants.orEmpty(),
            merchantSelection,
        ),
        selectedFilterDimension(
            stringResource(R.string.analysis_map_filter_place),
            "place",
            filterOptions?.places.orEmpty(),
            placeSelection,
        ),
        selectedFilterDimension(
            stringResource(R.string.analysis_map_filter_project),
            "project",
            filterOptions?.projects.orEmpty(),
            projectSelection,
        ),
        query.filters.minimumBaseAmountMinor?.let { minimum ->
            FilterDimensionUiModel(
                stringResource(R.string.analysis_amount),
                listOf(
                    FilterChipUiModel(
                        "amount-minimum",
                        stringResource(
                            R.string.analysis_map_filter_minimum_amount,
                            AnalysisPolicy.money(minimum, result.baseCurrency, locale).formatted,
                        ),
                    ),
                ),
            )
        },
        if (query.filters.hidesTransfersRepaymentsAndLoans) {
            null
        } else {
            FilterDimensionUiModel(
                stringResource(R.string.analysis_map_special_transactions),
                listOf(FilterChipUiModel("special-transactions", stringResource(R.string.analysis_map_special_included))),
            )
        },
    )
    Column(
        Modifier.fillMaxWidth().testTag(LedgerTestTags.CONSUMPTION_MAP_CONTROLS),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm),
    ) {
        LedgerText(
            stringResource(
                R.string.analysis_map_period_value,
                query.period.start.format(LedgerDateFormatterRuntime.formatter(locale)),
                query.period.endInclusive.format(LedgerDateFormatterRuntime.formatter(locale)),
            ),
            LedgerTextRole.BODY,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
            LedgerButton(
                stringResource(R.string.analysis_previous_period),
                actions.onPreviousPeriod,
                Modifier.weight(1f),
                compact = true,
                variant = LedgerButtonVariant.SECONDARY,
            )
            LedgerButton(
                stringResource(R.string.analysis_next_period),
                actions.onNextPeriod,
                Modifier.weight(1f),
                compact = true,
                variant = LedgerButtonVariant.SECONDARY,
            )
        }
        LedgerChoiceSelector(
            stringResource(R.string.analysis_map_mode),
            query.mode.ordinal,
            ConsumptionMapMode.entries.map { mapModeLabel(it) },
            { actions.onSelectMapMode(ConsumptionMapMode.entries[it]) },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
            LedgerChoiceSelector(
                stringResource(R.string.analysis_map_weight),
                query.weight.ordinal,
                ConsumptionMapWeight.entries.map { mapWeightLabel(it) },
                { actions.onSelectMapWeight(ConsumptionMapWeight.entries[it]) },
                Modifier.weight(1f),
            )
            LedgerChoiceSelector(
                stringResource(R.string.analysis_map_grouping),
                query.aggregation.ordinal,
                ConsumptionMapAggregation.entries.map { mapAggregationLabel(it) },
                { actions.onSelectMapAggregation(ConsumptionMapAggregation.entries[it]) },
                Modifier.weight(1f),
            )
        }
        LedgerChoiceSelector(
            stringResource(R.string.analysis_map_presentation),
            query.presentation.ordinal,
            ConsumptionMapPresentation.entries.map { mapPresentationLabel(it) },
            { actions.onSelectMapPresentation(ConsumptionMapPresentation.entries[it]) },
        )
        LedgerChoiceSelector(
            stringResource(R.string.analysis_map_special_transactions),
            if (query.filters.hidesTransfersRepaymentsAndLoans) 0 else 1,
            listOf(stringResource(R.string.analysis_map_special_hidden), stringResource(R.string.analysis_map_special_included)),
            { index ->
                val include = index == 1
                if (include == query.filters.hidesTransfersRepaymentsAndLoans) actions.onToggleMapSpecialTransactions()
            },
        )
        LedgerText(stringResource(R.string.analysis_map_complete_filters), LedgerTextRole.SECTION)
        app.ledger.core.designsystem.FilterBuilder(
            dimensions = selectedDimensions,
            naturalLanguageSummary = stringResource(R.string.analysis_map_filter_summary),
            onRemove = { actions.onRemoveMapFilter(it.stableKey) },
            onReset = actions.onResetMapFilters,
            onApply = actions.onRetry,
            showActions = false,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
            MapEntityChoiceSelector(
                stringResource(R.string.analysis_map_filter_account),
                "ACCOUNT",
                filterOptions?.accounts.orEmpty(),
                accountSelection,
                actions,
                Modifier.weight(1f),
            )
            MapEntityChoiceSelector(
                stringResource(R.string.analysis_category),
                "CATEGORY",
                filterOptions?.categories.orEmpty(),
                categorySelection,
                actions,
                Modifier.weight(1f),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
            MapEntityChoiceSelector(
                stringResource(R.string.analysis_map_filter_merchant),
                "MERCHANT",
                filterOptions?.merchants.orEmpty(),
                merchantSelection,
                actions,
                Modifier.weight(1f),
            )
            MapEntityChoiceSelector(
                stringResource(R.string.analysis_map_filter_place),
                "PLACE",
                filterOptions?.places.orEmpty(),
                placeSelection,
                actions,
                Modifier.weight(1f),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm)) {
            MapEntityChoiceSelector(
                stringResource(R.string.analysis_map_filter_project),
                "PROJECT",
                filterOptions?.projects.orEmpty(),
                projectSelection,
                actions,
                Modifier.weight(1f),
            )
            val amountChoices = listOf<Long?>(null, 1_000L, 10_000L, 100_000L)
            LedgerChoiceSelector(
                stringResource(R.string.analysis_amount),
                amountChoices.indexOf(query.filters.minimumBaseAmountMinor).coerceAtLeast(0),
                amountChoices.map { minimum ->
                    minimum?.let {
                        stringResource(R.string.analysis_map_filter_minimum_amount, AnalysisPolicy.money(it, result.baseCurrency, locale).formatted)
                    } ?: stringResource(R.string.analysis_map_filter_all)
                },
                { actions.onSelectMapAmountFilter(amountChoices[it]) },
                Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MapEntityChoiceSelector(
    label: String,
    dimension: String,
    options: List<ConsumptionMapFilterOption>,
    selected: Set<app.ledger.core.common.StableId>,
    actions: AnalysisActions,
    modifier: Modifier = Modifier,
) {
    val selectedId = selected.singleOrNull()
    val choices = listOf(stringResource(R.string.analysis_map_filter_all)) + options.map { it.label }
    val selectedIndex = selectedId?.let { id -> options.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.plus(1) } ?: 0
    LedgerChoiceSelector(
        label,
        selectedIndex,
        choices,
        { index -> actions.onSelectMapFilter(dimension, options.getOrNull(index - 1)?.id) },
        modifier,
    )
}

@Composable
private fun selectedFilterLabel(
    options: List<ConsumptionMapFilterOption>,
    selected: Set<app.ledger.core.common.StableId>,
): String = when (selected.size) {
    0 -> stringResource(R.string.analysis_map_filter_all)
    1 -> options.firstOrNull { it.id in selected }?.label ?: stringResource(R.string.analysis_map_filter_selected)
    else -> stringResource(R.string.analysis_map_filter_any_count, selected.size)
}

@Composable
private fun selectedFilterDimension(
    title: String,
    keyPrefix: String,
    options: List<ConsumptionMapFilterOption>,
    selected: Set<app.ledger.core.common.StableId>,
): FilterDimensionUiModel? {
    if (selected.isEmpty()) return null
    val labels = options.associate { it.id to it.label }
    return FilterDimensionUiModel(
        title,
        selected.sorted().map { id ->
            FilterChipUiModel(
                "$keyPrefix:$id",
                labels[id] ?: stringResource(R.string.analysis_map_filter_selected),
            )
        },
    )
}

@Composable
private fun MapPointRow(
    result: ConsumptionMapResult,
    point: app.ledger.analytics.domain.ConsumptionMapPoint,
    actions: AnalysisActions,
) {
    val locale = LocalLocale.current.platformLocale
    val label = point.label ?: stringResource(R.string.analysis_map_recorded_location)
    LedgerCard(Modifier.fillMaxWidth(), onClick = { actions.onSelectMapPoint(point.id) }) {
        Column(Modifier.padding(LedgerTheme.spacing.sm)) {
            LedgerText(label, LedgerTextRole.BODY)
            LedgerText(
                stringResource(
                    R.string.analysis_map_point_summary,
                    AnalysisPolicy.money(point.baseAmountMinor, result.baseCurrency, locale).formatted,
                    point.transactionCount,
                ),
                LedgerTextRole.SUPPORTING,
            )
        }
    }
}

@Composable
internal fun ConsumptionMapDetailScreen(state: AnalysisFeatureState, actions: AnalysisActions) {
    val detail = state.consumptionMapDetail
    if (state.presentation == AnalysisPresentation.LOADING) {
        LedgerLoadingState(
            Modifier.fillMaxSize().testTag(LedgerTestTags.CONSUMPTION_MAP_DETAIL),
            stringResource(R.string.analysis_map_loading),
        )
        return
    }
    if (detail == null) {
        LedgerEmptyState(
            stringResource(R.string.analysis_map_detail_missing),
            stringResource(R.string.analysis_map_detail_missing_body),
            stringResource(R.string.analysis_retry),
            actions.onRetry,
            Modifier.fillMaxSize().testTag(LedgerTestTags.CONSUMPTION_MAP_DETAIL),
        )
        return
    }
    MapDetailContent(detail, actions)
}

@Composable
private fun MapDetailContent(detail: ConsumptionMapDetail, actions: AnalysisActions) {
    val locale = LocalLocale.current.platformLocale
    val point = detail.point
    val privateLocationSemantics = stringResource(R.string.analysis_map_location_available)
    LazyColumn(
        Modifier.fillMaxSize().testTag(LedgerTestTags.CONSUMPTION_MAP_DETAIL),
        contentPadding = PaddingValues(LedgerTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.md),
    ) {
        item {
            LedgerText(point.label ?: stringResource(R.string.analysis_map_recorded_location), LedgerTextRole.TITLE)
            LedgerText(
                stringResource(R.string.analysis_map_detail_kind, mapGroupKindLabel(point.kind)),
                LedgerTextRole.SUPPORTING,
            )
            LedgerText(
                stringResource(R.string.analysis_map_coordinates, point.latitudeE7 / E7_DIVISOR, point.longitudeE7 / E7_DIVISOR),
                LedgerTextRole.SUPPORTING,
                Modifier.testTag(LedgerTestTags.CONSUMPTION_MAP_LOCATION)
                    .clearAndSetSemantics { contentDescription = privateLocationSemantics },
            )
            LedgerText(
                stringResource(
                    R.string.analysis_map_point_summary,
                    AnalysisPolicy.money(point.baseAmountMinor, detail.baseCurrency, locale).formatted,
                    point.transactionCount,
                ),
                LedgerTextRole.SECTION,
            )
        }
        item {
            AccessibleDataTable(
                AccessibleTableUiModel(
                    stringResource(R.string.analysis_map_category_composition),
                    listOf(
                        stringResource(R.string.analysis_category),
                        stringResource(R.string.analysis_amount),
                        stringResource(R.string.analysis_transaction_count),
                    ),
                    detail.categories.map { category ->
                        listOf(
                            category.label ?: stringResource(R.string.analysis_unassigned),
                            AnalysisPolicy.money(category.baseAmountMinor, detail.baseCurrency, locale).formatted,
                            LocaleNumberFormatter.integer(category.transactionCount, locale),
                        )
                    },
                ),
            )
        }
        item { LedgerText(stringResource(R.string.analysis_map_transaction_preview), LedgerTextRole.SECTION) }
        items(detail.transactionPreview, key = { it.transactionId.toString() }) { row ->
            LedgerCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(LedgerTheme.spacing.sm)) {
                    LedgerText(
                        "${LedgerDateFormatterRuntime.formatter(locale).format(row.localDate)} · ${transactionKindLabel(row.kindKey)}",
                        LedgerTextRole.BODY,
                    )
                    LedgerText(AnalysisPolicy.money(row.amountMinor, row.currency, locale).formatted, LedgerTextRole.SUPPORTING)
                }
            }
        }
        item {
            LedgerButton(
                stringResource(R.string.analysis_map_open_transactions),
                { actions.onOpenMapTransactions(detail.drilldownQueryId) },
                Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable private fun mapModeLabel(value: ConsumptionMapMode): String = stringResource(
    when (value) {
        ConsumptionMapMode.CONSUMPTION -> R.string.analysis_map_mode_consumption
        ConsumptionMapMode.ALL_EXPENSES -> R.string.analysis_map_mode_all_expenses
        ConsumptionMapMode.CASH_FLOW -> R.string.analysis_map_mode_cash_flow
        ConsumptionMapMode.ALL_LOCATED_TRANSACTIONS -> R.string.analysis_map_mode_all_located
    },
)

@Composable private fun mapWeightLabel(value: ConsumptionMapWeight): String = stringResource(
    if (value == ConsumptionMapWeight.BASE_AMOUNT) R.string.analysis_map_weight_amount else R.string.analysis_map_weight_count,
)

@Composable private fun mapAggregationLabel(value: ConsumptionMapAggregation): String = stringResource(
    if (value == ConsumptionMapAggregation.MERCHANT) R.string.analysis_map_group_merchant else R.string.analysis_map_group_place,
)

@Composable private fun mapPresentationLabel(value: ConsumptionMapPresentation): String = stringResource(
    when (value) {
        ConsumptionMapPresentation.CLUSTERS -> R.string.analysis_map_clusters
        ConsumptionMapPresentation.HEATMAP -> R.string.analysis_map_heatmap
        ConsumptionMapPresentation.SINGLE_POINTS -> R.string.analysis_map_single_points
    },
)

@Composable private fun mapGroupKindLabel(value: ConsumptionMapGroupKind): String = stringResource(
    when (value) {
        ConsumptionMapGroupKind.MERCHANT -> R.string.analysis_map_group_merchant
        ConsumptionMapGroupKind.PLACE -> R.string.analysis_map_group_place
        ConsumptionMapGroupKind.RECORDED_LOCATION -> R.string.analysis_map_recorded_location
        ConsumptionMapGroupKind.TRANSACTION -> R.string.analysis_map_single_transaction
    },
)

private const val E7_DIVISOR = 10_000_000.0
