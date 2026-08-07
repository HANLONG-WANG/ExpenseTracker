package app.ledger.app

import app.ledger.analytics.domain.ConsumptionMapFilterOption
import app.ledger.analytics.domain.ConsumptionMapFilters
import app.ledger.core.common.StableId
import app.ledger.finance.domain.CategoryId
import app.ledger.finance.domain.MerchantId
import app.ledger.finance.domain.PlaceId
import app.ledger.finance.domain.ProjectId
import app.ledger.finance.domain.UserAccountId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class ConsumptionMapFilterRemovalTest {
    @Test
    fun selectionAddsSameDimensionValuesAndDoesNotClearWhenOptionsAreUnavailable() {
        val first = UserAccountId(id(1))
        val second = UserAccountId(id(2))
        val options = listOf(
            ConsumptionMapFilterOption(first.value, "First account"),
            ConsumptionMapFilterOption(second.value, "Second account"),
        )

        val oneSelected = ConsumptionMapFilterSelection.add(
            filters = ConsumptionMapFilters(),
            dimension = ConsumptionMapFilterDimension.ACCOUNT,
            options = options,
        )
        val bothSelected = ConsumptionMapFilterSelection.add(
            filters = oneSelected,
            dimension = ConsumptionMapFilterDimension.ACCOUNT,
            options = options,
        )

        assertEquals(setOf(first, second), bothSelected.accountIds)
        assertEquals(
            bothSelected,
            ConsumptionMapFilterSelection.add(
                filters = bothSelected,
                dimension = ConsumptionMapFilterDimension.ACCOUNT,
                options = emptyList(),
            ),
        )
        assertTrue(
            ConsumptionMapFilterSelection.add(
                filters = bothSelected,
                dimension = ConsumptionMapFilterDimension.ACCOUNT,
                options = options,
            ).accountIds.isEmpty(),
        )
    }

    @Test
    fun eachChipRemovesOnlyItsTypedConditionAndSpecialResetRestoresSafeDefault() {
        val account = UserAccountId(id(1))
        val category = CategoryId(id(2))
        val merchant = MerchantId(id(3))
        val place = PlaceId(id(4))
        val project = ProjectId(id(5))
        val filters = ConsumptionMapFilters(
            accountIds = setOf(account),
            categoryIds = setOf(category),
            merchantIds = setOf(merchant),
            placeIds = setOf(place),
            projectIds = setOf(project),
            minimumBaseAmountMinor = 1_000,
        ).withSpecialTransactions(included = true)

        assertTrue(ConsumptionMapFilterRemoval.remove(filters, "account:${account.value}").accountIds.isEmpty())
        assertTrue(ConsumptionMapFilterRemoval.remove(filters, "category:${category.value}").categoryIds.isEmpty())
        assertTrue(ConsumptionMapFilterRemoval.remove(filters, "merchant:${merchant.value}").merchantIds.isEmpty())
        assertTrue(ConsumptionMapFilterRemoval.remove(filters, "place:${place.value}").placeIds.isEmpty())
        assertTrue(ConsumptionMapFilterRemoval.remove(filters, "project:${project.value}").projectIds.isEmpty())
        assertEquals(null, ConsumptionMapFilterRemoval.remove(filters, "amount-minimum").minimumBaseAmountMinor)
        assertTrue(ConsumptionMapFilterRemoval.remove(filters, "special-transactions").hidesTransfersRepaymentsAndLoans)
        assertFalse(filters.hidesTransfersRepaymentsAndLoans)
        assertEquals(filters, ConsumptionMapFilterRemoval.remove(filters, "unknown"))
    }

    private fun id(seed: Long): StableId = StableId.fromUuid(UUID(0, seed))
}
