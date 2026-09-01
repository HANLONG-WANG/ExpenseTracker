@file:Suppress("TooManyFunctions")

package app.ledger.app

import app.ledger.analytics.domain.AnalyticsApplicationPort
import app.ledger.analytics.domain.ConsumptionMapAggregation
import app.ledger.analytics.domain.ConsumptionMapFilterOptions
import app.ledger.analytics.domain.ConsumptionMapFilters
import app.ledger.analytics.domain.ConsumptionMapGroupKind
import app.ledger.analytics.domain.ConsumptionMapMode
import app.ledger.analytics.domain.ConsumptionMapPresentation
import app.ledger.analytics.domain.ConsumptionMapQuery
import app.ledger.analytics.domain.ConsumptionMapWeight
import app.ledger.analytics.domain.MapViewport
import app.ledger.analytics.domain.ReportPeriod
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.feature.analysis.AnalysisFeatureState
import app.ledger.feature.analysis.AnalysisLoadState
import app.ledger.feature.analysis.AnalysisPresentation

/** Owns P27's non-sensitive, in-memory query state independently from the cross-screen analysis controller. */
internal class ConsumptionMapController(private val application: AnalyticsApplicationPort) {
    private var query: ConsumptionMapQuery? = null
    private var filterOptions: ConsumptionMapFilterOptions? = null
    private var unavailable: Boolean = false

    fun reset() {
        query = null
        filterOptions = null
        unavailable = false
    }

    fun current(period: ReportPeriod): ConsumptionMapQuery = query ?: ConsumptionMapQuery(
        period,
        MapViewport.World,
        filters = ConsumptionMapFilters(),
    ).also { query = it }

    fun cycleMode(period: ReportPeriod) = mutate(period) {
        it.copy(mode = ConsumptionMapMode.entries[(it.mode.ordinal + 1) % ConsumptionMapMode.entries.size])
    }

    fun cycleWeight(period: ReportPeriod) = mutate(period) {
        it.copy(weight = ConsumptionMapWeight.entries[(it.weight.ordinal + 1) % ConsumptionMapWeight.entries.size])
    }

    fun cycleAggregation(period: ReportPeriod) = mutate(period) {
        it.copy(aggregation = ConsumptionMapAggregation.entries[(it.aggregation.ordinal + 1) % ConsumptionMapAggregation.entries.size])
    }

    fun cyclePresentation(period: ReportPeriod) = mutate(period) {
        it.copy(presentation = ConsumptionMapPresentation.entries[(it.presentation.ordinal + 1) % ConsumptionMapPresentation.entries.size])
    }

    fun selectMode(period: ReportPeriod, value: ConsumptionMapMode) = mutate(period) { it.copy(mode = value) }
    fun selectWeight(period: ReportPeriod, value: ConsumptionMapWeight) = mutate(period) { it.copy(weight = value) }
    fun selectAggregation(period: ReportPeriod, value: ConsumptionMapAggregation) = mutate(period) { it.copy(aggregation = value) }
    fun selectPresentation(period: ReportPeriod, value: ConsumptionMapPresentation) = mutate(period) { it.copy(presentation = value) }

    fun toggleSpecialTransactions(period: ReportPeriod) = mutate(period) {
        it.copy(filters = it.filters.withSpecialTransactions(it.filters.hidesTransfersRepaymentsAndLoans))
    }

    fun resetFilters(period: ReportPeriod) {
        query = current(period).copy(period = period, filters = ConsumptionMapFilters())
        unavailable = false
    }

    suspend fun cycleFilter(
        bookId: StableId,
        period: ReportPeriod,
        dimension: ConsumptionMapFilterDimension,
    ) {
        val options = ConsumptionMapFilterSelection.options(requireFilterOptions(bookId), dimension)
        mutate(period) { it.copy(filters = ConsumptionMapFilterSelection.add(it.filters, dimension, options)) }
    }

    fun cycleAmount(period: ReportPeriod) = mutate(period) {
        val minimum = when (it.filters.minimumBaseAmountMinor) {
            null -> MINIMUM_AMOUNT_SMALL
            MINIMUM_AMOUNT_SMALL -> MINIMUM_AMOUNT_MEDIUM
            MINIMUM_AMOUNT_MEDIUM -> MINIMUM_AMOUNT_LARGE
            else -> null
        }
        it.copy(filters = it.filters.copy(minimumBaseAmountMinor = minimum, maximumBaseAmountMinor = null))
    }

    fun selectFilter(period: ReportPeriod, dimension: ConsumptionMapFilterDimension, selectedId: StableId?) = mutate(period) {
        it.copy(filters = ConsumptionMapFilterSelection.select(it.filters, dimension, selectedId))
    }

    fun selectAmount(period: ReportPeriod, minimumBaseAmountMinor: Long?) = mutate(period) {
        it.copy(filters = it.filters.copy(minimumBaseAmountMinor = minimumBaseAmountMinor, maximumBaseAmountMinor = null))
    }

    fun removeFilter(period: ReportPeriod, stableKey: String) = mutate(period) {
        it.copy(filters = ConsumptionMapFilterRemoval.remove(it.filters, stableKey))
    }

    fun updateViewport(period: ReportPeriod, viewport: MapViewport): Boolean {
        val current = current(period)
        if (current.viewport == viewport) return false
        query = current.copy(viewport = viewport)
        return true
    }

    fun markUnavailable() {
        unavailable = true
    }

    suspend fun loadMap(
        bookId: StableId,
        period: ReportPeriod,
        baseState: (AnalysisPresentation) -> AnalysisFeatureState,
    ): AnalysisLoadState {
        val current = current(period).copy(period = period).also { query = it }
        if (filterOptions == null) {
            filterOptions = (application.consumptionMapFilterOptions(bookId) as? DomainResult.Success)?.value
        }
        return when (val result = application.consumptionMap(bookId, current)) {
            is DomainResult.Success -> {
                val presentation = when {
                    result.value.points.isEmpty() -> AnalysisPresentation.NO_LOCATION_DATA
                    unavailable -> AnalysisPresentation.MAP_UNAVAILABLE
                    current.presentation == ConsumptionMapPresentation.CLUSTERS -> AnalysisPresentation.CLUSTERS
                    current.presentation == ConsumptionMapPresentation.HEATMAP -> AnalysisPresentation.HEATMAP
                    else -> AnalysisPresentation.SINGLE_POINTS
                }
                AnalysisLoadState.Content(
                    baseState(presentation).copy(
                        consumptionMap = result.value,
                        consumptionMapFilterOptions = filterOptions,
                    ),
                )
            }
            is DomainResult.Failure -> AnalysisLoadState.Content(
                baseState(AnalysisPresentation.NO_LOCATION_DATA).copy(failureCode = result.error.code),
            )
        }
    }

    suspend fun loadDetail(
        bookId: StableId,
        period: ReportPeriod,
        pointId: StableId,
        baseState: (AnalysisPresentation) -> AnalysisFeatureState,
    ): AnalysisLoadState {
        val current = current(period).copy(period = period).also { query = it }
        return when (val result = application.consumptionMapDetail(bookId, current, pointId)) {
            is DomainResult.Success -> {
                val presentation = when (result.value.point.kind) {
                    ConsumptionMapGroupKind.PLACE -> AnalysisPresentation.PLACE
                    ConsumptionMapGroupKind.TRANSACTION -> AnalysisPresentation.SINGLE_TRANSACTION
                    else -> AnalysisPresentation.CLUSTER
                }
                AnalysisLoadState.Content(baseState(presentation).copy(consumptionMapDetail = result.value))
            }
            is DomainResult.Failure -> AnalysisLoadState.Failure("ANA-012", result.error.code)
        }
    }

    private fun mutate(period: ReportPeriod, transform: (ConsumptionMapQuery) -> ConsumptionMapQuery) {
        query = transform(current(period))
        unavailable = false
    }

    private suspend fun requireFilterOptions(bookId: StableId): ConsumptionMapFilterOptions {
        filterOptions?.let { return it }
        return when (val loaded = application.consumptionMapFilterOptions(bookId)) {
            is DomainResult.Success -> loaded.value.also { filterOptions = it }
            is DomainResult.Failure -> ConsumptionMapFilterOptions(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        }
    }

    private companion object {
        const val MINIMUM_AMOUNT_SMALL: Long = 1_000L
        const val MINIMUM_AMOUNT_MEDIUM: Long = 10_000L
        const val MINIMUM_AMOUNT_LARGE: Long = 100_000L
    }
}
