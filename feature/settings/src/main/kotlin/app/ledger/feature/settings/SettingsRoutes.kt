package app.ledger.feature.settings

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.EntryProviderScope
import app.ledger.core.navigation.LedgerDestinationKey
import app.ledger.core.navigation.LedgerRouteContract
import app.ledger.core.navigation.LedgerScreenUiAction
import app.ledger.core.navigation.LedgerScreenUiState
import app.ledger.core.navigation.ScreenId
import app.ledger.core.navigation.SettingsDestinationKey

public object SettingsRoutes {
    public val screens: Set<ScreenId> = LedgerRouteContract.allScreens.filter { it.module == ":feature:settings" }.mapTo(linkedSetOf()) { it.screenId }
}

public fun EntryProviderScope<LedgerDestinationKey>.settingsDestinations(onAction: (LedgerScreenUiAction) -> Unit, content: @Composable (LedgerScreenUiState<SettingsDestinationKey>, (LedgerScreenUiAction) -> Unit) -> Unit) {
    entry<SettingsDestinationKey> { key -> content(LedgerScreenUiState(key), onAction) }
}
