package com.transcripto.stream.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionsCsvTest {

    private fun ligne(
        action: String = "Relancer le client",
        responsable: String = "Fabrice",
        echeance: String = "30/09/2026",
        statut: String = "ouverte",
    ) = ActionsCsv.LigneAction(
        dossier = "Dupont SARL",
        enregistrement = "20260915_1000-1130",
        dateEnregistrement = "15/09/2026",
        action = action,
        responsable = responsable,
        echeance = echeance,
        statut = statut,
    )

    @Test
    fun `l'entete porte le separateur et les sept colonnes`() {
        val csv = ActionsCsv.construire(emptyList())

        assertTrue(csv.startsWith("sep=;\n"))
        val entete = csv.lines()[1]
        assertEquals(
            "Dossier;Enregistrement;Date de l'enregistrement;Action;Responsable;Échéance;Statut",
            entete,
        )
    }

    @Test
    fun `une ligne d'action reprend toutes les colonnes`() {
        val csv = ActionsCsv.construire(listOf(ligne()))

        val ligneCsv = csv.lines()[2]
        assertEquals(
            "Dupont SARL;20260915_1000-1130;15/09/2026;Relancer le client;Fabrice;30/09/2026;ouverte",
            ligneCsv,
        )
    }

    @Test
    fun `un point-virgule dans le texte est protege par des guillemets`() {
        val csv = ActionsCsv.construire(listOf(ligne(action = "Relancer ; puis clôturer")))

        assertTrue(csv.contains("\"Relancer ; puis clôturer\""))
    }

    @Test
    fun `un guillemet est double`() {
        assertEquals("\"il a dit \"\"oui\"\"\"", ActionsCsv.cellule("il a dit \"oui\""))
    }

    @Test
    fun `les sauts de ligne sont remplaces pour garder une ligne par action`() {
        val csv = ActionsCsv.construire(listOf(ligne(action = "Ligne 1\nLigne 2")))

        assertTrue(csv.contains("Ligne 1 Ligne 2"))
        assertEquals(3, csv.trimEnd().lines().size)
    }

    @Test
    fun `le fichier commence par le BOM UTF-8`() {
        val octets = ActionsCsv.octets(listOf(ligne()))

        assertEquals(0xEF.toByte(), octets[0])
        assertEquals(0xBB.toByte(), octets[1])
        assertEquals(0xBF.toByte(), octets[2])
        assertTrue(octets.size > 3)
    }

    @Test
    fun `la date d'enregistrement est au format francais`() {
        val reference = java.util.Calendar.getInstance(java.util.Locale.FRANCE).apply {
            clear()
            set(2026, java.util.Calendar.SEPTEMBER, 15, 10, 30)
        }.timeInMillis

        assertEquals("15/09/2026", ActionsCsv.dateLisible(reference))
    }
}
