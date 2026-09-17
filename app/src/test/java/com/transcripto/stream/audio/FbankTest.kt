package com.transcripto.stream.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class FbankTest {

    /** Voix synthétique de 2 s (harmoniques 140 Hz + bruit), int16 LE, 16 kHz — référence calculée hors ligne (numpy). */
    private fun voiceA(): ShortArray {
        val bytes = javaClass.classLoader!!.getResourceAsStream("voice_a.pcm")!!.readBytes()
        val out = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out)
        return out
    }

    @Test
    fun matchesKaldiReferenceValues() {
        val pcm = voiceA()
        assertEquals(32_000, pcm.size)
        val fb = Fbank.compute(pcm)
        assertEquals(198, fb.size)
        assertEquals(80, fb[0].size)
        val tol = 2e-3f
        assertArrayEquals(floatArrayOf(13.5633f, 10.8736f, 17.1322f, 18.9089f, 19.1732f, 18.9608f, 16.9476f, 14.9033f), fb[0].copyOf(8), tol)
        assertArrayEquals(floatArrayOf(13.1176f, 10.4052f, 16.4714f, 18.2775f, 18.5384f, 18.3205f, 16.3081f, 14.1191f), fb[50].copyOf(8), tol)
        assertArrayEquals(floatArrayOf(10.4241f, 9.9302f, 16.3703f, 18.1516f, 18.4131f, 18.1984f, 16.2268f, 14.3157f), fb[197].copyOf(8), tol)
        val mean = fb.sumOf { row -> row.sum().toDouble() } / (fb.size * 80)
        assertEquals(16.4767, mean, 1e-3)
        assertEquals(36, fb[50].indices.maxByOrNull { fb[50][it] })
        // Normalisation par la moyenne : chaque bande a une moyenne nulle
        val cmn = Fbank.meanNormalize(fb)
        for (j in 0 until 80) assertEquals(0.0, cmn.sumOf { it[j].toDouble() } / cmn.size, 1e-4)
    }

    @Test
    fun handlesShortAndSilentInput() {
        assertEquals(0, Fbank.compute(ShortArray(300)).size)
        assertEquals(1, Fbank.compute(ShortArray(400)).size)
        assertEquals(0, Fbank.frameCount(399))
        assertEquals(2, Fbank.frameCount(560))
        // Silence : logarithme borné, aucune valeur infinie
        val silent = Fbank.compute(ShortArray(1600))
        assertTrue(silent.all { row -> row.all { it.isFinite() && it < 0f } })
        // Sous-passage : mêmes trames que le découpage préalable
        val pcm = voiceA()
        val sub = Fbank.compute(pcm, 1600, 4800)
        val ref = Fbank.compute(pcm.copyOfRange(1600, 4800))
        assertEquals(ref.size, sub.size)
        assertArrayEquals(ref[3], sub[3], 0f)
    }
}
