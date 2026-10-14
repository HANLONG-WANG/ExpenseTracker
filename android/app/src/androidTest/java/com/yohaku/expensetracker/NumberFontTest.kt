package com.yohaku.expensetracker

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.ui.components.YText
import com.yohaku.expensetracker.ui.theme.YohakuTheme
import org.junit.Test
import org.junit.Rule
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NumberFontTest{
    @get:Rule val ui=createComposeRule()
    private data class Sample(val text:String,val size:Int,val spacing:Float)
    @Test fun magazineNumbersUseLicensedFontWithoutChangingAmounts(){
        val samples=listOf(Sample("¥86,180",49,-2f),Sample("¥650",45,0f),Sample("¥9,700",23,-1f),Sample("CN¥12,345.67",21,0f),Sample("US$1,280.50",21,0f),Sample("−¥8,600",21,0f),Sample("0123456789",30,0f),Sample("¥999,999,999",23,-1f))
        val metrics=mutableMapOf<Int,String>()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val density=instrumentation.targetContext.resources.displayMetrics.density
        val suffix=InstrumentationRegistry.getArguments().getString("captureSuffix")?.let{"-$it"}.orEmpty()
        val folder=File(instrumentation.targetContext.filesDir,"verification/number-font$suffix").also{it.mkdirs()}
        ui.setContent{YohakuTheme("magazine","zh"){
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)){
                samples.forEachIndexed{i,s->Column{
                    YText("${s.size} / ${s.spacing}",10)
                    YText(s.text,s.size,Modifier.testTag("number-$i"),number=true,letterSpacing=s.spacing,lineHeightMultiplier=1.3f,onTextLayout={r->
                        metrics[i]="$i,${s.size},${s.spacing},${r.getLineRight(0)/density},${r.size.height/density},${r.firstBaseline/density},${r.lineCount}"
                    })
                }}
            }
        }}
        for(i in samples.indices)ui.onNodeWithTag("number-$i").performScrollTo().assertTextEquals(samples[i].text)
        ui.waitForIdle()
        assertEquals(samples.size,metrics.size)
        assertTrue(metrics.values.all{it.endsWith(",1")})
        File(folder,"dimensions.csv").writeText("sample,size,spacing,advance,height,baseline,lines,layoutWidth\n"+samples.indices.joinToString("\n"){i->metrics.getValue(i)+","+ui.onNodeWithTag("number-$i").fetchSemanticsNode().boundsInRoot.width/density}+"\n")
        ui.onNodeWithTag("number-0").performScrollTo()
        instrumentation.uiAutomation.takeScreenshot().let{bitmap->
            File(folder,"magazine-number-samples.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        }
    }
}
