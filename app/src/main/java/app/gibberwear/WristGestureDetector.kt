package app.gibberwear

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Bound-sub wrist gestures (GibberWear v2).
 *
 * When the sub cannot tap (bound wrists), motion sensors stand in for
 * fingers. Coarse only — no precise numbers, no metric cycling via gestures.
 * The detailed numbers get filled in after (aftercare journal) or via the
 * Dom narrating.
 *
 * Mapping (keeps the v1 TAP / DBL / HLD chirp path intact):
 *   shake (accelerometer)            -> "I'm okay / enjoying it"  -> TAP
 *   wrist flip / rotate (gyroscope)  -> "more" or "less"          -> DBL
 *   big sustained shake              -> "stop"                     -> HLD
 *
 * Flip direction is reported to the caller (true = one way, false = the
 * other) so the screen can show "more" vs "less"; the chirp is DBL either
 * way, and the Dom interprets the adjustment request in context.
 *
 * Distinguishing a deliberate gesture from normal scene movement is the
 * hard part. This detector requires a clear, deliberate motion:
 * a sharp acceleration spike well above everyday movement, with a debounce
 * so one motion cannot fire twice. Tune the constants below if gestures
 * fire too easily (raise thresholds) or are missed (lower them).
 *
 * Edge case (out of scope for this build): a fully immobilised wrist
 * cannot shake or flip at all, and needs a different channel (foot, voice,
 * or the Dom's prompt).
 *
 * Lifecycle: call start() in onResume and stop() in onPause so gestures
 * are only listened for while the app is in the foreground (saves battery
 * and avoids false triggers when the app is in the background).
 */
class WristGestureDetector(
    context: Context,
    private val onShake: () -> Unit,
    private val onFlip: (more: Boolean) -> Unit,
    private val onSustainedShake: () -> Unit
) : SensorEventListener {

    companion object {
        /** Acceleration above gravity (m/s^2) that counts as a shake. */
        const val SHAKE_THRESHOLD = 18f

        /** Acceleration above gravity (m/s^2) that starts a "stop" shake. */
        const val STOP_THRESHOLD = 25f

        /** How long acceleration must stay above STOP_THRESHOLD to count as "stop". */
        const val SUSTAINED_MS = 500L

        /** Angular velocity (rad/s) around the forearm axis that counts as a flip. */
        const val FLIP_THRESHOLD = 3.0f

        /** Ignore further gestures within this window after one fires. */
        const val DEBOUNCE_MS = 1000L
    }

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private var lastGestureMs = 0L
    private var sustainedStartMs = 0L
    private var sustainedFired = false

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
        sustainedStartMs = 0L
        sustainedFired = false
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

        if (mag > STOP_THRESHOLD) {
            // Strong motion: may become a sustained "stop" shake.
            if (sustainedStartMs == 0L) sustainedStartMs = now
            if (now - sustainedStartMs >= SUSTAINED_MS && !sustainedFired) {
                sustainedFired = true
                if (now - lastGestureMs >= DEBOUNCE_MS) {
                    lastGestureMs = now
                    onSustainedShake()
                }
            }
            // While above the stop threshold, do not also count a single shake.
            return
        } else {
            sustainedStartMs = 0L
            sustainedFired = false
        }

        if (mag > SHAKE_THRESHOLD) {
            if (now - lastGestureMs >= DEBOUNCE_MS) {
                lastGestureMs = now
                onShake()
            }
        }
    }

    private fun handleGyro(event: SensorEvent) {
        val now = System.currentTimeMillis()
        // Wrist flip = rotation around the forearm (x) axis, i.e. turning
        // the palm up/down. Positive = one way, negative = the other.
        val wx = event.values[0]
        if (abs(wx) > FLIP_THRESHOLD) {
            if (now - lastGestureMs >= DEBOUNCE_MS) {
                lastGestureMs = now
                onFlip(wx > 0)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not used.
    }
}
