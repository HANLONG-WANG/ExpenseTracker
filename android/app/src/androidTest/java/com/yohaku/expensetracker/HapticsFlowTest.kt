package com.yohaku.expensetracker

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.data.db.YohakuDatabase
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.Currency
import com.yohaku.expensetracker.ui.features.*
import com.yohaku.expensetracker.ui.haptics.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class HapticsFlowTest {
    @get:Rule val ui=createComposeRule()
    @get:Rule(order=1) val failureArtifacts=RegressionFailureArtifacts(ui)
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val recorder=RecordingHapticOutput()
    private lateinit var db:YohakuDatabase
    private lateinit var repo:LedgerRepository
    private lateinit var vm:AppViewModel
    private lateinit var scope:CoroutineScope
    private lateinit var path:File
    @Before fun setup() {
        db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build();repo=LedgerRepository(db)
        scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        path=File(context.cacheDir,"haptics-${UUID.randomUUID()}.preferences_pb")
        val prefs=AppPreferences(context,PreferenceDataStoreFactory.create(scope=scope,produceFile={path}))
        vm=AppViewModel(repo,prefs,testReferenceRates(prefs,repo.clock))
    }
    @After fun close(){vm.viewModelScope.cancel();scope.cancel();db.close();path.delete()}
    private fun tap(tag:String){ui.onNodeWithTag(tag).performScrollTo().performClick();ui.waitForIdle()}
    private fun openSettings(theme:String,language:String="zh")=runBlocking {
        val book=repo.createBook("Haptics",Currency.JPY,theme,language)
        repo.dao.putBook(repo.dao.book(book)!!.copy(initialized=true));vm.preferences.select(book)
        ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book&&vm.hapticsEnabled.value==true}
        ui.runOnIdle{vm.navigate("settings")}
        ui.waitUntil(10000){ui.onAllNodesWithTag("settings-haptics").fetchSemanticsNodes().isNotEmpty()}
        ui.waitForIdle();recorder.clear()
    }
    @Test fun toggleUsesEachTheme() {
        ui.setContent{NativeApp(vm,haptics=recorder)}
        for(theme in listOf("magazine","journal","objects")) {
            openSettings(theme)
            tap("settings-reference-rates")
            ui.waitUntil(10000){ui.onAllNodesWithTag("rates-automatic").fetchSemanticsNodes().isNotEmpty()}
            tap("rates-automatic")
            ui.waitUntil(10000){ui.onNodeWithTag("rates-automatic").fetchSemanticsNode().config[SemanticsProperties.ToggleableState]==ToggleableState.Off}
            tap("rates-automatic")
            ui.waitUntil(10000){ui.onNodeWithTag("rates-automatic").fetchSemanticsNode().config[SemanticsProperties.ToggleableState]==ToggleableState.On}
            assertEquals(listOf(Triple(theme,Haptic.ToggleOff,1f),Triple(theme,Haptic.ToggleOn,1f)),recorder.calls.toList())
        }
    }
    @Test fun settingsDisableAndEnableFeedback() {
        ui.setContent{NativeApp(vm,haptics=recorder)}
        for((theme,language) in testLooks(this)) {
            openSettings(theme,language)
            if(visualEnabled()) {
                ui.onNodeWithTag("settings-haptics").performScrollTo();ui.waitForIdle()
                val file=File(context.getExternalFilesDir(null),"verification/haptics/$theme-$language-settings.png");file.parentFile!!.mkdirs()
                val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            }
            tap("settings-haptics")
            ui.waitUntil(10000){vm.hapticsEnabled.value==false};ui.waitForIdle()
            ui.onNodeWithTag("nav-analysis").performClick();ui.waitForIdle()
            ui.runOnIdle{vm.haptic(Haptic.Select);vm.navigate("settings")};ui.waitForIdle()
            assertTrue(recorder.calls.isEmpty())
            tap("settings-haptics")
            ui.waitUntil(10000){recorder.calls.isNotEmpty()}
            assertEquals(listOf(Triple(theme,Haptic.ToggleOn,1f)),recorder.calls.toList())
            recorder.clear();ui.runOnIdle{vm.haptic(Haptic.Select)}
            ui.waitUntil(10000){recorder.calls.isNotEmpty()}
            assertEquals(Triple(theme,Haptic.Select,1f),recorder.calls.single())
        }
    }
    @Test fun executeExceptionEmitsErrorUsingCurrentTheme() {
        ui.setContent{NativeApp(vm,haptics=recorder)}
        openSettings("journal")
        ui.runOnIdle{vm.execute{throw IllegalStateException("haptics test")}}
        ui.waitUntil(10000){recorder.calls.any{it.second==Haptic.Error}}
        assertEquals(listOf(Triple("journal",Haptic.Error,1f)),recorder.calls.toList())
        assertEquals("haptics test",vm.error.value)
    }
}
