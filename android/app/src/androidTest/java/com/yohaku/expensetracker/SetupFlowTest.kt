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
import org.json.JSONArray

@RunWith(AndroidJUnit4::class)
class SetupFlowTest {
    @get:Rule val ui=createComposeRule()
    private lateinit var db:YohakuDatabase
    private lateinit var repo:LedgerRepository
    private lateinit var prefs:AppPreferences
    private lateinit var vm:AppViewModel
    @Before fun setup(){
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        repo=LedgerRepository(db);prefs=AppPreferences(context)
        runBlocking{prefs.clearSetup()};vm=AppViewModel(repo,prefs)
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
    private fun capturePageAnchors(theme:String,language:String){
        val i=InstrumentationRegistry.getInstrumentation()
        val suffix=InstrumentationRegistry.getArguments().getString("captureSuffix")?.let{"-$it"}.orEmpty()
        val folder=File(i.targetContext.filesDir,"verification/setup$suffix").also{it.mkdirs()}
        val file=File(folder,"page-anchors.csv")
        if(theme=="magazine"&&language=="zh")file.writeText("theme,language,tag,top,width,height\n")
        val origin=ui.onNodeWithTag("page-edition").getUnclippedBoundsInRoot().top
        val tags=listOf("setup-progress","lead-cover","setup-book-form","field-group-"+(if(language=="zh")"账本名称" else "帳簿名"),"select-field-"+(if(language=="zh")"统计本位币" else "基準通貨"),"field-group-"+(if(language=="zh")"月预算 / 可稍后设置" else "月予算・後から設定可"),"date-field-0","setup-note","setup-next-button","setup-back-link")
        val standard=i.targetContext.resources.configuration.let{it.fontScale==1f&&it.screenWidthDp==390}
        val formTop=when(theme){"journal"->325.8f;"objects"->303.28f;else->if(language=="ja")306.8f else 329.59f}
        val expectedTops=listOf(when(theme){"journal"->46f;"objects"->29f;else->40f},null,formTop,formTop,formTop+when(theme){"journal"->83f;"objects"->88f;else->85f},formTop+when(theme){"journal"->171f;"objects"->175f;else->170f},formTop+when(theme){"journal"->254f;"objects"->263f;else->255f},null,when(theme){"journal"->if(language=="ja")781.39f else 758.59f;"objects"->746.28f;else->if(language=="ja")736.8f else 759.59f},when(theme){"journal"->if(language=="ja")850.39f else 827.59f;"objects"->817.28f;else->if(language=="ja")803.8f else 826.59f})
        tags.forEachIndexed{index,tag->
            val r=ui.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            if(standard)expectedTops[index]?.let{assertEquals("HTML page anchor $theme/$language/$tag",it,(r.top-origin).value,1f)}
            file.appendText("$theme,$language,$tag,${(r.top-origin).value},${(r.right-r.left).value},${(r.bottom-r.top).value}\n")
        }
    }
    private fun captureLaterStepAnchors(theme:String,language:String,step:Int){
        val i=InstrumentationRegistry.getInstrumentation()
        val suffix=InstrumentationRegistry.getArguments().getString("captureSuffix")?.let{"-$it"}.orEmpty()
        val folder=File(i.targetContext.filesDir,"verification/setup$suffix").also{it.mkdirs()}
        val file=File(folder,"later-step-anchors.csv")
        if(theme=="magazine"&&language=="zh"&&step==2)file.writeText("theme,language,step,tag,top,width,height\n")
        val origin=ui.onNodeWithTag("page-edition").getUnclippedBoundsInRoot().top
        val tags=if(step==4)listOf("setup-review","setup-finish-button","setup-back-link") else listOf("setup-account-list","setup-add-account","setup-note","setup-next-button","setup-back-link")
        val config=i.targetContext.resources.configuration
        val reference=if(config.fontScale==1f&&config.screenWidthDp==390){
            val rows=JSONArray(i.context.assets.open("setup-later-reference.json").bufferedReader().use{it.readText()})
            (0 until rows.length()).map{rows.getJSONObject(it)}.single{it.getString("theme")==theme&&it.getString("language")==language&&it.getInt("step")==step}.getJSONArray("nodes")
        }else null
        tags.forEach{tag->
            val r=ui.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            reference?.let{nodes->
                val expected=(0 until nodes.length()).map{nodes.getJSONObject(it)}.single{it.getString("tag")==tag}
                assertEquals("HTML later anchor $theme/$language/$step/$tag",expected.getDouble("top").toFloat(),(r.top-origin).value,1f)
                assertEquals("HTML later height $theme/$language/$step/$tag",expected.getDouble("height").toFloat(),(r.bottom-r.top).value,1f)
            }
            file.appendText("$theme,$language,$step,$tag,${(r.top-origin).value},${(r.right-r.left).value},${(r.bottom-r.top).value}\n")
        }
    }
    @Test fun allThemesAndLanguagesShowFourSetupStepsAndFinishWithoutRecords(){
        var mounted=false
        val measurements=mutableListOf<String>()
        for(theme in listOf("magazine","journal","objects"))for(language in listOf("zh","ja")){
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
            waitStep(id,1);verifyProgress(theme,language,1,measurements);capturePageAnchors(theme,language);capture("$theme-$language-1")
            tap(if(language=="zh")"下一步：资金账户" else "次へ：資金口座")
            waitStep(id,2);verifyProgress(theme,language,2,measurements);captureLaterStepAnchors(theme,language,2)
            ui.onNodeWithTag("setup-account-$cash").performScrollTo().assertIsDisplayed()
            capture("$theme-$language-2")
            tap(if(language=="zh")"保存这一页，继续" else "このページを保存して次へ")
            waitStep(id,3);verifyProgress(theme,language,3,measurements);captureLaterStepAnchors(theme,language,3);capture("$theme-$language-3")
            tap(if(language=="zh")"保存这一页，继续" else "このページを保存して次へ")
            waitStep(id,4);verifyProgress(theme,language,4,measurements);captureLaterStepAnchors(theme,language,4);capture("$theme-$language-4-top")
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
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"verification/setup$suffix/progress-dimensions.csv").writeText(measurements.joinToString("\n"))
    }
    @Test fun welcomeMatchesSourceAndStartsWithoutCreatingRecords(){
        var mounted=false
        val i=InstrumentationRegistry.getInstrumentation()
        val references=JSONArray(i.context.assets.open("welcome-reference.json").bufferedReader().use{it.readText()})
        val suffix=InstrumentationRegistry.getArguments().getString("captureSuffix")?.let{"-$it"}.orEmpty()
        val folder=File(i.targetContext.filesDir,"verification/setup$suffix").also{it.mkdirs()}
        val file=File(folder,"welcome-anchors.csv").also{it.writeText("theme,language,tag,top,width,height\n")}
        for(theme in listOf("magazine","journal","objects"))for(language in listOf("zh","ja")){
            val id=book(theme,language);runBlocking{repo.setupStep(id,0);prefs.select(id)}
            if(!mounted){ui.setContent{NativeApp(vm)};mounted=true}
            waitStep(id,0)
            val origin=ui.onNodeWithTag("page-edition").getUnclippedBoundsInRoot().top
            val nodes=(0 until references.length()).map{references.getJSONObject(it)}.single{it.getString("theme")==theme&&it.getString("language")==language}.getJSONArray("nodes")
            for(index in 0 until nodes.length()){
                val expected=nodes.getJSONObject(index);val tag=expected.getString("tag")
                val r=ui.onNodeWithTag(tag).getUnclippedBoundsInRoot()
                file.appendText("$theme,$language,$tag,${(r.top-origin).value},${(r.right-r.left).value},${(r.bottom-r.top).value}\n")
                if(i.targetContext.resources.configuration.let{it.fontScale==1f&&it.screenWidthDp==390}){
                    assertEquals("HTML welcome anchor $theme/$language/$tag",expected.getDouble("top").toFloat(),(r.top-origin).value,1f)
                    assertEquals("HTML welcome height $theme/$language/$tag",expected.getDouble("height").toFloat(),(r.bottom-r.top).value,1f)
                }
            }
            if(theme=="objects"){
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
        for(theme in listOf("magazine","journal","objects"))for(language in listOf("zh","ja")){
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
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"verification/setup$suffix/keyboard-dimensions.csv").writeText(measurements.joinToString("\n")+"\n")
    }
    @Test fun emptyFundingPageMatchesSourceAndSkipDoesNotCreateAccounts(){
        var mounted=false
        val i=InstrumentationRegistry.getInstrumentation()
        val suffix=InstrumentationRegistry.getArguments().getString("captureSuffix")?.let{"-$it"}.orEmpty()
        val folder=File(i.targetContext.filesDir,"verification/setup$suffix").also{it.mkdirs()}
        val data=File(folder,"empty-funding-anchors.csv").also{it.writeText("theme,language,tag,top,height\n")}
        for(theme in listOf("magazine","journal","objects"))for(language in listOf("zh","ja")){
            val id=book(theme,language);runBlocking{repo.setupStep(id,2);prefs.select(id)}
            if(!mounted){ui.setContent{NativeApp(vm)};mounted=true};waitStep(id,2)
            val origin=ui.onNodeWithTag("page-edition").getUnclippedBoundsInRoot().top
            val top=when(theme){"journal"->325.8f;"objects"->303.28f;else->if(language=="ja")351.59f else 329.59f}
            val emptyHeight=when(theme){"journal"->263.375f;"objects"->240.8f;else->321.78f}
            val buttonHeight=when(theme){"journal"->45f;"objects"->43f;else->41f}
            val noteHeight=if(theme=="journal")56.8f else if(theme=="objects")53f else 55f
            val nextHeight=if(theme=="journal")50f else if(theme=="objects")52f else 48f
            val tags=listOf("setup-account-list","theme-empty","setup-add-account","setup-note","setup-next-button","setup-back-link")
            val tops=listOf(top,top,top+emptyHeight+12,top+emptyHeight+12+buttonHeight+20,top+emptyHeight+12+buttonHeight+20+noteHeight+35,top+emptyHeight+12+buttonHeight+20+noteHeight+35+nextHeight+19)
            for((index,tag) in tags.withIndex()){
                val r=ui.onNodeWithTag(tag).getUnclippedBoundsInRoot()
                data.appendText("$theme,$language,$tag,${(r.top-origin).value},${(r.bottom-r.top).value}\n")
                if(i.targetContext.resources.configuration.let{it.fontScale==1f&&it.screenWidthDp==390}&&!(theme=="journal"&&index in listOf(3))){
                    val rotationShift=if(theme=="journal"&&index==2).899f else 0f
                    assertEquals("HTML empty page $theme/$language/$tag",tops[index]+rotationShift,(r.top-origin).value,1f)
                    if(index<=1)assertEquals("HTML empty height $theme/$language",emptyHeight,(r.bottom-r.top).value,1f)
                }
            }
            capture("empty-funding-$theme-$language")
            ui.onNodeWithTag("setup-add-account").performScrollTo().assertIsDisplayed()
            ui.onNodeWithTag("setup-next-button").performScrollTo().performClick();waitStep(id,3)
            assertTrue(snapshot(id).accounts.isEmpty());assertTrue(snapshot(id).events.isEmpty())
        }
    }
    @Test fun newFundingAccountFormMatchesSourceAndCancelLeavesItEmpty(){
        var mounted=false
        val i=InstrumentationRegistry.getInstrumentation()
        val suffix=InstrumentationRegistry.getArguments().getString("captureSuffix")?.let{"-$it"}.orEmpty()
        val folder=File(i.targetContext.filesDir,"verification/setup$suffix").also{it.mkdirs()}
        val file=File(folder,"account-form-anchors.csv").also{it.writeText("theme,language,tag,top,width,height\n")}
        for(theme in listOf("magazine","journal","objects"))for(language in listOf("zh","ja")){
            val id=book(theme,language);runBlocking{repo.setupStep(id,2);prefs.select(id)}
            if(!mounted){ui.setContent{NativeApp(vm)};mounted=true};waitStep(id,2)
            ui.onNodeWithTag("setup-add-account").performScrollTo().performClick()
            val origin=ui.onNodeWithTag("account-form").getUnclippedBoundsInRoot().top
            val tags=listOf("account-setup-label","select-field-"+(if(language=="zh")"账户类型" else "種類"),"field-group-"+(if(language=="zh")"账户名称" else "口座名"),"select-field-"+(if(language=="zh")"账户币种" else "通貨"),"field-group-"+(if(language=="zh")"期初余额" else "期首残高"),"date-field-0","account-save-button")
            val tops=when(theme){"journal"->listOf(0f,76.8f,164.8f,247.8f,335.8f,418.8f,516.8f);"objects"->listOf(0f,73f,160f,248f,335f,423f,526f);else->listOf(0f,75f,160f,245f,330f,415f,511f)}
            tags.forEachIndexed{index,tag->
                val r=ui.onNodeWithTag(tag).getUnclippedBoundsInRoot()
                file.appendText("$theme,$language,$tag,${(r.top-origin).value},${(r.right-r.left).value},${(r.bottom-r.top).value}\n")
                if(i.targetContext.resources.configuration.let{it.fontScale==1f&&it.screenWidthDp==390})assertEquals("HTML account form $theme/$language/$tag",tops[index],(r.top-origin).value,1f)
            }
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
        for(theme in listOf("magazine","journal","objects"))for(language in listOf("zh","ja")){
            val id=book(theme,language)
            val account=runBlocking{repo.saveAccount(id,"外币账户",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01").also{repo.setupStep(id,4);prefs.select(id)}}
            if(!mounted){ui.setContent{NativeApp(vm)};mounted=true}
            waitStep(id,4)
            val currencyLabel=if(language=="zh")"账户币种" else "通貨"
            val amountLabel=if(language=="zh")"期初余额" else "期首残高"
            fun changeCurrency(){
                ui.onNodeWithTag("setup-account-$account").performScrollTo().performClick()
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
