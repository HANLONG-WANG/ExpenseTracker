package com.yohaku.expensetracker

import android.graphics.Bitmap
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.ui.features.*
import com.yohaku.expensetracker.ui.theme.*
import org.junit.*
import org.junit.Assert.*
import java.io.File

class SectionHeadingTest{
    @get:Rule val ui=createComposeRule()
    @Test fun headingsKeepEachThemesOriginalStructureAndCollapsedMargins(){
        val theme=mutableStateOf("magazine");val language=mutableStateOf("zh");val link=mutableStateOf(false);val gap=mutableIntStateOf(15);var clicked=0
        val i=InstrumentationRegistry.getInstrumentation()
        val suffix=InstrumentationRegistry.getArguments().getString("captureSuffix")?.let{"-$it"}.orEmpty()
        val folder=File(i.targetContext.filesDir,"verification/section-headings$suffix").also{it.mkdirs()}
        val csv=File(folder,"positions.csv").also{it.writeText("theme,language,link,pageGap,beforeGap,height,afterGap\n")}
        ui.setContent{YohakuTheme(theme.value,language.value){
            Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal=25.dp),verticalArrangement=Arrangement.spacedBy(gap.intValue.dp)){
                Box(Modifier.fillMaxWidth().height(20.dp).testTag("before-section"))
                ThemeSection(word("持有的资金","持っている資金"),"AVAILABLE FUNDS",word("全部流水","すべて"),if(link.value){{clicked++}}else null,pageGap=gap.intValue)
                Box(Modifier.fillMaxWidth().height(20.dp).testTag("after-section"))
            }
        }}
        for(t in listOf("magazine","journal","objects"))for(l in listOf("zh","ja"))for(action in listOf(false,true))for(pageGap in listOf(15,18)){
            ui.runOnIdle{theme.value=t;language.value=l;link.value=action;gap.intValue=pageGap}
            val before=ui.onNodeWithTag("before-section").getUnclippedBoundsInRoot()
            val heading=ui.onNodeWithTag("section-heading").getUnclippedBoundsInRoot()
            val after=ui.onNodeWithTag("after-section").getUnclippedBoundsInRoot()
            val top=(heading.top-before.bottom).value;val height=(heading.bottom-heading.top).value;val bottom=(after.top-heading.bottom).value
            assertEquals("HTML top margin $t/$l",if(t=="magazine")23f else 20f,top,1f)
            assertEquals("HTML bottom margin $t/$l",12f,bottom,1f)
            if(i.targetContext.resources.configuration.let{it.fontScale==1f&&it.screenWidthDp==390}){
                assertEquals("HTML heading height $t/$l",when(t){"magazine"->44.781f;"journal"->39f;else->if(action)26f else 25f},height,1f)
            }
            if(t=="objects")ui.onNodeWithTag("section-tab").assertExists() else ui.onNodeWithTag("section-tab").assertDoesNotExist()
            if(t=="magazine")ui.onNodeWithTag("section-eyebrow").assertExists() else ui.onNodeWithTag("section-eyebrow").assertDoesNotExist()
            if(t=="journal"){
                ui.onNodeWithText("✳",substring=true).assertExists()
                val layouts=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                ui.onNodeWithTag("section-title").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult){it(layouts)}
                val width=layouts.single().getBoundingBox(0).width/i.targetContext.resources.displayMetrics.density
                assertEquals("HTML DejaVu star advance",14.25f*i.targetContext.resources.configuration.fontScale,width,1f)
            }
            csv.appendText("$t,$l,$action,$pageGap,$top,$height,$bottom\n")
            if(action){val old=clicked;ui.onNodeWithTag("section-link").performClick();ui.runOnIdle{assertEquals(old+1,clicked)}}
            if(pageGap==15)i.uiAutomation.takeScreenshot().let{bitmap->File(folder,"$t-$l-$action.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
        }
    }
}
