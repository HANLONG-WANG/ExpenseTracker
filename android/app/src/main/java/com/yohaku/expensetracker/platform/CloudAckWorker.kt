package com.yohaku.expensetracker.platform

import android.content.Context
import androidx.work.*
import com.yohaku.expensetracker.YohakuApplication
import java.util.concurrent.TimeUnit

class CloudAckWorker(context:Context,parameters:WorkerParameters):CoroutineWorker(context,parameters) {
    override suspend fun doWork():Result = if((applicationContext as YohakuApplication).cloud.sendPendingAcks())Result.retry() else Result.success()
    companion object {
        fun enqueue(context:Context) {
            // Append closes the race between committing a new ack and a running worker's final query.
            WorkManager.getInstance(context).enqueueUniqueWork("cloud-acks",ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<CloudAckWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,10,TimeUnit.SECONDS).build())
        }
    }
}
