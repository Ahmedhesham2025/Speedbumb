package app.bumpbeeper

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Spoken warnings ("Strong bump ahead.", "3 bumps ahead."), using the phone's own text-to-speech
 * (works offline once the voice is installed). Like the beeps, it plays as navigation voice: through the car's
 * Bluetooth if connected, music ducks. [onUnavailable] is used if speech isn't ready (e.g. no voice installed).
 */
class Voice(
    ctx: Context,
    /** Speech became usable (true) or stopped working (false). May be called on any thread. */
    private val onSpeechChange: (Boolean) -> Unit = {},
    private val onUnavailable: () -> Unit,
) {
    private val app = ctx.applicationContext
    private val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build()
    @Volatile private var ready = false
    @Volatile private var broken = false
    /** Text-to-speech is set up and hasn't failed. */
    val speaks: Boolean get() = ready && !broken
    private var lang = ""
    private var n = 0
    /** Called when an utterance ends, spoken or failed (by utterance id). */
    private val ends = ConcurrentHashMap<String, () -> Unit>()
    private val tts: TextToSpeech = TextToSpeech(app) { status ->
        ready = status == TextToSpeech.SUCCESS
        onSpeechChange(speaks)
    }

    init {
        tts.setAudioAttributes(attrs)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { am.abandonAudioFocusRequest(focus); ended(utteranceId) }
            @Deprecated("Deprecated in Android") override fun onError(utteranceId: String?) { am.abandonAudioFocusRequest(focus); ended(utteranceId) }
        })
    }

    private fun ended(id: String?) { if (id != null) ends.remove(id)?.invoke() }

    /**
     * After the strong bump's three beeps: "Strong bump ahead." Silent if speech isn't available (the beeps played).
     * [onEnd] runs once it has been spoken, or at once if it can't be.
     */
    fun strongBump(onEnd: () -> Unit = {}) = say(Phrases.strongBump(Prefs.voiceLang(app)), {}, onEnd)

    /**
     * Announce a group of spots ahead ("3 bumps ahead."); [fallback] plays instead if speech isn't available.
     * [onEnd] runs once the line has been spoken or the fallback played.
     */
    fun cluster(c: HazardCluster, fallback: () -> Unit, onEnd: () -> Unit = {}) {
        val text = Phrases.cluster(Prefs.voiceLang(app), c.count, c.maxSeverity == Severity.STRONG)
        if (text == null) { fallback(); onEnd() } else say(text, fallback, onEnd)
    }

    /** After the speeding tone: "Speed limit 60." Silent if speech isn't available (the tone already played). */
    fun speedLimit(kmh: Int) = say(Phrases.speedLimit(Prefs.voiceLang(app), kmh)) {}

    private fun fail(fallback: () -> Unit) {
        if (!broken) { broken = true; onSpeechChange(false) }
        fallback()
    }

    fun say(text: String, fallback: () -> Unit = onUnavailable, onEnd: () -> Unit = {}) {
        val code = Prefs.voiceLang(app)
        if (!ready) { fallback(); onEnd(); return }
        if (code != lang) {
            val r = tts.setLanguage(if (code == "ar") Locale("ar", "EG") else Locale.US)
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                // Arabic voice not installed: fall back to English rather than staying silent.
                if (code == "ar") tts.setLanguage(Locale.US) else { fail(fallback); onEnd(); return }
            }
            lang = code
        }
        am.requestAudioFocus(focus)
        val id = "w${n++}"
        ends[id] = onEnd
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
            ends.remove(id)
            am.abandonAudioFocusRequest(focus)
            fail(fallback)
            onEnd()
        }
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
