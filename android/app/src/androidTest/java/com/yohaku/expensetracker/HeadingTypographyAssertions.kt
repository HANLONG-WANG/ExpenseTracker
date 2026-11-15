package com.yohaku.expensetracker

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.LineBreak
import org.junit.Assert.*

/** Checks real line contents rather than freezing a particular balanced break position. */
internal fun SemanticsNodeInteraction.assertBalancedHeading() {
    performSemanticsAction(SemanticsActions.GetTextLayoutResult){action->
        val layouts=mutableListOf<TextLayoutResult>()
        assertTrue(action(layouts))
        val result=layouts.single()
        assertEquals(LineBreak.Heading,result.layoutInput.style.lineBreak)
        assertFalse("Heading must not overflow",result.hasVisualOverflow)
        if(result.lineCount>1){
            val last=result.lineCount-1
            val text=result.layoutInput.text.text.substring(result.getLineStart(last),result.getLineEnd(last,visibleEnd=true))
            assertTrue("Orphaned heading ending: $text",text.count{it.isLetterOrDigit()}>=2)
        }
    }
}
