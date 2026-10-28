package com.yohaku.expensetracker

import android.graphics.Bitmap
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.features.pageContentPadding
import com.yohaku.expensetracker.ui.theme.*
import org.junit.*
import org.junit.runner.RunWith
import java.io.File

/** Same text as capture-lead-reference.js; fixtures never write a ledger. */
@RunWith(AndroidJUnit4::class)
class LeadCoverTest {
    @get:Rule val ui=createComposeRule()
    @Test fun leadStatesUseThemeFontsAndMeasuredLineBoxes(){
        org.junit.Assume.assumeTrue("Requires visual=true", visualEnabled())
        val theme=mutableStateOf("magazine");val language=mutableStateOf("zh");val kind=mutableStateOf("setup")
        ui.setContent{YohakuTheme(theme.value,language.value){
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(pageContentPadding()).verticalScroll(rememberScrollState())){
                val welcome=kind.value=="welcome"
                YLead(if(welcome)word("从今天开始，留一点余量。","今日から、少し余白を。") else word("先给日常，一个名字。","日々に、名前を。"),
                    if(welcome)word("不用补齐所有过去。从现有余额和欠款开始，慢慢记下日常。","過去の全記録は不要。今の残高と借入から、日々を少しずつ。") else word("本位币用于预算和分析；账户仍可持有不同币种。","基準通貨は予算・分析用。口座は別の通貨も持てます。"),
                    if(welcome)"YOUR FIRST PAGE" else "FIRST STEPS / 01",
                    if(welcome)word("你好，余量。","こんにちは、ヨハク。") else if(kind.value=="balance")"¥10,000" else "",
                    if(welcome)word("分类从空白开始","カテゴリは空白から") else if(kind.value=="balance")word("账户余额","口座残高") else "",amountIsText=welcome)
            }
        }}
        val i=InstrumentationRegistry.getInstrumentation()
        val suffix=InstrumentationRegistry.getArguments().getString("captureSuffix")?.let{"-$it"}.orEmpty()
        val folder=File(i.targetContext.filesDir,"verification/lead$suffix").also{it.mkdirs()}
        val rows=mutableListOf<String>()
        for((t,l) in testLooks(this@LeadCoverTest))for(k in listOf("setup","balance","welcome")){
            ui.runOnIdle{theme.value=t;language.value=l;kind.value=k}
            ui.waitForIdle()
            ui.onNodeWithTag("lead-title").assertExists()
            if(k=="balance")ui.onNodeWithText("¥10,000",substring=true).assertExists()
            if(k=="welcome")ui.onNodeWithText(if(l=="zh")"你好，余量。" else "こんにちは、ヨハク。",substring=true).assertExists()
            val density=i.targetContext.resources.displayMetrics.density
            for(tag in listOf("lead-cover","lead-title","lead-description")+if(k=="setup")emptyList() else listOf("lead-amount")){
                val r=ui.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
                rows+="$t,$l,$k,$tag,${r.width/density},${r.height/density}"
            }
            Thread.sleep(100)
            val screenshot=i.uiAutomation.takeScreenshot()
            File(folder,"$t-$l-$k.png").outputStream().use{screenshot.compress(Bitmap.CompressFormat.PNG,100,it)};screenshot.recycle()
        }
        if (visualEnabled()) File(folder,"dimensions.csv").writeText(rows.joinToString("\n"))
    }
}
