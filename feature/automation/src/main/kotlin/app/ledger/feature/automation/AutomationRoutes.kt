package app.ledger.feature.automation

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.EntryProviderScope
import app.ledger.core.navigation.AutomationDestinationKey
import app.ledger.core.navigation.LedgerDestinationKey
import app.ledger.core.navigation.LedgerRouteContract
import app.ledger.core.navigation.LedgerScreenUiAction
import app.ledger.core.navigation.LedgerScreenUiState
import app.ledger.core.navigation.ScreenId

public object AutomationRoutes {
    public val screens: Set<ScreenId> = LedgerRouteContract.allScreens.filter { it.module == ":feature:automation" }.mapTo(linkedSetOf()) { it.screenId }
}

public fun EntryProviderScope<LedgerDestinationKey>.automationDestinations(onAction: (LedgerScreenUiAction) -> Unit, content: @Composable (LedgerScreenUiState<AutomationDestinationKey>, (LedgerScreenUiAction) -> Unit) -> Unit) {
    entry<AutomationDestinationKey> { key -> content(LedgerScreenUiState(key), onAction) }
}
