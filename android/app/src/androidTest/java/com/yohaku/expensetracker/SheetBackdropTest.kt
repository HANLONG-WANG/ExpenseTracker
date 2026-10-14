package com.yohaku.expensetracker

import android.graphics.Color
import android.view.inspector.WindowInspector
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.abs

class SheetBackdropTest{
    @get:Rule val ui=createComposeRule()
    @Test fun sheetsUseOnlyTheirOwnDesignBackdropAndDismissNormally(){
        val theme=mutableStateOf("magazine");val language=mutableStateOf("zh");val open=mutableStateOf(false)
        val i=InstrumentationRegistry.getInstrumentation();val density=i.targetContext.resources.displayMetrics.density
        val suffix=InstrumentationRegistry.getArguments().getString("captureSuffix")?.let{"-$it"}.orEmpty()
        val folder=File(i.targetContext.filesDir,"verification/sheet-backdrop$suffix").also{it.mkdirs()}
        val geometry=File(folder,"geometry.csv").also{it.writeText("theme,language,tag,top,width,height\n")}
        val file=File(folder,"colors.csv").also{it.writeText("theme,language,base,expected,actual\n")}
        ui.setContent{YohakuTheme(theme.value,language.value){
            Box(Modifier.fillMaxSize().testTag("sheet-host"))
            if(open.value)YSheet(word("账户与期初信息","口座・期首情報"),{open.value=false}){Box(Modifier.height(700.dp))}
        }}
        for(t in listOf("magazine","journal","objects"))for(l in listOf("zh","ja")){
            ui.runOnIdle{open.value=false;theme.value=t;language.value=l};ui.waitForIdle();Thread.sleep(350)
            val host=ui.onNodeWithTag("sheet-host").getUnclippedBoundsInRoot()
            val x=((host.right.value-2)*density).toInt();val y=(32*density).toInt()
            val base=i.uiAutomation.takeScreenshot().let{val pixel=it.getPixel(x,y);it.recycle();pixel}
            ui.runOnIdle{open.value=true};ui.waitForIdle();Thread.sleep(350)
            ui.runOnIdle{assertTrue("No additional Android dim layer",WindowInspector.getGlobalWindowViews().any{v->val p=v.layoutParams as? WindowManager.LayoutParams;p!=null&&p.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND!=0&&p.dimAmount==0f})}
            val shell=ui.onNodeWithTag("theme-sheet").getUnclippedBoundsInRoot()
            val expectedTops=when(t){"journal"->listOf(19f,36f,36f,39f,109f,880f);"objects"->listOf(13f,32f,32f,37f,102f,910f);else->listOf(22f,38f,38f,41f,118f,866f)}
            val expectedHeights=when(t){"journal"->listOf(3f,56f,42f,36f,33f,19f);"objects"->listOf(5f,55f,40f,30f,57f,60f);else->listOf(2f,60f,42f,36f,10f,26f)}
            for((index,tag) in listOf("sheet-grip","sheet-header","sheet-title","sheet-close","sheet-caption","sheet-colophon").withIndex()){
                val r=ui.onNodeWithTag(tag,useUnmergedTree=true).getUnclippedBoundsInRoot()
                if(i.targetContext.resources.configuration.let{it.fontScale==1f&&it.screenWidthDp==390}){
                    assertEquals("HTML shell top $t/$l/$tag",expectedTops[index],(r.top-shell.top).value,1f)
                    assertEquals("HTML shell height $t/$l/$tag",expectedHeights[index],(r.bottom-r.top).value,1f)
                }
                geometry.appendText("$t,$l,$tag,${(r.top-shell.top).value},${(r.right-r.left).value},${(r.bottom-r.top).value}\n")
            }
            val overlay=when(t){"magazine"->intArrayOf(26,52,44,110);"journal"->intArrayOf(97,86,65,98);else->intArrayOf(145,105,75,82)}
            val alpha=overlay[3]/255f
            val expected=Color.rgb((Color.red(base)*(1-alpha)+overlay[0]*alpha).roundToInt(),(Color.green(base)*(1-alpha)+overlay[1]*alpha).roundToInt(),(Color.blue(base)*(1-alpha)+overlay[2]*alpha).roundToInt())
            val actual=i.uiAutomation.takeScreenshot().let{bitmap->File(folder,"$t-$l.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};val pixel=bitmap.getPixel(x,y);bitmap.recycle();pixel}
            file.appendText("$t,$l,$base,$expected,$actual\n")
            assertTrue("CSS red $t/$l",abs(Color.red(expected)-Color.red(actual))<=1)
            assertTrue("CSS green $t/$l",abs(Color.green(expected)-Color.green(actual))<=1)
            assertTrue("CSS blue $t/$l",abs(Color.blue(expected)-Color.blue(actual))<=1)
            ui.onNodeWithText("×").performClick();ui.runOnIdle{assertFalse(open.value)}
            ui.runOnIdle{open.value=true};ui.waitForIdle();Thread.sleep(350)
            val viewport=ui.onNodeWithTag("sheet-viewport");val area=viewport.getUnclippedBoundsInRoot()
            val sheet=ui.onNodeWithTag("theme-sheet").getUnclippedBoundsInRoot()
            viewport.performTouchInput{click(Offset(width/2f,(sheet.top-area.top).value*density/2))}
            ui.runOnIdle{assertFalse("Background tap must close the sheet",open.value)}
        }
    }
    @Test fun longSheetsScrollGripAwayAndPinTitleLikeHtml(){
        val theme=mutableStateOf("magazine");val language=mutableStateOf("zh");val open=mutableStateOf(false)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val suffix=InstrumentationRegistry.getArguments().getString("captureSuffix")?.let{"-$it"}.orEmpty()
        val folder=File(context.filesDir,"verification/sheet-scroll$suffix").also{it.mkdirs()}
        val positions=File(folder,"positions.csv").also{it.writeText("theme,language,headerOffset,gripOffset\n")}
        ui.setContent{YohakuTheme(theme.value,language.value){
            Box(Modifier.fillMaxSize())
            if(open.value)YSheet(word("账户与期初信息","口座・期首情報"),{open.value=false}){Box(Modifier.height(1200.dp))}
        }}
        for(t in listOf("magazine","journal","objects"))for(l in listOf("zh","ja")){
            ui.runOnIdle{theme.value=t;language.value=l;open.value=true};ui.waitForIdle()
            ui.onNodeWithTag("sheet-scroll").performScrollToNode(hasTestTag("sheet-colophon"))
            val shell=ui.onNodeWithTag("theme-sheet").getUnclippedBoundsInRoot()
            val header=ui.onNodeWithTag("sheet-header",useUnmergedTree=true).getUnclippedBoundsInRoot()
            val grip=ui.onNodeWithTag("sheet-grip",useUnmergedTree=true).getUnclippedBoundsInRoot()
            val offset=(header.top-shell.top).value
            assertEquals("HTML sticky header $t/$l",when(t){"magazine"->22f;"journal"->19f;else->13f},offset,1f)
            assertTrue("Grip must scroll above the sheet",grip.bottom<shell.top)
            ui.onNodeWithTag("sheet-close",useUnmergedTree=true).assertIsDisplayed()
            positions.appendText("$t,$l,$offset,${(grip.top-shell.top).value}\n")
            ui.onNodeWithTag("theme-sheet").captureToImage().asAndroidBitmap().let{bitmap->
                File(folder,"$t-$l.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            }
            ui.onNodeWithTag("sheet-close",useUnmergedTree=true).performTouchInput{click()}
            ui.runOnIdle{assertFalse("Pinned close remains reachable",open.value)}
        }
    }

}
