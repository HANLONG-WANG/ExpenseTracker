package com.yohaku.expensetracker

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.features.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.io.File

class EntryExperienceFlowTest {
    @get:Rule val ui=createComposeRule()
    @get:Rule(order=1) val failureArtifacts=RegressionFailureArtifacts(ui)
    private lateinit var db:YohakuDatabase
    private lateinit var repo:LedgerRepository
    private lateinit var vm:AppViewModel
    private lateinit var root:File
    @Before fun setup() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        root=File(context.cacheDir,"entry-experience-${java.util.UUID.randomUUID()}").apply{mkdirs()}
        db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build();repo=LedgerRepository(db,mediaRoot=root)
        val prefs=AppPreferences(context);vm=AppViewModel(repo,prefs,testReferenceRates(prefs,repo.clock));ui.setContent{NativeApp(vm)}
    }
    @After fun close(){vm.viewModelScope.cancel();db.close();root.deleteRecursively()}
    private fun snapshot()=(vm.state.value as AppState.Ready).snapshot!!
    private fun fresh(theme:String,language:String):BookSnapshot {
        val book=runBlocking {
            val b=repo.createBook("生活",Currency.JPY,theme,language);repo.updateBook(repo.dao.book(b)!!.copy(initialized=true))
            repo.saveAccount(b,"現金",AccountType.CASH,Currency.JPY,Money(Currency.JPY,100),"2026-01-01")
            repo.saveAccount(b,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,1000),"2026-01-01")
            repo.saveAccount(b,"カード",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,0),"2026-01-01")
            repo.saveCategory(b,"食事",EntryKind.EXPENSE,null);repo.saveCategory(b,"収入",EntryKind.INCOME,null)
            vm.preferences.select(b);b
        }
        ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book&&!vm.busy.value}
        ui.runOnIdle{vm.navigate("home")};return snapshot()
    }
    private fun shot(name:String) {
        if(!visualEnabled())return
        val i=InstrumentationRegistry.getInstrumentation();i.uiAutomation.waitForIdle(350,5000)
        val image=i.uiAutomation.takeScreenshot();val dir=File(i.targetContext.filesDir,"verification/entry-experience").apply{mkdirs()}
        File(dir,"$name.png").outputStream().use{image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};image.recycle()
    }
    @Test fun tabsPreserveMediaAndReturnRouteAndUseLastSavedAccounts() {
        for((theme,language) in testLooks(this@EntryExperienceFlowTest)) {
            var s=fresh(theme,language);val book=s.book.id;val bank=s.accounts.first{it.type=="BANK"}.id;val card=s.accounts.first{it.type=="CREDIT"}.id
            runBlocking {
                repo.saveEntry(book,EntryDraft(accountId=bank,categoryId=s.categories.first{it.kind=="EXPENSE"}.id,amount="10"))
                repo.commitEntryReview(book,repo.previewEntry(book,EntryDraft(kind=EntryKind.TRANSFER,accountId=card,toAccountId=bank,amount="20",toAmount="20",cardPaymentChoice="SINGLE")))
            }
            ui.waitUntil(10000){snapshot().events.size==2};s=snapshot()
            ui.runOnIdle{vm.navigate("accounts");vm.startEntry(s);vm.changeEntry(book,vm.entry.value!!.copy(date="2026-10-01",note="旅行"))}
            ui.waitUntil(10000){!vm.busy.value}
            val session=vm.entry.value!!.mediaSession
            val file=runBlocking{repo.importMedia(book,session,"note.txt","text/plain"){"receipt note".byteInputStream()}}
            ui.runOnIdle{vm.changeEntry(book,vm.entry.value!!.copy(attachmentIds=listOf(file.id)))}
            ui.onNodeWithTag("category-kind-TRANSFER").performScrollTo().performClick()
            ui.waitUntil(10000){vm.route.value=="entry"&&vm.entry.value?.kind==EntryKind.TRANSFER&&!vm.busy.value}
            val transfer=vm.entry.value!!;assertEquals(card,transfer.accountId);assertEquals(bank,transfer.toAccountId);assertEquals("SINGLE",transfer.cardPaymentChoice)
            assertEquals("2026-10-01",transfer.date);assertEquals("旅行",transfer.note);assertEquals(session,transfer.mediaSession);assertEquals(listOf(file.id),transfer.attachmentIds)
            assertEquals("accounts",vm.entryReturnRoute.value);assertFalse(vm.entryHasManualInput())
            shot("$theme-$language-transfer-tabs")
            ui.onNodeWithTag("category-kind-EXPENSE").performScrollTo().performClick()
            assertEquals(bank,vm.entry.value!!.accountId);assertEquals("category-picker",vm.route.value);assertEquals(listOf(file.id),vm.entry.value!!.attachmentIds)
            ui.onNodeWithTag("category-kind-INCOME").performClick();assertEquals(EntryKind.INCOME,vm.entry.value!!.kind)
            ui.onNodeWithText("×").performClick();ui.waitUntil(10000){vm.entry.value==null&&vm.route.value=="accounts"&&!vm.busy.value}
            assertNull(runBlocking{repo.dao.storedDraft(book)})
        }
    }
    @Test fun negativeBalanceConfirmationPrecedesSaveAndTransferReview() {
        for((theme,language) in testLooks(this@EntryExperienceFlowTest)) {
            val s=fresh(theme,language);val book=s.book.id;val cash=s.accounts.first{it.type=="CASH"}.id;val bank=s.accounts.first{it.type=="BANK"}.id
            ui.runOnIdle{vm.startEntry(s);vm.changeEntry(book,vm.entry.value!!.copy(accountId=cash,categoryId=s.categories.first{it.kind=="EXPENSE"}.id,amount="150"));vm.navigate("entry")}
            ui.onNodeWithTag("entry-save-button").performScrollTo().performClick()
            ui.waitUntil(10000){vm.negativeBalanceReview.value!=null&&!vm.busy.value};assertTrue(snapshot().events.isEmpty());shot("$theme-$language-negative-balance")
            ui.onNodeWithTag("negative-balance-edit").performScrollTo().performClick();assertEquals("150",vm.entry.value!!.amount)
            ui.onNodeWithTag("entry-save-button").performScrollTo().performClick();ui.waitUntil(10000){vm.negativeBalanceReview.value!=null&&!vm.busy.value}
            ui.onNodeWithTag("negative-balance-save").performScrollTo().performClick();ui.waitUntil(10000){vm.entry.value==null&&snapshot().events.size==1&&!vm.busy.value}
            assertEquals(-50L,snapshot().balance(s.accounts.first{it.id==cash}))
            ui.runOnIdle{vm.startTransfer(snapshot(),cash,bank);vm.changeEntry(book,vm.entry.value!!.copy(amount="10",toAmount="10",fromConfirmed=true,toConfirmed=true));vm.saveEntry(book)}
            ui.waitUntil(10000){vm.negativeBalanceReview.value!=null&&!vm.busy.value}
            ui.onNodeWithTag("negative-balance-save").performScrollTo().performClick();ui.waitUntil(10000){vm.entryReview.value!=null}
            assertEquals(1,snapshot().events.size)
            ui.runOnIdle{vm.confirmEntryReview(book)};ui.waitUntil(10000){vm.entry.value==null&&snapshot().events.size==2&&!vm.busy.value}
            assertEquals(-60L,snapshot().balance(s.accounts.first{it.id==cash}))
        }
    }
    @Test fun merchantSuggestionsSearchAndSelectionWorkAcrossThemes() {
        for((theme,language) in testLooks(this@EntryExperienceFlowTest)) {
            val s=fresh(theme,language);val book=s.book.id
            val merchant=runBlocking{repo.saveMerchant(book,"Ｃａｆｅ １２")};ui.waitUntil(10000){snapshot().merchants.size==1}
            ui.runOnIdle{vm.startEntry(snapshot());vm.changeEntry(book,vm.entry.value!!.copy(categoryId=s.categories.first{it.kind=="EXPENSE"}.id));vm.navigate("entry")}
            val field=if(language=="zh")"field-商户 / 买了什么"else"field-お店 / 買ったもの"
            ui.onNodeWithTag(field).performScrollTo().performTextReplacement("cAFE")
            ui.waitUntil(5000){runCatching{ui.onNodeWithTag("merchant-suggestion-$merchant").assertIsDisplayed()}.isSuccess};shot("$theme-$language-merchant-suggestions")
            ui.onNodeWithTag("merchant-suggestion-$merchant").performClick();assertEquals(merchant,vm.entry.value!!.merchantId)
            ui.onNodeWithTag(field).performScrollTo().performTextReplacement("Cafe 1");assertNull(vm.entry.value!!.merchantId)
            ui.onNodeWithTag(field).performTextReplacement("cafe 12");assertEquals(merchant,vm.entry.value!!.merchantId)
            ui.onNodeWithTag("entry-merchant-selected").performScrollTo().performClick();assertNull(vm.entry.value!!.merchantId)
            ui.onNodeWithTag("entry-merchant-list").performScrollTo().performClick()
            ui.onNodeWithTag(if(language=="zh")"field-搜索商户"else"field-お店を検索").performTextReplacement("cafe")
            ui.onNodeWithTag("merchant-choice-$merchant").assertExists();shot("$theme-$language-merchant-search")
            ui.onNodeWithTag("merchant-choice-$merchant").performClick();assertEquals(merchant,vm.entry.value!!.merchantId)
            ui.runOnIdle{vm.leaveEntry(book,false,"settings")};ui.waitUntil(10000){vm.entry.value==null&&!vm.busy.value}
            ui.onNodeWithTag("settings-merchants").performScrollTo().performClick();assertEquals("merchants",vm.route.value)
        }
    }
}
