package app.ledger.app

import app.ledger.core.common.StableId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.util.UUID

class RecurrenceCatchUpWorkerContractTest {
    @Test
    fun `worker data contains only opaque operation id and names are deterministic`() {
        val operationId = StableId.fromUuid(UUID(0x23, 0x2301))
        val data = RecurrenceWorkScheduler.operationData(operationId)

        assertEquals(setOf(RecurrenceCatchUpWorker.INPUT_OPERATION_ID), data.keyValueMap.keys)
        assertEquals(operationId.toString(), data.getString(RecurrenceCatchUpWorker.INPUT_OPERATION_ID))
        assertEquals(RecurrenceWorkScheduler.uniqueName(operationId), RecurrenceWorkScheduler.uniqueName(operationId))
        assertEquals(RecurrenceWorkScheduler.periodicName(operationId), RecurrenceWorkScheduler.periodicName(operationId))
        assertFalse(RecurrenceWorkScheduler.uniqueName(operationId) == RecurrenceWorkScheduler.periodicName(operationId))
    }
}
