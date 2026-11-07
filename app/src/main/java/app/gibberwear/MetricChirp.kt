package app.gibberwear

import androidx.annotation.RawRes

/**
 * Metric-value chirp format (GibberWear v2).
 *
 * A metric value is sent as a short GGWave message (protocol p=4, ultrasound
 * fast — the same protocol as the v1 TAP / DBL / HLD chirps):
 *
 *     <metric letter><value -100 to +100>
 *
 * Metric letters:
 *   E = enjoyment
 *   H = heat / arousal
 *   N = endurance (willingness to endure an activity for someone else)
 *
 * Examples: "E-100" = enjoyment -100, "E0" = enjoyment 0, "H70" = heat +70.
 *
 * Values are quantised to multiples of 10 (-100, -90, ..., 0, ..., +100).
 * Pre-generated WAVs in res/raw are named chirp_<metric>_m<val>.wav for negatives
 * (e.g. chirp_e_m100.wav) and chirp_<metric><val>.wav for non-negatives
 * (e.g. chirp_e0.wav, chirp_e70.wav).
 */
object MetricChirp {

    const val ENJOYMENT = 'E'
    const val HEAT = 'H'
    const val ENDURANCE = 'N'

    /** Values are quantised to multiples of this step before sending. */
    const val STEP = 10

    /** Human-readable name for a metric letter. */
    fun name(metric: Char): String = when (metric) {
        ENJOYMENT -> "Enjoyment"
        HEAT -> "Heat"
        ENDURANCE -> "Endurance"
        else -> "?"
    }

    /** The message string that goes out over GGWave, e.g. "E-100" or "E70". */
    fun message(metric: Char, value: Int): String =
        "$metric${quantize(value)}"

    /** Round a -100..+100 value to the nearest multiple of STEP (10). */
    fun quantize(value: Int): Int {
        val clamped = value.coerceIn(-100, 100)
        val rounded = if (clamped >= 0) {
            (clamped + STEP / 2) / STEP * STEP
        } else {
            (clamped - STEP / 2) / STEP * STEP
        }
        return rounded.coerceIn(-100, 100)
    }

    /** The pre-generated WAV resource for a metric letter + value. */
    @RawRes
    fun resId(metric: Char, value: Int): Int {
        val q = quantize(value)
        return when (metric.uppercaseChar()) {
            ENJOYMENT -> when (q) {
                -100 -> R.raw.chirp_e_m100
                -90 -> R.raw.chirp_e_m90
                -80 -> R.raw.chirp_e_m80
                -70 -> R.raw.chirp_e_m70
                -60 -> R.raw.chirp_e_m60
                -50 -> R.raw.chirp_e_m50
                -40 -> R.raw.chirp_e_m40
                -30 -> R.raw.chirp_e_m30
                -20 -> R.raw.chirp_e_m20
                -10 -> R.raw.chirp_e_m10
                0 -> R.raw.chirp_e0
                10 -> R.raw.chirp_e10
                20 -> R.raw.chirp_e20
                30 -> R.raw.chirp_e30
                40 -> R.raw.chirp_e40
                50 -> R.raw.chirp_e50
                60 -> R.raw.chirp_e60
                70 -> R.raw.chirp_e70
                80 -> R.raw.chirp_e80
                90 -> R.raw.chirp_e90
                else -> R.raw.chirp_e100
            }
            HEAT -> when (q) {
                -100 -> R.raw.chirp_h_m100
                -90 -> R.raw.chirp_h_m90
                -80 -> R.raw.chirp_h_m80
                -70 -> R.raw.chirp_h_m70
                -60 -> R.raw.chirp_h_m60
                -50 -> R.raw.chirp_h_m50
                -40 -> R.raw.chirp_h_m40
                -30 -> R.raw.chirp_h_m30
                -20 -> R.raw.chirp_h_m20
                -10 -> R.raw.chirp_h_m10
                0 -> R.raw.chirp_h0
                10 -> R.raw.chirp_h10
                20 -> R.raw.chirp_h20
                30 -> R.raw.chirp_h30
                40 -> R.raw.chirp_h40
                50 -> R.raw.chirp_h50
                60 -> R.raw.chirp_h60
                70 -> R.raw.chirp_h70
                80 -> R.raw.chirp_h80
                90 -> R.raw.chirp_h90
                else -> R.raw.chirp_h100
            }
            ENDURANCE -> when (q) {
                -100 -> R.raw.chirp_n_m100
                -90 -> R.raw.chirp_n_m90
                -80 -> R.raw.chirp_n_m80
                -70 -> R.raw.chirp_n_m70
                -60 -> R.raw.chirp_n_m60
                -50 -> R.raw.chirp_n_m50
                -40 -> R.raw.chirp_n_m40
                -30 -> R.raw.chirp_n_m30
                -20 -> R.raw.chirp_n_m20
                -10 -> R.raw.chirp_n_m10
                0 -> R.raw.chirp_n0
                10 -> R.raw.chirp_n10
                20 -> R.raw.chirp_n20
                30 -> R.raw.chirp_n30
                40 -> R.raw.chirp_n40
                50 -> R.raw.chirp_n50
                60 -> R.raw.chirp_n60
                70 -> R.raw.chirp_n70
                80 -> R.raw.chirp_n80
                90 -> R.raw.chirp_n90
                else -> R.raw.chirp_n100
            }
            else -> R.raw.chirp_e0
        }
    }
}