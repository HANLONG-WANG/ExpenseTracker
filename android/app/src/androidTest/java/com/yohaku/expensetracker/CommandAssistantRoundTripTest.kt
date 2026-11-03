package com.yohaku.expensetracker

import android.content.ContextWrapper
import androidx.core.content.FileProvider
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yohaku.expensetracker.data.cloud.*
import com.yohaku.expensetracker.data.command.*
import com.yohaku.expensetracker.data.db.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.domain.finance.*
import com.yohaku.expensetracker.domain.model.*
import com.yohaku.expensetracker.ui.features.command.resolveName
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** An opt-in, offline assistant workbench. Both invocations use the same on-disk Room database.
 * Only the HTTP transport is replaced; attachments and receive/preview use production paths.
 * Neither mode uses confirm, workers, real preferences or real cloud credentials. */
@RunWith(AndroidJUnit4::class)
class CommandAssistantRoundTripTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName="assistant-roundtrip.db"
    private val root get()=File(requireNotNull(context.getExternalFilesDir(null)),"assistant-roundtrip")
    private val privateRoot get()=File(context.filesDir,"assistant-roundtrip")
    private val preferenceFile get()=File(privateRoot,"assistant.preferences_pb")
    private val clock=Clock.fixed(Instant.parse("2026-11-02T03:00:00Z"),ZoneId.of("Asia/Tokyo"))
    private val pretty=Json { prettyPrint=true; encodeDefaults=true }
    private fun openDatabase()=Room.databaseBuilder(context,YohakuDatabase::class.java,databaseName).addMigrations(*ALL_MIGRATIONS).build()
    private fun mode(value:String)=assumeTrue("仅在 -e assistant $value 时运行",InstrumentationRegistry.getArguments().getString("assistant")==value)
    private class Credentials:CloudCredentials {
        private val config=CloudConnectionConfig("assistant-roundtrip","https://assistant-roundtrip.invalid","fixture-device","fixture-token","OAI-Sites-Authorization",1)
        override val connection=MutableStateFlow<CloudConnectionInfo?>(CloudConnectionInfo(config.baseUrl,config.id,1,true,false))
        override suspend fun credentials()=config
        override suspend fun save(config:CloudConnectionConfig) { error("往返工具不应修改连接") }
        override suspend fun markBound() { error("往返工具不应绑定设备") }
        override suspend fun markNeedsReconnect() { error("往返工具不应发起网络请求") }
        override suspend fun clear() { error("往返工具不应解除连接") }
    }
    private fun preferences(scope:CoroutineScope)=AppPreferences(context,PreferenceDataStoreFactory.create(scope=scope,produceFile={preferenceFile}))
    /** Replaces HTTP only. advance() itself creates the task, sends every byte, records the
     * returned file IDs/timestamps and validates the submitted file collection. */
    private class UploadTransport {
        private var task:CloudTask?=null
        fun api(config:CloudConnectionConfig)=CloudApi(config.baseUrl,config){url->
            object:HttpURLConnection(url) {
                private val body=ByteArrayOutputStream()
                private val response by lazy {
                    val value:JsonElement=when {
                        url.path=="/api/v1/tasks"&&requestMethod=="POST" -> {
                            val request=cloudJson.parseToJsonElement(body.toString("UTF-8")).jsonObject
                            check(request.getValue("type")==JsonPrimitive("general"))
                            task=CloudTask("fixture-upload-task","general","uploading",1,emptyList(),emptyList(),emptyList())
                            cloudJson.encodeToJsonElement(requireNotNull(task))
                        }
                        url.path=="/api/v1/tasks/fixture-upload-task/files"&&requestMethod=="POST" -> {
                            val current=requireNotNull(task);check(current.status=="uploading")
                            val name=URLDecoder.decode(requireNotNull(url.query).removePrefix("name="),"UTF-8")
                            val bytes=body.toByteArray()
                            val hash=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
                            check(hash==getRequestProperty("X-Content-SHA256"))
                            check(current.files.none{it.name==name})
                            val id="fixture-input-${current.files.size}"
                            task=current.copy(files=current.files+CloudFile(id,"input",name,bytes.size.toLong(),hash),inputRevision=current.inputRevision+1)
                            buildJsonObject{put("fileId",id)}
                        }
                        url.path=="/api/v1/tasks/fixture-upload-task/submit"&&requestMethod=="POST" -> {
                            task=requireNotNull(task).copy(status="queued")
                            cloudJson.encodeToJsonElement(requireNotNull(task))
                        }
                        url.path=="/api/v1/tasks/fixture-upload-task"&&requestMethod=="GET" -> cloudJson.encodeToJsonElement(requireNotNull(task))
                        else -> error("往返工具不支持此HTTP请求：${requestMethod} ${url.path}")
                    }
                    value.toString().toByteArray(Charsets.UTF_8)
                }
                override fun connect() {}
                override fun disconnect() {}
                override fun usingProxy()=false
                override fun getResponseCode()=200
                override fun getContentType()="application/json"
                override fun getOutputStream()=body
                override fun getInputStream()=response.inputStream()
            }
        }
    }
    private fun cloud(ledger:LedgerRepository,prefs:AppPreferences,upload:UploadTransport?=null)=CloudRepository(object:ContextWrapper(context) {
        override fun getFilesDir()=privateRoot
    },ledger,Credentials(),
        apiFactory={config->requireNotNull(upload){"verify不应发起HTTP请求"}.api(config)},scheduleTransfer={},scheduleAcknowledgements={},commandReferenceSnapshot={emptyList()},commandPreferences=prefs)

    @Test fun export()=runBlocking<Unit> {
        mode("export")
        check(!context.getDatabasePath(databaseName).exists()&&!preferenceFile.exists()&&!File(root,"session.json").exists()) {
            "往返夹具已存在。verify 会复用它；要重新导出请按 android/README.md 清理专用测试数据库和目录。"
        }
        check(root.isDirectory||root.mkdirs())
        val db=openDatabase()
        val preferenceJob=SupervisorJob()
        val prefs=preferences(CoroutineScope(preferenceJob+Dispatchers.IO))
        try {
            val ledger=LedgerRepository(db,clock,File(privateRoot,"media"))
            val book=seed(ledger)
            prefs.select(book)
            val service=cloud(ledger,prefs,UploadTransport())
            val source=File(context.cacheDir,"exports/assistant-roundtrip/roundtrip-source.txt")
            check(source.parentFile!!.isDirectory||source.parentFile!!.mkdirs())
            source.writeText("街角咖啡消费凭据\n2026-10-09，JPY 480，早餐咖啡。\n此文件为测试用原始资料，可以作为相应记录的普通附件。\n",Charsets.UTF_8)
            val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",source)
            val bundle=ledger.commandExports(book,RecordScope("dates","2026-10-01","2026-11-02"),prefs)
            val localId=service.prepareCommandTask(book,listOf(uri),"独立助手往返验收",
                "请根据用户另行提供的自然语言需求和本任务附件提出方案。测试账本的当前日期为2026-11-02，时区Asia/Tokyo。",bundle)
            service.advance(localId)
            val transfers=db.cloudDao().transfers(localId)
            check(transfers.all{it.fileId!=null&&it.uploadedAt!=null}){"真实上传流程未登记完成"}
            assertEquals(setOf("yohaku-command-protocol-v1.md","yohaku-capabilities-v1.json","yohaku-command-plan-v1.schema.json","yohaku-ledger-context-v1.json","yohaku-records-v1.json","roundtrip-source.txt"),transfers.map{it.name}.toSet())
            val attachments=File(root,"attachments");check(attachments.mkdirs())
            for(transfer in transfers) {
                val file=File(transfer.path)
                check(file.length()==transfer.size&&sha256(file)==transfer.sha256)
                file.copyTo(File(attachments,transfer.name))
                assertEquals(transfer.sha256,sha256(File(attachments,transfer.name)))
            }
            val task=requireNotNull(db.cloudDao().task(localId))
            check(task.stage=="SUBMITTED"&&task.taskId!=null)
            assertEquals(listOf("roundtrip-source.txt"),cloudJson.parseToJsonElement(task.requestJson).jsonObject.getValue("context").jsonObject.getValue("source_files").jsonArray.map{it.jsonPrimitive.content})
            File(root,"task-request.json").writeText(task.requestJson,Charsets.UTF_8)
            File(root,"session.json").writeText(buildJsonObject {
                put("version",1);put("database",databaseName);put("bookId",book);put("localId",localId)
                put("catalogDigest",service.commands.registry.catalogDigest);put("contextDigest",bundle.context.contextDigest)
                put("asOf",clock.instant().toString());put("zoneId",clock.zone.id)
            }.toString(),Charsets.UTF_8)
            check(File(root,"plans").mkdirs());check(File(root,"results").mkdirs())
            File(root,"README.txt").writeText(buildString {
                appendLine("独立助手往返验收（测试数据，不连接网站）")
                appendLine("将 attachments/ 中的全部文件作为一次任务的附件交给独立助手，再单独给自然语言需求。不要给它实现源码或其他测试产物。")
                appendLine("当前日期：2026-11-02；时区：Asia/Tokyo。账本、源文件、协议和能力均来自真实任务准备流程。")
                appendLine("测试数据库：${context.getDatabasePath(databaseName)}；测试偏好：$preferenceFile（均持久化，隔离用户主库和偏好；不要清除应用数据或卸载）。")
                appendLine("账本：$book；18笔2026-10实际记录，含分类不当的咖啡/地铁/日用品；银行及两张信用卡，其中一张已有两期分期；预算、周期规则和通用月计划。")
                appendLine()
                val descriptions=mapOf("yohaku-command-protocol-v1.md" to "结果格式、引用、确认和永久边界",
                    "yohaku-capabilities-v1.json" to "当前注册表完整命令、schema、示例及catalogDigest",
                    "yohaku-command-plan-v1.schema.json" to "从当前注册表生成的完整方案JSON Schema（全部命令及顶层结构）",
                    "yohaku-ledger-context-v1.json" to "白名单账本结构及contextDigest",
                    "yohaku-records-v1.json" to "用户选择2026-10-01至2026-11-02的实际记录",
                    "roundtrip-source.txt" to "用户明确选择的原始消费凭据；文件名可用于attachment.attachSource")
                transfers.forEach{appendLine("attachments/${it.name}：${descriptions.getValue(it.name)}；${it.size} bytes；SHA-256 ${it.sha256}")}
                appendLine()
                appendLine("README.txt、session.json、task-request.json、plans/、results/均为工具辅助文件，不是上传附件。task-request.json只是实际任务正文的留档。")
                appendLine("普通办理的四份自动附件必带，records和源文件按选择附带；月评实际初次上传为协议+能力+方案schema+结构+月汇总五份，不附逐笔或源文件。本夹具导出普通办理任务。")
                appendLine("每份返回JSON用不同plan_id，保存为plans/*.json，运行 -e assistant verify。不得改写book_id或两个digest。")
                appendLine("verify只领取和预览；报告在results/<文件名去掉.json>.txt，完整确认模型在对应.preview.json；不执行确认。")
                appendLine("重复verify复用同一夹具；同一plan_id的不同内容会按真实去重规则返回原方案，并在报告中标明。重新导出步骤见android/README.md。")
            },Charsets.UTF_8)
        } finally { preferenceJob.cancelAndJoin();db.close() }
    }

    private suspend fun seed(r:LedgerRepository):String {
        val book=r.createBook("东京生活 · 助手往返验收",Currency.JPY,startDate="2026-09-01",budgetMinor=90000)
        r.updateBook(requireNotNull(r.dao.book(book)).copy(initialized=true,setupStep=4))
        val bank=r.saveAccount(book,"生活银行",AccountType.BANK,Currency.JPY,Money(Currency.JPY,200000),"2026-09-01")
        val card=r.saveAccount(book,"家电分期卡",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,12000),"2026-11-01")
        r.saveAccount(book,"日常信用卡",AccountType.CREDIT,Currency.JPY,Money(Currency.JPY,8000),"2026-09-01")
        val categories=listOf("餐饮","交通","日用品","娱乐","其他").associateWith{r.saveCategory(book,it,EntryKind.EXPENSE,null)}
        val salary=r.saveCategory(book,"工资",EntryKind.INCOME,null)
        // 17 expenses plus salary. These deliberately wrong categories are visible in the export.
        val merchants=listOf("车站食堂","东京地铁","日用品超市","书店","街角咖啡")
        val kinds=listOf("餐饮","交通","日用品","娱乐","餐饮")
        for(index in 0 until 17) {
            val merchant=if(index==7)"街角咖啡"else merchants[index%merchants.size]
            val category=if(index in setOf(1,2,7,12))"其他"else kinds[index%kinds.size]
            r.saveEntry(book,EntryDraft(kind=EntryKind.EXPENSE,accountId=bank,categoryId=categories.getValue(category),
                originalCurrency=Currency.JPY,amount=if(index==7)"480"else (600+index*130).toString(),
                date="2026-10-${(index+2).toString().padStart(2,'0')}",merchant=merchant,note=if(index==7)"早餐咖啡"else "日常消费"))
        }
        r.saveEntry(book,EntryDraft(kind=EntryKind.INCOME,accountId=bank,categoryId=salary,originalCurrency=Currency.JPY,amount="260000",date="2026-10-25",note="十月工资"))
        check(r.commitBudget(r.previewBudget(book,"2026-10",categories.getValue("餐饮"),Money(Currency.JPY,30000))))
        check(r.commitBudget(r.previewBudget(book,"2026-10",categories.getValue("交通"),Money(Currency.JPY,10000))))
        r.saveRecurrence(book,RecurrenceInput("手机月费",bank,categories.getValue("其他"),Money(Currency.JPY,2980),Money(Currency.JPY,2980),false,RecurrenceFrequency.MONTHLY,"2026-10-20"))
        r.saveMonthlyPlan(book,"餐饮每月不超过30,000日元，交通不超过10,000日元。先留出50,000日元储蓄，再安排娱乐消费；每月核对信用卡分期。")
        val rule=commandJson.decodeFromString<FinancialRuleVersion>("""{"id":"fixture-rule","bookId":"$book","accountId":"$card","mode":"INSTALLMENT","effectiveFrom":"2026-11-01","zoneId":"Asia/Tokyo","billing":{"closingDay":31,"closingDayInCurrentBill":true,"paymentMonthOffset":1,"paymentDay":10,"paymentAccountId":"$bank"},"installment":{"supportedPeriods":[2],"feeMethod":"TOTAL_AMOUNT","totalFeeMinor":120,"principalRemainder":"LAST","feeRemainder":"FIRST","roundingMode":"HALF_UP"}}""")
        checkNotNull(r.commitFinancialRule(r.previewFinancialRule(book,card,rule)))
        val prediction=r.previewFinancialPrediction(book,FinancialPredictionInput(card,"家电两期分期",FinanceMode.INSTALLMENT,
            FinancialScheduleTerms("2026-11-01",listOf("2026-12-10","2027-01-10"),firstPeriodNumber=1,remainingFeeMinor=120),
            openingPrincipalMinor=12000,paymentAccountId=bank,currentArrangementConfirmed=true))
        check(prediction.calculation is Calculation.Ready){"夹具分期预测无法建立：${prediction.calculation}"}
        checkNotNull(r.commitFinancialPrediction(prediction))
        return book
    }

    @Test fun verify()=runBlocking<Unit> {
        mode("verify")
        check(context.getDatabasePath(databaseName).isFile){"缺少持久测试数据库，请先运行 -e assistant export"}
        check(preferenceFile.isFile){"缺少持久测试偏好，请重新导出夹具"}
        val session=commandJson.parseToJsonElement(File(root,"session.json").readText(Charsets.UTF_8)).jsonObject
        check(session.getValue("version").jsonPrimitive.int==1&&session.getValue("database").jsonPrimitive.content==databaseName)
        check(session.getValue("asOf").jsonPrimitive.content==clock.instant().toString()&&session.getValue("zoneId").jsonPrimitive.content==clock.zone.id)
        val localId=session.getValue("localId").jsonPrimitive.content
        val book=session.getValue("bookId").jsonPrimitive.content
        val files=requireNotNull(File(root,"plans").listFiles()).filter{it.isFile&&it.extension=="json"}.sortedBy{it.name}
        check(files.isNotEmpty()){"plans/ 中没有 *.json；请先 adb push 独立助手的方案"}
        val results=File(root,"results");check(results.isDirectory||results.mkdirs())
        val db=openDatabase()
        val preferenceJob=SupervisorJob()
        val prefs=preferences(CoroutineScope(preferenceJob+Dispatchers.IO))
        try {
            val ledger=LedgerRepository(db,clock,File(privateRoot,"media"));val service=cloud(ledger,prefs)
            val task=requireNotNull(db.cloudDao().task(localId)){"导出任务不存在，不能用新任务代替原附件上下文"}
            check(task.bookId==book)
            // Detect tampering/lost source files as tool errors, outside plan validation handling.
            for(transfer in db.cloudDao().transfers(localId)) {
                check(transfer.fileId!=null&&transfer.uploadedAt!=null){"夹具未完成上传，请用新版export重建；不得绕过附件校验"}
                check(sha256(File(transfer.path))==transfer.sha256)
                check(sha256(File(root,"attachments/${transfer.name}"))==transfer.sha256)
            }
            val before=CommandUndoJournal(ledger,book).snapshot()
            val preferenceValues=prefs.commandValues(book)
            val inputRevision=requireNotNull(task.inputRevision)
            val inputFiles=db.cloudDao().transfers(localId).map{CloudFile(requireNotNull(it.fileId),"input",it.name,it.size,it.sha256,it.mime)}
            for(file in files) {
                val report=StringBuilder("文件：${file.name}\n日期：${clock.instant()}（${clock.zone}）\n仅 receive → preview；不执行 confirm。\n")
                val previewFile=File(results,"${file.nameWithoutExtension}.preview.json")
                // A later failed attempt must not leave an old successful preview beside its report.
                if(previewFile.exists())check(previewFile.delete())
                val bytes=file.readBytes()
                val text=try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
                    catch(error:java.nio.charset.CharacterCodingException) {
                        report.appendLine("校验：未通过（无效 UTF-8）\n${error.javaClass.name}: ${error.message}")
                        File(results,"${file.nameWithoutExtension}.txt").writeText(report.toString(),Charsets.UTF_8)
                        continue
                    }
                val hash=sha256(text);val taskId=requireNotNull(task.taskId);val resultId="result-$hash"
                val claim=CloudClaim(localId,book,"general",taskId,resultId,1,hash,file,text,null,emptyList(),"command")
                // Simulate only downloaded server metadata, as CommandFlowTest does. The real
                // receiveCommand validates task/result association and the original task digest.
                val remote=CloudTask(taskId,"general","ready",inputRevision,
                    inputFiles+CloudFile("file-$hash","result",file.name,bytes.size.toLong(),hash),
                    listOf(CloudResult(resultId,1,inputRevision,"file-$hash","ready",false,CloudValidation(emptyList(),emptyList()))),emptyList())
                db.cloudDao().updateTask(task.copy(remoteStatus="ready",remoteJson=cloudJson.encodeToString(remote)))
                var model:ConfirmationModel?=null
                try {
                    val row=service.receiveCommand(claim)
                    when {
                        row.sha256!=hash -> report.appendLine("校验：未独立预览。真实领取按 plan_id 复用了已有方案（${row.planId}）；请为新的方案使用新的UUID。原方案状态：${row.status}")
                        row.planId.startsWith("invalid:") -> report.appendLine("校验：未通过\n失败原因（原文）：\n${row.error}")
                        row.status=="ANSWER" -> {
                            val answer=readCommandDocument<CommandPlan>(row.planJson)
                            report.appendLine("校验：通过；无步骤回答，无需预览或勾选。\n${answer.message}")
                            answer.questions.forEach{report.appendLine("待补充：$it")}
                        }
                        else -> { model=service.commands.preview(row.planId) }
                    }
                } catch(error:CancellationException) { throw error }
                catch(error:Exception) {
                    // The production validator's rejection is a result for the human reviewer.
                    // File IO, fixture construction, metadata and rollback checks stay outside.
                    report.appendLine("校验/预览：未通过\n失败原因（原文）：\n${error.javaClass.name}: ${error.message}")
                    report.appendLine(error.stackTraceToString())
                }
                model?.let{preview->
                    report.appendLine("输入校验：通过\n预览可执行：${preview.executable}\n统计口径：${preview.basis}")
                    preview.steps.forEachIndexed{index,step->
                        val title=commandStepTitle(index+1,step.command)
                        report.appendLine("${title.first} / ${title.second} [${step.mode}]；目标 ${step.targets.size} 个")
                        step.missing.forEach{report.appendLine("缺项（原文）：$it")}
                        step.risks.forEach{report.appendLine("风险（原文）：${encoded(it)}")}
                    }
                    preview.conflicts.forEach{report.appendLine("冲突（原文）：${encoded(it)}")}
                    preview.unknown.forEach{report.appendLine("未知（原文）：$it")}
                    val changes=preview.net.filter{it.before!=it.after}
                    report.appendLine("\n最终净影响：${changes.size} 个对象；${changes.groupingBy{it.type}.eachCount()}。Minor金额为相应currency的最小单位，null保持未知。")
                    val snapshot=ledger.snapshot(requireNotNull(ledger.dao.book(book))).first()
                    for(effect in changes) {
                        report.appendLine("- ${resolveName(effect.key,snapshot,preview)} [${effect.type}] ${effect.key}")
                        appendDiff(report,"",effect.before,effect.after)
                    }
                    report.appendLine("\n需要用户勾选（工具不代勾选）：")
                    val checks=preview.requirements.filter{it.kind!=ConfirmationKind.INFORMATION}
                    if(checks.isEmpty())report.appendLine("无")
                    checks.forEach{report.appendLine("[ ] ${it.kind} ${it.zh}\n    ${it.ja}\n    ${it.key}")}
                    report.appendLine("完整确认模型（包括逐期预测/数组内容）：${previewFile.name}")
                    previewFile.writeText(pretty.encodeToString(preview),Charsets.UTF_8)
                }
                // Any persisted ledger/preference change is a tool/application failure, not a bad plan.
                assertEquals("预览污染了持久测试账本：${file.name}",before,CommandUndoJournal(ledger,book).snapshot())
                assertEquals("预览写入了偏好：${file.name}",preferenceValues,prefs.commandValues(book))
                File(results,"${file.nameWithoutExtension}.txt").writeText(report.toString(),Charsets.UTF_8)
            }
        } finally { preferenceJob.cancelAndJoin();db.close() }
    }

    private fun appendDiff(report:StringBuilder,path:String,before:JsonElement,after:JsonElement) {
        if(before==after)return
        if(before is JsonObject&&after is JsonObject) {
            for(key in (before.keys+after.keys).sorted())appendDiff(report,if(path.isEmpty())key else "$path.$key",before[key]?:JsonNull,after[key]?:JsonNull)
        } else if(before is JsonArray||after is JsonArray) {
            report.appendLine("  $path：${(before as? JsonArray)?.size?:0} 项 → ${(after as? JsonArray)?.size?:0} 项，内容变化，明细见完整确认模型")
        } else report.appendLine("  ${path.ifEmpty{"对象"}}：$before → $after")
    }
}
