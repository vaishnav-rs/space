package com.perfectframe.camera.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * Sensor-fusion level detector (spec §2 Phase 3).
 *
 * Uses [SensorManager.getRotationMatrix] over accelerometer + magnetometer — more stable than
 * raw accelerometer alone — then [SensorManager.getOrientation] for pitch/roll. Both raw signals
 * are low-pass filtered to keep the indicator from jittering. Emits a [StateFlow] the overlay
 * observes; a ~[toleranceDegrees]° band decides the binary "level" state.
 *
 * Call [start] from ON_RESUME and [stop] from ON_PAUSE.
 */
class LevelDetector(
    context: Context,
    private val toleranceDegrees: Float = DEFAULT_TOLERANCE_DEG,
) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnetometer: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    private val accel = FloatArray(3)
    private val magnet = FloatArray(3)
    private var hasAccel = false
    private var hasMagnet = false

    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    private val _state = MutableStateFlow(
        LevelState(hasSensors = accelerometer != null && magnetometer != null)
    )
    val state: StateFlow<LevelState> = _state.asStateFlow()

    private var tolerance = toleranceDegrees

    fun setTolerance(degrees: Float) { tolerance = degrees }

    fun start() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        magnetometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                lowPass(event.values, accel)
                hasAccel = true
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                lowPass(event.values, magnet)
                hasMagnet = true
            }
            else -> return
        }
        if (!hasAccel || !hasMagnet) return

        if (SensorManager.getRotationMatrix(rotationMatrix, null, accel, magnet)) {
            SensorManager.getOrientation(rotationMatrix, orientation)
            // orientation = [azimuth, pitch, roll] in radians.
            val pitch = Math.toDegrees(orientation[1].toDouble()).toFloat()
            val roll = Math.toDegrees(orientation[2].toDouble()).toFloat()
            _state.value = LevelState(
                rollDegrees = roll,
                pitchDegrees = pitch,
                isLevel = abs(roll) <= tolerance,
                hasSensors = true,
            )
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* no-op */ }

    /** In-place exponential low-pass filter to smooth sensor jitter. */
    private fun lowPass(input: FloatArray, output: FloatArray) {
        for (i in input.indices) {
            output[i] = output[i] + ALPHA * (input[i] - output[i])
        }
    }

    companion object {
        private const val DEFAULT_TOLERANCE_DEG = 3.0f
        private const val ALPHA = 0.2f
    }
}
