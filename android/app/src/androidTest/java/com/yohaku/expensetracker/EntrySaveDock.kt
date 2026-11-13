package com.yohaku.expensetracker

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

internal fun imeVisible()=android.view.inspector.WindowInspector.getGlobalWindowViews().any{ViewCompat.getRootWindowInsets(it)?.isVisible(WindowInsetsCompat.Type.ime())==true}

/**
 * Text input raises the keyboard asynchronously, and the entry save line moves between the sheet footer and the
 * end of the form with it; wait until the keyboard's visibility has held for 500ms before locating the line.
 */
internal fun ComposeTestRule.settleKeyboard() {
    var last=imeVisible();var since=System.currentTimeMillis()
    waitUntil(10000){val now=imeVisible();if(now!=last){last=now;since=System.currentTimeMillis()};System.currentTimeMillis()-since>=500}
    waitForIdle()
}

/**
 * An entry save/cancel action: docked in the sheet footer, outside the scrolled form, except while the keyboard
 * is up, when it ends the form and has to be scrolled to.
 */
internal fun ComposeTestRule.entryAction(tag:String):SemanticsNodeInteraction {
    waitUntil(10000){onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}
    settleKeyboard()
    val node=onNodeWithTag(tag)
    if(onAllNodes(hasTestTag(tag) and hasAnyAncestor(hasScrollAction())).fetchSemanticsNodes().isNotEmpty())node.performScrollTo()
    return node
}
