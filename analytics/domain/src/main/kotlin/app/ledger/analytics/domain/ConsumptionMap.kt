package app.ledger.analytics.domain

import app.ledger.core.common.StableId
import app.ledger.core.money.CurrencyCode
import app.ledger.finance.domain.CategoryId
import app.ledger.finance.domain.LocalRevision
import app.ledger.finance.domain.MerchantId
import app.ledger.finance.domain.PlaceId
import app.ledger.finance.domain.ProjectId
import app.ledger.finance.domain.TransactionKind
import app.ledger.finance.domain.UserAccountId
import java.time.LocalDate

enum class ConsumptionMapMode {
    CONSUMPTION,
    ALL_EXPENSES,
    CASH_FLOW,
    ALL_LOCATED_TRANSACTIONS,
}

enum class ConsumptionMapWeight { BASE_AMOUNT, TRANSACTION_COUNT }

enum class ConsumptionMapAggregation { MERCHANT, PLACE }

enum class ConsumptionMapPresentation { CLUSTERS, HEATMAP, SINGLE_POINTS }

enum class ConsumptionMapGroupKind { MERCHANT, PLACE, RECORDED_LOCATION, TRANSACTION }

data class MapViewport(
    val minimumLatitudeE7: Int,
    val maximumLatitudeE7: Int,
    val minimumLongitudeE7: Int,
    val maximumLongitudeE7: Int,
    val zoomBucket: Int,
) {
    init {
        require(minimumLatitudeE7 in MIN_LATITUDE_E7..MAX_LATITUDE_E7)
        require(maximumLatitudeE7 in MIN_LATITUDE_E7..MAX_LATITUDE_E7)
        require(minimumLatitudeE7 <= maximumLatitudeE7)
        require(minimumLongitudeE7 in MIN_LONGITUDE_E7..MAX_LONGITUDE_E7)
        require(maximumLongitudeE7 in MIN_LONGITUDE_E7..MAX_LONGITUDE_E7)
        require(zoomBucket in 0..MAX_ZOOM_BUCKET)
    }

    val crossesDateLine: Boolean get() = minimumLongitudeE7 > maximumLongitudeE7

    companion object {
        val World: MapViewport = MapViewport(
            MIN_LATITUDE_E7,
            MAX_LATITUDE_E7,
            MIN_LONGITUDE_E7,
            MAX_LONGITUDE_E7,
            0,
        )
    }
}

data class ConsumptionMapFilters(
    val accountIds: Set<UserAccountId> = emptySet(),
    val categoryIds: Set<CategoryId> = emptySet(),
    val merchantIds: Set<MerchantId> = emptySet(),
    val placeIds: Set<PlaceId> = emptySet(),
    val projectIds: Set<ProjectId> = emptySet(),
    val includedKinds: Set<TransactionKind> = DEFAULT_VISIBLE_KINDS,
    val minimumBaseAmountMinor: Long? = null,
    val maximumBaseAmountMinor: Long? = null,
) {
    init {
        require(listOf(accountIds, categoryIds, merchantIds, placeIds, projectIds).all { it.size <= MAX_FILTER_VALUES })
        require(includedKinds.isNotEmpty())
        require(minimumBaseAmountMinor == null || minimumBaseAmountMinor >= 0L)
        require(maximumBaseAmountMinor == null || maximumBaseAmountMinor >= 0L)
        require(minimumBaseAmountMinor == null || maximumBaseAmountMinor == null || minimumBaseAmountMinor <= maximumBaseAmountMinor)
    }

    val hidesTransfersRepaymentsAndLoans: Boolean
        get() = SPECIAL_TRANSACTION_KINDS.none(includedKinds::contains)

    fun withSpecialTransactions(included: Boolean): ConsumptionMapFilters = copy(
        includedKinds = if (included) TransactionKind.entries.toSet() else DEFAULT_VISIBLE_KINDS,
    )

    companion object {
        val SPECIAL_TRANSACTION_KINDS: Set<TransactionKind> = setOf(
            TransactionKind.TRANSFER,
            TransactionKind.CREDIT_PAYMENT,
            TransactionKind.LOAN_DISBURSEMENT,
            TransactionKind.LOAN_PAYMENT,
        )
        val DEFAULT_VISIBLE_KINDS: Set<TransactionKind> = TransactionKind.entries.toSet() - SPECIAL_TRANSACTION_KINDS
        private const val MAX_FILTER_VALUES = 64
    }
}

data class ConsumptionMapQuery(
    val period: ReportPeriod,
    val viewport: MapViewport,
    val mode: ConsumptionMapMode = ConsumptionMapMode.CONSUMPTION,
    val weight: ConsumptionMapWeight = ConsumptionMapWeight.BASE_AMOUNT,
    val aggregation: ConsumptionMapAggregation = ConsumptionMapAggregation.PLACE,
    val presentation: ConsumptionMapPresentation = ConsumptionMapPresentation.CLUSTERS,
    val filters: ConsumptionMapFilters = ConsumptionMapFilters(),
)

data class ConsumptionMapFilterOption(
    val id: StableId,
    val label: String,
) {
    init {
        require(label.isNotBlank())
    }
}

data class ConsumptionMapFilterOptions(
    val accounts: List<ConsumptionMapFilterOption>,
    val categories: List<ConsumptionMapFilterOption>,
    val merchants: List<ConsumptionMapFilterOption>,
    val places: List<ConsumptionMapFilterOption>,
    val projects: List<ConsumptionMapFilterOption>,
) {
    init {
        require(listOf(accounts, categories, merchants, places, projects).all { it.size <= MAX_OPTIONS_PER_DIMENSION })
    }

    companion object {
        const val MAX_OPTIONS_PER_DIMENSION: Int = 200
    }
}

data class ConsumptionMapPoint(
    val id: StableId,
    val kind: ConsumptionMapGroupKind,
    val label: String?,
    val latitudeE7: Int,
    val longitudeE7: Int,
    val baseAmountMinor: Long,
    val transactionCount: Long,
) {
    init {
        require(latitudeE7 in MIN_LATITUDE_E7..MAX_LATITUDE_E7)
        require(longitudeE7 in MIN_LONGITUDE_E7..MAX_LONGITUDE_E7)
        require(transactionCount > 0L)
        require(label == null || label.isNotBlank())
    }

    /** Rendering is non-authoritative; Long.MIN_VALUE saturates instead of overflowing. */
    fun renderWeight(weight: ConsumptionMapWeight): Long = when (weight) {
        ConsumptionMapWeight.TRANSACTION_COUNT -> transactionCount
        ConsumptionMapWeight.BASE_AMOUNT -> if (baseAmountMinor == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(baseAmountMinor)
    }
}

data class ConsumptionMapResult(
    val query: ConsumptionMapQuery,
    val baseCurrency: CurrencyCode,
    val points: List<ConsumptionMapPoint>,
    val viewportBaseAmountMinor: Long,
    val viewportTransactionCount: Long,
    val asOfLocalRevision: LocalRevision,
    val resultLimited: Boolean,
) {
    init {
        require(points.size <= MAX_RENDERED_POINTS)
        require(viewportTransactionCount >= 0L)
    }

    companion object {
        const val MAX_RENDERED_POINTS: Int = 512
    }
}

data class ConsumptionMapCategoryComposition(
    val categoryId: CategoryId?,
    val label: String?,
    val baseAmountMinor: Long,
    val transactionCount: Long,
)

data class ConsumptionMapDetail(
    val point: ConsumptionMapPoint,
    val baseCurrency: CurrencyCode,
    val categories: List<ConsumptionMapCategoryComposition>,
    val transactionPreview: List<DrilldownTransaction>,
    val drilldownQueryId: DrilldownQueryId,
    val asOfLocalRevision: LocalRevision,
) {
    init {
        require(categories.size <= MAX_CATEGORY_ROWS)
        require(transactionPreview.size <= MAX_PREVIEW_ROWS)
    }

    companion object {
        const val MAX_CATEGORY_ROWS: Int = 12
        const val MAX_PREVIEW_ROWS: Int = 8
    }
}

internal const val MIN_LATITUDE_E7: Int = -900_000_000
internal const val MAX_LATITUDE_E7: Int = 900_000_000
internal const val MIN_LONGITUDE_E7: Int = -1_800_000_000
internal const val MAX_LONGITUDE_E7: Int = 1_800_000_000
private const val MAX_ZOOM_BUCKET: Int = 22
