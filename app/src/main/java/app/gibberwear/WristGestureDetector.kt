package app.gibberwear

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Wrist gestures (GibberWear v2).
 *
 * Natural progression mapping:
 *   1. Casual wrist flip (gyroscope)     -> Green / Check-in acknowledge -> TAP
 *   2. Single shake (accelerometer)      -> Yellow / Secondary signal     -> DBL
 *   3. Sustained shake (accelerometer)   -> Red / Emergency stop          -> HLD
 *   4. Wrist Roll (gyroscope)            -> Commit Draft                 -> WRIST_ROLL
 */
class WristGestureDetector(
    context: Context,
    private val onFlickUp: () -> Unit,
    private val onFlickDown: () -> Unit,
    private val onShake: () -> Unit,
    private val onSustainedShake: () -> Unit,
    private val onWristRoll: () -> Unit = {}
) : SensorEventListener {

    companion object {
        /**
         * Angular velocity (rad/s) around forearm axis for a casual flip.
         * Tuned to 3.8f: intentional rotation to trigger green without firing on slow arm movement.
         */
        const val FLIP_THRESHOLD = 3.8f

        /**
         * Acceleration above gravity (m/s^2) for a single shake (Yellow / DBL).
         */
        const val SHAKE_THRESHOLD = 26f

        /**
         * Acceleration above gravity (m/s^2) threshold for sustained shake peaks (Red / HLD).
         */
        const val STOP_PEAK_THRESHOLD = 22f

        /**
         * Duration (ms) that repeated stop-shake peaks must occur within to trigger "stop".
         */
        const val SUSTAINED_WINDOW_MS = 600L

        /**
         * Minimum number of vigorous peaks within the window required to trigger "stop".
         */
        const val SUSTAINED_MIN_PEAKS = 3

        /** Ignore further gestures within this window after one fires. */
        const val DEBOUNCE_MS = 1500L

        // Wrist Roll Kinematic Parameters
        const val GYRO_ROTATION_THRESHOLD = 3.5f    // rad/s angular velocity (Roll axis / Y-axis)
        const val MIN_ROLL_ANGLE_RAD = 50.0f * (Math.PI.toFloat() / 180f) // Minimum angular displacement (rad)
        const val MAX_GESTURE_WINDOW_MS = 400L      // Must complete within 400ms
        const val REVERSAL_SETTLE_MS = 150L         // Settle period to prevent bounce
    }

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private var lastGestureMs = 0L

    // For sustained shake detection: track timestamps of recent strong peaks
    private val recentStopPeaks = mutableListOf<Long>()
    private var lastPeakTime = 0L

    // For wrist roll detection: integrate gyroscope
    private var rollStartTime = 0L
    private var rollIntegratedAngle = 0f
    private var rollPeakVelocity = 0f
    private var lastGyroTime = 0L
    private var isRolling = false

    fun start() {
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        recentStopPeaks.clear()
        isRolling = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> handleAccel(event)
            Sensor.TYPE_GYROSCOPE -> handleGyro(event)
        }
    }

    private fun handleAccel(event: SensorEvent) {
        val now = System.currentTimeMillis()
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        // Acceleration magnitude above gravity (1 g removed), in m/s^2.
        val mag = sqrt(x * x + y * y + z * z) - SensorManager.GRAVITY_EARTH

        if (mag > STOP_PEAK_THRESHOLD) {
            // Count discrete oscillation peaks (separated by at least 60ms)
            if (now - lastPeakTime > 60L) {
                lastPeakTime = now
                recentStopPeaks.add(now)
            }

            // Purge peaks older than the window
            recentStopPeaks.removeAll { now - it > SUSTAINED_WINDOW_MS }

            if (recentStopPeaks.size >= SUSTAINED_MIN_PEAKS) {
                recentStopPeaks.clear()
                if (now - lastGestureMs >= DEBOUNCE_MS) {
                    lastGestureMs = now
                    onSustainedShake()
                }
                return
            }
        } else {
            recentStopPeaks.removeAll { now - it > SUSTAINED_WINDOW_MS }
        }

        // Single deliberate shake -> Yellow (DBL)
        if (mag > SHAKE_THRESHOLD) {
            if (now - lastGestureMs >= DEBOUNCE_MS) {
                lastGestureMs = now
                onShake()
            }
        }
    }

    private fun handleGyro(event: SensorEvent) {
        val now = System.currentTimeMillis()
        
        // --- Legacy Wrist Flip Detection (X-axis) ---
        val wx = event.values[0]
        if (abs(wx) > FLIP_THRESHOLD) {
            if (now - lastGestureMs >= DEBOUNCE_MS) {
                lastGestureMs = now
                if (wx > 0) {
                    onFlickUp()
                } else {
                    onFlickDown()
                }
            }
        }

        // --- N-Pole Wrist Roll Detection (Y-axis) ---
        val wy = event.values[1]
        if (lastGyroTime == 0L) {
            lastGyroTime = event.timestamp
            return
        }

        val dt = (event.timestamp - lastGyroTime) * 1.0e-9f // convert nanoseconds to seconds
        lastGyroTime = event.timestamp

        // Reset if we exceed the time window
        if (isRolling && (now - rollStartTime > MAX_GESTURE_WINDOW_MS)) {
            isRolling = false
            rollIntegratedAngle = 0f
            rollPeakVelocity = 0f
        }

        if (abs(wy) > 1.8f) { // Above "normal movement" threshold
            if (!isRolling) {
                isRolling = true
                rollStartTime = now
                rollIntegratedAngle = 0f
                rollPeakVelocity = 0f
            }
            rollIntegratedAngle += wy * dt
            if (abs(wy) > rollPeakVelocity) {
                rollPeakVelocity = abs(wy)
            }
        } else if (isRolling) {
            // Motion halted or reversed
            if (rollPeakVelocity >= GYRO_ROTATION_THRESHOLD && abs(rollIntegratedAngle) >= MIN_ROLL_ANGLE_RAD) {
                if (now - lastGestureMs >= DEBOUNCE_MS) {
                    lastGestureMs = now + REVERSAL_SETTLE_MS // Apply debounce + settle time
                    onWristRoll()
                }
            }
            isRolling = false
            rollIntegratedAngle = 0f
            rollPeakVelocity = 0f
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not used.
    }
}

