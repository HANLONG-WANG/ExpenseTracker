package com.yohaku.expensetracker

import android.graphics.Bitmap
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.BookSnapshot
import com.yohaku.expensetracker.ui.features.*
import com.yohaku.expensetracker.ui.components.YEmpty
import com.yohaku.expensetracker.ui.theme.*
import org.junit.*
import org.junit.Assert.*
import java.io.File

class SetupPresentationTest {
    @get:Rule val ui=createComposeRule()
    @get:Rule(order=1) val failureArtifacts=RegressionFailureArtifacts(ui)
    @Test fun homePageMatchesSourceThroughDailyPanel(){
        val theme=mutableStateOf("magazine");val language=mutableStateOf("zh");val populated=mutableStateOf(true)
        val i=InstrumentationRegistry.getInstrumentation()
        val source=org.json.JSONArray(i.context.assets.open("home-page-measurements.json").bufferedReader().use{it.readText()})
        val suffix=InstrumentationRegistry.getArguments().getString("captureSuffix")?.let{"-$it"}.orEmpty()
        val folder=File(i.targetContext.filesDir,"verification/home-page$suffix").also{it.mkdirs()}
        val csv=File(folder,"dimensions.csv").also{it.writeText("theme,language,mode,key,top,height\n")}
        val errors=mutableListOf<String>()
        ui.setContent{YohakuTheme(theme.value,language.value){
            val account=AccountEntity("fixture","cash","现金","CASH","JPY",10000,"2026-10-01")
            val events=if(populated.value)listOf(EventEntity("fixture","record","EXPENSE","cash","food",300,"JPY",300,"2026-10-14","喫茶と余白","")) else emptyList()
            val postings=if(populated.value)listOf(PostingEntity("fixture","posting","record","cash",-300,"JPY","2026-10-14")) else emptyList()
            val snapshot=BookSnapshot(BookEntity("fixture","生活","JPY","2026-10-01",budgetMinor=if(populated.value)52500 else null),if(populated.value)listOf(account) else emptyList(),listOf(CategoryEntity("fixture","food","餐饮","EXPENSE")),events,postings,null)
            Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(pageContentPadding())){
                HomeScreen(snapshot,{},{},{},{},{})
            }
        }}
        for(index in 0 until source.length()){
            val ref=source.getJSONObject(index);val t=ref.getString("theme");val l=ref.getString("language");val mode=ref.getString("mode")
            ui.runOnIdle{theme.value=t;language.value=l;populated.value=mode=="budget"}
            val cover=if(mode=="budget")"home-cover" else "lead-cover"
            ui.onNodeWithTag(cover).performScrollTo();val origin=ui.onNodeWithTag(cover).getUnclippedBoundsInRoot().top
            val tags=mapOf("cover" to cover,"set-budget" to "home-set-budget","context" to "home-spend-context","funds" to "home-cash-note","records" to "transaction-list","daily" to "home-daily-panel")
            val nodes=ref.getJSONArray("nodes")
            // 2026-10-25 refinement reorders the home page: daily allowance follows the monthly figures and recent
            // records come before actual funds. Only the unchanged top anchors are still compared with the frozen
            // HTML; the magazine cover is shorter because its aside amount is one line instead of prefix + digits.
            for(j in 0 until nodes.length()){
                val expected=nodes.getJSONObject(j);val key=expected.getString("key")
                if(key !in listOf("cover","set-budget","context"))continue
                val r=ui.onNodeWithTag(tags.getValue(key)).getUnclippedBoundsInRoot()
                val top=(r.top-origin).value;val height=(r.bottom-r.top).value
                csv.appendText("$t,$l,$mode,$key,$top,$height\n")
                val coverChanged=t=="magazine"&&mode=="budget"
                if(i.targetContext.resources.configuration.let{it.fontScale==1f&&it.screenWidthDp==390}){
                    if(!(coverChanged&&key=="context")&&kotlin.math.abs(top-expected.getDouble("top"))>2)errors+="$t/$l/$mode/$key top $top vs ${expected.getDouble("top")}"
                    val expectedHeight=expected.getDouble("height")+if(key=="context")magazineRules(t,-21f) else 0f
                    if(!(coverChanged&&key=="cover")&&kotlin.math.abs(height-expectedHeight)>1)errors+="$t/$l/$mode/$key height $height vs $expectedHeight"
                }
            }
            fun top(tag:String)=ui.onNodeWithTag(tag).getUnclippedBoundsInRoot().top
            val fundsHeading=ui.onAllNodesWithTag("section-heading").onLast().getUnclippedBoundsInRoot().top
            assertTrue("$t/$l/$mode daily panel precedes recent records",top("home-daily-panel")<top("transaction-list"))
            assertTrue("$t/$l/$mode recent records precede actual funds",top("transaction-list")<fundsHeading&&fundsHeading<top("home-cash-note"))
            ui.onNodeWithTag("home-daily-stats").performScrollTo().assertIsDisplayed()
            i.uiAutomation.takeScreenshot().let{bitmap->File(folder,"$t-$l-$mode.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
        }
        assertTrue(errors.joinToString("\n"),errors.isEmpty())
    }
    @Test fun booksPageMatchesSourceThroughDetailsPanel(){
        val theme=mutableStateOf("magazine");val language=mutableStateOf("zh")
        val i=InstrumentationRegistry.getInstrumentation()
        val source=org.json.JSONArray(i.context.assets.open("books-page-measurements.json").bufferedReader().use{it.readText()})
        val suffix=InstrumentationRegistry.getArguments().getString("captureSuffix")?.let{"-$it"}.orEmpty()
        val folder=File(i.targetContext.filesDir,"verification/books-page$suffix").also{it.mkdirs()}
        val csv=File(folder,"dimensions.csv").also{it.writeText("theme,language,key,top,height\n")}
        val errors=mutableListOf<String>()
        ui.setContent{YohakuTheme(theme.value,language.value){
            val books=listOf(BookOverview(BookEntity("book-a","生活","JPY","2026-10-01",budgetMinor=52500),1,1),BookOverview(BookEntity("book-b","別の生活","CNY","2026-10-01",budgetMinor=12345),0,0))
            Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(pageContentPadding())){
                BooksScreen(books,"book-a",{},{},{})
            }
        }}
        for(index in 0 until source.length()){
            val ref=source.getJSONObject(index);val t=ref.getString("theme");val l=ref.getString("language")
            ui.runOnIdle{theme.value=t;language.value=l}
            ui.onNodeWithTag("page-title-heading").performScrollTo()
            val origin=ui.onNodeWithTag("page-title-heading").getUnclippedBoundsInRoot().top
            val tags=mapOf("heading" to "page-title-heading","first" to "book-choice-book-a","second" to "book-choice-book-b","add" to "books-create","note" to "books-note","details" to "books-audit-container")
            val nodes=ref.getJSONArray("blocks")
            for(j in 0 until nodes.length()){
                val expected=nodes.getJSONObject(j);val key=expected.getString("key");val r=ui.onNodeWithTag(tags.getValue(key)).getUnclippedBoundsInRoot()
                // Compare the unrotated page anchor, excluding the panel's symmetric 10dp outer spacing.
                // P23 adds the lifecycle link before the original details panel.
                val extension=if(key=="details")ui.onNodeWithTag("books-manage-lifecycle").getUnclippedBoundsInRoot().let{(it.bottom-it.top).value}+15f else 0f
                val top=(r.top-origin).value+(if(key=="details")10f else 0f)-extension;val height=(r.bottom-r.top).value-if(key=="details")20f else 0f
                csv.appendText("$t,$l,$key,$top,$height\n")
                val expectedHeight=expected.getDouble("height")+if(key=="details")magazineRules(t,-33f) else 0f
                if(i.targetContext.resources.configuration.let{it.fontScale==1f&&it.screenWidthDp==390}){
                    if(kotlin.math.abs(top-expected.getDouble("top"))>1)errors+="$t/$l/$key top $top vs ${expected.getDouble("top")}"
                    if(kotlin.math.abs(height-expectedHeight)>1)errors+="$t/$l/$key height $height vs $expectedHeight"
                }
            }
            ui.onNodeWithTag("books-create").performScrollTo().assertIsDisplayed()
            ui.onNodeWithTag("book-edit-book-b").performScrollTo().assertIsDisplayed()
            i.uiAutomation.takeScreenshot().let{bitmap->File(folder,"$t-$l.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
        }
        assertTrue(errors.joinToString("\n"),errors.isEmpty())
    }
    @Test fun ledgerPageMatchesSourceThroughFilteredList(){
        val theme=mutableStateOf("magazine");val language=mutableStateOf("zh");val populated=mutableStateOf(true)
        val i=InstrumentationRegistry.getInstrumentation()
        val source=org.json.JSONArray(i.context.assets.open("ledger-page-measurements.json").bufferedReader().use{it.readText()})
        val suffix=InstrumentationRegistry.getArguments().getString("captureSuffix")?.let{"-$it"}.orEmpty()
        val folder=File(i.targetContext.filesDir,"verification/ledger-page$suffix").also{it.mkdirs()}
        val csv=File(folder,"dimensions.csv").also{it.writeText("theme,language,mode,key,top,height\n")}
        val errors=mutableListOf<String>()
        ui.setContent{YohakuTheme(theme.value,language.value){
            val account=AccountEntity("fixture","cash","现金","CASH","JPY",10000,"2026-10-01")
            val events=if(populated.value)listOf(EventEntity("fixture","record","EXPENSE","cash","food",300,"JPY",300,"2026-10-14","喫茶と余白","")) else emptyList()
            val snapshot=BookSnapshot(BookEntity("fixture","生活","JPY","2026-10-01"),listOf(account),listOf(CategoryEntity("fixture","food","餐饮","EXPENSE")),events,emptyList(),null)
            Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(pageContentPadding()),verticalArrangement=Arrangement.spacedBy(15.dp)){
                LedgerScreen(snapshot,{},{},{},java.time.LocalDate.parse("2026-10-26"))
            }
        }}
        var expanded=false
        for(index in 0 until source.length()){
            val ref=source.getJSONObject(index);val t=ref.getString("theme");val l=ref.getString("language");val mode=ref.getString("mode")
            ui.runOnIdle{theme.value=t;language.value=l;populated.value=mode!="empty"}
            if(expanded!=(mode=="expanded")){ui.onNodeWithTag("ledger-filters").performScrollTo().performClick();expanded=mode=="expanded"}
            ui.onNodeWithTag("page-title-heading").performScrollTo()
            val origin=ui.onNodeWithTag("page-title-heading").getUnclippedBoundsInRoot().top
            val tags=mapOf("heading" to "page-title-heading","actions" to "ledger-entry-actions","search" to "ledger-search","type" to if(l=="ja")"select-field-種類" else "select-field-类型","filters" to "ledger-filter-panel","records" to "transaction-list")
            val nodes=ref.getJSONArray("nodes")
            for(j in 0 until nodes.length()){
                val expected=nodes.getJSONObject(j);val key=expected.getString("key");val r=ui.onNodeWithTag(tags.getValue(key)).getUnclippedBoundsInRoot()
                // 2026-10-25 refinement groups the ledger by day; compare the original rows by removing the day headers,
                // which replace the list's opening rule (magazine/journal) or add one 8dp card gap each (objects).
                val headers=if(key!="records"||mode=="empty")emptyList() else ui.onAllNodes(SemanticsMatcher("ledger day header"){androidx.compose.ui.semantics.SemanticsProperties.TestTag in it.config&&it.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag].startsWith("ledger-day-")}).fetchSemanticsNodes()
                val dayHeaders=if(headers.isEmpty())0f else headers.sumOf{(it.boundsInRoot.height/i.targetContext.resources.displayMetrics.density).toDouble()}.toFloat()+(if(t=="objects")8f*headers.size else -1f)
                val top=(r.top-origin).value;val height=(r.bottom-r.top).value-dayHeaders
                csv.appendText("$t,$l,$mode,$key,$top,$height\n")
                val expectedHeight=expected.getDouble("height")+if(key=="records"&&headers.isNotEmpty())magazineRules(t,-1f) else 0f
                if(i.targetContext.resources.configuration.let{it.fontScale==1f&&it.screenWidthDp==390}){
                    if(kotlin.math.abs(top-expected.getDouble("top"))>1)errors+="$t/$l/$mode/$key top $top vs ${expected.getDouble("top")}"
                    if(kotlin.math.abs(height-expectedHeight)>1)errors+="$t/$l/$mode/$key height $height vs $expectedHeight"
                }
            }
            i.uiAutomation.takeScreenshot().let{bitmap->File(folder,"$t-$l-$mode.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
            ui.onNodeWithTag("transaction-list").performScrollTo().assertIsDisplayed()
        }
        assertTrue(errors.joinToString("\n"),errors.isEmpty())
    }
    @Test fun accountDetailBodyMatchesSourceAndActionsRemainReachable(){
        val theme=mutableStateOf("magazine");val language=mutableStateOf("zh");val populated=mutableStateOf(false);var selected=""
        val i=InstrumentationRegistry.getInstrumentation()
        val source=org.json.JSONArray(i.context.assets.open("account-detail-measurements.json").bufferedReader().use{it.readText()})
        val suffix=InstrumentationRegistry.getArguments().getString("captureSuffix")?.let{"-$it"}.orEmpty()
        val folder=File(i.targetContext.filesDir,"verification/account-detail-body$suffix").also{it.mkdirs()}
        val csv=File(folder,"dimensions.csv").also{it.writeText("theme,language,populated,key,top,height\n")}
        val errors=mutableListOf<String>()
        ui.setContent{YohakuTheme(theme.value,language.value){
            val account=AccountEntity("fixture","cash","现金","CASH","JPY",10000,"2026-10-01")
            val events=if(populated.value)listOf(EventEntity("fixture","record","EXPENSE","cash","food",300,"JPY",300,"2026-10-14","喫茶と余白","")) else emptyList()
            val postings=if(populated.value)listOf(PostingEntity("fixture","posting","record","cash",-300,"JPY","2026-10-14")) else emptyList()
            val snapshot=BookSnapshot(BookEntity("fixture","生活","JPY","2026-10-01"),listOf(account),listOf(CategoryEntity("fixture","food","餐饮","EXPENSE")),events,postings,null)
            Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(pageContentPadding()),verticalArrangement=Arrangement.spacedBy(15.dp)){
                AccountDetail(snapshot,account,{selected="edit"},{selected=it.id})
            }
        }}
        for(index in 0 until source.length()){
            val ref=source.getJSONObject(index);val t=ref.getString("theme");val l=ref.getString("language");val hasRecords=ref.getBoolean("populated")
            ui.runOnIdle{theme.value=t;language.value=l;populated.value=hasRecords}
            ui.onNodeWithTag("lead-cover").performScrollTo()
            val origin=ui.onNodeWithTag("lead-cover").getUnclippedBoundsInRoot().top
            val tags=mapOf("lead" to "lead-cover","edit" to "account-detail-edit","heading" to "section-heading","records" to "transaction-list")
            if(t=="magazine"&&l=="ja"&&!hasRecords){
                for(tag in listOf("lead-title","lead-description","lead-amount","lead-aside")){
                    val r=ui.onNodeWithTag(tag).getUnclippedBoundsInRoot();csv.appendText("$t,$l,$hasRecords,$tag,${(r.top-origin).value},${(r.bottom-r.top).value},${(r.right-r.left).value}\n")
                }
                i.uiAutomation.takeScreenshot().let{bitmap->File(folder,"$t-$l-top.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
            }
            val nodes=ref.getJSONArray("nodes")
            for(j in 0 until nodes.length()){
                val expected=nodes.getJSONObject(j);val key=expected.getString("key");val r=ui.onNodeWithTag(tags.getValue(key)).getUnclippedBoundsInRoot()
                // B/C add the transfer action and actual-balance date note above the original editor.
                val transfer=ui.onNodeWithTag("account-detail-transfer").getUnclippedBoundsInRoot()
                val note=ui.onNodeWithText(if(l=="zh")"仅含截至今日已确认流水。未来已记流水在时间线中另列。" else "本日までの確定記録のみ。将来日付の記録は予定に別掲。",substring=true).getUnclippedBoundsInRoot()
                val extension=if(key=="lead")0f else (transfer.bottom-transfer.top).value+(note.bottom-note.top).value+30f
                val top=(r.top-origin).value-extension;val height=(r.bottom-r.top).value
                csv.appendText("$t,$l,$hasRecords,$key,$top,$height\n")
                if(i.targetContext.resources.configuration.let{it.fontScale==1f&&it.screenWidthDp==390}){
                    if(kotlin.math.abs(top-expected.getDouble("top"))>1)errors+="$t/$l/$hasRecords/$key top $top vs ${expected.getDouble("top")}" 
                    if(kotlin.math.abs(height-expected.getDouble("height"))>1)errors+="$t/$l/$hasRecords/$key height $height vs ${expected.getDouble("height")}" 
                }
            }
            ui.onNodeWithTag("account-detail-edit").performScrollTo().performClick();ui.runOnIdle{assertEquals("edit",selected)}
            if(hasRecords){ui.onNodeWithText("喫茶と余白").performScrollTo().performClick();ui.runOnIdle{assertEquals("record",selected)}}
        }
        assertTrue(errors.joinToString("\n"),errors.isEmpty())
    }
    @Test fun accountsPageMatchesSourceFromLeadThroughAdd(){
        val theme=mutableStateOf("magazine");val language=mutableStateOf("zh");val mode=mutableStateOf("funds")
        val i=InstrumentationRegistry.getInstrumentation()
        val source=org.json.JSONArray(i.context.assets.open("accounts-page-measurements.json").bufferedReader().use{it.readText()})
        val suffix=InstrumentationRegistry.getArguments().getString("captureSuffix")?.let{"-$it"}.orEmpty()
        val folder=File(i.targetContext.filesDir,"verification/accounts-page$suffix").also{it.mkdirs()}
        val csv=File(folder,"dimensions.csv").also{it.writeText("theme,language,mode,key,top,height\n")}
        val errors=mutableListOf<String>()
        ui.setContent{YohakuTheme(theme.value,language.value){
            val accounts=if(mode.value=="empty")emptyList() else listOf(AccountEntity("fixture","cash","现金","CASH","JPY",10000,"2026-10-01"))+if(mode.value=="debt")listOf(AccountEntity("fixture","credit","信用卡","CREDIT","JPY",3000,"2026-10-01")) else emptyList()
            val snapshot=BookSnapshot(BookEntity("fixture","生活","JPY","2026-10-01"),accounts,emptyList(),emptyList(),emptyList(),null)
            Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(pageContentPadding()),verticalArrangement=Arrangement.spacedBy(15.dp)){
                AccountsScreen(snapshot,{},{})
            }
        }}
        for(index in 0 until source.length()){
            val ref=source.getJSONObject(index);val t=ref.getString("theme");val l=ref.getString("language");val m=ref.getString("mode")
            ui.runOnIdle{theme.value=t;language.value=l;mode.value=m}
            ui.onNodeWithTag("lead-cover").performScrollTo()
            val origin=ui.onNodeWithTag("lead-cover").getUnclippedBoundsInRoot().top
            val nodes=ref.getJSONArray("nodes")
            for(j in 0 until nodes.length()){
                val expected=nodes.getJSONObject(j);val key=expected.getString("key")
                val node=when(key){
                    "lead"->ui.onNodeWithTag("lead-cover")
                    "summary"->ui.onNodeWithTag("accounts-reference-note")
                    "funds-heading"->ui.onAllNodesWithTag("section-heading").onFirst()
                    // A planning section now follows the account lists, so the debt heading is the second one.
                    "debt-heading"->ui.onAllNodesWithTag("section-heading")[1]
                    "funds"->ui.onNodeWithTag("funds-account-list")
                    "debt"->ui.onNodeWithTag("debt-account-list")
                    else->ui.onNodeWithTag("accounts-add")
                }
                val r=node.getUnclippedBoundsInRoot()
                // C adds the dated cash-balance explanation after the original summary.
                val note=ui.onNodeWithText(if(l=="zh")"资金只计截至今日已确认流水；未来已记流水在时间线中另列。" else "資金は本日までの確定記録のみ。将来日付の記録は予定に別掲。",substring=true).getUnclippedBoundsInRoot()
                val extension=if(key in listOf("lead","summary"))0f else (note.bottom-note.top).value+15f
                val top=(r.top-origin).value-extension;val height=(r.bottom-r.top).value
                csv.appendText("$t,$l,$m,$key,$top,$height\n")
                val lists=when(m){"empty"->0f;"funds"->1f;else->2f}
                val expectedTop=expected.getDouble("top")+if(key=="add")magazineRules(t,-27f-lists) else 0f
                val expectedHeight=expected.getDouble("height")+if(key in listOf("funds","debt")&&m!="empty"&&!(key=="debt"&&m=="funds"))magazineRules(t,-1f) else 0f
                if(i.targetContext.resources.configuration.let{it.fontScale==1f&&it.screenWidthDp==390}){
                    if(kotlin.math.abs(top-expectedTop)>1)errors+="$t/$l/$m/$key top $top vs $expectedTop"
                    if(kotlin.math.abs(height-expectedHeight)>1)errors+="$t/$l/$m/$key height $height vs $expectedHeight"
                }
            }
            ui.onNodeWithTag("accounts-add").performScrollTo().assertIsDisplayed()
        }
        assertTrue(errors.joinToString("\n"),errors.isEmpty())
    }
    @Test fun categoryManagementPageMatchesGroupedDesign(){
        val theme=mutableStateOf("magazine");val language=mutableStateOf("zh");val income=mutableStateOf(true)
        val i=InstrumentationRegistry.getInstrumentation()
        val source=org.json.JSONArray(i.context.assets.open("category-page-measurements.json").bufferedReader().use{it.readText()})
        val suffix=InstrumentationRegistry.getArguments().getString("captureSuffix")?.let{"-$it"}.orEmpty()
        val folder=File(i.targetContext.filesDir,"verification/category-page$suffix").also{it.mkdirs()}
        val csv=File(folder,"dimensions.csv").also{it.writeText("theme,language,income,key,top,height\n")}
        val errors=mutableListOf<String>()
        ui.setContent{YohakuTheme(theme.value,language.value){
            val categories=listOf(CategoryEntity("fixture","food","餐饮","EXPENSE"),CategoryEntity("fixture","coffee","咖啡","EXPENSE","food"))+if(income.value)listOf(CategoryEntity("fixture","work","收入","INCOME"),CategoryEntity("fixture","salary","工资","INCOME","work")) else emptyList()
            val snapshot=BookSnapshot(BookEntity("fixture","生活","JPY","2026-10-01"),emptyList(),categories,emptyList(),emptyList(),null)
            Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(pageContentPadding()),verticalArrangement=Arrangement.spacedBy(15.dp)){
                CategoryManagement(snapshot,{_,_->},{},{})
            }
        }}
        for(index in 0 until source.length()){
            val ref=source.getJSONObject(index);val t=ref.getString("theme");val l=ref.getString("language");val populated=ref.getString("income")=="populated"
            ui.runOnIdle{theme.value=t;language.value=l;income.value=populated}
            ui.onNodeWithTag("lead-cover").performScrollTo()
            val origin=ui.onNodeWithTag("lead-cover").getUnclippedBoundsInRoot().top
            val nodes=ref.getJSONArray("nodes")
            for(j in 0 until nodes.length()){
                val expected=nodes.getJSONObject(j);val key=expected.getString("key")
                val node=when(key){
                    "lead"->ui.onNodeWithTag("lead-cover")
                    "expense-heading"->ui.onAllNodesWithTag("section-heading").onFirst()
                    "income-heading"->ui.onAllNodesWithTag("section-heading").onLast()
                    "expense-content"->ui.onNodeWithTag("category-manage-group-food")
                    "income-content"->ui.onNodeWithTag(if(populated)"category-manage-group-work" else "theme-empty")
                    "expense-add"->ui.onNodeWithTag("category-kind-add-EXPENSE")
                    "income-add"->ui.onNodeWithTag("category-kind-add-INCOME")
                    else->ui.onNodeWithTag("audit-panel-body")
                }
                val r=node.getUnclippedBoundsInRoot();val top=(r.top-origin).value;val height=(r.bottom-r.top).value
                csv.appendText("$t,$l,$populated,$key,$top,$height\n")
                val groupsAbove=when(key){"lead","expense-heading","expense-content"->0;"expense-add","income-heading","income-content"->1;else->if(populated)2 else 1}
                val expectedTop=expected.getDouble("top")+magazineRules(t,-2f*groupsAbove)
                val expectedHeight=expected.getDouble("height")+when{key=="favorites"->magazineRules(t,-23f);key.endsWith("content")&&(key=="expense-content"||populated)->magazineRules(t,-2f);else->0f}
                if(i.targetContext.resources.configuration.let{it.fontScale==1f&&it.screenWidthDp==390}){
                    if(kotlin.math.abs(top-expectedTop)>1)errors+="$t/$l/$populated/$key top $top vs $expectedTop"
                    if(kotlin.math.abs(height-expectedHeight)>1)errors+="$t/$l/$populated/$key height $height vs $expectedHeight"
                }
            }
            ui.onNodeWithTag("category-kind-add-INCOME").performScrollTo().assertIsDisplayed()
        }
        assertTrue(errors.joinToString("\n"),errors.isEmpty())
    }
    @Test fun categoryManagementRowsMatchSourceForOneAndTwoChildren(){
        val theme=mutableStateOf("magazine");val language=mutableStateOf("zh");val count=mutableIntStateOf(1);var chosen=""
        val i=InstrumentationRegistry.getInstrumentation()
        val suffix=InstrumentationRegistry.getArguments().getString("captureSuffix")?.let{"-$it"}.orEmpty()
        val folder=File(i.targetContext.filesDir,"verification/category-manage-rows$suffix").also{it.mkdirs()}
        val csv=File(folder,"dimensions.csv").also{it.writeText("theme,language,count,tag,top,height\n")}
        ui.setContent{YohakuTheme(theme.value,language.value){Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal=if(theme.value=="objects")23.dp else 25.dp)){
            CategoryManageGroup("food","餐饮",false,(0 until count.intValue).map{"child$it" to "咖啡"},{chosen="parent"},{chosen=it},{chosen="add"})
        }}}
        for(t in listOf("magazine","journal","objects"))for(l in listOf("zh","ja"))for(n in 1..2){
            ui.runOnIdle{theme.value=t;language.value=l;count.intValue=n}
            ui.onNodeWithTag("category-manage-group-food").performScrollTo()
            val origin=ui.onNodeWithTag("category-manage-group-food").getUnclippedBoundsInRoot().top
            val parentTop=if(t=="objects")7f else 1f
            val parentHeight=if(t=="journal")94f else 87f
            val childTop=parentTop+parentHeight+if(t=="objects")7f else 0f
            val childHeight=when(t){"journal"->54f;"objects"->43f;else->45f}
            val childStep=childHeight+if(t=="objects")7f else 0f
            val addHeight=if(t=="journal")49f else 44f
            val expected=mutableListOf(Triple("category-manage-group-food",0f,childTop+n*childStep+addHeight+12+if(t=="objects")0f else 1f),Triple("category-manage-parent-food",parentTop,parentHeight),Triple("category-manage-children-food",childTop,n*childStep+addHeight+12))
            for(k in 0 until n)expected+=Triple("category-manage-child-child$k",childTop+k*childStep,childHeight)
            expected+=Triple("category-manage-add-food",childTop+n*childStep,addHeight)
            for((tag,top,height) in expected){
                val r=ui.onNodeWithTag(tag).getUnclippedBoundsInRoot();csv.appendText("$t,$l,$n,$tag,${(r.top-origin).value},${(r.bottom-r.top).value}\n")
                if(i.targetContext.resources.configuration.let{it.fontScale==1f&&it.screenWidthDp==390}){
                    assertEquals("Original management top $t/$l/$n/$tag",top,(r.top-origin).value,1f)
                    assertEquals("Original management height $t/$l/$n/$tag",height,(r.bottom-r.top).value,1f)
                }
            }
            i.uiAutomation.takeScreenshot().let{bitmap->File(folder,"$t-$l-$n.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
            for((tag,result) in listOf("category-manage-parent-food" to "parent","category-manage-child-child0" to "child0","category-manage-add-food" to "add")){
                ui.onNodeWithTag(tag).performScrollTo().performClick();ui.runOnIdle{assertEquals(result,chosen)}
            }
        }
    }
    @Test fun categoryFavoritesUseOriginalDisclosureRowsAndParentChildSelection(){
        val theme=mutableStateOf("magazine");val language=mutableStateOf("zh");val favorites=mutableStateOf(setOf<String>())
        val i=InstrumentationRegistry.getInstrumentation()
        val suffix=InstrumentationRegistry.getArguments().getString("captureSuffix")?.let{"-$it"}.orEmpty()
        val folder=File(i.targetContext.filesDir,"verification/favorites-panel$suffix").also{it.mkdirs()}
        val csv=File(folder,"dimensions.csv").also{it.writeText("theme,language,panelHeight,detailsTop\n")}
        ui.setContent{YohakuTheme(theme.value,language.value){key(theme.value,language.value){
            val categories=listOf(CategoryEntity("fixture","food","餐饮","EXPENSE"),CategoryEntity("fixture","coffee","咖啡","EXPENSE","food")).map{it.copy(favorite=it.id in favorites.value)}
            val snapshot=BookSnapshot(BookEntity("fixture","生活","JPY","2026-10-01"),emptyList(),categories,emptyList(),emptyList(),null)
            Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal=if(theme.value=="objects")23.dp else 25.dp)){
                CategoryFavorites(snapshot){c->favorites.value=if(c.id in favorites.value)favorites.value-c.id else favorites.value+c.id}
            }
        }}}
        for(t in listOf("magazine","journal","objects"))for(l in listOf("zh","ja")){
            ui.runOnIdle{theme.value=t;language.value=l;favorites.value=emptySet()}
            ui.onNodeWithTag("category-favorites-toggle").performScrollTo().performClick()
            val details=ui.onNodeWithTag("category-favorites-details").getUnclippedBoundsInRoot()
            val panel=ui.onNodeWithTag("audit-panel-body").getUnclippedBoundsInRoot()
            csv.appendText("$t,$l,${(panel.bottom-panel.top).value},${(details.top-panel.top).value}\n")
            if(i.targetContext.resources.configuration.let{it.fontScale==1f&&it.screenWidthDp==390}){
                val panelHeight=when(t){"magazine"->(if(l=="zh")258.484f else 290.781f)+magazineRules(t,-23f);"journal"->if(l=="zh")304.797f else 345.594f;else->if(l=="zh")292.188f else 326.188f}
                assertEquals("Original whole favorites panel $t/$l",panelHeight,(panel.bottom-panel.top).value,1f)
                val detailsTop=when(t){"magazine"->if(l=="zh")78.297f else 110.594f;"journal"->if(l=="zh")111.797f else 152.594f;else->if(l=="zh")110f else 144f}
                assertEquals("Original favorites title and body position $t/$l",detailsTop,(details.top-panel.top).value,1f)
            }
            i.uiAutomation.takeScreenshot().let{bitmap->File(folder,"$t-$l.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
            if(l=="zh"&&i.targetContext.resources.configuration.let{it.fontScale==1f&&it.screenWidthDp==390})assertEquals("Original favorites disclosure",when(t){"journal"->168f;"objects"->161f;else->157f},(details.bottom-details.top).value,1f)
            for(id in listOf("food","coffee")){
                val row=ui.onNodeWithTag("category-favorite-$id").performScrollTo()
                val r=row.getUnclippedBoundsInRoot()
                if(i.targetContext.resources.configuration.fontScale==1f)assertEquals("Original favorite row",when(t){"journal"->56f;"objects"->53f;else->51f},(r.bottom-r.top).value,1f)
                row.assertIsNotSelected().performClick().assertIsSelected().performClick().assertIsNotSelected()
            }
            ui.onNodeWithTag("category-favorites-toggle").performScrollTo().performClick()
            ui.onNodeWithTag("category-favorite-coffee").assertDoesNotExist()
        }
    }
    @Test fun entryFormMatchesSourceFromSelectedCategoryThroughSave(){
        val theme=mutableStateOf("magazine");val language=mutableStateOf("zh")
        val i=InstrumentationRegistry.getInstrumentation()
        val source=org.json.JSONArray(i.context.assets.open("entry-page-measurements.json").bufferedReader().use{it.readText()})
        val draft=mutableStateOf(com.yohaku.expensetracker.domain.model.EntryDraft(accountId="cash",categoryId="coffee",amount="680",merchant="喫茶と余白",date="2026-10-14"))
        val suffix=InstrumentationRegistry.getArguments().getString("captureSuffix")?.let{"-$it"}.orEmpty()
        val folder=File(i.targetContext.filesDir,"verification/entry-page$suffix").also{it.mkdirs()}
        val csv=File(folder,"dimensions.csv").also{it.writeText("theme,language,expanded,key,top,height\n")}
        val errors=mutableListOf<String>()
        ui.setContent{YohakuTheme(theme.value,language.value){key(theme.value,language.value){
            val snapshot=BookSnapshot(BookEntity("fixture","生活","JPY","2026-10-01"),listOf(AccountEntity("fixture","cash","现金","CASH","JPY",10000,"2026-10-01")),listOf(CategoryEntity("fixture","food","餐饮","EXPENSE"),CategoryEntity("fixture","coffee","咖啡","EXPENSE","food")),emptyList(),emptyList(),null)
            Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal=if(theme.value=="objects")23.dp else 25.dp),verticalArrangement=Arrangement.spacedBy(15.dp)){
                EntryForm(snapshot,draft.value,{draft.value=it},{},{},{},false,{})
            }
        }}}
        for(index in 0 until source.length()){
            val ref=source.getJSONObject(index);val t=ref.getString("theme");val l=ref.getString("language");val expanded=ref.getBoolean("expanded")
            ui.runOnIdle{theme.value=t;language.value=l}
            val noteField=if(l=="zh")"field-备注" else "field-メモ"
            if(ui.onAllNodesWithTag(noteField).fetchSemanticsNodes().isNotEmpty()!=expanded)ui.onNodeWithTag("entry-notes-toggle").performScrollTo().performClick()
            ui.onNodeWithTag("entry-summary").performScrollTo()
            val top=ui.onNodeWithTag("entry-summary").getUnclippedBoundsInRoot().top
            val tags=mapOf("summary" to "entry-summary","amount" to if(l=="zh")"field-group-金额 · JPY" else "field-group-金額 · JPY","quick" to "entry-quick-amount","base" to "entry-base-caption","account" to if(l=="zh")"select-field-付款账户" else "select-field-支払口座","merchant" to if(l=="zh")"field-group-商户 / 买了什么" else "field-group-お店 / 買ったもの","date" to "date-field-grid","notes" to "entry-notes","historical" to if(l=="zh")"toggle-历史补录：已包含在期初余额" else "toggle-履歴追加：期首残高に含まれる","save" to "entry-save-button")
            val nodes=ref.getJSONArray("nodes")
            for(j in 0 until nodes.length()){
                val expected=nodes.getJSONObject(j);val key=expected.getString("key");if(key=="tools")continue;val r=ui.onNodeWithTag(tags.getValue(key)).getUnclippedBoundsInRoot()
                // B/C add original-currency selection and an explicit manual-rate action.
                // Compare the retained A anchors after removing only those measured additions.
                val currencyField=ui.onNodeWithTag(if(l=="zh")"select-field-消费 / 收付原币" else "select-field-取引の原通貨").getUnclippedBoundsInRoot()
                val rateAction=ui.onNode(hasText(if(l=="zh")"查看 / 手动填写换算" else "換算を確認・手入力") and hasClickAction()).getUnclippedBoundsInRoot()
                val extension=when(key){"summary","tools","amount"->0f;"base","quick"->(currencyField.bottom-currencyField.top).value+12f;else->(currencyField.bottom-currencyField.top).value+12f+(rateAction.bottom-rateAction.top).value}
                // Removing the tools row also removes its two collapsed gaps; the sheet keeps its normal 15dp gap.
                val removedTools=if(key=="summary")0f else (0 until nodes.length()).map{nodes.getJSONObject(it)}.first{it.getString("key")=="tools"}.getDouble("height").toFloat()+28f
                val actualTop=(r.top-top).value-extension+removedTools;val actualHeight=(r.bottom-r.top).value
                csv.appendText("$t,$l,$expanded,$key,$actualTop,$actualHeight\n")
                if(i.targetContext.resources.configuration.let{it.fontScale==1f&&it.screenWidthDp==390}){
                    if(kotlin.math.abs(actualTop-expected.getDouble("top"))>1)errors+="$t/$l/$expanded/$key top: $actualTop vs ${expected.getDouble("top")}" 
                    if(kotlin.math.abs(actualHeight-expected.getDouble("height"))>1)errors+="$t/$l/$expanded/$key height: $actualHeight vs ${expected.getDouble("height")}" 
                }
            }
            ui.onNodeWithTag("entry-save-button").performScrollTo().assertIsDisplayed()
            i.uiAutomation.takeScreenshot().let{bitmap->File(folder,"$t-$l-$expanded-bottom.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
        }
        assertTrue(errors.joinToString("\n"),errors.isEmpty())
    }
    @Test fun historicalOpeningExplanationMatchesSourceAndToggleState(){
        val theme=mutableStateOf("magazine");val language=mutableStateOf("zh");val included=mutableStateOf(false)
        val i=InstrumentationRegistry.getInstrumentation()
        val suffix=InstrumentationRegistry.getArguments().getString("captureSuffix")?.let{"-$it"}.orEmpty()
        val folder=File(i.targetContext.filesDir,"verification/entry-lower$suffix").also{it.mkdirs()}
        val csv=File(folder,"dimensions.csv").also{it.writeText("theme,language,height\n")}
        ui.setContent{YohakuTheme(theme.value,language.value){
            Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal=if(theme.value=="objects")23.dp else 25.dp)){
                HistoricalOpeningToggle("2026-10-12",included.value){included.value=it}
            }
        }}
        for(t in listOf("magazine","journal","objects"))for(l in listOf("zh","ja")){
            ui.runOnIdle{theme.value=t;language.value=l;included.value=false}
            val label=if(l=="zh")"历史补录：已包含在期初余额" else "履歴追加：期首残高に含まれる"
            val node=ui.onNodeWithTag("toggle-$label").performScrollTo()
            val bounds=node.getUnclippedBoundsInRoot();val h=(bounds.bottom-bounds.top).value
            csv.appendText("$t,$l,$h\n")
            if(i.targetContext.resources.configuration.let{it.fontScale==1f&&it.screenWidthDp==390})assertEquals("Original explained toggle height $t/$l",when(t){"journal"->if(l=="ja")101f else 80f;"objects"->70f;else->71f},h,1f)
            ui.onNodeWithTag("historical-opening-description",useUnmergedTree=true).assertTextContains("2026-10-12",substring=true)
            node.assertIsOff().performClick().assertIsOn()
            i.uiAutomation.takeScreenshot().let{bitmap->File(folder,"$t-$l.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
            node.performClick().assertIsOff()
        }
    }
    @Test fun expandedCategoryRowsMatchSourceAndAllChoicesRemainReachable(){
        val theme=mutableStateOf("magazine");val language=mutableStateOf("zh");var chosen=""
        val i=InstrumentationRegistry.getInstrumentation()
        val suffix=InstrumentationRegistry.getArguments().getString("captureSuffix")?.let{"-$it"}.orEmpty()
        val folder=File(i.targetContext.filesDir,"verification/category-rows$suffix").also{it.mkdirs()}
        val csv=File(folder,"dimensions.csv").also{it.writeText("theme,language,tag,height\n")}
        ui.setContent{YohakuTheme(theme.value,language.value){
            Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal=if(theme.value=="objects")23.dp else 25.dp)){
                CategoryGroup("餐饮",0,listOf("coffee" to "咖啡"),true,{}, {chosen="parent"},{chosen=it},{chosen="add"},id="fixture")
            }
        }}
        for(t in listOf("magazine","journal","objects"))for(l in listOf("zh","ja")){
            ui.runOnIdle{theme.value=t;language.value=l}
            ui.onNodeWithTag("category-group-fixture").performScrollTo()
            // 2026-10-25 refinement: magazine groups keep only their bottom hairline, one rule less than the frozen row.
            val heights=when(t){"journal"->listOf(276f,63f,78f,59f,49f);"objects"->listOf(264f,62f,69f,52f,44f);else->listOf(254f,62f,67f,56f,44f)}
            val tags=listOf("category-group-fixture","category-group-trigger-fixture","category-parent-choice-fixture","category-child-coffee","category-add-child")
            for((tag,expected) in tags.zip(heights)){
                val r=ui.onNodeWithTag(tag).getUnclippedBoundsInRoot();val h=(r.bottom-r.top).value
                csv.appendText("$t,$l,$tag,$h\n")
                if(i.targetContext.resources.configuration.let{it.fontScale==1f&&it.screenWidthDp==390})assertEquals("Original row $t/$l/$tag",expected,h,1f)
            }
            i.uiAutomation.takeScreenshot().let{bitmap->File(folder,"$t-$l.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
            for((tag,result) in listOf("category-parent-choice-fixture" to "parent","category-child-coffee" to "coffee","category-add-child" to "add")){
                ui.onNodeWithTag(tag).performScrollTo().performClick();ui.runOnIdle{assertEquals(result,chosen)}
            }
        }
    }
    @Test fun categoryCoversMatchOriginalCompositionsAndCaptions(){
        val theme=mutableStateOf("magazine");val language=mutableStateOf("zh")
        val i=InstrumentationRegistry.getInstrumentation()
        val suffix=InstrumentationRegistry.getArguments().getString("captureSuffix")?.let{"-$it"}.orEmpty()
        val folder=File(i.targetContext.filesDir,"verification/category-covers$suffix").also{it.mkdirs()}
        val csv=File(folder,"dimensions.csv").also{it.writeText("theme,language,coverHeight,tabsTop,captionTop\n")}
        ui.setContent{YohakuTheme(theme.value,language.value){
            Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal=if(theme.value=="objects")23.dp else 25.dp),verticalArrangement=Arrangement.spacedBy(15.dp)){
                CategoryCover()
                CategoryKindTabs(com.yohaku.expensetracker.domain.model.EntryKind.EXPENSE){}
                CategorySectionCaption()
                YEmpty(word("还没有分类，从你自己的用途开始。","カテゴリはまだありません。自分の使い道から。"),{},word("创建第一个分类","最初のカテゴリを作成"))
                CategoryStepFooter()
            }
        }}
        for(t in listOf("magazine","journal","objects"))for(l in listOf("zh","ja")){
            ui.runOnIdle{theme.value=t;language.value=l}
            ui.onNodeWithTag("category-cover").performScrollTo()
            val cover=ui.onNodeWithTag("category-cover").getUnclippedBoundsInRoot()
            val tabs=ui.onNodeWithTag("category-kind-tabs").getUnclippedBoundsInRoot()
            val caption=ui.onNodeWithTag("category-section-caption").getUnclippedBoundsInRoot()
            val h=(cover.bottom-cover.top).value;val tt=(tabs.top-cover.top).value;val ct=(caption.top-cover.top).value
            csv.appendText("$t,$l,$h,$tt,$ct\n")
            if(i.targetContext.resources.configuration.let{it.fontScale==1f&&it.screenWidthDp==390}){
                val expected=when(t){"magazine"->200.578f;"journal"->174f;else->if(l=="zh")175f else 189.891f}
                assertEquals("Original cover height $t/$l",expected,h,1f)
                assertEquals("Original tabs offset $t/$l",expected+22,tt,1f)
                assertEquals("Original caption offset $t/$l",expected+44+when(t){"magazine"->36f;"journal"->47f;else->44f},ct,1f)
            }
            ui.onNodeWithText(if(l=="zh")"我的分类" else "マイカテゴリ").assertExists()
            i.uiAutomation.takeScreenshot().let{bitmap->File(folder,"$t-$l.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
            ui.onNodeWithTag("category-step-footer").performScrollTo().assertIsDisplayed()
        }
    }
    @Test fun recordDetailCoreMatchesFrozenSourceAndEditRemainsReachable(){
        val theme=mutableStateOf("magazine");val language=mutableStateOf("zh");var edited=0
        val i=InstrumentationRegistry.getInstrumentation()
        val suffix=InstrumentationRegistry.getArguments().getString("captureSuffix")?.let{"-$it"}.orEmpty()
        val folder=File(i.targetContext.filesDir,"verification/detail-core$suffix").also{it.mkdirs()}
        val csv=File(folder,"dimensions.csv").also{it.writeText("theme,language,tag,top,height\n")}
        ui.setContent{YohakuTheme(theme.value,language.value){
            val account=AccountEntity("fixture","cash",word("现金","現金"),"CASH","JPY",10000,"2026-10-01")
            val event=EventEntity("fixture","native-fixture","EXPENSE","cash",null,300,"JPY",300,"2026-10-14","喫茶と余白","",revision=1)
            val snapshot=BookSnapshot(BookEntity("fixture","生活","JPY","2026-10-01"),listOf(account),emptyList(),listOf(event),emptyList(),null)
            Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal=if(theme.value=="objects")23.dp else 25.dp)){
                RecordDetailCore(snapshot,event){edited++}
            }
        }}
        for(t in listOf("magazine","journal","objects"))for(l in listOf("zh","ja")){
            ui.runOnIdle{theme.value=t;language.value=l}
            ui.onNodeWithTag("record-cover").performScrollTo()
            val core=ui.onNodeWithTag("record-detail-core").getUnclippedBoundsInRoot()
            val coverHeight=when(t){"journal"->233.891f;"objects"->230.391f;else->231.094f}
            val editHeight=when(t){"journal"->50f;"objects"->52f;else->48f}
            val factsHeight=when(t){"journal"->212.375f;"objects"->184.375f;else->202.375f}
            for((tag,expected) in listOf("record-cover" to (0f to coverHeight),"record-edit-button" to (coverHeight+22 to editHeight),"record-facts" to (coverHeight+44+editHeight to factsHeight))){
                val r=ui.onNodeWithTag(tag).getUnclippedBoundsInRoot()
                csv.appendText("$t,$l,$tag,${(r.top-core.top).value},${(r.bottom-r.top).value}\n")
                if(i.targetContext.resources.configuration.let{it.fontScale==1f&&it.screenWidthDp==390}){
                    assertEquals("Frozen source top $t/$l/$tag",expected.first,(r.top-core.top).value,1f)
                    assertEquals("Frozen source height $t/$l/$tag",expected.second,(r.bottom-r.top).value,1f)
                }
            }
            ui.onNodeWithTag("record-fact-2").performScrollTo().assertIsDisplayed()
            ui.onNodeWithText("日常").assertExists()
            i.uiAutomation.takeScreenshot().let{bitmap->File(folder,"$t-$l.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
            val before=edited;ui.onNodeWithTag("record-edit-button").performScrollTo().performClick();ui.runOnIdle{assertEquals(before+1,edited)}
        }
    }
    @Test fun impactRowsMatchSupplementaryDesignWithoutWritingRecords(){
        val theme=mutableStateOf("magazine");val language=mutableStateOf("zh")
        val i=InstrumentationRegistry.getInstrumentation()
        val suffix=InstrumentationRegistry.getArguments().getString("captureSuffix")?.let{"-$it"}.orEmpty()
        val folder=File(i.targetContext.filesDir,"verification/impact-rows$suffix").also{it.mkdirs()}
        val csv=File(folder,"dimensions.csv").also{it.writeText("theme,language,balanceHeight,expenseHeight,budgetHeight\n")}
        ui.setContent{YohakuTheme(theme.value,language.value){
            val account=AccountEntity("fixture","cash",word("现金","現金"),"CASH","JPY",10000,"2026-10-01")
            val event=EventEntity("fixture","event","EXPENSE","cash",null,300,"JPY",300,"2026-10-14","","",revision=1)
            val snapshot=BookSnapshot(BookEntity("fixture","生活","JPY","2026-10-01",budgetMinor=10000),listOf(account),emptyList(),listOf(event),emptyList(),null)
            val impact=com.yohaku.expensetracker.data.repository.EntryImpact("2026-10",com.yohaku.expensetracker.domain.model.Currency.JPY,300,0,0,0,listOf(com.yohaku.expensetracker.data.repository.AccountBalanceChange(account,9700,10000)))
            Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal=25.dp),verticalArrangement=Arrangement.spacedBy(15.dp)){
                EntryImpactPreview(snapshot,event,impact,"2026-10")
            }
        }}
        for(t in listOf("magazine","journal","objects"))for(l in listOf("zh","ja")){
            ui.runOnIdle{theme.value=t;language.value=l}
            val heights=listOf("impact-balance-cash","impact-expense","impact-budget").map{tag->
                val r=ui.onNodeWithTag(tag).getUnclippedBoundsInRoot();(r.bottom-r.top).value
            }
            if(i.targetContext.resources.configuration.let{it.fontScale==1f&&it.screenWidthDp==390}){
                heights.forEach{assertEquals("Supplementary impact row $t/$l",when(t){"journal"->93.6f;"objects"->80.7f;else->89.7f},it,1f)}
            }
            csv.appendText("$t,$l,${heights.joinToString(",")}\n")
            i.uiAutomation.takeScreenshot().let{bitmap->File(folder,"$t-$l.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
            ui.onNodeWithTag("impact-budget").performScrollTo().assertIsDisplayed()
        }
    }
    @Test fun fundingCardsAndGroupedListsMatchSourceInBothLanguages(){
        val theme=mutableStateOf("magazine");val language=mutableStateOf("zh");val selected=mutableStateOf<String?>(null)
        val i=InstrumentationRegistry.getInstrumentation()
        val suffix=InstrumentationRegistry.getArguments().getString("captureSuffix")?.let{"-$it"}.orEmpty()
        val folder=File(i.targetContext.filesDir,"verification/account-lists$suffix").also{it.mkdirs()}
        val data=File(folder,"dimensions.csv").also{it.writeText("theme,language,firstHeight,secondHeight,listHeight,gap\n")}
        ui.setContent{YohakuTheme(theme.value,language.value){
            val accounts=listOf(
                AccountEntity("fixture","cash",word("随身现金","手元の現金"),"CASH","JPY",8600,"2026-10-12"),
                AccountEntity("fixture","bank",word("银行账户","銀行口座"),"BANK","JPY",18400,"2026-10-12"))
            val snapshot=BookSnapshot(BookEntity("fixture","生活","JPY","2026-10-12"),accounts,emptyList(),emptyList(),emptyList(),null)
            Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal=25.dp)){
                AccountsScreen(snapshot,{},{selected.value=it.id})
            }
        }}
        for(t in listOf("magazine","journal","objects"))for(l in listOf("zh","ja")){
            ui.runOnIdle{theme.value=t;language.value=l;selected.value=null}
            ui.onNodeWithTag("account-cash").performScrollTo().assertIsDisplayed()
            val first=ui.onNodeWithTag("account-cash").getUnclippedBoundsInRoot()
            val second=ui.onNodeWithTag("account-bank").getUnclippedBoundsInRoot()
            val list=ui.onNodeWithTag("funds-account-list").getUnclippedBoundsInRoot()
            val h1=(first.bottom-first.top).value;val h2=(second.bottom-second.top).value
            val listHeight=(list.bottom-list.top).value;val gap=listHeight-h1-h2
            assertEquals("Original list gap $t/$l",13f,gap,1f)
            if(i.targetContext.resources.configuration.let{it.fontScale==1f&&it.screenWidthDp==390}){
                val expected=when(t){"journal"->107.891f;"objects"->162.484f;else->86.484f}
                assertEquals("Original funding card height $t/$l",expected,h1,1f)
                assertEquals("Original second card height $t/$l",expected+magazineRules(t,-1f),h2,1f)
            }
            data.appendText("$t,$l,$h1,$h2,$listHeight,$gap\n")
            i.uiAutomation.takeScreenshot().let{bitmap->File(folder,"$t-$l.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
            ui.onNodeWithTag("account-cash").performClick();ui.runOnIdle{assertEquals("cash",selected.value)}
            ui.onNodeWithTag("account-bank").performScrollTo().performClick();ui.runOnIdle{assertEquals("bank",selected.value)}
        }
    }
    @Test fun fixedCurrencyBlockMatchesSourceInBothLanguages(){
        val theme=mutableStateOf("magazine");val language=mutableStateOf("zh")
        val i=InstrumentationRegistry.getInstrumentation()
        val suffix=InstrumentationRegistry.getArguments().getString("captureSuffix")?.let{"-$it"}.orEmpty()
        val folder=File(i.targetContext.filesDir,"verification/fixed-currency$suffix").also{it.mkdirs()}
        val data=File(folder,"dimensions.csv").also{it.writeText("theme,language,tag,top,height\n")}
        ui.setContent{YohakuTheme(theme.value,language.value){Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal=if(theme.value=="objects")23.dp else 25.dp)){
            AccountCurrencyFixed("JPY")
        }}}
        for(t in listOf("magazine","journal","objects"))for(l in listOf("zh","ja")){
            ui.runOnIdle{theme.value=t;language.value=l}
            val block=ui.onNodeWithTag("account-currency-fixed").getUnclippedBoundsInRoot()
            val tops=if(t=="objects")listOf(0f,18f,42f,85f) else listOf(0f,16f,40f,if(t=="journal")83f else 75f)
            val heights=listOf(when(t){"journal"->120.891f;"objects"->120.094f;else->109.094f},15f,if(t=="magazine")26f else 34f,if(t=="journal")20.891f else 17.094f)
            for((index,tag) in listOf("account-currency-fixed","fixed-currency-label","fixed-currency-code","fixed-currency-description").withIndex()){
                val r=ui.onNodeWithTag(tag).getUnclippedBoundsInRoot()
                data.appendText("$t,$l,$tag,${(r.top-block.top).value},${(r.bottom-r.top).value}\n")
                if(i.targetContext.resources.configuration.let{it.fontScale==1f&&it.screenWidthDp==390}){
                    assertEquals("HTML currency position $t/$l/$tag",tops[index],(r.top-block.top).value,1f)
                    assertEquals("HTML currency height $t/$l/$tag",heights[index],(r.bottom-r.top).value,1f)
                }
            }
            i.uiAutomation.takeScreenshot().let{bitmap->File(folder,"$t-$l.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
            ui.onNodeWithTag("fixed-currency-description").performScrollTo().assertIsDisplayed()
        }
    }
    @Test fun reviewAndNoteMatchSourceAndAccountRemainsEditable(){
        val theme=mutableStateOf("magazine");val language=mutableStateOf("zh");var edited:String?=null
        val i=InstrumentationRegistry.getInstrumentation()
        val suffix=InstrumentationRegistry.getArguments().getString("captureSuffix")?.let{"-$it"}.orEmpty()
        val folder=File(i.targetContext.filesDir,"verification/setup-review$suffix").also{it.mkdirs()}
        val data=File(folder,"dimensions.csv").also{it.writeText("theme,language,reviewHeight,accountHeight,noteHeight\n")}
        ui.setContent{YohakuTheme(theme.value,language.value){
            val narrow=LocalConfiguration.current.screenWidthDp<=360
            val padding=when(theme.value){"journal"->PaddingValues(start=if(narrow)25.dp else 28.dp,end=if(narrow)16.dp else 19.dp);"objects"->PaddingValues(horizontal=if(narrow)17.dp else 20.dp);else->PaddingValues(horizontal=if(narrow)18.dp else 25.dp)}
            val account=AccountEntity("fixture","setup-cash",word("现金","現金"),"CASH","JPY",10000,"2026-10-12")
            val snapshot=BookSnapshot(BookEntity("fixture",word("日常生活","日々の暮らし"),"JPY","2026-10-12"),listOf(account),emptyList(),emptyList(),emptyList(),null)
            Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(padding),verticalArrangement=Arrangement.spacedBy(15.dp)){
                SetupReview(snapshot){edited=it.id}
                SetupNote(word("新账本没有预建分类，也不复制其他账本的账户和记录。","新しい帳簿はカテゴリ未作成。他の口座・記録はコピーしません。"))
            }
        }}
        for(t in listOf("magazine","journal","objects"))for(l in listOf("zh","ja")){
            ui.runOnIdle{theme.value=t;language.value=l;edited=null}
            ui.onNodeWithTag("setup-review").performScrollTo()
            val review=ui.onNodeWithTag("setup-review").getUnclippedBoundsInRoot()
            val account=ui.onNodeWithTag("setup-account-setup-cash").getUnclippedBoundsInRoot()
            val note=ui.onNodeWithTag("setup-note").getUnclippedBoundsInRoot()
            val h=(review.bottom-review.top).value;val ah=(account.bottom-account.top).value;val nh=(note.bottom-note.top).value
            data.appendText("$t,$l,$h,$ah,$nh\n")
            if(i.targetContext.resources.configuration.fontScale==1f&&i.targetContext.resources.configuration.screenWidthDp==390){
                assertEquals("review $t/$l",when(t){"journal"->477f;"objects"->428f;else->448f+magazineRules(t,-1f)},h,1f)
                assertEquals("account $t/$l",when(t){"journal"->85f;"objects"->78f;else->80f},ah,1f)
                assertEquals("note $t/$l",when(t){"journal"->if(l=="ja")80f else 57f;"objects"->53f;else->55f},nh,1f)
            }
            i.uiAutomation.takeScreenshot().let{bitmap->File(folder,"$t-$l.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
            ui.onNodeWithTag("setup-account-setup-cash").performScrollTo().performClick()
            ui.runOnIdle{assertEquals("setup-cash",edited)}
            ui.onNodeWithTag("setup-note").performScrollTo().assertIsDisplayed()
        }
    }
}

/** 2026-10-25 magazine rule hierarchy (design/themes/magazine-rules.css): lists draw no hairline after their last row,
 *  and blocks that lost a closing rule also lost the space kept around it. Frozen v55 sizes shift by exactly that much. */
private fun magazineRules(theme:String,shift:Float)=if(theme=="magazine")shift else 0f
