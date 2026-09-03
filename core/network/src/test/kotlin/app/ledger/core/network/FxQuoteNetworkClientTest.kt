package app.ledger.core.network

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

class FxQuoteNetworkClientTest {
    @Test
    fun `request sends only pair and date and parses exact decimal evidence`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse(code = 200, body = """{"amount":1.0,"base":"USD","date":"2026-08-03","rates":{"JPY":152.1250}}"""))
            server.start()
            val fetched = Instant.parse("2026-08-03T01:02:03Z")
            val client = OkHttpFxQuoteNetworkClient(OkHttpClient(), server.url("/"), { fetched }, maxRetries = 0)

            val result = assertInstanceOf(
                NetworkFxQuoteResult.Available::class.java,
                client.quote(NetworkFxQuoteRequest("USD", "JPY", LocalDate.parse("2026-08-03"))),
            )

            assertEquals(BigDecimal("152.125"), result.quote.rate)
            assertEquals(fetched, result.quote.fetchedAt)
            val request = server.takeRequest()
            assertEquals("/v1/2026-08-03?base=USD&symbols=JPY", request.url.encodedPath + "?" + request.url.encodedQuery)
            assertEquals(setOf("base", "symbols"), request.url.queryParameterNames)
        }
    }

    @Test
    fun `retry is bounded and malformed response fails closed`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse(code = 503))
            server.enqueue(MockResponse(code = 200, body = """{"base":"EUR","date":"2026-08-03","rates":{"JPY":0}}"""))
            server.start()
            val client = OkHttpFxQuoteNetworkClient(OkHttpClient(), server.url("/"), { Instant.EPOCH })

            assertInstanceOf(NetworkFxQuoteResult.InvalidResponse::class.java, client.quote(NetworkFxQuoteRequest("USD", "JPY", null)))
            assertEquals(2, server.requestCount)
        }
    }
}
