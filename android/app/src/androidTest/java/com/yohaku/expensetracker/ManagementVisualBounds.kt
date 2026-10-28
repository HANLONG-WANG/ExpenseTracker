package com.yohaku.expensetracker

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Measured native layout evidence alongside screenshots, in density-independent pixels. */
internal fun writeManagementVisualBounds(ui:ComposeContentTestRule,imageFile:File) {
    if (!visualEnabled()) return
    val result=JSONObject()
    for(tag in listOf("theme-sheet","sheet-title","sheet-header","sheet-body-content","record-facts","record-fact-0","record-fact-1")) {
        val matches=ui.onAllNodes(if(tag=="theme-sheet")hasTestTag(tag)else hasTestTag(tag) and hasAnyAncestor(hasTestTag("theme-sheet")))
        val values=JSONArray()
        repeat(matches.fetchSemanticsNodes().size) {index->
            val r=matches[index].getUnclippedBoundsInRoot()
            values.put(JSONObject().put("left",r.left.value).put("top",r.top.value).put("right",r.right.value).put("bottom",r.bottom.value))
        }
        result.put(tag,values)
    }
    File(imageFile.parentFile,imageFile.nameWithoutExtension+".bounds.json").writeText(result.toString(2))
}
