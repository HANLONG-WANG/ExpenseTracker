package com.yohaku.expensetracker

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.data.cloud.*
import com.yohaku.expensetracker.data.command.*
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.features.*
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.time.*

@RunWith(AndroidJUnit4::class)
class CommandFlowTest {
    @get:Rule val ui=createComposeRule()
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db:YohakuDatabase
    private lateinit var r:LedgerRepository
    private lateinit var cloud:CloudRepository
    private lateinit var vm:AppViewModel
    private val sentAcks=java.util.concurrent.CopyOnWriteArrayList<String>()
    private val ackSchedules=java.util.concurrent.atomic.AtomicInteger(0)
    private val taskDirectories=mutableListOf<File>()
    private class Credentials:CloudCredentials {
        private val config=CloudConnectionConfig("test","https://example.test","test-device","test-platform","OAI-Sites-Authorization",1)
        override val connection=MutableStateFlow<CloudConnectionInfo?>(CloudConnectionInfo(config.baseUrl,config.id,1,true,false))
        override suspend fun credentials()=config
        override suspend fun save(config:CloudConnectionConfig) {}
        override suspend fun markBound() {}
        override suspend fun markNeedsReconnect() {}
        override suspend fun clear(){connection.value=null}
    }
    private fun api(config:CloudConnectionConfig)=CloudApi(config.baseUrl,config){url->
        object:java.net.HttpURLConnection(url) {
            private val body=java.io.ByteArrayOutputStream()
            override fun connect() {}
            override fun disconnect() {}
            override fun usingProxy()=false
            override fun getResponseCode()=200
            override fun getContentType()="application/json"
            override fun getOutputStream():java.io.OutputStream=body
            override fun getInputStream():java.io.InputStream {
                check(url.path.endsWith("/ack")){"Unexpected test request: ${url.path}"}
                sentAcks+=body.toString("UTF-8")
                return java.io.ByteArrayInputStream("{}".toByteArray())
            }
        }
    }
    @Before fun open() {
        db=Room.inMemoryDatabaseBuilder(context,YohakuDatabase::class.java).build()
        r=LedgerRepository(db,Clock.fixed(Instant.parse("2026-11-02T03:00:00Z"),ZoneId.of("Asia/Tokyo")))
        cloud=CloudRepository(context,r,Credentials(),apiFactory=::api,scheduleTransfer={},scheduleAcknowledgements={ackSchedules.incrementAndGet();Unit},commandReferenceSnapshot={emptyList()})
        vm=AppViewModel(r,AppPreferences(context),testReferenceRates(AppPreferences(context),r.clock),cloud=cloud)
        ui.setContent{NativeApp(vm)}
    }
    @After fun close(){vm.viewModelScope.cancel();db.close();taskDirectories.forEach{it.deleteRecursively()}}
    private fun tap(tag:String){ui.waitUntil(15000){!vm.busy.value&&ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()};ui.onNodeWithTag(tag).performScrollTo().performClick();ui.waitForIdle()}
    private fun shot(name:String) {
        if(!visualEnabled())return
        ui.waitForIdle();InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(350,5000)
        val file=File(context.getExternalFilesDir(null),"cloud-command-stage-1/$name.png");file.parentFile!!.mkdirs()
        file.outputStream().use{ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun submitReceiveReviewCheckConfirmAndHistory() {
        for((theme,language) in testLooks(this)) {
            val (book,bank)=runBlocking {
                val b=r.createBook("办理 $theme $language",Currency.JPY,theme,language,startDate="2026-11-01")
                r.updateBook(r.dao.book(b)!!.copy(initialized=true))
                val a=r.saveAccount(b,"扣款口座",AccountType.BANK,Currency.JPY,Money(Currency.JPY,100000),"2026-11-01")
                vm.preferences.select(b);b to a
            }
            ui.waitUntil(15000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book&&!vm.busy.value}
            ui.runOnIdle{vm.navigate("command-submit")}
            ui.waitUntil(15000){ui.onAllNodesWithTag("command-title").fetchSemanticsNodes().isNotEmpty()&&!vm.busy.value}
            ui.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("command-title"))).performTextReplacement("信用卡办理")
            ui.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("command-request"))).performScrollTo().performTextReplacement("根据截图建信用卡、规则和期初预测")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            ui.waitForIdle()
            ui.onNodeWithTag("command-upload-summary").performScrollTo();shot("$theme-$language-submit")
            ui.waitUntil(15000){ui.onAllNodes(hasTestTag("command-submit") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
            tap("command-submit")
            ui.waitUntil(15000){vm.route.value=="cloud"&&!vm.busy.value}
            val row=runBlocking {
                val task=cloud.tasks(book).first().single().task
                taskDirectories+=File(context.filesDir,"cloud/${task.localId}")
                assertEquals("general",task.type);assertEquals("command",task.purpose)
                val transfers=db.cloudDao().transfers(task.localId)
                assertEquals(setOf("yohaku-command-protocol-v1.md","yohaku-capabilities-v1.json","yohaku-ledger-context-v1.json"),transfers.map{it.name}.toSet())
                val exported=commandJson.decodeFromString<LedgerContextExport>(File(transfers.single{it.name=="yohaku-ledger-context-v1.json"}.path).readText())
                val plan=commandCardPlan(book,bank,cloud.commands.registry.catalogDigest,exported.contextDigest).withCommandCreditLimit()
                val claim=commandClaim(plan,localId=task.localId)
                val remote=CloudTask(claim.taskId,"general","ready",1,listOf(CloudFile("result-file","result","result.json",claim.text.toByteArray().size.toLong(),claim.sha256)),
                    listOf(CloudResult(claim.resultId,1,1,"result-file","ready",false,CloudValidation(emptyList(),emptyList()))),emptyList())
                db.cloudDao().updateTask(task.copy(taskId=claim.taskId,remoteStatus="ready",stage="SUBMITTED",inputRevision=1,remoteJson=cloudJson.encodeToString(remote)))
                // Fixed cloud result through the production receiving entry, with no website/network.
                cloud.receiveCommand(claim)
            }
            ui.runOnIdle{vm.openCommandPlan(row.planId)}
            ui.waitUntil(20000){!vm.busy.value&&ui.onAllNodesWithTag("command-step-forecast").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithTag("command-context").performScrollTo();shot("$theme-$language-preview")
            val model=runBlocking{cloud.commands.confirmation(cloud.commands.plan(row.planId))!!}
            assertTrue(model.requirements.any{it.kind==ConfirmationKind.USER_FACT})
            assertTrue(model.requirements.none{it.kind==ConfirmationKind.RISK})
            ui.onNodeWithTag("command-confirm").performScrollTo().assertIsNotEnabled()
            model.requirements.filter{it.kind!=ConfirmationKind.INFORMATION}.indices.forEach{tap("command-check-$it")}
            ui.onNodeWithTag("command-confirm").performScrollTo();shot("$theme-$language-checks")
            tap("command-confirm")
            ui.waitUntil(15000){!vm.busy.value}
            runBlocking {
                assertEquals("EXECUTED",cloud.commands.plan(row.planId).status)
                assertEquals(30000L,db.financialDao().graph(book).first()!!.bills.single().totalMinor)
                assertEquals(1,db.cloudDao().pendingAcks().count{it.localId==row.localId&&it.status=="imported"})
                ackSchedules.set(0)
                val restarted=CloudRepository(context,r,Credentials(),apiFactory=::api,scheduleTransfer={},scheduleAcknowledgements={ackSchedules.incrementAndGet();Unit},commandReferenceSnapshot={emptyList()})
                restarted.resumePending()
                assertTrue(ackSchedules.get()>0)
                assertFalse(restarted.sendPendingAcks())
                assertTrue(db.cloudDao().pendingAcks().none{it.localId==row.localId})
                assertTrue(sentAcks.any{commandJson.parseToJsonElement(it).jsonObject["status"]?.jsonPrimitive?.content=="imported"})
            }
            ui.runOnIdle{vm.navigate("command-history")}
            ui.waitUntil(10000){ui.onAllNodesWithTag("command-open-${row.planId}").fetchSemanticsNodes().isNotEmpty()}
            shot("$theme-$language-history")
            tap("command-undo-${row.planId}")
            ui.waitUntil(20000){!vm.busy.value&&ui.onAllNodesWithTag("command-undo-removals").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithTag("command-undo-removals").performScrollTo()
            shot("$theme-$language-card-undo-preview")
            tap("command-confirm")
            ui.waitUntil(15000){!vm.busy.value}
            runBlocking<Unit>{assertEquals("UNDONE",cloud.commands.plan(row.planId).status);assertTrue(db.financialDao().graph(book).first()!!.plans.isEmpty())}
            ui.runOnIdle{vm.navigate("command-history")}
            ui.waitUntil(10000){ui.onAllNodes(hasText(if(language=="ja")"取消済み"else"已撤销") and hasAnyAncestor(hasTestTag("history-${row.planId}"))).fetchSemanticsNodes().size==1}
            ui.onNodeWithTag("history-${row.planId}").performScrollTo()
            shot("$theme-$language-card-undo-history")
        }
    }
    @Test fun undoPreviewConfirmAndHistory() {
        for((theme,language) in testLooks(this)) {
            val (book,row)=runBlocking {
                val b=r.createBook("撤销 $theme $language",Currency.JPY,theme,language,startDate="2026-11-01")
                r.updateBook(r.dao.book(b)!!.copy(initialized=true))
                val bank=r.saveAccount(b,"生活口座",AccountType.BANK,Currency.JPY,Money(Currency.JPY,100000),"2026-11-01")
                val old=r.saveCategory(b,"日常 / 日々",EntryKind.EXPENSE,null)
                val next=r.saveCategory(b,"咖啡 / コーヒー",EntryKind.EXPENSE,null)
                repeat(20){r.saveEntry(b,EntryDraft(accountId=bank,categoryId=old,amount="500",date="2026-11-01",merchant="珈琲店 $it"))}
                val plan=commandCardPlan(b,bank,cloud.commands.registry.catalogDigest,r.commandExports(b,RecordScope()).context.contextDigest).copy(
                    message=if(language=="ja")"コーヒーの記録をまとめて分類"else"批量调整咖啡记录分类",
                    steps=listOf(CommandStep("recat","entry.update",1,encoded(EntryUpdateInput(EntryTargets(select=EntrySelector(category=old)),EntryCategorySet(next))).jsonObject)))
                val received=cloud.commands.receive(commandClaim(plan,"undo-$theme-$language",localId="undo-$theme-$language"),"https://example.test",plan.context_digest)
                val model=cloud.commands.preview(received.planId);cloud.commands.confirm(received.planId,commandChecks(model))
                vm.preferences.select(b);b to received
            }
            ui.waitUntil(15000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==book&&!vm.busy.value}
            ui.runOnIdle{vm.openCommandPlan(row.planId)}
            tap("command-undo-${row.planId}")
            ui.waitUntil(20000){!vm.busy.value&&vm.commandPlanId.value!=row.planId&&ui.onAllNodesWithTag("command-step-restore_objects").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithTag("command-category-restoration").performScrollTo()
            ui.onNodeWithText(if(language=="ja")"20 件のカテゴリを元に戻します"else"20 笔记录将恢复分类").assertIsDisplayed()
            val inSummary=hasAnyAncestor(hasTestTag("command-category-restoration"))
            ui.onAllNodes(hasText("珈琲店",substring=true) and inSummary).assertCountEquals(3)
            ui.onAllNodes(hasText("咖啡 / コーヒー → 日常 / 日々") and inSummary).assertCountEquals(3)
            shot("$theme-$language-undo-preview")
            tap("command-confirm")
            ui.waitUntil(15000){!vm.busy.value}
            runBlocking<Unit> {
                val original=cloud.commands.plan(row.planId)
                assertEquals("UNDONE",original.status);assertNotNull(original.undoneAt)
                assertEquals("EXECUTED",cloud.commands.plan(vm.commandPlanId.value!!).status)
                assertEquals(20,r.snapshot(r.dao.book(book)!!).first().events.count{event->
                    r.dao.category(book,event.categoryId!!)!!.name=="日常 / 日々"
                })
            }
            ui.runOnIdle{vm.navigate("command-history")}
            ui.waitUntil(10000){ui.onAllNodesWithTag("history-${row.planId}").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithTag("history-${row.planId}").performScrollTo()
            ui.onNode(hasText(if(language=="ja")"取消済み"else"已撤销") and hasAnyAncestor(hasTestTag("history-${row.planId}"))).assertExists()
            ui.onNodeWithText(if(language=="ja")"「コーヒーの記録をまとめて分類」を取り消す"else"撤销「批量调整咖啡记录分类」").assertExists()
            ui.onNodeWithTag("command-undo-${row.planId}").assertDoesNotExist()
            shot("$theme-$language-undo-history")
        }
    }

    @Test fun ledgerCombinationPreviewAcrossLooks() {
        for((theme,language) in testLooks(this)) {
            val row=runBlocking {
                val book=r.createBook("日常の帳簿",Currency.JPY,theme,language,startDate="2026-11-01")
                r.updateBook(r.dao.book(book)!!.copy(initialized=true))
                val account=r.saveAccount(book,"钱包・お財布",AccountType.CASH,Currency.JPY,Money(Currency.JPY,10000),"2026-11-01")
                vm.preferences.select(book)
                fun step(id:String,name:String,input:String)=CommandStep(id,name,1,commandJson.parseToJsonElement(input).jsonObject)
                val plan=CommandPlan("yohaku.command-plan",1,java.util.UUID.randomUUID().toString(),book,cloud.commands.registry.catalogDigest,r.commandExports(book,RecordScope()).context.contextDigest,
                    message=if(language=="ja")"日常の記録をまとめて作成"else"整理日常账本",steps=listOf(
                        step("category","category.save","""{"name":"午餐・ランチ","kind":"EXPENSE","favorite":true}"""),
                        step("merchant","merchant.save","""{"name":"駅前カフェ","note":"车站旁・駅のそば"}"""),
                        step("entry","entry.create","""{"account":"$account","category":{"ref":"category","output":"category"},"merchantId":{"ref":"merchant","output":"merchant"},"merchant":"駅前カフェ","amount":{"currency":"JPY","minor":900},"note":"午餐・ランチ"}""")))
                cloud.commands.receive(commandClaim(plan),"https://example.test",plan.context_digest).also{cloud.commands.preview(it.planId)}
            }
            ui.waitUntil(15000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==row.bookId&&!vm.busy.value}
            ui.runOnIdle{vm.openCommandPlan(row.planId)}
            ui.waitUntil(15000){ui.onAllNodesWithTag("command-step-entry").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithTag("command-step-entry").performScrollTo()
            shot("$theme-$language-ledger-preview")
            ui.onNodeWithTag("command-details-entry").performScrollTo().performClick()
            ui.waitForIdle()
            // The detail uses the same theme-specific RecordFacts layout and translated labels.
            ui.onAllNodesWithText(if(language=="ja")"記録"else"记录",substring=true).onFirst().assertExists()
            ui.runOnIdle{vm.navigate("home")}
        }
    }

    @Test fun planningCombinationPreviewAcrossLooks() {
        for((theme,language) in testLooks(this)) {
            val row=runBlocking {
                val b=r.createBook("旅行与日常・旅と日常",Currency.JPY,theme,language,startDate="2026-11-01")
                r.updateBook(r.dao.book(b)!!.copy(initialized=true))
                val a=r.saveAccount(b,"银行・銀行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,300000),"2026-11-01")
                val c=r.saveCategory(b,"餐饮・食事",EntryKind.EXPENSE,null)
                vm.preferences.select(b)
                fun step(id:String,name:String,input:String)=CommandStep(id,name,1,commandJson.parseToJsonElement(input).jsonObject)
                val p=CommandPlan("yohaku.command-plan",1,java.util.UUID.randomUUID().toString(),b,cloud.commands.registry.catalogDigest,r.commandExports(b,RecordScope()).context.contextDigest,
                    message=if(language=="ja")"月予算と京都旅行の確保、定期支払と入金予定を確認してください。"else"请核对月预算、京都旅行预留、周期支出与预计收入。",steps=listOf(
                        step("month","budget.save","""{"month":"2026-11","amount":{"currency":"JPY","minor":120000}}"""),
                        step("mode","activity.setMode","""{"enabled":true}"""),
                        step("activity","activity.save","""{"name":"京都旅行","start":"2026-11-02","end":"2026-11-05","budget":{"currency":"JPY","minor":50000},"categoryBudgets":[{"category":"$c","amount":{"currency":"JPY","minor":20000}}]}"""),
                        step("reserve","activityReserve.save","""{"activity":{"ref":"activity","output":"activity"},"name":"旅馆・宿泊","date":"2026-11-02","amount":{"currency":"JPY","minor":12000}}"""),
                        step("friend","friend.save","""{"name":"小林"}"""),
                        step("participant","activityParticipant.add","""{"activity":{"ref":"activity","output":"activity"},"friend":{"ref":"friend","output":"friend"}}"""),
                        step("treat","activityTreat.save","""{"activity":{"ref":"activity","output":"activity"},"payer":{"ref":"friend","output":"friend"},"name":"晚餐・夕食","date":"2026-11-02","total":{"currency":"JPY","minor":6000},"base":{"currency":"JPY","minor":6000},"shares":[{"friend":null},{"friend":{"ref":"friend","output":"friend"}}]}"""),
                        step("rule","recurrence.save","""{"name":"订阅・サブスク","account":"$a","category":"$c","frequency":"MONTHLY","anchorDate":"2026-11-02","amount":{"currency":"JPY","minor":1000}}"""),
                        step("income","expectedIncome.save","""{"name":"工资・給与","amount":{"currency":"JPY","minor":250000},"date":"2026-11-25","account":"$a"}""")))
                cloud.commands.receive(commandClaim(p),"https://example.test",p.context_digest).also{cloud.commands.preview(it.planId)}
            }
            ui.waitUntil(15000){(vm.state.value as? AppState.Ready)?.snapshot?.book?.id==row.bookId&&!vm.busy.value}
            ui.runOnIdle{vm.openCommandPlan(row.planId)}
            ui.waitUntil(15000){ui.onAllNodesWithTag("command-step-reserve").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithTag("command-step-mode").performScrollTo().assert(hasAnyDescendant(hasText(if(language=="ja")"いいえ → はい"else"否 → 是")))
            shot("$theme-$language-planning-mode-preview")
            ui.onNodeWithTag("command-step-reserve").performScrollTo()
            ui.onAllNodesWithText(if(language=="ja")"シーンの残額"else"活动余量",substring=true).onFirst().assertExists()
            shot("$theme-$language-planning-preview")
            ui.onNodeWithTag("command-details-reserve").performScrollTo().performClick()
            ui.waitForIdle()
            ui.onAllNodesWithText(if(language=="ja")"確保額"else"预留",substring=true).onFirst().assertExists()
            ui.runOnIdle{vm.navigate("home")}
        }
    }

}
