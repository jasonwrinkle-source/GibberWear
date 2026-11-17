package app.gibberwear.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

@Composable
internal fun DrawRadarWeb(
    poleCount: Int,
    poleValues: IntArray,
    focusedPoleIndex: Int,
    angleOffset: Float = -90f
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = min(size.width, size.height) / 2f * WidgetGeometry.RADAR_RADIUS_FRAC
        val sliceDeg = 360f / poleCount

        val angles = List(poleCount) { i -> angleOffset + i * sliceDeg }

        // Draw 3 concentric guide webs
        val guideFractions = listOf(0.33f, 0.66f, 1.0f)
        guideFractions.forEach { frac ->
            val path = Path()
            for (i in 0 until poleCount) {
                val rad = angles[i] * PI.toFloat() / 180f
                val pt = Offset(center.x + (radius * frac) * cos(rad), center.y + (radius * frac) * sin(rad))
                if (i == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
            }
            path.close()
            drawPath(path, color = Color.Gray.copy(alpha = 0.3f), style = Stroke(1f))
        }

        val fillPath = Path()
        for (i in 0 until poleCount) {
            val v = poleValues.getOrElse(i) { 0 }.coerceIn(0, 100) / 100f
            val rad = angles[i] * PI.toFloat() / 180f
            val maxPt = Offset(center.x + radius * cos(rad), center.y + radius * sin(rad))
            val pt = Offset(center.x + (radius * v) * cos(rad), center.y + (radius * v) * sin(rad))

            if (i == 0) fillPath.moveTo(pt.x, pt.y) else fillPath.lineTo(pt.x, pt.y)

            val focused = i == focusedPoleIndex
            val strokeW = if (focused) 6f else 2f
            val axisColor = if (focused) Color.White else Color.Gray.copy(alpha = 0.5f)
            drawLine(axisColor, center, maxPt, strokeWidth = strokeW)
            drawCircle(color = axisColor, radius = if (focused) 8f else 5f, center = pt)
        }
        fillPath.close()

        drawPath(fillPath, color = getRingColor(poleValues.getOrElse(focusedPoleIndex) { 0 }).copy(alpha = 0.5f))
    }
}

@Composable
fun W6HexadWidget(
    poleValues: IntArray,
    focusedPoleIndex: Int
) {
    DrawRadarWeb(6, poleValues, focusedPoleIndex)
}

@Composable
fun W7HeptadWidget(
    poleValues: IntArray,
    focusedPoleIndex: Int
) {
    DrawRadarWeb(7, poleValues, focusedPoleIndex)
}

@Composable
fun W8OctadWidget(
    poleValues: IntArray,
    focusedPoleIndex: Int
) {
    DrawRadarWeb(8, poleValues, focusedPoleIndex)
}