package app.ledger.core.navigation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FiveStackNavigatorP11Test {
    @Test
    fun fiveHistoriesRemainIndependentAndReselectionPopsOnlyCurrentStack() {
        val navigator = FiveStackNavigator()
        TopLevelDestination.entries.forEach { topLevel ->
            navigator.select(topLevel)
            assertEquals(
                NavigationOutcome.Navigated,
                navigator.navigate(LedgerRouteContract.destination(ScreenId("G-006")), SessionGateState.READY),
            )
        }
        assertTrue(navigator.backStacks.values.all { it.size == 2 })

        navigator.select(TopLevelDestination.ACCOUNTS)
        assertEquals(NavigationOutcome.Popped, navigator.select(TopLevelDestination.ACCOUNTS))
        assertEquals(1, navigator.backStacks.getValue(TopLevelDestination.ACCOUNTS).size)
        assertTrue(
            navigator.backStacks.filterKeys { it != TopLevelDestination.ACCOUNTS }.values.all { it.size == 2 },
        )
        assertEquals(NavigationOutcome.ScrollRootToTop, navigator.select(TopLevelDestination.ACCOUNTS))
    }

    @Test
    fun validNonSensitiveSnapshotRoundTripsAllStacksAndScrollPositions() {
        val source = FiveStackNavigator()
        source.navigate(LedgerRouteContract.destination(ScreenId("G-006")), SessionGateState.READY)
        source.select(TopLevelDestination.JOURNAL)
        source.navigate(LedgerRouteContract.destination(ScreenId("G-007")), SessionGateState.READY)
        val snapshot = source.snapshot(
            mapOf(
                TopLevelDestination.RECORD to ("record-root" to 23),
                TopLevelDestination.JOURNAL to ("journal-root" to 91),
            ),
        )

        val restored = FiveStackNavigator()
        assertTrue(restored.restore(snapshot))
        assertEquals(TopLevelDestination.JOURNAL, restored.currentTopLevel)
        assertEquals("G-007", restored.currentKey.contract.screenId.value)
        assertEquals(snapshot, restored.snapshot(mapOf(TopLevelDestination.RECORD to ("record-root" to 23), TopLevelDestination.JOURNAL to ("journal-root" to 91))))
    }

    @Test
    fun malformedSnapshotIsRejectedWithoutPartiallyChangingLiveStacks() {
        val navigator = FiveStackNavigator()
        val before = navigator.snapshot()
        val malformed = before.copy(
            stacks = before.stacks.map { stack ->
                if (stack.topLevel == TopLevelDestination.RECORD) {
                    stack.copy(destinations = listOf(DestinationSnapshot("G-006", emptyList())))
                } else {
                    stack
                }
            },
        )
        assertFalse(navigator.restore(malformed))
        assertEquals(before, navigator.snapshot())
    }

    @Test
    fun everyDestinationIncludingGlobalHubsIsBlockedBeforeReady() {
        listOf("JRN-001", "G-006", "G-007").forEach { screenId ->
            val destination = LedgerRouteContract.destination(ScreenId(screenId))
            SessionGateState.entries.filter { it != SessionGateState.READY }.forEach { state ->
                assertFalse(RouteAccessPolicy.canOpen(destination.contract, state), "$screenId/${state.name}")
            }
            assertTrue(RouteAccessPolicy.canOpen(destination.contract, SessionGateState.READY))
        }
    }
}
