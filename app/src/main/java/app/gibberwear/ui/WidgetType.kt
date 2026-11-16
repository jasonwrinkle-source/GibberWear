package app.gibberwear.ui

/**
 * N-pole rating widgets.
 *
 * Concentric rings are the 1-, 2-, and 3-pole arc sliders (W1, W1x2, W1x3).
 * W2 is the split switchbox. W3 is the barycentric triad. W4–W8 are radar polygons.
 * Ring and switchbox poles are bipolar (-100..+100). Simplex and radar poles are 0..100.
 */
enum class WidgetType(
    val poleCount: Int,
    val label: String,
    val minValue: Int,
    val maxValue: Int
) {
    W1_MONO(1, "W1", -100, 100),
    W1X2(2, "W1x2", -100, 100),
    W3_RINGS(3, "W1x3", -100, 100),
    W2_SWITCHBOX(2, "W2", -100, 100),
    W3_SIMPLEX(3, "W3", 0, 100),
    W4_TETRAD(4, "W4", 0, 100),
    W5_PENTAD(5, "W5", 0, 100),
    W6_HEXAD(6, "W6", 0, 100),
    W7_HEPTAD(7, "W7", 0, 100),
    W8_OCTAD(8, "W8", 0, 100);

    val bipolar: Boolean get() = minValue < 0

    companion object {
        /**
         * PROG / CH_ADD widget codes.
         * R3 and W1x3 are the 3-ring slider. W3 is the barycentric simplex.
         */
        fun fromCode(code: String): WidgetType? = when (code.trim().uppercase()) {
            "W1", "R1", "W1X1" -> W1_MONO
            "W1X2", "R2" -> W1X2
            "W1X3", "R3" -> W3_RINGS
            "W2" -> W2_SWITCHBOX
            "W3" -> W3_SIMPLEX
            "W4" -> W4_TETRAD
            "W5" -> W5_PENTAD
            "W6" -> W6_HEXAD
            "W7" -> W7_HEPTAD
            "W8" -> W8_OCTAD
            else -> null
        }
    }
}
