package app.gibberwear

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import br.com.chatnoir.ggwave_kotlin.GGWaveCodec
import br.com.chatnoir.ggwave_kotlin.GGWaveProtocolId
import br.com.chatnoir.ggwave_kotlin.GGWaveSampleFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Ultrasound send and listen via GGWave protocol p=4 (ultrasound fast, 48 kHz).
 *
 * Playback uses a short-lived static AudioTrack so an ACK such as `ACK:eve:CFG`
 * is actually played, not only logged. The microphone is paused for the chirp
 * and started again afterwards.
 */
class GibberLinkEngine(
    @Suppress("unused") private val context: Context,
    private val onMessageDecoded: (String) -> Unit
) {
    companion object {
        private const val TAG = "GibberLinkEngine"
        private const val SAMPLE_RATE = 48000f

        /** Handshake brief: volume 10 is too quiet for the watch speaker. */
        private const val VOLUME = 70
    }

    private val codec: GGWaveCodec = GGWaveCodec.Builder()
        .sampleRate(SAMPLE_RATE)
        .sampleFormatInp(GGWaveSampleFormat.I16)
        .sampleFormatOut(GGWaveSampleFormat.I16)
        .protocolId(GGWaveProtocolId.ULTRASOUND_FAST)
        .volume(VOLUME)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val playbackMutex = Mutex()
    private var listenJob: Job? = null
    private var isListening = false

    @SuppressLint("MissingPermission")
    fun startListening() {
        if (isListening) return
        isListening = true
        Log.i(TAG, "GibberLink listening started")
        listenJob = scope.launch {
            try {
                codec.startListeningFlow().collect { message ->
                    if (message.isBlank()) return@collect
                    Log.i(TAG, "Decoded GibberLink ultrasound message: $message")
                    withContext(Dispatchers.Main) {
                        onMessageDecoded(message)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "GibberLink listen failed", e)
            } finally {
                isListening = false
            }
        }
    }

    fun stopListening() {
        if (!isListening && listenJob == null) return
        isListening = false
        codec.stopListeningFlow()
        listenJob?.cancel()
        listenJob = null
        Log.i(TAG, "GibberLink listening stopped")
    }

    fun sendChirp(payload: String) {
        // A NUL in the payload is what crashes native ggwave_encode. Strip it
        // and skip the call if nothing is left. Do not disable encode itself.
        val cleaned = payload.replace("\u0000", "")
        if (cleaned.length != payload.length) {
            Log.w(TAG, "Stripped NUL from ultrasound payload before encode")
        }
        if (cleaned.isEmpty()) {
            Log.w(TAG, "Not encoding ultrasound payload: empty after removing NUL")
            return
        }
        scope.launch {
            playbackMutex.withLock {
                val resume = isListening
                Log.d(TAG, "Encoding and playing ultrasound chirp: $cleaned")
                if (resume) stopListening()
                try {
                    playEncodedMessage(cleaned)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to play ultrasound chirp: $cleaned", e)
                } finally {
                    if (resume) startListening()
                }
            }
        }
    }

    private suspend fun playEncodedMessage(message: String) {
        codec.encode(message) { waveform, _ ->
            if (waveform.isEmpty()) {
                Log.e(TAG, "GGWave encode returned an empty waveform for: $message")
                return@encode
            }
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE.toInt())
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(waveform.size)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            try {
                val written = track.write(waveform, 0, waveform.size)
                if (written <= 0) {
                    Log.e(TAG, "AudioTrack write failed ($written) for: $message")
                    return@encode
                }
                track.play()
                val durationMs = (waveform.size * 1000L) / (2 * SAMPLE_RATE.toInt())
                delay(durationMs + 80L)
            } finally {
                try {
                    track.stop()
                } catch (_: Exception) {
                }
                track.release()
            }
        }
    }

    fun release() {
        stopListening()
        scope.coroutineContext[Job]?.cancel()
    }
}
