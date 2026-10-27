package com.yohaku.expensetracker

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule

/** Ledger and account detail are lazy pages: a record below the screen is composed only after scrolling to it. */
internal fun ComposeTestRule.revealOnPage(matcher:SemanticsMatcher):SemanticsNodeInteraction {
    waitUntil(10000){onAllNodesWithTag("page-scroll").fetchSemanticsNodes().isNotEmpty()}
    onNodeWithTag("page-scroll").performScrollToNode(matcher)
    return onNode(matcher)
}
internal fun ComposeTestRule.revealOnPage(tag:String)=revealOnPage(hasTestTag(tag))
