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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import app.gibberwear.ui.*
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "GibberWearMain"

        const val PREFS_NAME = "gibberwear_prefs"
        const val PREF_PROFILE_KEY = "profile_key"
        const val PREF_TIMER_SEC = "timer_sec"
        const val PREF_LPM = "low_power_mode"
        const val PREF_HEAT = "heat_value"
        const val DEFAULT_PROFILE_KEY = "adam"

        /** Heat ring on W1x2 and W1x3. Outer ring is enjoyment. */
        const val HEAT_POLE = 1

        const val CHECKIN_SHOW_MS = 3000L
        const val PLACEHOLDER_TOPIC = "debug-widget-cycle"
        const val INTERACTION_HIDE_MS = 1500L
        const val FOCUS_DIM_TIMEOUT_MS = 3000L
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var hapticReminder: HapticReminder
    private lateinit var gestureDetector: WristGestureDetector
    private lateinit var bleServer: BleServer
    private lateinit var gestureSpeaker: GestureSpeaker
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

    // Draft pole values. Nothing is transmitted until a wrist roll.
    private var poleValues by mutableStateOf(IntArray(8) { 0 })
    private var focusedPoleIndex by mutableStateOf(0)
    private var activeTopicId by mutableStateOf(PLACEHOLDER_TOPIC)

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
        profileKey = (prefs.getString(PREF_PROFILE_KEY, DEFAULT_PROFILE_KEY) ?: DEFAULT_PROFILE_KEY)
            .replace("\u0000", "")
            .ifEmpty { DEFAULT_PROFILE_KEY }
        timerSec = prefs.getInt(PREF_TIMER_SEC, 0)
        isLowPowerMode = prefs.getBoolean(PREF_LPM, false)
        val savedHeat = prefs.getInt(PREF_HEAT, 0).coerceIn(-100, 100)
        if (savedHeat != 0) {
            val next = poleValues.copyOf()
            next[HEAT_POLE] = savedHeat
            poleValues = next
        }

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

        gestureSpeaker = GestureSpeaker(this)
        gestureSpeaker.start()

        gestureDetector = WristGestureDetector(
            context = this,
            onFlickUp = { sendTap("Flick. Go.") },
            onFlickDown = { sendTap("Flick. Go.") },
            onElbowRaise = { sendWristRoll("Elbow raise. Rating.") },
            onShake = { sendDoubleTap("Shake. Caution.") },
            onSustainedShake = { sendHold("Hard shake. Stop.") },
            onWristRoll = { sendWristRoll("Twist. Rating.") }
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
                        setPoleValue(idx, v)
                    },
                    onPoleDrag = { idx, v ->
                        markInteraction()
                        setPoleValue(idx, v)
                    },
                    onSimplex = { weights ->
                        markInteraction()
                        setPoles(weights)
                        focusedPoleIndex = weights.indices.maxBy { weights[it] }
                    },
                    onPoleRelease = { markInteraction() },
                    onCentreTap = { sendTap("Tap. Go.") },
                    onDoubleTap = { sendDoubleTap("Double tap. Caution.") },
                    onToggleTriad = { toggleTriadLayout() },
                    onLongPress = { sendHold("Hold. Stop.") },
                    onRotary = { delta ->
                        markInteraction()
                        markFocusAdjusting()
                        val currentVal = poleValues[focusedPoleIndex]
                        setPoleValue(
                            focusedPoleIndex,
                            currentVal + delta,
                            renormalizeSimplex = currentWidgetType == WidgetType.W3_SIMPLEX
                        )
                    },
                    onSwipeUp = { cycleWidgetType(1) },
                    onSwipeDown = { cycleWidgetType(-1) },
                    onCheckInTimerTrigger = { triggerCheckInTimer() }
                )
            }
        }
    }

    /** Profile key or ALL. A blank address is the deprecated unkeyed form and does not match. */
    private fun isAddressed(target: String): Boolean {
        val address = target.trim()
        if (address.isEmpty()) return false
        return address.equals("ALL", ignoreCase = true) ||
                address.equals(profileKey, ignoreCase = true)
    }

    private fun handleIncomingMessage(raw: String) {
        // BLE writes and decoded chirps sometimes include a C-string NUL.
        // Leave it in and the next ACK encodes that byte and native ggwave crashes.
        val stripped = raw.replace("\u0000", "")
        if (stripped.length != raw.length) {
            Log.w(TAG, "Incoming message contained a NUL; stripped before parsing")
        }
        val msg = stripped.trim()
        Log.i(TAG, "Incoming message: $msg")
        markTransceiving()

        when {
            msg.startsWith("CFG:") -> {
                // Live Muse sends CFG:<key>:<timer>. The longer form is CFG:<key>:<name>:<timer>.
                val parts = msg.split(":")
                if (parts.size >= 3) {
                    val newKey = parts[1].trim()
                    val newTimerSec = if (parts.size >= 4 && parts[2].toIntOrNull() == null) {
                        parts[3].trim().toIntOrNull() ?: 0
                    } else {
                        parts[2].trim().toIntOrNull() ?: 0
                    }
                    when {
                        newKey.isEmpty() -> Log.i(TAG, "Ignoring CFG with no key")
                        newKey.equals("ALL", ignoreCase = true) -> {
                            timerSec = newTimerSec
                            prefs.edit().putInt(PREF_TIMER_SEC, newTimerSec).apply()
                            Log.i(TAG, "Configured timer for ALL: timerSec=$newTimerSec key=$profileKey")
                            transmitTelemetry("ACK:$profileKey:CFG")
                        }
                        else -> {
                            profileKey = newKey
                            timerSec = newTimerSec
                            prefs.edit()
                                .putString(PREF_PROFILE_KEY, newKey)
                                .putInt(PREF_TIMER_SEC, newTimerSec)
                                .apply()
                            Log.i(TAG, "Configured profile: key=$newKey, timerSec=$newTimerSec")
                            transmitTelemetry("ACK:$newKey:CFG")
                        }
                    }
                }
            }

            msg.startsWith("PROG:") -> {
                // PROG:<TARGET>:<TOPIC_ID>:<WIDGET>:<TIMEOUT_SEC>
                val parts = msg.split(":")
                if (parts.size >= 4) {
                    val target = parts[1].trim()
                    val topic = parts[2].trim()
                    val widget = WidgetType.fromCode(parts[3].trim())
                    if (isAddressed(target) && topic.isNotEmpty() && widget != null) {
                        activeTopicId = topic
                        showWidget(widget, resetTopic = false, clearValues = true)
                        hapticReminder.pulseDouble()
                        checkInSymbol = widget.label
                        mainHandler.removeCallbacks(clearCheckInRunnable)
                        mainHandler.postDelayed(clearCheckInRunnable, CHECKIN_SHOW_MS)
                        Log.i(TAG, "PROG topic=$topic widget=${widget.label}")
                    }
                }
            }

            msg.startsWith("CHK:") -> {
                val parts = msg.split(":")
                if (parts.size >= 3) {
                    val targetKey = parts[1].trim()
                    val type = parts[2].trim()

                    if (isAddressed(targetKey)) {
                        when (type.uppercase()) {
                            "P" -> triggerCheckInPulse()
                            "T" -> triggerCheckInTimer()
                        }
                    }
                }
            }

            msg.startsWith("HEAT:") -> {
                // Heat-popover sync, sent after ACK:<key>:CFG. HEAT:<key>:<value>, -100..100.
                val parts = msg.split(":")
                if (parts.size >= 3) {
                    val target = parts[1].trim()
                    val heat = parts[2].trim().toIntOrNull()
                    if (heat != null && isAddressed(target)) {
                        applySyncedHeat(heat)
                    } else {
                        Log.i(TAG, "Ignoring HEAT target=$target value=${parts[2].trim()}")
                    }
                }
            }

            msg.startsWith("LPM:") -> {
                // LPM:<KEY|ALL>:<STATE>. Unkeyed LPM:1 is ignored.
                val parts = msg.split(":")
                if (parts.size >= 3 && isAddressed(parts[1])) {
                    val lpmVal = parts[2].trim()
                    isLowPowerMode = (lpmVal == "1" || lpmVal.equals("ON", ignoreCase = true))
                    prefs.edit().putBoolean(PREF_LPM, isLowPowerMode).apply()
                    Log.i(TAG, "Low Power Mode set to: $isLowPowerMode")
                    updateGibberLinkState()
                } else {
                    Log.i(TAG, "Ignoring LPM without a key or ALL")
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
        mainHandler.removeCallbacks(hideTransceivingRunnable)
        bleServer.stop()
        gibberLinkEngine.release()
        gestureSpeaker.stop()
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
                markFocusAdjusting()
                val currentVal = poleValues[focusedPoleIndex]
                setPoleValue(
                    focusedPoleIndex,
                    currentVal + step,
                    renormalizeSimplex = currentWidgetType == WidgetType.W3_SIMPLEX
                )
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    /**
     * Copy the profile heat onto the heat ring. Sent by Muse as HEAT:<key>:<value>
     * about 1.5s after the watch ACKs CFG. Not a rating commit, so nothing is transmitted.
     */
    private fun applySyncedHeat(raw: Int) {
        val heat = raw.coerceIn(-100, 100)
        val showsHeat = currentWidgetType == WidgetType.W1X2 ||
                currentWidgetType == WidgetType.W3_RINGS
        if (!showsHeat) {
            showWidget(WidgetType.W3_RINGS, resetTopic = false, clearValues = false)
        }
        focusedPoleIndex = HEAT_POLE
        setPoleValue(HEAT_POLE, heat)
        prefs.edit().putInt(PREF_HEAT, heat).apply()
        checkInSymbol = null
        mainHandler.removeCallbacks(clearCheckInRunnable)
        markFocusAdjusting()
        markInteraction()
        hapticReminder.pulseSingle()
        Log.i(TAG, "Synced heat rater to $heat")
    }

    private fun setPoleValue(poleIndex: Int, displayVal: Int, renormalizeSimplex: Boolean = false) {
        val type = currentWidgetType
        if (poleIndex !in 0 until type.poleCount) return
        val next = poleValues.copyOf()
        next[poleIndex] = displayVal.coerceIn(type.minValue, type.maxValue)
        if (renormalizeSimplex && type == WidgetType.W3_SIMPLEX) {
            normalizeSimplexAround(next, poleIndex)
        }
        poleValues = next
    }

    private fun setPoles(values: IntArray) {
        val type = currentWidgetType
        val next = poleValues.copyOf()
        for (i in values.indices) {
            if (i < type.poleCount) {
                next[i] = values[i].coerceIn(type.minValue, type.maxValue)
            }
        }
        poleValues = next
    }

    private fun showWidget(type: WidgetType, resetTopic: Boolean, clearValues: Boolean) {
        widgetTypeIndex = widgetTypes.indexOf(type).coerceAtLeast(0)
        focusedPoleIndex = 0
        if (resetTopic) activeTopicId = PLACEHOLDER_TOPIC
        val next = if (clearValues) IntArray(8) else poleValues.copyOf()
        for (i in next.indices) {
            next[i] = next[i].coerceIn(type.minValue, type.maxValue)
        }
        if (type == WidgetType.W3_SIMPLEX && !clearValues) {
            scaleSimplexTo100(next)
        }
        // A radar vertex at 0 sits on the hub, so an all-zero face has no area.
        // 50 is the midpoint web and shows a regular polygon immediately.
        if (type.poleCount >= 4 && type.minValue >= 0) {
            if ((0 until type.poleCount).all { next[it] == 0 }) {
                for (i in 0 until type.poleCount) next[i] = 50
            }
        }
        poleValues = next
    }

    private fun toggleTriadLayout() {
        val target = if (currentWidgetType == WidgetType.W3_RINGS) {
            WidgetType.W3_SIMPLEX
        } else {
            WidgetType.W3_RINGS
        }
        showWidget(target, resetTopic = false, clearValues = false)
        hapticReminder.pulseSingle()
        Log.i(TAG, "Toggled triad layout to ${target.label}")
    }

    /** Keep a simplex composition summing to 100 after the crown moves one pole. */
    private fun normalizeSimplexAround(values: IntArray, locked: Int) {
        val remain = (100 - values[locked]).coerceAtLeast(0)
        val others = (0 until 3).filter { it != locked }
        val otherSum = others.sumOf { values[it].coerceAtLeast(0) }
        if (otherSum <= 0) {
            val each = remain / others.size
            var extra = remain - each * others.size
            others.forEach { index ->
                values[index] = each + if (extra > 0) 1 else 0
                if (extra > 0) extra--
            }
        } else {
            others.forEach { index ->
                values[index] = values[index].coerceAtLeast(0) * remain / otherSum
            }
            var extra = remain - others.sumOf { values[it] }
            val order = others.sortedByDescending { values[it] }
            var k = 0
            while (extra > 0 && order.isNotEmpty()) {
                values[order[k % order.size]]++
                extra--
                k++
            }
        }
    }

    private fun scaleSimplexTo100(values: IntArray) {
        val sum = (0 until 3).sumOf { values[it].coerceAtLeast(0) }
        if (sum <= 0) {
            values[0] = 34
            values[1] = 33
            values[2] = 33
            return
        }
        val scaled = IntArray(3) { values[it].coerceAtLeast(0) * 100 / sum }
        var extra = 100 - scaled.sum()
        val order = (0..2).sortedByDescending { values[it].coerceAtLeast(0) }
        var k = 0
        while (extra > 0) {
            scaled[order[k % 3]]++
            extra--
            k++
        }
        for (i in 0 until 3) values[i] = scaled[i]
    }

    private fun cycleWidgetType(direction: Int = 1) {
        val size = widgetTypes.size
        val nextIndex = (widgetTypeIndex + direction % size + size) % size
        showWidget(widgetTypes[nextIndex], resetTopic = true, clearValues = false)
        markInteraction()
        hapticReminder.pulseDouble()
        checkInSymbol = currentWidgetType.label
        mainHandler.removeCallbacks(clearCheckInRunnable)
        mainHandler.postDelayed(clearCheckInRunnable, CHECKIN_SHOW_MS)
        Log.i(TAG, "Cycled widget type to: ${currentWidgetType.label}")
    }

    private fun sendTap(spoken: String) {
        gestureSpeaker.say(spoken)
        flashCenter(Color(0xFF2ECC71))
        hapticReminder.hapticGreen()
        transmitTelemetry("$profileKey:TAP", R.raw.chirp_tap)
    }

    private fun sendDoubleTap(spoken: String) {
        gestureSpeaker.say(spoken)
        flashCenter(Color(0xFFF1C40F))
        hapticReminder.hapticYellow()
        transmitTelemetry("$profileKey:DBL", R.raw.chirp_doubletap)
    }

    private fun sendHold(spoken: String) {
        gestureSpeaker.say(spoken)
        flashCenter(Color(0xFFE74C3C))
        hapticReminder.hapticRed()
        transmitTelemetry("$profileKey:HLD", R.raw.chirp_hold)
    }

    private fun sendWristRoll(spoken: String) {
        gestureSpeaker.say(spoken)
        // Leave the ring center well clear. A white disk there washes out "RATED"
        // and covers the 1x, 2x, and 3x tap target.
        centerFlash = null
        hapticReminder.hapticWristRoll()

        val type = currentWidgetType
        val poleCount = type.poleCount
        val valuesStr = poleValues.take(poleCount).joinToString(",")
        val payload = "RATE:$profileKey:$activeTopicId:$valuesStr:${System.currentTimeMillis()}"
        transmitTelemetry(payload, null)

        val ultrasoundPayload = MetricChirp.encodeHighDensity(
            profileKey,
            activeTopicId,
            poleValues,
            poleCount,
            bipolar = type.bipolar
        )
        gibberLinkEngine.sendChirp(ultrasoundPayload)

        checkInSymbol = "\u2713 RATED"
        mainHandler.removeCallbacks(clearCheckInRunnable)
        mainHandler.postDelayed(clearCheckInRunnable, CHECKIN_SHOW_MS)
        Log.i(TAG, "Wrist roll committed $payload / $ultrasoundPayload")
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
    onSimplex: (IntArray) -> Unit,
    onPoleRelease: () -> Unit,
    onCentreTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onToggleTriad: () -> Unit,
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
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { canvasSize = it }
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
                    val canvas = Size(size.width.toFloat(), size.height.toFloat())
                    val dx = down.position.x - canvas.width / 2f
                    val dy = down.position.y - canvas.height / 2f
                    val dist = sqrt(dx * dx + dy * dy)
                    val hub = dist < min(canvas.width, canvas.height) / 2f * 0.16f
                    val triad = activeWidget == WidgetType.W3_RINGS ||
                            activeWidget == WidgetType.W3_SIMPLEX
                    val simplexDown = if (activeWidget == WidgetType.W3_SIMPLEX && !hub) {
                        WidgetGeometry.simplexWeights(down.position, canvas)
                    } else {
                        null
                    }
                    val touchedPoleIndex = when {
                        simplexDown != null -> simplexDown.indices.maxBy { simplexDown[it] }
                        activeWidget == WidgetType.W3_SIMPLEX -> -1
                        else -> WidgetGeometry.poleAt(down.position, canvas, activeWidget, strokePx)
                    }

                    var isDrag = false
                    var isLongPressFired = false
                    val longPressTimeout = viewConfiguration.longPressTimeoutMillis
                    val editingPole = touchedPoleIndex != -1 || simplexDown != null

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
                            if (isDrag && activeWidget == WidgetType.W3_SIMPLEX && !hub) {
                                val weights = WidgetGeometry.simplexWeights(change.position, canvas)
                                if (weights != null) {
                                    change.consume()
                                    onSimplex(weights)
                                }
                            } else if (isDrag && touchedPoleIndex != -1) {
                                change.consume()
                                onSelectPole(touchedPoleIndex)
                                val v = WidgetGeometry.valueAt(
                                    change.position,
                                    canvas,
                                    activeWidget,
                                    touchedPoleIndex
                                )
                                onPoleDrag(touchedPoleIndex, v)
                            }
                        } else {
                            if (isDrag && editingPole) {
                                onPoleRelease()
                            } else if (isDrag) {
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
                                if (simplexDown != null) {
                                    onSimplex(simplexDown)
                                } else if (touchedPoleIndex != -1) {
                                    onSelectPole(touchedPoleIndex)
                                    val v = WidgetGeometry.valueAt(
                                        down.position,
                                        canvas,
                                        activeWidget,
                                        touchedPoleIndex
                                    )
                                    onPoleTap(touchedPoleIndex, v)
                                } else if (triad && hub) {
                                    if (now - lastTapTime < 300L) {
                                        onToggleTriad()
                                        lastTapTime = 0L
                                    } else {
                                        lastTapTime = now
                                        if (activeWidget == WidgetType.W3_RINGS) onCentreTap()
                                    }
                                } else if (
                                    activeWidget == WidgetType.W1_MONO ||
                                    activeWidget == WidgetType.W1X2 ||
                                    activeWidget == WidgetType.W3_RINGS
                                ) {
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
        when (activeWidget) {
            WidgetType.W1_MONO -> W1MonoWidget(poleValues, focusedPoleIndex, strokePx)
            WidgetType.W1X2 -> W1X2Widget(poleValues, focusedPoleIndex, isFocusAdjusting, strokePx)
            WidgetType.W2_SWITCHBOX -> W2SwitchboxWidget(poleValues, focusedPoleIndex, strokePx)
            WidgetType.W3_RINGS -> W3RingsWidget(poleValues, focusedPoleIndex, isFocusAdjusting, strokePx)
            WidgetType.W3_SIMPLEX -> W3SimplexWidget(poleValues, focusedPoleIndex)
            WidgetType.W4_TETRAD -> W4TetradWidget(poleValues, focusedPoleIndex)
            WidgetType.W5_PENTAD -> W5PentadWidget(poleValues, focusedPoleIndex)
            WidgetType.W6_HEXAD -> W6HexadWidget(poleValues, focusedPoleIndex)
            WidgetType.W7_HEPTAD -> W7HeptadWidget(poleValues, focusedPoleIndex)
            WidgetType.W8_OCTAD -> W8OctadWidget(poleValues, focusedPoleIndex)
        }

        val showCenterTap = activeWidget == WidgetType.W1_MONO ||
                activeWidget == WidgetType.W1X2 ||
                activeWidget == WidgetType.W3_RINGS
        if (showCenterTap) {
            val density = LocalDensity.current
            val maxRadius = min(canvasSize.width, canvasSize.height) / 2f
            val innerDiameterPx = if (maxRadius <= 0f) {
                0f
            } else {
                val centers = WidgetGeometry.ringCenters(maxRadius, strokePx, activeWidget.poleCount)
                (centers.last() - strokePx / 2f).coerceAtLeast(0f) * 2f
            }
            val innerCircleDp = with(density) { innerDiameterPx.toDp() }

            Box(
                modifier = Modifier
                    .size(innerCircleDp)
                    .clip(CircleShape)
                    .background(centerFlash ?: Color.Transparent),
                contentAlignment = Alignment.Center
            ) {
            }
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


