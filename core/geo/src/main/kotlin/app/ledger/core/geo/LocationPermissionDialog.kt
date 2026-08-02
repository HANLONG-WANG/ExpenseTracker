@file:Suppress("ktlint:standard:function-naming", "FunctionNaming", "MatchingDeclarationName")

package app.ledger.core.geo

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.ledger.core.designsystem.LedgerDialog
import app.ledger.core.designsystem.LedgerTestTags

enum class LocationPermissionDialogState { FIRST_ASK, DENIED, PERMANENTLY_DENIED }

@Composable
fun LocationPermissionDialog(
    state: LocationPermissionDialogState,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val permanentlyDenied = state == LocationPermissionDialogState.PERMANENTLY_DENIED
    val message = when (state) {
        LocationPermissionDialogState.FIRST_ASK -> stringResource(R.string.location_permission_first_ask)
        LocationPermissionDialogState.DENIED -> stringResource(R.string.location_permission_denied)
        LocationPermissionDialogState.PERMANENTLY_DENIED -> stringResource(R.string.location_permission_permanently_denied)
    }
    LedgerDialog(
        title = stringResource(R.string.location_permission_title),
        message = message,
        confirmLabel = stringResource(
            if (permanentlyDenied) R.string.location_permission_open_settings else R.string.location_permission_continue,
        ),
        onConfirm = if (permanentlyDenied) onOpenSettings else onRequestPermission,
        onDismiss = onDismiss,
        modifier = modifier.testTag(LedgerTestTags.LOCATION_PERMISSION),
    )
}
