package app.ledger.app

import app.ledger.core.security.BookSessionState

/** Rejects duplicate or stale system-authentication callbacks at the session boundary. */
internal object AppUnlockTransitionPolicy {
    fun mayConsumeSuccess(session: BookSessionState, authentication: AppAuthenticationState): Boolean =
        session == BookSessionState.Locked && authentication == AppAuthenticationState.AUTHENTICATING

    fun mayConsumeFailure(session: BookSessionState, authentication: AppAuthenticationState): Boolean =
        session == BookSessionState.Locked && authentication == AppAuthenticationState.AUTHENTICATING
}
