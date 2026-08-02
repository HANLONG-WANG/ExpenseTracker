package app.ledger.core.security

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AppLockControllerTest {
    @Test
    fun `lock enablement requires authentication and timeout uses monotonic elapsed time`() {
        var now = 1_000L
        var lockCallbacks = 0
        val controller = AppLockController(elapsedRealtimeMillis = { now }, onLocked = { lockCallbacks += 1 })

        controller.currentState() shouldBe AppLockState.Disabled
        shouldThrow<IllegalArgumentException> {
            controller.updateSettings(AppLockSettings(true, AppLockTimeout.OneMinute), authenticated = false)
        }
        controller.updateSettings(AppLockSettings(true, AppLockTimeout.OneMinute), authenticated = true)
        controller.currentState() shouldBe AppLockState.Locked
        controller.authenticationSucceeded()
        controller.onApplicationBackgrounded()
        now += 59_999L
        controller.onApplicationForegrounded() shouldBe AppLockState.Unlocked

        controller.onApplicationBackgrounded()
        now += 60_000L
        controller.onApplicationForegrounded() shouldBe AppLockState.Locked
        lockCallbacks shouldBe 1
    }

    @Test
    fun `immediate lock and force lock clear access exactly once`() {
        var callbacks = 0
        val controller = AppLockController(
            initialSettings = AppLockSettings(true, AppLockTimeout.Immediately),
            elapsedRealtimeMillis = { 0L },
            onLocked = { callbacks += 1 },
        )
        controller.authenticationSucceeded()
        controller.onApplicationBackgrounded()
        controller.currentState() shouldBe AppLockState.Locked
        controller.forceLock()
        callbacks shouldBe 1
    }

    @Test
    fun `custom timeout is closed and bounded`() {
        AppLockTimeout.Custom.of(30_000L).timeoutMillis shouldBe 30_000L
        AppLockTimeout.Custom.of(86_400_000L).timeoutMillis shouldBe 86_400_000L
        shouldThrow<IllegalArgumentException> { AppLockTimeout.Custom.of(29_999L) }
        shouldThrow<IllegalArgumentException> { AppLockTimeout.Custom.of(86_400_001L) }
    }
}
