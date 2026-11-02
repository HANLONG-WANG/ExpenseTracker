package com.yohaku.expensetracker.ui.features.command

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yohaku.expensetracker.data.command.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.features.AppViewModel
import com.yohaku.expensetracker.ui.features.RecordFacts
import com.yohaku.expensetracker.ui.features.receiptCurrencyText
import com.yohaku.expensetracker.ui.theme.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*

@Composable internal fun CommandExecutionMode(step:StepEffect) {
    if(step.mode=="transactional")return
    YText(if(step.mode=="interactive")word("需要你在本机操作","端末での操作が必要")else word("提交后执行","保存後に実行"),12,
        Modifier.testTag("command-mode-${step.stepId}"),color=LocalYohaku.current.accent,bold=true)
    if(step.objects.any{it.type=="followup"&&(it.after as? JsonObject)?.get("mode")==JsonPrimitive("interactive")})
        YText(word("系统权限：需要你在本机操作","システム権限：端末での操作が必要"),12,color=LocalYohaku.current.warm)
    YText(if(LocalLanguage.current=="ja")step.undo.ja else step.undo.zh,11,color=LocalYohaku.current.muted)
}

@Composable internal fun CommandFollowups(planId:String,vm:AppViewModel,focusOnAppear:Boolean=false) {
    val commands=vm.cloud?.commands?:return
    val rows by remember(planId){commands.effects(planId)}.collectAsStateWithLifecycle(emptyList())
    val plan by remember(planId){commands.observePlan(planId)}.collectAsStateWithLifecycle(null)
    val steps=remember(plan?.confirmationJson){plan?.let{commands.confirmation(it)}?.steps.orEmpty()}
    if(rows.isEmpty())return
    val requester=remember{BringIntoViewRequester()}
    LaunchedEffect(planId,focusOnAppear){if(focusOnAppear){withFrameNanos{};requester.bringIntoView()}}
    Column(Modifier.bringIntoViewRequester(requester)) {
        CommandCard(null,word("后续步骤","後続の処理"),"command-followup-status",followup=true) {
            YText(word("账本已提交；在这里重试或继续本机操作。","帳簿は保存済みです。ここで再試行・端末操作を続けられます。"),12)
            rows.forEach{row->key(row.id){CommandFollowupRow(row,vm,steps.indexOfFirst{it.stepId==row.stepId}.takeIf{it>=0}?.plus(1))}}
        }
    }
}
@Composable private fun CommandFollowupRow(row:CommandEffectEntity,vm:AppViewModel,number:Int?) {
    val commands=vm.cloud!!.commands
    val payload=remember(row.payloadJson){commands.followups.payload(row)}
    val busy by vm.busy.collectAsStateWithLifecycle()
    val context=LocalContext.current
    var details by rememberSaveable(row.id){mutableStateOf(false)}
    LaunchedEffect(row.status){if(row.status=="DONE")details=false}
    val title=(number?.let{commandStepName(it,payload.command)}?:commandName(payload.command))+
        if(payload.kind=="PERMISSION")word(" · 系统权限"," · システム権限")else""
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()){uri->
        if(uri!=null)vm.execute{commands.followups.selectExportDestination(row.id,uri)}
    }
    val permissions=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){result->
        if(result.values.any{it})vm.execute{commands.followups.completeInteraction(row.id)}
    }
    Column(Modifier.fillMaxWidth().testTag("command-followup-${row.stepId}-${payload.kind}"),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        YText(title,14)
        Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            YText(when(row.status){"PENDING"->word("待执行","実行待ち");"RUNNING"->word("进行中","実行中");"DONE"->word("完成","完了");"RETRY"->word("失败，可重试","失敗・再試行可能");else->word("等待你操作","操作をお待ちしています")},12,
                Modifier.weight(1f).testTag("command-followup-state-${row.stepId}-${payload.kind}"),color=if(row.status=="RETRY")LocalYohaku.current.warm else LocalYohaku.current.accent)
            if(row.status=="RETRY") {
                YButton(word("重试","再試行"),{if(payload.kind=="EXPORT")details=true else vm.execute{commands.followups.retry(row.id)}},Modifier.testTag("command-followup-retry-${row.stepId}"),enabled=!busy)
                YLink(word("详情","詳細"),{details=true})
            }
            if(row.status=="WAITING")YButton(word("继续","続ける"),{details=true},Modifier.testTag("command-followup-continue-${row.stepId}-${payload.kind}"),enabled=!busy)
        }
    }
    if(details)YSheet(title,{details=false}) {
        payload.input["name"]?.jsonPrimitive?.contentOrNull?.let{YText(it,12)}
        row.lastError?.let{YText(localizedCommand(it),11,color=LocalYohaku.current.warm)}
        if(row.status=="RETRY"&&payload.kind=="EXPORT")YText(word("重试会重写同一目标文件，请先检查现有文件。","再試行は同じ保存先ファイルを上書きします。先に現在のファイルを確認してください。"),11,color=LocalYohaku.current.warm)
        if(row.status=="RETRY")YButton(word("重试此步骤","この処理を再試行"),{vm.execute{commands.followups.retry(row.id)}},Modifier.testTag("command-followup-retry-detail-${row.stepId}"),enabled=!busy)
        if(row.status=="WAITING")when(payload.kind) {
            "DRAFT"->{
                YText(word("已保存为草稿；请在表单里补全并亲自保存。","下書きとして保存しました。フォームで補完してご自身で保存してください。"),12)
                YButton(word("打开草稿继续记账","下書きを開いて続ける"),{
                    var draftSnapshot:BookSnapshot?=null
                    vm.execute(onSuccess={draftSnapshot?.let(vm::resumeEntry)}) {
                        val s=vm.repository.snapshot(requireNotNull(vm.repository.dao.book(payload.bookId))).first()
                        require(s.draft?.mediaSession==payload.input["mediaSession"]?.jsonPrimitive?.content){"草稿已处理或改变，请从本机草稿入口继续 / 下書きは処理済みか変更されています。端末の下書きから続けてください"}
                        vm.preferences.select(payload.bookId);draftSnapshot=s
                        commands.followups.completeInteraction(row.id)
                    }
                },Modifier.testTag("command-followup-open-draft"),enabled=!busy)
                YLink(word("已在本机处理，结束引导","端末で対応済み・案内を終了"),{vm.execute{commands.followups.completeInteraction(row.id)}})
            }
            "EXPORT"->{
                YText(word("选择保存位置后写入文件；已导出的文件不能整批撤销。","保存先を選んでファイルを書き出します。出力したファイルは一括取消できません。"),12)
                YButton(word("选择位置并导出","保存先を選んで書き出す"),{picker.launch(null)},Modifier.testTag("command-followup-export"),enabled=!busy)
            }
            "PERMISSION"->{
                val location=payload.input["permission"]==JsonPrimitive("LOCATION")
                YText(if(location)word("仅在记账时使用前台定位。系统权限由你选择。","記録時の前景位置情報のみ使用します。システム権限はご自身で選択してください。")else word("允许系统通知后，提醒设置才能通知你。","システム通知を許可すると、設定した通知を受け取れます。"),12)
                YButton(word("检查并申请权限","権限を確認して申請"),{
                    val required=if(location)arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION)else if(Build.VERSION.SDK_INT>=33)arrayOf(Manifest.permission.POST_NOTIFICATIONS)else emptyArray()
                    if(required.isEmpty()||required.any{ContextCompat.checkSelfPermission(context,it)==PackageManager.PERMISSION_GRANTED})vm.execute{commands.followups.completeInteraction(row.id)}
                    else permissions.launch(required)
                },enabled=!busy)
                YLink(word("保留当前权限并结束","現在の権限のまま終了"),{vm.execute{commands.followups.completeInteraction(row.id)}})
            }
        }
    }
}

internal val workflowTypes=setOf("preference","analysisPreference","statementAssociation","statementRow","receiptProducts","receiptSource","receipt","receiptImage","attachment","csvBatch","csvEntry","drafts","entryDraft","followup")
internal val workflowFieldLabels=mapOf(
    "activeBook" to ("当前账本" to "現在の帳簿"),"automaticRates" to ("自动汇率" to "自動レート取得"),"photoOriginal" to ("照片保存方式" to "写真の保存方法"),"location" to ("记账时定位" to "記録時の位置情報"),"reminders" to ("提醒" to "通知"),
    "enabled" to ("开启提醒" to "通知を有効にする"),"deadline" to ("变更截止提醒" to "変更期限の通知"),"recurring" to ("周期账单提醒" to "定期請求の通知"),"budget" to ("预算提醒" to "予算の通知"),"funds" to ("资金缺口提醒" to "資金不足の通知"),"repaymentDays" to ("还款提前天数" to "返済の何日前"),"deadlineDays" to ("截止提前天数" to "期限の何日前"),"recurringDays" to ("周期提前天数" to "定期支払の何日前"),"time" to ("提醒时间" to "通知時刻"),
    "modulesJson" to ("分析模块顺序" to "分析項目の順序"),"pinnedJson" to ("置顶分析模块" to "固定する分析項目"),"sourceSummary" to ("流水摘要" to "明細の摘要"),"sourceDate" to ("流水日期" to "明細の日付"),"summary" to ("流水摘要" to "明細の摘要"),"direction" to ("资金方向" to "資金の方向"),"postedDate" to ("实际入账日" to "実計上日"),"occurredDate" to ("发生日" to "利用日"),"postingJson" to ("关联入账依据" to "関連計上の根拠"),
    "record_revision" to ("原记账版本" to "元記録の版"),"receipt_revision" to ("小票图片版本" to "レシート画像の版"),"recordJson" to ("商品明细" to "商品明細"),"image_ids" to ("来源图片" to "元画像"),"items" to ("商品" to "商品"),"quantity" to ("数量" to "数量"),"unit" to ("单位" to "単位"),"unit_price" to ("单价" to "単価"),"line_amount" to ("商品金额" to "商品金額"),"receipt_total" to ("票面合计" to "レシート合計"),"adjustments" to ("税费与折扣" to "税・割引"),"included_in_items" to ("已含于商品行" to "商品行に含む"),"uncertain_fields" to ("不确定字段" to "不確かな項目"),
    "requestedMedia" to ("本机补充方式" to "端末での補完方法"),"source_ref" to ("来源行" to "元の行"),"fileName" to ("文件名" to "ファイル名"),"importedCount" to ("导入笔数" to "取込件数"),"skippedCount" to ("跳过行数" to "スキップ行数"),"rowCount" to ("源文件行数" to "元ファイルの行数"),"size" to ("文件字节数" to "ファイルのバイト数"),"mime" to ("文件格式" to "ファイル形式"),"permission" to ("系统权限" to "システム権限"),"amount" to ("金额" to "金額"),"originalCurrency" to ("原币种" to "元の通貨"))

@Composable internal fun WorkflowSummary(e:ObjectEffect,s:BookSnapshot,model:ConfirmationModel) {
    val a=e.before as? JsonObject;val b=e.after as? JsonObject
    if(e.type=="preference") {
        val key=e.key
        YText(fieldName(key)+"："+fieldValue((a?.get(key)?:JsonNull) to null,key,s.book.currency,s,model)+" → "+fieldValue((b?.get(key)?:JsonNull) to null,key,s.book.currency,s,model),13)
    }else if(e.type in setOf("drafts","entryDraft")) {
        val before=(a?.get("json") as? JsonObject)?:a;val after=(b?.get("json") as? JsonObject)?:b
        if(e.type=="drafts"&&e.after==JsonNull)YText(word("将移除已保存的记账草稿","保存済みの記録下書きを削除します"),13)
        YText(word("记账草稿：","記録の下書き：")+((after?:before)?.get("merchant")?.jsonPrimitive?.contentOrNull.orEmpty()),13)
        if(before?.get("categoryId")!=after?.get("categoryId"))RecordFacts(listOf(fieldName("categoryId") to
            (fieldValue((before?.get("categoryId")?:JsonNull) to null,"categoryId",s.book.currency,s,model)+" → "+fieldValue((after?.get("categoryId")?:JsonNull) to null,"categoryId",s.book.currency,s,model))))
    }else if(e.type=="receiptProducts") {
        val record=(b?.get("recordJson")?:a?.get("recordJson")) as? JsonObject
        YText(word("商品明细","商品明細"),14)
        val currency=record?.get("currency")?.jsonPrimitive?.contentOrNull?:s.book.currency
        record?.get("items")?.jsonArray?.take(3)?.forEach{item->
            val v=item.jsonObject
            YText((v["name"]?.jsonPrimitive?.contentOrNull?:word("名称未知","名称不明"))+" · "+(v["quantity"]?.jsonPrimitive?.contentOrNull?:"—")+" · "+(v["line_amount"]?.jsonPrimitive?.contentOrNull?.let{receiptCurrencyText(it,currency)}?:word("金额未知","金額不明")),12)
        }
    }else {
        YText(when(e.type){"analysisPreference"->word("分析模块与置顶已调整","分析項目と固定表示を変更");"statementAssociation"->if(e.after==JsonNull)word("解除差异关联，保留流水与原记账","差異関連を解除し、明細と記録を保持")else word("差异已关联，仍不视为完全匹配","差異を関連付け、完全一致とは判定しません");"attachment"->word("附件：","添付：")+(b?.get("name")?.jsonPrimitive?.contentOrNull?:resolveName(e.key,s,model));"entryDraft"->word("待补完草稿：","補完待ち下書き：")+(b?.get("merchant")?.jsonPrimitive?.contentOrNull.orEmpty());"csvBatch"->word("保留批次历史与后续编辑","バッチの履歴と後の編集を保持");"receipt"->word("小票整理状态：","レシートの整理状態：")+fieldValue((a?.get("status")?:JsonNull) to null,"status",s.book.currency,s,model)+" → "+fieldValue((b?.get("status")?:JsonNull) to null,"status",s.book.currency,s,model);"receiptSource","receiptImage"->word("小票来源图片","レシートの元画像");"csvEntry"->word("导入记录归属","取込記録の所属");else->word("需要在本机继续","端末で続けてください")},13)
        if(e.type=="analysisPreference")for(key in listOf("modulesJson","pinnedJson"))RecordFacts(listOf(fieldName(key) to (fieldValue((a?.get(key)?:JsonArray(emptyList())) to null,key,s.book.currency,s,model)+" → "+fieldValue((b?.get(key)?:JsonArray(emptyList())) to null,key,s.book.currency,s,model))))
    }
}

/** User-facing projections omit storage/transport identities; comparison retains the original facts. */
internal fun workflowDisplayFacts(type:String,value:JsonElement):JsonElement {
    if(value !is JsonObject)return value
    return when(type) {
        "drafts","entryDraft"->JsonObject(((value["json"] as? JsonObject)?:value).filterKeys{it in setOf("kind","accountId","categoryId","amount","originalCurrency","accountAmount","baseAmount","date","settlementDate","merchant","note","toAccountId","toAmount","toDate","originalEventId","requestedMedia")})
        "statementAssociation"->JsonObject(value.filterKeys{it in setOf("sourceDate","sourceSummary","postingJson","note","invalidatedAt")})
        "attachment"->JsonObject(value.filterKeys{it in setOf("entryId","eventId","name","fileId","size","mime")})
        "csvBatch"->JsonObject(value.filterKeys{it in setOf("fileName","accountId","rowCount","importedCount","skippedCount")})
        else->value
    }
}

@Composable internal fun CommandResultCopy(row:CommandPlanEntity,vm:AppViewModel) {
    val context=LocalContext.current
    val busy by vm.busy.collectAsStateWithLifecycle()
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()){uri->
        if(uri!=null)vm.execute {
            val text=readCommandJson(row.planJson)
            com.yohaku.expensetracker.data.files.writeNewTreeDocument(context,uri,"yohaku-command-result.json","application/json"){it.write(text.toByteArray(Charsets.UTF_8))}
        }
    }
    YButton(word("另存原始方案文件","元のプランファイルを保存"),{picker.launch(null)},Modifier.testTag("command-export-original"),enabled=!busy)
}
