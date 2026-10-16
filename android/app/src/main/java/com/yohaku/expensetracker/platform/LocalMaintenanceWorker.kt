package com.yohaku.expensetracker.platform

import android.content.Context
import androidx.work.*
import com.yohaku.expensetracker.YohakuApplication
import com.yohaku.expensetracker.data.repository.collectUnusedMedia
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/** Deferrable cleanup has no network requirement and never runs a permanent foreground service. */
class LocalMaintenanceWorker(context:Context,parameters:WorkerParameters):CoroutineWorker(context,parameters) {
    override suspend fun doWork():Result {
        val app=applicationContext as YohakuApplication
        return try {
            app.repository.purgeExpired()
            app.repository.collectUnusedMedia()
            app.preferences.pruneBookPreferences(app.repository.books.first().map{it.id}.toSet())
            app.reminders.rebuildSafely(false)
            Result.success()
        }catch(e:CancellationException){throw e}
        catch(e:Exception){android.util.Log.e("YohakuMaintenance","Local cleanup failed",e);Result.retry()}
    }
    companion object {
        const val UNIQUE_NAME="local-data-maintenance"
        fun schedule(context:Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(UNIQUE_NAME,ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<LocalMaintenanceWorker>(24,TimeUnit.HOURS).build())
        }
    }
}
