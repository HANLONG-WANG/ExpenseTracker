package app.ledger.core.navigation

import app.ledger.core.common.StableId
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.YearMonth
import java.util.UUID

class NavigationContractTest {
    private val stableId = StableId.fromUuid(UUID.fromString("00000000-0000-4000-8000-000000000001"))

    @Test
    fun `generated contract aligns all YAML screens routes states and components`() {
        GeneratedScreenContract.VERSION shouldBe "1.0.0"
        GeneratedScreenContract.SCREEN_COUNT shouldBe 215
        GeneratedScreenContract.REQUIRED_STATE_COUNT shouldBe 646
        GeneratedScreenContract.CANONICAL_SHA256 shouldBe
            "d6cf0096c91ec9fb7cbf626b40ce270e3cc0b5c815cc3a246d726eee50f00e5b"
        LedgerRouteContract.allScreens shouldHaveSize 215
        LedgerRouteContract.allScreens.map { it.screenId }.toSet() shouldHaveSize 215
        LedgerRouteContract.allScreens.map { it.routePattern }.toSet() shouldHaveSize 215
    }

    @Test
    fun `every registered screen creates a Navigation 3 key with only contract-safe values`() {
        LedgerRouteContract.allScreens.forEach { screen ->
            val arguments = screen.parameters.associate { parameter -> parameter.name to safeValue(screen, parameter) }
            val key = LedgerRouteContract.destination(screen.screenId, arguments)
            key.path.contains('{') shouldBe false
            key.path.contains('}') shouldBe false
            key.contract shouldBe screen
        }
    }

    @Test
    fun `route API rejects raw names arbitrary enum values and missing identifiers`() {
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> { OpaqueKeyArgument.validated("Account name") }
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            LedgerRouteContract.enumArgument(ScreenId("REC-001"), "tab", "PAYLOAD")
        }
        org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            LedgerRouteContract.destination(ScreenId("G-004"))
        }
    }

    @Test
    fun `five Navigation 3 stacks retain independent history and current-tab reselection semantics`() {
        val navigator = FiveStackNavigator()
        navigator.backStacks.size shouldBe 5
        navigator.currentKey.contract.screenId shouldBe ScreenId("REC-001")
        val editor = LedgerRouteContract.destination(
            ScreenId("REC-003"),
            mapOf(
                "mode" to LedgerRouteContract.enumArgument(ScreenId("REC-003"), "mode", "CREATE"),
            ),
        )
        navigator.navigate(editor, SessionGateState.READY) shouldBe NavigationOutcome.Navigated
        navigator.currentBackStack shouldHaveSize 2
        navigator.select(TopLevelDestination.JOURNAL) shouldBe NavigationOutcome.Navigated
        navigator.currentBackStack shouldHaveSize 1
        navigator.select(TopLevelDestination.RECORD) shouldBe NavigationOutcome.Navigated
        navigator.currentBackStack shouldHaveSize 2
        navigator.select(TopLevelDestination.RECORD) shouldBe NavigationOutcome.Popped
        navigator.select(TopLevelDestination.RECORD) shouldBe NavigationOutcome.ScrollRootToTop
    }

    @Test
    fun `system back only pops current stack and business routes pass SessionGate`() {
        val navigator = FiveStackNavigator(TopLevelDestination.ACCOUNTS)
        val detail = LedgerRouteContract.destination(
            ScreenId("ACC-005"),
            mapOf("accountId" to StableIdArgument(stableId)),
        )
        navigator.navigate(detail, SessionGateState.LOCKED) shouldBe NavigationOutcome.BlockedBySessionGate
        navigator.navigate(detail, SessionGateState.READY) shouldBe NavigationOutcome.Navigated
        navigator.pop() shouldBe NavigationOutcome.Popped
        navigator.pop() shouldBe NavigationOutcome.AtRoot
        navigator.currentTopLevel shouldBe TopLevelDestination.ACCOUNTS
    }

    private fun safeValue(screen: ScreenContract, parameter: ScreenParameterSpec): SafeRouteArgument = when (parameter.kind) {
        RouteArgumentKind.STABLE_ID -> StableIdArgument(stableId)
        RouteArgumentKind.ENUM -> LedgerRouteContract.enumArgument(screen.screenId, parameter.name, parameter.allowedValues.first())
        RouteArgumentKind.NAMED_ENUM -> NamedEnumArgument.validated("QUICK_RECORD")
        RouteArgumentKind.YEAR_MONTH -> YearMonthArgument(YearMonth.of(2026, 8))
        RouteArgumentKind.OPAQUE_KEY -> OpaqueKeyArgument.validated("allowlisted_key")
        RouteArgumentKind.ENUM_MASK -> EnumMaskArgument.fromBits(1)
        RouteArgumentKind.POSITIVE_INT -> PositiveIntArgument.fromPositive(1)
    }
}
