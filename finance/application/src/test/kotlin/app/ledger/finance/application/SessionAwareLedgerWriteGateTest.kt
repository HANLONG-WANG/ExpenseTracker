package app.ledger.finance.application

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SessionAwareLedgerWriteGateTest {
    @Test
    fun rejectsBeforeDelegateWhenSessionIsNotReady() {
        var delegateCalls = 0
        val gate = SessionAwareLedgerWriteGate(
            delegate = object : LedgerWriteGate {
                override suspend fun <T> execute(block: suspend () -> T): T {
                    delegateCalls += 1
                    return block()
                }
            },
            availability = FinancialWriteAvailability { false },
        )
        assertThrows<IllegalStateException> { runTest { gate.execute { delegateCalls += 100 } } }
        assertEquals(0, delegateCalls)
    }

    @Test
    fun rechecksInsideSerializedGateAndRejectsSessionTransition() {
        var ready = true
        var blockCalls = 0
        val gate = SessionAwareLedgerWriteGate(
            delegate = object : LedgerWriteGate {
                override suspend fun <T> execute(block: suspend () -> T): T {
                    ready = false
                    return block()
                }
            },
            availability = FinancialWriteAvailability { ready },
        )
        assertThrows<IllegalStateException> { runTest { gate.execute { blockCalls += 1 } } }
        assertEquals(0, blockCalls)
    }
}
