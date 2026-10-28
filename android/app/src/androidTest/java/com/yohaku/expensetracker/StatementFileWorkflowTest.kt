package com.yohaku.expensetracker

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.files.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.domain.finance.FinanceMode
import com.yohaku.expensetracker.ui.features.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.time.*

@RunWith(AndroidJUnit4::class)
class StatementFileWorkflowTest {
    @get:Rule val ui=createComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private lateinit var db:YohakuDatabase
    private lateinit var repo:LedgerRepository
    private lateinit var vm:AppViewModel
    private val folders=mutableListOf<String>()
    @Before fun open() {
        val service=instrumentation.uiAutomation.serviceInfo;service.flags=service.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;instrumentation.uiAutomation.serviceInfo=service
        val context=instrumentation.targetContext
        db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        repo=LedgerRepository(db,Clock.fixed(Instant.parse("2026-10-16T00:00:00Z"),ZoneOffset.UTC))
        vm=AppViewModel(repo,AppPreferences(context), testReferenceRates(AppPreferences(context),repo.clock));ui.setContent{NativeApp(vm)}
    }
    @After fun close(){vm.viewModelScope.cancel();db.close();folders.forEach{shell("rm -r /sdcard/Download/$it")}}
    private fun shell(command:String):ByteArray=ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use{it.readBytes()}
    private fun nodes(node:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> = if(node==null)emptyList()else listOf(node)+(0 until node.childCount).flatMap{nodes(node.getChild(it))}
    private fun systemNodes()=nodes(instrumentation.uiAutomation.rootInActiveWindow)
    private fun systemClick(label:String) {
        try { ui.waitUntil(15000){systemNodes().any{it.text?.toString()?.equals(label,true)==true||it.contentDescription?.toString()?.equals(label,true)==true}} }
        catch(e:ComposeTimeoutException){
            println("System picker missing $label: "+systemNodes().joinToString(" | "){it.text.toString()+" / "+it.contentDescription.toString()})
            shot("picker-failure");throw e
        }
        instrumentation.uiAutomation.waitForIdle(300,5000)
        val node=systemNodes().first{it.text?.toString()?.equals(label,true)==true||it.contentDescription?.toString()?.equals(label,true)==true}
        val bounds=android.graphics.Rect();node.getBoundsInScreen(bounds)
        shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
    }
    private fun openFolder(folder:String) {
        ui.waitUntil(15000){systemNodes().any{it.packageName?.toString()?.contains("documentsui")==true}}
        instrumentation.uiAutomation.waitForIdle(500,5000)
        if(systemNodes().any{it.text?.toString()==folder&&it.viewIdResourceName?.endsWith("/breadcrumb_text")==true})return
        if(systemNodes().none{it.text?.toString()==folder}&&systemNodes().any{it.text?.toString()=="Download"}) {
            systemClick("Download")
        }else if(systemNodes().none{it.text?.toString()==folder}) {
            val navigation=systemNodes().firstOrNull{it.contentDescription?.toString()?.contains("roots",true)==true||it.contentDescription?.toString()?.contains("navigation",true)==true}
            navigation?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            systemClick("Downloads")
        }
        systemClick(folder)
    }
    private fun click(tag:String) {
        ui.waitUntil(10000){!vm.busy.value&&ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}
        val node=ui.onNodeWithTag(tag);node.performScrollTo();ui.waitForIdle()
        val scrolls=ui.onAllNodes(hasTestTag("sheet-scroll") and hasAnyDescendant(hasTestTag(tag)))
        if(scrolls.fetchSemanticsNodes().isNotEmpty()) {
            val scroll=scrolls.onLast()
            val header=ui.onAllNodes(hasTestTag("sheet-header") and hasAnyAncestor(hasAnyDescendant(hasTestTag(tag)))).onLast()
            val top=node.getUnclippedBoundsInRoot().top.value
            val boundary=header.getUnclippedBoundsInRoot().bottom.value
            if(top<boundary+8)scroll.performSemanticsAction(SemanticsActions.ScrollBy){it(0f,-(boundary+8-top)*instrumentation.targetContext.resources.displayMetrics.density)}
            ui.waitForIdle()
            val bottom=node.getUnclippedBoundsInRoot().bottom.value
            val viewport=scroll.getUnclippedBoundsInRoot().bottom.value
            if(bottom>viewport)scroll.performSemanticsAction(SemanticsActions.ScrollBy){it(0f,(bottom-viewport+8)*instrumentation.targetContext.resources.displayMetrics.density)}
        }
        ui.waitForIdle();node.performClick()
    }
    private fun shot(name:String) {
        if (!visualEnabled()) return
        ui.waitForIdle();instrumentation.uiAutomation.waitForIdle(400,5000)
        val bitmap=instrumentation.uiAutomation.takeScreenshot()
        val file=File(instrumentation.targetContext.getExternalFilesDir(null),"phase-d-statements-${if(instrumentation.targetContext.resources.configuration.screenWidthDp<=360)"narrow"else"standard"}/$name.png").apply{parentFile!!.mkdirs()}
        file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }

    @Test fun savedStatementManualDifferenceSupplementAndDuplicateAcrossThemesAndLanguages() {
        for((theme,language) in testLooks(this@StatementFileWorkflowTest)) {
            fun text(zh:String,ja:String)=if(language=="ja")ja else zh
            val book=runBlocking {
                val b=repo.createBook("银行核对 $theme $language",Currency.JPY,theme=theme,language=language)
                repo.updateBook(repo.dao.book(b)!!.copy(initialized=true))
                val account=repo.saveAccount(b,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
                val category=repo.saveCategory(b,"生活",EntryKind.EXPENSE,null)
                for(amount in listOf("1000","1000","1200"))repo.saveEntry(b,EntryDraft(accountId=account,categoryId=category,amount=amount,date="2026-10-14",merchant="店"))
                vm.preferences.select(b);b
            }
            fun state()=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
            ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book}
            val folder="YohakuS-"+repo.id();folders+=folder;shell("mkdir -p /sdcard/Download/$folder")
            val ids=runBlocking{repo.importIdentifiers(book)}
            val account=ids.accounts.single()
            val source=StatementFile("yohaku.statement",1,repo.id(),ids.book_id,account.account_id,account.currency,"2026-10-01","2026-10-31","posted_date",true,"銀行CSV",emptyList(),
                listOf("1000","1000","950","500").map{amount->StatementRow(repo.id(),"2026-10-14","out",FileMoney(account.currency,amount),"原始摘要",occurred_date="2026-10-14",posted_date="2026-10-14")})
            val raw=FileProtocols.json.encodeToString(source)
            val batch=runBlocking{repo.saveStatement(book,repo.previewStatement(book,raw))}
            ui.runOnIdle{vm.selectedStatementBatch.value=batch;vm.navigate("statements")}
            assertEquals(3,state().events.size)
            val result=state().reviewStatements(runBlocking{db.fileWorkflowDao().statements(book).first()},emptyList()).single()
            assertEquals(2,result.result.exact.size);assertEquals(2,result.result.exact.map{it.postingId}.distinct().size)
            val diff=source.rows[2]
            click("statement-candidates-${diff.row_id}");click("statement-posting-candidate")
            click("select-option-"+text("实际资金端候选","実際の資金側の候補")+"-1")
            click("statement-preview-difference")
            assertTrue(runBlocking{db.fileWorkflowDao().decisions(book).first().isEmpty()})
            shot("$theme-$language-difference")
            click("statement-confirm-difference")
            ui.waitUntil(10000){!vm.busy.value&&runBlocking{db.fileWorkflowDao().decisions(book).first().size==1}}
            ui.onNodeWithTag("statement-status-${diff.row_id}").assertTextEquals(text("已确认差异，非完全一致","差異確認済・完全一致ではない"))
            val missing=source.rows[3]
            click("statement-supplement-${missing.row_id}");click("statement-supplement-kind")
            click("select-option-"+text("补记类型","記録の種類")+"-1");click("statement-start-supplement")
            ui.waitUntil(10000){vm.entry.value!=null&&vm.route.value=="category-picker"}
            assertEquals(3,state().events.size);assertEquals("500",vm.entry.value!!.amount)
            click("category-shortcut-${state().categories.single().id}")
            click("entry-save-button")
            ui.waitUntil(10000){!vm.busy.value&&vm.entry.value==null&&state().events.size==4}
            val after=state()
            assertEquals(6300L,after.balance(after.accounts.single()))
            val matched=after.reviewStatements(runBlocking{db.fileWorkflowDao().statements(book).first()},runBlocking{db.fileWorkflowDao().decisions(book).first()}).single()
            assertEquals(3,matched.result.exact.size);assertEquals(1,matched.decisions.size);assertTrue(matched.decisions.single().active)
            shot("$theme-$language-reconciled")
            assertEquals(batch,runBlocking{repo.previewStatement(book,raw)}.duplicateBatchId)
            assertEquals(1,runBlocking{db.fileWorkflowDao().statements(book).first().size})
            if(theme=="objects"&&language=="ja") {
                click("statement-export-original");openFolder(folder);systemClick("Use this folder");systemClick("Allow")
                val exported="/sdcard/Download/$folder/yohaku-statement-${source.file_id}.json"
                ui.waitUntil(15000){!vm.busy.value&&shell("cat $exported").toString(Charsets.UTF_8)==FileProtocols.json.encodeToString(source)}
                val decision=runBlocking{db.fileWorkflowDao().decisions(book).first().single()}
                val original=after.events.first{e->after.storedPostings.any{it.id==decision.postingId&&it.eventId==e.id}}
                runBlocking{repo.saveEntry(book,EntryDraft(id=original.id,accountId=account.account_id,categoryId=original.categoryId,amount="1300",date=original.date,merchant=original.merchant))}
                ui.waitUntil(10000){ui.onAllNodesWithTag("statement-status-${diff.row_id}").fetchSemanticsNodes().isNotEmpty()}
                ui.onNodeWithTag("statement-status-${diff.row_id}").assertTextEquals("元記録が変わり、再照合待ち")
                click("statement-candidates-${diff.row_id}");shot("objects-ja-changed-decision")
                click("statement-unlink-difference")
                runBlocking{repo.saveEntry(book,EntryDraft(id=original.id,accountId=account.account_id,categoryId=original.categoryId,amount="1200",date=original.date,merchant=original.merchant))}
                val partial=source.copy(file_id=repo.id(),complete=false,issues=listOf("转换缺行・要確認"),rows=listOf(source.rows.last().copy(row_id=repo.id(),settlement=FileMoney("JPY","999"))))
                val partialPreview=runBlocking{repo.previewStatement(book,FileProtocols.json.encodeToString(partial))}
                assertEquals(listOf(batch),partialPreview.overlappingBatchIds)
                val partialBatch=runBlocking{repo.saveStatement(book,partialPreview,acceptOverlap=true)}
                ui.runOnIdle{vm.selectedStatementBatch.value=partialBatch}
                val partialReview=state().reviewStatements(runBlocking{db.fileWorkflowDao().statements(book).first()},runBlocking{db.fileWorkflowDao().decisions(book).first()}).first{it.file.file_id==partial.file_id}
                assertTrue(partialReview.result.ledgerOnly.isEmpty());assertTrue(partialReview.result.uncovered.isNotEmpty())
                click("statement-delete");click("statement-delete-confirm")
                ui.waitUntil(10000){!vm.busy.value&&runBlocking{db.fileWorkflowDao().statements(book).first().size==1}}
                ui.runOnIdle{vm.selectedStatementBatch.value=source.file_id}
            }
            click("statement-delete");click("statement-delete-confirm")
            ui.waitUntil(10000){!vm.busy.value&&runBlocking{db.fileWorkflowDao().statements(book).first().isEmpty()}}
            assertTrue(runBlocking{db.fileWorkflowDao().decisions(book).first().isEmpty()})
            assertEquals(4,state().events.size);assertEquals(6300L,state().balance(state().accounts.single()))
            println("Statement file workflow passed: $theme $language; saved statement → exact 1:1/manual difference → normal supplement → duplicate rejected; ledger preserved on deletion")
        }
    }
    private fun confirmNormalEntryReview() {
        ui.waitUntil(10000){!vm.busy.value&&vm.entryReview.value!=null}
        ui.onNode(hasText("確認して保存")and hasClickAction()).performScrollTo().performClick()
        ui.waitUntil(10000){!vm.busy.value&&vm.entry.value==null&&vm.entryReview.value==null}
    }
    @Test fun actualSettlementTransferAndBillInfoUseNormalExplicitReviews() {
        val ids=runBlocking {
            val b=repo.createBook("実計上と請求",Currency.JPY,theme="objects",language="ja")
            repo.updateBook(repo.dao.book(b)!!.copy(initialized=true))
            val bank=repo.saveAccount(b,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
            val target=repo.saveAccount(b,"別銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,0),"2026-10-01")
            val category=repo.saveCategory(b,"生活",EntryKind.EXPENSE,null)
            val fx=repo.saveEntry(b,EntryDraft(accountId=bank,categoryId=category,originalCurrency=Currency.CNY,amount="100.00",accountAmount="2000",baseAmount="2000",date="2026-10-14",settlementDate="2026-10-15",settlementConfirmed=false,merchant="外貨購入"))
            vm.preferences.select(b);listOf(b,bank,target,fx)
        }
        val book=ids[0];val bank=ids[1];val target=ids[2];val fx=ids[3]
        fun state()=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
        fun source(account:String,row:StatementRow?,info:StatementInfo?=null)=StatementFile("yohaku.statement",1,repo.id(),book,account,"JPY","2026-10-01","2026-10-31","posted_date",false,"実際の明細",emptyList(),listOfNotNull(row),info)
        fun save(file:StatementFile)=runBlocking{repo.saveStatement(book,repo.previewStatement(book,FileProtocols.json.encodeToString(file)))}
        ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book}
        val row=StatementRow(repo.id(),"2026-10-15","out",FileMoney("JPY","2150"),"外貨購入",original=FileMoney("CNY","100.00"),occurred_date="2026-10-14",posted_date="2026-10-15")
        val file=source(bank,row);save(file)
        ui.runOnIdle{vm.selectedStatementBatch.value=file.file_id;vm.navigate("statements")}
        click("statement-candidates-${row.row_id}");click("statement-confirm-actual-$fx")
        ui.waitUntil(10000){vm.entry.value?.id==fx}
        assertFalse(state().settlements.single().confirmed);assertEquals(10000L,state().balance(state().accounts.first{it.id==bank}))
        click("entry-save-button")
        ui.waitUntil(10000){vm.entryReview.value!=null}
        assertFalse(state().settlements.single().confirmed)
        confirmNormalEntryReview()
        var snapshot=state()
        assertEquals(1,snapshot.events.size);assertEquals(10000L,snapshot.events.single().amountMinor)
        assertEquals(2150L,snapshot.events.single().baseMinor);assertEquals(2000L,snapshot.settlements.single().expectedAccountMinor)
        assertEquals(7850L,snapshot.balance(snapshot.accounts.first{it.id==bank}))
        assertEquals(1,snapshot.matchStatements(runBlocking{db.fileWorkflowDao().statements(book).first()})[file.file_id]!!.exact.size)
        shot("objects-ja-actual-fx")
        val transfer=runBlocking{repo.commitEntryReview(book,repo.previewEntry(book,EntryDraft(kind=EntryKind.TRANSFER,accountId=bank,toAccountId=target,amount="1000",toAmount="1000",date="2026-10-14",toDate="2026-10-16",fromConfirmed=true,toConfirmed=false)))!!}
        val arrival=StatementRow(repo.id(),"2026-10-16","in",FileMoney("JPY","1000"),"振替入金",occurred_date="2026-10-14",posted_date="2026-10-16",event_hint="transfer")
        val transferFile=source(target,arrival);save(transferFile)
        ui.runOnIdle{vm.selectedStatementBatch.value=transferFile.file_id}
        click("statement-candidates-${arrival.row_id}");click("statement-confirm-actual-$transfer")
        ui.onNode(hasText("振替の変更を保存")and hasClickAction()).performScrollTo().performClick();confirmNormalEntryReview()
        snapshot=state()
        assertEquals(2,snapshot.events.size);assertEquals(6850L,snapshot.balance(snapshot.accounts.first{it.id==bank}));assertEquals(1000L,snapshot.balance(snapshot.accounts.first{it.id==target}))
        val posted=snapshot.reconciliationPostings().filter{it.eventId==transfer}
        val result=snapshot.matchStatements(runBlocking{db.fileWorkflowDao().statements(book).first()})[transferFile.file_id]!!
        assertEquals(listOf(posted.first{it.accountId==target}.postingId),result.exact.map{it.postingId})
        assertTrue(result.ledgerOnly.isEmpty())
        val card=runBlocking{repo.saveAccount(book,"カード",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,1000),"2026-10-01")}
        ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.accounts?.any{it.id==card}==true}
        val plan=runBlocking{repo.commitManualFinancialPlan(repo.previewManualFinancialPlan(book,ManualFinancialPlanInput(card,"既知の請求",FinanceMode.SINGLE,listOf(KnownBillInput("2026-11-27",1000,1000,0,bank)),opening=true)))!!}
        val bill=runBlocking{db.financialDao().bills(book,plan).single()}
        val billFile=source(card,null,StatementInfo(FileMoney("JPY","1000"),FileMoney("JPY","50"),FileMoney("JPY","1050"),"2026-11-28"));save(billFile)
        assertEquals(1000L,runBlocking{db.financialDao().bills(book,plan).single().totalMinor})
        ui.runOnIdle{vm.selectedStatementBatch.value=billFile.file_id}
        click("statement-confirm-bill-info");click("statement-bill-${bill.id}");click("finance-bill-preview")
        ui.waitUntil(10000){!vm.busy.value&&vm.financialBillReview.value!=null}
        assertEquals(1050L,vm.financialBillReview.value!!.bill.totalMinor)
        assertEquals(1000L,runBlocking{db.financialDao().bills(book,plan).single().totalMinor})
        click("finance-bill-confirm")
        ui.waitUntil(10000){!vm.busy.value&&runBlocking{db.financialDao().bills(book,plan).single().totalMinor==1050L}}
        val confirmed=runBlocking{db.financialDao().bills(book,plan).single()}
        assertEquals(50L,confirmed.feeMinor);assertEquals(1000L,confirmed.principalMinor);assertEquals("2026-11-28",confirmed.date)
        snapshot=state();assertEquals(2,snapshot.events.size);assertEquals(6850L,snapshot.balance(snapshot.accounts.first{it.id==bank}))
        println("Statement actual confirmations passed: FX keeps original/estimate; transfer confirms one side; bill info only enters explicit normal bill review")
    }

    @Test fun typeHandoffsKeepExistingDraftAndUseRefundTransferAndRepaymentForms() {
        val ids=runBlocking {
            val b=repo.createBook("補記の種類",Currency.JPY,theme="journal",language="ja")
            repo.updateBook(repo.dao.book(b)!!.copy(initialized=true))
            val bank=repo.saveAccount(b,"銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,10000),"2026-10-01")
            val target=repo.saveAccount(b,"別銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,0),"2026-10-01")
            val card=repo.saveAccount(b,"カード",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,1000),"2026-10-01")
            val category=repo.saveCategory(b,"生活",EntryKind.EXPENSE,null)
            val expense=repo.saveEntry(b,EntryDraft(accountId=bank,categoryId=category,amount="500",date="2026-10-14",merchant="元購入"))
            vm.preferences.select(b);listOf(b,bank,target,card,expense)
        }
        val book=ids[0]
        fun state()=runBlocking{repo.snapshot(repo.dao.book(book)!!).first()}
        ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book&&vm.financialState.value?.book?.id==book}
        val outgoing=StatementRow(repo.id(),"2026-10-15","out",FileMoney("JPY","1000"),"支払",occurred_date="2026-10-14",posted_date="2026-10-15")
        val refund=outgoing.copy(row_id=repo.id(),direction="in",settlement=FileMoney("JPY","200"),summary="返金")
        val file=StatementFile("yohaku.statement",1,repo.id(),book,ids[1],"JPY","2026-10-01","2026-10-31","posted_date",false,"銀行",emptyList(),listOf(outgoing,refund))
        runBlocking{repo.saveStatement(book,repo.previewStatement(book,FileProtocols.json.encodeToString(file)));repo.saveDraft(book,EntryDraft(amount="77",merchant="残した下書き"))}
        ui.waitUntil(10000){(vm.state.value as? AppState.Ready)?.snapshot?.draft!=null}
        ui.runOnIdle{vm.selectedStatementBatch.value=file.file_id;vm.navigate("statements")}
        fun choose(row:StatementRow,index:Int) {click("statement-supplement-${row.row_id}");click("statement-supplement-kind");click("select-option-記録の種類-$index")}
        choose(outgoing,2);click("statement-start-supplement")
        ui.onNode(hasText("既存の下書きを続ける")and hasClickAction()).assertExists()
        assertEquals("77",state().draft!!.amount);assertEquals(1,state().events.size)
        shot("journal-ja-existing-draft")
        ui.onNode(hasText("下書きを破棄して明細フォームを開く")and hasClickAction()).performScrollTo().performClick()
        ui.waitUntil(10000){!vm.busy.value&&vm.entry.value?.kind==EntryKind.TRANSFER}
        assertEquals(ids[1],vm.entry.value!!.accountId);assertEquals("1000",vm.entry.value!!.amount);assertNull(vm.entry.value!!.toAccountId)
        ui.runOnIdle{vm.leaveEntry(book,false,"statements")}
        ui.waitUntil(10000){vm.entry.value==null}
        choose(refund,3)
        click("select-trigger-元の支出と関連付け")
        click("select-option-元の支出と関連付け-1");click("statement-start-supplement")
        ui.waitUntil(10000){vm.entry.value?.kind==EntryKind.REFUND}
        assertEquals(ids[4],vm.entry.value!!.originalEventId);assertEquals("200",vm.entry.value!!.amount)
        ui.runOnIdle{vm.leaveEntry(book,false,"statements")}
        ui.waitUntil(10000){vm.entry.value==null}
        choose(outgoing,3)
        click("select-trigger-債務口座");click("select-option-債務口座-1")
        click("statement-start-supplement")
        ui.onNodeWithText("実際の支払と返済",substring=true).assertExists();shot("journal-ja-repayment-handoff")
        assertEquals(1,state().events.size);assertEquals(9500L,state().balance(state().accounts.first{it.id==ids[1]}))
        println("Statement typed handoffs passed: existing draft explicitly retained/discarded; transfer/refund normal unsaved forms; repayment financial draft host; no automatic facts")
    }

}
