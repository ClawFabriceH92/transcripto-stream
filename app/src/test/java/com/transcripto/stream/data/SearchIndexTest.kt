package com.transcripto.stream.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SearchIndexTest {

    private fun src(name: String, stamp: Long, modified: Long, dossier: String = "") = IndexSource(
        file = File("/tmp/$name.wav"),
        contentStamp = stamp,
        baseName = name,
        dossier = dossier,
        hasAudio = true,
        speakerNames = emptyMap(),
        modifiedAt = modified,
    )

    @Test
    fun foldIgnoresAccentsAndCase() {
        assertEquals("ecriture d'ete", TextFold.fold("Écriture d'Été"))
        assertEquals(listOf("provision", "12"), TextFold.terms("  Provision, « 12 » "))
        assertTrue(TextFold.terms("   ").isEmpty())
    }

    @Test
    fun syncReloadsOnlyChangedRecordingsAndDropsStale() {
        val index = SearchIndex()
        var loads = 0
        val loader: (File) -> List<StoredSegment> = { f ->
            loads++
            when (f.name) {
                "a.wav" -> listOf(StoredSegment(0, 5000, "Provision pour litige de 12 000 euros", 1), StoredSegment(5000, 9000, "Passons aux stocks", 2))
                "b.wav" -> listOf(StoredSegment(0, 3000, "La provision est validée", 1))
                else -> emptyList()
            }
        }
        assertEquals(2, index.sync(listOf(src("a", 1, 100), src("b", 1, 200)), loader))
        assertEquals(2, loads)
        // Rien n'a changé : aucune relecture
        assertEquals(0, index.sync(listOf(src("a", 1, 100), src("b", 1, 200)), loader))
        assertEquals(2, loads)
        // Le dossier change sans le texte : pas de relecture, mais les résultats portent le nouveau dossier
        index.sync(listOf(src("a", 1, 100, dossier = "SARL X"), src("b", 1, 200)), loader)
        assertEquals(2, loads)
        assertEquals("SARL X", index.search("stocks").single().dossier)
        // Le contenu de a change : relecture ; b disparaît
        assertEquals(1, index.sync(listOf(src("a", 2, 100)), loader))
        assertEquals(1, index.size)
    }

    @Test
    fun searchRequiresAllTermsAndOrdersRecentFirst() {
        val index = SearchIndex()
        index.sync(listOf(src("ancien", 1, 100), src("recent", 1, 900))) { f ->
            if (f.name == "ancien.wav") {
                listOf(StoredSegment(60_000, 65_000, "Une provision de 12 000 € à comptabiliser", 1))
            } else {
                listOf(
                    StoredSegment(0, 1000, "Bonjour", 1),
                    StoredSegment(1000, 4000, "Il faut passer la PROVISION avant la clôture", 2),
                )
            }
        }
        val hits = index.search("provision")
        assertEquals(listOf("recent", "ancien"), hits.map { it.baseName })
        assertEquals(1, hits[0].index)
        assertEquals(1000L, hits[0].startMs)
        assertEquals(2, hits[0].speaker)
        assertEquals(1, index.search("provision 12").size) // les deux termes exigés
        assertEquals(2, index.search("provisión").size) // accents ignorés
        assertTrue(index.search("inexistant").isEmpty())
        assertEquals(setOf("/tmp/recent.wav", "/tmp/ancien.wav"), index.matchingFiles("provision").map { File(it).path }.toSet())
    }

    @Test
    fun segmentsFromTextParsesSpeakerAndClock() {
        val body = "[Intervenant 1] [00:05] Bonjour à tous.\n[Mme Durand] [01:02:03] Reprise.\nSans étiquette.\n\n--- Temps de parole ---\nIntervenant 1 : 00:10 (50 %)"
        val segs = SearchIndex.segmentsFromText(body)
        assertEquals(4, segs.size)
        assertEquals(StoredSegment(5000, 5000, "Bonjour à tous.", 1), segs[0])
        assertEquals(StoredSegment(3_723_000, 3_723_000, "Reprise.", 0), segs[1])
        assertEquals(StoredSegment(-1, -1, "Sans étiquette.", 0), segs[2])
        assertEquals("Intervenant 1 : 00:10 (50 %)", segs[3].text)
    }
}
