package com.yohaku.expensetracker

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.ui.theme.YohakuTheme
import org.junit.*
import org.junit.Assert.*
import java.io.File
import kotlin.math.abs
import kotlin.math.max

class ThemePaperTest {
    @get:Rule val ui=createComposeRule()
    @Test fun themeSurfacesMatchTheActualHtmlLayers(){
        val theme=mutableStateOf("magazine")
        val i=InstrumentationRegistry.getInstrumentation()
        val width=i.targetContext.resources.configuration.screenWidthDp
        Assume.assumeTrue("Paper pixel references require 390dp or 320dp; other widths need their own reference",width==390||width==320)
        val suffix=InstrumentationRegistry.getArguments().getString("captureSuffix")?.let{"-$it"}.orEmpty()
        val folder=File(i.targetContext.filesDir,"verification/theme-paper$suffix").also{it.mkdirs()}
        val measurements=File(folder,"pixels.csv").also{it.writeText("theme,width,samples,maxChannelDelta,pixelsOverOne\n")}
        ui.setContent{Column(Modifier.fillMaxSize().safeDrawingPadding()){
            Box(Modifier.fillMaxWidth().height(120.dp).testTag("paper-fixture")){YohakuTheme(theme.value,"zh") {}}
        }}
        for(t in listOf("magazine","journal","objects")){
            ui.runOnIdle{theme.value=t}
            val actual=ui.onNodeWithTag("paper-fixture").captureToImage().asAndroidBitmap()
            val expected=i.context.assets.open("theme-paper-$t-$width.png").use{BitmapFactory.decodeStream(it)}
            val density=i.targetContext.resources.displayMetrics.density
            assertEquals((width*density).toInt(),actual.width)
            assertEquals((120*density).toInt(),actual.height)
            var largest=0;var pixelsOverOne=0
            for(y in 0 until expected.height)for(x in 0 until expected.width){
                val a=actual.getPixel(((x+.5f)*density).toInt(),((y+.5f)*density).toInt())
                val e=expected.getPixel(x,y)
                val delta=max(abs(Color.red(a)-Color.red(e)),max(abs(Color.green(a)-Color.green(e)),abs(Color.blue(a)-Color.blue(e))))
                largest=max(largest,delta)
                if(delta>1)pixelsOverOne++
            }
            measurements.appendText("$t,$width,${expected.width*expected.height},$largest,$pixelsOverOne\n")
            File(folder,"$t.png").outputStream().use{actual.compress(Bitmap.CompressFormat.PNG,100,it)}
            expected.recycle()
            // Chrome/Android source-over rounding differs by two RGB levels at two rule/margin crossings.
            assertTrue("HTML surface $t: maximum RGB difference $largest",largest<=if(t=="journal")2 else 0)
            assertTrue("Only the two observed alpha crossings may differ by more than one",pixelsOverOne<=if(t=="journal")2 else 0)
        }
    }
}
