package app.ledger.feature.planning

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.EntryProviderScope
import app.ledger.core.navigation.LedgerDestinationKey
import app.ledger.core.navigation.LedgerRouteContract
import app.ledger.core.navigation.LedgerScreenUiAction
import app.ledger.core.navigation.LedgerScreenUiState
import app.ledger.core.navigation.PlanningDestinationKey
import app.ledger.core.navigation.ScreenId

public object PlanningRoutes {
    public val screens: Set<ScreenId> = LedgerRouteContract.allScreens.filter { it.module == ":feature:planning" }.mapTo(linkedSetOf()) { it.screenId }
}

public fun EntryProviderScope<LedgerDestinationKey>.planningDestinations(onAction: (LedgerScreenUiAction) -> Unit, content: @Composable (LedgerScreenUiState<PlanningDestinationKey>, (LedgerScreenUiAction) -> Unit) -> Unit) {
    entry<PlanningDestinationKey> { key -> content(LedgerScreenUiState(key), onAction) }
}
