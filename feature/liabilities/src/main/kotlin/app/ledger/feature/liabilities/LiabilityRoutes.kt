package app.ledger.feature.liabilities

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.EntryProviderScope
import app.ledger.core.navigation.LedgerDestinationKey
import app.ledger.core.navigation.LedgerRouteContract
import app.ledger.core.navigation.LedgerScreenUiAction
import app.ledger.core.navigation.LedgerScreenUiState
import app.ledger.core.navigation.LiabilitiesDestinationKey
import app.ledger.core.navigation.ScreenId

public object LiabilityRoutes {
    public val screens: Set<ScreenId> = LedgerRouteContract.allScreens.filter { it.module == ":feature:liabilities" }.mapTo(linkedSetOf()) { it.screenId }
}

public fun EntryProviderScope<LedgerDestinationKey>.liabilityDestinations(onAction: (LedgerScreenUiAction) -> Unit, content: @Composable (LedgerScreenUiState<LiabilitiesDestinationKey>, (LedgerScreenUiAction) -> Unit) -> Unit) {
    entry<LiabilitiesDestinationKey> { key -> content(LedgerScreenUiState(key), onAction) }
}
