package app.ledger.core.database

import android.os.Trace
import java.util.concurrent.atomic.AtomicLong

/**
 * Privacy-safe, process-local counters for P37 architecture and performance gates.
 *
 * The runtime deliberately has no API accepting SQL text, identifiers, names, amounts, or other
 * user-controlled values. It is diagnostic state only and is never an authority for app behavior.
 */
object LedgerDatabasePerformance {
    private val primaryOpenCount = AtomicLong()
    private val primarySqlStatementCount = AtomicLong()
    private val financialCommitTransactionCount = AtomicLong()

    fun recordPrimaryOpen() {
        primaryOpenCount.incrementAndGet()
    }

    fun recordPrimarySqlStatement() {
        primarySqlStatementCount.incrementAndGet()
    }

    fun recordFinancialCommitTransaction() {
        financialCommitTransactionCount.incrementAndGet()
    }

    fun snapshot(): LedgerDatabasePerformanceSnapshot = LedgerDatabasePerformanceSnapshot(
        primaryOpenCount = primaryOpenCount.get(),
        primarySqlStatementCount = primarySqlStatementCount.get(),
        financialCommitTransactionCount = financialCommitTransactionCount.get(),
    )

    fun resetForTest() {
        primaryOpenCount.set(0L)
        primarySqlStatementCount.set(0L)
        financialCommitTransactionCount.set(0L)
    }

    fun <T> tracePrimaryOpen(block: () -> T): T {
        val started = runCatching { Trace.beginSection(PRIMARY_OPEN_TRACE) }.isSuccess
        return try {
            block()
        } finally {
            if (started) runCatching { Trace.endSection() }
        }
    }

    private const val PRIMARY_OPEN_TRACE = "P37/primary_open"
}

data class LedgerDatabasePerformanceSnapshot(
    val primaryOpenCount: Long,
    val primarySqlStatementCount: Long,
    val financialCommitTransactionCount: Long,
) {
    operator fun minus(earlier: LedgerDatabasePerformanceSnapshot): LedgerDatabasePerformanceSnapshot = LedgerDatabasePerformanceSnapshot(
        primaryOpenCount = primaryOpenCount - earlier.primaryOpenCount,
        primarySqlStatementCount = primarySqlStatementCount - earlier.primarySqlStatementCount,
        financialCommitTransactionCount = financialCommitTransactionCount - earlier.financialCommitTransactionCount,
    )
}
