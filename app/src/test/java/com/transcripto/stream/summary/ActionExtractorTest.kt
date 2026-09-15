package com.transcripto.stream.summary

import com.transcripto.stream.data.ActionItem
import com.transcripto.stream.data.MetaCodec
import com.transcripto.stream.data.RecordingMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale

class ActionExtractorTest {

    /** Référence : 15 septembre 2026, 10 h (heure locale). */
    private val ref = Calendar.getInstance(Locale.FRANCE).apply { clear(); set(2026, Calendar.SEPTEMBER, 15, 10, 0) }.timeInMillis

    private fun day(y: Int, m: Int, d: Int) = Calendar.getInstance(Locale.FRANCE).apply { clear(); set(y, m, d) }.timeInMillis

    @Test
    fun extractsStructuredBulletsFromTheActionsSection() {
        val md = """
            # Synthèse — Réunion
            ## Points clés
            - Il faut relancer le client (pas une action ici).
            ## Actions à mener
            - **M. Martin** — envoyer la convention signée — avant le 30 septembre
            - Mme Durand — préparer les pièces de la clôture — non précisé
            - relancer la banque pour les relevés — d'ici 2 semaines
            - Cabinet : vérifier la provision pour litige de **12 000 €**
            - Envoyer la convention signée — 30/09
            ## Points de vigilance
            - Rien
        """.trimIndent()
        val actions = ActionExtractor.extract(md, ref)
        assertEquals(actions.toString(), 4, actions.size)
        assertEquals("M. Martin", actions[0].owner)
        assertEquals("envoyer la convention signée", actions[0].text)
        assertEquals("avant le 30 septembre", actions[0].dueLabel)
        assertEquals(day(2026, Calendar.SEPTEMBER, 30), actions[0].dueAt)
        assertEquals("Mme Durand", actions[1].owner)
        assertEquals("préparer les pièces de la clôture", actions[1].text)
        assertEquals("", actions[1].dueLabel)
        assertEquals(0L, actions[1].dueAt)
        assertEquals("", actions[2].owner)
        assertEquals("relancer la banque pour les relevés", actions[2].text)
        assertEquals("d'ici 2 semaines", actions[2].dueLabel)
        assertEquals(day(2026, Calendar.SEPTEMBER, 29), actions[2].dueAt)
        assertEquals("Cabinet", actions[3].owner)
        assertEquals("vérifier la provision pour litige de 12 000 €", actions[3].text)
        assertTrue(actions.all { it.id.isNotBlank() && it.createdAt == ref && !it.done })
        // Doublon replié (accents/casse ignorés) écarté ; hors rubrique ignoré
        assertTrue(ActionExtractor.extract("## Décisions\n- Envoyer la convention", ref).isEmpty())
    }

    @Test
    fun recognisesFrenchDeadlines() {
        fun due(s: String) = ActionExtractor.findDue(s, ref)
        assertEquals(day(2027, Calendar.MARCH, 15), due("avant le 15 mars")!!.at) // mars déjà passé → année suivante
        assertEquals(day(2026, Calendar.OCTOBER, 1), due("pour le 1er octobre")!!.at)
        assertEquals(day(2026, Calendar.SEPTEMBER, 20), due("le 20 sept. au plus tard")!!.at)
        assertEquals(day(2026, Calendar.DECEMBER, 31), due("fin décembre")!!.at)
        assertEquals(day(2026, Calendar.JUNE, 30), due("fin juin 2026")!!.at)
        assertEquals(day(2026, Calendar.NOVEMBER, 15), due("mi-novembre")!!.at)
        assertEquals(day(2026, Calendar.JUNE, 30), due("T2 2026")!!.at)
        assertEquals("T4 2026", due("livrable T4 2026")!!.label)
        assertEquals(day(2026, Calendar.DECEMBER, 31), due("T4 2026")!!.at)
        assertEquals(day(2026, Calendar.OCTOBER, 5), due("le 05/10")!!.at)
        assertEquals(day(2027, Calendar.JANUARY, 12), due("12/01/27")!!.at)
        assertEquals(day(2026, Calendar.SEPTEMBER, 25), due("sous 10 jours")!!.at)
        assertEquals(day(2026, Calendar.OCTOBER, 15), due("dans 1 mois")!!.at)
        assertEquals(day(2026, Calendar.SEPTEMBER, 30), due("fin du mois")!!.at)
        assertEquals(day(2026, Calendar.SEPTEMBER, 18), due("fin de semaine")!!.at) // vendredi 18
        assertEquals(day(2026, Calendar.SEPTEMBER, 21), due("début de la semaine prochaine")!!.at)
        assertEquals(day(2026, Calendar.DECEMBER, 31), due("fin d'année")!!.at)
        assertNull(due("dès que possible"))
        assertNull(due("31/13"))
    }

    @Test
    fun mergeKeepsTrackedStateAndAppendsNewOnes() {
        val tracked = listOf(
            ActionItem("1", "Envoyer la convention signée", owner = "M. Martin", done = true),
            ActionItem("2", "Relancer la banque", dueLabel = "fin septembre"),
        )
        val extracted = listOf(
            ActionItem("x", "envoyer la convention signee"), // même action, repliée
            ActionItem("y", "Préparer les pièces"),
        )
        val merged = ActionExtractor.merge(tracked, extracted)
        assertEquals(listOf("1", "2", "y"), merged.map { it.id })
        assertTrue(merged[0].done)
        assertEquals("M. Martin", merged[0].owner)
        // Codec .meta
        val meta = RecordingMeta(actions = merged)
        val back = MetaCodec.fromJson(MetaCodec.toJson(meta))
        assertEquals(merged, back.actions)
        assertEquals(listOf("2", "y"), back.openActions.map { it.id })
        assertTrue(RecordingMeta().isEmpty)
        assertTrue(!meta.isEmpty)
    }
}
