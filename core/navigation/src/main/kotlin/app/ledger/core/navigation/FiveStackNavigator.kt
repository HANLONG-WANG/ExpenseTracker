package app.ledger.core.navigation

import androidx.navigation3.runtime.NavBackStack
import app.ledger.core.common.StableId
import app.ledger.core.common.getOrNull
import java.time.YearMonth

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

public data class EncodedRouteArgument(
    val name: String,
    val value: String,
)

public data class DestinationSnapshot(
    val screenId: String,
    val arguments: List<EncodedRouteArgument>,
)

public data class TopLevelStackSnapshot(
    val topLevel: TopLevelDestination,
    val destinations: List<DestinationSnapshot>,
    val scrollKey: String?,
    val scrollOffset: Int,
) {
    init {
        require(scrollKey == null || SCROLL_KEY.matches(scrollKey))
        require(scrollOffset >= 0)
    }

    private companion object {
        val SCROLL_KEY = Regex("[A-Za-z][A-Za-z0-9._-]{0,63}")
    }
}

public data class FiveStackSnapshot(
    val selectedTopLevel: TopLevelDestination,
    val stacks: List<TopLevelStackSnapshot>,
) {
    init {
        require(stacks.map(TopLevelStackSnapshot::topLevel).toSet() == TopLevelDestination.entries.toSet())
    }
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

    public fun snapshot(
        scrollStates: Map<TopLevelDestination, Pair<String, Int>> = emptyMap(),
    ): FiveStackSnapshot = FiveStackSnapshot(
        selectedTopLevel = currentTopLevel,
        stacks = TopLevelDestination.entries.map { topLevel ->
            val scroll = scrollStates[topLevel]
            TopLevelStackSnapshot(
                topLevel = topLevel,
                destinations = backStacks.getValue(topLevel).map { key ->
                    DestinationSnapshot(
                        screenId = key.contract.screenId.value,
                        arguments = key.argumentValues.entries.sortedBy(Map.Entry<String, SafeRouteArgument>::key).map { (name, value) ->
                            EncodedRouteArgument(name, value.encoded)
                        },
                    )
                },
                scrollKey = scroll?.first,
                scrollOffset = scroll?.second ?: 0,
            )
        },
    )

    /** Restores only a fully valid, contract-aligned snapshot; malformed state is discarded atomically. */
    public fun restore(snapshot: FiveStackSnapshot): Boolean = runCatching {
        val decoded = snapshot.stacks.associate { stack ->
            val keys = stack.destinations.map(::decodeDestination)
            require(keys.isNotEmpty() && keys.first() == roots.getValue(stack.topLevel))
            require(keys.drop(1).all { RouteAccessPolicy.canOpen(it.contract, SessionGateState.READY) })
            stack.topLevel to keys
        }
        TopLevelDestination.entries.forEach { topLevel ->
            val stack = backStacks.getValue(topLevel)
            stack.clear()
            stack.addAll(decoded.getValue(topLevel))
        }
        currentTopLevel = snapshot.selectedTopLevel
    }.isSuccess

    private fun decodeDestination(snapshot: DestinationSnapshot): LedgerDestinationKey {
        val screenId = ScreenId(snapshot.screenId)
        val contract = LedgerRouteContract.screen(screenId)
        val encoded = snapshot.arguments.associate { it.name to it.value }
        require(encoded.size == snapshot.arguments.size)
        val arguments = contract.parameters.mapNotNull { parameter ->
            val value = encoded[parameter.name] ?: return@mapNotNull null
            parameter.name to decodeArgument(screenId, parameter, value)
        }.toMap()
        require(arguments.keys == encoded.keys)
        return LedgerRouteContract.destination(screenId, arguments)
    }

    private fun decodeArgument(
        screenId: ScreenId,
        parameter: ScreenParameterSpec,
        value: String,
    ): SafeRouteArgument = when (parameter.kind) {
        RouteArgumentKind.STABLE_ID -> StableIdArgument(requireNotNull(StableId.parse(value).getOrNull()))
        RouteArgumentKind.ENUM -> LedgerRouteContract.enumArgument(screenId, parameter.name, value)
        RouteArgumentKind.NAMED_ENUM -> NamedEnumArgument.validated(value)
        RouteArgumentKind.YEAR_MONTH -> {
            require(YEAR_MONTH.matches(value))
            YearMonthArgument(
                YearMonth.of(value.take(YEAR_DIGITS).toInt(), value.takeLast(MONTH_DIGITS).toInt()),
            )
        }
        RouteArgumentKind.OPAQUE_KEY -> OpaqueKeyArgument.validated(value)
        RouteArgumentKind.ENUM_MASK -> EnumMaskArgument.fromBits(value.toInt())
        RouteArgumentKind.POSITIVE_INT -> PositiveIntArgument.fromPositive(value.toInt())
    }

    private companion object {
        val YEAR_MONTH = Regex("[0-9]{6}")
        const val YEAR_DIGITS = 4
        const val MONTH_DIGITS = 2
    }
}

public object RouteAccessPolicy {
    @Suppress("UnusedParameter")
    public fun canOpen(contract: ScreenContract, state: SessionGateState): Boolean {
        // Session states are rendered by the root gate itself. No destination, including global
        // hubs, is allowed onto a Navigation 3 stack before the book is ready.
        return state == SessionGateState.READY
    }
}
