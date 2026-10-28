package com.yohaku.expensetracker

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yohaku.expensetracker.data.db.YohakuDatabase
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.features.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancel
import androidx.lifecycle.viewModelScope
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SetupFlowTest {
    @get:Rule val ui=createComposeRule()
    @get:Rule(order=1) val failureArtifacts=RegressionFailureArtifacts(ui)
    private lateinit var db:YohakuDatabase
    private lateinit var repo:LedgerRepository
    private lateinit var prefs:AppPreferences
    private lateinit var vm:AppViewModel
    @Before fun setup(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        repo=LedgerRepository(db);prefs=AppPreferences(context)
        runBlocking{prefs.clearSetup()};vm=AppViewModel(repo,prefs, testReferenceRates(prefs,repo.clock))
    }
    @After fun close(){vm.viewModelScope.cancel();db.close()}
    private fun book(theme:String,language:String)=runBlocking{
        val id=repo.createBook("初期の生活",Currency.JPY,theme,language,"2026-10-01",50000)
        repo.setupStep(id,1);id
    }
    private fun snapshot(id:String)=runBlocking{repo.snapshot(repo.dao.book(id)!!).first()}
    private fun tap(text:String){ui.waitUntil(10000){ui.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()};ui.onNodeWithText(text).performScrollTo().performClick()}
    private fun waitStep(id:String,step:Int){ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.let{it.id==id&&it.setupStep==step}==true}}
    private fun capture(name:String){
        if (!visualEnabled()) return
        ui.waitForIdle();Thread.sleep(350)
        val i=InstrumentationRegistry.getInstrumentation()
        val suffix=InstrumentationRegistry.getArguments().getString("captureSuffix")?.let{"-$it"}.orEmpty()
        val folder=File(i.targetContext.filesDir,"verification/setup$suffix").also{it.mkdirs()}
        val image=i.uiAutomation.takeScreenshot()
        File(folder,"$name.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
    }
    private fun verifyProgress(theme:String,language:String,step:Int,measurements:MutableList<String>){
        ui.waitForIdle()
        for(i in 1..4){
            val node=ui.onNodeWithTag("setup-progress-step-$i")
            if(i==step)node.assertIsSelected() else node.assertIsNotSelected()
        }
        if (!visualEnabled()) return
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val r=ui.onNodeWithTag("setup-progress").fetchSemanticsNode().boundsInRoot
        val density=context.resources.displayMetrics.density
        val height=r.height/density
        if(context.resources.configuration.fontScale==1f){
            val narrow=context.resources.configuration.screenWidthDp<=360
            val expected=when(theme){"magazine"->44f;"journal"->if(narrow)66f else 69f;else->67f}
            assertEquals(expected,height,1f)
        }
        measurements+="$theme,$language,$step,${r.width/density},$height"
    }
    @Test fun allThemesAndLanguagesShowFourSetupStepsAndFinishWithoutRecords(){
        var mounted=false
        val measurements=mutableListOf<String>()
        for((theme,language) in testLooks(this@SetupFlowTest)){
            val id=book(theme,language)
            val cash=runBlocking{
                val cash=repo.saveAccount(id,"お財布",AccountType.CASH,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
                repo.saveAccount(id,"外貨口座",AccountType.BANK,Currency.CNY,Money(Currency.CNY,20000),"2026-10-01")
                repo.saveAccount(id,"カード",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,3000),"2026-10-01")
                repo.saveAccount(id,"借入",AccountType.LOAN,Currency.USD,Money(Currency.USD,40000),"2026-10-01")
                cash
            }
            runBlocking{prefs.select(id)}
            if(!mounted){ui.setContent{NativeApp(vm)};mounted=true}
            waitStep(id,1);verifyProgress(theme,language,1,measurements);capture("$theme-$language-1")
            tap(if(language=="zh")"下一步：资金账户" else "次へ：資金口座")
            waitStep(id,2);verifyProgress(theme,language,2,measurements);
            ui.onNodeWithTag("setup-account-$cash").performScrollTo().assertIsDisplayed()
            capture("$theme-$language-2")
            tap(if(language=="zh")"保存这一页，继续" else "このページを保存して次へ")
            waitStep(id,3);verifyProgress(theme,language,3,measurements);capture("$theme-$language-3")
            tap(if(language=="zh")"保存这一页，继续" else "このページを保存して次へ")
            waitStep(id,4);verifyProgress(theme,language,4,measurements);capture("$theme-$language-4-top")
            ui.onNodeWithTag("setup-review").performScrollTo().assertExists()
            val config=InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration
            if(config.fontScale==1f&&config.screenWidthDp==390){
                val review=ui.onNodeWithTag("setup-review").getUnclippedBoundsInRoot()
                assertEquals("Four-account HTML review $theme/$language",when(theme){"journal"->762f;"objects"->692f;else->718f},(review.bottom-review.top).value,1f)
            }
            capture("$theme-$language-4")
            tap(if(language=="zh")"完成，进入我的空白账本" else "完了・空白のマイ帳簿へ")
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.let{it.id==id&&it.initialized}==true}
            val saved=snapshot(id)
            assertEquals(4,saved.accounts.size);assertTrue(saved.events.isEmpty());assertTrue(saved.categories.isEmpty());assertTrue(saved.postings.isEmpty())
            assertEquals(10000L,saved.balance(saved.accounts.first{it.id==cash}))
            assertEquals(theme,saved.book.theme);assertEquals(language,saved.book.language)
        }
        val suffix=InstrumentationRegistry.getArguments().getString("captureSuffix")?.let{"-$it"}.orEmpty()
        if (visualEnabled()) File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"verification/setup$suffix/progress-dimensions.csv").writeText(measurements.joinToString("\n"))
    }
    @Test fun welcomeMatchesSourceAndStartsWithoutCreatingRecords(){
        var mounted=false
        for((theme,language) in testLooks(this@SetupFlowTest)){
            val id=book(theme,language);runBlocking{repo.setupStep(id,0);prefs.select(id)}
            if(!mounted){ui.setContent{NativeApp(vm)};mounted=true}
            waitStep(id,0)
            if(visualEnabled() && theme=="objects"){
                val greeting=ui.onNodeWithTag("lead-amount").captureToImage().asAndroidBitmap()
                var sourceInkFound=false
                for(y in 0 until greeting.height)for(x in 0 until greeting.width){if(greeting.getPixel(x,y)==0xffae6246.toInt())sourceInkFound=true}
                assertTrue("Original objects greeting uses terracotta accent #ae6246",sourceInkFound)
            }
            capture("welcome-$theme-$language")
            ui.onNodeWithTag("setup-start-button").performScrollTo().assertIsDisplayed().performClick();waitStep(id,1)
            ui.onNodeWithTag(if(language=="zh")"field-账本名称" else "field-帳簿名").assertTextEquals("初期の生活")
            val saved=snapshot(id);assertTrue(saved.accounts.isEmpty());assertTrue(saved.categories.isEmpty());assertTrue(saved.events.isEmpty())
        }
    }
    @Test fun realKeyboardKeepsSetupSaveReachableInEveryThemeAndLanguage(){
        Assume.assumeTrue("Requires an actual visible IME",InstrumentationRegistry.getArguments().getString("realKeyboard")=="true")
        var mounted=false
        val measurements=mutableListOf("theme,language,viewportBefore,viewportWithIme,saveBottom")
        for((theme,language) in testLooks(this@SetupFlowTest)){
            val id=book(theme,language)
            runBlocking{prefs.select(id)}
            if(!mounted){ui.setContent{NativeApp(vm)};mounted=true}
            waitStep(id,1)
            val viewport=ui.onNodeWithTag("setup-viewport")
            val before=viewport.getUnclippedBoundsInRoot()
            val name=ui.onNodeWithTag(if(language=="zh")"field-账本名称" else "field-帳簿名")
            name.performScrollTo().performClick()
            ui.waitUntil(10000){viewport.getUnclippedBoundsInRoot().bottom.value<before.bottom.value-100}
            val entered="键盘入力 $theme-$language"
            name.performTextReplacement(entered)
            val save=ui.onNodeWithTag("setup-next-button").performScrollTo().assertIsDisplayed()
            val visible=viewport.getUnclippedBoundsInRoot();val button=save.getUnclippedBoundsInRoot()
            assertTrue("Next must stay above the real IME",button.bottom.value<=visible.bottom.value+1)
            assertTrue("Next must stay below the header",button.top.value>=visible.top.value)
            measurements+="$theme,$language,${before.bottom.value},${visible.bottom.value},${button.bottom.value}"
            capture("keyboard-$theme-$language")
            save.performClick();waitStep(id,2)
            assertEquals(entered,snapshot(id).book.name)
            assertTrue(snapshot(id).events.isEmpty())
        }
        val suffix=InstrumentationRegistry.getArguments().getString("captureSuffix")?.let{"-$it"}.orEmpty()
        if (visualEnabled()) File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"verification/setup$suffix/keyboard-dimensions.csv").writeText(measurements.joinToString("\n")+"\n")
    }
    @Test fun emptyFundingPageMatchesSourceAndSkipDoesNotCreateAccounts(){
        var mounted=false
        for((theme,language) in testLooks(this@SetupFlowTest)){
            val id=book(theme,language);runBlocking{repo.setupStep(id,2);prefs.select(id)}
            if(!mounted){ui.setContent{NativeApp(vm)};mounted=true};waitStep(id,2)
            capture("empty-funding-$theme-$language")
            ui.onNodeWithTag("setup-add-account").performScrollTo().assertIsDisplayed()
            ui.onNodeWithTag("setup-next-button").performScrollTo().performClick();waitStep(id,3)
            assertTrue(snapshot(id).accounts.isEmpty());assertTrue(snapshot(id).events.isEmpty())
        }
    }
    @Test fun newFundingAccountFormMatchesSourceAndCancelLeavesItEmpty(){
        var mounted=false
        for((theme,language) in testLooks(this@SetupFlowTest)){
            val id=book(theme,language);runBlocking{repo.setupStep(id,2);prefs.select(id)}
            if(!mounted){ui.setContent{NativeApp(vm)};mounted=true};waitStep(id,2)
            ui.onNodeWithTag("setup-add-account").performScrollTo().performClick()
            capture("new-account-$theme-$language")
            ui.onNodeWithText("×").performClick();ui.waitForIdle()
            assertTrue(snapshot(id).accounts.isEmpty());assertTrue(snapshot(id).events.isEmpty())
            ui.onNodeWithTag("setup-next-button").performScrollTo().performClick();waitStep(id,3)
            ui.onNodeWithTag("setup-next-button").performScrollTo().performClick();waitStep(id,4)
            assertTrue(snapshot(id).accounts.isEmpty());assertTrue(snapshot(id).categories.isEmpty())
        }
    }
    @Test fun unusedAccountCurrencyEditorCancelsAndSavesOriginalIdInBothLanguages(){
        var mounted=false
        for((theme,language) in testLooks(this@SetupFlowTest)){
            val id=book(theme,language)
            val account=runBlocking{repo.saveAccount(id,"外币账户",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01").also{repo.setupStep(id,4);prefs.select(id)}}
            if(!mounted){ui.setContent{NativeApp(vm)};mounted=true}
            waitStep(id,4)
            ui.waitForIdle()
            val currencyLabel=if(language=="zh")"账户币种" else "通貨"
            val amountLabel=if(language=="zh")"期初余额" else "期首残高"
            fun changeCurrency(){
                val card=ui.onNodeWithTag("setup-account-$account")
                card.performScrollTo();ui.waitForIdle()
                InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(100,5000)
                val bounds=card.getUnclippedBoundsInRoot()
                val viewport=ui.onNodeWithTag("setup-viewport").getUnclippedBoundsInRoot()
                assertTrue("Account card must be inside the setup viewport before tapping",bounds.top>=viewport.top&&bounds.bottom<=viewport.bottom)
                card.performClick()
                ui.waitUntil(10000){ui.onAllNodesWithTag("select-trigger-$currencyLabel").fetchSemanticsNodes().isNotEmpty()&&!vm.busy.value}
                ui.onNodeWithTag("select-trigger-$currencyLabel").performScrollTo().assertTextContains("JPY",substring=true).performClick()
                ui.onNodeWithTag("select-option-$currencyLabel-1").performScrollTo().performClick()
                ui.onNodeWithTag("field-$amountLabel").performScrollTo().performTextReplacement("12.34")
            }
            changeCurrency()
            ui.onNodeWithText("×").performClick()
            ui.waitForIdle()
            assertEquals("JPY",snapshot(id).accounts.single().currency)
            assertEquals(10000L,snapshot(id).accounts.single().openingMinor)
            changeCurrency();capture("currency-$theme-$language")
            tap(if(language=="zh")"保存账户信息" else "口座情報を保存")
            ui.waitUntil(10000){snapshot(id).accounts.single().currency=="CNY"}
            val saved=snapshot(id);assertEquals(account,saved.accounts.single().id);assertEquals(1234L,saved.balance(saved.accounts.single()))
            assertTrue(saved.events.isEmpty());assertTrue(saved.postings.isEmpty())
        }
    }
    @Test fun setupCreatesAllFiveAccountTypesAndEditsFromReviewWithoutIncome(){
        val id=book("journal","ja");runBlocking{prefs.select(id)}
        ui.setContent{NativeApp(vm)};waitStep(id,1)
        tap("次へ：資金口座");waitStep(id,2)
        for((index,type) in listOf(AccountType.CASH,AccountType.BANK,AccountType.IC).withIndex()){
            tap("＋ 資金口座を追加")
            ui.onNodeWithTag("select-trigger-種類").performScrollTo().performClick()
            ui.onNodeWithTag("select-option-種類-$index").performScrollTo().performClick()
            ui.onNodeWithTag("field-口座名").performTextReplacement(type.name)
            ui.onNodeWithTag("field-期首残高").performScrollTo().performTextReplacement("1000")
            tap("口座情報を保存")
            ui.waitUntil(10000){snapshot(id).accounts.size==index+1}
        }
        tap("このページを保存して次へ");waitStep(id,3)
        for((index,type) in listOf(AccountType.CREDIT,AccountType.LOAN).withIndex()){
            tap("＋ カード・借入を追加")
            ui.onNodeWithTag("select-trigger-種類").performScrollTo().performClick()
            ui.onNodeWithTag("select-option-種類-$index").performScrollTo().performClick()
            ui.onNodeWithTag("field-口座名").performTextReplacement(type.name)
            ui.onNodeWithTag("field-期首借入残高").performScrollTo().performTextReplacement("2000")
            tap("口座情報を保存")
            ui.waitUntil(10000){snapshot(id).accounts.size==index+4}
        }
        tap("このページを保存して次へ");waitStep(id,4)
        val cash=snapshot(id).accounts.first{it.type==AccountType.CASH.name}
        ui.onNodeWithTag("setup-account-${cash.id}").performScrollTo().performClick()
        ui.onNodeWithTag("field-期首残高").performScrollTo().performTextReplacement("1500")
        tap("口座情報を保存")
        ui.waitUntil(10000){snapshot(id).accounts.first{it.id==cash.id}.openingMinor==1500L}
        tap("完了・空白のマイ帳簿へ")
        ui.waitUntil(10000){snapshot(id).book.initialized}
        val saved=snapshot(id)
        assertEquals(AccountType.entries.toSet(),saved.accounts.map{AccountType.valueOf(it.type)}.toSet())
        assertEquals(1500L,saved.balance(saved.accounts.first{it.id==cash.id}))
        assertTrue(saved.events.isEmpty());assertTrue(saved.postings.isEmpty());assertTrue(saved.categories.isEmpty())
    }
}
