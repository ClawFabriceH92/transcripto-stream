package com.transcripto.stream.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PcmResamplerTest {

    @Test
    fun downmix_averagesChannels() {
        // stéréo : L=-100, R=100 → 0 ; L=200, R=400 → 300
        val stereo = shortArrayOf(-100, 100, 200, 400)
        assertArrayEquals(shortArrayOf(0, 300), PcmResampler.downmixToMono(stereo, 2, 2))
    }

    @Test
    fun downmix_monoPassthrough() {
        val mono = shortArrayOf(1, 2, 3)
        assertArrayEquals(mono, PcmResampler.downmixToMono(mono, 3, 1))
    }

    @Test
    fun floats_convertAndClamp() {
        val out = PcmResampler.floatsToShorts(floatArrayOf(0f, 0.5f, 1.5f, -2f), 4)
        assertEquals(0, out[0].toInt())
        assertEquals(16384, out[1].toInt())
        assertEquals(32767, out[2].toInt())
        assertEquals(-32768, out[3].toInt())
    }

    @Test
    fun resampler_identityWhenSameRate() {
        val r = LinearResampler(16000, 16000)
        val input = shortArrayOf(5, -3, 8)
        assertArrayEquals(input, r.process(input, 3))
    }

    @Test
    fun resampler_downsamplesRampByThree() {
        // 48 k → 16 k sur une rampe : sortie ≈ rampe sous-échantillonnée ×3
        val r = LinearResampler(48000, 16000)
        val input = ShortArray(300) { it.toShort() }
        val out = r.process(input, input.size)
        assertEquals(100, out.size)
        for (i in out.indices) {
            assertEquals((i * 3).toShort(), out[i])
        }
    }

    @Test
    fun resampler_upsamplesInterpolating() {
        // 8 k → 16 k : les échantillons intermédiaires sont la moyenne des voisins
        val r = LinearResampler(8000, 16000)
        val out = r.process(shortArrayOf(0, 100, 200), 3)
        assertTrue(out.size >= 5)
        assertEquals(0, out[0].toInt())
        assertEquals(50, out[1].toInt())
        assertEquals(100, out[2].toInt())
        assertEquals(150, out[3].toInt())
        assertEquals(200, out[4].toInt())
    }

    @Test
    fun resampler_chunkedMatchesWhole() {
        // Découper le flux en blocs ne doit pas changer le résultat (interpolation
        // correcte à la frontière) — c'est le cas d'usage réel du décodeur.
        val input = ShortArray(1000) { ((it * 37) % 1201 - 600).toShort() }
        val whole = LinearResampler(44100, 16000).process(input, input.size)

        val chunked = LinearResampler(44100, 16000)
        val parts = mutableListOf<Short>()
        var offset = 0
        for (size in listOf(333, 250, 417)) {
            val chunk = input.copyOfRange(offset, offset + size)
            parts.addAll(chunked.process(chunk, chunk.size).toList())
            offset += size
        }
        // La toute fin du flux peut différer d'un échantillon (clamp de fin) : on
        // compare la partie commune.
        val n = minOf(whole.size, parts.size)
        assertTrue(n >= whole.size - 2)
        for (i in 0 until n) {
            assertTrue(
                "échantillon $i : ${whole[i]} vs ${parts[i]}",
                abs(whole[i] - parts[i]) <= 1,
            )
        }
    }
}
