package app.ledger.core.navigation

import androidx.navigation3.runtime.NavBackStack

public enum class TopLevelDestination {
    RECORD,
    JOURNAL,
    ACCOUNTS,
    BUDGET,
    ANALYSIS,
}

public enum class SessionGateState {
    UNINITIALIZED,
    LOCKED,
    OPENING_BOOK,
    MAINTENANCE,
    RECOVERY_REQUIRED,
    READY,
}

public sealed interface NavigationOutcome {
    public data object Navigated : NavigationOutcome
    public data object Popped : NavigationOutcome
    public data object AtRoot : NavigationOutcome
    public data object ScrollRootToTop : NavigationOutcome
    public data object BlockedBySessionGate : NavigationOutcome
}

/** Navigation 3 owner for the five independent top-level histories. */
public class FiveStackNavigator(
    initialTopLevel: TopLevelDestination = TopLevelDestination.RECORD,
) {
    private val roots: Map<TopLevelDestination, LedgerDestinationKey> = mapOf(
        TopLevelDestination.RECORD to LedgerRouteContract.destination(
            screenId = ScreenId("REC-001"),
            arguments = mapOf(
                "tab" to LedgerRouteContract.enumArgument(ScreenId("REC-001"), "tab", "EXPENSE"),
            ),
        ),
        TopLevelDestination.JOURNAL to LedgerRouteContract.destination(ScreenId("JRN-001")),
        TopLevelDestination.ACCOUNTS to LedgerRouteContract.destination(ScreenId("ACC-001")),
        TopLevelDestination.BUDGET to LedgerRouteContract.destination(ScreenId("BUD-001")),
        TopLevelDestination.ANALYSIS to LedgerRouteContract.destination(ScreenId("ANA-001")),
    )

    public val backStacks: Map<TopLevelDestination, NavBackStack<LedgerDestinationKey>> =
        roots.mapValues { (_, root) -> NavBackStack(root) }

    public var currentTopLevel: TopLevelDestination = initialTopLevel
        private set

    public val currentBackStack: NavBackStack<LedgerDestinationKey>
        get() = backStacks.getValue(currentTopLevel)

    public val currentKey: LedgerDestinationKey
        get() = currentBackStack.last()

    public val isBottomNavigationVisible: Boolean
        get() = currentBackStack.size == 1 && currentKey.contract.presentation in
            setOf(LedgerPresentation.TOPLEVEL, LedgerPresentation.ROOT)

    public fun select(destination: TopLevelDestination): NavigationOutcome = when {
        destination != currentTopLevel -> {
            currentTopLevel = destination
            NavigationOutcome.Navigated
        }
        currentBackStack.size == 1 -> NavigationOutcome.ScrollRootToTop
        else -> {
            while (currentBackStack.size > 1) currentBackStack.removeAt(currentBackStack.lastIndex)
            NavigationOutcome.Popped
        }
    }

    public fun navigate(
        destination: LedgerDestinationKey,
        sessionGateState: SessionGateState,
    ): NavigationOutcome {
        if (!RouteAccessPolicy.canOpen(destination.contract, sessionGateState)) {
            return NavigationOutcome.BlockedBySessionGate
        }
        currentBackStack.add(destination)
        return NavigationOutcome.Navigated
    }

    public fun pop(): NavigationOutcome {
        if (currentBackStack.size == 1) return NavigationOutcome.AtRoot
        currentBackStack.removeAt(currentBackStack.lastIndex)
        return NavigationOutcome.Popped
    }
}

public object RouteAccessPolicy {
    public fun canOpen(contract: ScreenContract, state: SessionGateState): Boolean {
        if (state == SessionGateState.READY) return true
        return contract.screenId.value.startsWith("G-") || contract.screenId.value.startsWith("ONB-")
    }
}
