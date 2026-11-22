package com.yohaku.expensetracker.ui.features

import org.junit.Assert.assertEquals
import org.junit.Test

class ReconciliationMoveDraftTest {
    @Test fun refreshAddsNewClearingsAndKeepsEditedSplitsOfSurvivingRows() {
        val first=ReconciliationMoveDraft("first","bill","100","0","0")
        val parts=listOf(first.copy(principal="60"),first.copy(billId="other",principal="40"))
        val added=first.copy(id="added")
        assertEquals(parts+added,refreshClearingMoves(parts+first.copy(id="removed"),listOf(first,added)))
    }
}
