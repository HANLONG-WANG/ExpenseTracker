package app.ledger.core.telemetry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PrivacyDiagnosticManagerTest {
    @TempDir lateinit var temporary: File

    @Test
    fun `nothing is queued before consent and disabling removes queue and identifier`() {
        var now = 1_000L
        var randomSeed = 1
        val sender = CapturingSender()
        val manager = PrivacyDiagnosticManager(
            temporary,
            { now },
            { bytes ->
                bytes.indices.forEach { bytes[it] = (randomSeed + it).toByte() }
                randomSeed += 17
            },
            PrivacyDiagnosticRuntime("1.2.3", 36, DeviceCapabilityCategory.DEVICE_CREDENTIAL_ONLY),
            sender,
        )
        manager.record(feature())
        manager.recordCrash(crash())
        assertTrue(manager.snapshot().featureEvents.isEmpty())
        assertTrue(manager.snapshot().crashEvents.isEmpty())

        manager.applyConsent(privacyAccepted = true, featureEnabled = true, crashEnabled = true)
        manager.record(feature())
        manager.recordCrash(crash())
        assertTrue(manager.snapshot().featureIdentifierPresent)
        assertTrue(manager.snapshot().crashIdentifierPresent)

        manager.sendPending()
        val oldFeatureIdentifier = sender.featureIdentifiers.single()
        val oldCrashIdentifier = sender.crashIdentifiers.single()
        manager.record(feature())
        manager.recordCrash(crash())
        manager.applyConsent(privacyAccepted = true, featureEnabled = false, crashEnabled = false)
        val disabled = manager.snapshot()
        assertFalse(disabled.featureIdentifierPresent)
        assertFalse(disabled.crashIdentifierPresent)
        assertTrue(disabled.featureEvents.isEmpty())
        assertTrue(disabled.crashEvents.isEmpty())

        now += 1_000L
        manager.applyConsent(privacyAccepted = true, featureEnabled = true, crashEnabled = true)
        manager.record(feature())
        manager.recordCrash(crash())
        manager.sendPending()
        assertNotEquals(oldFeatureIdentifier, sender.featureIdentifiers.last())
        assertNotEquals(oldCrashIdentifier, sender.crashIdentifiers.last())
    }

    @Test
    fun `identifiers rotate at thirty days and channel retention is enforced`() {
        var now = 10_000L
        var byteValue = 1
        val sender = CapturingSender()
        val manager = PrivacyDiagnosticManager(
            temporary,
            { now },
            { bytes -> bytes.fill(byteValue++.toByte()) },
            PrivacyDiagnosticRuntime("2.0", 35, DeviceCapabilityCategory.OTHER),
            sender,
        )
        manager.applyConsent(true, true, true)
        manager.record(feature())
        manager.recordCrash(crash())
        manager.sendPending()
        val initialFeature = sender.featureIdentifiers.last()
        val initialCrash = sender.crashIdentifiers.last()

        now += 30L * DAY + 1L
        manager.record(feature())
        manager.recordCrash(crash())
        manager.sendPending()
        assertNotEquals(initialFeature, sender.featureIdentifiers.last())
        assertNotEquals(initialCrash, sender.crashIdentifiers.last())

        manager.record(feature())
        manager.recordCrash(crash())
        now += 91L * DAY
        val afterFeatureRetention = manager.snapshot()
        assertTrue(afterFeatureRetention.featureEvents.isEmpty())
        assertEquals(1, afterFeatureRetention.crashEvents.size)
        now += 90L * DAY
        assertTrue(manager.snapshot().crashEvents.isEmpty())
    }

    @Test
    fun `crash sanitizer never represents throwable messages or invalid arbitrary frames`() {
        val sentinel = "PAN-4111111111111111-CVC-123-free-text"
        val failure = IllegalStateException(sentinel).apply {
            stackTrace = arrayOf(
                StackTraceElement("app.ledger.SafeClass", "safeMethod", "Safe.kt", 42),
                StackTraceElement("bad class $sentinel", "bad method", "Unsafe.kt", 9),
            )
        }
        val sanitized = PrivacyCrashSanitizer.fromThrowable(failure)
        assertEquals(1, sanitized.frames.size)
        assertFalse(sanitized.toString().contains(sentinel))
        assertEquals("app.ledger.SafeClass", sanitized.frames.single().className)
    }

    private fun feature() = FeatureDiagnosticEvent(
        FeatureEventName.USER_ACTION_COMPLETED,
        FeatureEntry.PRIVACY_DIAGNOSTICS,
        DiagnosticOutcome.SUCCEEDED,
        DurationBucket.UNDER_1_SECOND,
    )

    private fun crash() = SanitizedCrashDiagnostic(
        CrashKind.JAVA_UNCAUGHT,
        SanitizedErrorCode.UNCAUGHT_EXCEPTION,
        listOf(requireNotNull(SanitizedStackFrame.create("app.ledger.Safe", "run", 12))),
    )

    private class CapturingSender : WhitelistedDiagnosticSender {
        val featureIdentifiers = mutableListOf<String>()
        val crashIdentifiers = mutableListOf<String>()
        override fun sendFeature(identifier: String, entry: FeatureQueueEntry): SendDisposition {
            featureIdentifiers += identifier
            return SendDisposition.SENT
        }
        override fun sendCrash(identifier: String, entry: CrashQueueEntry): SendDisposition {
            crashIdentifiers += identifier
            return SendDisposition.SENT
        }
    }

    private companion object {
        const val DAY = 86_400_000L
    }
}
