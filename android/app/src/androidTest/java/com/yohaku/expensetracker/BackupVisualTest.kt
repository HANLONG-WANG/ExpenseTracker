package com.yohaku.expensetracker

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.data.backup.*
import com.yohaku.expensetracker.ui.components.YBackBar
import com.yohaku.expensetracker.ui.features.*
import com.yohaku.expensetracker.ui.theme.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Device-run visual evidence for the actual shared native components, never an HTML approximation. */
class BackupVisualTest {
    @get:Rule val ui=createComposeRule()
    @Test fun capturePageAndConfirmationInSixAppearances() {
        var theme by mutableStateOf("magazine")
        var language by mutableStateOf("zh")
        var confirming by mutableStateOf(false)
        val manifest=BackupManifest(format="yohaku.backup",formatVersion=1,versionName="0.5.0",versionCode=5,schemaVersion=29,createdAt="2026-10-26T03:30:00Z",
            books=listOf(BackupBook("daily","日常生活 / 毎日の暮らし",156),BackupBook("travel","京都旅行",28)),attachmentCount=24,attachmentBytes=12345678,entries=emptyList())
        ui.setContent {
            YohakuTheme(theme,language) {
                Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(22.dp).testTag("backup-page"),verticalArrangement=Arrangement.spacedBy(15.dp)) {
                    YBackBar(word("备份与恢复","バックアップと復元"),{})
                    BackupContent(BackupUiState(),{},{confirming=true},{},{})
                }
                if(confirming)BackupConfirmation(manifest,{confirming=false},{})
            }
        }
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=File(context.getExternalFilesDir(null),"backup-visual").apply{mkdirs()}
        fun capture(tag:String,name:String) {
            ui.onNodeWithTag(tag).captureToImage().asAndroidBitmap().let{bitmap->
                File(directory,name).outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
                bitmap.recycle()
            }
        }
        for(t in listOf("magazine","journal","objects"))for(l in listOf("zh","ja")) {
            ui.runOnIdle{theme=t;language=l;confirming=false}
            ui.onNodeWithTag("backup-export").performScrollTo().assertIsDisplayed()
            capture("backup-page","$t-$l-page.png")
            ui.onNodeWithTag("backup-open").performScrollTo().performClick()
            ui.onNodeWithTag("backup-confirm").performScrollTo().assertIsDisplayed()
            capture("theme-sheet","$t-$l-confirmation.png")
        }
    }
}
