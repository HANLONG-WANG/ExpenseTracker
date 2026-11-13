package com.yohaku.expensetracker

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule

internal fun ComposeTestRule.showProcessedOccurrence(id:String) {
    if(onAllNodesWithTag("occurrence-$id").fetchSemanticsNodes().isEmpty())onNodeWithTag("recurring-processed-toggle").performScrollTo().performClick()
    onNodeWithTag("occurrence-$id").assertExists()
}
internal fun ComposeTestRule.occurrenceMenu(id:String) {
    showProcessedOccurrence(id)
    onNodeWithTag("occurrence-more-$id").performScrollTo().performClick()
}
internal fun ComposeTestRule.occurrenceDetails(id:String) {
    showProcessedOccurrence(id)
    onNodeWithTag("occurrence-details-$id").performScrollTo().performClick()
}
internal fun ComposeTestRule.confirmOccurrence(id:String) {
    if(onAllNodesWithTag("confirm-occurrence-$id").fetchSemanticsNodes().isEmpty())occurrenceMenu(id)
    onNodeWithTag("confirm-occurrence-$id").performScrollTo().performClick()
}
