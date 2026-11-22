package com.yohaku.expensetracker.ui.features.scan

internal class ScanFrameFailures(private val log: (Int, Exception) -> Unit) {
    private var consecutive = 0
    @Volatile var count = 0
        private set
    private var lastLogMs: Long? = null

    fun <T> dropping(nowMs: Long, work: () -> T): T? = try { work().also { consecutive = 0 } } catch (e: Exception) {
        record(nowMs, e)
        if (++consecutive >= 3) throw e
        null
    }

    private fun record(nowMs: Long, error: Exception) {
        count++
        if (lastLogMs == null || nowMs - lastLogMs!! >= 5_000) {
            lastLogMs = nowMs
            log(count, error)
        }
    }
}
