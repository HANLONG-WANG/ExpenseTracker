package app.ledger.app

import app.ledger.analytics.domain.ConsumptionMapFilterOption
import app.ledger.analytics.domain.ConsumptionMapFilterOptions
import app.ledger.analytics.domain.ConsumptionMapFilters
import app.ledger.core.common.StableId
import app.ledger.finance.domain.CategoryId
import app.ledger.finance.domain.MerchantId
import app.ledger.finance.domain.PlaceId
import app.ledger.finance.domain.ProjectId
import app.ledger.finance.domain.UserAccountId

internal enum class ConsumptionMapFilterDimension { ACCOUNT, CATEGORY, MERCHANT, PLACE, PROJECT }

internal object ConsumptionMapFilterSelection {
    fun options(
        options: ConsumptionMapFilterOptions,
        dimension: ConsumptionMapFilterDimension,
    ): List<ConsumptionMapFilterOption> = when (dimension) {
        ConsumptionMapFilterDimension.ACCOUNT -> options.accounts
        ConsumptionMapFilterDimension.CATEGORY -> options.categories
        ConsumptionMapFilterDimension.MERCHANT -> options.merchants
        ConsumptionMapFilterDimension.PLACE -> options.places
        ConsumptionMapFilterDimension.PROJECT -> options.projects
    }

    fun add(
        filters: ConsumptionMapFilters,
        dimension: ConsumptionMapFilterDimension,
        options: List<ConsumptionMapFilterOption>,
    ): ConsumptionMapFilters {
        fun next(current: Set<StableId>): Set<StableId> {
            val candidate = options.firstOrNull { it.id !in current }?.id
            return when {
                options.isEmpty() -> current
                current.size >= MAX_FILTER_VALUES -> current
                candidate == null -> emptySet()
                else -> current + candidate
            }
        }
        return when (dimension) {
            ConsumptionMapFilterDimension.ACCOUNT -> filters.copy(
                accountIds = next(filters.accountIds.map { it.value }.toSet()).map(::UserAccountId).toSet(),
            )
            ConsumptionMapFilterDimension.CATEGORY -> filters.copy(
                categoryIds = next(filters.categoryIds.map { it.value }.toSet()).map(::CategoryId).toSet(),
            )
            ConsumptionMapFilterDimension.MERCHANT -> filters.copy(
                merchantIds = next(filters.merchantIds.map { it.value }.toSet()).map(::MerchantId).toSet(),
            )
            ConsumptionMapFilterDimension.PLACE -> filters.copy(
                placeIds = next(filters.placeIds.map { it.value }.toSet()).map(::PlaceId).toSet(),
            )
            ConsumptionMapFilterDimension.PROJECT -> filters.copy(
                projectIds = next(filters.projectIds.map { it.value }.toSet()).map(::ProjectId).toSet(),
            )
        }
    }

    private const val MAX_FILTER_VALUES: Int = 64
}

/** Removes one typed map-filter condition without parsing an identifier into a different aggregate type. */
internal object ConsumptionMapFilterRemoval {
    fun remove(filters: ConsumptionMapFilters, stableKey: String): ConsumptionMapFilters {
        val value = stableKey.substringAfter(':', missingDelimiterValue = "")
        return when {
            stableKey.startsWith("account:") -> filters.copy(
                accountIds = filters.accountIds.filterNot { it.value.toString() == value }.toSet(),
            )
            stableKey.startsWith("category:") -> filters.copy(
                categoryIds = filters.categoryIds.filterNot { it.value.toString() == value }.toSet(),
            )
            stableKey.startsWith("merchant:") -> filters.copy(
                merchantIds = filters.merchantIds.filterNot { it.value.toString() == value }.toSet(),
            )
            stableKey.startsWith("place:") -> filters.copy(
                placeIds = filters.placeIds.filterNot { it.value.toString() == value }.toSet(),
            )
            stableKey.startsWith("project:") -> filters.copy(
                projectIds = filters.projectIds.filterNot { it.value.toString() == value }.toSet(),
            )
            stableKey == "amount-minimum" -> filters.copy(minimumBaseAmountMinor = null)
            stableKey == "special-transactions" -> filters.withSpecialTransactions(included = false)
            else -> filters
        }
    }
}
