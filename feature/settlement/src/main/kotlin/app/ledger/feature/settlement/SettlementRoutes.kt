package app.ledger.feature.settlement

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.EntryProviderScope
import app.ledger.core.navigation.LedgerDestinationKey
import app.ledger.core.navigation.LedgerRouteContract
import app.ledger.core.navigation.LedgerScreenUiAction
import app.ledger.core.navigation.LedgerScreenUiState
import app.ledger.core.navigation.ScreenId
import app.ledger.core.navigation.SettlementDestinationKey

public object SettlementRoutes {
    public val screens: Set<ScreenId> = LedgerRouteContract.allScreens.filter { it.module == ":feature:settlement" }.mapTo(linkedSetOf()) { it.screenId }
}

public fun EntryProviderScope<LedgerDestinationKey>.settlementDestinations(onAction: (LedgerScreenUiAction) -> Unit, content: @Composable (LedgerScreenUiState<SettlementDestinationKey>, (LedgerScreenUiAction) -> Unit) -> Unit) {
    entry<SettlementDestinationKey> { key -> content(LedgerScreenUiState(key), onAction) }
}
