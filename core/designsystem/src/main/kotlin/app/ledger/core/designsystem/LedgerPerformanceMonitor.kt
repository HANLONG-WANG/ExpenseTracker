package app.ledger.core.designsystem

import android.view.Window
import androidx.metrics.performance.JankStats
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** Closed, value-free scene names used by JankStats. Business values are never recorded. */
public enum class LedgerPerformanceScene {
    STARTUP,
    RECORD,
    JOURNAL,
    ACCOUNTS,
    BUDGET,
    ANALYSIS,
    MAP,
    REPORT,
    IMPORT,
    EXPORT,
    BACKUP,
    RESTORE,
}

public data class LedgerFrameAggregate(
    val frames: Long,
    val jankyFrames: Long,
    val maximumDurationNanos: Long,
)

/**
 * Process-local, bounded performance diagnostics. It deliberately stores only a closed scene enum
 * and aggregate timing counters; it cannot accept route arguments, text, money or identifiers.
 */
public object LedgerPerformanceRuntime {
    private val currentScene = AtomicReference(LedgerPerformanceScene.STARTUP)
    private val aggregates = ConcurrentHashMap<LedgerPerformanceScene, MutableAggregate>()

    public fun enter(scene: LedgerPerformanceScene) {
        currentScene.set(scene)
    }

    internal fun record(durationNanos: Long, isJank: Boolean) {
        require(durationNanos >= 0L)
        aggregates.computeIfAbsent(currentScene.get()) { MutableAggregate() }.record(durationNanos, isJank)
    }

    public fun snapshot(): Map<LedgerPerformanceScene, LedgerFrameAggregate> = aggregates.entries.associate { (scene, aggregate) -> scene to aggregate.snapshot() }

    public fun clear() {
        aggregates.clear()
        currentScene.set(LedgerPerformanceScene.STARTUP)
    }

    private class MutableAggregate {
        private var frames: Long = 0L
        private var jankyFrames: Long = 0L
        private var maximumDurationNanos: Long = 0L

        @Synchronized
        fun record(durationNanos: Long, isJank: Boolean) {
            frames = Math.addExact(frames, 1L)
            if (isJank) jankyFrames = Math.addExact(jankyFrames, 1L)
            maximumDurationNanos = maxOf(maximumDurationNanos, durationNanos)
        }

        @Synchronized
        fun snapshot(): LedgerFrameAggregate = LedgerFrameAggregate(frames, jankyFrames, maximumDurationNanos)
    }
}

/** Activity-owned JankStats lifecycle. Closing it immediately stops frame tracking. */
public class LedgerJankMonitor private constructor(private val stats: JankStats) : AutoCloseable {
    override fun close() {
        stats.isTrackingEnabled = false
    }

    public companion object {
        public fun attach(window: Window): LedgerJankMonitor {
            // JankStats requires an already-created DecorView. Accessing decorView is idempotent and
            // keeps this boundary safe even when a caller attaches before its first content view.
            window.decorView
            val stats = JankStats.createAndTrack(window) { frame ->
                LedgerPerformanceRuntime.record(frame.frameDurationUiNanos, frame.isJank)
            }
            return LedgerJankMonitor(stats)
        }
    }
}
