package app.ledger.finance.application

import app.ledger.core.common.StableId
import java.util.LinkedHashMap

/** Finite semantic identity for one cached ledger query. */
public interface LedgerQueryKey {
    public val scope: LedgerDataScope
}

/**
 * A cache key is valid only for one open-book generation and the authoritative revisions read
 * from that generation. [valuationRevision] is omitted only for queries with no valued amounts.
 */
public data class LedgerRevisionCacheKey<Q : LedgerQueryKey>(
    val bookId: StableId,
    val sessionGeneration: Long,
    val localRevision: Long,
    val valuationRevision: Long?,
    val query: Q,
)

/** Small synchronized LRU used by route/query repositories; it never owns authoritative state. */
public class RevisionAwareBoundedCache<Q : LedgerQueryKey, V : Any>(maxEntries: Int) {
    private val lock = Any()
    private val entries = object : LinkedHashMap<LedgerRevisionCacheKey<Q>, V>(maxEntries, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LedgerRevisionCacheKey<Q>, V>?): Boolean = size > maxEntries
    }

    init {
        require(maxEntries > 0)
    }

    public fun get(key: LedgerRevisionCacheKey<Q>): V? = synchronized(lock) { entries[key] }

    public fun put(key: LedgerRevisionCacheKey<Q>, value: V) {
        synchronized(lock) { entries[key] = value }
    }

    public fun invalidate(bookId: StableId, scopes: Set<LedgerDataScope>) {
        synchronized(lock) {
            entries.keys.removeAll { key -> key.bookId == bookId && key.query.scope in scopes }
        }
    }

    public fun clearBook(bookId: StableId) {
        synchronized(lock) { entries.keys.removeAll { key -> key.bookId == bookId } }
    }

    public fun clear() {
        synchronized(lock) { entries.clear() }
    }

    public fun size(): Int = synchronized(lock) { entries.size }

    private companion object {
        private const val LOAD_FACTOR = 0.75f
    }
}

/** Process cache lifecycle hook used by commit, lock, switch, restore and recovery owners. */
public interface LedgerRevisionCacheControl {
    public fun committed(change: CommittedLedgerChange)
    public fun clearBook(bookId: StableId)
    public fun clearAll()
}
