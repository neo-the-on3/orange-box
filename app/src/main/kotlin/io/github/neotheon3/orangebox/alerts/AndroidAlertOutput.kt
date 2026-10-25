package io.github.neotheon3.orangebox.alerts

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.speech.tts.TextToSpeech
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.neotheon3.orangebox.R
import io.github.neotheon3.orangebox.core.common.OrangeLog
import io.github.neotheon3.orangebox.core.driving.alerts.AlertOutput
import io.github.neotheon3.orangebox.core.model.AlertKind
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plays driving alerts (M8 #59) with Orange Box's own tones (synthesized by tools/gen_alert_sounds.py)
 * as navigation-guidance audio (mixed over music; no audio focus is taken). With voice on, it also speaks the
 * alert through the platform [TextToSpeech] with the phone's default engine (none is forced; no TTS SDK or
 * cloud voice), created only once voice is first used. Without an engine, voice stays silent.
 */
@Singleton
class AndroidAlertOutput @Inject constructor(@ApplicationContext private val context: Context) : AlertOutput {
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private val pool: SoundPool by lazy { SoundPool.Builder().setMaxStreams(2).setAudioAttributes(attributes).build() }
    private val sounds: Map<AlertKind, Int> by lazy {
        AlertKind.entries.associateWith { pool.load(context, soundOf(it), 1) }
    }

    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false

    override fun play(kind: AlertKind, sound: Boolean, voice: Boolean) {
        if (sound) sounds[kind]?.let { pool.play(it, 1f, 1f, 1, 0, 1f) }
        if (voice) speak(context.getString(textOf(kind)))
    }

    private fun speak(text: String) {
        val engine = tts ?: synchronized(this) {
            tts ?: TextToSpeech(context) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    tts?.setAudioAttributes(attributes)
                    tts?.language = Locale.getDefault()
                }
                OrangeLog.i(OrangeLog.Areas.APP, "alert_tts", "ready" to ttsReady)
            }.also { tts = it }
        }
        // The first alert after enabling voice only primes the engine; the tone has already played.
        if (ttsReady) engine.speak(text, TextToSpeech.QUEUE_ADD, null, "orangebox-alert")
    }

    companion object {
        fun soundOf(kind: AlertKind): Int = when (kind) {
            AlertKind.OVERSPEED -> R.raw.alert_overspeed
            AlertKind.IDLING -> R.raw.alert_idling
            AlertKind.HARSH_ACCEL, AlertKind.HARSH_BRAKE -> R.raw.alert_harsh
            AlertKind.MIL -> R.raw.alert_mil
        }

        fun textOf(kind: AlertKind): Int = when (kind) {
            AlertKind.OVERSPEED -> R.string.alert_voice_overspeed
            AlertKind.IDLING -> R.string.alert_voice_idling
            AlertKind.HARSH_ACCEL -> R.string.alert_voice_harsh_accel
            AlertKind.HARSH_BRAKE -> R.string.alert_voice_harsh_brake
            AlertKind.MIL -> R.string.alert_voice_mil
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AlertModule {
    @Binds abstract fun bindAlertOutput(impl: AndroidAlertOutput): AlertOutput
}
