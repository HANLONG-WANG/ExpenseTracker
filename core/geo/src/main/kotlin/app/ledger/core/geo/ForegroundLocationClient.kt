@file:Suppress("DEPRECATION", "MaxLineLength", "ReturnCount")

package app.ledger.core.geo

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import app.ledger.core.common.DomainError
import app.ledger.core.common.DomainResult
import app.ledger.finance.application.CapturedLocation
import app.ledger.finance.application.CapturedLocationProvider
import app.ledger.finance.application.ForegroundLocationPort
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class LocationInfrastructureError(override val code: String) : DomainError {
    PERMISSION_DENIED("LOCATION_PERMISSION_DENIED"),
    UNAVAILABLE("LOCATION_UNAVAILABLE"),
}

fun interface PlayServicesLocationAvailability {
    fun isAvailable(): Boolean
}

class AndroidPlayServicesLocationAvailability(context: Context) : PlayServicesLocationAvailability {
    private val applicationContext = context.applicationContext

    override fun isAvailable(): Boolean = GoogleApiAvailability.getInstance()
        .isGooglePlayServicesAvailable(applicationContext) == ConnectionResult.SUCCESS
}

fun interface ForegroundLocationEngine {
    suspend fun currentLocation(maxWaitMillis: Long): Location?
}

class ProductionForegroundLocationClient(
    context: Context,
    private val clock: Clock,
    private val availability: PlayServicesLocationAvailability = AndroidPlayServicesLocationAvailability(context),
    private val fused: ForegroundLocationEngine = FusedLocationEngine(
        LocationServices.getFusedLocationProviderClient(context.applicationContext),
    ),
    private val system: ForegroundLocationEngine = LocationManagerEngine(
        context.getSystemService(LocationManager::class.java),
        ContextCompat.getMainExecutor(context.applicationContext),
    ),
) : ForegroundLocationPort {
    private val applicationContext = context.applicationContext

    @Suppress("TooGenericExceptionCaught")
    override suspend fun capture(deadline: Instant): DomainResult<CapturedLocation?> {
        if (!hasForegroundPermission()) return DomainResult.Failure(LocationInfrastructureError.PERMISSION_DENIED)
        val requestedMillis = Duration.between(clock.instant(), deadline).toMillis().coerceIn(0L, MAXIMUM_SAVE_WAIT_MILLIS)
        if (requestedMillis == 0L) return DomainResult.Success(null)
        return try {
            val outcome = withTimeoutOrNull(requestedMillis) {
                if (availability.isAvailable()) {
                    val fusedLocation = try {
                        fused.currentLocation(requestedMillis)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    fusedLocation?.let { return@withTimeoutOrNull it.toCaptured(CapturedLocationProvider.FUSED) }
                }
                system.currentLocation(requestedMillis)?.let { location ->
                    val provider = if (location.provider == LocationManager.GPS_PROVIDER) {
                        CapturedLocationProvider.GPS
                    } else {
                        CapturedLocationProvider.NETWORK
                    }
                    location.toCaptured(provider)
                }
            }
            DomainResult.Success(outcome)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecurityException) {
            DomainResult.Failure(LocationInfrastructureError.PERMISSION_DENIED)
        } catch (_: Exception) {
            DomainResult.Success(null)
        }
    }

    private fun hasForegroundPermission(): Boolean = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
        hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun hasPermission(permission: String): Boolean = ContextCompat.checkSelfPermission(applicationContext, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val MAXIMUM_SAVE_WAIT_MILLIS: Long = 3_000L
        val FOREGROUND_PERMISSIONS: Set<String> = setOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }
}

private class FusedLocationEngine(
    private val client: FusedLocationProviderClient,
) : ForegroundLocationEngine {
    @SuppressLint("MissingPermission")
    override suspend fun currentLocation(maxWaitMillis: Long): Location? = suspendCancellableCoroutine { continuation ->
        val cancellation = CancellationTokenSource()
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            .setDurationMillis(maxWaitMillis)
            .setMaxUpdateAgeMillis(MAXIMUM_CACHED_LOCATION_AGE_MILLIS)
            .build()
        client.getCurrentLocation(request, cancellation.token)
            .addOnSuccessListener { location -> if (continuation.isActive) continuation.resume(location) }
            .addOnFailureListener { error -> if (continuation.isActive) continuation.resumeWithException(error) }
            .addOnCanceledListener { if (continuation.isActive) continuation.cancel() }
        continuation.invokeOnCancellation { cancellation.cancel() }
    }

    private companion object {
        const val MAXIMUM_CACHED_LOCATION_AGE_MILLIS = 10_000L
    }
}

private class LocationManagerEngine(
    private val manager: LocationManager,
    private val executor: Executor,
) : ForegroundLocationEngine {
    @SuppressLint("MissingPermission")
    override suspend fun currentLocation(maxWaitMillis: Long): Location? {
        val provider = when {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return null
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            currentApi30(provider)
        } else {
            currentLegacy(provider)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    @SuppressLint("MissingPermission")
    private suspend fun currentApi30(provider: String): Location? = suspendCancellableCoroutine { continuation ->
        val cancellation = CancellationSignal()
        manager.getCurrentLocation(provider, cancellation, executor) { location ->
            if (continuation.isActive) continuation.resume(location)
        }
        continuation.invokeOnCancellation { cancellation.cancel() }
    }

    @SuppressLint("MissingPermission")
    private suspend fun currentLegacy(provider: String): Location? = suspendCancellableCoroutine { continuation ->
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                manager.removeUpdates(this)
                if (continuation.isActive) continuation.resume(location)
            }

            override fun onProviderDisabled(provider: String) {
                manager.removeUpdates(this)
                if (continuation.isActive) continuation.resume(null)
            }

            override fun onProviderEnabled(provider: String) = Unit
        }
        manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
        continuation.invokeOnCancellation { manager.removeUpdates(listener) }
    }
}

class ForegroundLocationSaveSession(
    private val port: ForegroundLocationPort,
    private val clock: Clock,
    private val elapsedRealtimeMillis: () -> Long,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private var startedAtElapsedRealtime: Long? = null
    private var completed = false
    private var deferred: kotlinx.coroutines.Deferred<DomainResult<CapturedLocation?>>? = null

    fun prefetch(scope: CoroutineScope) {
        check(!completed) { "location save session is complete" }
        if (deferred == null) {
            startedAtElapsedRealtime = elapsedRealtimeMillis()
            deferred = scope.async(workerDispatcher) { port.capture(clock.instant().plusMillis(MAXIMUM_WAIT_MILLIS)) }
        }
    }

    suspend fun locationForSave(): LocationSaveResult = coroutineScope {
        check(!completed) { "location save session is complete" }
        if (deferred == null) {
            startedAtElapsedRealtime = elapsedRealtimeMillis()
            deferred = async(workerDispatcher) { port.capture(clock.instant().plusMillis(MAXIMUM_WAIT_MILLIS)) }
        }
        val started = checkNotNull(startedAtElapsedRealtime)
        val remaining = (MAXIMUM_WAIT_MILLIS - (elapsedRealtimeMillis() - started)).coerceAtLeast(0L)
        val result = if (remaining == 0L) null else withTimeoutOrNull(remaining) { checkNotNull(deferred).await() }
        completed = true
        if (result == null) {
            deferred?.cancel()
            LocationSaveResult(null, LocationSaveDisposition.TIMED_OUT)
        } else {
            when (result) {
                is DomainResult.Success -> LocationSaveResult(
                    result.value,
                    if (result.value == null) LocationSaveDisposition.UNAVAILABLE else LocationSaveDisposition.LOCATED,
                )
                is DomainResult.Failure -> LocationSaveResult(
                    null,
                    if (result.error == LocationInfrastructureError.PERMISSION_DENIED) {
                        LocationSaveDisposition.PERMISSION_DENIED
                    } else {
                        LocationSaveDisposition.UNAVAILABLE
                    },
                )
            }
        }
    }

    companion object {
        const val MAXIMUM_WAIT_MILLIS: Long = ProductionForegroundLocationClient.MAXIMUM_SAVE_WAIT_MILLIS
    }
}

data class LocationSaveResult(
    val location: CapturedLocation?,
    val disposition: LocationSaveDisposition,
)

enum class LocationSaveDisposition {
    LOCATED,
    PERMISSION_DENIED,
    TIMED_OUT,
    UNAVAILABLE,
}

private fun Location.toCaptured(providerType: CapturedLocationProvider): CapturedLocation = CapturedLocation(
    latitudeE7 = BigDecimal.valueOf(latitude).movePointRight(COORDINATE_E7_SCALE).setScale(0, RoundingMode.HALF_UP).intValueExact(),
    longitudeE7 = BigDecimal.valueOf(longitude).movePointRight(COORDINATE_E7_SCALE).setScale(0, RoundingMode.HALF_UP).intValueExact(),
    accuracyMillimeters = if (hasAccuracy()) {
        BigDecimal.valueOf(accuracy.toDouble()).movePointRight(ACCURACY_MM_SCALE).setScale(0, RoundingMode.HALF_UP).intValueExact()
    } else {
        null
    },
    capturedAt = Instant.ofEpochMilli(time),
    provider = providerType,
)

private const val COORDINATE_E7_SCALE = 7
private const val ACCURACY_MM_SCALE = 3
