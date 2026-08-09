package app.ledger.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.ledger.core.common.DomainResult
import app.ledger.transfer.domain.BackupFailure
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

internal sealed interface GoogleDriveAuthorization {
    data class Authorized(val accessToken: String) : GoogleDriveAuthorization
    data class ResolutionRequired(val pendingIntent: PendingIntent) : GoogleDriveAuthorization
}

/** Google Identity Authorization gateway requesting only Drive's per-app file scope. */
internal class GoogleDriveAuthorizationGateway(context: Context) {
    private val client = Identity.getAuthorizationClient(context.applicationContext)
    private val request = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
        .build()

    suspend fun authorize(): DomainResult<GoogleDriveAuthorization> = try {
        val result = client.authorize(request).await()
        result.authorization()
    } catch (_: ApiException) {
        DomainResult.Failure(BackupFailure.DriveAuthorizationRequired)
    } catch (_: Exception) {
        DomainResult.Failure(BackupFailure.NetworkUnavailable)
    }

    fun resultFromIntent(intent: Intent): DomainResult<GoogleDriveAuthorization> = try {
        client.getAuthorizationResultFromIntent(intent).authorization()
    } catch (_: ApiException) {
        DomainResult.Failure(BackupFailure.DriveAuthorizationRequired)
    }

    suspend fun disconnect(): DomainResult<Unit> = try {
        client.revokeAccess(RevokeAccessRequest.builder().setScopes(listOf(Scope(DRIVE_FILE_SCOPE))).build()).await()
        DomainResult.Success(Unit)
    } catch (_: Exception) {
        DomainResult.Failure(BackupFailure.NetworkUnavailable)
    }

    private fun AuthorizationResult.authorization(): DomainResult<GoogleDriveAuthorization> = when {
        hasResolution() -> DomainResult.Success(GoogleDriveAuthorization.ResolutionRequired(requireNotNull(pendingIntent)))
        accessToken?.isNotBlank() == true && grantedScopes.contains(DRIVE_FILE_SCOPE) ->
            DomainResult.Success(GoogleDriveAuthorization.Authorized(requireNotNull(accessToken)))
        else -> DomainResult.Failure(BackupFailure.DriveAuthorizationRequired)
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { value -> if (continuation.isActive) continuation.resume(value) }
        addOnFailureListener { error -> if (continuation.isActive) continuation.resumeWith(Result.failure(error)) }
        addOnCanceledListener { if (continuation.isActive) continuation.cancel() }
    }

    private companion object {
        const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
    }
}
