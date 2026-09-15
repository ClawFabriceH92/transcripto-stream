package com.transcripto.stream.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewMarksTest {

    private fun found(text: String) = ReviewMarks.figureRanges(text).map { text.substring(it) }

    @Test
    fun doubtfulOnlyWhenConfidenceKnownAndLow() {
        assertTrue(ReviewMarks.isDoubtful(0.2f))
        assertTrue(ReviewMarks.isDoubtful(0.599f))
        assertFalse(ReviewMarks.isDoubtful(0.6f))
        assertFalse(ReviewMarks.isDoubtful(0.95f))
        assertFalse("inconnue : pas douteuse", ReviewMarks.isDoubtful(-1f))
    }

    @Test
    fun spotsAmountsPercentagesAndDates() {
        assertEquals(
            listOf("12 000 €", "3,5 %", "15 mars 2026", "T2 2026", "30/09", "2 M€"),
            found("Provision de 12 000 € soit 3,5 % du CA, à passer avant le 15 mars 2026 ou T2 2026 ; relevé du 30/09 ; plafond 2 M€."),
        )
        assertEquals(listOf("1er juillet", "exercice 2025"), found("Clôture au 1er juillet de l'exercice 2025."))
        assertTrue(found("Bonjour à tous, on commence par les stocks.").isEmpty())
        assertEquals(listOf("150 k€"), found("Le découvert atteint 150 k€"))
    }
}
