package com.yohaku.expensetracker

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.data.files.*
import com.yohaku.expensetracker.ui.components.ReceiptPaper
import com.yohaku.expensetracker.ui.features.ReceiptItemRows
import com.yohaku.expensetracker.ui.features.ReceiptTotalLines
import com.yohaku.expensetracker.ui.theme.YohakuTheme
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Uncertain values are marked only while reviewing a result; differences appear only when something is comparable. */
class ReceiptItemsReviewUiTest {
    @get:Rule val ui=createComposeRule()

    private fun record(items:List<ReceiptItem>,total:String?,adjustments:List<ReceiptAdjustment> = emptyList(),issues:List<String> = emptyList())=
        ReceiptItemsRecord("event","receipt",1,1,"JPY",listOf("image"),items,adjustments,false,issues,total)

    @Test fun reviewMarksUncertainValuesAndSavedDetailsShowOnlyRealDifferences() {
        val unsure=record(listOf(ReceiptItem("a",listOf("image"),listOf("name","line_amount"),"シュークリーム","2","個","190","380"),
            ReceiptItem("b",listOf("image"),listOf("unit_price","line_amount"),"ミルクティー","1","杯")),"1600",
            issues=listOf("2行目の金額が読み取れません"))
        val taxed=record(listOf(ReceiptItem("c",listOf("image"),emptyList(),"パン","1",null,"1564","1564")),"1688",
            listOf(ReceiptAdjustment("tax","124",false)))
        val unknown=record(listOf(ReceiptItem("d",listOf("image"),listOf("line_amount"),"処方箋料","1")),null)
        // name, record, original amount, review mode, texts that must show, texts that must not
        val cases=listOf(
            Triple("review",Triple(unsure,"1600",true),listOf("シュークリーム?","¥380?","2行目の金額が読み取れません") to listOf("相差","差 ")),
            Triple("saved",Triple(unsure,"1600",false),listOf("シュークリーム","¥380") to listOf("シュークリーム?","¥380?")),
            Triple("difference",Triple(taxed,"1601",false),listOf("¥87") to emptyList()),
            Triple("unknown",Triple(unknown,"1420",false),listOf("処方箋料") to listOf("相差","差 ","−¥1,420"))
        )
        val theme=mutableStateOf("magazine");val language=mutableStateOf("zh");val case=mutableIntStateOf(0)
        ui.setContent {
            YohakuTheme(theme.value,language.value) {
                key(theme.value,language.value,case.intValue) {
                    val (target,original,review)=cases[case.intValue].second
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        ReceiptPaper {
                            val totals=receiptTotals(target,FileMoney("JPY",original))
                            ReceiptItemRows(target,totals.knownItems,markUncertain=review)
                            ReceiptTotalLines(target,totals)
                        }
                    }
                }
            }
        }
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        for((t,l) in testLooks(this))for((index,fixture) in cases.withIndex()) {
            ui.runOnIdle{theme.value=t;language.value=l;case.intValue=index}
            val (present,absent)=fixture.third
            present.forEach{ui.onAllNodesWithText(it,substring=true).onFirst().assertExists()}
            absent.forEach{ui.onAllNodesWithText(it,substring=true).assertCountEquals(0)}
            if(visualEnabled()) {
                ui.waitForIdle()
                val suffix=InstrumentationRegistry.getArguments().getString("captureSuffix").orEmpty()
                val folder=File(instrumentation.targetContext.getExternalFilesDir(null),"receipt-items-review").also{it.mkdirs()}
                val bitmap=instrumentation.uiAutomation.takeScreenshot()
                File(folder,"$t-$l-${fixture.first}$suffix.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
                bitmap.recycle()
            }
        }
    }
}
