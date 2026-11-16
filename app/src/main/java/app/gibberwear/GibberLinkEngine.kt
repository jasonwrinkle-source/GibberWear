package app.gibberwear

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import br.com.chatnoir.ggwave_kotlin.GGWaveCodec
import br.com.chatnoir.ggwave_kotlin.GGWaveProtocolId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class GibberLinkEngine(
    private val context: Context,
    private val onMessageDecoded: (String) -> Unit
) {
    companion object {
        private const val TAG = "GibberLinkEngine"
        private const val SAMPLE_RATE = 48000f
    }

    private val codec: GGWaveCodec = GGWaveCodec.Builder()
        .sampleRate(SAMPLE_RATE)
        .protocolId(GGWaveProtocolId.ULTRASOUND_FAST)
        .volume(70) // Increased volume for web decoder
        .build()

    private var isListening = false
    private val coroutineScope = CoroutineScope(Dispatchers.Default)
    private var listenJob: Job? = null

    @SuppressLint("MissingPermission")
    fun startListening() {
        if (isListening) return
        isListening = true
        Log.i(TAG, "GibberLink listening started")
        
        listenJob = coroutineScope.launch {
            try {
                codec.startListeningFlow().collect { message ->
                    onMessageDecoded(message)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in listenAndDecode", e)
            }
        }
    }

    fun stopListening() {
        if (!isListening) return
        isListening = false
        codec.stopListeningFlow()
        listenJob?.cancel()
        Log.i(TAG, "GibberLink listening stopped")
    }

    fun sendChirp(payload: String) {
        if (payload.isEmpty()) {
            Log.w(TAG, "Ignoring empty payload to prevent GGWave crash")
            return
        }
        
        // GGWave has strict byte limits
        val payloadBytes = payload.toByteArray(Charsets.UTF_8)
        if (payloadBytes.size > 16) { // Typical limit for fast ultrasound
            Log.w(TAG, "Payload exceeds GGWave size limit (${payloadBytes.size} bytes), truncating or dropping: $payload")
            // Depending on requirements, truncate or drop
            return
        }

        Log.i(TAG, "Sending chirp: $payload")
        
        coroutineScope.launch {
            try {
                codec.encodeAndPlay(payload)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to encode/send chirp", e)
            }
        }
    }

    fun release() {
        stopListening()
    }
}
