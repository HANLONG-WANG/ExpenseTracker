package com.yohaku.expensetracker

import android.graphics.Bitmap
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.ui.features.RecordCover
import com.yohaku.expensetracker.ui.features.RecordFacts
import com.yohaku.expensetracker.ui.theme.*
import org.junit.Test
import org.junit.Rule
import java.io.File

class RecordPresentationTest{
    @get:Rule val ui=createComposeRule()
    @Test fun recordCoverAndFactsKeepThemeAndLanguage(){
        org.junit.Assume.assumeTrue("Requires visual=true", visualEnabled())
        val theme=mutableStateOf("magazine");val language=mutableStateOf("zh")
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val density=instrumentation.targetContext.resources.displayMetrics.density
        val suffix=InstrumentationRegistry.getArguments().getString("captureSuffix")?.let{"-$it"}.orEmpty()
        val folder=File(instrumentation.targetContext.filesDir,"verification/record-presentation$suffix").also{it.mkdirs()}
        val dimensions=File(folder,"dimensions.csv");dimensions.writeText("theme,language,component,width,height\n")
        ui.setContent{YohakuTheme(theme.value,language.value){
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal=25.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(20.dp)){
                RecordCover("喫茶と余白","¥300","2026-10-14",word("支出","支出"),recordId="11111111-2222-4333-8444-555555555555")
                RecordFacts(listOf(word("账户","口座") to "随身现金",word("分类","カテゴリ") to "餐饮 / 咖啡",word("备注","メモ") to "午后咖啡 / 午後のコーヒー"))
            }
        }}
        for((t,l) in testLooks(this@RecordPresentationTest)){
            ui.runOnIdle{theme.value=t;language.value=l}
            ui.onNodeWithText("¥300").performScrollTo().assertIsDisplayed()
            for(tag in listOf("record-cover","record-fact-0","record-fact-1","record-fact-2")){
                ui.onNodeWithTag(tag).performScrollTo()
                val bounds=ui.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
                dimensions.appendText("$t,$l,$tag,${bounds.width/density},${bounds.height/density}\n")
            }
            ui.onNodeWithTag("record-cover").performScrollTo()
            instrumentation.uiAutomation.takeScreenshot().let{bitmap->File(folder,"$t-$l.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
        }
    }
}
