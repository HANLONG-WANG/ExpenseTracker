package app.ledger.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.ledger.core.common.DomainResult
import app.ledger.core.common.StableId
import app.ledger.core.common.StableIdSource
import app.ledger.core.common.getOrNull
import app.ledger.core.time.LedgerClock
import app.ledger.finance.application.ControlledPurgeApplicationPort
import app.ledger.finance.application.ControlledPurgeRequest
import app.ledger.finance.application.JournalApplicationPort
import app.ledger.finance.application.JournalPageRequest
import app.ledger.finance.domain.TransactionFilter
import app.ledger.finance.domain.TransactionLifecycleState
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

/** Periodically assesses every expired trash item through the same guarded purge port as the UI. */
internal class TrashAutoPurgeWorker(
    applicationContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(applicationContext, workerParameters) {
    override suspend fun doWork(): Result {
        val dependencies = EntryPointAccessors.fromApplication(applicationContext, TrashAutoPurgeEntryPoint::class.java)
        val settings = dependencies.settings().current()
        val bookId = StableId.fromBytes(settings.bookId.toByteArray()).getOrNull() ?: return Result.success()
        val now = dependencies.clock().now()
        var cursor: app.ledger.finance.application.CurrentTransactionCursor? = null
        do {
            val page = when (
                val result = dependencies.journal().page(
                    JournalPageRequest(
                        bookId = bookId,
                        filter = TransactionFilter(lifecycleStates = setOf(TransactionLifecycleState.TRASHED)),
                        limit = PAGE_SIZE,
                        cursor = cursor,
                    ),
                )
            ) {
                is DomainResult.Success -> result.value
                is DomainResult.Failure -> return if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
            }
            page.items.filter { item -> item.purgeAfter?.let { it <= now } == true }.forEach { item ->
                val assessment = when (val result = dependencies.purge().assess(bookId, item.transactionId, now)) {
                    is DomainResult.Success -> result.value
                    is DomainResult.Failure -> return@forEach
                }
                if (assessment.canPurgeNow) {
                    val ids = dependencies.stableIds()
                    val reserved = mutableSetOf(bookId, item.transactionId, item.revisionId)
                    fun nextUnique(): StableId {
                        var candidate = ids.nextStableId()
                        while (!reserved.add(candidate)) candidate = ids.nextStableId()
                        return candidate
                    }
                    dependencies.purge().purge(
                        ControlledPurgeRequest(
                            bookId = bookId,
                            commandId = nextUnique(),
                            transactionId = item.transactionId,
                            expectedRevisionId = item.revisionId,
                            purgeCommitId = nextUnique(),
                            deviceInstanceId = nextUnique(),
                            evaluatedAt = assessment.evaluatedAt,
                        ),
                    )
                }
            }
            cursor = page.nextCursor
        } while (cursor != null)
        return Result.success()
    }

    private companion object {
        const val PAGE_SIZE = 100
        const val MAX_RETRIES = 3
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface TrashAutoPurgeEntryPoint {
    fun settings(): AppSettingsRepository
    fun clock(): LedgerClock
    fun stableIds(): StableIdSource
    fun journal(): JournalApplicationPort
    fun purge(): ControlledPurgeApplicationPort
}

internal object TrashAutoPurgeScheduler {
    private const val STARTUP_WORK = "ledger-trash-auto-purge-startup"
    private const val PERIODIC_WORK = "ledger-trash-auto-purge-periodic"

    fun ensureScheduled(context: Context) {
        val constraints = Constraints.Builder().setRequiresStorageNotLow(true).build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            STARTUP_WORK,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<TrashAutoPurgeWorker>().setConstraints(constraints).build(),
        )
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<TrashAutoPurgeWorker>(24, TimeUnit.HOURS).setConstraints(constraints).build(),
        )
    }
}
