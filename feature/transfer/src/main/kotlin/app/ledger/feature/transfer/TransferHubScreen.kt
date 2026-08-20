@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package app.ledger.feature.transfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.ledger.core.designsystem.LedgerBanner
import app.ledger.core.designsystem.LedgerBannerVariant
import app.ledger.core.designsystem.LedgerCard
import app.ledger.core.designsystem.LedgerText
import app.ledger.core.designsystem.LedgerTextRole
import app.ledger.core.designsystem.LedgerTheme

data class TransferHubState(
    val operationActive: Boolean,
    val notificationPermissionAvailable: Boolean = true,
)

sealed interface TransferHubScreenAction {
    data object OpenImport : TransferHubScreenAction
    data object OpenExport : TransferHubScreenAction
    data object OpenBackup : TransferHubScreenAction
    data object OpenRestore : TransferHubScreenAction
    data object OpenOperations : TransferHubScreenAction
}

private class TransferHubActions(
    val openImport: () -> Unit,
    val openExport: () -> Unit,
    val openBackup: () -> Unit,
    val openRestore: () -> Unit,
    val openOperations: () -> Unit,
)

@Composable
fun TransferHubScreen(
    state: TransferHubState,
    onAction: (TransferHubScreenAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val actions = TransferHubActions(
        openImport = { onAction(TransferHubScreenAction.OpenImport) },
        openExport = { onAction(TransferHubScreenAction.OpenExport) },
        openBackup = { onAction(TransferHubScreenAction.OpenBackup) },
        openRestore = { onAction(TransferHubScreenAction.OpenRestore) },
        openOperations = { onAction(TransferHubScreenAction.OpenOperations) },
    )
    val entries = listOf(
        TransferEntry(R.string.transfer_hub_import, R.string.transfer_hub_import_body, actions.openImport),
        TransferEntry(R.string.transfer_hub_export, R.string.transfer_hub_export_body, actions.openExport),
        TransferEntry(R.string.transfer_hub_backup, R.string.transfer_hub_backup_body, actions.openBackup),
        TransferEntry(R.string.transfer_hub_restore, R.string.transfer_hub_restore_body, actions.openRestore),
        TransferEntry(R.string.transfer_hub_operations, R.string.transfer_hub_operations_body, actions.openOperations),
    )
    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = LedgerTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(LedgerTheme.spacing.sm),
    ) {
        if (state.operationActive) {
            item { LedgerBanner(stringResource(R.string.transfer_hub_active), LedgerBannerVariant.WARNING) }
        }
        if (!state.notificationPermissionAvailable) {
            item { LedgerBanner(stringResource(R.string.transfer_hub_notifications_unavailable), LedgerBannerVariant.INFO) }
        }
        items(entries) { entry ->
            LedgerCard(Modifier.fillMaxWidth(), onClick = entry.action) {
                Column(Modifier.fillMaxWidth().padding(LedgerTheme.spacing.sm)) {
                    LedgerText(stringResource(entry.title), LedgerTextRole.SECTION)
                    LedgerText(stringResource(entry.body), LedgerTextRole.SUPPORTING)
                }
            }
        }
    }
}

private data class TransferEntry(val title: Int, val body: Int, val action: () -> Unit)
