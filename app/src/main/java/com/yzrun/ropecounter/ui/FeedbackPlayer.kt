package com.yzrun.ropecounter.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.yzrun.ropecounter.R
import java.util.Collections

/** Plays short audio files bundled in res/raw; it never invokes TTS or a network service. */
class FeedbackPlayer(context: Context) {
    private val soundPool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .build()
    private val loadedSamples = Collections.synchronizedSet(mutableSetOf<Int>())
    private val countBeepSample: Int
    private val standSample: Int
    private val fullBodySample: Int
    private val threeSample: Int
    private val twoSample: Int
    private val oneSample: Int
    private val startSample: Int
    @Volatile private var pendingVoiceSample: Int? = null
    @Volatile private var activeVoiceStream = 0

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) {
                loadedSamples += sampleId
                if (pendingVoiceSample == sampleId) {
                    pendingVoiceSample = null
                    playVoice(sampleId)
                }
            }
        }
        countBeepSample = soundPool.load(context, R.raw.count_beep, 1)
        standSample = soundPool.load(context, R.raw.prompt_stand, 1)
        fullBodySample = soundPool.load(context, R.raw.prompt_full_body, 1)
        threeSample = soundPool.load(context, R.raw.countdown_three, 1)
        twoSample = soundPool.load(context, R.raw.countdown_two, 1)
        oneSample = soundPool.load(context, R.raw.countdown_one, 1)
        startSample = soundPool.load(context, R.raw.prompt_start, 1)
    }

    fun jump() {
        if (countBeepSample in loadedSamples) {
            soundPool.play(
                countBeepSample,
                MAX_PLAYBACK_VOLUME,
                MAX_PLAYBACK_VOLUME,
                COUNT_BEEP_PRIORITY,
                0,
                1f,
            )
        }
    }

    fun phaseChanged() {
        vibrator?.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    fun speakGuidance(message: String) {
        when (message) {
            "请站稳" -> playVoice(standSample)
            "请保持全身入镜" -> playVoice(fullBodySample)
        }
    }

    fun speakCountdown(seconds: Int) {
        val sample = when (seconds) {
            3 -> threeSample
            2 -> twoSample
            1 -> oneSample
            else -> return
        }
        playVoice(sample)
    }

    fun speakStart() {
        playVoice(startSample)
    }

    fun stopSpeech() {
        pendingVoiceSample = null
        if (activeVoiceStream != 0) {
            soundPool.stop(activeVoiceStream)
            activeVoiceStream = 0
        }
    }

    private fun playVoice(sampleId: Int) {
        if (sampleId !in loadedSamples) {
            pendingVoiceSample = sampleId
            return
        }
        stopSpeech()
        activeVoiceStream = soundPool.play(
            sampleId,
            MAX_PLAYBACK_VOLUME,
            MAX_PLAYBACK_VOLUME,
            VOICE_PRIORITY,
            0,
            1f,
        )
    }

    fun release() {
        stopSpeech()
        soundPool.release()
    }

    private companion object {
        const val MAX_PLAYBACK_VOLUME = 1f
        const val VOICE_PRIORITY = 1
        const val COUNT_BEEP_PRIORITY = 2
    }
}
