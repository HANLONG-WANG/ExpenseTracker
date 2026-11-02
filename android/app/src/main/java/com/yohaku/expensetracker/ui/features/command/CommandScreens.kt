package com.yohaku.expensetracker.ui.features.command

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yohaku.expensetracker.data.command.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.features.*
import com.yohaku.expensetracker.ui.theme.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

@Composable internal fun CommandEntry(vm:AppViewModel,connected:Boolean) {
    CloudHandoffStrip(word("云端办理","クラウド手続き"),word("说出需求，收到方案后在本机试运行，由你核对并确认。","要望を伝え、届いたプランを端末で試算。確認してから確定します。"),"command-entry") {
        YButton(word("提出办理需求","手続きを依頼"),{vm.navigate("command-submit")},Modifier.fillMaxWidth().testTag("command-new"),primary=true,enabled=connected)
        YLink(word("方案历史","プランの履歴"),{vm.navigate("command-history")},Modifier.testTag("command-history"))
    }
}

@Composable internal fun CommandSubmitScreen(s:BookSnapshot,vm:AppViewModel) {
    val cloud=vm.cloud?:return
    var title by rememberSaveable(s.book.id){mutableStateOf("")}
    var request by rememberSaveable(s.book.id){mutableStateOf("")}
    var mode by rememberSaveable(s.book.id){mutableStateOf("none")}
    var from by rememberSaveable(s.book.id){mutableStateOf(LocalDate.now(vm.repository.clock).withDayOfMonth(1).toString())}
    var through by rememberSaveable(s.book.id){mutableStateOf(LocalDate.now(vm.repository.clock).toString())}
    var account by rememberSaveable(s.book.id){mutableStateOf(s.accounts.firstOrNull()?.id)}
    var files by remember{mutableStateOf<List<Uri>>(emptyList())}
    var dateOwner by remember{mutableStateOf<Int?>(null)}
    var bundle by remember{mutableStateOf<CommandExportBundle?>(null)}
    var exportedScope by remember{mutableStateOf<RecordScope?>(null)}
    var exportError by remember{mutableStateOf<String?>(null)}
    val scope=RecordScope(mode,if(mode=="dates")from else null,if(mode=="dates")through else null,if(mode=="account")account else null)
    val busy by vm.busy.collectAsStateWithLifecycle()
    LaunchedEffect(s,scope) {
        bundle=null;exportedScope=null;exportError=null
        try {bundle=withContext(Dispatchers.IO){vm.repository.commandExports(s.book.id,scope)};exportedScope=scope}
        catch(e:CancellationException){throw e}
        catch(e:Exception){exportError=e.message?:"无法准备上传摘要 / 送信内容を準備できません"}
    }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){files=(files+it).distinct()}
    CommandHeading(word("把需求写下来。","やりたいことを書こう。"),word("先看上传范围，再交给助手。","送る範囲を確認してから、アシスタントへ。"),"01", "REQUEST")
    Panel {
        YField(word("任务名称","タスク名"),title,{title=it.take(150)},Modifier.testTag("command-title"))
        YField(word("希望办理什么","依頼したいこと"),request,{request=it.take(19800)},Modifier.testTag("command-request"),multiline=true)
        CloudPickedFiles(files,{files=files-it})
        YButton(word("添加截图或文件","画像・ファイルを追加"),{picker.launch(arrayOf("*/*"))},Modifier.testTag("command-files"),enabled=!busy)
    }
    Panel {
        YText(word("具体记录的上传范围","送信する記録の範囲"),16)
        YSelect(word("记录范围","記録の範囲"),mode,listOf("none" to word("不上传记录","記録を送らない"),"dates" to word("按日期范围","日付で指定"),"account" to word("按账户","口座で指定")),{mode=it},Modifier.testTag("command-record-scope"))
        if(mode=="dates")YDateFields(listOf(word("从","開始") to from,word("至","終了") to through),dateOwner,{dateOwner=it},{i,v->if(i==0)from=v else through=v})
        if(mode=="account")YSelect(word("账户","口座"),account,s.accounts.map{it.id as String? to it.name},{account=it},Modifier.testTag("command-record-account"))
    }
    CloudHandoffStrip(word("将上传的内容","送信する内容"),word("协议说明、能力清单和本账本结构总会附带。结构含账户余额、分类、商户与地点、朋友、模板、预算、周期、金融规则和计划、额度及活动资料。","プロトコル、対応機能一覧、帳簿の構成を必ず添付します。構成には口座残高・カテゴリ・お店と場所・友人・テンプレート・予算・定期予定・金融条件とプラン・利用枠・シーンが含まれます。"),"command-upload-summary") {
        YText(word("结构项目 ${bundle?.context?.items?.size?:0} 项 · 具体记录 ${bundle?.records?.records?.size?:0} 笔 · 所选文件 ${files.size} 份","構成 ${bundle?.context?.items?.size?:0} 件・記録 ${bundle?.records?.records?.size?:0} 件・選択ファイル ${files.size} 個"),13)
        if(mode=="dates")YText("$from → $through",12,number=true)
        if(mode=="account")YText(s.accounts.firstOrNull{it.id==account}?.name.orEmpty(),12)
        YText(word("所选文件按原样上传。未选择的具体记录、草稿和附件不会自动上传。","選んだファイルはそのまま送信します。未選択の記録・下書き・添付資料は自動送信しません。"),11,color=LocalYohaku.current.muted)
    }
    exportError?.let{YText(localizedCommand(it),12,color=LocalYohaku.current.warm)}
    YButton(word("上传并提交办理需求","アップロードして依頼"),{
        val ready=bundle?.takeIf{exportedScope==scope}?:return@YButton;val selected=files;val note=request;val name=title
        vm.execute(onSuccess={vm.navigate("cloud")}){cloud.prepareCommandTask(s.book.id,selected,name,note,ready)}
    },Modifier.fillMaxWidth().testTag("command-submit"),primary=true,enabled=!busy&&bundle!=null&&exportedScope==scope&&title.isNotBlank()&&request.isNotBlank())
}

@Composable internal fun CommandHistoryScreen(s:BookSnapshot,vm:AppViewModel) {
    val commands=vm.cloud?.commands?:return
    val history by remember(s.book.id){commands.history(s.book.id)}.collectAsStateWithLifecycle(emptyList())
    CommandHeading(word("每份方案，都有来处。","プランの歩みを残す。"),word("预览、执行与拒绝都保存在本机。","確認・実行・拒否の履歴は端末に保存します。"),"03","HISTORY")
    if(history.isEmpty())YEmpty(word("还没有收到办理方案。","まだ手続きプランは届いていません。"))
    history.forEachIndexed{index,row->CommandCard(index+1,commandStatus(row.status),"history-${row.planId}",history=true) {
        val plan=remember(row.planJson){runCatching{readCommandDocument<CommandPlan>(row.planJson)}.getOrNull()}
        YText(plan?.message?.take(180)?.ifBlank{word("办理方案","手続きプラン")}?:word("无法识别的方案","読み取れないプラン"),14)
        YText(word("${plan?.steps?.size?:0} 个步骤 · 结果版本 ${row.resultVersion}","${plan?.steps?.size?:0} ステップ・結果バージョン ${row.resultVersion}"),11,color=LocalYohaku.current.muted)
        row.error?.let{YText(localizedCommand(it),11,color=LocalYohaku.current.warm)}
        YLink(word("查看方案","プランを見る"),{vm.openCommandPlan(row.planId)},Modifier.testTag("command-open-${row.planId}"))
    }}
}

@Composable internal fun CommandPreviewScreen(s:BookSnapshot,vm:AppViewModel) {
    val commands=vm.cloud?.commands?:return
    val planId by vm.commandPlanId.collectAsStateWithLifecycle()
    val history by remember(s.book.id){commands.history(s.book.id)}.collectAsStateWithLifecycle(emptyList())
    val row=history.firstOrNull{it.planId==planId}
    val busy by vm.busy.collectAsStateWithLifecycle()
    val model=remember(row?.confirmationJson){row?.let{commands.confirmation(it)}}
    var checks by remember(model){mutableStateOf<Set<String>>(emptySet())}
    var details by remember(model){mutableStateOf<List<ObjectEffect>?>(null)}
    LaunchedEffect(planId) {planId?.let{id->vm.execute{val p=commands.plan(id);if(p.status=="PENDING"&&p.confirmationJson==null)commands.preview(id)}}}
    CommandHeading(word("先试运行，再决定。","試算してから、決める。"),if(row?.status=="EXECUTED")word("方案已在本机执行；回执由云端队列发送。","プランは端末で実行済み。受領通知は送信キューで処理します。")else word("账本尚未改变；下列数字来自本机。","帳簿はまだ変わりません。数値は端末で試算します。"),"02","REVIEW")
    if(row==null){YText(word("正在读取方案…","プランを読み込み中…"),13);return}
    val plan=remember(row.planJson){runCatching{readCommandDocument<CommandPlan>(row.planJson)}.getOrNull()}
    Panel {
        YText(commandStatus(row.status),12,color=LocalYohaku.current.accent)
        plan?.let{YText(it.message,15,lineHeightMultiplier=1.7f);it.questions.forEach{q->YText("• $q",13)}}
        row.error?.let{YText(localizedCommand(it),13,color=LocalYohaku.current.warm)}
    }
    if(model!=null) {
        Panel(Modifier.testTag("command-context")) {
            YText(word("试算口径","試算の基準"),15)
            YText("${model.context.asOfDate} · ${model.context.zoneId}\n→ ${model.context.throughDate}",12,number=true)
            YText(word("余额采用已确认入账；债务与额度按账户币种；预算按账本币种；未来付款和资金走势为预测。未知金额保留未知，不按零计算。","残高は確定済み計上額、債務と利用枠は口座通貨、予算は基準通貨。今後の支払・資金推移は予測です。不明額をゼロとして扱いません。"),11)
            if(model.context.referenceRates.isEmpty())YText(word("本次未使用参考汇率。","今回は参考レートを使用していません。"),11)
            model.context.referenceRates.forEach{rate->YText("${rate.base} → ${rate.quote}  ${rate.rate}  (${rate.date})",11,number=true)}
        }
        model.steps.forEachIndexed{index,step->CommandCard(index+1,commandName(step.command),"command-step-${step.stepId}") {
            YText(word("方案中间状态 · ${step.targets.size} 个目标","プランの途中の状態・対象 ${step.targets.size} 件"),11,color=LocalYohaku.current.muted)
            StepSummary(step,s,model)
            CommandRiskNotices(step.risks,s,model)
            step.missing.filterNot{field->step.risks.any{it.detail==field}}.forEach{YText(commandIssue(it),12,color=LocalYohaku.current.warm)}
            if(step.objects.isNotEmpty())YLink(word("逐项查看前后与连带影响","変更前後と関連する影響を見る"),{details=step.objects},Modifier.testTag("command-details-${step.stepId}"))
        }}
        Panel(Modifier.testTag("command-net")) {
            YText(if(model.executable)word("最终净影响","最終的な変化")else word("已试运行步骤的净影响 · 方案待补充","試算済みステップの変化・情報の補完待ち"),18)
            val changed=model.net.filter{it.before!=it.after}
            YText(word("${changed.count{it.type=="entry"}} 笔记录 · ${changed.count{it.type=="account"}} 个账户 · ${changed.count{it.type=="bill"}} 个账期发生变化","記録 ${changed.count{it.type=="entry"}} 件・口座 ${changed.count{it.type=="account"}} 件・請求 ${changed.count{it.type=="bill"}} 回が変わります"),12)
            changed.filter{it.type in setOf("balance","budget","creditLimit","bill")&&commandSummaryChanged(it)}.take(6).forEach{EffectSummary(it,s,model)}
            if(changed.isEmpty())YText(word("没有账本数值变化；仍保留逐步操作记录。","帳簿の数値は変わりません。各ステップの操作履歴は残します。"),12)
            YLink(word("查看全部净影响及未变化的相关值","すべての変化と関連値を見る"),{details=model.net},Modifier.testTag("command-net-details"))
        }
        val finalRisks=commandRisks(model.net)
        val intermediate=model.steps.flatMapIndexed{index,step->step.risks.filter{risk->finalRisks.none{it.copy(requiresConfirmation=false)==risk.copy(requiresConfirmation=false)}}.map{index+1 to it}}.distinctBy{it.second}
        if(finalRisks.isNotEmpty()||intermediate.isNotEmpty())Panel(Modifier.testTag("command-risks")) {
            YText(word("具体风险与未知项","具体的なリスク・不明な項目"),16)
            CommandRiskNotices(finalRisks,s,model)
            intermediate.forEach{(number,risk)->
                YText(word("步骤 $number 的中间状态","ステップ $number の途中の状態"),11,color=LocalYohaku.current.muted)
                CommandRiskNotices(listOf(risk),s,model)
            }
        }
        val otherUnknown=model.unknown.filterNot{field->model.steps.any{step->step.risks.any{it.detail==field}}}
        if(otherUnknown.isNotEmpty())Panel {
            YText(word("需要补充或核对","補完・確認が必要"),16)
            otherUnknown.forEach{YText(commandIssue(it),12)}
        }
        CloudHandoffStrip(word("执行边界","実行の範囲"),word("所有步骤在本机一起成功或一起回滚。本方案没有提交后执行或需要系统操作的步骤；不代表在银行或金融平台办理。执行后暂不支持整批撤销。","すべてのステップを端末でまとめて保存し、失敗時は全体を戻します。保存後の追加処理・システム操作はありません。銀行・返済先での手続きを行うものではありません。実行後の一括取消には対応していません。"),"command-boundary") {}
        if(row.status=="EXECUTED") {
            val saved=remember(row.checksJson){commandJson.decodeFromString<List<String>>(row.checksJson).toSet()}
            Panel {
                YText(word("执行时的确认","実行時の確認"),16)
                model.requirements.filter{it.kind!=ConfirmationKind.INFORMATION}.forEach{requirement->
                    val statement=if(LocalLanguage.current=="ja")requirement.ja else requirement.zh
                    YText((if(requirement.key in saved)"✓ "else"— ")+statement,12)
                }
            }
        }
        if(row.status=="PENDING") {
            model.requirements.filter{it.kind!=ConfirmationKind.INFORMATION}.forEachIndexed{index,requirement->
                val stepNumber=model.steps.indexOfFirst{it.stepId==requirement.stepId}+1
                YToggle(word("步骤 $stepNumber · ","ステップ $stepNumber・")+(if(LocalLanguage.current=="ja")requirement.ja else requirement.zh),requirement.key in checks,{checked->checks=if(checked)checks+requirement.key else checks-requirement.key},Modifier.testTag("command-check-$index"))
            }
            YButton(if(model.steps.isEmpty())word("确认收阅","受け取りを確認")else word("确认执行整份方案","プラン全体を確定"),{vm.execute{commands.confirm(row.planId,checks)}},Modifier.fillMaxWidth().testTag("command-confirm"),primary=true,
                enabled=!busy&&model.executable&&model.requirements.filter{it.kind!=ConfirmationKind.INFORMATION}.all{it.key in checks})
        }
    }
    if(row.status in listOf("PENDING","FAILED")) {
        YButton(word("重新试运行","再び試算"),{checks=emptySet();vm.execute{commands.preview(row.planId)}},Modifier.testTag("command-repreview"),enabled=!busy)
        YLink(word("拒绝此方案","このプランを拒否"),{if(!busy)vm.execute{commands.reject(row.planId)}},Modifier.testTag("command-reject"))
    }
    details?.let{effects->CommandEffectsSheet(effects,s,model!!){details=null}}
}

@Composable private fun CommandHeading(title:String,description:String,number:String,edition:String) {
    val t=LocalYohaku.current
    when(t.id) {
        "magazine"->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            YText(number,46,number=true,color=t.accent)
            Column(Modifier.weight(1f)){YText(edition,10,letterSpacing=2f);YText(title,25);YText(description,11,color=t.muted)}
        }
        "journal"->Column(Modifier.fillMaxWidth().rotate(-.5f).drawBehind{drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx())}.padding(vertical=12.dp)) {
            YText(word("办理手记 · ","手続きのメモ・")+number,11,color=t.warm);YText(title,26);YText(description,12,color=t.muted)
        }
        else->Row(Modifier.fillMaxWidth().background(t.surface,RoundedCornerShape(28.dp)).padding(20.dp),horizontalArrangement=Arrangement.spacedBy(14.dp),verticalAlignment=Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).background(t.accent.copy(alpha=.15f),CircleShape),contentAlignment=Alignment.Center){YIcon("file",Modifier.size(23.dp),t.accent)}
            Column(Modifier.weight(1f)){YText(title,19);YText(description,11,color=t.muted)}
        }
    }
}

@Composable private fun CommandCard(number:Int,title:String,tag:String,history:Boolean=false,content:@Composable ColumnScope.()->Unit) {
    val t=LocalYohaku.current
    when(t.id) {
        "magazine"->Row(Modifier.fillMaxWidth().testTag(tag).drawBehind{drawLine(t.ink,Offset.Zero,Offset(size.width,0f),1.dp.toPx())}.padding(top=14.dp),horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            YText(number.toString().padStart(2,'0'),30,number=true,color=t.accent)
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(10.dp)){YText(title,18);content()}
        }
        "journal"->Panel(Modifier.testTag(tag).rotate(if(number%2==0).3f else -.3f)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){YText("✳ $title",19);YText("#$number",12,number=true,color=t.warm)}
            Column(Modifier.padding(start=12.dp).drawBehind{drawLine(t.warm.copy(alpha=.4f),Offset.Zero,Offset(0f,size.height),1.dp.toPx())}.padding(start=12.dp),verticalArrangement=Arrangement.spacedBy(10.dp),content=content)
        }
        else->Panel(Modifier.testTag(tag)) {
            Box(Modifier.background(t.accent.copy(alpha=.12f),RoundedCornerShape(18.dp)).padding(horizontal=12.dp,vertical=5.dp)){YText(if(history)word("方案 $number","プラン $number")else word("步骤 $number","ステップ $number"),10,color=t.accent)}
            YText(title,19)
            content()
        }
    }
}
@Composable private fun commandName(name:String)=when(name){"account.save"->word("账户与期初信息","口座と期首情報");"financialRule.addVersion"->word("新增金融规则版本","金融条件のバージョン追加");"financialPrediction.save"->word("建立或重算预测","予測の作成・再計算");"entry.update"->word("批量修改分类","カテゴリをまとめて変更");else->name}
@Composable private fun commandStatus(status:String)=when(status){"PENDING"->word("待预览 / 确认","確認待ち");"EXECUTED"->word("已执行","実行済み");"REJECTED"->word("已拒绝","拒否済み");else->word("失败","失敗")}
@Composable internal fun localizedCommand(text:String)=if(LocalLanguage.current=="ja")text.substringAfter(" / ",text)else text.substringBefore(" / ")
@Composable internal fun commandIssue(text:String)=when(text){"prediction.firstPeriodNumber"->word("缺少原合同起始期数","元の契約の開始回数が不足しています");"prediction.cannotSettle"->word("当前还款金额无法覆盖费用，不能生成可清偿预测","現在の支払額では手数料を賄えず、返済可能な予測を作成できません");"prediction.dates"->word("缺少付款日期或计息区间","支払日または計算期間が不足しています");"prediction.openingPrincipal"->word("缺少期初本金或来源记录","期首元金または対象記録が不足しています");"previousStep.incomplete"->word("前一步资料不足，此步骤尚未试运行","前のステップの情報不足により、まだ試算できません");else->when {
    text.startsWith("limit.")->limitLabel(text)
    text in setOf("PAYMENT_ACCOUNT_INACTIVE","INCOME_RECEIPT_CHANGED","REFERENCE_CONVERSION","STALE_PLAN","PENDING_POSTING","DEBT_PAYMENT_UNSCHEDULED","PAYMENT_ACCOUNT_REQUIRED","RESERVE_ACCOUNT_REQUIRED","UNKNOWN_CONVERSION","PENDING_CLEARING_AMOUNT","PENDING_CLEARING")->cashIssue(text)
    else->financialIssueName(text)
}}
