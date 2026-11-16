package app.gibberwear.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min

fun getRingColor(value: Int): Color {
    val yellow = Color(0xFFF1C40F)
    val green = Color(0xFF2ECC71)
    val red = Color(0xFFE74C3C)

    val t = (abs(value) / 100f).coerceIn(0f, 1f)
    return if (value >= 0) {
        Color(
            red = yellow.red + t * (green.red - yellow.red),
            green = yellow.green + t * (green.green - yellow.green),
            blue = yellow.blue + t * (green.blue - yellow.blue),
            alpha = 1.0f
        )
    } else {
        Color(
            red = yellow.red + t * (red.red - yellow.red),
            green = yellow.green + t * (red.green - yellow.green),
            blue = yellow.blue + t * (red.blue - yellow.blue),
            alpha = 1.0f
        )
    }
}

@Composable
fun W1MonoWidget(
    poleValues: IntArray,
    focusedPoleIndex: Int,
    strokePx: Float
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val valInt = poleValues.getOrElse(0) { 0 }
        val color = getRingColor(valInt)
        val maxRadius = min(size.width, size.height) / 2f
        val r1Outer = maxRadius * 0.98f
        val r1Center = r1Outer - strokePx / 2f
        val diameter = r1Center * 2f
        val left = (size.width - diameter) / 2f
        val top = (size.height - diameter) / 2f
        val stroke = Stroke(width = strokePx, cap = StrokeCap.Round)
        val halfSweep = WidgetGeometry.MONO_ARC_SWEEP / 2f

        drawArc(
            color = Color(0xFF222222),
            startAngle = WidgetGeometry.MONO_ARC_START,
            sweepAngle = WidgetGeometry.MONO_ARC_SWEEP,
            useCenter = false,
            topLeft = Offset(left, top),
            size = Size(diameter, diameter),
            style = stroke
        )

        if (valInt != 0) {
            val sweepFromNoon = halfSweep * (valInt / 100f)
            drawArc(
                color = color,
                startAngle = 270f,
                sweepAngle = sweepFromNoon,
                useCenter = false,
                topLeft = Offset(left, top),
                size = Size(diameter, diameter),
                style = stroke
            )
        }
    }
}

@Composable
fun W1X2Widget(
    poleValues: IntArray,
    focusedPoleIndex: Int,
    isFocusAdjusting: Boolean,
    strokePx: Float
) {
    ConcentricRings(
        ringCount = 2,
        names = listOf("ENJOYMENT", "HEAT"),
        poleValues = poleValues,
        focusedPoleIndex = focusedPoleIndex,
        isFocusAdjusting = isFocusAdjusting,
        strokePx = strokePx
    )
}

@Composable
fun W2SwitchboxWidget(
    poleValues: IntArray,
    focusedPoleIndex: Int,
    strokePx: Float
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val maxRadius = min(size.width, size.height) / 2f
        val rCenter = (maxRadius * 0.98f) - strokePx / 2f
        val diameter = rCenter * 2f
        val left = (size.width - diameter) / 2f
        val top = (size.height - diameter) / 2f
        val stroke = Stroke(width = strokePx, cap = StrokeCap.Round)

        // Pole 0: Top hemi-circle (180 degrees from 180 to 360/0)
        val val0 = poleValues.getOrElse(0) { 0 }
        val color0 = getRingColor(val0)
        val alpha0 = if (focusedPoleIndex == 0) 1.0f else 0.4f

        drawArc(
            color = Color(0xFF222222).copy(alpha = alpha0),
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(left, top),
            size = Size(diameter, diameter),
            style = stroke
        )
        if (val0 != 0) {
            val sweep0 = 90f * (val0 / 100f)
            drawArc(
                color = color0.copy(alpha = alpha0),
                startAngle = 270f, // Noon
                sweepAngle = sweep0,
                useCenter = false,
                topLeft = Offset(left, top),
                size = Size(diameter, diameter),
                style = stroke
            )
        }

        // Pole 1: Bottom hemi-circle (180 degrees from 0 to 180)
        val val1 = poleValues.getOrElse(1) { 0 }
        val color1 = getRingColor(val1)
        val alpha1 = if (focusedPoleIndex == 1) 1.0f else 0.4f

        drawArc(
            color = Color(0xFF222222).copy(alpha = alpha1),
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(left, top),
            size = Size(diameter, diameter),
            style = stroke
        )
        if (val1 != 0) {
            val sweep1 = 90f * (val1 / 100f)
            drawArc(
                color = color1.copy(alpha = alpha1),
                startAngle = 90f, // 6:00
                sweepAngle = sweep1,
                useCenter = false,
                topLeft = Offset(left, top),
                size = Size(diameter, diameter),
                style = stroke
            )
        }
    }
}

@Composable
fun W3RingsWidget(
    poleValues: IntArray,
    focusedPoleIndex: Int,
    isFocusAdjusting: Boolean,
    strokePx: Float
) {
    ConcentricRings(
        ringCount = 3,
        names = listOf("ENJOYMENT", "HEAT", "ENDURANCE"),
        poleValues = poleValues,
        focusedPoleIndex = focusedPoleIndex,
        isFocusAdjusting = isFocusAdjusting,
        strokePx = strokePx
    )
}

@Composable
private fun ConcentricRings(
    ringCount: Int,
    names: List<String>,
    poleValues: IntArray,
    focusedPoleIndex: Int,
    isFocusAdjusting: Boolean,
    strokePx: Float
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val maxRadius = min(size.width, size.height) / 2f
        val ringCenterRadii = WidgetGeometry.ringCenters(maxRadius, strokePx, ringCount).toList()
        val halfSweep = WidgetGeometry.RINGS_ARC_SWEEP / 2f

        for (index in 0 until ringCount) {
            val valInt = poleValues.getOrElse(index) { 0 }
            val color = getRingColor(valInt)
            val isFocused = (index == focusedPoleIndex)
            val centerRadius = ringCenterRadii[index]
            val diameter = centerRadius * 2f
            val left = (size.width - diameter) / 2f
            val top = (size.height - diameter) / 2f
            val stroke = Stroke(width = strokePx, cap = StrokeCap.Round)

            val alpha = if (isFocusAdjusting) {
                if (isFocused) 1.0f else 0.25f
            } else {
                1.0f
            }

            drawArc(
                color = Color(0xFF222222).copy(alpha = alpha),
                startAngle = WidgetGeometry.RINGS_ARC_START,
                sweepAngle = WidgetGeometry.RINGS_ARC_SWEEP,
                useCenter = false,
                topLeft = Offset(left, top),
                size = Size(diameter, diameter),
                style = stroke
            )

            if (valInt != 0) {
                val sweepFromNoon = halfSweep * (valInt / 100f)
                drawArc(
                    color = color.copy(alpha = alpha),
                    startAngle = 270f,
                    sweepAngle = sweepFromNoon,
                    useCenter = false,
                    topLeft = Offset(left, top),
                    size = Size(diameter, diameter),
                    style = stroke
                )
            }

            val name = names[index]
            val nativeCanvas = drawContext.canvas.nativeCanvas
            val centerX = size.width / 2f
            val centerY = size.height / 2f

            val textPaint = android.graphics.Paint().apply {
                this.color = if (isFocusAdjusting && !isFocused) android.graphics.Color.GRAY else android.graphics.Color.WHITE
                this.textSize = if (isFocused) 10.dp.toPx() else 8.5.dp.toPx()
                this.isAntiAlias = true
                this.textAlign = android.graphics.Paint.Align.CENTER
                this.isFakeBoldText = isFocused
            }

            val numChars = name.length
            val charWidths = FloatArray(numChars)
            textPaint.getTextWidths(name, charWidths)
            val totalWidth = charWidths.sum()
            val totalAngleDeg = (totalWidth / centerRadius) * (180f / PI.toFloat())
            var currentAngleDeg = 270f - (totalAngleDeg / 2f)

            for (i in 0 until numChars) {
                val charW = charWidths[i]
                val charAngleDeg = (charW / centerRadius) * (180f / PI.toFloat())
                val midCharAngleDeg = currentAngleDeg + (charAngleDeg / 2f)

                nativeCanvas.save()
                nativeCanvas.rotate(midCharAngleDeg + 90f, centerX, centerY)
                val yPos = centerY - centerRadius + (textPaint.textSize / 3.2f)
                nativeCanvas.drawText(name[i].toString(), centerX, yPos, textPaint)
                nativeCanvas.restore()

                currentAngleDeg += charAngleDeg
            }
        }
    }
}

@Composable
fun GenericPlaceholderWidget(
    widgetType: WidgetType,
    poleValues: IntArray,
    focusedPoleIndex: Int,
    strokePx: Float
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val radius = min(size.width, size.height) / 2f * 0.8f
        drawCircle(color = Color.DarkGray, radius = radius, style = Stroke(strokePx))
    }
}