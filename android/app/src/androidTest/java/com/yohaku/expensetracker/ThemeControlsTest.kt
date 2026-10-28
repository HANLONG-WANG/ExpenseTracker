package com.yohaku.expensetracker

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.features.EntrySummary
import com.yohaku.expensetracker.ui.features.CategoryStepFooter
import com.yohaku.expensetracker.ui.features.CategoryKindTabs
import com.yohaku.expensetracker.ui.features.CategoryShortcuts
import com.yohaku.expensetracker.domain.model.EntryKind
import com.yohaku.expensetracker.ui.theme.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ThemeControlsTest {
    @get:Rule val ui=createComposeRule()
    @Test fun yenQuickAmountsUseOriginalThemeSizesAndAddToInput(){
        val theme=mutableStateOf("magazine");val language=mutableStateOf("zh");val amount=mutableStateOf("")
        ui.setContent{YohakuTheme(theme.value,language.value){Column(Modifier.fillMaxSize().safeDrawingPadding().padding(25.dp)){
            com.yohaku.expensetracker.ui.features.EntryQuickAmount(amount.value){amount.value=it}
        }}}
        for((t,l) in testLooks(this@ThemeControlsTest)){
            ui.runOnIdle{theme.value=t;language.value=l;amount.value=""}
            val r=ui.onNodeWithTag("entry-quick-amount").getUnclippedBoundsInRoot()
            if(visualEnabled() && InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.fontScale==1f)assertEquals("Original quick amount height",when(t){"journal"->40.390625f;"objects"->48f;else->36f},(r.bottom-r.top).value,1f)
            for(n in listOf(100,500,1000))ui.onNodeWithTag("entry-quick-$n").performClick()
            assertEquals("1600",amount.value)
            ui.runOnIdle{amount.value="680"};ui.onNodeWithTag("entry-quick-100").performClick();assertEquals("780",amount.value)
            ui.runOnIdle{amount.value="1.5"};ui.onNodeWithTag("entry-quick-100").assertIsNotEnabled()
        }
    }
    @Test fun categoryShortcutsAndSearchMatchOriginalLineBoxes(){
        val theme=mutableStateOf("magazine");val language=mutableStateOf("zh");var chosen="";val query=mutableStateOf("")
        ui.setContent{YohakuTheme(theme.value,language.value){Column(Modifier.fillMaxSize().safeDrawingPadding().padding(25.dp),verticalArrangement=Arrangement.spacedBy(15.dp)){
            CategoryShortcuts(word("我的常用 · 一步选择","よく使うカテゴリ・直接選択"),listOf("coffee" to "★ 餐饮 / 咖啡")){chosen=it}
            YSearch(word("搜索分类","カテゴリを検索"),query.value,{query.value=it},category=true,displayPlaceholder=word("搜索一级或二级分类","親・子カテゴリを検索"))
        }}}
        for((t,l) in testLooks(this@ThemeControlsTest)){
            ui.runOnIdle{theme.value=t;language.value=l;query.value="";chosen=""}
            val shortcuts=ui.onNodeWithTag("category-shortcuts").getUnclippedBoundsInRoot()
            val search=ui.onNodeWithTag("category-search").getUnclippedBoundsInRoot()
            if(visualEnabled() && InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.fontScale==1f){
                assertEquals("Original shortcut block",if(t=="objects")67.6875f else 69.6875f,(shortcuts.bottom-shortcuts.top).value,1f)
                assertEquals("Original search height",if(t=="objects")41f else 42f,(search.bottom-search.top).value,1f)
            }
            if (visualEnabled()) assertEquals("Original collapsed shortcut margin",22f,(search.top-shortcuts.bottom).value,1f)
            ui.onNodeWithTag("category-shortcut-coffee").performClick();assertEquals("coffee",chosen)
            ui.onNodeWithTag(if(l=="zh")"field-搜索分类" else "field-カテゴリを検索").performTextReplacement("咖啡")
            assertEquals("咖啡",query.value)
        }
    }
    @Test fun categoryKindsUseOriginalIndependentTabsAndKeepSelectedState(){
        val theme=mutableStateOf("magazine");val language=mutableStateOf("zh");val kind=mutableStateOf(EntryKind.EXPENSE)
        ui.setContent{YohakuTheme(theme.value,language.value){Column(Modifier.fillMaxSize().safeDrawingPadding().padding(25.dp)){
            CategoryKindTabs(kind.value){kind.value=it}
        }}}
        for((t,l) in testLooks(this@ThemeControlsTest)){
            ui.runOnIdle{theme.value=t;language.value=l;kind.value=EntryKind.EXPENSE}
            val row=ui.onNodeWithTag("category-kind-tabs").getUnclippedBoundsInRoot()
            if(visualEnabled() && InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.fontScale==1f)assertEquals("Original tab height",when(t){"journal"->47f;"objects"->44f;else->36f},(row.bottom-row.top).value,1f)
            ui.onNodeWithTag("category-kind-EXPENSE").assertIsSelected()
            ui.onNodeWithTag("category-kind-INCOME").performClick().assertIsSelected()
            assertEquals(EntryKind.INCOME,kind.value)
            ui.onNodeWithTag("category-kind-EXPENSE").performClick().assertIsSelected()
        }
    }
    @Test fun categoryStepFooterUsesOriginalThemeHeightsAndGap(){
        val theme=mutableStateOf("magazine");val language=mutableStateOf("zh")
        ui.setContent{YohakuTheme(theme.value,language.value){Column(Modifier.fillMaxSize().safeDrawingPadding().padding(25.dp),verticalArrangement=Arrangement.spacedBy(15.dp)){
            Box(Modifier.height(20.dp).fillMaxWidth().testTag("before-step-footer"))
            CategoryStepFooter()
        }}}
        for((t,l) in testLooks(this@ThemeControlsTest)){
            ui.runOnIdle{theme.value=t;language.value=l}
            val before=ui.onNodeWithTag("before-step-footer").getUnclippedBoundsInRoot()
            val footer=ui.onNodeWithTag("category-step-footer").getUnclippedBoundsInRoot()
            if (visualEnabled()) assertEquals("Original 25dp margin",25f,(footer.top-before.bottom).value,1f)
            if(visualEnabled() && InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.fontScale==1f)assertEquals("Original footer height",when(t){"journal"->25f;"objects"->24f;else->19f},(footer.bottom-footer.top).value,1f)
            ui.onNodeWithText(if(l=="zh")"02 填写并保存" else "02 入力して保存").assertIsDisplayed()
        }
    }
    @Test fun expandedSelectMarkersMatchHtmlAndIndependentMenusKeepSelection(){
        val theme=mutableStateOf("magazine");val language=mutableStateOf("zh")
        val first=mutableStateOf("cash");val second=mutableStateOf("JPY")
        ui.setContent{YohakuTheme(theme.value,language.value){key(theme.value,language.value){
            Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(15.dp)){
                YSelect("A",first.value,listOf("cash" to "现金", "bank" to "銀行"),{first.value=it})
                YSelect("B",second.value,listOf("JPY" to "JPY", "CNY" to "CNY"),{second.value=it})
                Box(Modifier.fillMaxWidth().height(40.dp).testTag("outside-menu"))
            }
        }}}
        for((t,l) in testLooks(this@ThemeControlsTest)){
            ui.runOnIdle{theme.value=t;language.value=l;first.value="cash";second.value="JPY"}
            val closed=when(t){"journal"->"↓";"objects"->"＋";else->"⌄"}
            val expanded=when(t){"journal"->"↑";"objects"->"−";else->"⌃"}
            ui.onNodeWithTag("select-trigger-A").assert(hasText(closed)).performClick()
            ui.onNodeWithTag("select-trigger-A").assert(hasText(expanded))
            ui.onNodeWithTag("select-trigger-B").performScrollTo().performClick()
            ui.onNodeWithTag("select-trigger-B").assert(hasText(expanded))
            ui.onAllNodesWithTag("select-menu-frame").assertCountEquals(2)
            ui.onNodeWithTag("outside-menu").performScrollTo().performTouchInput{click()}
            ui.onAllNodesWithTag("select-menu-frame").assertCountEquals(2)
            ui.onNodeWithTag("select-option-B-1").performScrollTo().performClick()
            ui.onNodeWithTag("select-trigger-B").assert(hasText(closed))
            ui.onAllNodesWithTag("select-menu-frame").assertCountEquals(1)
            ui.onNodeWithTag("select-trigger-A").performScrollTo().performClick()
            ui.onNodeWithTag("select-trigger-A").assert(hasText(closed))
            ui.onAllNodesWithTag("select-menu-frame").assertCountEquals(0)
            assertEquals("cash",first.value);assertEquals("CNY",second.value)
        }
    }
    @Test fun entrySummaryMatchesSourceAndReselectRemainsReachable(){
        val theme=mutableStateOf("magazine");val language=mutableStateOf("zh");var clicks=0
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val suffix=InstrumentationRegistry.getArguments().getString("captureSuffix")?.let{"-$it"}.orEmpty()
        val folder=File(instrumentation.targetContext.filesDir,"verification/entry-summary$suffix").also{it.mkdirs()}
        val data=File(folder,"dimensions.csv").also{if (visualEnabled()) it.writeText("theme,language,component,width,height\n")}
        ui.setContent{YohakuTheme(theme.value,language.value){Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal=if(theme.value=="objects")23.dp else 25.dp).verticalScroll(rememberScrollState())){
            EntrySummary("生活","餐饮 / 咖啡",false,{clicks++})
        }}}
        for((t,l) in testLooks(this@ThemeControlsTest)){
            ui.runOnIdle{theme.value=t;language.value=l}
            for(tag in listOf("entry-summary","entry-reselect")){
                val r=ui.onNodeWithTag(tag).getUnclippedBoundsInRoot();val height=(r.bottom-r.top).value
                if (visualEnabled()) data.appendText("$t,$l,$tag,${(r.right-r.left).value},$height\n")
                if(visualEnabled() && instrumentation.targetContext.resources.configuration.fontScale==1f&&instrumentation.targetContext.resources.configuration.screenWidthDp==390){
                    val expected=if(tag=="entry-summary")when(t){"magazine"->82f;"journal"->90f;else->77f} else if(t=="objects")17f else 18f
                    assertEquals("HTML category summary: $t/$l/$tag",expected,height,1f)
                }
            }
            if (visualEnabled()) instrumentation.uiAutomation.takeScreenshot().let{bitmap->File(folder,"$t-$l.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
            val before=clicks;ui.onNodeWithTag("entry-reselect").performScrollTo().performClick();assertEquals(before+1,clicks)
        }
    }

    @Test fun dateAndSelectClosedFramesMatchSourceAcrossThemesAndLanguages(){
        val theme=mutableStateOf("magazine");val language=mutableStateOf("zh")
        val owner=mutableStateOf<Int?>(null);val date=mutableStateOf("2026-10-14")
        val selected=mutableStateOf("cash")
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val suffix=InstrumentationRegistry.getArguments().getString("captureSuffix")?.let{"-$it"}.orEmpty()
        val folder=File(instrumentation.targetContext.filesDir,"verification/select-date$suffix").also{it.mkdirs()}
        val dimensions=File(folder,"dimensions.csv").also{if (visualEnabled()) it.writeText("theme,language,component,width,height\n")}
        ui.setContent{YohakuTheme(theme.value,language.value){Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal=25.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(20.dp)){
            YSelect(word("付款账户","支払口座"),selected.value,listOf("cash" to word("现金","現金"),"bank" to word("银行账户","銀行口座")),{selected.value=it})
            YDateFields(listOf(word("日期","日付") to date.value),owner.value,{owner.value=it},{_,v->date.value=v})
        }}}
        for((t,l) in testLooks(this@ThemeControlsTest)){
            ui.runOnIdle{theme.value=t;language.value=l;owner.value=null;date.value="2026-10-14";selected.value="cash"}
            val accountLabel=if(l=="zh")"付款账户" else "支払口座"
            for((tag,expected) in listOf("select-field-$accountLabel" to when(t){"magazine"->69f;"journal"->72f;else->71f},"date-field-0" to when(t){"magazine"->65f;"journal"->67f;else->72f})){
                val r=ui.onNodeWithTag(tag).getUnclippedBoundsInRoot()
                val height=(r.bottom-r.top).value
                if (visualEnabled()) dimensions.appendText("$t,$l,$tag,${(r.right-r.left).value},$height\n")
                if(visualEnabled() && instrumentation.targetContext.resources.configuration.fontScale==1f)assertEquals("HTML closed field height: $t/$l/$tag",expected,height,1f)
            }
            val dateLabel=if(l=="zh")"日期" else "日付"
            val icon=ui.onNodeWithTag("date-icon-$dateLabel",useUnmergedTree=true).getUnclippedBoundsInRoot()
            val dateField=ui.onNodeWithTag("date-field-0").getUnclippedBoundsInRoot()
            if (visualEnabled()) assertEquals(28f,(icon.right-icon.left).value,.5f)
            if (visualEnabled()) assertEquals(29f,(icon.bottom-icon.top).value,.5f)
            if (visualEnabled()) assertEquals("Date icon right inset",5f,(dateField.right-icon.right).value,.5f)
            if (visualEnabled()) dimensions.appendText("$t,$l,date-icon,${(icon.right-icon.left).value},${(icon.bottom-icon.top).value}\n")
            if (visualEnabled()) instrumentation.uiAutomation.takeScreenshot().let{bitmap->File(folder,"$t-$l.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
            ui.onNodeWithTag("select-trigger-$accountLabel").performScrollTo().performClick()
            val menu=ui.onNodeWithTag("select-menu-frame").getUnclippedBoundsInRoot()
            if (visualEnabled()) dimensions.appendText("$t,$l,select-menu-frame,${(menu.right-menu.left).value},${(menu.bottom-menu.top).value}\n")
            if (visualEnabled()) instrumentation.uiAutomation.takeScreenshot().let{bitmap->File(folder,"$t-$l-open.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
            ui.onNodeWithTag("select-option-$accountLabel-1").performScrollTo().performClick()
            assertEquals("bank",selected.value)
            assertEquals("2026-10-14",date.value)
            ui.onNodeWithTag("date-field-0").performScrollTo().performClick()
            ui.onNodeWithContentDescription(if(l=="zh")"上一月" else "前の月").performScrollTo().performClick()
            ui.onNodeWithText("2026 / 9").assertExists()
            ui.onNodeWithContentDescription(if(l=="zh")"下一月" else "次の月").performScrollTo().performClick()
            ui.onNodeWithText("2026 / 10").assertExists()
            val frame=ui.onNodeWithTag("calendar-frame").performScrollTo().getUnclippedBoundsInRoot()
            val calendarHeight=(frame.bottom-frame.top).value
            if (visualEnabled()) dimensions.appendText("$t,$l,calendar-frame,${(frame.right-frame.left).value},$calendarHeight\n")
            if(visualEnabled() && instrumentation.targetContext.resources.configuration.fontScale==1f)assertEquals("HTML calendar height: $t/$l",when(t){"magazine"->409f;"journal"->429.8f;else->424.8f},calendarHeight,1f)
            if (visualEnabled()) instrumentation.uiAutomation.takeScreenshot().let{bitmap->File(folder,"$t-$l-calendar.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
            ui.onNodeWithTag("calendar-done").performScrollTo().performClick()
            assertNull(owner.value)
            assertEquals("2026-10-14",date.value)
        }
    }

    @Test fun allThemesUseOneFullWidthCalendarAndUpdateOnlyItsOwner() {
        val theme=mutableStateOf("magazine")
        val brand=mutableStateOf(false)
        val header=mutableStateOf(false)
        val owner=mutableStateOf<Int?>(null)
        val dates=mutableStateOf(listOf("2026-10-12","2026-10-15T09:00"))
        val selected=mutableStateOf("cash")
        ui.setContent{YohakuTheme(theme.value,"zh"){
            if(brand.value)YStartup() else if(header.value)Column(Modifier.fillMaxSize().safeDrawingPadding()){YAppHeader("日常生活",{},{})} else Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)){
                YTitle("日期与账户","THE EVERYDAY CONTROLS")
                YSelect("付款账户",selected.value,listOf("cash" to "现金","bank" to "银行账户"),{selected.value=it})
                YDateFields(listOf("转出日期" to dates.value[0],"实际到账日期" to dates.value[1]),owner.value,{owner.value=it},{i,v->dates.value=dates.value.toMutableList().also{it[i]=v}})
                YToggle("历史补录",false,{})
            }
        }}
        for(id in testLooks(this@ThemeControlsTest).map { it.first }.distinct()){
            ui.runOnIdle{theme.value=id;brand.value=true;owner.value=null;selected.value="cash";dates.value=listOf("2026-10-12","2026-10-15T09:00")}
            capture(id+"-startup")
            ui.runOnIdle{brand.value=false;header.value=true}
            capture(id+"-header")
            ui.onNodeWithTag("header-book").assertIsDisplayed()
            ui.onNodeWithTag("header-settings").assertIsDisplayed()
            ui.runOnIdle{header.value=false}
            ui.onNodeWithTag("date-field-0").performScrollTo().performClick()
            ui.onAllNodesWithTag("shared-calendar").assertCountEquals(1)
            val grid=ui.onNodeWithTag("date-field-grid").fetchSemanticsNode().boundsInRoot
            val calendar=ui.onNodeWithTag("shared-calendar").fetchSemanticsNode().boundsInRoot
            if (visualEnabled()) assertEquals(grid.width,calendar.width,1f)
            capture(id+"-calendar")
            ui.onNodeWithTag("date-field-1").performScrollTo().performClick()
            ui.onAllNodesWithTag("shared-calendar").assertCountEquals(1)
            ui.onNodeWithText("20").performScrollTo().performClick()
            assertEquals("2026-10-12",dates.value[0])
            assertEquals("2026-10-20T09:00",dates.value[1])
            ui.onNodeWithTag("date-field-1").performScrollTo().performClick()
            ui.onNodeWithTag("select-trigger-时").performScrollTo().performClick()
            ui.onNodeWithTag("select-option-时-11").performScrollTo()
            println("hour option bounds="+ui.onNodeWithTag("select-option-时-11").fetchSemanticsNode().boundsInRoot)
            capture(id+"-hour-menu")
            ui.onNodeWithTag("select-option-时-11").performClick()
            ui.waitForIdle()
            assertEquals("2026-10-20T11:00",dates.value[1])
            ui.runOnIdle{owner.value=null}
            ui.onNodeWithTag("select-trigger-付款账户").performScrollTo().performClick()
            capture(id+"-menu")
            ui.onNodeWithTag("select-option-付款账户-1").performClick()
            assertEquals("bank",selected.value)
        }
    }
    @Test fun startupUsesEachThemesBrandAndLanguageWithoutEntryButton(){
        val theme=mutableStateOf("magazine");val language=mutableStateOf("zh")
        ui.setContent{YohakuTheme(theme.value,language.value){YStartup()}}
        for((t,l) in testLooks(this@ThemeControlsTest)){
            ui.runOnIdle{theme.value=t;language.value=l}
            ui.onNodeWithTag("startup-brand").assertExists()
            ui.onNodeWithText(if(l=="zh")"余量" else "ヨハク").assertExists()
            ui.onNodeWithText("YOHAKU").assertExists()
            ui.onNodeWithText(if(l=="zh")"给日常，留一点余量。" else "日々に、少し余白を。").performScrollTo().assertIsDisplayed()
            ui.onAllNodes(hasClickAction()).assertCountEquals(0)
            capture("$t-$l-startup-final")
        }
    }
    @Test fun themeButtonsKeepWidthDisabledStateAndMixedScriptFonts(){
        val theme=mutableStateOf("magazine")
        val clicks=mutableStateOf(0)
        ui.setContent{YohakuTheme(theme.value,"zh"){
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(20.dp)){
                YText("餐饮 / 喫茶 · ¥9,700 · CN¥12.34 · US$5.00",18,number=true)
                YButton("保存记录 / 記録を保存",{clicks.value++},Modifier.testTag("primary-button"),primary=true)
                YButton("取消 / 取り消す",{},Modifier.fillMaxWidth().testTag("outline-button"))
                YButton("保存中 / 保存中",{clicks.value++},Modifier.testTag("disabled-button"),primary=true,enabled=false)
            }
        }}
        for(t in testLooks(this@ThemeControlsTest).map { it.first }.distinct()){
            ui.runOnIdle{theme.value=t}
            ui.onNodeWithTag("primary-button").assertIsEnabled()
            ui.onNodeWithTag("disabled-button").assertIsNotEnabled()
            val primary=ui.onNodeWithTag("primary-button").fetchSemanticsNode().boundsInRoot
            val outline=ui.onNodeWithTag("outline-button").fetchSemanticsNode().boundsInRoot
            if (visualEnabled()) assertEquals(primary.width,outline.width,1f)
            val before=clicks.value
            ui.onNodeWithTag("primary-button").performClick()
            assertEquals(before+1,clicks.value)
            capture("$t-buttons-fonts")
        }
    }
    @Test fun emptyStatesUseEachThemesLayoutAndRealAction(){
        val theme=mutableStateOf("magazine");val language=mutableStateOf("zh")
        var clicks=0;var expected=0
        ui.setContent{YohakuTheme(theme.value,language.value){
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState())){
                YEmpty(word("还没有记录。创建自己的分类，开始记录日常。","記録はまだありません。自分のカテゴリを作って、日々の記録を始めましょう。"),{clicks++},word("记一笔","記録"))
            }
        }}
        for((t,l) in testLooks(this@ThemeControlsTest)){
            ui.runOnIdle{theme.value=t;language.value=l}
            val label=if(l=="zh")"记一笔" else "記録"
            ui.onNodeWithText(label).performScrollTo().assertIsDisplayed().performClick()
            assertEquals(++expected,clicks)
            ui.onNodeWithTag("theme-empty").assertExists()
            val node=ui.onNodeWithTag("theme-empty").fetchSemanticsNode()
            val instrumentation=InstrumentationRegistry.getInstrumentation()
            val suffix=InstrumentationRegistry.getArguments().getString("captureSuffix")?.let{"-$it"}.orEmpty()
            val folder=File(instrumentation.targetContext.filesDir,"verification/theme-controls$suffix").also{it.mkdirs()}
            val density=instrumentation.targetContext.resources.displayMetrics.density
            File(folder,"empty-dimensions.csv").let{file->
                if(t=="magazine"&&l=="zh")if (visualEnabled()) file.writeText("theme,language,width,height\n")
                if (visualEnabled()) file.appendText("$t,$l,${node.boundsInRoot.width/density},${node.boundsInRoot.height/density}\n")
            }
            capture("$t-$l-empty")
        }
    }
    @Test fun successToastKeepsEachThemesStyleInBothLanguages(){
        val theme=mutableStateOf("magazine");val language=mutableStateOf("zh")
        ui.setContent{YohakuTheme(theme.value,language.value){
            Box(Modifier.fillMaxSize().safeDrawingPadding().padding(start=24.dp,end=24.dp,bottom=95.dp),contentAlignment=androidx.compose.ui.Alignment.BottomCenter){
                YToast(word("修改已保存到原条目","元の記録に変更を保存"))
            }
        }}
        for((t,l) in testLooks(this@ThemeControlsTest)){
            ui.runOnIdle{theme.value=t;language.value=l}
            ui.onNodeWithTag("theme-toast").assertIsDisplayed()
            ui.onNodeWithText(if(l=="zh")"修改已保存到原条目" else "元の記録に変更を保存").assertIsDisplayed()
            ui.onAllNodes(hasClickAction()).assertCountEquals(0)
            val node=ui.onNodeWithTag("theme-toast").fetchSemanticsNode()
            val instrumentation=InstrumentationRegistry.getInstrumentation()
            val suffix=InstrumentationRegistry.getArguments().getString("captureSuffix")?.let{"-$it"}.orEmpty()
            val folder=File(instrumentation.targetContext.filesDir,"verification/theme-controls$suffix").also{it.mkdirs()}
            val density=instrumentation.targetContext.resources.displayMetrics.density
            File(folder,"toast-dimensions.csv").let{file->
                if(t=="magazine"&&l=="zh")if (visualEnabled()) file.writeText("theme,language,width,height\n")
                if (visualEnabled()) file.appendText("$t,$l,${node.boundsInRoot.width/density},${node.boundsInRoot.height/density}\n")
            }
            capture("$t-$l-toast")
        }
    }
    @Test fun togglesExposeStateAndThemeSpecificControls(){
        val theme=mutableStateOf("magazine");val language=mutableStateOf("zh");val enabled=mutableStateOf(false)
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val suffix=InstrumentationRegistry.getArguments().getString("captureSuffix")?.let{"-$it"}.orEmpty()
        val folder=File(instrumentation.targetContext.filesDir,"verification/theme-controls$suffix").also{it.mkdirs()}
        val dimensions=File(folder,"toggle-dimensions.csv");if (visualEnabled()) dimensions.writeText("theme,language,width,height\n")
        ui.setContent{YohakuTheme(theme.value,language.value){
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState())){
                YToggle(word("常用分类","よく使うカテゴリ"),enabled.value,{enabled.value=it})
                YToggle(word("历史补录：已包含在期初余额","履歴追加：期首残高に含まれる"),enabled.value,{enabled.value=it})
            }
        }}
        for((t,l) in testLooks(this@ThemeControlsTest)){
            ui.runOnIdle{theme.value=t;language.value=l;enabled.value=false}
            val label=if(l=="zh")"常用分类" else "よく使うカテゴリ"
            ui.onNodeWithTag("toggle-$label").assertIsOff().performClick().assertIsOn()
            capture("$t-$l-toggle-on")
            ui.onNodeWithTag("toggle-$label").performClick().assertIsOff()
            val bounds=ui.onNodeWithTag("toggle-$label").fetchSemanticsNode().boundsInRoot
            val density=instrumentation.targetContext.resources.displayMetrics.density
            if (visualEnabled()) dimensions.appendText("$t,$l,${bounds.width/density},${bounds.height/density}\n")
            capture("$t-$l-toggle-off")
        }
    }
    @Test fun readFailureUsesThemeAndLocalizedRetryAndDetails(){
        val theme=mutableStateOf("magazine");val language=mutableStateOf("zh")
        var retries=0
        ui.setContent{YohakuTheme(theme.value,language.value){key(theme.value,language.value){
            com.yohaku.expensetracker.ui.features.ReadFailureScreen("SQLiteException: no such table: books",{retries++})
        }}}
        for((t,l) in testLooks(this@ThemeControlsTest)){
            ui.runOnIdle{theme.value=t;language.value=l}
            ui.onNodeWithText(if(l=="zh")"无法读取账本" else "帳簿を読み込めません").assertIsDisplayed()
            ui.onNodeWithTag("read-error-details").assertDoesNotExist()
            val before=retries
            ui.onNodeWithTag("retry-read").performScrollTo().performClick()
            assertEquals(before+1,retries)
            capture("$t-$l-read-error")
            ui.onNodeWithText(if(l=="zh")"展开错误详情" else "エラー詳細を表示").performScrollTo().performClick()
            ui.onNodeWithTag("read-error-details").performScrollTo().assertIsDisplayed()
            capture("$t-$l-read-error-details")
        }
    }
    private fun capture(name:String){
        if (!visualEnabled()) return
        ui.waitForIdle()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val suffix=InstrumentationRegistry.getArguments().getString("captureSuffix")?.let{"-$it"}.orEmpty()
        val folder=File(instrumentation.targetContext.filesDir,"verification/theme-controls$suffix").also{it.mkdirs()}
        instrumentation.uiAutomation.takeScreenshot().useBitmap{bitmap->File(folder,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}
    }
    private inline fun Bitmap.useBitmap(block:(Bitmap)->Unit){try{block(this)}finally{recycle()}}
}
