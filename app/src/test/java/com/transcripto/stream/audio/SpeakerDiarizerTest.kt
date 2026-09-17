package com.transcripto.stream.audio

import com.transcripto.stream.stt.SegmentData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerDiarizerTest {

    /**
     * Encodeur factice : l'empreinte d'un passage est un vecteur unitaire dont l'axe est la
     * valeur (constante) des échantillons du passage — deux passages de même « voix » sont
     * identiques, deux voix différentes orthogonales. Passages sous une seconde : null.
     */
    private val fake = VoiceEmbedder { pcm, start, end ->
        if (end - start < Fbank.SAMPLE_RATE) null
        else FloatArray(4).also { it[pcm[start].toInt().coerceIn(0, 3)] = 1f }
    }

    /** PCM 16 kHz où chaque passage (1..n) est rempli de la « voix » demandée. */
    private fun pcm(vararg voicesPerSecond: Int): ShortArray {
        val out = ShortArray(voicesPerSecond.size * Fbank.SAMPLE_RATE)
        voicesPerSecond.forEachIndexed { i, v -> out.fill(v.toShort(), i * Fbank.SAMPLE_RATE, (i + 1) * Fbank.SAMPLE_RATE) }
        return out
    }

    private fun seg(startS: Int, endS: Int, text: String = "x") = SegmentData(text, startS * 1000L, endS * 1000L)

    @Test
    fun assignsSpeakersByVoiceAndKeepsCentroids() {
        val audio = pcm(1, 1, 2, 1, 2, 2)
        val segments = listOf(seg(0, 2), seg(2, 3), seg(3, 4), seg(4, 6))
        val r = SpeakerDiarizer.assign(audio, segments, fake)
        assertEquals(listOf(1, 2, 1, 2), r.speakerIds)
        assertEquals(setOf(1, 2), r.voices.keys)
        assertEquals(1f, r.voices.getValue(1)[1], 1e-6f)
        assertEquals(1f, r.voices.getValue(2)[2], 1e-6f)
    }

    @Test
    fun shortAndBlankSegmentsFollowTheirNeighbour() {
        val audio = pcm(1, 1, 2, 2)
        // 0-2 s voix 1 ; 2-2,5 s trop court ; 2,5-4 s voix 2 mais vide de texte ; 2,5-4 voix 2
        val segments = listOf(seg(0, 2), SegmentData("court", 2000L, 2500L), SegmentData("  ", 2500L, 4000L), SegmentData("y", 2500L, 4000L))
        val r = SpeakerDiarizer.assign(audio, segments, fake)
        assertEquals(4, r.speakerIds.size)
        assertEquals(1, r.speakerIds[0])
        assertEquals(2, r.speakerIds[3])
        assertTrue(r.speakerIds[1] in 1..2)
        assertTrue(r.speakerIds[2] in 1..2)
        assertEquals(setOf(1, 2), r.voices.keys)
    }

    @Test
    fun expectedCountAndEmptyInput() {
        val audio = pcm(1, 2, 3)
        val segments = listOf(seg(0, 1), seg(1, 2), seg(2, 3))
        assertEquals(listOf(1, 1, 1), SpeakerDiarizer.assign(audio, segments, fake, expected = 1).speakerIds)
        assertEquals(listOf(1, 2, 3), SpeakerDiarizer.assign(audio, segments, fake).speakerIds)
        val empty = SpeakerDiarizer.assign(audio, emptyList(), fake)
        assertTrue(empty.speakerIds.isEmpty())
        assertTrue(empty.voices.isEmpty())
        // Passages tous trop courts : un seul intervenant, pas d'empreinte
        val r = SpeakerDiarizer.assign(audio, listOf(SegmentData("a", 0L, 500L), SegmentData("b", 500L, 900L)), fake)
        assertEquals(listOf(1, 1), r.speakerIds)
        assertTrue(r.voices.isEmpty())
        assertNull(fake.embed(audio, 0, 100))
    }

    @Test
    fun recognisesKnownVoicesOnceEachAboveTheThreshold() {
        val v1 = floatArrayOf(1f, 0f, 0f, 0f)
        val v2 = floatArrayOf(0f, 1f, 0f, 0f)
        val near1 = SpeakerClustering.centroid(listOf(v1, floatArrayOf(0.9f, 0.3f, 0f, 0f)))!!
        val known = mapOf("M. Martin" to near1, "Mme Durand" to v2, "Inconnu" to floatArrayOf(0f, 0f, 0f, 1f))
        val voices = mapOf(1 to v1, 2 to v2, 3 to floatArrayOf(0.7f, 0.7f, 0f, 0f))
        val found = SpeakerDiarizer.recognise(voices, known)
        assertEquals("M. Martin", found[1])
        assertEquals("Mme Durand", found[2])
        // L'intervenant 3 ressemble aux deux, mais chaque nom n'est attribué qu'une fois
        assertEquals(2, found.size)
        assertTrue(SpeakerDiarizer.recognise(emptyMap(), known).isEmpty())
        assertTrue(SpeakerDiarizer.recognise(voices, emptyMap()).isEmpty())
        // Sous le seuil : rien
        assertTrue(SpeakerDiarizer.recognise(mapOf(1 to floatArrayOf(0f, 0f, 1f, 0f)), known).isEmpty())
    }
}
