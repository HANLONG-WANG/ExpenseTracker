package app.ledger.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Activity-retained, memory-only form storage. The app host owns the backing store; values are
 * deliberately not written to SavedStateHandle or a Bundle so secret drafts never reach disk.
 */
public interface LedgerRetainedStateStore {
    public fun read(scopeKey: String, stateKey: String): Any?
    public fun write(scopeKey: String, stateKey: String, value: Any?)
}

private object NoOpRetainedStateStore : LedgerRetainedStateStore {
    override fun read(scopeKey: String, stateKey: String): Any? = null
    override fun write(scopeKey: String, stateKey: String, value: Any?) = Unit
}

private object RetainedNullValue

public val LocalLedgerRetainedStateStore = staticCompositionLocalOf<LedgerRetainedStateStore> { NoOpRetainedStateStore }
public val LocalLedgerRetainedStateScopeKey = staticCompositionLocalOf { "" }

/** Called only for interactions inside an editor destination, never for the shell back button. */
public val LocalLedgerFormChangeReporter = compositionLocalOf<() -> Unit> { {} }

@Composable
public fun <T> rememberLedgerRetainedState(stateKey: String, initializer: () -> T): MutableState<T> {
    val store = LocalLedgerRetainedStateStore.current
    val scopeKey = LocalLedgerRetainedStateScopeKey.current
    @Suppress("UNCHECKED_CAST")
    return remember(store, scopeKey, stateKey) {
        val stored = if (scopeKey.isBlank()) null else store.read(scopeKey, stateKey)
        val initial = when (stored) {
            RetainedNullValue -> null as T
            null -> initializer()
            else -> stored as T
        }
        val delegate = mutableStateOf(initial)
        object : MutableState<T> by delegate {
            override var value: T
                get() = delegate.value
                set(value) {
                    delegate.value = value
                    if (scopeKey.isNotBlank()) store.write(scopeKey, stateKey, value ?: RetainedNullValue)
                }
        }
    }
}
