package app.ledger.core.security

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle

fun interface VaultClipboardClearScheduler {
    fun schedule(delayMillis: Long, action: () -> Unit): AutoCloseable
}

private class HandlerClipboardClearScheduler : VaultClipboardClearScheduler {
    private val handler = Handler(Looper.getMainLooper())

    override fun schedule(delayMillis: Long, action: () -> Unit): AutoCloseable {
        val runnable = Runnable(action)
        handler.postDelayed(runnable, delayMillis)
        return AutoCloseable { handler.removeCallbacks(runnable) }
    }
}

/** The sole complete-card-number clipboard path. No security-code copy method exists. */
class VaultClipboardController(
    context: Context,
    private val clearDelayMillis: Long = DEFAULT_CLEAR_DELAY_MILLIS,
    private val scheduler: VaultClipboardClearScheduler = HandlerClipboardClearScheduler(),
) : AutoCloseable {
    private val clipboard = context.applicationContext.getSystemService(ClipboardManager::class.java)
    private var pendingClear: AutoCloseable? = null

    init {
        require(clearDelayMillis in 1L..DEFAULT_CLEAR_DELAY_MILLIS)
    }

    @Synchronized
    fun copyPrimaryNumber(value: SensitivePlaintext) {
        val clip = value.useBytes { bytes ->
            val visible = bytes.toString(Charsets.UTF_8)
            ClipData.newPlainText(CLIP_LABEL, visible).also { data ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    data.description.extras = (data.description.extras ?: PersistableBundle()).apply {
                        putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                    }
                }
            }
        }
        clipboard.setPrimaryClip(clip)
        pendingClear?.close()
        pendingClear = scheduler.schedule(clearDelayMillis, ::clearIfOwned)
    }

    @Synchronized
    fun clearIfOwned() {
        if (clipboard.primaryClipDescription?.label?.toString() == CLIP_LABEL) clipboard.clearPrimaryClip()
        pendingClear?.close()
        pendingClear = null
    }

    fun onApplicationBackgrounded() = clearIfOwned()

    override fun close() = clearIfOwned()

    companion object {
        const val DEFAULT_CLEAR_DELAY_MILLIS: Long = 30_000L
        private const val CLIP_LABEL = "ledger-vault-sensitive"
    }
}
