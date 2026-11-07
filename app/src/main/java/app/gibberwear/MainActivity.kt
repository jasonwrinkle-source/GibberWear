package app.gibberwear

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.window.OnBackInvokedDispatcher
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.sqrt

/**
 * GibberWear v2 — MainActivity.
 */
class MainActivity : ComponentActivity() {

    companion object {
        const val REMINDER_INTERVAL_MS = 5 * 60 * 1000L
        const val ARC_START = 100f
        const val ARC_SWEEP = 340f
        const val CHECKIN_SHOW_MS = 3000L
        const val INTERACTION_HIDE_MS = 1500L
        const val FOCUS_DIM_TIMEOUT_MS = 3000L
    }

    private lateinit var chirpPlayer: ChirpPlayer
    private lateinit var hapticReminder: HapticReminder
    private lateinit var gestureDetector: WristGestureDetector
    private lateinit var bleServer: BleServer

    private val metrics = listOf(
        MetricChirp.ENJOYMENT,
        MetricChirp.HEAT,
        MetricChirp.ENDURANCE
    )
    private var metricIndex by mutableStateOf(0)

    private val metricValues = mutableStateMapOf(
        MetricChirp.ENJOYMENT to 0,
        MetricChirp.HEAT to 0,
        MetricChirp.ENDURANCE to 0
    )

    // Center flash color persists until next tap/double-tap/hold recolors it
    private var centerFlash by mutableStateOf<Color?>(null)
    private var checkInSymbol by mutableStateOf<String?>(null)
    private var isInteracting by mutableStateOf(false)
    private var isFocusAdjusting by mutableStateOf(false)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val clearCheckInRunnable = Runnable { checkInSymbol = null }
    private val hideInteractionRunnable = Runnable { isInteracting = false }
    private val restoreEqualBrightnessRunnable = Runnable { isFocusAdjusting = false }

    private fun flashCenter(color: Color) {
        // Persists until next tap / double-tap / hold recolors it
        centerFlash = color
    }

    private fun markInteraction() {
        isInteracting = true
        mainHandler.removeCallbacks(hideInteractionRunnable)
        mainHandler.postDelayed(hideInteractionRunnable, INTERACTION_HIDE_MS)
        markFocusAdjusting()
    }

    private fun markFocusAdjusting() {
        isFocusAdjusting = true
        mainHandler.removeCallbacks(restoreEqualBrightnessRunnable)
        mainHandler.postDelayed(restoreEqualBrightnessRunnable, FOCUS_DIM_TIMEOUT_MS)
    }

    private fun triggerCheckInPulse() {
        hapticReminder.pulseSingle()
        checkInSymbol = "✓  ♥"
        mainHandler.removeCallbacks(clearCheckInRunnable)
        mainHandler.postDelayed(clearCheckInRunnable, CHECKIN_SHOW_MS)
    }

    private fun triggerCheckInTimer() {
        hapticReminder.pulseDouble()
        checkInSymbol = "✓  ⏱"
        mainHandler.removeCallbacks(clearCheckInRunnable)
        mainHandler.postDelayed(clearCheckInRunnable, CHECKIN_SHOW_MS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        chirpPlayer = ChirpPlayer(this)
        hapticReminder = HapticReminder(this)

        bleServer = BleServer(
            context = this,
            onCheckInPulse = { triggerCheckInPulse() },
            onCheckInTimer = { triggerCheckInTimer() },
            onProfileIdUpdated = { }
        )

        gestureDetector = WristGestureDetector(
            context = this,
            onShake = { sendTap() },
            onFlip = { more -> sendDoubleTap(if (more) "more" else "less") },
            onSustainedShake = { sendHold() }
        )

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                cycleMetricFocus()
            }
        })

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) {
                cycleMetricFocus()
            }
        }

        setContent {
            MaterialTheme {
                GibberWearScreen(
                    focusedMetricIndex = metricIndex,
                    enjoymentVal = metricValues[MetricChirp.ENJOYMENT] ?: 0,
                    heatVal = metricValues[MetricChirp.HEAT] ?: 0,
                    enduranceVal = metricValues[MetricChirp.ENDURANCE] ?: 0,
                    centerFlash = centerFlash,
                    checkInSymbol = checkInSymbol,
                    isInteracting = isInteracting,
                    isFocusAdjusting = isFocusAdjusting,
                    onSelectMetric = { idx ->
                        metricIndex = idx
                        markFocusAdjusting()
                    },
                    onRingTap = { metric, v ->
                        markInteraction()
                        setMetricValue(metric, v, forcePlay = true)
                    },
                    onRingDrag = { metric, v ->
                        markInteraction()
                        setMetricValue(metric, v, forcePlay = false)
                    },
                    onCentreTap = { sendTap() },
                    onDoubleTap = { sendDoubleTap(null) },
                    onLongPress = { sendHold() },
                    onRotary = { delta ->
                        markInteraction()
                        val currentMetric = metrics[metricIndex]
                        val currentVal = metricValues[currentMetric] ?: 0
                        setMetricValue(currentMetric, currentVal + delta, forcePlay = false)
                    },
                    onCheckInTimerTrigger = { triggerCheckInTimer() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        gestureDetector.start()
        checkAndStartBle()
    }

    override fun onPause() {
        gestureDetector.stop()
        super.onPause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(clearCheckInRunnable)
        mainHandler.removeCallbacks(hideInteractionRunnable)
        mainHandler.removeCallbacks(restoreEqualBrightnessRunnable)
        bleServer.stop()
        chirpPlayer.release()
        super.onDestroy()
    }

    private fun checkAndStartBle() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val permissions = arrayOf(
                android.Manifest.permission.BLUETOOTH_ADVERTISE,
                android.Manifest.permission.BLUETOOTH_CONNECT
            )
            val missing = permissions.filter {
                checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) {
                requestPermissions(missing.toTypedArray(), 1001)
                return
            }
        }
        bleServer.start()
    }

    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            bleServer.start()
        }
    }

    private fun cycleMetricFocus() {
        metricIndex = (metricIndex + 1) % metrics.size
        markFocusAdjusting()
    }

    @Suppress("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isStemKey(event.keyCode) || event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_HOME) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                cycleMetricFocus()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isStemKey(keyCode) || keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_HOME) {
            if (event?.repeatCount == 0) {
                cycleMetricFocus()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (isStemKey(keyCode) || keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_HOME) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onKeyShortcut(keyCode: Int, event: KeyEvent?): Boolean {
        if (isStemKey(keyCode) || keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_HOME) {
            cycleMetricFocus()
            return true
        }
        return super.onKeyShortcut(keyCode, event)
    }

    private fun isStemKey(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_STEM_PRIMARY ||
                keyCode == KeyEvent.KEYCODE_STEM_1 ||
                keyCode == KeyEvent.KEYCODE_STEM_2 ||
                keyCode == KeyEvent.KEYCODE_STEM_3 ||
                keyCode == KeyEvent.KEYCODE_APP_SWITCH ||
                keyCode == KeyEvent.KEYCODE_NAVIGATE_NEXT ||
                keyCode == KeyEvent.KEYCODE_NAVIGATE_PREVIOUS ||
                keyCode == KeyEvent.KEYCODE_NAVIGATE_IN ||
                keyCode == KeyEvent.KEYCODE_NAVIGATE_OUT ||
                keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                keyCode == KeyEvent.KEYCODE_ENTER ||
                keyCode == KeyEvent.KEYCODE_HOME ||
                keyCode == KeyEvent.KEYCODE_BACK
    }

    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        if (event?.action == MotionEvent.ACTION_SCROLL) {
            val delta = event.getAxisValue(MotionEvent.AXIS_SCROLL)
            val step = when {
                delta < 0f -> 1
                delta > 0f -> -1
                else -> 0
            }
            if (step != 0) {
                markInteraction()
                val currentMetric = metrics[metricIndex]
                val currentVal = metricValues[currentMetric] ?: 0
                setMetricValue(currentMetric, currentVal + step, forcePlay = false)
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    private fun setMetricValue(metric: Char, displayVal: Int, forcePlay: Boolean = false) {
        val clampedDisplay = displayVal.coerceIn(-100, 100)
        val oldDisplay = metricValues[metric] ?: 0

        val oldQ = MetricChirp.quantize(oldDisplay)
        val newQ = MetricChirp.quantize(clampedDisplay)

        metricValues[metric] = clampedDisplay
        if (forcePlay || oldQ != newQ) {
            chirpPlayer.playMetric(metric, clampedDisplay)
            val msg = MetricChirp.message(metric, clampedDisplay)
            bleServer.sendNotification(msg)
        }
    }

    private fun sendTap() {
        flashCenter(Color(0xFF2ECC71)) // green center flash (persists)
        chirpPlayer.play(R.raw.chirp_tap)
        bleServer.sendNotification("TAP")
    }

    private fun sendDoubleTap(hint: String?) {
        flashCenter(Color(0xFFF1C40F)) // yellow center flash (persists)
        chirpPlayer.play(R.raw.chirp_doubletap)
        bleServer.sendNotification("DBL")
    }

    private fun sendHold() {
        flashCenter(Color(0xFFE74C3C)) // red center flash (persists)
        chirpPlayer.play(R.raw.chirp_hold)
        bleServer.sendNotification("HLD")
    }
}

private fun getRingColor(value: Int): Color {
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
fun GibberWearScreen(
    focusedMetricIndex: Int,
    enjoymentVal: Int,
    heatVal: Int,
    enduranceVal: Int,
    centerFlash: Color?,
    checkInSymbol: String?,
    isInteracting: Boolean,
    isFocusAdjusting: Boolean,
    onSelectMetric: (Int) -> Unit,
    onRingTap: (Char, Int) -> Unit,
    onRingDrag: (Char, Int) -> Unit,
    onCentreTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onLongPress: () -> Unit,
    onRotary: (Int) -> Unit,
    onCheckInTimerTrigger: () -> Unit
) {
    // Local check-in reminder every 5 minutes
    LaunchedEffect(Unit) {
        while (true) {
            delay(MainActivity.REMINDER_INTERVAL_MS)
            onCheckInTimerTrigger()
        }
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    val metrics = listOf(
        Triple(MetricChirp.ENJOYMENT, enjoymentVal, getRingColor(enjoymentVal)),
        Triple(MetricChirp.HEAT, heatVal, getRingColor(heatVal)),
        Triple(MetricChirp.ENDURANCE, enduranceVal, getRingColor(enduranceVal))
    )

    val focusedTriple = metrics[focusedMetricIndex.coerceIn(0, 2)]
    val focusedVal = focusedTriple.second
    val formattedFocusedVal = if (focusedVal > 0) "+$focusedVal" else "$focusedVal"

    // Ring stroke width = 44px
    val strokePx = 44f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                val nativeEvent = keyEvent.nativeKeyEvent
                if (nativeEvent.action == KeyEvent.ACTION_DOWN && nativeEvent.repeatCount == 0) {
                    onSelectMetric((focusedMetricIndex + 1) % 3)
                    true
                } else {
                    false
                }
            }
            .onRotaryScrollEvent { event ->
                val step = when {
                    event.verticalScrollPixels < 0 -> 1
                    event.verticalScrollPixels > 0 -> -1
                    else -> 0
                }
                if (step != 0) onRotary(step)
                true
            }
            .pointerInput(Unit) {
                var lastTapTime = 0L
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downTime = System.currentTimeMillis()
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val maxRadius = min(size.width, size.height) / 2f

                    // Radii of 3 adjacent 44px wide rings
                    val r1Outer = maxRadius * 0.98f
                    val r1Center = r1Outer - strokePx / 2f
                    val r2Center = r1Center - strokePx
                    val r3Center = r2Center - strokePx
                    val innerCenterBoundary = r3Center - strokePx / 2f

                    val dx = down.position.x - center.x
                    val dy = down.position.y - center.y
                    val dist = sqrt(dx * dx + dy * dy)

                    // Touch boundary detection for 44px adjacent rings
                    val touchedMetricIndex = when {
                        dist >= r1Center - strokePx / 2f -> 0 // Enjoyment (Outer)
                        dist >= r2Center - strokePx / 2f -> 1 // Heat (Middle)
                        dist >= innerCenterBoundary -> 2      // Endurance (Inner)
                        else -> -1                             // Center area
                    }

                    var isDrag = false
                    var isLongPressFired = false
                    val longPressTimeout = viewConfiguration.longPressTimeoutMillis

                    while (true) {
                        val event = withTimeoutOrNull(50L) { awaitPointerEvent() }
                        val now = System.currentTimeMillis()

                        if (event == null) {
                            if (!isDrag && !isLongPressFired && (now - downTime >= longPressTimeout)) {
                                isLongPressFired = true
                                onLongPress()
                            }
                            continue
                        }

                        val change = event.changes.firstOrNull { it.id == down.id } ?: break

                        if (change.pressed) {
                            val dragDistance = (change.position - down.position).getDistance()
                            if (dragDistance > viewConfiguration.touchSlop) {
                                isDrag = true
                            }
                            if (isDrag && touchedMetricIndex != -1) {
                                change.consume()
                                onSelectMetric(touchedMetricIndex)
                                val cDx = change.position.x - center.x
                                val cDy = change.position.y - center.y
                                val v = calculateValueFromAngle(cDx, cDy)
                                onRingDrag(metrics[touchedMetricIndex].first, v)
                            }
                        } else {
                            if (!isDrag && !isLongPressFired) {
                                if (touchedMetricIndex != -1) {
                                    onSelectMetric(touchedMetricIndex)
                                    val v = calculateValueFromAngle(dx, dy)
                                    onRingTap(metrics[touchedMetricIndex].first, v)
                                } else {
                                    if (now - lastTapTime < 300L) {
                                        onDoubleTap()
                                        lastTapTime = 0L
                                    } else {
                                        lastTapTime = now
                                        onCentreTap()
                                    }
                                }
                            }
                            break
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val maxRadius = min(size.width, size.height) / 2f

            // Calculate 44px adjacent radii with ZERO gap between rings
            val r1Outer = maxRadius * 0.98f
            val r1Center = r1Outer - strokePx / 2f
            val r2Center = r1Center - strokePx
            val r3Center = r2Center - strokePx
            val ringCenterRadii = listOf(r1Center, r2Center, r3Center)

            metrics.forEachIndexed { index, (metricChar, valInt, color) ->
                val isFocused = (index == focusedMetricIndex)
                val centerRadius = ringCenterRadii[index]
                val diameter = centerRadius * 2f
                val left = (size.width - diameter) / 2f
                val top = (size.height - diameter) / 2f
                val stroke = Stroke(width = strokePx, cap = StrokeCap.Round)

                // Alpha: dim unfocused rings while focus-adjusting; equal brightness when idle!
                val alpha = if (isFocusAdjusting) {
                    if (isFocused) 1.0f else 0.25f
                } else {
                    1.0f // Equal brightness when restored
                }

                // 1. Track arc (dim background)
                drawArc(
                    color = Color(0xFF222222).copy(alpha = alpha),
                    startAngle = MainActivity.ARC_START,
                    sweepAngle = MainActivity.ARC_SWEEP,
                    useCenter = false,
                    topLeft = Offset(left, top),
                    size = Size(diameter, diameter),
                    style = stroke
                )

                // 2. Value arc: starts at 12:00 (270°), fills CW (+170°) for positive, CCW (-170°) for negative
                if (valInt != 0) {
                    val sweepFromNoon = 170f * (valInt / 100f)
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

                // 3. Name centered at 12:00 and contained 100% WITHIN the 44px ring stroke
                val name = MetricChirp.name(metricChar).uppercase()
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

                // Render each letter rotated around center to 12:00 so characters NEVER overlap or pile up
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

        // Inner center circle (flashes tap/doubletap/hold inside center only, persists)
        // Dynamically sized to fit inside the inner 44px ring
        val density = LocalDensity.current
        val maxRadius = min(384f, 384f) / 2f
        val r1Outer = maxRadius * 0.98f
        val r1Center = r1Outer - strokePx / 2f
        val r2Center = r1Center - strokePx
        val r3Center = r2Center - strokePx
        val innerBoundaryPx = (r3Center - strokePx / 2f) * 2f
        val innerCircleDp = with(density) { innerBoundaryPx.toDp() }

        Box(
            modifier = Modifier
                .size(innerCircleDp)
                .clip(CircleShape)
                .background(centerFlash ?: Color.Transparent),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
            ) {
                if (checkInSymbol != null) {
                    Text(
                        text = checkInSymbol,
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                } else if (isInteracting) {
                    Text(
                        text = formattedFocusedVal,
                        color = Color.White,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

private fun calculateValueFromAngle(dx: Float, dy: Float): Int {
    var angleDeg = atan2(dy, dx) * 180f / PI.toFloat()
    if (angleDeg < 0) angleDeg += 360f
    val rel = (angleDeg - MainActivity.ARC_START + 360f) % 360f
    return if (rel <= MainActivity.ARC_SWEEP) {
        val frac = rel / MainActivity.ARC_SWEEP
        (-100 + frac * 200f).toInt().coerceIn(-100, 100)
    } else {
        if (rel < 350f) 100 else -100
    }
}

@Preview(device = "id:wearos_small_round", showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun GibberWearPreviewOverview() {
    MaterialTheme {
        GibberWearScreen(
            focusedMetricIndex = 0,
            enjoymentVal = 70,
            heatVal = 50,
            enduranceVal = 30,
            centerFlash = null,
            checkInSymbol = null,
            isInteracting = false,
            isFocusAdjusting = false,
            onSelectMetric = {},
            onRingTap = { _, _ -> },
            onRingDrag = { _, _ -> },
            onCentreTap = {},
            onDoubleTap = {},
            onLongPress = {},
            onRotary = {},
            onCheckInTimerTrigger = {}
        )
    }
}

@Preview(device = "id:wearos_small_round", showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun GibberWearPreviewEnjoymentAdjusting() {
    MaterialTheme {
        GibberWearScreen(
            focusedMetricIndex = 0,
            enjoymentVal = 75,
            heatVal = 40,
            enduranceVal = 20,
            centerFlash = null,
            checkInSymbol = null,
            isInteracting = true,
            isFocusAdjusting = true,
            onSelectMetric = {},
            onRingTap = { _, _ -> },
            onRingDrag = { _, _ -> },
            onCentreTap = {},
            onDoubleTap = {},
            onLongPress = {},
            onRotary = {},
            onCheckInTimerTrigger = {}
        )
    }
}

@Preview(device = "id:wearos_small_round", showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun GibberWearPreviewHeatAdjusting() {
    MaterialTheme {
        GibberWearScreen(
            focusedMetricIndex = 1,
            enjoymentVal = 60,
            heatVal = 85,
            enduranceVal = 40,
            centerFlash = null,
            checkInSymbol = null,
            isInteracting = true,
            isFocusAdjusting = true,
            onSelectMetric = {},
            onRingTap = { _, _ -> },
            onRingDrag = { _, _ -> },
            onCentreTap = {},
            onDoubleTap = {},
            onLongPress = {},
            onRotary = {},
            onCheckInTimerTrigger = {}
        )
    }
}

@Preview(device = "id:wearos_small_round", showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun GibberWearPreviewCheckInTimer() {
    MaterialTheme {
        GibberWearScreen(
            focusedMetricIndex = 0,
            enjoymentVal = 70,
            heatVal = 50,
            enduranceVal = 30,
            centerFlash = null,
            checkInSymbol = "✓  ⏱",
            isInteracting = false,
            isFocusAdjusting = false,
            onSelectMetric = {},
            onRingTap = { _, _ -> },
            onRingDrag = { _, _ -> },
            onCentreTap = {},
            onDoubleTap = {},
            onLongPress = {},
            onRotary = {},
            onCheckInTimerTrigger = {}
        )
    }
}

@Preview(device = "id:wearos_small_round", showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun GibberWearPreviewTapFlash() {
    MaterialTheme {
        GibberWearScreen(
            focusedMetricIndex = 0,
            enjoymentVal = 70,
            heatVal = 50,
            enduranceVal = 30,
            centerFlash = Color(0xFF2ECC71),
            checkInSymbol = null,
            isInteracting = false,
            isFocusAdjusting = false,
            onSelectMetric = {},
            onRingTap = { _, _ -> },
            onRingDrag = { _, _ -> },
            onCentreTap = {},
            onDoubleTap = {},
            onLongPress = {},
            onRotary = {},
            onCheckInTimerTrigger = {}
        )
    }
}