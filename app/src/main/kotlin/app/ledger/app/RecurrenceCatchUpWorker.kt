package app.ledger.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.common.getOrNull
import app.ledger.core.time.LedgerClock
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Worker payload is deliberately restricted to the single opaque operation ID. */
internal class RecurrenceCatchUpWorker(
    applicationContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(applicationContext, workerParameters) {
    override suspend fun doWork(): Result {
        val operationId = if (inputData.keyValueMap.keys == setOf(INPUT_OPERATION_ID)) {
            inputData.getString(INPUT_OPERATION_ID)?.let { StableId.parse(it).getOrNull() }
        } else {
            null
        } ?: return Result.failure()
        val entryPoint = EntryPointAccessors.fromApplication(applicationContext, RecurrenceWorkerEntryPoint::class.java)
        val outcome = when (entryPoint.headlessExecutor().catchUp(operationId, entryPoint.clock().now())) {
            is DomainResult.Success -> Result.success()
            is DomainResult.Failure -> if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }
        return outcome
    }

    companion object {
        const val INPUT_OPERATION_ID = "operationId"
        private const val MAX_RETRIES = 5
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface RecurrenceWorkerEntryPoint {
    fun headlessExecutor(): HeadlessRecurrenceExecutor
    fun clock(): LedgerClock
}

internal object RecurrenceWorkScheduler {
    fun enqueueCatchUp(context: Context, operationId: StableId) {
        val request = catchUpRequest(operationId)
        WorkManager.getInstance(context).enqueueUniqueWork(uniqueName(operationId), ExistingWorkPolicy.KEEP, request)
    }

    /**
     * Enqueues both durable schedules after the foreground catch-up, then joins the unique
     * one-time worker. The periodic worker has a full-period initial delay, so it cannot duplicate
     * startup work inside the first interactive measurement window.
     */
    suspend fun scheduleStartupCatchUpAndAwait(context: Context, operationId: StableId) {
        val workManager = WorkManager.getInstance(context)
        val request = catchUpRequest(operationId)
        val enqueue = workManager.enqueueUniqueWork(uniqueName(operationId), ExistingWorkPolicy.KEEP, request)
        ensurePeriodicCatchUp(context, operationId)
        withContext(Dispatchers.IO) { enqueue.result.get() }
        while (true) {
            val work = withContext(Dispatchers.IO) {
                workManager.getWorkInfosForUniqueWork(uniqueName(operationId)).get()
            }
            if (work.isNotEmpty() && work.none { info -> !info.state.isFinished }) return
            delay(WORK_COMPLETION_POLL_MILLIS)
        }
    }

    fun ensurePeriodicCatchUp(context: Context, operationId: StableId) {
        val request = PeriodicWorkRequestBuilder<RecurrenceCatchUpWorker>(PERIODIC_HOURS, TimeUnit.HOURS)
            .setInputData(operationData(operationId))
            .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
            .setInitialDelay(PERIODIC_HOURS, TimeUnit.HOURS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(periodicName(operationId), ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private fun catchUpRequest(operationId: StableId) = OneTimeWorkRequestBuilder<RecurrenceCatchUpWorker>()
        .setInputData(operationData(operationId))
        .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
        .build()

    internal fun operationData(operationId: StableId): Data = Data.Builder().putString(RecurrenceCatchUpWorker.INPUT_OPERATION_ID, operationId.toString()).build()
    internal fun uniqueName(operationId: StableId): String = "recurrence-catch-up-$operationId"
    internal fun periodicName(operationId: StableId): String = "recurrence-periodic-$operationId"
    private const val PERIODIC_HOURS = 12L
    private const val WORK_COMPLETION_POLL_MILLIS = 100L
}
