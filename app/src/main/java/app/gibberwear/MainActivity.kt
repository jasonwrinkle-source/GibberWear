package app.gibberwear

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RawRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "GibberWearMain"

        const val PREFS_NAME = "gibberwear_prefs"
        const val PREF_PROFILE_KEY = "profile_key"
        const val PREF_TIMER_SEC = "timer_sec"
        const val PREF_LPM = "low_power_mode"
        const val DEFAULT_PROFILE_KEY = "adam"

        const val ARC_START = 100f
        const val ARC_SWEEP = 340f
        const val CHECKIN_SHOW_MS = 3000L
        const val INTERACTION_HIDE_MS = 1500L
        const val FOCUS_DIM_TIMEOUT_MS = 3000L
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var gibberLinkEngine: GibberLinkEngine
    private lateinit var hapticReminder: HapticReminder
    private lateinit var gestureDetector: WristGestureDetector
    private lateinit var bleServer: BleServer

    private var localAudioPlayer: MediaPlayer? = null

    var profileKey by mutableStateOf(DEFAULT_PROFILE_KEY)
        private set
    var timerSec by mutableStateOf(0)
        private set
    var isLowPowerMode by mutableStateOf(false)
        private set

    private var isBleConnected = false

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

    private var centerFlash by mutableStateOf<Color?>(null)
    private var checkInSymbol by mutableStateOf<String?>(null)
    private var isInteracting by mutableStateOf(false)
    private var isFocusAdjusting by mutableStateOf(false)
    
    private val widgetTypes = listOf("R3", "W1", "W2", "W3", "W4", "W5", "W6", "W7", "W8")
    private var widgetTypeIndex by mutableStateOf(0)
    private val currentWidgetType get() = widgetTypes[widgetTypeIndex]

    private val mainHandler = Handler(Looper.getMainLooper())
    private val clearCheckInRunnable = Runnable { checkInSymbol = null }
    private val hideInteractionRunnable = Runnable { isInteracting = false }
    private val restoreEqualBrightnessRunnable = Runnable { isFocusAdjusting = false }

    private fun flashCenter(color: Color) {
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

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        profileKey = prefs.getString(PREF_PROFILE_KEY, DEFAULT_PROFILE_KEY) ?: DEFAULT_PROFILE_KEY
        timerSec = prefs.getInt(PREF_TIMER_SEC, 0)
        isLowPowerMode = prefs.getBoolean(PREF_LPM, false)

        hapticReminder = HapticReminder(this)

        gibberLinkEngine = GibberLinkEngine(this) { incoming ->
            handleIncomingMessage(incoming)
        }

        bleServer = BleServer(
            context = this,
            onConnectionChange = { connected ->
                isBleConnected = connected
                Log.i(TAG, "BLE connection state changed: $connected")
                updateGibberLinkState()
            },
            onMessageReceived = { incoming ->
                handleIncomingMessage(incoming)
            }
        )

        gestureDetector = WristGestureDetector(
            context = this,
            onFlickUp = { cycleRatingType() },
            onFlickDown = { sendTap() },
            onShake = { sendDoubleTap() },
            onSustainedShake = { sendHold() },
            onWristRoll = { sendWristRoll() }
        )

        setContent {
            MaterialTheme {
                GibberWearScreen(
                    profileKey = profileKey,
                    timerSec = timerSec,
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
                        setMetricValue(metric, v, transmit = true)
                    },
                    onRingDrag = { metric, v ->
                        markInteraction()
                        // Drag only smoothly updates local display - no chirp / BLE mid-drag
                        setMetricValue(metric, v, transmit = false)
                    },
                    onRingRelease = { metric ->
                        markInteraction()
                        // Final release transmits the settled value
                        val finalVal = metricValues[metric] ?: 0
                        transmitMetric(metric, finalVal)
                    },
                    onCentreTap = { sendTap() },
                    onDoubleTap = { sendDoubleTap() },
                    onLongPress = { sendHold() },
                    onRotary = { delta ->
                        markInteraction()
                        val currentMetric = metrics[metricIndex]
                        val currentVal = metricValues[currentMetric] ?: 0
                        setMetricValue(currentMetric, currentVal + delta, transmit = true)
                    },
                    onSwipeUp = { cycleWidgetType() },
                    onCheckInTimerTrigger = { triggerCheckInTimer() }
                )
            }
        }
    }

    private fun handleIncomingMessage(raw: String) {
        val msg = raw.trim()
        Log.i(TAG, "Incoming message: $msg")

        when {
            msg.startsWith("CFG:") -> {
                val parts = msg.split(":")
                if (parts.size >= 3) {
                    val newKey = parts[1].trim()
                    val newTimerSec = parts[2].trim().toIntOrNull() ?: 0
                    if (newKey.isNotEmpty()) {
                        profileKey = newKey
                        timerSec = newTimerSec
                        prefs.edit()
                            .putString(PREF_PROFILE_KEY, newKey)
                            .putInt(PREF_TIMER_SEC, newTimerSec)
                            .apply()
                        Log.i(TAG, "Configured profile: key=$newKey, timerSec=$newTimerSec")

                        val ackMsg = "ACK:$newKey:CFG"
                        transmitTelemetry(ackMsg)
                    }
                }
            }

            msg.startsWith("CHK:") -> {
                val parts = msg.split(":")
                if (parts.size >= 3) {
                    val targetKey = parts[1].trim()
                    val type = parts[2].trim()

                    val isTarget = targetKey.equals("ALL", ignoreCase = true) ||
                            targetKey.equals(profileKey, ignoreCase = true)

                    if (isTarget) {
                        when (type.uppercase()) {
                            "P" -> triggerCheckInPulse()
                            "T" -> triggerCheckInTimer()
                        }
                    }
                }
            }

            msg.startsWith("HEAT:") -> {
                val parts = msg.split(":")
                if (parts.size >= 3) {
                    val targetKey = parts[1].trim()
                    val heatVal = parts[2].trim().toIntOrNull()

                    val isTarget = targetKey.equals("ALL", ignoreCase = true) ||
                            targetKey.equals(profileKey, ignoreCase = true)

                    if (isTarget && heatVal != null) {
                        runOnUiThread {
                            setMetricValue(MetricChirp.HEAT, heatVal, transmit = false)
                            flashCenter(Color(0xFFFF9800)) // Amber / heat flash
                            markInteraction()
                            hapticReminder.pulseSingle()
                        }
                        Log.i(TAG, "Adopted heat: key=$targetKey, heat=$heatVal")
                    }
                }
            }
            msg.startsWith("LPM:") -> {
                val parts = msg.split(":")
                if (parts.size >= 2) {
                    val lpmVal = parts[1].trim()
                    isLowPowerMode = (lpmVal == "1" || lpmVal.uppercase() == "ON")
                    prefs.edit().putBoolean(PREF_LPM, isLowPowerMode).apply()
                    Log.i(TAG, "Low Power Mode set to: $isLowPowerMode")
                    updateGibberLinkState()
                }
            }
        }
    }

    private fun updateGibberLinkState() {
        if (isLowPowerMode && isBleConnected) {
            Log.i(TAG, "LPM is ON and BLE is connected. Stopping GibberLink ultrasound.")
            gibberLinkEngine.stopListening()
        } else {
            Log.i(TAG, "LPM is OFF or BLE is disconnected. Starting GibberLink ultrasound.")
            gibberLinkEngine.startListening()
        }
    }

    private fun transmitTelemetry(fullPayload: String, @RawRes fallbackAudioResId: Int? = null) {
        bleServer.sendNotification(fullPayload)
        gibberLinkEngine.sendChirp(fullPayload)
        if (fallbackAudioResId != null) {
            playLocalSound(fallbackAudioResId)
        }
    }

    private fun transmitMetric(metric: Char, value: Int) {
        val clampedDisplay = value.coerceIn(-100, 100)
        val metricMsg = MetricChirp.message(metric, clampedDisplay)
        val fullPayload = "$profileKey:$metricMsg"
        transmitTelemetry(fullPayload, MetricChirp.resId(metric, clampedDisplay))
    }

    private fun playLocalSound(@RawRes resId: Int) {
        try {
            localAudioPlayer?.release()
            localAudioPlayer = MediaPlayer.create(this, resId)?.apply {
                setOnCompletionListener { mp -> mp.release() }
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing local audio: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        gestureDetector.start()
        updateGibberLinkState()
        checkAndStartPermissions()
    }

    override fun onPause() {
        gestureDetector.stop()
        gibberLinkEngine.stopListening()
        super.onPause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(clearCheckInRunnable)
        mainHandler.removeCallbacks(hideInteractionRunnable)
        mainHandler.removeCallbacks(restoreEqualBrightnessRunnable)
        bleServer.stop()
        gibberLinkEngine.release()
        localAudioPlayer?.release()
        super.onDestroy()
    }

    private fun checkAndStartPermissions() {
        val permissionsNeeded = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                permissionsNeeded.add(android.Manifest.permission.BLUETOOTH_ADVERTISE)
            }
            if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                permissionsNeeded.add(android.Manifest.permission.BLUETOOTH_CONNECT)
            }
        }

        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            permissionsNeeded.add(android.Manifest.permission.RECORD_AUDIO)
        }

        if (permissionsNeeded.isNotEmpty()) {
            requestPermissions(permissionsNeeded.toTypedArray(), 1001)
        } else {
            bleServer.start()
            updateGibberLinkState()
        }
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
            updateGibberLinkState()
        }
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
                setMetricValue(currentMetric, currentVal + step, transmit = true)
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    private fun setMetricValue(metric: Char, displayVal: Int, transmit: Boolean = false) {
        val clampedDisplay = displayVal.coerceIn(-100, 100)
        metricValues[metric] = clampedDisplay

        if (transmit) {
            transmitMetric(metric, clampedDisplay)
        }
    }

    private fun cycleRatingType() {
        metricIndex = (metricIndex + 1) % metrics.size
        markInteraction()
        hapticReminder.pulseSingle()
        Log.i(TAG, "Cycled rating type via flick up to: ${metrics[metricIndex]}")
    }

    private fun cycleWidgetType() {
        widgetTypeIndex = (widgetTypeIndex + 1) % widgetTypes.size
        markInteraction()
        hapticReminder.pulseDouble()
        checkInSymbol = currentWidgetType
        mainHandler.removeCallbacks(clearCheckInRunnable)
        mainHandler.postDelayed(clearCheckInRunnable, CHECKIN_SHOW_MS)
        Log.i(TAG, "Cycled widget type via swipe up to: $currentWidgetType")
    }

    private fun sendTap() {
        flashCenter(Color(0xFF2ECC71))
        hapticReminder.hapticGreen()
        transmitTelemetry("$profileKey:TAP", R.raw.chirp_tap)
    }

    private fun sendDoubleTap() {
        flashCenter(Color(0xFFF1C40F))
        hapticReminder.hapticYellow()
        transmitTelemetry("$profileKey:DBL", R.raw.chirp_doubletap)
    }

    private fun sendHold() {
        flashCenter(Color(0xFFE74C3C))
        hapticReminder.hapticRed()
        transmitTelemetry("$profileKey:HLD", R.raw.chirp_hold)
    }

    private fun sendWristRoll() {
        flashCenter(Color.White)
        hapticReminder.hapticWristRoll()
        val e = metricValues[MetricChirp.ENJOYMENT] ?: 0
        val h = metricValues[MetricChirp.HEAT] ?: 0
        val n = metricValues[MetricChirp.ENDURANCE] ?: 0
        val payload = "RATE:$profileKey:default:$e,$h,$n:${System.currentTimeMillis()}"
        transmitTelemetry(payload, null) // no local chirp for now or fallback
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
    profileKey: String,
    timerSec: Int,
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
    onRingRelease: (Char) -> Unit,
    onCentreTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onLongPress: () -> Unit,
    onRotary: (Int) -> Unit,
    onSwipeUp: () -> Unit,
    onCheckInTimerTrigger: () -> Unit
) {
    LaunchedEffect(timerSec) {
        if (timerSec > 0) {
            val intervalMs = timerSec * 1000L
            while (true) {
                delay(intervalMs)
                onCheckInTimerTrigger()
            }
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

    val strokePx = 44f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
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

                    val r1Outer = maxRadius * 0.98f
                    val r1Center = r1Outer - strokePx / 2f
                    val r2Center = r1Center - strokePx
                    val r3Center = r2Center - strokePx
                    val innerCenterBoundary = r3Center - strokePx / 2f

                    val dx = down.position.x - center.x
                    val dy = down.position.y - center.y
                    val dist = sqrt(dx * dx + dy * dy)

                    val touchedMetricIndex = when {
                        dist >= r1Center - strokePx / 2f -> 0
                        dist >= r2Center - strokePx / 2f -> 1
                        dist >= innerCenterBoundary -> 2
                        else -> -1
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
                            if (isDrag && touchedMetricIndex != -1) {
                                // Finger released after dragging: transmit final settled value!
                                onRingRelease(metrics[touchedMetricIndex].first)
                            } else if (isDrag && touchedMetricIndex == -1) {
                                val totalDx = change.position.x - down.position.x
                                val totalDy = change.position.y - down.position.y
                                if (abs(totalDy) > abs(totalDx) && totalDy < -viewConfiguration.touchSlop) {
                                    onSwipeUp()
                                }
                            } else if (!isLongPressFired) {
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

                val alpha = if (isFocusAdjusting) {
                    if (isFocused) 1.0f else 0.25f
                } else {
                    1.0f
                }

                // 1. Track arc
                drawArc(
                    color = Color(0xFF222222).copy(alpha = alpha),
                    startAngle = MainActivity.ARC_START,
                    sweepAngle = MainActivity.ARC_SWEEP,
                    useCenter = false,
                    topLeft = Offset(left, top),
                    size = Size(diameter, diameter),
                    style = stroke
                )

                // 2. Value arc
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

                // 3. Name centered at 12:00 within stroke
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

        // Inner center flash feedback
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
            // Holds flash background
        }

        // Foreground text: drawn on top and allowed to expand across/over inner rings
        Box(
            modifier = Modifier.fillMaxSize(),
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
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                } else if (isInteracting) {
                    Text(
                        text = formattedFocusedVal,
                        color = Color.White,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                } else {
                    // Profile key only - bold and layered on top of rings
                    Text(
                        text = profileKey.uppercase(),
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 2.sp,
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
            profileKey = "adam",
            timerSec = 0,
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
            onRingRelease = {},
            onCentreTap = {},
            onDoubleTap = {},
            onLongPress = {},
            onRotary = {},
            onSwipeUp = {},
            onCheckInTimerTrigger = {}
        )
    }
}
