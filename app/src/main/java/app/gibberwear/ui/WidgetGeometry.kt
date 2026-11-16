package app.gibberwear.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Shared hit-testing for the n-pole widgets.
 * Arc constants match the strokes drawn in RadialWidgets.
 */
object WidgetGeometry {

    /** Classic concentric rings (W1x2, W1x3): gap centered under 6 o'clock. */
    const val RINGS_ARC_START = 100f
    const val RINGS_ARC_SWEEP = 340f

    /** Single monopole arc: 270° from 135° to 45°, zero at 12 o'clock. */
    const val MONO_ARC_START = 135f
    const val MONO_ARC_SWEEP = 270f

    const val RADAR_RADIUS_FRAC = 0.85f
    const val SIMPLEX_RADIUS_FRAC = 0.85f

    /** Equilateral triad: 12:00, 4:00, 8:00 in screen space (y down). */
    val SIMPLEX_ANGLES = floatArrayOf(-90f, 30f, 150f)

    fun ringCenters(maxRadius: Float, strokePx: Float, count: Int): FloatArray {
        val outer = maxRadius * 0.98f
        return FloatArray(count) { i -> outer - strokePx / 2f - i * strokePx }
    }

    fun simplexVertices(center: Offset, radius: Float): List<Offset> {
        return SIMPLEX_ANGLES.map { angle ->
            val rad = angle * PI.toFloat() / 180f
            Offset(center.x + radius * cos(rad), center.y + radius * sin(rad))
        }
    }

    fun poleAt(pos: Offset, size: Size, widget: WidgetType, strokePx: Float): Int {
        val center = Offset(size.width / 2f, size.height / 2f)
        val maxRadius = min(size.width, size.height) / 2f
        val dx = pos.x - center.x
        val dy = pos.y - center.y
        val dist = sqrt(dx * dx + dy * dy)

        return when (widget) {
            WidgetType.W1_MONO,
            WidgetType.W1X2,
            WidgetType.W3_RINGS -> {
                val count = widget.poleCount
                val centers = ringCenters(maxRadius, strokePx, count)
                for (i in 0 until count) {
                    val innerEdge = centers[i] - strokePx / 2f
                    if (dist >= innerEdge) return i
                }
                -1
            }
            WidgetType.W2_SWITCHBOX -> {
                val outer = maxRadius * 0.98f
                val innerEdge = outer - strokePx
                if (dist < innerEdge) -1 else if (dy < 0f) 0 else 1
            }
            WidgetType.W3_SIMPLEX -> -1
            else -> {
                // Hub is reserved for swipe-to-cycle. Spokes outside it snap to the nearest pole.
                if (dist < maxRadius * 0.16f) return -1
                val angleDeg = atan2(dy, dx) * 180f / PI.toFloat()
                var shifted = (angleDeg + 90f) % 360f
                if (shifted < 0f) shifted += 360f
                val slice = 360f / widget.poleCount
                var index = ((shifted + slice / 2f) / slice).toInt()
                if (index >= widget.poleCount) index = 0
                index
            }
        }
    }

    fun valueAt(pos: Offset, size: Size, widget: WidgetType, poleIndex: Int): Int {
        val center = Offset(size.width / 2f, size.height / 2f)
        val dx = pos.x - center.x
        val dy = pos.y - center.y
        return when (widget) {
            WidgetType.W1_MONO -> valueOnArc(dx, dy, MONO_ARC_START, MONO_ARC_SWEEP)
            WidgetType.W1X2,
            WidgetType.W3_RINGS -> valueOnArc(dx, dy, RINGS_ARC_START, RINGS_ARC_SWEEP)
            WidgetType.W2_SWITCHBOX -> valueOnSwitchbox(dx, dy, poleIndex)
            else -> {
                val dist = sqrt(dx * dx + dy * dy)
                val outer = min(size.width, size.height) / 2f * RADAR_RADIUS_FRAC
                if (outer <= 0f) 0
                else ((dist / outer).coerceIn(0f, 1f) * 100f).toInt()
            }
        }
    }

    /**
     * Barycentric weights inside the triad, scaled to integers that sum to 100.
     * Null when the touch is outside the triangle.
     */
    fun simplexWeights(pos: Offset, size: Size): IntArray? {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = min(size.width, size.height) / 2f * SIMPLEX_RADIUS_FRAC
        val v = simplexVertices(center, radius)
        val w = barycentric(pos, v[0], v[1], v[2])
        if (w[0] < -0.02f || w[1] < -0.02f || w[2] < -0.02f) return null
        val clamped = floatArrayOf(
            w[0].coerceAtLeast(0f),
            w[1].coerceAtLeast(0f),
            w[2].coerceAtLeast(0f)
        )
        val sum = clamped[0] + clamped[1] + clamped[2]
        if (sum <= 0f) return intArrayOf(34, 33, 33)
        val raw = FloatArray(3) { clamped[it] / sum * 100f }
        val ints = IntArray(3) { raw[it].toInt() }
        var leftover = 100 - ints.sum()
        val order = (0..2).sortedByDescending { raw[it] - ints[it] }
        var k = 0
        while (leftover > 0) {
            ints[order[k % 3]]++
            leftover--
            k++
        }
        return ints
    }

    private fun valueOnArc(dx: Float, dy: Float, start: Float, sweep: Float): Int {
        var angle = atan2(dy, dx) * 180f / PI.toFloat()
        if (angle < 0f) angle += 360f
        val rel = (angle - start + 360f) % 360f
        if (rel <= sweep) {
            return (-100f + rel / sweep * 200f).toInt().coerceIn(-100, 100)
        }
        val gapMid = sweep + (360f - sweep) / 2f
        return if (rel < gapMid) 100 else -100
    }

    private fun valueOnSwitchbox(dx: Float, dy: Float, poleIndex: Int): Int {
        var angle = atan2(dy, dx) * 180f / PI.toFloat()
        if (angle < 0f) angle += 360f
        return if (poleIndex == 0) {
            val shifted = if (angle < 180f) angle + 360f else angle
            val rel = shifted - 180f
            (-100f + rel / 180f * 200f).toInt().coerceIn(-100, 100)
        } else {
            (-100f + angle / 180f * 200f).toInt().coerceIn(-100, 100)
        }
    }

    private fun barycentric(p: Offset, a: Offset, b: Offset, c: Offset): FloatArray {
        val den = (b.y - c.y) * (a.x - c.x) + (c.x - b.x) * (a.y - c.y)
        if (den == 0f) return floatArrayOf(-1f, -1f, -1f)
        val w0 = ((b.y - c.y) * (p.x - c.x) + (c.x - b.x) * (p.y - c.y)) / den
        val w1 = ((c.y - a.y) * (p.x - c.x) + (a.x - c.x) * (p.y - c.y)) / den
        val w2 = 1f - w0 - w1
        return floatArrayOf(w0, w1, w2)
    }
}
