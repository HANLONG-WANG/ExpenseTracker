package app.ledger.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SystemAuthenticationCoordinatorTest {
    @Test
    fun onePromptRoutesCompletionBackToTheRequestThatStartedIt() {
        val coordinator = SystemAuthenticationCoordinator()

        assertEquals(
            SystemAuthenticationStartResult.STARTED,
            coordinator.start(SystemAuthenticationChannel.VAULT),
        )
        assertTrue(coordinator.isActive(SystemAuthenticationChannel.VAULT))
        assertEquals(
            SystemAuthenticationStartResult.ALREADY_ACTIVE,
            coordinator.start(SystemAuthenticationChannel.VAULT),
        )
        assertEquals(
            SystemAuthenticationStartResult.BUSY,
            coordinator.start(SystemAuthenticationChannel.SENSITIVE_SETTINGS),
        )
        assertEquals(SystemAuthenticationChannel.VAULT, coordinator.finish())
        assertNull(coordinator.finish())
        assertFalse(coordinator.isActive(SystemAuthenticationChannel.VAULT))
    }

    @Test
    fun restoredActivityRetainsThePendingBusinessChannel() {
        val coordinator = SystemAuthenticationCoordinator(SystemAuthenticationChannel.VAULT)

        assertTrue(coordinator.isActive(SystemAuthenticationChannel.VAULT))
        assertEquals(SystemAuthenticationChannel.VAULT, coordinator.finish())
    }
}
