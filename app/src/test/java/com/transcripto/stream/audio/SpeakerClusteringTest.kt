package com.transcripto.stream.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SpeakerClusteringTest {

    /** Empreinte autour d'un centre unitaire, avec un peu de bruit. */
    private fun around(center: FloatArray, rnd: Random, noise: Float = 0.15f) =
        FloatArray(center.size) { center[it] + (rnd.nextFloat() - 0.5f) * 2 * noise }

    private val a = floatArrayOf(1f, 0f, 0f, 0f)
    private val b = floatArrayOf(0f, 1f, 0f, 0f)
    private val c = floatArrayOf(0f, 0f, 1f, 0f)

    @Test
    fun autoModeFindsTheSpeakersAndNumbersThemByAppearance() {
        val rnd = Random(3)
        val embs = listOf(around(b, rnd), around(b, rnd), around(a, rnd), around(b, rnd), around(a, rnd), around(c, rnd), around(c, rnd))
        assertEquals(listOf(1, 1, 2, 1, 2, 3, 3), SpeakerClustering.cluster(embs))
        // Un seul locuteur : tout le monde à 1
        assertEquals(listOf(1, 1, 1), SpeakerClustering.cluster(listOf(around(a, rnd), around(a, rnd), around(a, rnd))))
    }

    @Test
    fun expectedCountForcesTheNumberOfGroups() {
        val rnd = Random(5)
        val embs = listOf(around(a, rnd), around(b, rnd), around(c, rnd), around(a, rnd))
        assertEquals(listOf(1, 2, 3, 1), SpeakerClustering.cluster(embs, expected = 3))
        val two = SpeakerClustering.cluster(embs, expected = 2)
        assertEquals(2, two.toSet().size)
        assertEquals(two[0], two[3])
        assertEquals(listOf(1, 1, 1, 1), SpeakerClustering.cluster(embs, expected = 1))
        // Jamais plus de six groupes en automatique
        val many = List(9) { i -> FloatArray(9) { if (it == i) 1f else 0f } }
        assertTrue(SpeakerClustering.cluster(many).toSet().size <= SpeakerClustering.MAX_SPEAKERS)
    }

    @Test
    fun shortPassagesFollowTheirNeighbour() {
        val rnd = Random(9)
        val embs = listOf(null, around(a, rnd), null, around(b, rnd), null)
        assertEquals(listOf(1, 1, 1, 2, 2), SpeakerClustering.cluster(embs))
        assertEquals(listOf(1, 1), SpeakerClustering.cluster(listOf(null, null)))
        assertTrue(SpeakerClustering.cluster(emptyList()).isEmpty())
        assertEquals(1f, SpeakerClustering.cosine(a, a), 1e-6f)
        assertEquals(0f, SpeakerClustering.cosine(a, b), 1e-6f)
        val centroid = SpeakerClustering.centroid(listOf(floatArrayOf(2f, 0f), floatArrayOf(0f, 2f)))!!
        assertEquals(0.7071f, centroid[0], 1e-3f)
    }
}
