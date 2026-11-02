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
                val plan=commandCardPlan(book,bank,cloud.commands.registry.catalogDigest,exported.contextDigest)
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
        }
    }
}
