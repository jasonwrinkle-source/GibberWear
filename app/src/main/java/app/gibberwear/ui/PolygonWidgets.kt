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
    Canvas(modifier = Modifier.fillMaxSize()) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = min(size.width, size.height) / 2f * WidgetGeometry.RADAR_RADIUS_FRAC

        val angles = listOf(-90f, 0f, 90f, 180f)
        val vertices = angles.map { angle ->
            val rad = angle * PI.toFloat() / 180f
            Offset(center.x + radius * cos(rad), center.y + radius * sin(rad))
        }

        // Draw diamond
        val diamondPath = Path().apply {
            moveTo(vertices[0].x, vertices[0].y)
            lineTo(vertices[1].x, vertices[1].y)
            lineTo(vertices[2].x, vertices[2].y)
            lineTo(vertices[3].x, vertices[3].y)
            close()
        }
        drawPath(diamondPath, color = Color.Gray, style = Stroke(2f))

        val fillPath = Path()
        for (i in 0 until 4) {
            val v = poleValues.getOrElse(i) { 0 }.coerceIn(0, 100) / 100f
            val rad = angles[i] * PI.toFloat() / 180f
            val pt = Offset(center.x + (radius * v) * cos(rad), center.y + (radius * v) * sin(rad))
            if (i == 0) fillPath.moveTo(pt.x, pt.y) else fillPath.lineTo(pt.x, pt.y)

            // Draw axis line thicker if focused
            val strokeW = if (i == focusedPoleIndex) 6f else 2f
            val axisColor = if (i == focusedPoleIndex) Color.White else Color.Gray.copy(alpha = 0.5f)
            drawLine(axisColor, center, vertices[i], strokeWidth = strokeW)
        }
        fillPath.close()

        drawPath(fillPath, color = getRingColor(poleValues.getOrElse(focusedPoleIndex) { 0 }).copy(alpha = 0.5f))
    }
}

@Composable
fun W5PentadWidget(
    poleValues: IntArray,
    focusedPoleIndex: Int
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = min(size.width, size.height) / 2f * WidgetGeometry.RADAR_RADIUS_FRAC

        val angles = List(5) { i -> -90f + i * 72f }
        val vertices = angles.map { angle ->
            val rad = angle * PI.toFloat() / 180f
            Offset(center.x + radius * cos(rad), center.y + radius * sin(rad))
        }

        // Draw pentagon
        val shapePath = Path().apply {
            moveTo(vertices[0].x, vertices[0].y)
            for (i in 1 until 5) lineTo(vertices[i].x, vertices[i].y)
            close()
        }
        drawPath(shapePath, color = Color.Gray, style = Stroke(2f))

        val fillPath = Path()
        for (i in 0 until 5) {
            val v = poleValues.getOrElse(i) { 0 }.coerceIn(0, 100) / 100f
            val rad = angles[i] * PI.toFloat() / 180f
            val pt = Offset(center.x + (radius * v) * cos(rad), center.y + (radius * v) * sin(rad))
            if (i == 0) fillPath.moveTo(pt.x, pt.y) else fillPath.lineTo(pt.x, pt.y)

            val strokeW = if (i == focusedPoleIndex) 6f else 2f
            val axisColor = if (i == focusedPoleIndex) Color.White else Color.Gray.copy(alpha = 0.5f)
            drawLine(axisColor, center, vertices[i], strokeWidth = strokeW)
        }
        fillPath.close()

        drawPath(fillPath, color = getRingColor(poleValues.getOrElse(focusedPoleIndex) { 0 }).copy(alpha = 0.5f))
    }
}