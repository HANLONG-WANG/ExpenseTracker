package com.yohaku.expensetracker.ui.features.scan

import android.content.Context
import android.hardware.*
import android.os.SystemClock
import kotlin.math.sqrt

internal interface ScanMotionSource : AutoCloseable {
    fun start()
    fun isStill(nowMs: Long): Boolean
}

/** Available sensors both must be still. No sensors => image stability alone. Main thread. */
internal class ScanMotionMonitor(context: Context, private val onMotion: () -> Unit) : SensorEventListener, ScanMotionSource {
    private val manager = context.getSystemService(SensorManager::class.java)
    private var gravity: DoubleArray? = null
    private var lastMotion = Long.MIN_VALUE
    private var running = false
    private var hasSensors = false
    override fun start() {
        if (running) return
        running = true; gravity = null; hasSensors = false
        for (type in listOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE)) {
            manager.getDefaultSensor(type)?.let { hasSensors = manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) || hasSensors }
        }
        lastMotion = if (hasSensors) SystemClock.elapsedRealtime() else Long.MIN_VALUE
    }
    override fun isStill(nowMs: Long) = !hasSensors || nowMs - lastMotion >= ScanThresholds.MOTION_SETTLE_MS
    override fun onSensorChanged(event: SensorEvent) {
        val values = event.values
        val moving = if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
            sqrt(values.take(3).sumOf { it.toDouble() * it }) > ScanThresholds.GYRO_RAD_S
        } else {
            val g = gravity ?: DoubleArray(3) { values[it].toDouble() }.also { gravity = it }
            val acceleration = sqrt((0..2).sumOf { i -> (values[i] - g[i]).let { it * it } })
            for (i in 0..2) g[i] = .8 * g[i] + .2 * values[i]
            acceleration > ScanThresholds.ACCEL_M_S2
        }
        if (moving) { lastMotion = SystemClock.elapsedRealtime(); onMotion() }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    override fun close() { manager.unregisterListener(this); running = false; hasSensors = false; gravity = null }
}
