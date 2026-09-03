package app.ledger.app

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LedgerApplicationProcessPolicyTest {
    @Test
    fun onlyThePackageMainProcessInitializesProcessScopedApplicationRuntime() {
        val packageName = "app.ledger.expensetracker"

        assertTrue(LedgerApplicationProcessPolicy.shouldInitialize(packageName, packageName))
        assertFalse(LedgerApplicationProcessPolicy.shouldInitialize("$packageName:acra", packageName))
        assertFalse(LedgerApplicationProcessPolicy.shouldInitialize("$packageName:worker", packageName))
        assertFalse(LedgerApplicationProcessPolicy.shouldInitialize(null, packageName))
    }

    @Test
    fun optionalHeadlessStartupWorkIsAdmittedOnlyAfterTheFirstInteractiveContent() {
        val gate = FirstInteractiveContentGate()

        assertFalse(gate.hasEntered())
        assertTrue(gate.enter())
        assertTrue(gate.hasEntered())
        assertFalse(gate.enter())
        assertFalse(gate.isCompletedForTest())

        gate.complete()
        runBlocking { gate.awaitCompletion() }
        assertTrue(gate.isCompletedForTest())
    }
}
