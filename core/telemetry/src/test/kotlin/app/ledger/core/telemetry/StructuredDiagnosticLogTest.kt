package app.ledger.core.telemetry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class StructuredDiagnosticLogTest {
    @Test
    fun `release log removes even fixed debug metadata and remains bounded`() {
        val log = StructuredDiagnosticLog(releaseMode = true, nowEpochMillis = { 8L }, capacity = 2)
        repeat(3) {
            log.record(
                DiagnosticSeverity.INFO,
                DiagnosticPhase.VAULT_READ,
                DiagnosticCode.FLOW_COMPLETED,
                DebugDiagnosticMetadata(it, false, DeviceCapabilityCategory.OTHER),
            )
        }
        val entries = log.snapshot()
        assertEquals(2, entries.size)
        entries.forEach { assertNull(it.debugMetadata) }
    }
}
