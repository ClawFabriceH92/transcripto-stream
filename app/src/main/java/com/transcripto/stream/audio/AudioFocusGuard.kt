package com.transcripto.stream.audio

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log

/**
 * Focus audio pendant un enregistrement Whisper (micro interrompu par un appel ou
 * une autre application → pause, reprise à la fin) et écoute silencieuse (coupe
 * les bips du SpeechRecognizer Google, ne rétablit que les flux réellement coupés).
 */
class AudioFocusGuard(
    private val context: Context,
    /** Perte du focus ; [transient] = une reprise (AUDIOFOCUS_GAIN) suivra. */
    private val onLoss: (transient: Boolean) -> Unit,
    private val onGain: () -> Unit,
) {

    companion object {
        private const val TAG = "AudioFocusGuard"
    }

    private var request: AudioFocusRequest? = null
    private val mutedStreams = mutableListOf<Int>()

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            // Perte DÉFINITIVE : aucun AUDIOFOCUS_GAIN ne suivra — on ne promet
            // pas une reprise automatique qui n'arrivera jamais.
            AudioManager.AUDIOFOCUS_LOSS -> onLoss(false)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> onLoss(true)
            // CAN_DUCK (bip de notification…) : les autres apps baissent le volume,
            // le micro n'est pas préempté — on continue d'enregistrer sans coupure.
            AudioManager.AUDIOFOCUS_GAIN -> onGain()
        }
    }

    private val audioManager: AudioManager?
        get() = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    fun request() {
        val am = audioManager ?: return
        try {
            val r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setOnAudioFocusChangeListener(listener)
                .build()
            am.requestAudioFocus(r)
            request = r
        } catch (e: Exception) {
            Log.e(TAG, "requestAudioFocus : ${e.message}")
        }
    }

    fun abandon() {
        val r = request ?: return
        request = null
        try {
            audioManager?.abandonAudioFocusRequest(r)
        } catch (e: Exception) {
            Log.e(TAG, "abandonAudioFocus : ${e.message}")
        }
    }

    fun muteSystemSounds() {
        if (mutedStreams.isNotEmpty()) return
        val am = audioManager ?: return
        for (stream in intArrayOf(
            AudioManager.STREAM_MUSIC,
            AudioManager.STREAM_NOTIFICATION,
            AudioManager.STREAM_SYSTEM,
        )) {
            try {
                am.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0)
                mutedStreams.add(stream)
            } catch (e: Exception) {
                // NOTIFICATION/SYSTEM peuvent exiger l'accès « Ne pas déranger » :
                // on coupe ce qu'on peut, sans planter
            }
        }
    }

    fun unmuteSystemSounds() {
        if (mutedStreams.isEmpty()) return
        val am = audioManager
        if (am != null) {
            for (stream in mutedStreams) {
                try {
                    am.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
                } catch (e: Exception) {
                    Log.e(TAG, "unmute stream $stream : ${e.message}")
                }
            }
        }
        mutedStreams.clear()
    }
}
