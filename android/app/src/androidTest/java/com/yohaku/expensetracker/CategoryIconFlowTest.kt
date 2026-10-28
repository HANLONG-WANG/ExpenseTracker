package com.yohaku.expensetracker

import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.features.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Picks an icon through the real editor and sheet in every appearance; screenshots go to category-icon-visual/. */
@RunWith(AndroidJUnit4::class)
class CategoryIconFlowTest {
    @get:Rule val ui=createComposeRule()
    private fun tap(tag:String) {
        ui.waitUntil(10000){ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag(tag).performScrollTo();ui.waitForIdle();ui.onNodeWithTag(tag).performClick();ui.waitForIdle()
    }
    private fun shot(name:String) {
        if (!visualEnabled()) return
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        ui.waitForIdle();instrumentation.uiAutomation.waitForIdle(350,5000)
        val width=instrumentation.targetContext.resources.configuration.screenWidthDp
        val file=File(instrumentation.targetContext.getExternalFilesDir(null),"category-icon-visual/$width/$name.png");file.parentFile!!.mkdirs()
        val image=instrumentation.uiAutomation.takeScreenshot()
        file.outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
    }
    @Test fun pickIconThroughEditorAndShowItAcrossPages()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val path=File(context.cacheDir,"category-icon-${UUID.randomUUID()}.preferences_pb")
        val prefs=AppPreferences(context,PreferenceDataStoreFactory.create(scope=scope,produceFile={path}))
        val repo=LedgerRepository(db);val vm=AppViewModel(repo,prefs, testReferenceRates(prefs,repo.clock))
        fun current()=(vm.state.value as? AppState.Ready)?.snapshot
        try {
            ui.setContent{NativeApp(vm)}
            for((theme,language) in testLooks(this@CategoryIconFlowTest)) {
                val b=repo.createBook("生活",Currency.JPY,theme,language)
                repo.dao.putBook(repo.dao.book(b)!!.copy(initialized=true))
                val a=repo.saveAccount(b,"現金",AccountType.CASH,Currency.JPY,Money(Currency.JPY,50000),"2026-10-01")
                val food=repo.saveCategory(b,"餐饮",EntryKind.EXPENSE,null)
                val coffee=repo.saveCategory(b,"咖啡",EntryKind.EXPENSE,food)
                repo.saveCategory(b,"外食",EntryKind.EXPENSE,food)
                val transport=repo.saveCategory(b,"交通",EntryKind.EXPENSE,null)
                val train=repo.saveCategory(b,"电车",EntryKind.EXPENSE,transport)
                repo.saveCategory(b,"其他与手续费",EntryKind.EXPENSE,null)
                repo.saveCategory(b,"工资",EntryKind.INCOME,null)
                repo.saveEntry(b,EntryDraft(accountId=a,categoryId=coffee,amount="680",date="2026-10-16",merchant="喫茶と余白"))
                repo.saveEntry(b,EntryDraft(accountId=a,categoryId=train,amount="180",date="2026-10-16",merchant="JR 山手線"))
                assertEquals("coffee",repo.dao.category(b,coffee)!!.icon);assertNull(repo.dao.category(b,train)!!.icon)
                prefs.select(b);ui.waitUntil(10000){current()?.book?.id==b}
                ui.runOnIdle{vm.navigate("categories")};ui.waitForIdle()
                shot("$theme-$language-manage")
                tap("category-manage-child-$coffee");ui.onNodeWithTag("category-icon-field").performScrollTo()
                shot("$theme-$language-editor")
                tap("category-icon-open");shot("$theme-$language-sheet")
                tap("category-icon-tab-fun")
                // Scroll deep into the grid: tabs stay pinned under the title and the use button at the bottom.
                ui.onNodeWithTag("sheet-scroll").performSemanticsAction(SemanticsActions.ScrollBy){it(0f,1400f)};ui.waitForIdle()
                shot("$theme-$language-sheet-scrolled")
                val tabs=ui.onNodeWithTag("category-icon-tabs").getUnclippedBoundsInRoot();val header=ui.onNodeWithTag("sheet-header").getUnclippedBoundsInRoot()
                assertTrue("tabs pinned below the title",tabs.top>=header.bottom-1.dp)
                tap("category-icon-tab-food")
                ui.onNodeWithTag("category-icon-cell-teapot").performScrollTo().performClick()
                ui.onNodeWithTag("category-icon-use").performClick();ui.waitForIdle()
                ui.onNodeWithTag("category-icon-field").performScrollTo()
                shot("$theme-$language-editor-picked")
                tap("category-editor-save")
                ui.waitUntil(10000){current()?.categories?.firstOrNull{it.id==coffee}?.icon=="teapot"&&!vm.busy.value}
                shot("$theme-$language-manage-after")
                ui.runOnIdle{vm.navigate("ledger")};ui.waitForIdle();shot("$theme-$language-ledger")
                ui.runOnIdle{vm.startEntry(current()!!)};ui.waitForIdle()
                ui.waitUntil(10000){ui.onAllNodesWithTag("category-group-$food").fetchSemanticsNodes().isNotEmpty()}
                ui.onNodeWithTag("category-group-$food").performScrollTo();shot("$theme-$language-picker")
                ui.runOnIdle{vm.navigate("home")};ui.waitForIdle()
                assertNull(vm.error.value)
            }
        }finally{vm.viewModelScope.cancel();scope.cancel();db.close();path.delete()}
    }
}
