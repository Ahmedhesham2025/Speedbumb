package app.bumpbeeper

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Plays the warning beeps. Uses the "navigation guidance" audio channel, like Google Maps voice:
 * it goes to the car's Bluetooth speakers if connected and briefly lowers any music.
 * "Loud beeps" switches to the alarm channel instead (phone speaker, alarm volume).
 */
class Beeper(private val ctx: Context) {
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())

    /** Speed bump warning: [count] short high beeps. */
    fun beep(count: Int) = play(tones(DoubleArray(count) { 1046.5 }, 140, 90, 0.9))

    /** Pothole warning: a falling two-tone "uh-oh", so you can tell it apart without looking. */
    fun pothole() = play(tones(doubleArrayOf(784.0, 523.3), 220, 70, 0.9))

    /** Warning for this spot: pothole sound for potholes, beeps for everything else. */
    fun warn(b: Bump, speedKmh: Double) {
        if (b.kind == BumpKind.POTHOLE) pothole() else beep(if (speedKmh >= 50) 3 else 2)
    }

    /** Soft tick, used (optionally) when a new bump is recorded. */
    fun click() = play(tones(doubleArrayOf(1568.0), 35, 0, 0.4))

    private fun tones(freqsHz: DoubleArray, onMs: Int, gapMs: Int, volume: Double): ShortArray {
        val count = freqsHz.size
        val on = SAMPLE_RATE * onMs / 1000
        val gap = SAMPLE_RATE * gapMs / 1000
        val fade = SAMPLE_RATE * 5 / 1000   // 5 ms fade in/out so it doesn't click
        val lead = SAMPLE_RATE * 250 / 1000 // 250 ms of silence first: car/Bluetooth audio often clips the start
        val out = ShortArray(lead + count * on + (count - 1) * gap + SAMPLE_RATE * 30 / 1000)
        var start = lead
        for (freqHz in freqsHz) {
            for (n in 0 until on) {
                val env = min(1.0, min(n, on - 1 - n).toDouble() / fade)
                val s = sin(2 * PI * freqHz * n / SAMPLE_RATE) * env * volume
                out[start + n] = (s * Short.MAX_VALUE).toInt().toShort()
            }
            start += on + gap
        }
        return out
    }

    private fun play(pcm: ShortArray) {
        try {
            val usage = if (Prefs.loud(ctx)) AudioAttributes.USAGE_ALARM
                        else AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
            val attrs = AudioAttributes.Builder()
                .setUsage(usage)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            track.write(pcm, 0, pcm.size)

            val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrs)
                .build()
            am.requestAudioFocus(focus)
            track.play()

            // Generous slack: Bluetooth output can take a few hundred ms to wake up before playing.
            val durationMs = pcm.size * 1000L / SAMPLE_RATE + 1000
            main.postDelayed({
                track.release()
                am.abandonAudioFocusRequest(focus)
            }, durationMs)
        } catch (e: Exception) {
            Log.w("BumpBeeper", "beep failed", e)
        }
    }

    companion object {
        private const val SAMPLE_RATE = 44_100
    }
}
