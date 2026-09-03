package app.ledger.finance.application

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

public sealed interface AsyncContent<out T> {
    public data object Empty : AsyncContent<Nothing>
    public data class Loading<T>(val previous: T? = null) : AsyncContent<T>
    public data class Content<T>(val value: T, val refreshing: Boolean = false) : AsyncContent<T>
    public data class Failure<T>(val previous: T?, val sanitizedCode: String) : AsyncContent<T>
}

public sealed interface LoadResult<out T> {
    public data class Success<T>(val value: T) : LoadResult<T>
    public data class Failure(val sanitizedCode: String) : LoadResult<Nothing> {
        init {
            require(SANITIZED_CODE.matches(sanitizedCode))
        }
    }

    private companion object {
        val SANITIZED_CODE: Regex = Regex("[A-Z][A-Z0-9_]{0,47}")
    }
}

/**
 * Owns at most one request. Same-key requests reuse the active job; a changed key supersedes it,
 * and a monotonically increasing token prevents a cancellation-ignoring worker from publishing.
 */
public class SingleFlightLoader<K : Any, T : Any>(
    private val scope: CoroutineScope,
    private val worker: suspend (K) -> LoadResult<T>,
    private val timeoutMillis: Long = DEFAULT_INTERACTIVE_LOAD_TIMEOUT_MILLIS,
    private val publish: (K, AsyncContent<T>) -> Unit,
) {
    init {
        require(timeoutMillis > 0L)
    }

    private val lock = Any()
    private var token: Long = 0L
    private var activeKey: K? = null
    private var activeJob: Job? = null

    public fun request(key: K, previous: T? = null): Job = synchronized(lock) {
        activeJob?.takeIf { it.isActive && activeKey == key }?.let { return@synchronized it }
        token += 1L
        val requestToken = token
        activeJob?.cancel()
        activeKey = key
        publish(key, AsyncContent.Loading(previous))
        scope.launch {
            val result = withTimeoutOrNull(timeoutMillis) { worker(key) }
                ?: LoadResult.Failure("LOAD_TIMEOUT")
            val current = synchronized(lock) { requestToken == token && activeKey == key }
            if (!current) return@launch
            when (result) {
                is LoadResult.Success -> publish(key, AsyncContent.Content(result.value))
                is LoadResult.Failure -> publish(key, AsyncContent.Failure(previous, result.sanitizedCode))
            }
            synchronized(lock) {
                if (requestToken == token) activeJob = null
            }
        }.also { activeJob = it }
    }

    public fun cancel() {
        synchronized(lock) {
            token += 1L
            activeKey = null
            activeJob?.cancel()
            activeJob = null
        }
    }
}

/**
 * Debounces a changing key while preserving single-flight/supersession semantics. A key is not
 * published twice until [reset] is called, and cancellation-ignoring work is fenced by a token.
 */
public class DebouncedSingleFlightLoader<K : Any, T : Any>(
    private val scope: CoroutineScope,
    private val delayMillis: Long,
    private val worker: suspend (K) -> T,
    private val timeoutMillis: Long = DEFAULT_INTERACTIVE_LOAD_TIMEOUT_MILLIS,
    private val onTimeout: (K) -> Unit = {},
    private val publish: (K, T) -> Unit,
) {
    init {
        require(delayMillis >= 0L)
        require(timeoutMillis > 0L)
    }

    private val lock = Any()
    private var token: Long = 0L
    private var activeKey: K? = null
    private var publishedKey: K? = null
    private var activeJob: Job? = null

    public fun request(key: K): Job? = synchronized(lock) {
        activeJob?.takeIf { it.isActive && activeKey == key }?.let { return@synchronized it }
        if (activeJob == null && publishedKey == key) return@synchronized null
        token += 1L
        val requestToken = token
        activeJob?.cancel()
        activeKey = key
        scope.launch {
            delay(delayMillis)
            val value = withTimeoutOrNull(timeoutMillis) { worker(key) }
            val current = synchronized(lock) { requestToken == token && activeKey == key }
            if (!current) return@launch
            if (value == null) onTimeout(key) else publish(key, value)
            synchronized(lock) {
                if (requestToken == token) {
                    publishedKey = key
                    activeKey = null
                    activeJob = null
                }
            }
        }.also { activeJob = it }
    }

    public fun reset() {
        synchronized(lock) {
            token += 1L
            activeKey = null
            publishedKey = null
            activeJob?.cancel()
            activeJob = null
        }
    }
}

public const val DEFAULT_INTERACTIVE_LOAD_TIMEOUT_MILLIS: Long = 15_000L

/** Token registry for route loaders whose result types differ. Changed keys supersede prior work. */
public class KeyedLoadRegistry<O : Any, K : Any> {
    public class Lease<O : Any, K : Any> internal constructor(
        public val owner: O,
        public val key: K,
        internal val token: Long,
    )

    private data class Active<K : Any>(val key: K, val token: Long)

    private val lock = Any()
    private var nextToken: Long = 0L
    private val active = mutableMapOf<O, Active<K>>()

    /** Returns null when the same owner/key is already active. */
    public fun begin(owner: O, key: K): Lease<O, K>? = synchronized(lock) {
        if (active[owner]?.key == key) return@synchronized null
        nextToken += 1L
        Active(key, nextToken).also { active[owner] = it }
        Lease(owner, key, nextToken)
    }

    public fun isCurrent(lease: Lease<O, K>): Boolean = synchronized(lock) {
        active[lease.owner] == Active(lease.key, lease.token)
    }

    public fun complete(lease: Lease<O, K>) {
        synchronized(lock) {
            if (active[lease.owner] == Active(lease.key, lease.token)) active.remove(lease.owner)
        }
    }

    public fun cancelAll() {
        synchronized(lock) {
            nextToken += 1L
            active.clear()
        }
    }
}
