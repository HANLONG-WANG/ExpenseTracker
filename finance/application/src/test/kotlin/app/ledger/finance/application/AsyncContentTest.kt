package app.ledger.finance.application

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AsyncContentTest {
    @Test
    fun `same load key reuses one active request`() = runTest {
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val states = mutableListOf<AsyncContent<String>>()
        val loader = SingleFlightLoader<String, String>(this, {
            calls += 1
            release.await()
            LoadResult.Success(it)
        }) { _, state -> states += state }

        val first = loader.request("record")
        val second = loader.request("record")
        release.complete(Unit)
        first.join()

        first shouldBe second
        calls shouldBe 1
        states shouldBe listOf(AsyncContent.Loading(null), AsyncContent.Content("record"))
    }

    @Test
    fun `superseded cancellation-ignoring request cannot overwrite newer content`() = runTest {
        val firstRelease = CompletableDeferred<Unit>()
        val states = mutableListOf<Pair<String, AsyncContent<String>>>()
        val loader = SingleFlightLoader<String, String>(this, { key ->
            if (key == "old") withContext(NonCancellable) { firstRelease.await() }
            LoadResult.Success(key)
        }) { key, state -> states += key to state }

        val old = loader.request("old", previous = "previous")
        val current = loader.request("current", previous = "previous")
        current.join()
        firstRelease.complete(Unit)
        old.join()

        states.last() shouldBe ("current" to AsyncContent.Content("current"))
        states.none { it == "old" to AsyncContent.Content("old") } shouldBe true
    }

    @Test
    fun `refresh failure retains previous content with a sanitized code`() = runTest {
        val states = mutableListOf<AsyncContent<String>>()
        val loader = SingleFlightLoader<String, String>(this, {
            LoadResult.Failure("DATABASE_UNAVAILABLE")
        }) { _, state -> states += state }

        loader.request("accounts", previous = "visible").join()

        states shouldBe listOf(
            AsyncContent.Loading("visible"),
            AsyncContent.Failure("visible", "DATABASE_UNAVAILABLE"),
        )
    }

    @Test
    fun `single flight timeout becomes retryable failure and retains previous content`() = runTest {
        val states = mutableListOf<AsyncContent<String>>()
        val loader = SingleFlightLoader<String, String>(
            scope = this,
            worker = {
                kotlinx.coroutines.delay(101L)
                LoadResult.Success(it)
            },
            timeoutMillis = 100L,
            publish = { _, state -> states += state },
        )

        val job = loader.request("journal", previous = "visible")
        advanceTimeBy(100L)
        runCurrent()
        job.join()

        states shouldBe listOf(
            AsyncContent.Loading("visible"),
            AsyncContent.Failure("visible", "LOAD_TIMEOUT"),
        )
    }

    @Test
    fun `search debounce publishes only the latest distinct key`() = runTest {
        val calls = mutableListOf<String>()
        val published = mutableListOf<String>()
        val loader = DebouncedSingleFlightLoader<String, String>(this, 300L, { key ->
            calls += key
            key.uppercase()
        }) { _, value -> published += value }

        loader.request("m")
        advanceTimeBy(150L)
        loader.request("me")
        advanceTimeBy(299L)
        runCurrent()
        calls shouldBe emptyList()
        advanceTimeBy(1L)
        runCurrent()

        calls shouldBe listOf("me")
        published shouldBe listOf("ME")
        loader.request("me") shouldBe null
        runCurrent()
        calls shouldBe listOf("me")
    }

    @Test
    fun `debounced cancellation-ignoring work cannot publish stale result`() = runTest {
        val oldRelease = CompletableDeferred<Unit>()
        val published = mutableListOf<String>()
        val loader = DebouncedSingleFlightLoader<String, String>(this, 300L, { key ->
            if (key == "old") withContext(NonCancellable) { oldRelease.await() }
            key
        }) { _, value -> published += value }

        val old = requireNotNull(loader.request("old"))
        advanceTimeBy(300L)
        runCurrent()
        val current = requireNotNull(loader.request("current"))
        advanceTimeBy(300L)
        current.join()
        oldRelease.complete(Unit)
        old.join()

        published shouldBe listOf("current")
    }

    @Test
    fun `debounced worker timeout clears pending state without publishing a result`() = runTest {
        val timedOut = mutableListOf<String>()
        val published = mutableListOf<String>()
        val loader = DebouncedSingleFlightLoader<String, String>(
            scope = this,
            delayMillis = 0L,
            worker = { key ->
                kotlinx.coroutines.delay(101L)
                key
            },
            timeoutMillis = 100L,
            onTimeout = { timedOut += it },
            publish = { _, value -> published += value },
        )

        val job = requireNotNull(loader.request("refund"))
        advanceTimeBy(100L)
        runCurrent()
        job.join()

        timedOut shouldBe listOf("refund")
        published shouldBe emptyList()
    }

    @Test
    fun `keyed registry coalesces same key and supersedes changed key`() {
        val registry = KeyedLoadRegistry<String, String>()
        val old = requireNotNull(registry.begin("refund", "old"))

        registry.begin("refund", "old") shouldBe null
        val current = requireNotNull(registry.begin("refund", "current"))

        registry.isCurrent(old) shouldBe false
        registry.isCurrent(current) shouldBe true
        registry.complete(current)
        registry.isCurrent(current) shouldBe false
    }
}
