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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import app.gibberwear.ui.*
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
    private lateinit var hapticReminder: HapticReminder
    private lateinit var gestureDetector: WristGestureDetector
    private lateinit var bleServer: BleServer
    private lateinit var gibberLinkEngine: GibberLinkEngine

    private var localAudioPlayer: MediaPlayer? = null

    var profileKey by mutableStateOf(DEFAULT_PROFILE_KEY)
        private set
    var timerSec by mutableStateOf(0)
        private set
    var isLowPowerMode by mutableStateOf(false)
        private set

    private var isBleConnected = false

    private val widgetTypes = WidgetType.entries.toTypedArray()
    private var widgetTypeIndex by mutableStateOf(widgetTypes.indexOf(WidgetType.W3_RINGS).coerceAtLeast(0))
    private val currentWidgetType get() = widgetTypes[widgetTypeIndex]

    // Generalized N-Pole State
    private var poleValues by mutableStateOf(IntArray(8) { 0 })
    private var focusedPoleIndex by mutableStateOf(0)

    private var centerFlash by mutableStateOf<Color?>(null)
    private var checkInSymbol by mutableStateOf<String?>(null)
    private var isInteracting by mutableStateOf(false)
    private var isFocusAdjusting by mutableStateOf(false)
    private var isTransceiving by mutableStateOf(false)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val clearCheckInRunnable = Runnable { checkInSymbol = null }
    private val hideInteractionRunnable = Runnable { isInteracting = false }
    private val restoreEqualBrightnessRunnable = Runnable { isFocusAdjusting = false }
    private val hideTransceivingRunnable = Runnable { isTransceiving = false }

    private fun flashCenter(color: Color) {
        centerFlash = color
    }

    private fun markTransceiving() {
        isTransceiving = true
        mainHandler.removeCallbacks(hideTransceivingRunnable)
        mainHandler.postDelayed(hideTransceivingRunnable, 2000L)
    }

    private fun markInteraction() {
        isInteracting = true
        mainHandler.removeCallbacks(hideInteractionRunnable)
        mainHandler.postDelayed(hideInteractionRunnable, INTERACTION_HIDE_MS)
    }

    private fun markFocusAdjusting() {
        isFocusAdjusting = true
        mainHandler.removeCallbacks(restoreEqualBrightnessRunnable)
        mainHandler.postDelayed(restoreEqualBrightnessRunnable, FOCUS_DIM_TIMEOUT_MS)
    }

    private fun triggerCheckInPulse() {
        flashCenter(Color(0xFFE91E63))
        checkInSymbol = "✓ ♥"
        hapticReminder.pulseSingle()
        mainHandler.removeCallbacks(clearCheckInRunnable)
        mainHandler.postDelayed(clearCheckInRunnable, CHECKIN_SHOW_MS)
    }

    private fun triggerCheckInTimer() {
        flashCenter(Color(0xFF3498DB))
        checkInSymbol = "✓ ⏱"
        hapticReminder.pulseDouble()
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
            onFlickUp = { cycleRatingType(1) },
            onFlickDown = { cycleRatingType(-1) },
            onShake = { sendDoubleTap() },
            onSustainedShake = { sendHold() },
            onWristRoll = { sendWristRoll() }
        )

        setContent {
            MaterialTheme {
                GibberWearScreen(
                    profileKey = profileKey,
                    timerSec = timerSec,
                    activeWidget = currentWidgetType,
                    poleValues = poleValues,
                    focusedPoleIndex = focusedPoleIndex,
                    centerFlash = centerFlash,
                    checkInSymbol = checkInSymbol,
                    isInteracting = isInteracting,
                    isTransceiving = isTransceiving,
                    isFocusAdjusting = isFocusAdjusting,
                    onSelectPole = { idx ->
                        focusedPoleIndex = idx
                        markFocusAdjusting()
                    },
                    onPoleTap = { idx, v ->
                        markInteraction()
                        setPoleValue(idx, v, transmit = true)
                    },
                    onPoleDrag = { idx, v ->
                        markInteraction()
                        setPoleValue(idx, v, transmit = false)
                    },
                    onPoleRelease = { _ ->
                        markInteraction()
                        transmitAllPoles()
                    },
                    onCentreTap = { sendTap() },
                    onDoubleTap = { sendDoubleTap() },
                    onLongPress = { sendHold() },
                    onRotary = { delta ->
                        markInteraction()
                        val currentVal = poleValues[focusedPoleIndex]
                        setPoleValue(focusedPoleIndex, currentVal + delta, transmit = true)
                    },
                    onSwipeUp = { cycleWidgetType(1) },
                    onSwipeDown = { cycleWidgetType(-1) },
                    onCheckInTimerTrigger = { triggerCheckInTimer() }
                )
            }
        }
    }

    private fun handleIncomingMessage(raw: String) {
        val msg = raw.trim()
        Log.i(TAG, "Incoming message: $msg")
        markTransceiving()

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
        markTransceiving()
        bleServer.sendNotification(fullPayload)

        // Prevent sending long payloads to GGWave which has a strict byte limit
        if (!fullPayload.startsWith("RATE:")) {
            gibberLinkEngine.sendChirp(fullPayload)
        }

        if (fallbackAudioResId != null) {
            playLocalSound(fallbackAudioResId)
        }
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
            if (delta != 0f) {
                val step = if (delta > 0) 5 else -5
                markInteraction()
                val currentVal = poleValues[focusedPoleIndex]
                setPoleValue(focusedPoleIndex, currentVal + step, transmit = true)
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    private fun setPoleValue(poleIndex: Int, displayVal: Int, transmit: Boolean = false) {
        val clampedDisplay = displayVal.coerceIn(-100, 100)
        val newValues = poleValues.copyOf()
        newValues[poleIndex] = clampedDisplay
        poleValues = newValues

        if (transmit) {
            transmitAllPoles()
        }
    }

    private fun transmitAllPoles() {
        val poleCount = currentWidgetType.poleCount
        val valuesStr = poleValues.take(poleCount).joinToString(",")
        val topicId = "debug-widget-cycle" // Placeholder for debug manual cycling

        val payload = "RATE:$profileKey:$topicId:$valuesStr:${System.currentTimeMillis()}"
        transmitTelemetry(payload, null)
    }

    private fun cycleRatingType(direction: Int = 1) {
        val count = currentWidgetType.poleCount
        focusedPoleIndex = (focusedPoleIndex + direction % count + count) % count
        markInteraction()
        hapticReminder.pulseSingle()
        Log.i(TAG, "Cycled rating focus via flick to index: $focusedPoleIndex")
    }

    private fun cycleWidgetType(direction: Int = 1) {
        val size = widgetTypes.size
        widgetTypeIndex = (widgetTypeIndex + direction % size + size) % size
        focusedPoleIndex = 0 // reset focus
        markInteraction()
        hapticReminder.pulseDouble()
        checkInSymbol = currentWidgetType.name
        mainHandler.removeCallbacks(clearCheckInRunnable)
        mainHandler.postDelayed(clearCheckInRunnable, CHECKIN_SHOW_MS)
        Log.i(TAG, "Cycled widget type to: ${currentWidgetType.name}")
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

        val poleCount = currentWidgetType.poleCount
        val valuesStr = poleValues.take(poleCount).joinToString(",")
        val topicId = currentWidgetType.topicHash

        val payload = "RATE:$profileKey:$topicId:$valuesStr:${System.currentTimeMillis()}"
        transmitTelemetry(payload, null)

        // Use compressed payload for ultrasound fallback
        val ultrasoundPayload = MetricChirp.encodeHighDensity(profileKey, topicId, poleValues, poleCount)
        gibberLinkEngine.sendChirp(ultrasoundPayload)
    }
}

private fun getPoleAt(pos: Offset, size: Size, widgetType: WidgetType, strokePx: Float): Int {
    val center = Offset(size.width / 2f, size.height / 2f)
    val maxRadius = min(size.width, size.height) / 2f
    val dx = pos.x - center.x
    val dy = pos.y - center.y
    val dist = sqrt(dx * dx + dy * dy)

    return when (widgetType) {
        WidgetType.W1_MONO -> {
            val r1Outer = maxRadius * 0.98f
            val innerBoundary = r1Outer - strokePx * 1.5f
            if (dist >= innerBoundary) 0 else -1
        }
        WidgetType.W2_SWITCHBOX -> {
            val r1Outer = maxRadius * 0.98f
            val innerBoundary = r1Outer - strokePx * 1.5f
            if (dist >= innerBoundary) {
                if (dy < 0) 0 else 1 // Top = 0, Bottom = 1
            } else {
                -1
            }
        }
        WidgetType.W3_RINGS -> {
            val r1Outer = maxRadius * 0.98f
            val r1Center = r1Outer - strokePx / 2f
            val r2Center = r1Center - strokePx
            val r3Center = r2Center - strokePx
            val innerCenterBoundary = r3Center - strokePx / 2f
            when {
                dist >= r1Center - strokePx / 2f -> 0
                dist >= r2Center - strokePx / 2f -> 1
                dist >= innerCenterBoundary -> 2
                else -> -1
            }
        }
        else -> {
            if (dist < maxRadius * 0.3f) -1
            else {
                var angleDeg = atan2(dy, dx) * 180f / Math.PI.toFloat()
                // Shift angle so that 0 is at the top (-90 degrees)
                var shiftedAngle = (angleDeg + 90f) % 360f
                if (shiftedAngle < 0) shiftedAngle += 360f
                
                val slice = 360f / widgetType.poleCount
                var poleIndex = ((shiftedAngle + slice / 2f) / slice).toInt()
                if (poleIndex >= widgetType.poleCount) poleIndex = 0
                
                poleIndex
            }
        }
    }
}

@Composable
fun GibberWearScreen(
    profileKey: String,
    timerSec: Int,
    activeWidget: WidgetType,
    poleValues: IntArray,
    focusedPoleIndex: Int,
    centerFlash: Color?,
    checkInSymbol: String?,
    isInteracting: Boolean,
    isTransceiving: Boolean,
    isFocusAdjusting: Boolean,
    onSelectPole: (Int) -> Unit,
    onPoleTap: (Int, Int) -> Unit,
    onPoleDrag: (Int, Int) -> Unit,
    onPoleRelease: (Int) -> Unit,
    onCentreTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onLongPress: () -> Unit,
    onRotary: (Int) -> Unit,
    onSwipeUp: () -> Unit,
    onSwipeDown: () -> Unit,
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

    val focusedVal = poleValues.getOrElse(focusedPoleIndex) { 0 }
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
            .pointerInput(activeWidget) {
                var lastTapTime = 0L
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downTime = System.currentTimeMillis()
                    val center = Offset(size.width / 2f, size.height / 2f)

                    val dx = down.position.x - center.x
                    val dy = down.position.y - center.y

                    val touchedPoleIndex = getPoleAt(down.position, Size(size.width.toFloat(), size.height.toFloat()), activeWidget, strokePx)

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
                            if (isDrag && touchedPoleIndex != -1) {
                                change.consume()
                                onSelectPole(touchedPoleIndex)
                                val cDx = change.position.x - center.x
                                val cDy = change.position.y - center.y
                                val v = calculateValueFromAngle(cDx, cDy)
                                onPoleDrag(touchedPoleIndex, v)
                            }
                        } else {
                            if (isDrag && touchedPoleIndex != -1) {
                                onPoleRelease(touchedPoleIndex)
                            } else if (isDrag && touchedPoleIndex == -1) {
                                val totalDx = change.position.x - down.position.x
                                val totalDy = change.position.y - down.position.y
                                if (abs(totalDy) > abs(totalDx)) {
                                    if (totalDy < -viewConfiguration.touchSlop) {
                                        onSwipeUp()
                                    } else if (totalDy > viewConfiguration.touchSlop) {
                                        onSwipeDown()
                                    }
                                }
                            } else if (!isLongPressFired) {
                                if (touchedPoleIndex != -1) {
                                    onSelectPole(touchedPoleIndex)
                                    val v = calculateValueFromAngle(dx, dy)
                                    onPoleTap(touchedPoleIndex, v)
                                } else {
                                    // Disable center taps if we're on a rating/polygon widget
                                    if (activeWidget == WidgetType.W1_MONO || activeWidget == WidgetType.W2_SWITCHBOX || activeWidget == WidgetType.W3_RINGS) {
                                        if (now - lastTapTime < 300L) {
                                            onDoubleTap()
                                            lastTapTime = 0L
                                        } else {
                                            lastTapTime = now
                                            onCentreTap()
                                        }
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
        when (activeWidget) {
            WidgetType.W1_MONO -> W1MonoWidget(poleValues, focusedPoleIndex, strokePx)
            WidgetType.W2_SWITCHBOX -> W2SwitchboxWidget(poleValues, focusedPoleIndex, strokePx)
            WidgetType.W3_RINGS -> W3RingsWidget(poleValues, focusedPoleIndex, isFocusAdjusting, strokePx)
            WidgetType.W3_SIMPLEX -> W3SimplexWidget(poleValues, focusedPoleIndex)
            WidgetType.W4_TETRAD -> W4TetradWidget(poleValues, focusedPoleIndex)
            WidgetType.W5_PENTAD -> W5PentadWidget(poleValues, focusedPoleIndex)
            WidgetType.W6_HEXAD -> W6HexadWidget(poleValues, focusedPoleIndex)
            WidgetType.W7_HEPTAD -> W7HeptadWidget(poleValues, focusedPoleIndex)
            WidgetType.W8_OCTAD -> W8OctadWidget(poleValues, focusedPoleIndex)
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
        }

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
                } else if (isTransceiving) {
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
