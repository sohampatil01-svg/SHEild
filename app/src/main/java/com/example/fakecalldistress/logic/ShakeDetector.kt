package com.example.fakecalldistress.logic

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

class ShakeDetector(private val onShake: () -> Unit) : SensorEventListener {

    // Threshold for shake detection (g-force). Higher = harder shake.
    // 2.7F is a good balance to avoid accidental triggers while walking/running.
    private val SHAKE_THRESHOLD_GRAVITY = 2.7F
    private val SHAKE_SLOP_TIME_MS = 500
    private var shakeTimestamp: Long = 0

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            if (processSensorData(x, y, z)) {
                onShake()
            }
        }
    }

    fun processSensorData(x: Float, y: Float, z: Float, gravity: Float = SensorManager.GRAVITY_EARTH): Boolean {
        val gX = x / gravity
        val gY = y / gravity
        val gZ = z / gravity

        // gForce will be close to 1 when there is no movement.
        val gForce = sqrt((gX * gX + gY * gY + gZ * gZ).toDouble()).toFloat()

        if (gForce > SHAKE_THRESHOLD_GRAVITY) {
            val now = System.currentTimeMillis()
            // Ignore shakes too close to each other (debounce)
            if (shakeTimestamp + SHAKE_SLOP_TIME_MS > now) {
                return false
            }

            shakeTimestamp = now
            return true
        }
        return false
    }
}
