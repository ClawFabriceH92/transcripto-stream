package com.transcripto.stream.audio

import android.util.Log
import com.transcripto.stream.stt.StreamResult
import com.transcripto.stream.stt.VoiceCommands
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

/** Moteur de transcription d'une fenêtre PCM : whisper.cpp en production, factice en test. */
fun interface WindowTranscriber {
    suspend fun transcribe(pcm: ByteArray, language: String, prompt: String): StreamResult
}

/** Réglages relus à chaque tic par la boucle (un changement en cours d'enregistrement est suivi). */
interface LiveSettings {
    val language: String
    val vadSilero: Boolean
    val dictationMode: Boolean
}

/**
 * Transcription en direct : ring buffer alimenté par le thread de capture, VAD
 * neuronale (fin de phrase) ou seuil de volume, prélèvement des fenêtres avec
 * chevauchement, boucle de transcription et fusion du texte validé (recoupement
 * de suffixe), marqueurs « [⭐ mm:ss] », texte partiel/final du moteur Google.
 *
 * Aucune dépendance Android hors journalisation : testable en JVM avec un moteur
 * et une VAD factices ([tick] joue une itération de la boucle sans attendre).
 */
class LiveTranscriber(
    private val engine: WindowTranscriber,
    /** Verrou du contexte whisper.cpp, partagé avec la transcription différée et le chargement du modèle. */
    private val lock: Mutex,
    private val settings: LiveSettings,
    /** Crée la VAD neuronale (null si indisponible) ; appelée hors du thread principal. */
    private val vadFactory: () -> FrameVad?,
    private val scope: CoroutineScope,
    private val onError: (String) -> Unit,
) {

    companion object {
        private const val TAG = "LiveTranscriber"
        const val SAMPLE_RATE = 16000
        private const val WINDOW_SECONDS = 4
        private const val OVERLAP_SECONDS = 1
        const val TICK_MS = 1000L
        /** Cadence de vérification quand la VAD neuronale est active. */
        const val VAD_TICK_MS = 200L
        /** Parole continue au-delà de cette durée : transcription partielle sans attendre la fin de phrase. */
        const val LONG_PHRASE_MS = 3000L
        /** Nouvel audio minimal à la fin d'une phrase (au-delà du chevauchement déjà transcrit). */
        const val MIN_ENDED_MS = 250L
        /** Minimum de nouvel audio pour transcrire. */
        const val MIN_NEW_MS = 500L
        /** RMS int16 : silence ~<50, parole >300 — seuil volontairement bas pour ne rien perdre. */
        const val VAD_THRESHOLD = 120.0
    }

    private val _liveText = MutableStateFlow("")
    /** Texte en direct (commandes de dictée appliquées), tel qu'affiché et sauvegardé. */
    val liveText: StateFlow<String> = _liveText.asStateFlow()

    private val _windowCount = MutableStateFlow(0)
    val windowCount: StateFlow<Int> = _windowCount.asStateFlow()

    private val _lastWindowText = MutableStateFlow("")
    val lastWindowText: StateFlow<String> = _lastWindowText.asStateFlow()

    /** Texte validé BRUT (mots exacts du moteur) : base du recoupement entre fenêtres. */
    private var validatedText = ""

    @Volatile
    private var transcribing = false

    @Volatile
    var paused = false
        private set

    private var loopJob: Job? = null

    // ---- Ring buffer (WINDOW_SECONDS + marge) ----
    private val ringSize = SAMPLE_RATE * (WINDOW_SECONDS + OVERLAP_SECONDS)
    private val ring = ShortArray(ringSize)
    private var writePos = 0
    private var filled = false
    private var windowStart = 0
    /** Position d'écriture au dernier prélèvement (-1 : aucun) : distingue l'audio neuf du chevauchement. */
    private var lastSnapshotEnd = -1

    // ---- VAD neuronale : alimentée depuis le thread de capture ----
    @Volatile
    private var vad: FrameVad? = null
    private val speechGate = SpeechGate()
    private val vadFrame = ShortArray(FrameVad.FRAME)
    private var vadFrameFill = 0
    /** Une phrase vient de se terminer : transcrire sans attendre. */
    @Volatile
    private var phraseEnded = false
    /** Trames de parole vues depuis le dernier prélèvement de fenêtre (thread audio ↔ boucle). */
    private val speechFramesSinceSnapshot = AtomicInteger(0)
    private val vadLoading = AtomicBoolean(false)
    /** Faux dès qu'une erreur d'inférence survient pendant un enregistrement (repli RMS). */
    @Volatile
    private var vadHealthy = true

    /** VAD neuronale prête et utilisée (réglage actif, modèle chargé, aucune erreur). */
    val neural: Boolean
        get() = settings.vadSilero && vad != null && vadHealthy

    // ---- Cycle de vie ----

    /** Début d'enregistrement : texte, tampons et VAD remis à zéro. */
    fun reset() {
        clearText()
        paused = false
        transcribing = false
        resetRing()
        resetVadState()
        vadHealthy = true
    }

    /** Efface le texte en direct (nouvel enregistrement, import). */
    fun clearText() {
        validatedText = ""
        _liveText.value = ""
        _windowCount.value = 0
        _lastWindowText.value = ""
    }

    /** Lance la boucle de transcription ; [prompt] = vocabulaire personnalisé (figé pour la session). */
    fun start(prompt: String) {
        loopJob?.cancel()
        loopJob = scope.launch {
            while (isActive) {
                // VAD neuronale : réaction rapide à la fin d'une phrase ; sinon tic d'une seconde
                delay(if (neural) VAD_TICK_MS else TICK_MS)
                tick(prompt)
            }
        }
    }

    /** Arrête la boucle ; ferme la VAD si le réglage a été désactivé pendant l'enregistrement. */
    fun stop() {
        loopJob?.cancel()
        loopJob = null
        if (!settings.vadSilero) {
            vad?.close()
            vad = null
        }
    }

    fun pause() {
        paused = true
    }

    /** Reprise : l'audio capté pendant la pause n'existe pas, on repart d'un tampon vide. */
    fun resume() {
        paused = false
        resetRing()
        resetVadState()
    }

    /** Libère la VAD (fin de vie du ViewModel). */
    fun close() {
        stop()
        vad?.close()
        vad = null
    }

    // ---- Boucle ----

    /**
     * Une itération de la boucle : décide s'il y a une fenêtre à transcrire et la
     * transcrit. Retourne true si une fenêtre a été envoyée au moteur.
     */
    suspend fun tick(prompt: String): Boolean {
        if (transcribing || paused) return false
        if (neural) {
            // On transcrit quand une phrase vient de se terminer, ou quand la parole
            // continue depuis longtemps (résultat partiel) — sans prélever avant
            val ended = phraseEnded
            if (!ended && pendingNewMs() < LONG_PHRASE_MS) return false
            phraseEnded = false
            val (pcm, newMs) = snapshotNewAudio()
            val speechFrames = speechFramesSinceSnapshot.getAndSet(0)
            // Fin de phrase : on accepte une fenêtre courte, mais pas le seul chevauchement déjà transcrit
            if (newMs < (if (ended) MIN_ENDED_MS else MIN_NEW_MS)) return false
            // Aucune trame de parole dans la fenêtre : bruit ou silence, on n'envoie rien
            if (speechFrames == 0) return false
            runWindow(pcm, prompt)
            return true
        }
        // Pas de prélèvement tant qu'il n'y a pas assez d'audio neuf : un prélèvement
        // avance le curseur et ferait passer cet audio pour du chevauchement déjà vu
        if (pendingNewMs() < MIN_NEW_MS) return false
        val (pcm, _) = snapshotNewAudio()
        // VAD par volume : ignore les fenêtres de silence (coupe les blancs, moins d'hallucinations)
        if (rmsOf(pcm) < VAD_THRESHOLD) return false
        runWindow(pcm, prompt)
        return true
    }

    /** Transcrit une fenêtre audio et fusionne le résultat dans le texte en direct. */
    private suspend fun runWindow(pcm: ByteArray, prompt: String) {
        transcribing = true
        val res = try {
            lock.withLock { engine.transcribe(pcm, settings.language, prompt) }
        } finally {
            transcribing = false
        }
        if (res.error != null) {
            onError(res.error)
        } else {
            _windowCount.value++
            _lastWindowText.value = res.fullText.trim()
            if (res.fullText.isNotBlank()) mergeLive(res.fullText)
        }
    }

    private fun mergeLive(full: String) {
        val cleaned = full.trim()
        // Les marqueurs [⭐ mm:ss] ne viennent pas de Whisper : on les exclut du
        // suffixe de recoupement, sinon plus rien ne matche et le texte se duplique.
        val words = validatedText.split(" ").filter { it.isNotBlank() && !it.contains("⭐") }
        var matched = 0
        val maxMatch = minOf(words.size, 8)
        for (n in maxMatch downTo 1) {
            val suffix = words.takeLast(n).joinToString(" ")
            if (suffix.isNotBlank() && cleaned.startsWith(suffix)) {
                matched = suffix.length
                break
            }
        }
        val extra = if (matched > 0) cleaned.substring(matched).trim() else cleaned
        if (extra.isNotEmpty()) {
            // validatedText reste BRUT : le recoupement ci-dessus compare aux mots
            // exacts sortis de Whisper — stocker du texte transformé par la dictée
            // casserait le suffixe et dupliquerait le texte à chaque fenêtre.
            validatedText = (validatedText.trim() + " " + extra).trim()
            _liveText.value = displayText()
        }
    }

    /** Texte affiché/sauvegardé : les commandes de dictée ne s'appliquent qu'en sortie. */
    private fun displayText(raw: String = validatedText): String =
        if (settings.dictationMode) VoiceCommands.apply(raw) else raw

    // ---- Texte : marqueurs et moteur Google ----

    /**
     * Marqueur pendant l'enregistrement : insère « [⭐ mm:ss] » dans le texte en direct
     * pour retrouver un moment clé (décision, chiffre cité, point d'audit) à la relecture.
     */
    fun addMarker(elapsedSec: Long) {
        // Un seul token (pas d'espace interne) : le recoupement de fenêtres Whisper
        // filtre les mots contenant ⭐ — un espace couperait le marqueur en deux.
        val marker = "[⭐%02d:%02d]".format(elapsedSec / 60, elapsedSec % 60)
        validatedText = (validatedText.trim() + " " + marker).trim()
        _liveText.value = displayText()
    }

    /** Résultat partiel du moteur Google : affiché à la suite du texte validé, sans être retenu. */
    fun showPartial(partial: String) {
        _liveText.value = displayText((validatedText + " " + partial).trim())
    }

    /** Résultat final du moteur Google : ajouté au texte validé. */
    fun commitFinal(final: String) {
        if (final.isNotEmpty() && final != validatedText) {
            validatedText = (validatedText + " " + final).trim()
        }
        _liveText.value = displayText()
    }

    // ---- Capture ----

    /** Thread de capture : alimente le ring buffer et la VAD. */
    fun feed(buf: ShortArray, n: Int) {
        appendSamples(buf, n)
        feedVad(buf, n)
    }

    private fun appendSamples(buf: ShortArray, n: Int) {
        synchronized(ring) {
            var i = 0
            while (i < n) {
                ring[writePos] = buf[i]
                writePos = (writePos + 1) % ring.size
                if (writePos == 0) filled = true
                i++
            }
        }
    }

    private fun resetRing() {
        synchronized(ring) {
            writePos = 0
            filled = false
            windowStart = 0
            lastSnapshotEnd = -1
        }
    }

    /** Audio NEUF depuis le dernier prélèvement (hors chevauchement), sans le prélever (ms). */
    private fun pendingNewMs(): Long {
        synchronized(ring) {
            return (freshSamples() * 1000L) / SAMPLE_RATE
        }
    }

    /** Échantillons écrits depuis le dernier prélèvement (tout le tampon s'il n'y en a pas eu). */
    private fun freshSamples(): Int {
        val end = writePos
        if (lastSnapshotEnd < 0) {
            val len = (end - windowStart + ring.size) % ring.size
            return if (len == 0 && filled) ring.size else len
        }
        var fresh = (end - lastSnapshotEnd + ring.size) % ring.size
        if (fresh == 0 && filled && end != lastSnapshotEnd) fresh = ring.size
        return fresh.coerceAtMost(ring.size)
    }

    private fun snapshotNewAudio(): Pair<ByteArray, Long> {
        synchronized(ring) {
            val end = writePos
            val start = windowStart
            var len = (end - start + ring.size) % ring.size
            if (len == 0 && filled) len = ring.size
            if (len == 0) return ByteArray(0) to 0L

            val out = ShortArray(len)
            for (i in 0 until len) {
                out[i] = ring[(start + i) % ring.size]
            }
            val bytes = ByteArray(len * 2)
            for (i in 0 until len) {
                val v = out[i].toInt()
                bytes[2 * i] = (v and 0xFF).toByte()
                bytes[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
            }
            // Durée renvoyée = audio neuf (hors chevauchement déjà transcrit)
            val newMs = (freshSamples().coerceAtMost(len) * 1000L) / SAMPLE_RATE
            val overlap = SAMPLE_RATE * OVERLAP_SECONDS
            windowStart = if (!filled) {
                maxOf(0, end - overlap)
            } else {
                (end - overlap + ring.size) % ring.size
            }
            lastSnapshotEnd = end
            return bytes to newMs
        }
    }

    /** RMS d'un buffer PCM int16 — VAD par volume. */
    private fun rmsOf(pcm: ByteArray): Double {
        if (pcm.size < 2) return 0.0
        var sum = 0.0
        var n = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val v = ((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)).toShort().toDouble()
            sum += v * v
            n++
            i += 2
        }
        if (n == 0) return 0.0
        return sqrt(sum / n)
    }

    // ---- VAD neuronale ----

    /**
     * Découpe le flux en trames de 512 échantillons pour la VAD neuronale et met à jour
     * la machine à états (thread de capture). Une erreur d'inférence désactive la VAD
     * jusqu'au prochain enregistrement : la boucle repasse au seuil de volume.
     */
    private fun feedVad(buf: ShortArray, n: Int) {
        val v = vad ?: return
        if (!vadHealthy || !settings.vadSilero) return
        var i = 0
        try {
            while (i < n) {
                val take = minOf(n - i, FrameVad.FRAME - vadFrameFill)
                System.arraycopy(buf, i, vadFrame, vadFrameFill, take)
                vadFrameFill += take
                i += take
                if (vadFrameFill == FrameVad.FRAME) {
                    vadFrameFill = 0
                    val p = v.process(vadFrame)
                    if (p >= 0.5f) speechFramesSinceSnapshot.incrementAndGet()
                    if (speechGate.feed(p, FrameVad.FRAME_MS) == SpeechGate.Event.SPEECH_END) phraseEnded = true
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "VAD : ${t.message} — repli sur le seuil de volume")
            vadHealthy = false
        }
    }

    /** Charge la VAD hors du thread principal ; sans effet si elle est déjà chargée ou en cours. */
    fun loadVad(dispatcher: CoroutineDispatcher = Dispatchers.IO) {
        if (vad != null || !vadLoading.compareAndSet(false, true)) return
        scope.launch(dispatcher) {
            try {
                val v = vadFactory()
                if (v != null && vad == null && settings.vadSilero) vad = v else v?.close()
            } finally {
                vadLoading.set(false)
            }
        }
    }

    /** État de la VAD remis à zéro (début d'enregistrement, reprise après pause). */
    private fun resetVadState() {
        speechGate.reset()
        vadFrameFill = 0
        phraseEnded = false
        speechFramesSinceSnapshot.set(0)
        vad?.reset()
    }

    /**
     * Réglage « VAD neuronale » : charge le modèle, ou le ferme s'il n'y a pas
     * d'enregistrement en cours (pendant un enregistrement, la boucle et [feed]
     * ignorent déjà la VAD — réglage relu à chaque tic — et [stop] la fermera).
     */
    fun setVadEnabled(enabled: Boolean, recording: Boolean) {
        if (enabled) {
            loadVad()
        } else if (!recording) {
            vad?.close()
            vad = null
        }
    }
}
