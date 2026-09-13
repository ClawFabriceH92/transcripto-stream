package com.transcripto.stream.summary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryTemplatesTest {

    private fun input(text: String) = SummaryInput("Test", text, 60_000L, "1 janv. 2026")

    @Test
    fun idsAreUniqueAndByIdFallsBack() {
        val ids = SummaryTemplates.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(SummaryTemplates.REUNION, SummaryTemplates.byId(null))
        assertEquals(SummaryTemplates.REUNION, SummaryTemplates.byId("inconnu"))
        assertEquals(SummaryTemplates.CLOTURE, SummaryTemplates.byId("cloture"))
        SummaryTemplates.ALL.forEach { t ->
            assertTrue(t.label.isNotBlank() && t.description.isNotBlank() && t.aiTask.contains("## "))
        }
    }

    @Test
    fun clotureTemplateRoutesSentencesToItsSections() {
        val text = "[Intervenant 1] [00:05] Il manque encore la facture du fournisseur principal pour le mois de décembre.\n" +
            "[Intervenant 2] [00:12] Nous devons passer une écriture de provision pour le litige prud'homal de 12 000 euros.\n" +
            "[Intervenant 1] [00:20] Sur le cycle stocks, l'inventaire physique a été rapproché de la balance sans écart.\n" +
            "[Intervenant 2] [00:30] Le tableau d'amortissement de l'emprunt est à récupérer auprès de la banque avant vendredi."
        val out = LocalSummarizer.summarize(input(text), SummaryTemplates.CLOTURE)
        assertTrue(out, out.contains("## Documents à obtenir"))
        assertTrue(out, out.contains("## Ajustements proposés") || out.contains("## Points de révision"))
        assertTrue(out, out.contains("Clôture / révision"))
        assertFalse(out, out.contains("## Décisions\n- Il manque"))
    }

    @Test
    fun defaultTemplateKeepsHistoricalHeadings() {
        val text = "Nous avons décidé de valider le budget de 50 000 euros pour le premier trimestre 2026. " +
            "Il faudra envoyer la convention signée au client avant le 15 mars. " +
            "Attention au risque de retard sur la livraison du module comptable. " +
            "Le point suivant concerne le recrutement d'un assistant comptable pour l'agence de Lyon."
        val out = LocalSummarizer.summarize(input(text))
        assertTrue(out, out.contains("## Décisions"))
        assertTrue(out, out.contains("## Actions à mener"))
        assertTrue(out, out.contains("## Points de vigilance"))
        assertFalse(out, out.contains("· Réunion ·")) // gabarit par défaut : pas de mention
    }

    @Test
    fun dictationTemplateProducesCleanTextNotBullets() {
        val text = "[Intervenant 1] [00:02] Madame, monsieur, nous accusons réception de votre courrier du 3 mars 2026. " +
            "nous vous confirmons que la liasse fiscale sera déposée avant le 15 mai\n" +
            "--- Temps de parole (estimation par la voix) ---\nIntervenant 1 : 00:40 (100 %)"
        val out = LocalSummarizer.summarize(input(text), SummaryTemplates.DICTEE)
        assertTrue(out, out.contains("## Texte"))
        assertTrue(out, out.contains("Madame, monsieur, nous accusons réception"))
        assertTrue(out, out.contains("Nous vous confirmons que la liasse fiscale sera déposée avant le 15 mai."))
        assertFalse(out, out.contains("## Points clés"))
        assertFalse(out, out.contains("[Intervenant 1]"))
        assertFalse(out, out.contains("Temps de parole"))
        assertTrue(out, out.contains("## Chiffres et dates à vérifier"))
    }

    @Test
    fun aiSystemPromptEmbedsTemplateTask() {
        val p = ClaudeSummarizer.systemPrompt(SummaryTemplates.AG)
        assertTrue(p.contains("## Résolutions et votes"))
        assertTrue(p.contains("Règles :"))
        assertTrue(p.contains("cabinet d'expertise comptable"))
        assertTrue(p.indexOf("## Résolutions") < p.indexOf("Règles :"))
    }
}
