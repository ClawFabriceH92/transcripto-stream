package com.transcripto.stream.summary

import com.transcripto.stream.data.StoredSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterDetectorTest {

    /** Segments de 10 s répétant en boucle les phrases d'un thème pendant [minutes] minutes. */
    private fun topic(startMs: Long, minutes: Int, sentences: List<String>): List<StoredSegment> {
        val out = ArrayList<StoredSegment>()
        var t = startMs
        var k = 0
        while (t < startMs + minutes * 60_000L) {
            out += StoredSegment(t, t + 9_000, sentences[k % sentences.size], 1 + (k % 2))
            t += 10_000
            k++
        }
        return out
    }

    private val stocks = listOf(
        "Pour les stocks, l'inventaire physique a été rapproché de la valeur comptable.",
        "La dépréciation des stocks anciens doit être revue avec le responsable logistique.",
        "Les écarts d'inventaire sur les stocks restent faibles cette année.",
    )
    private val paie = listOf(
        "Sur la paie, les charges sociales du dernier trimestre sont provisionnées.",
        "Le contrôle des bulletins de paie montre une erreur sur les congés payés.",
        "La déclaration sociale nominative de la paie de décembre est à vérifier.",
    )
    private val tresorerie = listOf(
        "La trésorerie est tendue, le découvert bancaire dépasse l'autorisation.",
        "Le rapprochement bancaire fait apparaître des chèques non débités en trésorerie.",
        "Les prévisions de trésorerie du premier semestre seront revues avec la banque.",
    )

    @Test
    fun detectsTopicShiftsAndTitlesThem() {
        val segs = topic(0, 4, stocks) + topic(4 * 60_000L, 4, paie) + topic(8 * 60_000L, 4, tresorerie)
        val chapters = ChapterDetector.detect(segs)
        assertEquals(chapters.toString(), 3, chapters.size)
        assertEquals(0L, chapters[0].startMs)
        // Frontières à ±1 min des vrais changements de sujet
        assertTrue(chapters.toString(), kotlin.math.abs(chapters[1].startMs - 4 * 60_000L) <= 60_000L)
        assertTrue(chapters.toString(), kotlin.math.abs(chapters[2].startMs - 8 * 60_000L) <= 60_000L)
        assertTrue(chapters[0].title, chapters[0].title.contains("Stock", ignoreCase = true))
        assertTrue(chapters[1].title, chapters[1].title.contains("Paie", ignoreCase = true))
        assertTrue(chapters[2].title, chapters[2].title.contains("Trésorerie", ignoreCase = true))
    }

    @Test
    fun singleTopicOrShortRecordingYieldsNoChapters() {
        assertTrue(ChapterDetector.detect(topic(0, 12, stocks)).isEmpty())
        assertTrue(ChapterDetector.detect(topic(0, 3, stocks) + topic(3 * 60_000L, 0, paie)).isEmpty())
        assertTrue(ChapterDetector.detect(emptyList()).isEmpty())
        // Segments sans horodatage (texte seul) : pas de chapitres
        assertTrue(ChapterDetector.detect(List(40) { StoredSegment(-1, -1, stocks[it % 3], 1) }).isEmpty())
    }
}
