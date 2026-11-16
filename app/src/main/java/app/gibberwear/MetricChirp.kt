package app.gibberwear

import android.annotation.SuppressLint
import androidx.annotation.RawRes

/**
 * GibberLink payload encoder and audio fallback mapping.
 */
object MetricChirp {

    /** Values are quantised to multiples of this step before sending. */
    const val STEP = 10

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

    /**
     * Returns the raw resource ID for the given metric and value.
     */
    @SuppressLint("DiscouragedApi")
    @RawRes
    fun resId(context: android.content.Context, metric: Char, value: Int): Int {
        val q = quantize(value)
        val m = metric.lowercaseChar()
        val suffix = if (q < 0) "_m${-q}" else "$q"
        val name = "chirp_${m}${suffix}"
        return context.resources.getIdentifier(name, "raw", context.packageName)
    }

    /**
     * High-density ultrasound payload.
     * Format: R:<KEY>:<HASH4>:<HEX_VALUES>
     *
     * HASH4 is the low 16 bits of FNV-1a over the topic id.
     * Each pole is one hex nibble (0..15).
     * Unipolar widgets (0..100) map 0 to 0 and 100 to 15.
     * Bipolar widgets (-100..+100) map -100 to 0, 0 to 8, and +100 to 15.
     */
    fun encodeHighDensity(
        profileKey: String,
        topicId: String,
        poleValues: IntArray,
        poleCount: Int,
        bipolar: Boolean
    ): String {
        val hexBuilder = StringBuilder()
        for (i in 0 until poleCount) {
            val raw = poleValues.getOrElse(i) { 0 }
            val nibble = if (bipolar) {
                val v = raw.coerceIn(-100, 100)
                ((v + 100) * 15 + 100) / 200
            } else {
                val v = raw.coerceIn(0, 100)
                (v * 15 + 50) / 100
            }.coerceIn(0, 15)
            hexBuilder.append(nibble.toString(16).uppercase())
        }
        return "R:$profileKey:${topicHash(topicId)}:$hexBuilder"
    }

    /** 4-hex-char FNV-1a fold of the topic id. */
    fun topicHash(topicId: String): String {
        var hash = 2166136261u
        for (b in topicId.toByteArray(Charsets.UTF_8)) {
            hash = hash xor b.toUByte().toUInt()
            hash *= 16777619u
        }
        val folded = (hash xor (hash shr 16)) and 0xFFFFu
        return folded.toString(16).uppercase().padStart(4, '0')
    }
}
