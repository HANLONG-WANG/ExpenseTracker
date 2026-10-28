package com.yohaku.expensetracker

import android.graphics.Bitmap
import android.view.Window
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.ViewCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SystemWindowThemeTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    private var sheetWindow:Window?=null
    private fun check(window:Window) {
        val bars=WindowCompat.getInsetsController(window,window.decorView)
        assertTrue("Light paper requires dark status icons",bars.isAppearanceLightStatusBars)
        assertTrue("Light paper requires dark navigation icons",bars.isAppearanceLightNavigationBars)
    }
    private fun shot(name:String) {
        if (!visualEnabled()) return
        ui.waitForIdle();val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(300,5000);val bitmap=automation.takeScreenshot()
        val file=File(ui.activity.getExternalFilesDir(null),"phase-b-system-windows/$name.png");file.parentFile!!.mkdirs()
        file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    @Test fun productionActivityAndThemedDialogKeepReadableIconsAfterRecreationAndImeResize() {
        org.junit.Assume.assumeTrue("Requires visual=true", visualEnabled())
        val mode=InstrumentationRegistry.getArguments().getString("systemMode")?:"unspecified"
        ui.activityRule.scenario.recreate()
        ui.runOnIdle{check(ui.activity.window)}
        for((theme,language) in testLooks(this@SystemWindowThemeTest)) {
            sheetWindow=null
            // Exercise the production Activity's window and the actual shared theme/dialog components.
            // This local scene does not seed or edit any user book, preferences or ledger data.
            ui.runOnUiThread{ui.activity.setContent {
                YohakuTheme(theme,language) {
                    var show by remember{mutableStateOf(false)}
                    var amount by remember{mutableStateOf("")}
                    Box(Modifier.fillMaxSize().background(LocalYohaku.current.bg)) {
                        Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
                            YText("WINDOW / $theme / $language")
                            YButton(word("打开预算弹层","予算の画面を開く"),{show=true},Modifier.testTag("window-open-sheet"))
                        }
                    }
                    if(show)YSheet(word("调整预算","予算を変更"),{show=false}) {
                        val window=(LocalView.current.parent as DialogWindowProvider).window
                        SideEffect{sheetWindow=window}
                        YField(word("金额","金額"),amount,{amount=it},numeric=true)
                        YButton(word("返回","戻る"),{show=false},Modifier.testTag("window-return"))
                    }
                }
            }}
            ui.runOnIdle{check(ui.activity.window)}
            ui.onNodeWithTag("window-open-sheet").performClick()
            ui.waitUntil(10000){sheetWindow!=null}
            ui.runOnIdle{check(sheetWindow!!)}
            shot("$mode-$theme-$language-sheet")
            val field=ui.onNodeWithTag("field-"+(if(language=="ja")"金額"else"金额"))
            field.performClick();field.performTextReplacement("1234")
            if(InstrumentationRegistry.getArguments().getString("realIme")=="true") {
                ui.waitUntil(10000){ViewCompat.getRootWindowInsets(sheetWindow!!.decorView)?.isVisible(WindowInsetsCompat.Type.ime())==true}
                ui.runOnIdle{check(sheetWindow!!)}
                shot("$mode-$theme-$language-ime")
            }
            ui.onNodeWithTag("sheet-close").performClick()
            ui.waitUntil(10000){ui.onAllNodesWithTag("theme-sheet").fetchSemanticsNodes().isEmpty()}
            ui.runOnIdle{check(ui.activity.window)}
        }
    }
}
