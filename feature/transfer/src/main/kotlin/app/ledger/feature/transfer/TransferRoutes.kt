package app.ledger.feature.transfer

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.EntryProviderScope
import app.ledger.core.navigation.LedgerDestinationKey
import app.ledger.core.navigation.LedgerRouteContract
import app.ledger.core.navigation.LedgerScreenUiAction
import app.ledger.core.navigation.LedgerScreenUiState
import app.ledger.core.navigation.ScreenId
import app.ledger.core.navigation.TransferDestinationKey

public object TransferRoutes {
    public val screens: Set<ScreenId> = LedgerRouteContract.allScreens.filter { it.module == ":feature:transfer" }.mapTo(linkedSetOf()) { it.screenId }
}

public fun EntryProviderScope<LedgerDestinationKey>.transferDestinations(onAction: (LedgerScreenUiAction) -> Unit, content: @Composable (LedgerScreenUiState<TransferDestinationKey>, (LedgerScreenUiAction) -> Unit) -> Unit) {
    entry<TransferDestinationKey> { key -> content(LedgerScreenUiState(key), onAction) }
}
