package app.gibberwear

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Haptic check-in reminder (GibberWear v2).
 *
 * Supports two distinct check-in signals:
 *  1. Check-in Pulse: single vibration pulse (150 ms)
 *  2. Check-in Timer: double vibration pulse (100 ms on, 100 ms off, 100 ms on)
 */
class HapticReminder(context: Context) {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager =
            context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        manager.defaultVibrator
    } else {
        context.getSystemService(Vibrator::class.java)
    }

    private val doublePattern = longArrayOf(0, 100, 100, 100)

    /** Vibrate ONCE (150 ms) for Check-in Pulse signal. */
    fun pulseSingle() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        v.vibrate(VibrationEffect.createOneShot(150L, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    /** Vibrate TWICE (100 ms on, 100 ms off, 100 ms on) for Check-in Timer signal. */
    fun pulseDouble() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        v.vibrate(VibrationEffect.createWaveform(doublePattern, -1))
    }
}