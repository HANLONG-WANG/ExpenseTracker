package app.ledger.core.geo

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.location.Location
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.ledger.core.common.DomainResult
import app.ledger.finance.application.CapturedLocationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class ForegroundLocationInfrastructureDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun deniedPermissionReturnsTypedFailureWithoutTouchingProviders() = runBlocking {
        var providerCalls = 0
        val client = ProductionForegroundLocationClient(
            PermissionContext(context, emptySet()),
            CLOCK,
            availability = PlayServicesLocationAvailability { true },
            fused = ForegroundLocationEngine {
                providerCalls++
                null
            },
            system = ForegroundLocationEngine {
                providerCalls++
                null
            },
        )

        val result = client.capture(CLOCK.instant().plusSeconds(3))

        assertEquals(LocationInfrastructureError.PERMISSION_DENIED, (result as DomainResult.Failure).error)
        assertEquals(0, providerCalls)
    }

    @Test
    fun missingPlayServicesUsesLocationManagerBoundaryAndFreezesGpsEvidence() = runBlocking {
        var fusedCalls = 0
        var systemCalls = 0
        val fix = Location(android.location.LocationManager.GPS_PROVIDER).apply {
            latitude = 35.681236
            longitude = 139.767125
            accuracy = 5.25f
            time = CLOCK.instant().toEpochMilli()
        }
        val client = ProductionForegroundLocationClient(
            PermissionContext(context, setOf(Manifest.permission.ACCESS_FINE_LOCATION)),
            CLOCK,
            availability = PlayServicesLocationAvailability { false },
            fused = ForegroundLocationEngine {
                fusedCalls++
                null
            },
            system = ForegroundLocationEngine {
                systemCalls++
                fix
            },
        )

        val captured = (client.capture(CLOCK.instant().plusSeconds(3)) as DomainResult.Success).value

        assertEquals(0, fusedCalls)
        assertEquals(1, systemCalls)
        assertEquals(CapturedLocationProvider.GPS, captured?.provider)
        assertEquals(356_812_360, captured?.latitudeE7)
        assertEquals(1397_671_250, captured?.longitudeE7)
        assertEquals(5_250, captured?.accuracyMillimeters)
    }

    @Test
    fun expiredDeadlineReturnsNoLocationAndManifestRequestsNoBackgroundAccess() = runBlocking {
        var providerCalls = 0
        val client = ProductionForegroundLocationClient(
            PermissionContext(context, setOf(Manifest.permission.ACCESS_COARSE_LOCATION)),
            CLOCK,
            availability = PlayServicesLocationAvailability { true },
            fused = ForegroundLocationEngine {
                providerCalls++
                null
            },
            system = ForegroundLocationEngine {
                providerCalls++
                null
            },
        )

        val captured = (client.capture(CLOCK.instant()) as DomainResult.Success).value

        assertNull(captured)
        assertEquals(0, providerCalls)
        val permissions = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
            .requestedPermissions
            .orEmpty()
            .toSet()
        assertTrue(Manifest.permission.ACCESS_FINE_LOCATION in permissions)
        assertTrue(Manifest.permission.ACCESS_COARSE_LOCATION in permissions)
        assertFalse(Manifest.permission.ACCESS_BACKGROUND_LOCATION in permissions)
    }

    private class PermissionContext(
        base: Context,
        private val granted: Set<String>,
    ) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this

        override fun checkPermission(permission: String, pid: Int, uid: Int): Int = if (permission in granted) {
            PackageManager.PERMISSION_GRANTED
        } else {
            PackageManager.PERMISSION_DENIED
        }

        override fun checkCallingOrSelfPermission(permission: String): Int = if (permission in granted) {
            PackageManager.PERMISSION_GRANTED
        } else {
            PackageManager.PERMISSION_DENIED
        }

        override fun checkSelfPermission(permission: String): Int = if (permission in granted) {
            PackageManager.PERMISSION_GRANTED
        } else {
            PackageManager.PERMISSION_DENIED
        }
    }

    private companion object {
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-08-02T00:00:00Z"), ZoneOffset.UTC)
    }
}
