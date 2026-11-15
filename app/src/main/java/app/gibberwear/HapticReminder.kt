package app.gibberwear

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Stoplight Haptic Engine (GibberWear v2).
 *
 * Implements tactile signatures designed specifically for non-verbal signaling:
 *  - GREEN (OK / Proceed): Single crisp mechanical micro-click (subtle detent)
 *  - YELLOW (Caution / Change): Staccato double-tap (two sharp 80ms pulses)
 *  - RED (Emergency Stop / Hold): Urgent 3-burst stutter followed by heavy 350ms rumble
 *
 * Also supports incoming remote prompts:
 *  - Check-in Pulse: gentle 120ms reminder
 *  - Check-in Timer: double 100ms reminder
 */
class HapticReminder(context: Context) {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager =
            context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        manager.defaultVibrator
    } else {
        context.getSystemService(Vibrator::class.java)
    }

    /**
     * 🟢 GREEN ("Go / I'm okay / Continue"):
     * Single crisp mechanical click. Subtle, reassuring detent sensation.
     */
    fun hapticGreen() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                return
            } catch (_: Exception) {}
        }
        // Fallback: 35ms sharp click
        v.vibrate(VibrationEffect.createOneShot(35L, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    /**
     * 🟡 YELLOW ("Caution / Modify / More-Less"):
     * Sharp staccato double-tap: 80ms on, 80ms silence, 80ms on.
     */
    fun hapticYellow() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return

        val pattern = longArrayOf(0, 80, 80, 80)
        v.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    /**
     * 🔴 RED ("STOP / Hard hold / Emergency"):
     * Visceral emergency brake: 3 rapid bursts (70ms/40ms) + sustained 350ms heavy rumble.
     */
    fun hapticRed() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return

        // 0 delay, 70 on, 40 off, 70 on, 40 off, 70 on, 60 off, 350 heavy sustain
        val pattern = longArrayOf(0, 70, 40, 70, 40, 70, 60, 350)
        val amplitudes = intArrayOf(0, 255, 0, 255, 0, 255, 0, 255)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && v.hasAmplitudeControl()) {
            v.vibrate(VibrationEffect.createWaveform(pattern, amplitudes, -1))
        } else {
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        }
    }

    /** Remote check-in pulse prompt (single 120ms reminder). */
    fun pulseSingle() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        v.vibrate(VibrationEffect.createOneShot(120L, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    /** Remote check-in timer prompt (double 100ms reminder). */
    fun pulseDouble() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val pattern = longArrayOf(0, 100, 100, 100)
        v.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    /** Heavy confirmatory thump for Wrist Roll commit (50ms sharp pulse + 120ms resonant deceleration). */
    fun hapticWristRoll() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val pattern = longArrayOf(0, 50, 20, 120)
        val amplitudes = intArrayOf(0, 255, 0, 180)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && v.hasAmplitudeControl()) {
            v.vibrate(VibrationEffect.createWaveform(pattern, amplitudes, -1))
        } else {
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        }
    }
}
