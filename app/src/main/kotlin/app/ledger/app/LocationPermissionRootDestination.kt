package app.ledger.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.ledger.core.geo.LocationPermissionDialog
import app.ledger.core.geo.LocationPermissionDialogState

@Composable
internal fun LocationPermissionRootDestination(
    viewModel: AppRootViewModel,
    onNavigationChanged: () -> Unit,
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var presentation by remember { mutableStateOf(LocationPermissionDialogState.FIRST_ASK) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            viewModel.completeLocationPermission()
            onNavigationChanged()
        } else {
            val rationale = activity?.let {
                ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.ACCESS_FINE_LOCATION) ||
                    ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.ACCESS_COARSE_LOCATION)
            } == true
            presentation = if (rationale) LocationPermissionDialogState.DENIED else LocationPermissionDialogState.PERMANENTLY_DENIED
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (context.hasLedgerLocationPermission()) {
            viewModel.completeLocationPermission()
            onNavigationChanged()
        }
    }
    LocationPermissionDialog(
        presentation,
        onRequestPermission = {
            launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        },
        onOpenSettings = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
            )
        },
        onDismiss = {
            viewModel.dismissLocationPermission()
            onNavigationChanged()
        },
    )
}

internal fun android.content.Context.hasLedgerLocationPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
