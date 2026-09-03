@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package app.ledger.app

import androidx.compose.runtime.Composable
import app.ledger.core.designsystem.LedgerBottomSheet
import app.ledger.core.designsystem.LedgerModalDialog

@Composable
internal fun GovernedDestinationModal(
    screenId: String,
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    when (screenId) {
        in GOVERNED_DIALOG_DESTINATIONS -> LedgerModalDialog(title, onDismiss, content = content)
        in GOVERNED_SHEET_DESTINATIONS -> LedgerBottomSheet(onDismiss = onDismiss, content = content)
        else -> content()
    }
}

internal val GOVERNED_DIALOG_DESTINATIONS = setOf("PRJ-006", "LOA-011", "SYS-002")
internal val GOVERNED_SHEET_DESTINATIONS = setOf("GOL-005", "CRD-006", "AUT-010", "ANA-009", "ANA-010", "ANA-012", "REC-006")
