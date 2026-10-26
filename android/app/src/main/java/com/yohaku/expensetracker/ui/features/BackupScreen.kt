package com.yohaku.expensetracker.ui.features

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yohaku.expensetracker.YohakuApplication
import com.yohaku.expensetracker.data.backup.*
import com.yohaku.expensetracker.platform.RestoreActivity
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

internal data class BackupUiState(val busy:Boolean=false,val result:Pair<String,Long>?=null,val error:String?=null,val review:Pair<File,BackupManifest>?=null)
internal class BackupViewModel(private val app:YohakuApplication):AndroidViewModel(app) {
    val state=MutableStateFlow(BackupUiState())
    private val service=BackupService(app,app.repository,app.preferences)
    private fun run(action:suspend ()->Unit) {
        if(state.value.busy)return
        state.value=state.value.copy(busy=true,error=null,result=null)
        viewModelScope.launch {
            try{action()}catch(e:CancellationException){throw e}
            catch(e:Exception){state.value=state.value.copy(error=e.message?:e.javaClass.simpleName)}
            finally{state.value=state.value.copy(busy=false)}
        }
    }
    fun export(uri:Uri)=run{state.value=state.value.copy(result=service.exportDocument(uri))}
    fun inspect(uri:Uri)=run{
        withContext(Dispatchers.IO){state.value.review?.first?.deleteRecursively()}
        state.value=state.value.copy(review=null)
        state.value=state.value.copy(review=service.inspect(uri))
    }
    fun dismiss(){
        val stage=state.value.review?.first
        state.value=state.value.copy(review=null)
        viewModelScope.launch(Dispatchers.IO){stage?.deleteRecursively()}
    }
    fun restore(theme:String,language:String) {
        val stage=state.value.review?.first?:return
        run {
            // Keep the current process quiescent until the foreground restore activity terminates it.
            app.stopForRestore()
            app.startActivity(Intent(app,RestoreActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("mainPid",android.os.Process.myPid()).putExtra("stage",stage.name)
                .putExtra("theme",theme).putExtra("language",language))
            awaitCancellation()
        }
    }
    // Abandoned staging is removed at the next cold start, before any new backup operation.
}

@Composable internal fun BackupScreen() {
    val app=LocalContext.current.applicationContext as YohakuApplication
    val model:BackupViewModel=viewModel(factory=object:androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T:androidx.lifecycle.ViewModel> create(modelClass:Class<T>):T=BackupViewModel(app) as T
    })
    val state by model.state.collectAsState()
    val create=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){it?.let(model::export)}
    val open=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){it?.let(model::inspect)}
    val theme=LocalYohaku.current.id;val language=LocalLanguage.current
    BackupContent(state,{
        create.launch("yohaku-backup-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))}.yohaku")
    },{open.launch(arrayOf("*/*"))},{model.dismiss()},{model.restore(theme,language)})
}

@Composable internal fun BackupContent(state:BackupUiState,onExport:()->Unit,onOpen:()->Unit,onDismiss:()->Unit,onRestore:()->Unit) {
    val t=LocalYohaku.current
    YLead(word("把日常，好好留下。","日々の記録を、大切に。"),word("整份保存全部账本、附件原图与用户设置，换手机后也能继续。","すべての帳簿・添付原本・設定をまとめて保存。新しい端末でも続けられます。"),"BACKUP & RESTORE",pageFlow=true)
    Panel {
        YText(word("导出完整备份","完全バックアップを書き出す"),18)
        YText(word("备份文件不加密，请保存在你信任的位置。不自动备份，也不进行云同步。","ファイルは暗号化されません。信頼できる場所に保存してください。自動バックアップ・クラウド同期は行いません。"),12,color=t.muted)
        YButton(word("选择位置并导出","保存先を選んで書き出す"),onExport,Modifier.fillMaxWidth().testTag("backup-export"),primary=true,enabled=!state.busy)
    }
    Panel {
        YText(word("从备份恢复","バックアップから復元"),18)
        YText(word("恢复会替换本机全部数据。你可以先导出当前数据；恢复前不会自动备份。","復元すると端末の全データを置換します。必要なら先に現在のデータを書き出してください。復元前の自動保存はありません。"),12,color=t.muted)
        YButton(word("选择备份文件","バックアップを選ぶ"),onOpen,Modifier.fillMaxWidth().testTag("backup-open"),enabled=!state.busy)
    }
    YText(word("包含全部账本（含回收站与草稿）、账户、记录、分类、预算、附件及提醒设置。云端连接凭证与上传暂存不包含在备份内，本机连接保持不变。","全帳簿（ごみ箱・下書きを含む）、口座、記録、カテゴリ、予算、添付ファイル、通知設定を含みます。クラウド接続情報・アップロード一時ファイルは含まず、端末の接続情報は維持します。"),11,color=t.muted)
    state.result?.let{(name,size)->Panel{YText(word("备份已保存","バックアップを保存しました"),18);YText(name);YText(backupSize(size),12)}}
    state.error?.let{error->Panel{YText(word("操作失败","操作に失敗しました"),18,color=t.warm);YText(if(LocalLanguage.current=="ja")error.substringAfter(" / ",error)else error.substringBefore(" / "),12)}}
    if(state.busy)YSheet(word("正在处理","処理しています"),{}) {
        YText(word("文件较多时需要一些时间，请保持应用打开。","ファイルが多い場合は時間がかかります。アプリを開いたままお待ちください。"))
    } else state.review?.second?.let{manifest->BackupConfirmation(manifest,onDismiss,onRestore)}
}

@Composable internal fun BackupConfirmation(manifest:BackupManifest,onDismiss:()->Unit,onRestore:()->Unit) {
    YSheet(word("确认整份恢复","全データの復元を確認"),onDismiss) {
        YText(word("将替换本机全部数据，当前数据不会保留。","端末の全データを置換します。現在のデータは残りません。"),18,color=LocalYohaku.current.warm)
        YText(word("可以先取消并导出一份当前数据；恢复前不会自动备份。完成后应用将重新启动。","必要ならキャンセルして現在のデータを書き出してください。自動保存は行いません。完了後にアプリを再起動します。"),12)
        YText(word("创建时间：","作成日時：")+java.time.Instant.parse(manifest.createdAt).atZone(java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")),12)
        YText(word("应用版本：","アプリバージョン：")+manifest.versionName+" (${manifest.versionCode})",12)
        manifest.books.forEach{book->YText("${book.name} · ${book.recordCount} "+word("条记录","件の記録"),14)}
        YText(word("附件：","添付ファイル：")+"${manifest.attachmentCount} · ${backupSize(manifest.attachmentBytes)}",12)
        YButton(word("替换全部数据并重启","全データを置換して再起動"),onRestore,Modifier.fillMaxWidth().testTag("backup-confirm"),primary=true)
        YButton(word("取消，保留当前数据","キャンセル・現在のデータを保持"),onDismiss,Modifier.fillMaxWidth())
    }
}
private fun backupSize(bytes:Long)=if(bytes<1_000_000)"${bytes / 1000} KB ($bytes B)" else String.format(java.util.Locale.ROOT,"%.1f MB",bytes/1_000_000.0)
