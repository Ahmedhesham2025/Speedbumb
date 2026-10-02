package app.bumpbeeper

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Spoken pothole warnings ("Pothole on the right. Keep left."), using the phone's own text-to-speech
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
    private val tts: TextToSpeech = TextToSpeech(app) { status ->
        ready = status == TextToSpeech.SUCCESS
        onSpeechChange(speaks)
    }

    init {
        tts.setAudioAttributes(attrs)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { am.abandonAudioFocusRequest(focus) }
            @Deprecated("Deprecated in Android") override fun onError(utteranceId: String?) { am.abandonAudioFocusRequest(focus) }
        })
    }

    /** Warn about a pothole ahead, telling which side it is on and which way to keep. */
    fun pothole(side: Side) = say(Phrases.pothole(Prefs.voiceLang(app), side))

    /** Announce a group of spots ahead ("3 bumps ahead."); [fallback] plays instead if speech isn't available. */
    fun cluster(c: HazardCluster, fallback: () -> Unit) {
        val text = Phrases.cluster(Prefs.voiceLang(app), c.count, c.kind, c.harshSide)
        if (text == null) fallback() else say(text, fallback)
    }

    private fun fail(fallback: () -> Unit) {
        if (!broken) { broken = true; onSpeechChange(false) }
        fallback()
    }

    fun say(text: String, fallback: () -> Unit = onUnavailable) {
        val code = Prefs.voiceLang(app)
        if (!ready) { fallback(); return }
        if (code != lang) {
            val r = tts.setLanguage(if (code == "ar") Locale("ar", "EG") else Locale.US)
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                // Arabic voice not installed: fall back to English rather than staying silent.
                if (code == "ar") tts.setLanguage(Locale.US) else { fail(fallback); return }
            }
            lang = code
        }
        am.requestAudioFocus(focus)
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "w${n++}") != TextToSpeech.SUCCESS) {
            am.abandonAudioFocusRequest(focus)
            fail(fallback)
        }
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
