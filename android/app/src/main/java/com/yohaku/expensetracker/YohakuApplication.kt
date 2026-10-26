package com.yohaku.expensetracker
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import com.yohaku.expensetracker.data.repository.*
class YohakuApplication:android.app.Application() {
    val repository by lazy { LedgerRepository(com.yohaku.expensetracker.data.backup.openYohakuDatabase(this),mediaRoot=java.io.File(filesDir,"attachments")) }
    val cloud by lazy { com.yohaku.expensetracker.data.cloud.CloudRepository(this,repository) }
    private val applicationScope=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()+kotlinx.coroutines.Dispatchers.IO)
    val reminders by lazy { com.yohaku.expensetracker.platform.ReminderCoordinator(this) }
    override fun onCreate() {
        super.onCreate()
        if(getProcessName()!=packageName)return
        com.yohaku.expensetracker.data.backup.RestoreFiles.recoverOnStartup(this)
        // The default ContentProvider initializer runs BEFORE Application.onCreate. Delay it so no
        // surviving worker can open Room while an interrupted restore is being rolled back.
        androidx.work.WorkManager.initialize(this,androidx.work.Configuration.Builder().build())
        reminders.start()
        com.yohaku.expensetracker.platform.LocalMaintenanceWorker.schedule(this)
        applicationScope.launch { try { cloud.resumePending() } catch(e:Exception) { android.util.Log.e("YohakuCloud","Cloud resume skipped",e) } }
    }
    suspend fun stopForRestore() {
        applicationScope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()
        reminders.stopForRestore()
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            androidx.work.WorkManager.getInstance(this@YohakuApplication).cancelAllWork().result.get()
            repository.db.close()
        }
    }
    val preferences by lazy { AppPreferences(this) }
}
