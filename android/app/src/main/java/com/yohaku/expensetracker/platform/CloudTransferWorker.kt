package com.yohaku.expensetracker.platform

import android.content.Context
import androidx.work.*
import com.yohaku.expensetracker.YohakuApplication
import com.yohaku.expensetracker.data.cloud.cloudRetryable
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

class CloudTransferWorker(context:Context,parameters:WorkerParameters):CoroutineWorker(context,parameters) {
    override suspend fun doWork():Result {
        val localId=inputData.getString("localId") ?: return Result.failure()
        return try {
            (applicationContext as YohakuApplication).cloud.advance(localId)
            Result.success()
        } catch(e:CancellationException) { throw e }
        catch(e:Exception) { if(cloudRetryable(e))Result.retry() else Result.failure() }
    }
    companion object {
        fun enqueue(context:Context,localId:String) {
            WorkManager.getInstance(context).enqueueUniqueWork("cloud-task-$localId",ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<CloudTransferWorker>().setInputData(workDataOf("localId" to localId))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,10,TimeUnit.SECONDS).build())
        }
    }
}
