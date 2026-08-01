package app.ledger.core.network

import io.kotest.matchers.shouldBe
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.Test

class MockWebServerInfrastructureTest {
    @Test
    fun `MockWebServer allocates a local HTTPS-test endpoint`() {
        MockWebServer().use { server ->
            server.start()
            server.url("/exchange-rates").scheme shouldBe "http"
        }
    }
}
