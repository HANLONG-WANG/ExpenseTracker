package com.yohaku.expensetracker

import android.graphics.Bitmap
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.ui.components.SetupIntro
import com.yohaku.expensetracker.ui.theme.*
import org.junit.*
import org.junit.Assert.*
import java.io.File

class IntroPresentationTest{
    @get:Rule val ui=createComposeRule()
    @Test fun introRowsMatchSourceAndKeepIndependentThemeInBothLanguages(){
        val theme=mutableStateOf("magazine");val language=mutableStateOf("zh")
        val i=InstrumentationRegistry.getInstrumentation()
        val suffix=InstrumentationRegistry.getArguments().getString("captureSuffix")?.let{"-$it"}.orEmpty()
        val folder=File(i.targetContext.filesDir,"verification/intro$suffix").also{it.mkdirs()}
        val data=File(folder,"dimensions.csv").also{it.writeText("theme,language,row,width,height\n")}
        ui.setContent{YohakuTheme(theme.value,language.value){
            val narrow=LocalConfiguration.current.screenWidthDp<=360
            val padding=when(theme.value){"journal"->PaddingValues(start=if(narrow)25.dp else 28.dp,end=if(narrow)16.dp else 19.dp);"objects"->PaddingValues(horizontal=if(narrow)17.dp else 20.dp);else->PaddingValues(horizontal=if(narrow)18.dp else 25.dp)}
            Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(padding)){
                SetupIntro(listOf(word("一本独立的账本","独立した帳簿") to word("账户、预算、活动和资料各自独立。","口座・予算・シーン・資料を独立管理。"),word("期初不是新的消费","期首残高は新しい支出ではない") to word("现有余额、欠款和已确认账单分别填写。","今の残高・借入・確定明細を分けて入力。"),word("复杂规则可以稍后补齐","計算条件は後からでも") to word("没有规则时，只展示已知安排，不假装能准确预测。","条件未設定なら既知の予定のみ。正確な予測を装いません。")))
            }
        }}
        for(t in listOf("magazine","journal","objects"))for(l in listOf("zh","ja")){
            ui.runOnIdle{theme.value=t;language.value=l}
            for(row in 0..2){
                val r=ui.onNodeWithTag("setup-intro-row-$row").getUnclippedBoundsInRoot()
                val height=(r.bottom-r.top).value
                data.appendText("$t,$l,$row,${(r.right-r.left).value},$height\n")
                if(i.targetContext.resources.configuration.fontScale==1f&&i.targetContext.resources.configuration.screenWidthDp==390){
                    val expected=when(t){"journal"->if(row==2)127f else if(l=="ja"&&row==1)136f else 104f;"objects"->94f;else->99f}
                    assertEquals("HTML intro row: $t/$l/$row",expected,height,1f)
                }
            }
            ui.onNodeWithTag("setup-intro-row-0").performScrollTo()
            i.uiAutomation.takeScreenshot().let{bitmap->File(folder,"$t-$l.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
        }
    }
}
