package app.ledger.app

import app.ledger.core.security.BookSessionState
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppUnlockTransitionPolicyTest {
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
