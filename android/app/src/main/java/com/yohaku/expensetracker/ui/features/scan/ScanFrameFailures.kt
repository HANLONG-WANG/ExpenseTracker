package com.yohaku.expensetracker.ui.features.scan

/** A broken analysis frame is dropped; it never owns camera lifecycle or the error page.
 * Shared by the analyzer and overlay mapping, which run on different threads.
 */
internal class ScanFrameFailures(private val log: (Int, Exception) -> Unit) {
    @Volatile var count = 0
        private set
    private var lastLogMs: Long? = null

    fun <T> dropping(nowMs: Long, work: () -> T): T? = try { work() } catch (e: Exception) {
        record(nowMs, e)
        null
    }

    @Synchronized private fun record(nowMs: Long, error: Exception) {
        count++
        if (lastLogMs == null || nowMs - lastLogMs!! >= 5_000) {
            lastLogMs = nowMs
            log(count, error)
        }
    }
}
