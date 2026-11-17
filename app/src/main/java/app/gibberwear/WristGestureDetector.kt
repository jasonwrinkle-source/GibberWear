package app.gibberwear

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

class WristGestureDetector(
    context: Context,
    private val onFlickUp: () -> Unit,
    private val onFlickDown: () -> Unit,
    private val onElbowRaise: () -> Unit = {},
    private val onShake: () -> Unit,
    private val onSustainedShake: () -> Unit,
    private val onWristRoll: () -> Unit = {}
) : SensorEventListener {

    companion object {
        private const val TAG = "WristGesture"

        const val FLIP_THRESHOLD = 3.8f
        /**
         * A flick has to be a snap. Ordinary wrist motion clears the 3.8 start
         * and must not send go. Peak is rad/s.
         */
        const val FLICK_PEAK_MIN = 6.5f
        const val FLICK_MIN_MS = 50L
        const val FLICK_MIN_ANGLE_RAD = 30.0f * (Math.PI.toFloat() / 180f)
        /** One deliberate jolt, after the window fails to become a hard shake. Yellow. */
        const val SHAKE_THRESHOLD = 11f
        /** Upward crossing that counts as a shake jolt. Three inside the window is red. */
        const val STOP_PEAK_THRESHOLD = 8f
        /** Must fall back under this before the next jolt counts, so one crest is one peak. */
        const val SHAKE_FLOOR = 4f
        /** One spike this far above gravity is itself a hard shake. About 1.6 g. */
        const val VIGOROUS_THRESHOLD = 16f
        const val SUSTAINED_WINDOW_MS = 700L
        const val SUSTAINED_MIN_PEAKS = 3
        const val PEAK_GAP_MS = 60L
        const val DEBOUNCE_MS = 1500L

        /** A wrist flip is a short snap. A 90° elbow raise stays on X longer than this. */
        const val FLICK_MAX_MS = 220L

        /**
         * The winning axis must be this many times the others.
         * Keeps a forearm twist from being read as an elbow raise, and the reverse.
         */
        const val AXIS_DOMINANCE = 1.5f

        // Forearm twist (device Y) also commits a rating when it dominates.
        const val GYRO_ROTATION_THRESHOLD = 3.5f
        const val MIN_ROLL_ANGLE_RAD = 50.0f * (Math.PI.toFloat() / 180f)
        const val MAX_GESTURE_WINDOW_MS = 400L
        const val REVERSAL_SETTLE_MS = 150L
        const val ROLL_START_RAD_S = 1.8f
    }

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private var lastGestureMs = 0L

    private var shakePeaks = 0
    private var shakeWindowStart = 0L
    private var shakeActiveUntil = 0L
    private var lastPeakTime = 0L
    private var shakeMax = 0f
    /** True once acceleration has fallen back under the floor, so the next rise is a new jolt. */
    private var shakeArmed = true

    // Forearm twist, integrated on device Y.
    private var rollStartTime = 0L
    private var rollIntegratedAngle = 0f
    private var rollPeakVelocity = 0f
    private var rollCrossPeak = 0f
    private var lastGyroTime = 0L
    private var isRolling = false

    // Short snap on device X is a flick. A longer burst is the elbow raise and rates.
    private var flicking = false
    private var flickStartTime = 0L
    private var flickPeak = 0f
    private var flickSign = 0f
    private var flickAngle = 0f
    private var raiseLatched = false

    private val initTime = System.currentTimeMillis()

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
        shakePeaks = 0
        shakeMax = 0f
        shakeArmed = true
        shakeActiveUntil = 0L
        resetRoll()
        flicking = false
        flickAngle = 0f
        raiseLatched = false
    }

    private fun gyroDt(event: SensorEvent): Float {
        if (lastGyroTime == 0L) {
            lastGyroTime = event.timestamp
            return 0f
        }
        val dt = (event.timestamp - lastGyroTime) * 1.0e-9f
        lastGyroTime = event.timestamp
        return if (dt <= 0f || dt > 0.1f) 0f else dt
    }

    private fun resetRoll() {
        isRolling = false
        rollIntegratedAngle = 0f
        rollPeakVelocity = 0f
        rollCrossPeak = 0f
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
        val mag = sqrt(x * x + y * y + z * z) - SensorManager.GRAVITY_EARTH

        if (mag > STOP_PEAK_THRESHOLD) {
            if (shakePeaks > 0 && mag > shakeMax) shakeMax = mag
            // A new jolt is an upward crossing, not another sample of the same crest.
            if (shakeArmed && now - lastPeakTime > PEAK_GAP_MS) {
                shakeArmed = false
                lastPeakTime = now
                val inWindow = shakePeaks > 0 && now - shakeWindowStart <= SUSTAINED_WINDOW_MS
                if (!inWindow) {
                    if (now - lastGestureMs < DEBOUNCE_MS) return
                    shakePeaks = 1
                    shakeWindowStart = now
                    shakeMax = mag
                } else {
                    shakePeaks++
                    if (mag > shakeMax) shakeMax = mag
                }
            }
            // Two reversals means this is a shake. Do not also rate it as an elbow raise.
            if (shakePeaks >= 2) {
                shakeActiveUntil = now + SUSTAINED_WINDOW_MS
            }
            val vigorous = shakeMax >= VIGOROUS_THRESHOLD
            if (shakePeaks > 0 && (shakePeaks >= SUSTAINED_MIN_PEAKS || vigorous)) {
                fireHardShake(now)
            }
            return
        }

        if (mag < SHAKE_FLOOR) shakeArmed = true

        if (shakePeaks in 1 until SUSTAINED_MIN_PEAKS &&
            now - shakeWindowStart > SUSTAINED_WINDOW_MS
        ) {
            val max = shakeMax
            shakePeaks = 0
            shakeMax = 0f
            shakeActiveUntil = 0L
            if (max >= SHAKE_THRESHOLD && now - lastGestureMs >= DEBOUNCE_MS) {
                lastGestureMs = now
                Log.i(TAG, "Shake max=$max")
                onShake()
            }
        }
    }

    private fun fireHardShake(now: Long) {
        shakePeaks = 0
        shakeMax = 0f
        shakeActiveUntil = now + 500L
        lastGestureMs = now
        Log.i(TAG, "Hard shake")
        onSustainedShake()
    }

    private fun handleGyro(event: SensorEvent) {
        val now = System.currentTimeMillis()
        
        // Block gestures entirely for 2.5 seconds after launch to prevent 
        // startup spikes from triggering an infinite pulse-crash loop.
        if (now - initTime < 2500L || now < shakeActiveUntil) {
            // A shake in progress must not finish as a flick or an elbow raise.
            if (now < shakeActiveUntil) {
                flicking = false
                flickAngle = 0f
                raiseLatched = true
                resetRoll()
            }
            lastGyroTime = event.timestamp
            return
        }

        val wx = event.values[0]
        val wy = event.values[1]
        val wz = event.values.getOrElse(2) { 0f }
        val absX = abs(wx)
        val absY = abs(wy)
        val absZ = abs(wz)
        val dt = gyroDt(event)

        // Hardware frame, not the rotated right-wrist UI. Right wrist flips the
        // signs and leaves the axes where they are.
        // X, toward 3 o'clock, lies across the wrist: elbow flexion, and a wrist flip.
        // Y, toward 12 o'clock, lies along the forearm: pronation/supination.
        // A short snap on X is the flick (go). Holding that rotation is the elbow
        // raise and commits the rating. A right wrist flips the sign only.
        val twistDominates = absY > absX * AXIS_DOMINANCE && absY > absZ * AXIS_DOMINANCE
        val raiseDominates = absX > absY * AXIS_DOMINANCE && absX > absZ * AXIS_DOMINANCE

        if (raiseDominates && absX > FLIP_THRESHOLD) {
            if (!raiseLatched) {
                if (!flicking) {
                    flicking = true
                    flickStartTime = now
                    flickPeak = absX
                    flickSign = wx
                    flickAngle = 0f
                } else if (absX > flickPeak) {
                    flickPeak = absX
                    flickSign = wx
                }
                flickAngle += absX * dt
                if (flicking && now - flickStartTime > FLICK_MAX_MS) {
                    flicking = false
                    flickAngle = 0f
                    raiseLatched = true
                    if (now - lastGestureMs >= DEBOUNCE_MS) {
                        lastGestureMs = now
                        Log.i(TAG, "Elbow raise on gyro X, peak=$flickPeak")
                        onElbowRaise()
                    }
                }
            }
            resetRoll()
        } else if (flicking) {
            val dur = now - flickStartTime
            val peak = flickPeak
            val angle = flickAngle
            flicking = false
            flickPeak = 0f
            flickAngle = 0f
            val snapped = peak >= FLICK_PEAK_MIN &&
                    angle >= FLICK_MIN_ANGLE_RAD &&
                    dur >= FLICK_MIN_MS &&
                    dur <= FLICK_MAX_MS
            if (snapped && now - lastGestureMs >= DEBOUNCE_MS) {
                lastGestureMs = now
                Log.i(TAG, "Wrist flick on gyro X, peak=$peak dur=$dur angle=$angle")
                if (flickSign > 0f) onFlickUp() else onFlickDown()
            } else if (peak >= 5f) {
                Log.i(TAG, "Rejected flick peak=$peak dur=$dur angle=$angle")
            }
        } else {
            raiseLatched = false
        }

        if (dt <= 0f) return

        if (isRolling && now - rollStartTime > MAX_GESTURE_WINDOW_MS) {
            resetRoll()
        }

        if (twistDominates && absY > ROLL_START_RAD_S && !flicking) {
            if (!isRolling) {
                isRolling = true
                rollStartTime = now
                rollIntegratedAngle = 0f
                rollPeakVelocity = 0f
                rollCrossPeak = 0f
            }
            rollIntegratedAngle += wy * dt
            if (absY > rollPeakVelocity) rollPeakVelocity = absY
            if (absX > rollCrossPeak) rollCrossPeak = absX
        } else if (isRolling) {
            val angleEnough = abs(rollIntegratedAngle) >= MIN_ROLL_ANGLE_RAD
            val twistWon = rollPeakVelocity >= GYRO_ROTATION_THRESHOLD &&
                    angleEnough &&
                    rollPeakVelocity > rollCrossPeak * AXIS_DOMINANCE
            if (twistWon && now - lastGestureMs >= DEBOUNCE_MS) {
                lastGestureMs = now + REVERSAL_SETTLE_MS
                Log.i(
                    TAG,
                    "Wrist roll on gyro Y (forearm twist) peak=$rollPeakVelocity xPeak=$rollCrossPeak angle=$rollIntegratedAngle"
                )
                onWristRoll()
            } else if (rollPeakVelocity >= GYRO_ROTATION_THRESHOLD && angleEnough) {
                Log.i(
                    TAG,
                    "Rejected roll: cross-axis larger (yPeak=$rollPeakVelocity xPeak=$rollCrossPeak)"
                )
            }
            resetRoll()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not used.
    }
}
