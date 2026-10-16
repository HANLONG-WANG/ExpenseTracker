package com.yohaku.expensetracker.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One foreground fix; coordinates are neither logged nor stored as a location history. */
data class LocationFix(val latitude:Double,val longitude:Double,val accuracyMeters:Double)
interface CurrentLocationSource { suspend fun currentFix():LocationFix }
enum class LocationFailure { PERMISSION,SYSTEM_OFF,NO_PROVIDER,TIMEOUT,UNAVAILABLE }
class LocationException(val reason:LocationFailure):Exception(reason.name)

class ForegroundLocation(private val context:Context):CurrentLocationSource {
    private val manager get()=context.getSystemService(LocationManager::class.java)
    override suspend fun currentFix():LocationFix = withContext(Dispatchers.Main.immediate) {
        val fine=context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
        val coarse=context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED
        if(!fine&&!coarse)throw LocationException(LocationFailure.PERMISSION)
        if(!manager.isLocationEnabled)throw LocationException(LocationFailure.SYSTEM_OFF)
        val providers=manager.getProviders(true).filter{it==LocationManager.NETWORK_PROVIDER||(fine||Build.VERSION.SDK_INT>=30)&&it==LocationManager.GPS_PROVIDER}
        if(providers.isEmpty())throw LocationException(LocationFailure.NO_PROVIDER)
        withTimeoutOrNull(20_000L) {
            suspendCancellableCoroutine { continuation ->
                val signals=mutableListOf<CancellationSignal>()
                val listeners=mutableListOf<LocationListener>()
                var remaining=providers.size
                fun clean() {signals.forEach{it.cancel()};listeners.forEach{manager.removeUpdates(it)}}
                fun accept(location:Location?) {
                    if(!continuation.isActive)return
                    val fresh=location!=null&&SystemClock.elapsedRealtimeNanos()-location.elapsedRealtimeNanos in 0..120_000_000_000L
                    if(fresh&&location.hasAccuracy()&&location.accuracy.isFinite()&&location.accuracy>0) {
                        clean();continuation.resume(LocationFix(location.latitude,location.longitude,location.accuracy.toDouble()))
                    }else if(--remaining==0){clean();continuation.resumeWithException(LocationException(LocationFailure.UNAVAILABLE))}
                }
                continuation.invokeOnCancellation{context.mainExecutor.execute{clean()}}
                try {
                    for(provider in providers) {
                        if(!continuation.isActive)break
                        if(Build.VERSION.SDK_INT>=30) {
                            val signal=CancellationSignal();signals+=signal
                            manager.getCurrentLocation(provider,signal,context.mainExecutor){accept(it)}
                        }else {
                            val listener=object:LocationListener {
                                override fun onLocationChanged(location:Location){accept(location)}
                                override fun onProviderDisabled(provider:String){accept(null)}
                                @Deprecated("Legacy provider callback") override fun onStatusChanged(provider:String?,status:Int,extras:android.os.Bundle?) {}
                            }
                            listeners+=listener
                            @Suppress("DEPRECATION")
                            manager.requestSingleUpdate(provider,listener,Looper.getMainLooper())
                        }
                    }
                }catch(e:SecurityException){clean();if(continuation.isActive)continuation.resumeWithException(LocationException(LocationFailure.PERMISSION))}
                catch(e:Exception){clean();if(continuation.isActive)continuation.resumeWithException(e)}
            }
        }?:throw LocationException(LocationFailure.TIMEOUT)
    }
}
