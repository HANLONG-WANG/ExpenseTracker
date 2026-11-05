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
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable internal fun CommandEntry(vm:AppViewModel,connected:Boolean) {
    CloudHandoffStrip(word("云端办理","クラウド手続き"),word("说出需求，收到方案后在本机试运行，由你核对并确认。","要望を伝え、届いたプランを端末で試算。確認してから確定します。"),"command-entry") {
        YButton(word("提出办理需求","手続きを依頼"),{vm.navigate("command-submit")},Modifier.fillMaxWidth().testTag("command-new"),primary=true,enabled=connected)
        YLink(word("月度评语与历史","月の振り返り・履歴"),{vm.navigate("monthly-history")},Modifier.testTag("command-monthly-history"))
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
        try {bundle=withContext(Dispatchers.IO){vm.repository.commandExports(s.book.id,scope,vm.preferences)};exportedScope=scope}
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
    CloudHandoffStrip(word("将上传的内容","送信する内容"),word("协议说明、能力清单和本账本结构总会附带。结构含账户余额、分类、商户与地点、朋友、模板、预算、周期、金融规则和计划、额度及活动资料、可办理偏好、CSV 批次摘要和月计划。","プロトコル、対応機能一覧、帳簿の構成を必ず添付します。構成には口座残高・カテゴリ・お店と場所・友人・テンプレート・予算・定期予定・金融条件とプラン・利用枠・シーン・対応する設定・CSVバッチの概要・月の計画が含まれます。"),"command-upload-summary") {
        val entryCount=bundle?.records?.records?.size?:0
        val treatCount=bundle?.records?.activityTreats?.size?:0
        YText(word("结构项目 ${bundle?.context?.items?.size?:0} 项 · 具体记录 $entryCount 笔 · 他人请客 $treatCount 笔 · 所选文件 ${files.size} 份","構成 ${bundle?.context?.items?.size?:0} 件・記録 $entryCount 件・友達のごちそう $treatCount 件・選択ファイル ${files.size} 個"),13)
        YText(word("范围内流水、小票商品与 CSV 关联 ${bundle?.records?.workflows?.size?:0} 项（不含图片字节）","範囲内の明細・レシート商品・CSV関連 ${bundle?.records?.workflows?.size?:0} 件（画像データを除く）"),12)
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
    CommandHeading(word("每份方案，都有来处。","プランの歩みを残す。"),word("预览、执行、拒绝与撤销都保存在本机。","確認・実行・拒否・取消の履歴は端末に保存します。"),"03","HISTORY")
    if(history.isEmpty())YEmpty(word("还没有收到办理方案。","まだ手続きプランは届いていません。"))
    history.forEachIndexed{index,row->CommandCard(index+1,commandStatus(row.status),"history-${row.planId}",history=true) {
        val plan=remember(row.planJson){runCatching{readCommandDocument<CommandPlan>(row.planJson)}.getOrNull()}
        YText(if(row.undoOf!=null)undoTitle(history.firstOrNull{it.planId==row.undoOf})else plan?.message?.take(180)?.ifBlank{word("办理方案","手続きプラン")}?:word("无法识别的方案","読み取れないプラン"),14)
        YText(if(row.undoOf!=null)word("本机撤销 · ${plan?.steps?.size?:0} 个步骤","端末での取消・${plan?.steps?.size?:0} ステップ")else if(plan?.steps?.isEmpty()==true)word("文字回答","テキスト回答")else word("${plan?.steps?.size?:0} 个步骤 · 结果版本 ${row.resultVersion}","${plan?.steps?.size?:0} ステップ・結果バージョン ${row.resultVersion}"),11,color=LocalYohaku.current.muted)
        row.undoneAt?.let{YText(word("撤销记录时间：","取消の記録日時：")+commandTime(it),12,number=true)}
        if(row.status in listOf("EXECUTED","UNDONE"))CommandFollowups(row.planId,vm)
        if(row.status=="EXECUTED"&&row.undoOf==null)CommandUndoEntry(row,vm)
        row.error?.let{YText(localizedCommand(it),11,color=LocalYohaku.current.warm)}
        YLink(if(plan?.steps?.isEmpty()==true)word("查看回答","回答を見る")else word("查看方案","プランを見る"),{vm.openCommandPlan(row.planId)},Modifier.testTag("command-open-${row.planId}"))
    }}
}

@Composable internal fun CommandPreviewScreen(s:BookSnapshot,vm:AppViewModel) {
    val commands=vm.cloud?.commands?:return
    val planId by vm.commandPlanId.collectAsStateWithLifecycle()
    val observedRow by remember(planId){commands.observePlan(planId.orEmpty())}.collectAsStateWithLifecycle(null)
    val row=observedRow
    val monthly by remember(planId){vm.repository.db.monthlyDao().forPlan(planId.orEmpty())}.collectAsStateWithLifecycle(null)
    val parsedPlan=remember(row?.planJson){row?.let{runCatching{readCommandDocument<CommandPlan>(it.planJson)}.getOrNull()}}
    val answer=row?.undoOf==null&&parsedPlan?.steps?.isEmpty()==true
    val original by remember(row?.undoOf){commands.observePlan(row?.undoOf.orEmpty())}.collectAsStateWithLifecycle(null)
    val busy by vm.busy.collectAsStateWithLifecycle()
    val model=remember(row?.confirmationJson){row?.let{commands.confirmation(it)}}
    var checks by remember(model){mutableStateOf<Set<String>>(emptySet())}
    var details by remember(model){mutableStateOf<List<ObjectEffect>?>(null)}
    LaunchedEffect(planId) {planId?.let{id->vm.execute{val p=commands.plan(id);if(p.status=="PENDING"&&p.confirmationJson==null&&(p.undoOf!=null||readCommandDocument<CommandPlan>(p.planJson).steps.isNotEmpty())&&vm.repository.db.monthlyDao().submission(p.localId)==null)commands.preview(id)}}}
    val title=when {
        monthly!=null->word("这个月，留下了什么？","今月、どんな一歩を残した？")
        answer->word("助手的回答。","アシスタントからの回答。")
        row?.undoOf!=null&&row.status=="EXECUTED"->word("数据库撤销已提交。","データベースの取消を保存しました。")
        row?.undoOf!=null->word("撤销前，再核对一次。","取消前に、もう一度確認。")
        else->word("先试运行，再决定。","試算してから、決める。")
    }
    val description=when {
        monthly!=null->word("评语已保存。你可以选择预览建议，不执行也会保留评语。","コメントを保存しました。提案の確認・実行は任意です。実行しなくてもコメントは残ります。")
        answer->word("回答已保存在历史，无需确认执行。","回答は履歴に保存済みです。実行の確認は不要です。")
        row?.status=="UNDONE"||row?.undoOf!=null&&row.status=="EXECUTED"->word("数据库恢复已提交；原执行记录保留，后续恢复见撤销方案状态。","データベースの復元は保存済みです。元の履歴は保持し、後続の復元は取消プランの状態で確認できます。")
        row?.undoOf!=null->word("由本机执行记录生成；下列变更将恢复原方案执行前的业务状态。","端末の実行履歴から作成。以下の変更で元のプラン実行前の状態に戻します。")
        row?.status=="EXECUTED"->word("数据库步骤已提交；后续步骤见下方状态，回执由队列发送。","データベースの処理は保存済みです。後続の状態は下に表示し、受領通知はキューで送信します。")
        else->word("账本尚未改变；下列数字来自本机。","帳簿はまだ変わりません。数値は端末で試算します。")
    }
    CommandHeading(title,description,"02",if(row?.undoOf!=null)"UNDO"else"REVIEW")
    if(row==null){YText(word("正在读取方案…","プランを読み込み中…"),13);return}
    val plan=remember(row.planJson){runCatching{readCommandDocument<CommandPlan>(row.planJson)}.getOrNull()}
    if(row.status in listOf("EXECUTED","UNDONE"))CommandFollowups(row.planId,vm,focusOnAppear=true)
    Panel {
        YText(commandStatus(row.status),12,color=LocalYohaku.current.accent)
        row.undoneAt?.let{YText(word("撤销记录时间：","取消の記録日時：")+commandTime(it),12,number=true)}
        if(row.undoOf!=null)YText(undoTitle(original),15)
        if(monthly==null)plan?.let{if(it.message.isNotBlank())YText(it.message,15,lineHeightMultiplier=1.7f);it.questions.forEach{q->YText("• $q",13)}}
        row.error?.let{YText(localizedCommand(it),13,color=LocalYohaku.current.warm)}
    }
    if(monthly!=null)MonthlyPanel(word("月度评语","月の振り返り"),"monthly-review") {
        plan?.let{YText(it.message,15,lineHeightMultiplier=1.7f);it.questions.forEach{q->YText("• $q",13)}}
        val saved=remember(monthly?.snapshotJson){monthly?.let{readCommandDocument<MonthlyReviewBundle>(it.snapshotJson)}}
        saved?.let{YText(it.summary.month,14);YText(word("当时的月计划：","送信時の計画：")+it.summary.monthlyPlan,13)}
        YText(monthlySuggestionStatus(row,plan),12)
        plan?.steps?.forEachIndexed{index,step->YText(commandStepName(index+1,step.command),13)}
        if(plan?.steps?.isNotEmpty()==true&&model==null&&row.status in listOf("PENDING","FAILED"))YButton(word("预览建议步骤","提案の手順を確認"),{vm.execute{commands.preview(row.planId)}},Modifier.testTag("monthly-preview-suggestions"),primary=true,enabled=!busy)
    }
    if(answer){Box(Modifier.testTag("command-answer")){YText(word("已保存到历史","履歴に保存済み"),12)};return}
    CommandResultCopy(row,vm)
    if(row.status=="EXECUTED"&&row.undoOf==null)CommandUndoEntry(row,vm)
    if(model!=null) {
        if(model.conflicts.isNotEmpty())CommandCard(null,word("整批撤销已阻止","一括取消を停止しました"),"command-undo-conflicts") {
            YText(word("以下对象与执行后状态不一致；没有进行任何撤销。请核对后重新预览。","次の対象が実行直後の状態と一致しません。取消は一切行っていません。確認してから再プレビューしてください。"),13)
            model.conflicts.forEach{conflict->
                val label=model.removals.firstOrNull{it.key==conflict.key}?.name?:when(conflict.type){"monthly_plans","monthlyPlan"->word("月度计划","月の計画");"account","accounts","accountUsage"->s.accounts.firstOrNull{it.id==conflict.key}?.name;"entry","entryDetails"->s.events.firstOrNull{it.id==conflict.key}?.let{"${it.date} · ${it.merchant}"};else->null}
                YText(label?:conflict.reference?.targetName?:resolveName(conflict.key,s,model),12)
                if(conflict.reference!=null) {
                    val ref=conflict.reference
                    YText(word("方案外引用：","プラン外からの参照：")+undoObjectType(ref.table)+" · "+ref.name,12,color=LocalYohaku.current.warm)
                    YText(commandConflictField(conflict.field),11)
                }else {
                    YText(commandConflictField(conflict.field),12,color=LocalYohaku.current.warm)
                    YText(word("执行后：","実行直後：")+commandConflictValue(conflict.expected,conflict,s,model),11)
                    YText(word("现在：","現在：")+commandConflictValue(conflict.current,conflict,s,model),12)
                }
            }
        }
        if(model.removals.isNotEmpty())CommandCard(null,word("将移除","削除するもの"),"command-undo-removals") {
            YText(word("以下对象均由原方案新建；只在未被修改且没有方案外引用时整批移除，包括本方案生成的从属行和修订。","以下は元のプランで作成されたものです。変更やプラン外からの参照がない場合のみ、付随データ・履歴とまとめて削除します。"),12)
            model.removals.forEach{removed->
                YText(undoObjectType(removed.table)+" · "+removed.name+(removed.number?.let{" · #$it"}?:""),13,Modifier.testTag("command-remove-${removed.key}"))
            }
        }
        Panel(Modifier.testTag("command-context")) {
            YText(word("试算口径","試算の基準"),15)
            YText("${model.context.asOfDate} · ${model.context.zoneId}\n→ ${model.context.throughDate}",12,number=true)
            YText(word("余额采用已确认入账；债务与额度按账户币种；预算按账本币种；未来付款和资金走势为预测。未知金额保留未知，不按零计算。","残高は確定済み計上額、債務と利用枠は口座通貨、予算は基準通貨。今後の支払・資金推移は予測です。不明額をゼロとして扱いません。"),11)
            if(model.context.referenceRates.isEmpty())YText(word("本次未使用参考汇率。","今回は参考レートを使用していません。"),11)
            model.context.referenceRates.forEach{rate->YText("${rate.base} → ${rate.quote}  ${rate.rate}  (${rate.date})",11,number=true)}
        }
        model.steps.forEachIndexed{index,step->CommandCard(index+1,commandName(step.command),"command-step-${step.stepId}") {
            YText(if(step.command=="entry.prepareDraft")word("将生成 1 份草稿，待你补完后保存","下書きを1件作成し、ご自身で補完して保存")else word("方案中间状态 · ${step.targets.size} 个目标","プランの途中の状態・対象 ${step.targets.size} 件"),11,color=LocalYohaku.current.muted)
            CommandExecutionMode(step)
            StepSummary(step,s,model)
            CommandRiskNotices(step.risks,s,model)
            step.missing.filterNot{field->step.risks.any{it.detail==field}}.forEach{YText(commandIssue(it),12,color=LocalYohaku.current.warm)}
            CommandFinancialComparison(step,s,model)
            if(step.objects.isNotEmpty())YLink(word("逐项查看前后与连带影响","変更前後と関連する影響を見る"),{details=step.objects},Modifier.testTag("command-details-${step.stepId}"))
        }}
        Panel(Modifier.testTag("command-net")) {
            YText(if(model.conflicts.isNotEmpty())word("原定恢复内容（未执行）","復元予定の内容（未実行）")else if(model.executable)word("最终净影响","最終的な変化")else word("已试运行步骤的净影响 · 方案待补充","試算済みステップの変化・情報の補完待ち"),18)
            val changed=model.net.filter{it.before!=it.after}
            YText(word("${changed.count{it.type=="entry"}} 笔记录 · ${changed.count{it.type=="account"}} 个账户 · ${changed.count{it.type=="bill"}} 个账期发生变化","記録 ${changed.count{it.type=="entry"}} 件・口座 ${changed.count{it.type=="account"}} 件・請求 ${changed.count{it.type=="bill"}} 回が変わります"),12)
            changed.filter{it.type in setOf("balance","budget","creditLimit","bill","activityBalance","budgetSetting")&&commandSummaryChanged(it)}.take(6).forEach{EffectSummary(it,s,model)}
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
        CloudHandoffStrip(word("执行边界","実行の範囲"),word("数据库步骤一起提交或回滚。标注的后续步骤在提交后分别执行，可重试；本机操作需要你继续完成。未完成后续步骤前不能整批撤销；不代表在银行或金融平台办理。","データベースの処理は全体をまとめて保存・取消します。後続の処理は保存後に個別実行し、再試行できます。端末での操作はご自身で続けてください。後続処理の完了前は一括取消できません。銀行・返済先での手続きは行いません。"),"command-boundary") {
            if(row.status=="UNDONE")YText(word("此方案已撤销，不能再次撤销或执行。","このプランは取消済みのため、再度の取消・実行はできません。"),12)
            else if(row.undoOf!=null)YText(word("仅在所有对象仍与原执行后的状态一致时整批撤销；撤销操作不能再次撤销。","すべての対象が元の実行直後と一致する場合のみ一括取消します。取消操作を再び取り消すことはできません。"),12)
            else {
                val savedAvailability=if(row.status=="EXECUTED")commands.undoAvailability(row)else null
                YText(if(savedAvailability?.available?:model.steps.all{it.undo.available})word("本方案可整批撤销；执行后再有修改将阻止撤销。","このプランは一括取消できます。実行後に対象が変わると取消を停止します。")else word("本方案不可整批撤销","このプランは一括取消できません"),14)
                if(savedAvailability?.available==false)YText(if(LocalLanguage.current=="ja")savedAvailability.ja else savedAvailability.zh,12)
                else model.steps.forEachIndexed{index,step->YText(commandStepName(index+1,step.command)+"："+(if(LocalLanguage.current=="ja")step.undo.ja else step.undo.zh),12)}
            }
        }
        if(row.status in listOf("EXECUTED","UNDONE")) {
            val saved=remember(row.checksJson){commandJson.decodeFromString<List<String>>(row.checksJson).toSet()}
            Panel {
                YText(word("执行时的确认","実行時の確認"),16)
                model.requirements.filter{it.kind!=ConfirmationKind.INFORMATION}.forEach{requirement->
                    val statement=commandRequirementText(requirement,model)
                    YText((if(requirement.key in saved)"✓ "else"— ")+statement,12)
                }
            }
        }
        if(row.status=="PENDING") {
            model.requirements.filter{it.kind!=ConfirmationKind.INFORMATION}.forEachIndexed{index,requirement->
                val stepNumber=model.steps.indexOfFirst{it.stepId==requirement.stepId}+1
                YToggle(word("步骤 $stepNumber · ","ステップ ${stepNumber}・")+commandRequirementText(requirement,model),requirement.key in checks,{checked->checks=if(checked)checks+requirement.key else checks-requirement.key},Modifier.testTag("command-check-$index"))
            }
            YButton(if(row.undoOf!=null)word("确认撤销整份云端方案","クラウドプラン全体の取消を確定")else if(model.steps.isEmpty())word("确认收阅","受け取りを確認")else word("确认执行整份方案","プラン全体を確定"),{vm.execute{commands.confirm(row.planId,checks)}},Modifier.fillMaxWidth().testTag("command-confirm"),primary=true,
                enabled=!busy&&model.executable&&model.requirements.filter{it.kind!=ConfirmationKind.INFORMATION}.all{it.key in checks})
        }
    }
    if(row.status in listOf("PENDING","FAILED")) {
        YButton(word("重新试运行","再び試算"),{checks=emptySet();vm.execute{commands.preview(row.planId)}},Modifier.testTag("command-repreview"),enabled=!busy)
        YLink(word("拒绝此方案","このプランを拒否"),{if(!busy)vm.execute{commands.reject(row.planId)}},Modifier.testTag("command-reject"))
    }
    details?.let{effects->CommandEffectsSheet(effects,s,model!!){details=null}}
}

@Composable private fun CommandUndoEntry(row:CommandPlanEntity,vm:AppViewModel) {
    val commands=vm.cloud!!.commands
    val availability=remember(row){commands.undoAvailability(row)}
    val effects by remember(row.planId){commands.effects(row.planId)}.collectAsStateWithLifecycle(emptyList())
    val busy by vm.busy.collectAsStateWithLifecycle()
    CommandCard(null,word("撤销整份云端方案","クラウドプラン全体を取り消す"),"command-undo-availability-${row.planId}") {
        YText(if(LocalLanguage.current=="ja")availability.ja else availability.zh,12,color=LocalYohaku.current.muted)
        if(availability.available)YButton(word("撤销整份云端方案","クラウドプラン全体を取り消す"),{
            vm.execute{val inverse=commands.prepareUndo(row.planId);commands.preview(inverse.planId);vm.openCommandPlan(inverse.planId)}
        },Modifier.testTag("command-undo-${row.planId}"),enabled=!busy&&effects.all{it.status=="DONE"})
    }
}
@Composable private fun undoTitle(original:CommandPlanEntity?):String {
    val message=remember(original?.planJson){original?.let{readCommandDocument<CommandPlan>(it.planJson).message}.orEmpty()}
    val title=localizedCommand(message).lineSequence().firstOrNull{it.isNotBlank()}?.trim()?.trimStart('#')?.trim()?.take(100)?.takeIf{it.isNotEmpty()}?:word("办理方案","手続きプラン")
    return word("撤销「${title}」","「${title}」を取り消す")
}
@Composable private fun undoObjectType(table:String)=when(table) {
    "receipt_item_documents"->word("本方案新增的商品文档","このプランで追加した商品文書");"analysis_preferences"->word("本方案设置的分析偏好","このプランで設定した分析項目");"attachment_links"->word("本方案建立的记录附件关系","このプランで作成した記録の添付関係");"receipts"->word("本方案建立的小票归属","このプランで作成したレシートの帰属");"receipt_images"->word("本方案建立的小票图片关系","このプランで作成したレシート画像の関連");
    "activities"->word("本方案新建的活动","このプランで作成したシーン");"activity_reserves"->word("本方案新建的预留","このプランで作成した確保");"recurrences"->word("本方案新建的周期规则","このプランで作成した定期ルール");"occurrences"->word("本方案生成的周期实例","このプランで作成した定期予定");"expected_incomes"->word("本方案新建的预计收入","このプランで作成した収入予定");"treats"->word("本方案新建的请客记录","このプランで作成したごちそう記録");"activity_budgets"->word("本方案设置的活动分类预算","このプランで設定したシーンのカテゴリ予算");"activity_participants"->word("本方案新增的活动参与关系","このプランで追加したシーンの参加関係");"book_features"->word("本方案新增的活动模式设置","このプランで追加したシーンモード設定");"treat_allocations"->word("本方案产生的请客份额","このプランによるごちそうの取り分");"reserve_uses"->word("本方案产生的预留使用关系","このプランによる確保の使用関係");"draft_media"->word("草稿暂存附件关系","下書きの添付関係");
    "books"->word("账本","帳簿");"categories"->word("分类","カテゴリ");"merchants"->word("商户","お店");"merchant_locations"->word("保存地点","保存した場所");"friends"->word("朋友","友達");"entry_templates"->word("记账模板","記録テンプレート");"budgets"->word("预算设置","予算設定");"settlements"->word("结算","決済");"transfers"->word("转账","振替");"expense_components"->word("手续费","手数料");"event_places"->word("记录地点","記録の場所");
    "financial_rule_validations"->word("本方案新增的规则核验记录","このプランで追加した条件の検証記録");"debt_contracts"->word("本方案新增的借款合同","このプランで追加した借入契約");"financial_repayments"->word("本方案新增的还款清偿资料","このプランで追加した返済確定の情報");"financial_repayment_allocations"->word("本方案新增的还款分配关系","このプランで追加した返済の充当関係");"financial_contract_plans"->word("本方案新增的合同与计划关联","このプランで追加した契約とプランの関連");"financial_arrangements"->word("本方案新增的已办理安排记录","このプランで追加した手続き済み予定の記録");"financial_bill_adjustments"->word("本方案新增的账期义务调整","このプランで追加した請求債務の調整");"credit_limit_groups"->word("本方案新增的共享额度组","このプランで追加した共通利用枠");"credit_limit_group_members"->word("本方案新增的共享额度成员关系","このプランで追加した共通利用枠の所属関係");"credit_limit_group_revisions"->word("本方案新增的共享额度修订","このプランで追加した共通利用枠の更新履歴");"credit_limit_recoveries"->word("本方案新增的额度恢复确认","このプランで追加した利用枠回復の確認");"credit_limit_observations"->word("本方案新增的可用额度观测","このプランで追加した利用可能枠の確認");
    "monthly_plans"->word("月度计划","月の計画");"accounts"->word("账户","口座");"financial_rules"->word("规则版本","条件バージョン");"financial_plans"->word("预测计划","予測プラン");"financial_bills"->word("账期","請求期間");"financial_plan_revisions"->word("预测修订","予測の履歴");"credit_limit_configs"->word("额度配置版本","利用枠のバージョン")
    "events"->word("记录","記録");"revisions"->word("本方案产生的记录修订","このプランによる記録の更新履歴");"drafts","financial_drafts"->word("草稿","下書き");"postings"->word("入账","計上");else->word("关联资料","関連情報")
}
@Composable private fun commandTime(value:Long)=com.yohaku.expensetracker.ui.features.FinancialDisplayFacts.time(Instant.ofEpochMilli(value),ZoneId.systemDefault(),LocalLanguage.current)
@Composable private fun commandConflictValue(value:JsonElement,conflict:UndoConflict,s:BookSnapshot,model:ConfirmationModel):String {
    if(value==JsonNull)return word("不存在 / 无值","存在しない／値なし")
    if(value !is JsonPrimitive)return word("关联内容已变化","関連する内容が変わりました")
    val currency=s.accounts.firstOrNull{it.id==conflict.key}?.currency?:s.events.firstOrNull{it.id==conflict.key}?.currency?:(model.net.firstOrNull{it.key==conflict.key}?.let{(it.before as? JsonObject)?:it.after as? JsonObject})?.get("currency")?.jsonPrimitive?.contentOrNull?:s.book.currency
    return fieldValue(value to null,if(conflict.type=="preference")conflict.key else conflict.field.substringAfterLast('.'),currency,s,model)
}
@Composable private fun commandConflictField(field:String)=field.split('.').map{fieldName(it)}.joinToString(" / ")

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

@Composable internal fun CommandCard(number:Int?,title:String,tag:String,history:Boolean=false,followup:Boolean=false,content:@Composable ColumnScope.()->Unit) {
    val t=LocalYohaku.current
    when(t.id) {
        "magazine"->Row(Modifier.fillMaxWidth().testTag(tag).drawBehind{drawLine(t.ink,Offset.Zero,Offset(size.width,0f),1.dp.toPx())}.padding(top=14.dp),horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            YText(number?.toString()?.padStart(2,'0')?:if(followup)"→"else"↶",30,number=true,color=t.accent)
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(10.dp)){YText(title,18);content()}
        }
        "journal"->Panel(Modifier.testTag(tag).rotate(if(number!=null&&number%2==0).3f else -.3f)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                    YText("✳",19,Modifier.clearAndSetSemantics{});YText(title,19)
                }
                YText(number?.let{"#$it"}?:word("本机","端末"),12,number=number!=null,color=t.warm)
            }
            Column(Modifier.padding(start=12.dp).drawBehind{drawLine(t.warm.copy(alpha=.4f),Offset.Zero,Offset(0f,size.height),1.dp.toPx())}.padding(start=12.dp),verticalArrangement=Arrangement.spacedBy(10.dp),content=content)
        }
        else->Panel(Modifier.testTag(tag)) {
            Box(Modifier.background(t.accent.copy(alpha=.12f),RoundedCornerShape(18.dp)).padding(horizontal=12.dp,vertical=5.dp)){YText(if(followup)word("后续办理","後続の手続き")else if(number==null)word("本机撤销","端末で取消")else if(history)word("方案 $number","プラン $number")else word("步骤 $number","ステップ $number"),10,color=t.accent)}
            YText(title,19)
            content()
        }
    }
}
@Composable internal fun commandName(name:String):String {
    val namePair=commandBusinessName(name)
    return word(namePair.first,namePair.second)
}
@Composable internal fun commandStepName(number:Int,command:String):String {
    val title=commandStepTitle(number,command)
    return word(title.first,title.second)
}
@Composable private fun commandStatus(status:String)=when(status){"ANSWER"->word("回答已保存","回答保存済み");"PENDING"->word("待预览 / 确认","確認待ち");"EXECUTED"->word("已执行","実行済み");"UNDONE"->word("已撤销","取消済み");"REJECTED"->word("已拒绝","拒否済み");else->word("失败","失敗")}
@Composable internal fun localizedCommand(text:String)=if(LocalLanguage.current=="ja")text.substringAfter(" / ",text)else text.substringBefore(" / ")
@Composable internal fun commandIssue(text:String)=when(text){"prediction.firstPeriodNumber"->word("缺少原合同起始期数","元の契約の開始回数が不足しています");"prediction.cannotSettle"->word("当前还款金额无法覆盖费用，不能生成可清偿预测","現在の支払額では手数料を賄えず、返済可能な予測を作成できません");"prediction.dates"->word("缺少付款日期或计息区间","支払日または計算期間が不足しています");"prediction.openingPrincipal"->word("缺少期初本金或来源记录","期首元金または対象記録が不足しています");"previousStep.incomplete"->word("前一步资料不足，此步骤尚未试运行","前のステップの情報不足により、まだ試算できません");else->when {
    text.startsWith("limit.")->limitLabel(text)
    text in setOf("PAYMENT_ACCOUNT_INACTIVE","INCOME_RECEIPT_CHANGED","REFERENCE_CONVERSION","STALE_PLAN","PENDING_POSTING","DEBT_PAYMENT_UNSCHEDULED","PAYMENT_ACCOUNT_REQUIRED","RESERVE_ACCOUNT_REQUIRED","UNKNOWN_CONVERSION","PENDING_CLEARING_AMOUNT","PENDING_CLEARING")->cashIssue(text)
    else->financialIssueName(text)
}}
