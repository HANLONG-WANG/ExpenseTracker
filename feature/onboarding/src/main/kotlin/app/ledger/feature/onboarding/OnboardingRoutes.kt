package app.ledger.feature.onboarding

import app.ledger.core.navigation.LedgerRouteContract
import app.ledger.core.navigation.ScreenId

/** Onboarding is session-gated rather than hosted by the Ready NavDisplay, but still owns its route catalog. */
public object OnboardingRoutes {
    public val screens: Set<ScreenId> = LedgerRouteContract.allScreens.filter { it.module == ":feature:onboarding" }.mapTo(linkedSetOf()) { it.screenId }
}
