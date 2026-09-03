package app.ledger.finance.application

import app.ledger.core.common.StableId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

class RevisionAwareLedgerCacheTest {
    @Test
    fun `cache never reuses values across books generations or local revisions`() {
        val cache = RevisionAwareBoundedCache<Query, String>(4)
        val firstBook = id(1)
        val key = key(firstBook, generation = 7, localRevision = 11, valuationRevision = null, Query.Journal)
        cache.put(key, "current")

        cache.get(key) shouldBe "current"
        cache.get(key(id(2), 7, 11, null, Query.Journal)) shouldBe null
        cache.get(key(firstBook, 8, 11, null, Query.Journal)) shouldBe null
        cache.get(key(firstBook, 7, 12, null, Query.Journal)) shouldBe null
    }

    @Test
    fun `valuation-sensitive keys reject a different valuation revision`() {
        val cache = RevisionAwareBoundedCache<Query, String>(2)
        val current = key(id(1), 3, 9, 4, Query.Accounts)
        cache.put(current, "valued")

        cache.get(current) shouldBe "valued"
        cache.get(key(id(1), 3, 9, 5, Query.Accounts)) shouldBe null
        cache.get(key(id(1), 3, 9, null, Query.Accounts)) shouldBe null
    }

    @Test
    fun `least recently used entry is evicted at the explicit bound`() {
        val cache = RevisionAwareBoundedCache<Query, String>(2)
        val first = key(id(1), 1, 1, null, Query.Entry)
        val second = key(id(1), 1, 1, null, Query.Journal)
        val third = key(id(1), 1, 1, 1, Query.Accounts)
        cache.put(first, "first")
        cache.put(second, "second")
        cache.get(first) shouldBe "first"

        cache.put(third, "third")

        cache.size() shouldBe 2
        cache.get(second) shouldBe null
        cache.get(first) shouldBe "first"
        cache.get(third) shouldBe "third"
    }

    @Test
    fun `scope invalidation is targeted and book clear removes every scope`() {
        val cache = RevisionAwareBoundedCache<Query, String>(4)
        val firstBook = id(1)
        val secondBook = id(2)
        val accounts = key(firstBook, 1, 1, 1, Query.Accounts)
        val journal = key(firstBook, 1, 1, null, Query.Journal)
        val otherBook = key(secondBook, 1, 1, null, Query.Journal)
        cache.put(accounts, "accounts")
        cache.put(journal, "journal")
        cache.put(otherBook, "other")

        cache.invalidate(firstBook, setOf(LedgerDataScope.ACCOUNT_SUMMARIES))
        cache.get(accounts) shouldBe null
        cache.get(journal) shouldBe "journal"
        cache.get(otherBook) shouldBe "other"

        cache.clearBook(firstBook)
        cache.get(journal) shouldBe null
        cache.get(otherBook) shouldBe "other"
    }

    private fun key(
        bookId: StableId,
        generation: Long,
        localRevision: Long,
        valuationRevision: Long?,
        query: Query,
    ): LedgerRevisionCacheKey<Query> = LedgerRevisionCacheKey(
        bookId,
        generation,
        localRevision,
        valuationRevision,
        query,
    )

    private fun id(value: Int): StableId = StableId.fromUuid(UUID(0L, value.toLong()))

    private sealed interface Query : LedgerQueryKey {
        data object Entry : Query {
            override val scope: LedgerDataScope = LedgerDataScope.ENTRY_REFERENCES
        }

        data object Accounts : Query {
            override val scope: LedgerDataScope = LedgerDataScope.ACCOUNT_SUMMARIES
        }

        data object Journal : Query {
            override val scope: LedgerDataScope = LedgerDataScope.JOURNAL
        }
    }
}
