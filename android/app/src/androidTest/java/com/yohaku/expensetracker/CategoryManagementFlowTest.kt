package com.yohaku.expensetracker

import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.features.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CategoryManagementFlowTest {
    @get:Rule val ui=createComposeRule()
    private fun tap(tag:String) {
        ui.waitUntil(10000){ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}
        val node=ui.onNodeWithTag(tag).performScrollTo();ui.waitForIdle()
        if(ui.onAllNodesWithTag("sheet-header").fetchSemanticsNodes().isNotEmpty()) {
            val density=InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
            val top=node.getUnclippedBoundsInRoot().top.value
            val header=ui.onNodeWithTag("sheet-header").getUnclippedBoundsInRoot().bottom.value
            if(top<header+8)ui.onNodeWithTag("sheet-scroll").performSemanticsAction(SemanticsActions.ScrollBy){it(0f,-(header+8-top)*density)}
        }
        ui.waitForIdle();node.performClick();ui.waitForIdle()
    }
    private fun shot(name:String) {
        if (!visualEnabled()) return
        ui.waitForIdle()
        val identity=ui.onNodeWithTag("category-operation-name").getUnclippedBoundsInRoot()
        val header=ui.onNodeWithTag("sheet-header").getUnclippedBoundsInRoot()
        assertTrue("Preview identity must be below the sticky header",identity.top>=header.bottom)
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(350,5000)
        val context=instrumentation.targetContext
        val file=File(context.getExternalFilesDir(null),"phase-e-category-management/$name.png");file.parentFile!!.mkdirs();writeManagementVisualBounds(ui,file)
        val image=instrumentation.uiAutomation.takeScreenshot()
        file.outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
        image.recycle()
    }
    private fun pageShot(name:String,focus:String) {
        if (!visualEnabled()) return
        // Bring the expanded shelf above the bottom navigation before capturing.
        ui.onNodeWithTag(focus).performScrollTo();ui.onRoot().performTouchInput{swipeUp(startY=centerY,endY=centerY-height*.25f,durationMillis=900)}
        ui.waitForIdle()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(350,5000)
        val file=File(instrumentation.targetContext.getExternalFilesDir(null),"phase-e-category-management/$name.png");file.parentFile!!.mkdirs()
        val image=instrumentation.uiAutomation.takeScreenshot()
        file.outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
        image.recycle()
    }
    @Test fun archiveRestoreMoveAndMergeAcrossAppearances()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val path=File(context.cacheDir,"category-ui-${UUID.randomUUID()}.preferences_pb")
        val prefs=AppPreferences(context,PreferenceDataStoreFactory.create(scope=scope,produceFile={path}))
        val repo=LedgerRepository(db);val vm=AppViewModel(repo,prefs, testReferenceRates(prefs,repo.clock))
        fun current()=(vm.state.value as? AppState.Ready)?.snapshot
        try {
            ui.setContent{NativeApp(vm)}
            for((theme,language) in testLooks(this@CategoryManagementFlowTest)) {
                fun label(zh:String,ja:String)=if(language=="ja")ja else zh
                val b=repo.createBook("生活 / 暮らし",Currency.JPY,theme,language)
                repo.dao.putBook(repo.dao.book(b)!!.copy(initialized=true))
                val a=repo.saveAccount(b,"現金",AccountType.CASH,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
                val parent=repo.saveCategory(b,"食事",EntryKind.EXPENSE,null)
                val child=repo.saveCategory(b,"コーヒー",EntryKind.EXPENSE,parent)
                val target=repo.saveCategory(b,"日常",EntryKind.EXPENSE,null)
                repo.saveEntry(b,EntryDraft(accountId=a,categoryId=child,amount="500",date="2026-10-16"))
                repo.dao.putBudget(BudgetEntity(b,"2026-10",child,child,1000))
                prefs.select(b);ui.waitUntil(10000){current()?.book?.id==b}
                ui.runOnIdle{vm.navigate("categories")}
                tap("category-manage-parent-$parent");tap("category-manage");tap("category-operation-preview")
                shot("$theme-$language-stop");tap("confirm-category-operation")
                ui.waitUntil(10000){current()?.categories?.firstOrNull{it.id==parent}?.active==false&&!vm.busy.value}
                tap("category-archive-toggle-EXPENSE");ui.onNodeWithTag("category-archived-child-$child").assertExists();pageShot("$theme-$language-archive-group","category-archived-child-$child")
                tap("category-restore-$parent")
                ui.waitUntil(10000){current()?.categoryEnabled(child)==true&&!vm.busy.value}
                tap("category-manage-child-$child");tap("category-manage");tap("category-operation-preview");tap("confirm-category-operation")
                ui.waitUntil(10000){current()?.categories?.firstOrNull{it.id==child}?.active==false&&!vm.busy.value}
                ui.onAllNodesWithTag("category-manage-child-$child").assertCountEquals(0)
                tap("category-archive-toggle-EXPENSE");pageShot("$theme-$language-archive-shelf","category-restore-$child")
                tap("category-restore-$child")
                ui.waitUntil(10000){current()?.categoryEnabled(child)==true&&!vm.busy.value}
                tap("category-manage-child-$child");tap("category-manage")
                tap("select-trigger-"+label("操作","操作"));ui.onNodeWithText(label("调整父级，保留分类ID","親を変更し、カテゴリIDを保持")).performClick()
                tap("select-trigger-"+label("迁往分类 / 父级","統合先・新しい親"));ui.onNode(hasText("日常") and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role,androidx.compose.ui.semantics.Role.RadioButton)).performClick()
                tap("category-operation-preview");shot("$theme-$language-reparent");tap("confirm-category-operation")
                ui.waitUntil(10000){current()?.categories?.firstOrNull{it.id==child}?.parentId==target&&!vm.busy.value}
                tap("category-manage-child-$child");tap("category-manage")
                tap("select-trigger-"+label("操作","操作"));ui.onNodeWithText(label("迁移整组资料并删除分类","情報を統合しカテゴリを削除")).performClick()
                tap("select-trigger-"+label("迁往分类 / 父级","統合先・新しい親"));ui.onNode(hasText("日常") and SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role,androidx.compose.ui.semantics.Role.RadioButton)).performClick()
                tap("select-trigger-"+label("分类预算处理","カテゴリ予算の扱い"));ui.onNodeWithText(label("合并已设额度，父级覆盖时不重复","設定額を合算、親の包含分は重複しない")).performClick()
                tap("category-operation-preview");shot("$theme-$language-merge");tap("confirm-category-operation")
                ui.waitUntil(10000){current()?.categories?.none{it.id==child}==true&&!vm.busy.value}
                assertEquals(target,current()!!.events.single().categoryId);assertEquals(1000L,current()!!.budgetLimit("2026-10",target));assertNull(vm.error.value)
            }
        }finally{vm.viewModelScope.cancel();scope.cancel();db.close();path.delete()}
    }
}
