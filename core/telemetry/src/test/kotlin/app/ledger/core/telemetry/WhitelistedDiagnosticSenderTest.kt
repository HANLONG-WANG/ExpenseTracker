package app.ledger.core.telemetry

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WhitelistedDiagnosticSenderTest {
    @Test
    fun `fixed feature payload has runtime metadata and no business text channel`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(204).build())
            val sender = WhitelistedHttpDiagnosticSender.forTest(
                server.url("/diagnostics"),
                OkHttpClient(),
                "1.0.0",
                36,
                DeviceCapabilityCategory.STRONG_BIOMETRIC_OR_CREDENTIAL,
            )
            val result = sender.sendFeature(
                "00112233445566778899aabbccddeeff",
                FeatureQueueEntry(
                    1_234L,
                    FeatureDiagnosticEvent(
                        FeatureEventName.FEATURE_OPENED,
                        FeatureEntry.VAULT,
                        DiagnosticOutcome.SUCCEEDED,
                        DurationBucket.NOT_MEASURED,
                    ),
                ),
            )
            assertTrue(result == SendDisposition.SENT)
            val body = server.takeRequest().body?.utf8().orEmpty()
            assertTrue(body.contains("\"schema\":\"ledger-feature-v1\""))
            assertTrue(body.contains("\"app_version\":\"1.0.0\""))
            assertTrue(body.contains("\"android_major\":36"))
            assertFalse(body.contains("amount", ignoreCase = true))
            assertFalse(body.contains("text", ignoreCase = true))
        }
    }

    @Test
    fun `fixed crash payload contains sanitized symbols only`() {
        val sender = WhitelistedHttpDiagnosticSender.forTest(
            okhttp3.HttpUrl.Builder().scheme("http").host("localhost").port(1).build(),
            OkHttpClient(),
        )
        val entry = CrashQueueEntry(
            9L,
            SanitizedCrashDiagnostic(
                CrashKind.APPLICATION_NOT_RESPONDING,
                SanitizedErrorCode.APPLICATION_NOT_RESPONDING,
                listOf(requireNotNull(SanitizedStackFrame.create("app.ledger.Worker", "execute", 7))),
            ),
        )
        val encoded = sender.encodeCrash("ffeeddccbbaa99887766554433221100", entry)
        assertTrue(encoded.contains("app.ledger.Worker"))
        assertFalse(encoded.contains("message"))
        assertFalse(encoded.contains("PAN-4111111111111111-CVC-123"))
    }

    @Test
    fun `network boundary drops a forged stack symbol containing a card number`() {
        MockWebServer().use { server ->
            server.start()
            val sender = WhitelistedHttpDiagnosticSender.forTest(server.url("/diagnostics"), OkHttpClient())
            val forged = CrashQueueEntry(
                10L,
                SanitizedCrashDiagnostic(
                    CrashKind.JAVA_UNCAUGHT,
                    SanitizedErrorCode.UNCAUGHT_EXCEPTION,
                    listOf(requireNotNull(SanitizedStackFrame.create("app.ledger.PAN4111111111111111", "run", 1))),
                ),
            )
            assertTrue(sender.sendCrash("ffeeddccbbaa99887766554433221100", forged) == SendDisposition.DROPPED_BY_POLICY)
            assertTrue(server.requestCount == 0)
        }
    }
}
