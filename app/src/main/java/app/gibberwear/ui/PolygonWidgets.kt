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
fun W3SimplexWidget(
    poleValues: IntArray,
    focusedPoleIndex: Int
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = min(size.width, size.height) / 2f * WidgetGeometry.SIMPLEX_RADIUS_FRAC
        val vertices = WidgetGeometry.simplexVertices(center, radius)

        // Draw triangle
        val path = Path().apply {
            moveTo(vertices[0].x, vertices[0].y)
            lineTo(vertices[1].x, vertices[1].y)
            lineTo(vertices[2].x, vertices[2].y)
            close()
        }
        drawPath(path, color = Color.Gray, style = Stroke(2f))

        // Compute barycentric puck
        val v0 = (poleValues.getOrElse(0) { 0 } + 100f).coerceIn(0f, 200f)
        val v1 = (poleValues.getOrElse(1) { 0 } + 100f).coerceIn(0f, 200f)
        val v2 = (poleValues.getOrElse(2) { 0 } + 100f).coerceIn(0f, 200f)
        val sum = v0 + v1 + v2

        val puckPos = if (sum > 0) {
            val w0 = v0 / sum
            val w1 = v1 / sum
            val w2 = v2 / sum
            Offset(
                w0 * vertices[0].x + w1 * vertices[1].x + w2 * vertices[2].x,
                w0 * vertices[0].y + w1 * vertices[1].y + w2 * vertices[2].y
            )
        } else {
            center
        }

        drawCircle(color = getRingColor(poleValues.getOrElse(focusedPoleIndex) { 0 }), radius = 16f, center = puckPos)
    }
}

@Composable
fun W4TetradWidget(
    poleValues: IntArray,
    focusedPoleIndex: Int
) {
    DrawRadarWeb(4, poleValues, focusedPoleIndex)
}

@Composable
fun W5PentadWidget(
    poleValues: IntArray,
    focusedPoleIndex: Int
) {
    DrawRadarWeb(5, poleValues, focusedPoleIndex)
}