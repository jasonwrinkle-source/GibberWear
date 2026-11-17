package app.gibberwear

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Speaks recognized gestures on debug installs. A missing speech engine is skipped.
 * Release builds stay quiet.
 */
class GestureSpeaker(context: Context) {
    private val appContext = context.applicationContext
    private val enabled =
        (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false

    fun start() {
        if (!enabled || tts != null) return
        tts = TextToSpeech(appContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.language = Locale.US
                Log.i(TAG, "Debug gesture speech ready")
            } else {
                Log.w(TAG, "Debug gesture speech unavailable")
            }
        }
    }

    fun say(phrase: String) {
        if (!enabled) return
        mainHandler.post {
            if (!ready) return@post
            tts?.speak(phrase, TextToSpeech.QUEUE_FLUSH, null, "gesture")
        }
    }

    fun stop() {
        ready = false
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    companion object {
        private const val TAG = "GestureSpeaker"
    }
}
