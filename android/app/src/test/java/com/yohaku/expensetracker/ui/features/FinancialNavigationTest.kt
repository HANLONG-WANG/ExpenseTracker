package com.yohaku.expensetracker.ui.features

import org.junit.Assert.assertEquals
import org.junit.Test

class FinancialNavigationTest {
    @Test fun planAndPeriodTargetsRoundTripWithoutBecomingManagementPages() {
        val target=FinancialDestination.from("plans/plan-id/bill-id")
        assertEquals("plans",target.tab)
        assertEquals(null,target.page)
        assertEquals("plan-id",target.planId)
        assertEquals("bill-id",target.periodId)
        assertEquals(target,FinancialDestination.from(target.path))
    }
    @Test fun legacyTargetsKeepTheirBusinessDestinationUnderManagement() {
        mapOf("rules" to "rules", "movements" to "records", "limits" to "limits").forEach { (old, page) ->
            val destination = FinancialDestination.from(old)
            assertEquals("manage", destination.tab)
            assertEquals(page, destination.page)
            assertEquals(destination, FinancialDestination.from(destination.path))
        }
        listOf("overview", "statement", "plans", "manage").forEach {
            assertEquals(FinancialDestination(it), FinancialDestination.from(it))
        }
    }
}
