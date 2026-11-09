package com.yohaku.expensetracker.receipt.scan

import android.content.Context
import android.os.Process
import androidx.work.*
import java.util.concurrent.TimeUnit

/** No expedited work, no startup model IO. Missing/invalid contexts are compiled serially. */
class ScannerWarmupWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        val priority = Process.getThreadPriority(Process.myTid())
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        try {
            ScannerModels(applicationContext).use { it.warmup { isStopped } }
            return Result.success()
        } finally { Process.setThreadPriority(priority) }
    }
    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("receipt-scanner-model-warmup", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ScannerWarmupWorker>().setInitialDelay(15, TimeUnit.SECONDS)
                    .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build()).build())
        }
    }
}
