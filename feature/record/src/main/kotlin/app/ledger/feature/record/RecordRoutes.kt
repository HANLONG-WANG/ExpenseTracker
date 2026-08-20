package app.ledger.feature.record

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.EntryProviderScope
import app.ledger.core.navigation.LedgerDestinationKey
import app.ledger.core.navigation.LedgerRouteContract
import app.ledger.core.navigation.LedgerScreenUiAction
import app.ledger.core.navigation.LedgerScreenUiState
import app.ledger.core.navigation.RecordDestinationKey
import app.ledger.core.navigation.ScreenId

public object RecordRoutes {
    public val screens: Set<ScreenId> = LedgerRouteContract.allScreens.filter { it.module == ":feature:record" }.mapTo(linkedSetOf()) { it.screenId }
}

public fun EntryProviderScope<LedgerDestinationKey>.recordDestinations(
    onAction: (LedgerScreenUiAction) -> Unit,
    content: @Composable (LedgerScreenUiState<RecordDestinationKey>, (LedgerScreenUiAction) -> Unit) -> Unit,
) {
    entry<RecordDestinationKey> { key -> content(LedgerScreenUiState(key), onAction) }
}
