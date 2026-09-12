package com.transcripto.stream.summary

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSummarizerTest {

    private val meeting = """
        [Intervenant 1] [00:05] Bonjour à tous, nous ouvrons la réunion de clôture de l'exercice 2025 du dossier Martin.
        [00:12] Le chiffre d'affaires ressort à 1 250 000 € en hausse de 8 % par rapport à l'exercice précédent.
        [Intervenant 2] [00:40] Nous avons décidé de constituer une provision pour litige de 45 000 € sur le dossier prud'homal.
        [01:05] Il faut transmettre les justificatifs de stock avant le 15 octobre pour finaliser le contrôle.
        [⭐01:23] Attention, un écart de 12 000 € subsiste sur le rapprochement bancaire de décembre.
        [Intervenant 1] [01:50] Le commissaire aux comptes validera le rapport final lors de la prochaine réunion.
        [02:10] La trésorerie reste confortable et les délais de paiement clients sont stables.
        [02:30] On doit également préparer la convention réglementée pour l'assemblée générale de juin.
        [02:55] Merci à tous pour votre participation, la réunion est terminée.
        --- Temps de parole (estimation par la voix) ---
        Intervenant 1 : 01:40 (60 %)
        Intervenant 2 : 01:05 (40 %)
    """.trimIndent()

    private fun summary() = LocalSummarizer.summarize(
        SummaryInput(title = "Clôture Martin", transcript = meeting, durationMs = 175_000, dateLabel = "12 sept. 2026")
    )

    @Test
    fun hasHeaderAndKeyPoints() {
        val md = summary()
        assertTrue(md.startsWith("# Synthèse — Clôture Martin"))
        assertTrue(md.contains("## Points clés"))
        assertTrue(md.lines().count { it.startsWith("- ") } >= 3)
        assertTrue(md.contains("2 intervenants"))
    }

    @Test
    fun detectsDecisionsAndActions() {
        val md = summary()
        assertTrue(md.contains("## Décisions"))
        assertTrue(md.contains("provision pour litige"))
        assertTrue(md.contains("## Actions à mener"))
        assertTrue(md.contains("justificatifs de stock"))
    }

    @Test
    fun extractsFiguresAndDates() {
        val md = summary()
        assertTrue(md.contains("## Chiffres et dates cités"))
        assertTrue(md.contains("1 250 000 €"))
        assertTrue(md.contains("15 octobre"))
    }

    @Test
    fun listsMarkersAndSpeakingStats() {
        val md = summary()
        assertTrue(md.contains("## Moments marqués ⭐"))
        assertTrue(md.contains("**01:23**"))
        assertTrue(md.contains("rapprochement bancaire"))
        assertTrue(md.contains("## Répartition de la parole"))
        assertTrue(md.contains("Intervenant 1 : 01:40 (60 %)"))
    }

    @Test
    fun keywordsSkipStopwordsAndTimestamps() {
        val md = summary()
        val keywords = md.substringAfter("## Mots-clés\n").substringBefore("\n\n")
        assertFalse(keywords.split(" · ").any { it in setOf("le", "de", "les", "nous", "pour") })
        assertFalse(md.contains("[00:12]")) // horodatages retirés des puces
        assertFalse(md.contains("[Intervenant"))
    }

    @Test
    fun emptyTranscriptStaysGraceful() {
        val md = LocalSummarizer.summarize(SummaryInput("Vide", "", 0, "aujourd'hui"))
        assertTrue(md.contains("trop courte"))
        assertFalse(md.contains("## Points clés"))
    }

    @Test
    fun unpunctuatedSpeechIsChunked() {
        val words = (1..200).joinToString(" ") { "mot$it" }
        val md = LocalSummarizer.summarize(SummaryInput("Brut", words, 60_000, "date"))
        assertTrue(md.contains("## Points clés"))
        assertTrue(md.lines().count { it.startsWith("- ") } >= 3)
    }
}

class LocalSummarizerEdgeTest {
    @Test
    fun noEmptyKeyPointsHeading() {
        val text = "Nous avons décidé de valider le budget de formation. Il faut envoyer le devis signé avant vendredi."
        val md = LocalSummarizer.summarize(SummaryInput("Court", text, 20_000, "date"))
        assertFalse(md.contains("## Points clés\n\n"))
        assertTrue(md.contains("## Décisions") || md.contains("## Actions à mener"))
    }
}
