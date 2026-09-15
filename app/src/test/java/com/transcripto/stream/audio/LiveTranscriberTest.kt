package com.transcripto.stream.audio

import com.transcripto.stream.stt.StreamResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class LiveTranscriberTest {

    private class Settings(
        override var language: String = "fr",
        override var vadSilero: Boolean = false,
        override var dictationMode: Boolean = false,
    ) : LiveSettings

    /** Moteur factice : renvoie les textes dans l'ordre et note la taille des fenêtres reçues. */
    private class FakeEngine(vararg texts: String) : WindowTranscriber {
        val script = texts.toMutableList()
        val windowsMs = mutableListOf<Long>()
        override suspend fun transcribe(pcm: ByteArray, language: String, prompt: String): StreamResult {
            windowsMs += pcm.size / 32L
            return StreamResult(if (script.isEmpty()) "" else script.removeAt(0), emptyList())
        }
    }

    /** VAD factice : « parole » si la trame est bruyante (amplitude), silence sinon ; peut lever. */
    private class FakeVad : FrameVad {
        var resets = 0
        var closed = false
        var fail = false
        override fun process(frame: ShortArray, offset: Int): Float {
            if (fail) throw IllegalStateException("ORT down")
            return if (frame[offset].toInt() != 0 || frame[offset + 1].toInt() != 0) 0.9f else 0.05f
        }
        override fun reset() { resets++ }
        override fun close() { closed = true }
    }

    private val errors = mutableListOf<String>()

    private fun transcriber(engine: WindowTranscriber, settings: Settings, vad: FakeVad? = null): LiveTranscriber {
        val t = LiveTranscriber(engine, Mutex(), settings, { vad }, CoroutineScope(Dispatchers.Unconfined)) { errors += it }
        if (vad != null) t.loadVad(Dispatchers.Unconfined)
        t.reset()
        return t
    }

    /** [ms] millisecondes de signal : sinus 200 Hz à amplitude 3000 (voix) ou silence. */
    private fun feed(t: LiveTranscriber, ms: Int, voice: Boolean) {
        val n = ms * 16
        val buf = ShortArray(n) { i -> if (voice) (3000 * sin(2 * PI * 200 * i / 16000.0)).toInt().toShort() else 0 }
        // Par paquets de 100 ms, comme le recorder
        var off = 0
        while (off < n) {
            val take = minOf(1600, n - off)
            t.feed(buf.copyOfRange(off, off + take), take)
            off += take
        }
    }

    @Test
    fun rmsBranchTranscribesLoudWindowsOnlyAndMergesOverlap() = runBlocking {
        val engine = FakeEngine("Bonjour à tous", "Bonjour à tous", "à tous mes amis")
        val t = transcriber(engine, Settings(vadSilero = false))
        assertFalse(t.neural)
        feed(t, 300, voice = true)
        assertFalse("moins de 500 ms de nouvel audio", t.tick("cac"))
        feed(t, 300, voice = true)
        assertTrue(t.tick("cac"))
        assertEquals("Bonjour à tous", t.liveText.value)
        assertEquals(1, t.windowCount.value)
        assertEquals(listOf(600L), engine.windowsMs)
        // La fenêtre suivante garde la parole en chevauchement : même texte renvoyé, rien n'est dupliqué
        feed(t, 1000, voice = false)
        assertTrue(t.tick("cac"))
        assertEquals("Bonjour à tous", t.liveText.value)
        assertEquals(2, t.windowCount.value)
        // Fenêtre de silence pur : rien n'est envoyé même avec assez d'audio neuf
        feed(t, 1000, voice = false)
        assertFalse(t.tick("cac"))
        assertEquals(2, engine.windowsMs.size)
        // Nouvelle fenêtre : le suffixe « à tous » est recoupé, pas dupliqué
        feed(t, 1000, voice = true)
        assertTrue(t.tick("cac"))
        assertEquals("Bonjour à tous mes amis", t.liveText.value)
        assertEquals("à tous mes amis", t.lastWindowText.value)
        assertEquals(listOf(600L, 1600L, 2000L), engine.windowsMs)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun neuralBranchFiresAtPhraseEndAndAfterLongSpeechButNeverOnOverlapAlone() = runBlocking {
        val engine = FakeEngine("Première phrase", "Deuxième phrase", "trois")
        val vad = FakeVad()
        val t = transcriber(engine, Settings(vadSilero = true), vad)
        assertTrue(t.neural)
        // Durées multiples de 32 ms (une trame VAD) : aucune trame à cheval parole/silence
        // 1 s de parole puis 608 ms de silence : fin de phrase détectée → fenêtre courte acceptée
        feed(t, 1024, voice = true)
        assertFalse("parole en cours, pas de fin de phrase", t.tick("p"))
        feed(t, 608, voice = false)
        assertTrue(t.tick("p"))
        assertEquals("Première phrase", t.liveText.value)
        assertEquals(1632L, engine.windowsMs[0])
        // Silence qui se prolonge : pas de nouvelle fin de phrase, rien n'est renvoyé
        feed(t, 608, voice = false)
        assertFalse(t.tick("p"))
        // Parole continue > 3 s : résultat partiel sans attendre la fin de phrase
        feed(t, 3200, voice = true)
        assertTrue(t.tick("p"))
        assertEquals("Première phrase Deuxième phrase", t.liveText.value)
        // La phrase se termine juste après ce prélèvement : la fenêtre ne contiendrait que
        // du chevauchement déjà transcrit (aucune trame de parole neuve) → rien n'est renvoyé
        feed(t, 608, voice = false)
        assertFalse("seul le chevauchement déjà transcrit", t.tick("p"))
        assertEquals(2, engine.windowsMs.size)
    }

    @Test
    fun vadFailureFallsBackToVolumeAndPauseResetsBuffers() = runBlocking {
        val engine = FakeEngine("a", "b", "c")
        val vad = FakeVad()
        val settings = Settings(vadSilero = true)
        val t = transcriber(engine, settings, vad)
        vad.fail = true
        feed(t, 700, voice = true)
        assertFalse(t.neural) // repli RMS après l'erreur d'inférence
        assertTrue(t.tick("p"))
        assertEquals("a", t.liveText.value)
        // Pause : rien ne part ; reprise : le tampon repart de zéro
        t.pause()
        assertTrue(t.paused)
        feed(t, 1000, voice = true)
        assertFalse(t.tick("p"))
        t.resume()
        assertFalse(t.paused)
        assertFalse("tampon vidé à la reprise", t.tick("p"))
        feed(t, 600, voice = true)
        assertTrue(t.tick("p"))
        assertEquals("a b", t.liveText.value)
        // Arrêt avec le réglage désactivé : la VAD est fermée
        settings.vadSilero = false
        t.stop()
        assertTrue(vad.closed)
    }

    @Test
    fun markersAndGoogleTextKeepMergingSane() = runBlocking {
        val engine = FakeEngine("nous validons le bilan", "le bilan est signé")
        val t = transcriber(engine, Settings(dictationMode = true))
        feed(t, 600, voice = true)
        assertTrue(t.tick(""))
        t.addMarker(65)
        assertEquals("nous validons le bilan [⭐01:05]", t.liveText.value)
        feed(t, 600, voice = true)
        assertTrue(t.tick(""))
        // Le marqueur n'empêche pas le recoupement du suffixe « le bilan »
        assertEquals("nous validons le bilan [⭐01:05] est signé", t.liveText.value)
        // Moteur Google : partiel affiché sans être retenu, final ajouté, dictée appliquée en sortie
        t.clearText()
        t.showPartial("bonjour virgule")
        assertEquals("bonjour,", t.liveText.value)
        t.commitFinal("bonjour virgule tout le monde point")
        t.commitFinal("bonjour virgule tout le monde point") // doublon ignoré
        assertEquals("bonjour, tout le monde.", t.liveText.value)
        t.showPartial("")
        assertEquals("bonjour, tout le monde.", t.liveText.value)
    }

    @Test
    fun engineErrorIsReportedAndCountersReset() = runBlocking {
        val engine = object : WindowTranscriber {
            override suspend fun transcribe(pcm: ByteArray, language: String, prompt: String) =
                StreamResult("", emptyList(), error = "whisper KO")
        }
        val t = transcriber(engine, Settings())
        feed(t, 600, voice = true)
        assertTrue(t.tick(""))
        assertEquals(listOf("whisper KO"), errors)
        assertEquals(0, t.windowCount.value)
        t.reset()
        assertEquals("", t.liveText.value)
        assertFalse(t.tick(""))
    }
}
