package app.gibberwear

import android.content.Context
import android.media.MediaPlayer
import androidx.annotation.RawRes
import java.util.concurrent.Executors

/**
 * Plays a pre-generated GGWave ultrasonic chirp stored in res/raw.
 * Releases any currently playing chirp first so rapid taps don't stack.
 * All MediaPlayer operations run on a single background thread to prevent
 * main thread freezes or UI lag.
 */
class ChirpPlayer(private val context: Context) {

    private var mediaPlayer: MediaPlayer? = null
    private val executor = Executors.newSingleThreadExecutor()

    fun play(@RawRes resId: Int) {
        executor.execute {
            try {
                mediaPlayer?.let { mp ->
                    try {
                        if (mp.isPlaying) mp.stop()
                    } catch (_: Exception) {}
                    mp.release()
                }
                mediaPlayer = null
                mediaPlayer = MediaPlayer.create(context, resId)?.apply {
                    setOnCompletionListener { mp ->
                        try {
                            mp.release()
                        } catch (_: Exception) {}
                        if (mediaPlayer == mp) mediaPlayer = null
                    }
                    start()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Play a metric-value chirp (v2): the pre-generated WAV for the metric
     * letter + quantised value, e.g. enjoyment 75 -> "E80" -> chirp_e80.
     * See MetricChirp for the format.
     */
    fun playMetric(metric: Char, value: Int) {
        play(MetricChirp.resId(metric, value))
    }

    fun release() {
        executor.execute {
            try {
                mediaPlayer?.release()
            } catch (_: Exception) {}
            mediaPlayer = null
        }
        executor.shutdown()
    }
}
