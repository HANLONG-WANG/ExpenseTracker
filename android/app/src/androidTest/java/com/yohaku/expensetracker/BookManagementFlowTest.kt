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
class BookManagementFlowTest {
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
        ui.waitForIdle()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(350,5000)
        val context=instrumentation.targetContext
        val file=File(context.getExternalFilesDir(null),"phase-e-book-management/$name.png");file.parentFile!!.mkdirs();writeManagementVisualBounds(ui,file)
        val image=instrumentation.uiAutomation.takeScreenshot()
        file.outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
        image.recycle()
    }
    @Test fun lastBookArchiveTrashRestoreAndPermanentConfirmationStayReachableAcrossAppearances()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val path=File(context.cacheDir,"book-ui-${UUID.randomUUID()}.preferences_pb")
        val prefs=AppPreferences(context,PreferenceDataStoreFactory.create(scope=scope,produceFile={path}))
        val repo=LedgerRepository(db);val vm=AppViewModel(repo,prefs)
        try {
            ui.setContent{NativeApp(vm)}
            for(theme in listOf("magazine","journal","objects"))for(language in listOf("zh","ja")) {
                val b=repo.createBook("生活 / 暮らし",Currency.JPY,theme,language)
                repo.dao.putBook(repo.dao.book(b)!!.copy(initialized=true))
                val a=repo.saveAccount(b,"現金",AccountType.CASH,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
                val c=repo.saveCategory(b,"食事",EntryKind.EXPENSE,null)
                val event=repo.saveEntry(b,EntryDraft(accountId=a,categoryId=c,amount="500",merchant="原名",date="2026-10-16"))
                prefs.select(b)
                ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==b}
                ui.runOnIdle{vm.navigate("books")}
                tap("books-manage-lifecycle")
                tap("book-archive-$b");tap("confirm-book-operation")
                ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.let{it.snapshot==null&&it.books.singleOrNull()?.archived==true}==true}
                ui.onAllNodesWithTag("book-choice-$b").assertCountEquals(0)
                ui.onNodeWithTag("app-header").performScrollTo()
                ui.onAllNodesWithTag("header-language").assertCountEquals(0)
                ui.onAllNodesWithTag("header-settings").assertCountEquals(0)
                if(theme=="magazine") {
                    val today=java.time.LocalDate.now()
                    val left=ui.onNodeWithText("YOHAKU / BOOK-MANAGEMENT").getUnclippedBoundsInRoot()
                    val right=ui.onNodeWithText("VOL. ${today.monthValue} · ${today.year}").getUnclippedBoundsInRoot()
                    assertTrue("Long edition text must leave room for its date",left.right<=right.left)
                }
                shot("$theme-$language-library-header")
                ui.onNodeWithTag("managed-book-$b").performScrollTo()
                shot("$theme-$language-archived")
                tap("book-trash-$b")
                shot("$theme-$language-trash-preview")
                tap("confirm-book-operation")
                ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.books?.singleOrNull()?.deletedAt!=null}
                assertNotNull(repo.dao.storedBook(b));assertNull(repo.dao.book(b))
                if(ui.onAllNodesWithTag("book-restore-$b").fetchSemanticsNodes().isEmpty())tap("book-management-toggle-trash")
                ui.onNodeWithTag("managed-book-$b").performScrollTo();shot("$theme-$language-trashed")
                tap("book-restore-$b");tap("confirm-book-operation")
                ui.waitUntil(10000){repoBookRestored(vm,b)}
                if(ui.onAllNodesWithTag("book-archive-$b").fetchSemanticsNodes().isEmpty())tap("book-management-toggle-trash")
                tap("book-archive-$b");tap("confirm-book-operation")
                ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.let{it.id==b&&!it.archived}==true}
                val restored=repo.snapshot(repo.dao.book(b)!!).first()
                assertEquals(event,restored.events.single().id);assertEquals(9500L,restored.balance(restored.accounts.single()))
                tap("book-trash-$b");tap("confirm-book-operation")
                ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.books?.singleOrNull()?.deletedAt!=null}
                if(ui.onAllNodesWithTag("book-purge-$b").fetchSemanticsNodes().isEmpty())tap("book-management-toggle-trash")
                tap("book-purge-$b")
                assertNotNull(repo.dao.storedBook(b)) // Preview itself never deletes.
                tap("confirm-book-operation")
                ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.books?.isEmpty()==true&&!vm.busy.value}
                assertNull(repo.dao.storedBook(b));assertNull(vm.error.value)
            }
        }finally{vm.viewModelScope.cancel();scope.cancel();db.close();path.delete()}
    }
    @Test fun emptyTrashConfirmsBothBooksAndLeavesTheActiveBookAcrossAppearances()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val path=File(context.cacheDir,"book-empty-${UUID.randomUUID()}.preferences_pb")
        val prefs=AppPreferences(context,PreferenceDataStoreFactory.create(scope=scope,produceFile={path}))
        val repo=LedgerRepository(db);val vm=AppViewModel(repo,prefs)
        try {
            val keep=repo.createBook("保留 / 保持",Currency.JPY)
            repo.dao.putBook(repo.dao.book(keep)!!.copy(initialized=true));prefs.select(keep)
            val bank=repo.saveAccount(keep,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
            val category=repo.saveCategory(keep,"生活",EntryKind.EXPENSE,null)
            val event=repo.saveEntry(keep,EntryDraft(accountId=bank,categoryId=category,amount="500",date="2026-10-16"))
            ui.setContent{NativeApp(vm)}
            for(theme in listOf("magazine","journal","objects"))for(language in listOf("zh","ja")) {
                repo.updateAppearance(keep,theme,language)
                val trash=(1..2).map {n->
                    val b=repo.createBook("回收$n / 回収$n",Currency.JPY,theme,language)
                    repo.dao.putBook(repo.dao.book(b)!!.copy(initialized=true))
                    assertTrue(repo.commitBookOperation(repo.previewBookOperation(b,BookOperation.TRASH)));b
                }
                ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.let{it.books.size==3&&it.snapshot?.book?.theme==theme&&it.snapshot.book.language==language}==true}
                ui.runOnIdle{vm.navigate("book-management")}
                if(ui.onAllNodesWithTag("empty-book-trash").fetchSemanticsNodes().isEmpty())tap("book-management-toggle-trash")
                tap("empty-book-trash")
                assertTrue(trash.all{repo.dao.storedBook(it)!=null})
                shot("$theme-$language-empty-preview")
                ui.onNodeWithTag("sheet-close").performClick();ui.waitForIdle()
                assertTrue(trash.all{repo.dao.storedBook(it)!=null})
                tap("empty-book-trash");tap("confirm-empty-book-trash")
                ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.books?.size==1&&!vm.busy.value}
                assertTrue(trash.all{repo.dao.storedBook(it)==null})
                val snapshot=repo.snapshot(repo.dao.book(keep)!!).first()
                assertEquals(event,snapshot.events.single().id);assertEquals(9500L,snapshot.balance(snapshot.accounts.single()))
                ui.onAllNodesWithTag("empty-book-trash").assertCountEquals(0)
                shot("$theme-$language-empty-result")
                assertNull(vm.error.value)
            }
        }finally{vm.viewModelScope.cancel();scope.cancel();db.close();path.delete()}
    }
    private fun repoBookRestored(vm:AppViewModel,id:String)=(vm.state.value as? AppState.Ready)?.books?.singleOrNull()?.let{it.id==id&&it.deletedAt==null&&it.archived}==true
}
