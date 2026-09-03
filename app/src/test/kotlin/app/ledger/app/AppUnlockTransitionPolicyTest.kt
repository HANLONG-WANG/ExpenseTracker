package app.ledger.app

import app.ledger.core.common.StableId
import app.ledger.core.security.BookSessionState
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class AppUnlockTransitionPolicyTest {
    @Test
    fun `automatic open is admitted only from Locked when app lock is disabled`() {
        assertTrue(AppUnlockTransitionPolicy.mayOpenWithoutAuthentication(BookSessionState.Locked, appLockEnabled = false))
        assertFalse(AppUnlockTransitionPolicy.mayOpenWithoutAuthentication(BookSessionState.Locked, appLockEnabled = true))
        assertFalse(AppUnlockTransitionPolicy.mayOpenWithoutAuthentication(BookSessionState.Opening, appLockEnabled = false))
        assertFalse(
            AppUnlockTransitionPolicy.mayOpenWithoutAuthentication(
                BookSessionState.Ready(StableId.fromUuid(UUID(0L, 1L)), 1),
                appLockEnabled = false,
            ),
        )
    }

    @Test
    fun onlyTheActiveLockedAuthenticationAttemptMayComplete() {
        assertTrue(
            AppUnlockTransitionPolicy.mayConsumeSuccess(
                BookSessionState.Locked,
                AppAuthenticationState.AUTHENTICATING,
            ),
        )
        assertFalse(AppUnlockTransitionPolicy.mayConsumeSuccess(BookSessionState.Locked, AppAuthenticationState.AUTH_FAILED))
        assertFalse(AppUnlockTransitionPolicy.mayConsumeSuccess(BookSessionState.Opening, AppAuthenticationState.AUTHENTICATING))
        assertFalse(AppUnlockTransitionPolicy.mayConsumeFailure(BookSessionState.Opening, AppAuthenticationState.AUTHENTICATING))
    }
}
