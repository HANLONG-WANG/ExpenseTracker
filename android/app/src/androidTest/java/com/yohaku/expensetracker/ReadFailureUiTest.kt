package com.yohaku.expensetracker

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.data.db.YohakuDatabase
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.ui.features.*
import com.yohaku.expensetracker.domain.model.*
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.util.UUID
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadFailureUiTest {
    @get:Rule val ui=createComposeRule()
    private lateinit var db:YohakuDatabase
    private lateinit var vm:AppViewModel
    private var preferencesScope:CoroutineScope?=null
    private var preferencesFile:File?=null
    @After fun close(){vm.viewModelScope.cancel();db.close();runBlocking{preferencesScope?.coroutineContext?.get(Job)?.cancelAndJoin()};preferencesFile?.delete()}
    @Test fun databaseReadFailureShowsErrorInsteadOfEndlessStartup(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        // Fault is confined to this disposable database, before any UI subscription.
        db.openHelper.writableDatabase.execSQL("DROP TABLE books")
        val prefs=AppPreferences(context);runBlocking{prefs.clearSetup()}
        vm=AppViewModel(LedgerRepository(db),prefs, testReferenceRates(prefs))
        ui.setContent{NativeApp(vm)}
        ui.waitUntil(10000){vm.state.value is AppState.Failure}
        ui.onNodeWithText("无法读取账本").assertIsDisplayed()
        ui.onNodeWithTag("startup-brand").assertDoesNotExist()
    }
    @Test fun retryReadsExistingRecordsAfterTemporaryFailureWithoutReset(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        val repo=LedgerRepository(db);val prefs=AppPreferences(context)
        val bookId=runBlocking{
            prefs.clearSetup()
            val id=repo.createBook("原有账本",Currency.JPY)
            repo.updateBook(repo.dao.book(id)!!.copy(initialized=true))
            val account=repo.saveAccount(id,"现金",AccountType.CASH,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
            repo.saveCategory(id,"餐饮",EntryKind.EXPENSE,null)
            repo.saveEntry(id,EntryDraft(accountId=account,categoryId=repo.dao.categories(id).first().single().id,amount="300",merchant="原有记录"))
            prefs.select(id);id
        }
        val before=runBlocking{repo.snapshot(repo.dao.book(bookId)!!).first()}
        // Rename preserves rows and relationships; only this disposable DB is affected.
        db.openHelper.writableDatabase.execSQL("ALTER TABLE books RENAME TO temporarily_unavailable_books")
        vm=AppViewModel(repo,prefs, testReferenceRates(prefs,repo.clock));ui.setContent{NativeApp(vm)}
        ui.waitUntil(10000){vm.state.value is AppState.Failure}
        ui.onNodeWithTag("read-error-details").assertDoesNotExist()
        ui.onNodeWithText("展开错误详情").performScrollTo().performClick()
        ui.onNodeWithTag("read-error-details").performScrollTo().assertIsDisplayed()
        val firstFailure=vm.state.value
        ui.onNodeWithTag("retry-read").performScrollTo().performClick()
        ui.waitUntil(10000){vm.state.value is AppState.Failure&&vm.state.value!==firstFailure}
        db.openHelper.writableDatabase.execSQL("ALTER TABLE temporarily_unavailable_books RENAME TO books")
        ui.onNodeWithTag("retry-read").performScrollTo().performClick()
        ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==bookId}
        ui.onNodeWithTag("read-failure").assertDoesNotExist()
        ui.onNodeWithText("原有记录").assertExists()
        val after=runBlocking{repo.snapshot(repo.dao.book(bookId)!!).first()}
        assertEquals(before.book,after.book)
        assertEquals(before.events,after.events)
        assertEquals(before.accounts,after.accounts)
        assertEquals(9700L,after.balance(after.accounts.single()))
        assertEquals(1,runBlocking{repo.dao.revisions(bookId,after.events.single().id).first()}.size)
    }
    @Test fun invalidSetupDataShowsFailureAndRestoresKnownLanguageAfterRetry(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO);preferencesScope=scope
        val file=File(context.cacheDir,"read-retry-${UUID.randomUUID()}.preferences_pb");preferencesFile=file
        val storage=PreferenceDataStoreFactory.create(scope=scope,produceFile={file})
        val prefs=AppPreferences(context,storage)
        val saved=SetupDraft(step=1,name="途中の帳簿",budget="68000",theme="journal",language="ja")
        runBlocking{prefs.saveSetup(saved)}
        vm=AppViewModel(LedgerRepository(db),prefs, testReferenceRates(prefs));ui.setContent{NativeApp(vm)}
        ui.waitUntil(10000){vm.setupDraft.value==saved}
        ui.onNodeWithTag("field-帳簿名").performScrollTo().assertTextEquals("途中の帳簿")
        runBlocking{storage.edit{it[stringPreferencesKey("first_setup")]="{invalid json"}}
        ui.waitUntil(10000){vm.state.value is AppState.Failure}
        ui.onNodeWithText("帳簿を読み込めません").assertIsDisplayed()
        assertEquals(saved,vm.setupDraft.value)
        assertEquals("{invalid json",runBlocking{storage.data.first()[stringPreferencesKey("first_setup")]})
        runBlocking{prefs.saveSetup(saved)}
        ui.onNodeWithTag("retry-read").performScrollTo().performClick()
        ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.firstSetup==saved}
        ui.onNodeWithTag("field-帳簿名").performScrollTo().assertTextEquals("途中の帳簿")
        ui.onNodeWithTag("field-月予算・後から設定可").performScrollTo().assertTextEquals("68000")
        assertTrue(runBlocking{vm.repository.books.first()}.isEmpty())
    }
}
