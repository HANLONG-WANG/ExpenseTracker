package com.yohaku.expensetracker.ui.features

import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yohaku.expensetracker.data.cloud.*
import com.yohaku.expensetracker.data.files.*
import com.yohaku.expensetracker.data.repository.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*
import kotlinx.coroutines.delay

/** Cloud handoff desk. The server only moves files; every ledger write still goes through local preview. */
@Composable internal fun CloudScreen(s:BookSnapshot,vm:AppViewModel) {
    val cloud=vm.cloud?:return
    val context=LocalContext.current
    val busy by vm.busy.collectAsStateWithLifecycle()
    val connection by cloud.connection.collectAsStateWithLifecycle()
    val tasks by remember(s.book.id){cloud.tasks(s.book.id)}.collectAsStateWithLifecycle(emptyList())
    var connect by rememberSaveable{mutableStateOf(false)}
    var unbind by rememberSaveable{mutableStateOf(false)}
    var general by rememberSaveable(s.book.id){mutableStateOf(false)}
    var supplementFor by rememberSaveable(s.book.id){mutableStateOf<String?>(null)}
    var claim by remember(s.book.id){mutableStateOf<CloudClaim?>(null)}
    var notice by rememberSaveable(s.book.id){mutableStateOf<String?>(null)}
    val active=tasks.any{it.phase in listOf(CloudPhase.SUBMITTED,CloudPhase.QUEUED,CloudPhase.ANALYZING,CloudPhase.REVIEW)}
    // Foreground polling only: no push exists, and per-task failures are stored on the task instead of raising dialogs.
    LaunchedEffect(s.book.id,connection?.bound,active) {
        if(connection?.bound!=true)return@LaunchedEffect
        cloud.refreshActive(s.book.id)
        while(active){delay(30_000);cloud.refreshActive(s.book.id)}
    }
    val supplement=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){uris->
        val id=supplementFor
        if(id!=null&&uris.isNotEmpty())vm.execute(onSuccess={supplementFor=null;notice="已补充资料，正在重新提交 / 資料を追加しました。再提出しています"}){cloud.addSupplement(id,uris)}
    }
    YTitle(word("资料出门，账目留家。","資料は外へ、帳簿は手元に。"),"CLOUD DESK",word("把小票图片或银行资料交给云端整理，结果回来后仍在本机预览确认。","レシート画像や銀行資料をクラウドで整理し、結果は端末で確認してから保存します。"),icon="file",pageFlow=true)
    CloudConnectionCard(connection,busy,onImport={connect=true},onTest={vm.execute(onSuccess={notice="连接正常 / 接続できました"}){cloud.testConnection()}},
        onOpen={connection?.let{context.startActivity(Intent(Intent.ACTION_VIEW,it.origin.toUri()))}},onUnbind={unbind=true})
    notice?.let{YText(localized(it),12,Modifier.testTag("cloud-notice"),color=LocalYohaku.current.accent)}
    CloudSection(word("这本账的云端任务","この帳簿のクラウドタスク"),tasks.size)
    if(tasks.isEmpty())YEmpty(word("还没有交出去的资料。可以从小票工作台或流水导入页发起。","まだ渡した資料はありません。レシート整理や明細取込から始められます。"))
    tasks.forEachIndexed{index,view->
        CloudTaskCard(view,index,busy,
            onRetry={cloud.enqueue(view.task.localId);notice="已重新排队上传 / 再アップロードを予約しました"},
            onRefresh={vm.execute{cloud.refresh(view.task.localId)}},
            onClaim={var result:CloudClaim?=null;vm.execute(onSuccess={claim=result}){result=cloud.claim(view.task.localId)}},
            onSupplement={supplementFor=view.task.localId;supplement.launch(arrayOf("*/*"))},
            onDiscard={vm.execute{cloud.discard(view.task.localId)}})
    }
    YButton(word("新建通用分析任务","汎用分析タスクを作成"),{general=true},Modifier.fillMaxWidth().testTag("cloud-new-general"),enabled=!busy&&connection?.bound==true)
    YText(word("云端不调用银行、不直接写账。上传中断会按原文件继续；结果下载后先核对大小和校验值，再进入原有预览。","クラウドは銀行接続も記帳もしません。中断したアップロードは同じファイルで再開し、結果はサイズとチェックサムを確認してから通常のプレビューへ進みます。"),10,color=LocalYohaku.current.muted,lineHeightMultiplier=2f)
    if(connect)CloudConnectSheet(vm,cloud,{connect=false},{connect=false;notice="已绑定云端分析台 / クラウド分析デスクに接続しました"})
    if(unbind)YSheet(word("解除这台设备的连接","この端末の接続を解除"),{if(!busy)unbind=false}) {
        YText(word("只删除本机保存的连接凭证。已导入的账目、云端任务记录与回执队列保留；如配置可能泄露，请在网页撤销设备。","端末に保存した接続情報のみ削除します。取込済みの記録・タスク履歴・受領通知の予約は残ります。漏えいの恐れがあればWebで端末を取り消してください。"),12,color=LocalYohaku.current.muted)
        YActionRow {
            YButton(word("解除连接","接続を解除"),{vm.execute(onSuccess={unbind=false}){cloud.unbind()}},Modifier.testTag("cloud-confirm-unbind"),primary=true,enabled=!busy)
            YLink(word("保留连接","接続を保持"),{unbind=false})
        }
    }
    if(general)CloudGeneralSheet(s,vm,cloud,{general=false},{general=false;notice="任务已排队上传 / タスクのアップロードを予約しました"})
    claim?.let{c->CloudClaimReview(s,vm,cloud,c,{claim=null},{message->claim=null;notice=message})}
}

/** Notices set from callbacks keep both languages and are split on display, like repository errors. */@Composable private fun localized(text:String)=if(LocalLanguage.current=="ja")text.substringAfter(" / ",text)else text.substringBefore(" / ")

@Composable private fun CloudSection(title:String,count:Int) {
    val t=LocalYohaku.current
    Row(Modifier.fillMaxWidth().padding(top=10.dp).then(when(t.id){
        "magazine"->Modifier.drawBehind{drawLine(t.ink,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx())}.padding(bottom=8.dp)
        "journal"->Modifier.rotate(-.6f)
        else->Modifier
    }),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        when(t.id) {
            "magazine"->YText("DISPATCHES",8,color=t.muted,letterSpacing=1.5f)
            "journal"->YText("✳",16,Modifier.clearAndSetSemantics{},color=t.warm)
            else->Box(Modifier.size(10.dp).background(Color(0xffd9a77c),CircleShape))
        }
        YText(title,when(t.id){"journal"->20;"objects"->16;else->17},Modifier.weight(1f),weight=if(t.id=="objects")FontWeight.Medium else FontWeight.Normal)
        YText(count.toString().padStart(if(t.id=="magazine")2 else 1,'0'),if(t.id=="magazine")20 else 16,number=true,color=if(t.id=="journal")t.warm else t.muted)
    }
}

/** Magazine: a masthead slip. Journal: a taped envelope with a postmark. Objects: a luggage tag. */
@Composable private fun CloudConnectionCard(info:CloudConnectionInfo?,busy:Boolean,onImport:()->Unit,onTest:()->Unit,onOpen:()->Unit,onUnbind:()->Unit) {
    val t=LocalYohaku.current;val journal=t.id=="journal";val objects=t.id=="objects"
    val bound=info?.bound==true&&!info.needsReconnect
    val state=when {
        info==null->word("尚未连接","未接続")
        info.needsReconnect->word("需要重新连接","再接続が必要")
        !info.bound->word("待验证","確認待ち")
        else->word("已连接","接続済み")
    }
    val frame=when(t.id) {
        "magazine"->Modifier.drawBehind{drawLine(t.ink,Offset.Zero,Offset(size.width,0f),2.dp.toPx());drawLine(t.ink,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx())}.padding(vertical=18.dp)
        "journal"->Modifier.rotate(-.8f).background(Color(0xfffff6df)).drawBehind{
            drawRect(t.line,style=Stroke(1.dp.toPx()))
            // Envelope flap folds drawn as two diagonal hand lines.
            drawLine(t.line,Offset.Zero,Offset(size.width/2,46.dp.toPx()),1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx())))
            drawLine(t.line,Offset(size.width,0f),Offset(size.width/2,46.dp.toPx()),1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx())))
        }.padding(start=17.dp,end=17.dp,top=58.dp,bottom=19.dp)
        else->Modifier.drawBehind{drawRoundRect(Color(0x2bbd9565),Offset(0f,5.dp.toPx()),cornerRadius=androidx.compose.ui.geometry.CornerRadius(22.dp.toPx()))}.background(Color(0xfff2dfb9),RoundedCornerShape(30.dp,22.dp,22.dp,30.dp)).padding(start=46.dp,end=19.dp,top=22.dp,bottom=20.dp)
    }
    Box(Modifier.fillMaxWidth().padding(top=if(journal)14.dp else 4.dp).testTag("cloud-connection").then(frame)) {
        if(journal) {
            Box(Modifier.align(Alignment.TopCenter).offset(y=(-72).dp).size(88.dp,18.dp).rotate(-3f).background(Color(0x80d5bd85)))
            Box(Modifier.align(Alignment.TopEnd).offset(y=(-48).dp).size(58.dp).rotate(-14f).border(1.5.dp,if(bound)t.accent else t.warm,CircleShape).padding(5.dp).border(1.dp,(if(bound)t.accent else t.warm).copy(alpha=.5f),CircleShape),contentAlignment=Alignment.Center) {
                YText(state,9,color=if(bound)t.accent else t.warm)
            }
        }
        if(objects) {
            // Tag eyelet and string, part of the luggage-tag silhouette.
            Box(Modifier.align(Alignment.CenterStart).offset(x=(-31).dp).size(15.dp).background(t.bg,CircleShape).border(2.dp,Color(0xffc99e70),CircleShape))
            Box(Modifier.align(Alignment.CenterStart).offset(x=(-52).dp,y=(-14).dp).size(26.dp,2.dp).rotate(28f).background(Color(0xffb88a5e)))
        }
        Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(if(journal)10.dp else 12.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                YText(when(t.id){"magazine"->"CONNECTION / DEVICE";"journal"->word("寄往云端的信封","クラウドへの封筒");else->"MY CLOUD TAG"},if(journal)12 else 8,Modifier.weight(1f),color=if(journal)t.warm else t.muted,letterSpacing=if(t.id=="magazine")1.5f else 0f)
                if(!journal)YText(state,if(objects)10 else 9,Modifier.testTag("cloud-connection-state").then(if(objects)Modifier.background(if(bound)Color(0xffe3e7d6)else Color(0xffefd3ad),RoundedCornerShape(8.dp)).padding(horizontal=9.dp,vertical=5.dp)else Modifier.border(1.dp,if(bound)t.accent else t.warm).padding(horizontal=8.dp,vertical=4.dp)),color=if(objects)if(bound)Color(0xff7b8663)else Color(0xffa57a47)else if(bound)t.accent else t.warm)
            }
            YText(info?.origin?.substringAfter("://")?:word("连接一台私人分析台","私用の分析デスクにつなぐ"),when(t.id){"journal"->21;"objects"->18;else->22},lineHeightMultiplier=1.4f)
            if(info!=null)YText(word("设备 ","端末 ")+info.deviceId+" · API v"+info.apiVersion,if(journal)11 else 9,color=t.muted,number=true)
            else YText(word("在网页登录后生成连接配置，粘贴或选择文件导入。令牌只加密保存在本机。","Webでログインして接続設定を作成し、貼り付けかファイルで取り込みます。トークンは端末内で暗号化して保存します。"),if(journal)12 else 10,color=t.muted,lineHeightMultiplier=1.9f)
            if(info?.needsReconnect==true)YText(word("设备令牌已失效或被撤销。上传已暂停，请导入新的连接配置。","端末トークンが無効か取り消されました。アップロードを止めています。新しい接続設定を取り込んでください。"),12,color=t.warm)
            FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                YButton(if(info==null)word("导入连接配置","接続設定を取込")else word("更换配置","設定を差し替え"),onImport,Modifier.testTag("cloud-import-config"),primary=info==null||info.needsReconnect,enabled=!busy)
                if(info!=null) {
                    YActionRow {
                        YButton(word("测试连接","接続テスト"),onTest,Modifier.testTag("cloud-test"),enabled=!busy)
                        YButton(word("打开网页工作台","Webデスクを開く"),onOpen,Modifier.testTag("cloud-open-web"))
                        YButton(word("解除","解除"),onUnbind,Modifier.testTag("cloud-unbind"),enabled=!busy)
                    }
                }
            }
        }
    }
}

private fun CloudPhase.step()=when(this) {
    CloudPhase.PREPARING,CloudPhase.UPLOADING,CloudPhase.BLOCKED->0
    CloudPhase.SUBMITTED,CloudPhase.QUEUED->1
    CloudPhase.ANALYZING,CloudPhase.NEEDS_INFO,CloudPhase.REVIEW->2
    CloudPhase.READY->3
    CloudPhase.ARCHIVED->4
    // Past the last stop: every step is done, none is current.
    CloudPhase.IMPORTED->5
}
@Composable private fun CloudPhase.label()=when(this) {
    CloudPhase.PREPARING->word("准备资料","資料を準備")
    CloudPhase.UPLOADING->word("上传中","アップロード中")
    CloudPhase.SUBMITTED->word("已提交","提出済み")
    CloudPhase.QUEUED->word("待分析","分析待ち")
    CloudPhase.ANALYZING->word("分析中","分析中")
    CloudPhase.NEEDS_INFO->word("待补资料","資料の追加待ち")
    CloudPhase.REVIEW->word("结果待检查","結果を確認中")
    CloudPhase.READY->word("结果可领取","結果を受取可能")
    CloudPhase.IMPORTED->word("已导入","取込済み")
    CloudPhase.ARCHIVED->word("已归档","アーカイブ済み")
    CloudPhase.BLOCKED->word("已暂停","停止中")
}
@Composable private fun cloudTypeLabel(type:String)=when(type){"receipt"->word("小票整理","レシート整理");"statement"->word("银行流水转换","明細の変換");else->word("通用分析","汎用分析")}

/** The five-step route is drawn per theme: rules, a pencil trail or beads. */
@Composable private fun CloudTrack(step:Int,warn:Boolean) {
    val t=LocalYohaku.current
    val labels=listOf(word("上传","送信"),word("排队","待機"),word("分析","分析"),word("领取","受取"),word("入账本","帳簿へ"))
    val current=if(warn)t.warm else t.accent
    Column(Modifier.fillMaxWidth().clearAndSetSemantics{},verticalArrangement=Arrangement.spacedBy(6.dp)) {
        when(t.id) {
            "magazine"->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                repeat(5){i->Box(Modifier.weight(1f).height(if(i==step)3.dp else 1.dp).background(if(i<step)t.ink else if(i==step)current else t.line))}
            }
            "journal"->Canvas(Modifier.fillMaxWidth().height(18.dp)) {
                val gap=size.width/5;val y=size.height/2
                drawLine(t.line,Offset(gap/2,y),Offset(size.width-gap/2,y),1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx())))
                repeat(5){i->
                    val c=Offset(gap*i+gap/2,y+if(i%2==0)-1.dp.toPx()else 1.dp.toPx())
                    if(i<step)drawCircle(t.accent.copy(alpha=.75f),5.dp.toPx(),c)
                    else drawCircle(if(i==step)current else t.line,if(i==step)7.dp.toPx()else 5.dp.toPx(),c,style=Stroke(if(i==step)2.dp.toPx()else 1.dp.toPx()))
                }
            }
            else->Row(Modifier.fillMaxWidth().background(Color(0xfff0e4ce),RoundedCornerShape(11.dp)).padding(horizontal=12.dp,vertical=8.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                repeat(5){i->Box(Modifier.size(if(i==step)16.dp else 11.dp).background(if(i<step)Color(0xffc8a27a)else if(i==step)current else Color(0xfffff8ec),CircleShape).border(1.dp,Color(0xffdcc4a0),CircleShape))}
            }
        }
        Row(Modifier.fillMaxWidth()) {
            labels.forEachIndexed{i,l->YText(l,if(t.id=="journal")10 else 8,Modifier.weight(1f),color=if(i==step)current else t.muted,textAlign=androidx.compose.ui.text.style.TextAlign.Center)}
        }
    }
}

@Composable private fun CloudTaskCard(view:CloudTaskView,index:Int,busy:Boolean,onRetry:()->Unit,onRefresh:()->Unit,onClaim:()->Unit,onSupplement:()->Unit,onDiscard:()->Unit) {
    val t=LocalYohaku.current;val journal=t.id=="journal";val objects=t.id=="objects"
    val task=view.task;val phase=view.phase
    val warn=phase in listOf(CloudPhase.BLOCKED,CloudPhase.NEEDS_INFO)
    val good=phase in listOf(CloudPhase.READY,CloudPhase.IMPORTED)
    val shape=RoundedCornerShape(19.dp,19.dp,10.dp,10.dp)
    val frame=when(t.id) {
        "magazine"->Modifier.drawBehind{drawLine(t.ink,Offset.Zero,Offset(size.width,0f),1.dp.toPx())}.padding(top=17.dp,bottom=4.dp)
        "journal"->Modifier.rotate(if(index%2==0)-.5f else .4f).background(Color(0xfffff8e7)).border(1.dp,t.line).padding(start=15.dp,end=15.dp,top=20.dp,bottom=16.dp)
        else->Modifier.background(Color(0xfffff8e7),shape).border(1.dp,Color(0xffe1ccaa),shape).padding(horizontal=15.dp,vertical=19.dp)
    }
    Box(Modifier.fillMaxWidth().padding(top=8.dp).testTag("cloud-task-${task.localId}").then(frame)) {
        if(journal)Box(Modifier.align(Alignment.TopStart).offset(x=(-4).dp,y=(-27).dp).size(54.dp,14.dp).rotate(-6f).background(Color(0x66b3bba7)))
        Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                YText((index+1).toString().padStart(2,'0')+"  "+cloudTypeLabel(task.type),if(journal)12 else 9,Modifier.weight(1f).then(if(objects)Modifier else Modifier),color=t.muted,number=true,letterSpacing=if(t.id=="magazine")1f else 0f)
                val tone=if(warn)t.warm else if(good)t.accent else t.muted
                YText(phase.label(),if(journal)11 else 9,Modifier.testTag("cloud-phase-${task.localId}").then(when(t.id) {
                    "magazine"->Modifier.background(if(good)t.ink else Color.Transparent).border(1.dp,if(good)t.ink else tone).padding(horizontal=8.dp,vertical=4.dp)
                    "journal"->Modifier.rotate(-4f).border(1.5.dp,tone,RoundedCornerShape(50)).padding(horizontal=10.dp,vertical=3.dp)
                    else->Modifier.background(if(warn)Color(0xffefd3ad)else if(good)Color(0xffe3e7d6)else Color(0xfff0e4ce),RoundedCornerShape(8.dp)).padding(horizontal=9.dp,vertical=5.dp)
                }),color=if(t.id=="magazine"&&good)t.surface else if(objects)if(warn)Color(0xffa57a47)else if(good)Color(0xff7b8663)else t.muted else tone)
            }
            YText(task.title,if(journal)21 else 17,lineHeightMultiplier=1.5f)
            val uploaded=view.transfers.count{it.fileId!=null}
            YText(listOf(java.time.Instant.ofEpochMilli(task.createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString(),
                word("资料 $uploaded/${view.transfers.size} 份","資料 $uploaded/${view.transfers.size} 件"),
                view.remote?.inputRevision?.let{word("输入第${it}版","入力 第${it}版")}).filterNotNull().joinToString(" · "),if(journal)11 else 9,color=t.muted,number=true)
            CloudTrack(phase.step(),warn)
            task.lastError?.let{YText(localized(it),11,color=t.warm)}
            if(phase==CloudPhase.NEEDS_INFO)view.latestEventMessages.firstOrNull()?.let{YText(word("分析台留言：","分析デスクより：")+it,12,color=t.warm)}
            view.claimable?.let{r->
                r.summary?.takeIf{it.isNotBlank()}?.let{YText(it,12)}
                r.validation.warnings.forEach{YText("· "+it,11,color=t.warm)}
            }
            view.ackWarnings.forEach{YText(localized(it),10,color=t.warm)}
            if(view.ackPending)YText(word("本地已导入，回执待发送。不会重复导入。","端末では取込済み。受領通知の送信待ちです。重複取込はしません。"),10,color=t.muted)
            FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                when(phase) {
                    CloudPhase.READY->YButton(word("领取结果并预览","結果を受け取って確認"),onClaim,Modifier.testTag("cloud-claim-${task.localId}"),primary=true,enabled=!busy)
                    CloudPhase.BLOCKED,CloudPhase.PREPARING->YButton(word("继续上传","アップロードを再開"),onRetry,Modifier.testTag("cloud-retry-${task.localId}"),enabled=!busy)
                    CloudPhase.NEEDS_INFO->YButton(word("补充资料","資料を追加"),onSupplement,Modifier.testTag("cloud-supplement-${task.localId}"),enabled=!busy)
                    else->{}
                }
                if(task.taskId!=null&&phase!=CloudPhase.IMPORTED)YButton(word("刷新状态","状態を更新"),onRefresh,Modifier.testTag("cloud-refresh-${task.localId}"),enabled=!busy)
                if(task.taskId==null)YButton(word("放弃","破棄"),onDiscard,Modifier.testTag("cloud-discard-${task.localId}"),enabled=!busy)
            }
        }
    }
}

@Composable private fun CloudConnectSheet(vm:AppViewModel,cloud:CloudRepository,onClose:()->Unit,onBound:()->Unit) {
    val context=LocalContext.current
    val busy by vm.busy.collectAsStateWithLifecycle()
    var raw by rememberSaveable{mutableStateOf("")}
    var parsed by remember{mutableStateOf<CloudConnectionConfig?>(null)}
    fun parse(text:String){var result:CloudConnectionConfig?=null;vm.execute(onSuccess={parsed=result}){result=cloud.parseConfig(text)}}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null){var text="";vm.execute(onSuccess={raw=text;parse(text)}){text=readUtf8Document(context,uri)}}}
    YSheet(word("导入连接配置","接続設定を取込"),{if(!busy)onClose()}) {
        val config=parsed
        if(config==null) {
            YText(word("在网页工作台的「设备连接」生成配置。它只显示一次，请勿放进聊天、截图或源码。","Webデスクの「端末接続」で設定を作成します。一度だけ表示されるため、チャット・スクリーンショット・ソースに置かないでください。"),12,color=LocalYohaku.current.muted,lineHeightMultiplier=1.9f)
            YField(word("粘贴配置 JSON","設定JSONを貼り付け"),raw,{raw=it},Modifier.testTag("cloud-config-text"),multiline=true)
            YButton(word("检查配置","設定を確認"),{parse(raw)},Modifier.fillMaxWidth().testTag("cloud-config-parse"),primary=true,enabled=!busy&&raw.isNotBlank())
            YButton(word("选择配置文件","設定ファイルを選択"),{picker.launch(arrayOf("application/json","text/plain","application/octet-stream"))},Modifier.fillMaxWidth().testTag("cloud-config-file"),enabled=!busy)
        } else {
            YText(word("请确认目标网站与设备。确认后加密保存并测试连接。","接続先と端末を確認してください。確定すると暗号化保存し、接続テストを行います。"),12,color=LocalYohaku.current.muted)
            RecordFacts(listOf(word("网站","サイト") to config.baseUrl.substringAfter("://"),word("设备 ID","端末ID") to config.id,word("接口版本","APIバージョン") to config.apiVersion.toString()),framed=true)
            YActionRow {
                YButton(word("确认保存并测试","保存して接続テスト"),{vm.execute(onSuccess={raw="";onBound()}){cloud.bind(config)}},Modifier.testTag("cloud-config-confirm"),primary=true,enabled=!busy)
                YLink(word("返回修改","戻って修正"),{parsed=null})
            }
        }
    }
}

/** Shared handoff form: title, free note, picked source files. Files are copied in before anything is sent. */
@Composable private fun CloudGeneralSheet(s:BookSnapshot,vm:AppViewModel,cloud:CloudRepository,onClose:()->Unit,onQueued:()->Unit) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    var title by rememberSaveable(s.book.id){mutableStateOf("")}
    var note by rememberSaveable(s.book.id){mutableStateOf("")}
    var files by remember(s.book.id){mutableStateOf(listOf<Uri>())}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){uris->files=(files+uris).distinct()}
    YSheet(word("通用分析任务","汎用分析タスク"),{if(!busy)onClose()}) {
        YText(word("结果按文本或文件领取后另存，不会套用到小票或流水导入。","結果はテキスト・ファイルとして保存し、レシートや明細の取込には使いません。"),12,color=LocalYohaku.current.muted)
        YField(word("任务名称","タスク名"),title,{title=it.take(150)},Modifier.testTag("cloud-general-title"))
        YField(word("给分析台的说明","分析デスクへの説明"),note,{note=it},Modifier.testTag("cloud-general-note"),multiline=true)
        CloudPickedFiles(files,{files=files-it})
        YButton(word("添加文件","ファイルを追加"),{picker.launch(arrayOf("*/*"))},Modifier.fillMaxWidth().testTag("cloud-general-files"),enabled=!busy)
        YButton(word("上传并提交","アップロードして提出"),{vm.execute(onSuccess={onQueued()}){cloud.prepareGeneralTask(s.book.id,files,title,note)}},Modifier.fillMaxWidth().testTag("cloud-general-submit"),primary=true,enabled=!busy&&title.isNotBlank()&&files.isNotEmpty())
    }
}

@Composable internal fun CloudPickedFiles(files:List<Uri>,onRemove:(Uri)->Unit) {
    val context=LocalContext.current
    val t=LocalYohaku.current
    if(files.isEmpty())return
    ReceiptPaper(packageTree=true) {
        files.forEachIndexed{i,uri->
            val name=remember(uri){runCatching{context.contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use{c->if(c.moveToFirst())c.getString(0)else null}}.getOrNull()?:uri.lastPathSegment.orEmpty()}
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                YText((i+1).toString().padStart(2,'0'),10,color=t.muted,number=true)
                YText(name,11,Modifier.weight(1f))
                YLink(word("移除","削除"),{onRemove(uri)})
            }
        }
    }
}

/** Downloaded, verified result goes into the same local preview used for manual files. */
@Composable private fun CloudClaimReview(s:BookSnapshot,vm:AppViewModel,cloud:CloudRepository,claim:CloudClaim,onClose:()->Unit,onDone:(String)->Unit) {
    val context=LocalContext.current
    val busy by vm.busy.collectAsStateWithLifecycle()
    var statement by remember(claim.resultId){mutableStateOf<StatementImportPreview?>(null)}
    var receipt by remember(claim.resultId){mutableStateOf<ReceiptBackfillPreview?>(null)}
    var previewError by remember(claim.resultId){mutableStateOf<String?>(null)}
    var overlap by rememberSaveable(claim.resultId){mutableStateOf(false)}
    LaunchedEffect(claim.resultId) {
        if(claim.type=="general")return@LaunchedEffect
        try {
            require(claim.bookId==s.book.id){"结果属于其他账本，请切换后再领取 / 結果は別の帳簿のものです。切り替えてから受け取ってください"}
            if(claim.type=="statement")statement=vm.repository.previewStatement(claim.bookId,claim.text)
            else receipt=vm.repository.previewReceiptItems(claim.bookId,claim.text)
        }catch(e:Exception){previewError=e.message?:"预览失败 / プレビューできません"}
    }
    val directory=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()){uri->if(uri!=null){
        val name="yohaku-cloud-${claim.taskId.take(8)}-v${claim.resultVersion}.txt"
        vm.execute(onSuccess={onDone("结果已保存 / 結果を保存しました")}) {
            writeNewTreeDocument(context,uri,name,"text/plain"){out->claim.file.inputStream().use{it.copyTo(out)}}
            cloud.recordGeneralSaved(claim,name)
        }
    }}
    val r=receipt
    if(r!=null) {
        ReceiptBackfillSheet(s,r,busy,onClose={if(!busy)onClose()},onReselect={if(!busy)onClose()},onSave={stale,replacement->
            vm.execute(onSuccess={onDone("商品明细已保存，回执已排队 / 商品明細を保存し、受領通知を予約しました")}) {
                try{cloud.saveClaimedReceiptItems(claim,r,stale,replacement)}catch(e:Exception){cloud.reportFailed(claim);throw e}
            }
        })
        return
    }
    YSheet(word("云端结果预览","クラウド結果の確認"),{if(!busy)onClose()}) {
        ReceiptHandoffSummary("RESULT v${claim.resultVersion} / ${claim.sha256.take(12)}",cloudTypeLabel(claim.type),word("大小与校验值已核对。确认前不会写入账本。","サイズとチェックサムを確認済み。確定するまで帳簿には書き込みません。"))
        claim.summary?.takeIf{it.isNotBlank()}?.let{YText(it,12)}
        claim.warnings.forEach{YText("· "+it,11,color=LocalYohaku.current.warm)}
        previewError?.let {
            YText(localized(it),12,color=LocalYohaku.current.warm)
            YText(word("结果保留在云端，可以修正本地资料后再次领取；确认无法导入时可向分析台报告。","結果はクラウドに残ります。端末の資料を直して再度受け取るか、取り込めない場合は分析デスクへ報告できます。"),11,color=LocalYohaku.current.muted)
            YButton(word("报告无法导入","取込不可を報告"),{vm.execute(onSuccess={onDone("已报告导入失败 / 取込失敗を報告しました")}){cloud.reportFailed(claim)}},Modifier.testTag("cloud-report-failed"),enabled=!busy)
        }
        statement?.let{p->StatementPreviewContent(s,vm,p,overlap,{overlap=it},busy,onConfirm={
            var id:String?=null
            vm.execute(onSuccess={vm.selectedStatementBatch.value=id;onClose();vm.navigate("statements")}) {
                id=try{cloud.saveClaimedStatement(claim,p,overlap)}catch(e:Exception){cloud.reportFailed(claim);throw e}
            }
        },onCancel=onClose)}
        if(claim.type=="general") {
            ReceiptPaper{YText(claim.text.take(4000),11)}
            YButton(word("选择文件夹保存结果","フォルダを選んで保存"),{directory.launch(null)},Modifier.fillMaxWidth().testTag("cloud-general-save"),primary=true,enabled=!busy)
        }
        YLink(word("稍后再领取","あとで受け取る"),onClose,Modifier.testTag("cloud-claim-later"))
    }
}

/** Entry strip on the receipt page. Magazine: a ruled dispatch line; journal: a pinned note; objects: a parcel pill. */
@Composable internal fun CloudHandoffStrip(title:String,description:String,tag:String,content:@Composable ColumnScope.()->Unit) {
    val t=LocalYohaku.current
    val frame=when(t.id) {
        "magazine"->Modifier.drawBehind{drawLine(t.ink,Offset.Zero,Offset(size.width,0f),1.dp.toPx());drawLine(t.line,Offset(0f,size.height),Offset(size.width,size.height),1.dp.toPx())}.padding(vertical=17.dp)
        "journal"->Modifier.rotate(.6f).background(Color(0xffeef1e6)).drawBehind{drawRect(t.accent.copy(alpha=.45f),style=Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx()))))}.padding(start=16.dp,end=16.dp,top=20.dp,bottom=16.dp)
        else->Modifier.background(Color(0xffe8e9dc),RoundedCornerShape(24.dp,24.dp,30.dp,24.dp)).padding(horizontal=18.dp,vertical=18.dp)
    }
    Box(Modifier.fillMaxWidth().padding(top=6.dp).testTag(tag).then(frame)) {
        if(t.id=="journal")Box(Modifier.align(Alignment.TopCenter).offset(y=(-27).dp).size(12.dp).background(t.warm.copy(alpha=.8f),CircleShape))
        Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(9.dp)) {
                when(t.id) {
                    "magazine"->YText("→ CLOUD",8,color=t.accent,letterSpacing=1.5f)
                    "journal"->YText("✳",15,Modifier.clearAndSetSemantics{},color=t.warm)
                    else->Box(Modifier.size(28.dp).background(Color(0xffd5dbbf),RoundedCornerShape(10.dp)).padding(6.dp)){YIcon("file",Modifier.fillMaxSize(),Color(0xff7b8663))}
                }
                YText(title,when(t.id){"journal"->18;"objects"->15;else->16},weight=if(t.id=="objects")FontWeight.Medium else FontWeight.Normal)
            }
            YText(description,if(t.id=="journal")12 else 10,color=t.muted,lineHeightMultiplier=1.9f)
            content()
        }
    }
}

@Composable private fun cloudReady(vm:AppViewModel):Boolean {
    val info by (vm.cloud?.connection?:return false).collectAsStateWithLifecycle()
    return info?.bound==true&&!info!!.needsReconnect
}

/** Receipts leave as the same original-byte ZIP parts; registration happens only after the cloud confirms submission. */
@Composable internal fun CloudReceiptHandoff(s:BookSnapshot,vm:AppViewModel,selected:List<String>,onQueued:()->Unit) {
    val cloud=vm.cloud?:return
    val busy by vm.busy.collectAsStateWithLifecycle()
    val ready=cloudReady(vm)
    var open by rememberSaveable(s.book.id){mutableStateOf(false)}
    var title by rememberSaveable(s.book.id){mutableStateOf("")}
    var note by rememberSaveable(s.book.id){mutableStateOf("")}
    val defaultTitle=word("${java.time.LocalDate.now()} 小票整理 · ${selected.size} 组","${java.time.LocalDate.now()} レシート整理・${selected.size} 組")
    CloudHandoffStrip(word("交给云端整理","クラウドで整理"),if(ready)word("所选小票图片按已保存的字节上传，提交确认后标记为已交云端。结果回来仍需本机预览。","選んだレシート画像をそのまま送信し、提出確認後に送信済みとして記録します。結果は端末で確認します。")else word("小票由云端分析台整理。先连接分析台，再上传所选小票。","レシートはクラウド分析デスクで整理します。先に接続してから、選んだレシートを送信します。"),"receipt-cloud-handoff") {
        if(ready)YButton(word("上传所选小票 ↗","選択したレシートを送信 ↗"),{title=defaultTitle;open=true},Modifier.fillMaxWidth().testTag("receipt-cloud-upload"),primary=true,enabled=!busy&&selected.isNotEmpty())
        else YButton(word("连接云端分析台","クラウド分析デスクに接続"),{vm.navigate("cloud")},Modifier.fillMaxWidth().testTag("receipt-cloud-connect"),primary=true)
    }
    if(open)YSheet(word("确认交给云端","クラウドへ渡す内容"),{if(!busy)open=false}) {
        ReceiptHandoffSummary("YOHAKU / CLOUD RECEIPTS",word("${selected.size} 组小票，保存的图片不再压缩。","${selected.size} 組のレシート。保存済み画像は再圧縮しません。"),word("单张小票的图片超过上传上限时无法提交，请先减少该小票的图片。","1枚のレシート画像が上限を超えると送信できません。画像を減らしてください。"))
        YField(word("任务名称","タスク名"),title,{title=it.take(150)},Modifier.testTag("receipt-cloud-title"))
        YField(word("补充说明（可选）","補足（任意）"),note,{note=it},Modifier.testTag("receipt-cloud-note"),multiline=true)
        YActionRow {
            YButton(word("打包并上传","まとめて送信"),{vm.execute(onSuccess={open=false;note="";onQueued()}){cloud.prepareReceiptTask(s.book.id,selected.toSet(),title,note)}},Modifier.testTag("receipt-cloud-confirm"),primary=true,enabled=!busy&&title.isNotBlank())
            YLink(word("返回小票工作台","レシート整理へ戻る"),{open=false})
        }
    }
}

/** Bank files travel with an identifiers file; the target account is fixed when the task is created. The caller frames the entry. */
@Composable internal fun CloudStatementHandoff(s:BookSnapshot,vm:AppViewModel) {
    val cloud=vm.cloud?:return
    val busy by vm.busy.collectAsStateWithLifecycle()
    val ready=cloudReady(vm)
    var open by rememberSaveable(s.book.id){mutableStateOf(false)}
    var account by rememberSaveable(s.book.id){mutableStateOf(s.accounts.firstOrNull()?.id.orEmpty())}
    var title by rememberSaveable(s.book.id){mutableStateOf("")}
    var note by rememberSaveable(s.book.id){mutableStateOf("")}
    var files by remember(s.book.id){mutableStateOf(listOf<Uri>())}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){uris->files=(files+uris).distinct()}
    val defaultTitle=word("${java.time.LocalDate.now()} 流水转换","${java.time.LocalDate.now()} 明細の変換")
    YText(if(ready)word("上传银行截图、PDF 或导出文件，并附上本账本的账户标识。返回的核对文件仍在这里预览确认。","銀行の画像・PDF・書出しファイルに口座IDを添えて送信します。戻った照合ファイルはここで確認します。")else word("原始流水由云端分析台转换。先连接分析台，再选择账户与资料。","元明細はクラウド分析デスクで変換します。先に接続してから、口座と資料を選びます。"),12,color=LocalYohaku.current.muted,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
    if(ready)YButton(word("选择账户与资料 ↗","口座と資料を選ぶ ↗"),{if(title.isBlank())title=defaultTitle;open=true},Modifier.fillMaxWidth().testTag("statement-cloud-start"),primary=true,enabled=!busy&&s.accounts.isNotEmpty())
    else YButton(word("连接云端分析台","クラウド分析デスクに接続"),{vm.navigate("cloud")},Modifier.fillMaxWidth().testTag("statement-cloud-connect"),primary=true)
    if(open)YSheet(word("交给云端转换流水","明細の変換を依頼"),{if(!busy)open=false}) {
        YSelect(word("目标账户","対象口座"),account,s.accounts.map{it.id to "${it.name} · ${it.currency}"},{account=it})
        YField(word("任务名称","タスク名"),title,{title=it.take(150)},Modifier.testTag("statement-cloud-title"))
        YField(word("资料来源与日期范围（可选）","資料の出典と期間（任意）"),note,{note=it},Modifier.testTag("statement-cloud-note"),multiline=true)
        CloudPickedFiles(files,{files=files-it})
        YButton(word("添加银行资料","銀行資料を追加"),{picker.launch(arrayOf("*/*"))},Modifier.fillMaxWidth().testTag("statement-cloud-files"),enabled=!busy)
        YText(word("每份文件须小于 100 MB。一个任务只对应一个账户；不明确的入账日会作为疑点返回，不会被猜测。","各ファイルは100 MB未満。1タスク1口座です。不明な計上日は推測せず疑問点として返されます。"),10,color=LocalYohaku.current.muted,lineHeightMultiplier=1.9f)
        YButton(word("上传并提交","アップロードして提出"),{vm.execute(onSuccess={open=false;files=emptyList();note="";vm.navigate("cloud")}){cloud.prepareStatementTask(s.book.id,account,files,title,note)}},Modifier.fillMaxWidth().testTag("statement-cloud-confirm"),primary=true,enabled=!busy&&title.isNotBlank()&&files.isNotEmpty()&&account.isNotEmpty())
    }
}
