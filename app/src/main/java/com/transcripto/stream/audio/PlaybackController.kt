package com.transcripto.stream.audio

import android.media.MediaPlayer
import android.media.PlaybackParams
import com.transcripto.stream.data.CryptoManager
import com.transcripto.stream.data.RecordingNames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Lecture d'un enregistrement (écran principal ou fiche) : MediaPlayer, position
 * suivie pour le surlignage du segment courant, vitesse, déchiffrement temporaire
 * des .enc dans le cache (supprimé à l'arrêt). Appels depuis le thread principal.
 */
class PlaybackController(
    private val cacheDir: File,
    private val scope: CoroutineScope,
    /** Vitesse de lecture courante (réglage). */
    private val speed: () -> Float,
    /** Faux quand la lecture est interdite (pendant un enregistrement, le haut-parleur serait recapté). */
    private val canStart: () -> Boolean,
    private val onError: (String) -> Unit,
) {

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playingFile = MutableStateFlow<File?>(null)
    val playingFile: StateFlow<File?> = _playingFile.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null
    private var playbackFile: File? = null // temp déchiffré en cours de lecture
    private var playbackIsTemp = false
    private var positionJob: Job? = null
    private var startJob: Job? = null

    /** Lance/arrête la lecture de [file]. */
    fun toggle(file: File) {
        if (!RecordingNames.isAudio(file.name)) return // entrée texte seul : rien à écouter
        if (_isPlaying.value && _playingFile.value == file) {
            stop()
        } else {
            start(file, 0L)
        }
    }

    /** Tap sur un segment de la fiche : cale la lecture sur [positionMs]. */
    fun playFrom(file: File, positionMs: Long) {
        if (!RecordingNames.isAudio(file.name)) return
        if (_isPlaying.value && _playingFile.value == file) {
            seekTo(positionMs)
        } else {
            start(file, positionMs)
        }
    }

    /** Curseur de position de la fiche. */
    fun seekTo(positionMs: Long) {
        try {
            mediaPlayer?.seekTo(positionMs.toInt())
        } catch (_: Exception) {
        }
        _positionMs.value = positionMs
    }

    /** Applique une nouvelle vitesse à la lecture en cours (le réglage est lu au prochain démarrage). */
    fun setSpeed(value: Float) {
        try {
            mediaPlayer?.playbackParams = PlaybackParams().setSpeed(value)
        } catch (_: Exception) {}
    }

    private fun start(file: File, startMs: Long) {
        if (!canStart()) return
        stop()
        startJob = scope.launch {
            // Déchiffrement hors du thread UI : un .enc de réunion fait des dizaines de Mo.
            // NonCancellable : une annulation pendant l'IO rendrait sinon le résultat
            // impossible à récupérer — le temp ne serait jamais supprimé.
            val clear = withContext(Dispatchers.IO + NonCancellable) {
                if (file.extension != "enc") file else CryptoManager.decryptToTemp(file, cacheDir, "_pb")
            }
            if (clear == null) {
                onError("Déchiffrement impossible")
                return@launch
            }
            if (!isActive) { // lecture annulée pendant le déchiffrement
                if (clear != file) clear.delete()
                return@launch
            }
            val mp = MediaPlayer()
            try {
                mp.setDataSource(clear.absolutePath)
                mp.setOnCompletionListener {
                    finish(clear != file, clear)
                }
                mp.setOnErrorListener { _, _, _ ->
                    finish(clear != file, clear)
                    true
                }
                mp.prepare()
                mp.playbackParams = PlaybackParams().setSpeed(speed())
                if (startMs > 0) mp.seekTo(startMs.toInt())
                mp.start()
                mediaPlayer = mp
                playbackFile = clear
                playbackIsTemp = clear != file
                _playingFile.value = file
                _durationMs.value = try {
                    mp.duration.toLong()
                } catch (e: Exception) {
                    0L
                }
                _positionMs.value = startMs
                _isPlaying.value = true
                startPositionTicker()
            } catch (e: Exception) {
                if (mediaPlayer === mp) mediaPlayer = null
                try {
                    mp.release() // pas de player natif fuité à chaque échec
                } catch (_: Exception) {
                }
                if (clear != file) clear.delete()
                onError("Lecture impossible : ${e.message}")
            }
        }
    }

    /** Fin naturelle ou erreur du lecteur : libère sans repasser par stop(). */
    private fun finish(deleteTemp: Boolean, temp: File) {
        positionJob?.cancel()
        positionJob = null
        _isPlaying.value = false
        _playingFile.value = null
        mediaPlayer?.release()
        mediaPlayer = null
        playbackFile = null
        playbackIsTemp = false
        if (deleteTemp) temp.delete()
    }

    /** Suit la position de lecture (surlignage du segment courant sur la fiche). */
    private fun startPositionTicker() {
        positionJob?.cancel()
        positionJob = scope.launch {
            while (isActive && _isPlaying.value) {
                _positionMs.value = try {
                    mediaPlayer?.currentPosition?.toLong() ?: 0L
                } catch (e: Exception) {
                    0L
                }
                delay(300)
            }
        }
    }

    fun stop() {
        startJob?.cancel()
        startJob = null
        positionJob?.cancel()
        positionJob = null
        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {}
        mediaPlayer?.release()
        mediaPlayer = null
        if (playbackIsTemp) playbackFile?.delete()
        playbackFile = null
        playbackIsTemp = false
        _isPlaying.value = false
        _playingFile.value = null
        _positionMs.value = 0L
    }
}
