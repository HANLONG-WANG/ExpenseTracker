package app.ledger.app

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocationPermissionCompletionPolicyTest {
    @Test
    fun onlyThePermissionDestinationMayConsumeTheCompletion() {
        assertTrue(LocationPermissionCompletionPolicy.shouldHandle("SYS-001"))
        assertFalse(LocationPermissionCompletionPolicy.shouldHandle("REC-009"))
        assertFalse(LocationPermissionCompletionPolicy.shouldHandle("REC-003"))
    }
}
