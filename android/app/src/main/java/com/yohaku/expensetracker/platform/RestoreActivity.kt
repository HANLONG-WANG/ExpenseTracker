package com.yohaku.expensetracker.platform

import android.content.Intent
import android.os.Bundle
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yohaku.expensetracker.MainActivity
import com.yohaku.expensetracker.data.backup.*
import com.yohaku.expensetracker.ui.components.*
import com.yohaku.expensetracker.ui.theme.*
import kotlinx.coroutines.*
import java.io.File

/** Runs in :restore. It owns no application repositories, workers, or DataStore instance. */
class RestoreActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val theme=intent.getStringExtra("theme")?:"magazine"
        val language=intent.getStringExtra("language")?:"zh"
        setContent {YohakuTheme(theme,language){
            BackHandler { }
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),verticalArrangement=Arrangement.Center){
                YTitle(word("正在恢复","復元しています"),"RESTORE")
                YText(word("完成后将重新打开应用，请稍候。","完了後にアプリを開き直します。しばらくお待ちください。"))
            }
        }}
        // Survives configuration changes; a recreated activity must not launch a second replacement.
        if(!running) {
            running=true
            CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
                var result="恢复完成，已替换本机数据 / 復元が完了し、この端末のデータを置換しました"
                try {
                    RestoreFiles.locked(this@RestoreActivity) {
                        val pid=intent.getIntExtra("mainPid",-1)
                        require(pid>0&&pid!=Process.myPid())
                        Process.killProcess(pid)
                        // Ensure all old file handles are gone before any rename.
                        val deadline=System.currentTimeMillis()+10_000
                        while(File("/proc/$pid").exists()&&System.currentTimeMillis()<deadline)Thread.sleep(25)
                        check(!File("/proc/$pid").exists()){"无法停止旧进程 / 元のプロセスを停止できません"}
                        val stage=File(cacheDir,intent.getStringExtra("stage")?:error("Missing stage"))
                        require(stage.name.startsWith("backup-import-")&&stage.parentFile!!.canonicalFile==cacheDir.canonicalFile)
                        try {
                            val manifest=BackupArchive.json.decodeFromString<BackupManifest>(File(stage,"manifest.json").readText())
                            // Recheck the private staging bytes immediately before destructive work.
                            runBlocking {prepareRestore(this@RestoreActivity,stage,manifest)}
                            RestoreFiles.replace(RestoreFiles.layout(this@RestoreActivity),stage)
                        }finally{stage.deleteRecursively()}
                    }
                }catch(e:Exception){
                    android.util.Log.e("YohakuBackup","Restore failed",e)
                    val reason=e.message?:e.javaClass.simpleName
                    result="恢复失败：${reason.substringBefore(" / ")} / 復元に失敗しました：${reason.substringAfter(" / ",reason)}"
                }
                File(noBackupFilesDir,"backup-result.txt").writeText(result)
                withContext(Dispatchers.Main) {
                    startActivity(Intent(this@RestoreActivity,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                    finish()
                    Process.killProcess(Process.myPid())
                }
            }
        }
    }
    companion object { @Volatile private var running=false }
}
