package com.yohaku.expensetracker

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import com.yohaku.expensetracker.data.repository.reconciliationPool
import com.yohaku.expensetracker.ui.features.AppViewModel

/** Exercise the visible list/detail navigation before an existing business operation. */
internal fun openFinancialPlan(ui:ComposeContentTestRule,vm:AppViewModel,id:String) {
    val f=requireNotNull(vm.financialState.value)
    val plan=f.plans.first{it.id==id}
    val destination=if(plan.origin=="POOL_MEMBER")requireNotNull(f.reconciliationPool(plan))else plan
    if(ui.onAllNodesWithTag("finance-plan-detail-${destination.id}").fetchSemanticsNodes().isNotEmpty())return
    ui.onNodeWithTag("finance-tab-plans").performClick()
    ui.waitForIdle()
    if(!destination.active){
        if(ui.onAllNodesWithTag("finance-archived-${destination.id}").fetchSemanticsNodes().isEmpty())ui.onNodeWithTag("finance-archived-open").performScrollTo().performClick()
        ui.onNodeWithTag("finance-archived-${destination.id}").performScrollTo().performClick()
    }else ui.onNodeWithTag("finance-plan-row-${destination.id}").performScrollTo().performClick()
    ui.onNodeWithTag("finance-plan-detail-${destination.id}").assertExists()
}

internal fun openFinancialPeriod(ui:ComposeContentTestRule,vm:AppViewModel,id:String) {
    if(ui.onAllNodesWithTag("finance-bill-$id").fetchSemanticsNodes().isNotEmpty())return
    val bill=requireNotNull(vm.financialState.value).bills.first{it.id==id}
    openFinancialPlan(ui,vm,bill.planId)
    ui.onNodeWithTag("finance-plan-period-$id").performScrollTo().performClick()
    ui.onNodeWithTag("finance-bill-$id").assertExists()
}

/** Fixed flow actions have no scrolling ancestor; form and short-sheet actions still scroll normally. */
internal fun SemanticsNodeInteraction.scrollIfScrollable():SemanticsNodeInteraction {
    var ancestor=fetchSemanticsNode().parent
    while(ancestor!=null) {
        if(ancestor.config.contains(androidx.compose.ui.semantics.SemanticsActions.ScrollBy))return performScrollTo()
        ancestor=ancestor.parent
    }
    return this
}

/** IME animation is outside Compose's idling clock. Finish text entry before non-keyboard taps.
 * Fixed action taps intentionally keep the IME open to exercise the real keyboard-safe action area. */
internal fun finishFinancialInputBeforeTap(ui:ComposeContentTestRule,tag:String) {
    if(tag.startsWith("field-")||ui.onAllNodesWithTag("finance-flow").fetchSemanticsNodes().isEmpty())return
    if(ui.onAllNodes(hasTestTag(tag) and hasAnyAncestor(hasTestTag("finance-flow-actions"))).fetchSemanticsNodes().isNotEmpty())return
    androidx.test.espresso.Espresso.closeSoftKeyboard()
    ui.waitForIdle()
}

/** Put the reviewed section at the actual flow viewport top instead of merely at its bottom edge. */
internal fun alignFinancialFlowSection(ui:ComposeContentTestRule,tag:String) {
    val node=ui.onNodeWithTag(tag)
    node.performScrollTo();ui.waitForIdle()
    val viewport=ui.onNodeWithTag("finance-flow-scroll")
    val offset=node.getUnclippedBoundsInRoot().top.value-viewport.getUnclippedBoundsInRoot().top.value-12f
    val density=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
    viewport.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy){it(0f,offset*density)}
    ui.waitForIdle()
}
