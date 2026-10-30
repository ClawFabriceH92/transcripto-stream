package com.transcripto.stream.summary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class OllamaSummarizerTest {

    private val input = SummaryInput(
        title = "Réunion budget",
        transcript = "Intervenant 1 : le budget 2027 est voté.\nIntervenant 2 : question sur les comptes.",
        durationMs = 65_000,
        dateLabel = "15 septembre 2026 à 10:00",
    )

    private class TransportFactice(
        private val reponse: String = "",
        private val erreur: Throwable? = null,
    ) : OllamaTransport {
        var derniereUrl: String? = null
        var dernierModele: String? = null
        var dernierPrompt: String? = null

        override fun generer(url: String, modele: String, prompt: String): String {
            derniereUrl = url
            dernierModele = modele
            dernierPrompt = prompt
            if (erreur != null) throw erreur
            return reponse
        }
    }

    @Test
    fun `le corps de requete est un JSON valide et echappe`() {
        val corps = OllamaSummarizer.corpsRequete("ministral-3:8b", "Ligne \"citée\"\nSuite")

        assertTrue(corps.contains("\"model\":\"ministral-3:8b\""))
        assertTrue(corps.contains("\\\"citée\\\""))
        assertTrue(corps.contains("\\nSuite"))
        assertTrue(corps.contains("\"stream\":false"))
    }

    @Test
    fun `la reponse non streamee est extraite et desechappee`() {
        val json = """{"model":"x","response":"Résumé\u00a0:\nligne 1","done":true}"""

        assertEquals("Résumé\u00a0:\nligne 1", OllamaSummarizer.extraireReponse(json))
    }

    @Test
    fun `adresse normalisee sans barre oblique finale`() {
        assertEquals("http://192.168.0.162:11434", OllamaSummarizer.normaliser(" http://192.168.0.162:11434/ "))
        assertEquals(OllamaSummarizer.URL_DEFAUT, OllamaSummarizer.normaliser(""))
    }

    @Test
    fun `une synthese reussie renvoie le markdown et le modele`() {
        val transport = TransportFactice("## Points clés\n- budget voté")

        val resultat = OllamaSummarizer.summarize(
            url = "http://192.168.0.162:11434",
            modele = "ministral-3:8b",
            input = input,
            transport = transport,
        )

        assertTrue(resultat is AiSummaryResult.Ok)
        val ok = resultat as AiSummaryResult.Ok
        assertEquals("ministral-3:8b", ok.model)
        assertTrue(ok.markdown.contains("budget voté"))
        assertTrue(transport.dernierPrompt!!.contains(input.transcript))
        assertTrue(transport.dernierPrompt!!.contains("cabinet d'expertise comptable"))
    }

    @Test
    fun `un serveur injoignable donne un echec explicite`() {
        val transport = TransportFactice(erreur = IOException("connexion refusée"))

        val resultat = OllamaSummarizer.summarize(
            url = "http://192.168.0.162:11434",
            modele = "ministral-3:8b",
            input = input,
            transport = transport,
        )

        assertTrue(resultat is AiSummaryResult.Failed)
        assertTrue((resultat as AiSummaryResult.Failed).message.contains("injoignable"))
    }

    @Test
    fun `une reponse vide est signalee`() {
        val resultat = OllamaSummarizer.summarize(
            url = "http://192.168.0.162:11434",
            modele = "ministral-3:8b",
            input = input,
            transport = TransportFactice("   "),
        )

        assertTrue(resultat is AiSummaryResult.Failed)
    }

    @Test
    fun `une transcription vide ne part pas au modele`() {
        val transport = TransportFactice("peu importe")

        val resultat = OllamaSummarizer.summarize(
            url = "http://192.168.0.162:11434",
            modele = "ministral-3:8b",
            input = input.copy(transcript = "  "),
            transport = transport,
        )

        assertTrue(resultat is AiSummaryResult.Failed)
        assertEquals(null, transport.dernierPrompt)
    }

    @Test
    fun `les actions ouvertes sont rappelees au modele`() {
        val transport = TransportFactice("ok")

        OllamaSummarizer.summarize(
            url = "http://192.168.0.162:11434",
            modele = "ministral-3:8b",
            input = input,
            transport = transport,
            openActions = listOf("Relancer le client (Fabrice), échéance 30/09 — reunion-1"),
        )

        assertTrue(transport.dernierPrompt!!.contains("Relancer le client"))
    }
}
