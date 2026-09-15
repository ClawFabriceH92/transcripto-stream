package com.transcripto.stream.export

import com.transcripto.stream.data.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class DocxExportTest {

    private val doc = ExportDocument(
        title = "Réunion de clôture <SARL Martin & Fils>",
        dateLabel = "12 sept. 2026 10:30",
        durationMs = 95_000L,
        dossier = "SARL Martin & Fils",
        missionLabel = "Clôture / révision",
        speakerNames = mapOf(1 to "M. Martin (DG)"),
        sha256 = "abc123",
        encrypted = true,
        summaryMarkdown = "# Synthèse — x\n_meta_\n\n## Points clés\n- Provision de **12 000 €** à passer.\n- Point *ouvert* sur les stocks.\n",
        segments = listOf(
            ExportSegment(1, 0L, 30_000L, "Bonjour à tous, on commence par les stocks."),
            ExportSegment(2, 30_000L, 60_000L, "L'inventaire a été rapproché & validé."),
            ExportSegment(1, 60_000L, 95_000L, "Parfait, on passe aux provisions.", confidence = 0.42f),
        ),
        chapters = listOf(Chapter(0L, "Ouverture"), Chapter(30_000L, "Stocks et provisions")),
        actions = listOf(
            ExportAction("Envoyer la convention signée", owner = "M. Martin (DG)", due = "avant le 30 septembre"),
            ExportAction("Relancer la banque", done = true),
        ),
        appVersion = "0.9.0",
        generatedLabel = "13 sept. 2026 09:00",
    )

    private fun texts(blocks: List<DocBlock>): List<String> = blocks.map { b ->
        when (b) {
            is DocBlock.Title -> "T:" + b.text
            is DocBlock.Subtitle -> "S:" + b.text
            is DocBlock.Heading -> "H${b.level}:" + b.text
            is DocBlock.Meta -> "M:" + b.label + "=" + b.value
            is DocBlock.Para -> (if (b.bullet) "B:" else "P:") + b.spans.joinToString("") { it.text }
            is DocBlock.Speaker -> "SP:" + b.label
            is DocBlock.Segment -> "SG:" + (b.clock ?: "-") + " " + b.text
            is DocBlock.Note -> "N:" + b.text
            DocBlock.PageBreak -> "PB"
        }
    }

    @Test
    fun composerBuildsCoverSummaryAndTranscript() {
        val t = texts(ExportComposer.compose(doc))
        assertEquals("T:Réunion de clôture <SARL Martin & Fils>", t[0])
        assertEquals("S:SARL Martin & Fils", t[1])
        assertTrue(t.contains("M:Durée=01:35"))
        assertTrue(t.contains("M:Type de mission=Clôture / révision"))
        assertTrue(t.contains("M:Intervenants=M. Martin (DG), Intervenant 2"))
        assertTrue(t.contains("M:Temps de parole=M. Martin (DG) 68 % · Intervenant 2 32 %"))
        assertTrue(t.contains("M:Empreinte SHA-256 (PCM)=abc123"))
        assertTrue(t.any { it.startsWith("N:Document généré par Transcripto Stream v0.9.0 le 13 sept. 2026 09:00") })
        assertTrue(t.contains("H1:Synthèse"))
        assertFalse(t.any { it.startsWith("H1:Synthèse — x") }) // titre de niveau 1 de la synthèse ignoré
        assertTrue(t.contains("H2:Points clés"))
        assertTrue(t.contains("B:Provision de 12 000 € à passer."))
        assertTrue(t.contains("H1:Actions à mener"))
        assertTrue(t.contains("M:Suivi=1 à faire sur 2"))
        assertTrue(t.contains("B:☐ Envoyer la convention signée — M. Martin (DG) · échéance avant le 30 septembre"))
        assertTrue(t.contains("B:☑ Relancer la banque — fait"))
        assertTrue("les actions précèdent la transcription", t.indexOf("H1:Actions à mener") < t.indexOf("PB"))
        assertTrue(t.contains("PB"))
        assertTrue(t.contains("H1:Transcription"))
        assertTrue(t.contains("SP:M. Martin (DG)"))
        assertTrue(t.contains("SG:00:00 Bonjour à tous, on commence par les stocks."))
        assertTrue(t.contains("M:Passages à vérifier=1 (confiance du moteur sous 60 %, marqués « (à vérifier) »)"))
        assertTrue(t.contains("SG:01:00 Parfait, on passe aux provisions. (à vérifier)"))
        assertTrue(t.contains("SP:Intervenant 2"))
        assertTrue(t.contains("M:Chapitres=2"))
        assertTrue(t.contains("H2:00:00 — Ouverture"))
        assertTrue(t.contains("H2:00:30 — Stocks et provisions"))
        // Un titre de chapitre précède l'étiquette d'intervenant du passage qui le suit
        assertTrue(t.indexOf("H2:00:30 — Stocks et provisions") < t.indexOf("SP:Intervenant 2"))
        // Les deux passages consécutifs de M. Martin ne répètent l'étiquette qu'au changement
        assertEquals(2, t.count { it == "SP:M. Martin (DG)" })
    }

    @Test
    fun composerFallsBackToTextWhenNoSegments() {
        val t = texts(ExportComposer.compose(doc.copy(segments = emptyList(), transcriptText = "[M. Martin (DG)] Bonjour.\n\nSuite.")))
        assertTrue(t.contains("P:[M. Martin (DG)] Bonjour."))
        assertTrue(t.contains("P:Suite."))
        assertFalse(t.any { it.startsWith("M:Temps de parole") })
        val empty = texts(ExportComposer.compose(doc.copy(segments = emptyList(), transcriptText = "", summaryMarkdown = null, actions = emptyList())))
        assertTrue(empty.contains("N:Pas de transcription pour cet enregistrement."))
        assertFalse(empty.contains("H1:Synthèse"))
        assertFalse(empty.contains("H1:Actions à mener"))
    }

    @Test
    fun dossierDocumentHasCoverTableOfContentsAndOneSectionPerRecording() {
        val second = doc.copy(title = "Point d'étape", dateLabel = "20 sept. 2026 14:00", durationMs = 30_000L, actions = listOf(ExportAction("Signer le PV", done = false)), speakerNames = mapOf(1 to "Mme Durand"))
        val d = DossierDocument(name = "SARL Martin & Fils", recordings = listOf(doc, second), includeTranscripts = false, appVersion = "0.12.0", generatedLabel = "21 sept. 2026")
        val t = texts(ExportComposer.composeDossier(d))
        assertEquals("T:SARL Martin & Fils", t[0])
        assertEquals("S:Dossier — 2 enregistrements", t[1])
        assertTrue(t.contains("M:Durée cumulée=02:05"))
        assertTrue(t.contains("M:Période=12 sept. 2026 10:30 → 20 sept. 2026 14:00"))
        assertTrue(t.contains("M:Actions=2 à faire sur 3"))
        assertTrue(t.contains("M:Intervenants=M. Martin (DG), Mme Durand"))
        assertTrue(t.contains("H1:Sommaire"))
        assertTrue(t.contains("B:1. Réunion de clôture <SARL Martin & Fils> — 12 sept. 2026 10:30 · 1 action à faire"))
        assertTrue(t.contains("B:2. Point d'étape — 20 sept. 2026 14:00 · 1 action à faire"))
        assertTrue(t.contains("H1:1. Réunion de clôture <SARL Martin & Fils>"))
        assertTrue(t.contains("H1:2. Point d'étape"))
        assertEquals(2, t.count { it == "PB" })
        assertEquals(2, t.count { it == "H2:Actions à mener" })
        assertEquals(2, t.count { it == "H2:Synthèse" })
        assertTrue(t.contains("H3:Points clés"))
        assertEquals(2, t.count { it.startsWith("H1:") && it[3].isDigit() })
        assertFalse("transcriptions exclues par défaut", t.any { it.startsWith("SG:") })
        assertFalse(t.contains("H2:Transcription"))
        val full = texts(ExportComposer.composeDossier(d.copy(includeTranscripts = true)))
        assertEquals(2, full.count { it == "H2:Transcription" })
        assertTrue(full.contains("SG:00:00 Bonjour à tous, on commence par les stocks."))
        assertTrue("chapitres relégués au niveau 3 dans un dossier", full.contains("H3:00:30 — Stocks et provisions"))
        val bytes = DocxWriter.write(ExportComposer.composeDossier(d), "Dossier " + d.name)
        assertEquals(0x50, bytes[0].toInt())
    }

    @Test
    fun docxPackageHasRequiredPartsAndWellFormedXml() {
        val bytes = DocxWriter.write(ExportComposer.compose(doc), doc.title)
        assertEquals(0x50, bytes[0].toInt()) // 'P' — signature zip
        assertEquals(0x4B, bytes[1].toInt()) // 'K'
        val parts = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            var e = zin.nextEntry
            while (e != null) {
                parts[e.name] = zin.readBytes().toString(Charsets.UTF_8)
                e = zin.nextEntry
            }
        }
        assertEquals("[Content_Types].xml", parts.keys.first())
        for (name in listOf("_rels/.rels", "word/document.xml", "word/styles.xml", "word/numbering.xml", "word/_rels/document.xml.rels", "docProps/core.xml")) {
            assertTrue(name, parts.containsKey(name))
        }
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        for ((name, xml) in parts) {
            val parsed = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
            assertNotNull(name, parsed.documentElement)
        }
        val document = parts.getValue("word/document.xml")
        assertTrue(document.contains("Réunion de clôture &lt;SARL Martin &amp; Fils&gt;"))
        assertTrue(document.contains("rapproché &amp; validé"))
        assertTrue(document.contains("<w:pStyle w:val=\"Heading1\"/>"))
        assertTrue(document.contains("<w:numId w:val=\"1\"/>"))
        assertTrue(document.contains("<w:b/></w:rPr><w:t xml:space=\"preserve\">12 000 €</w:t>"))
        assertTrue(document.contains("<w:br w:type=\"page\"/>"))
        assertTrue(document.contains("[00:30] "))
        assertTrue(parts.getValue("docProps/core.xml").contains("<dc:title>Réunion de clôture &lt;SARL Martin &amp; Fils&gt;</dc:title>"))
    }

    @Test
    fun escapeDropsControlCharacters() {
        assertEquals("a&amp;b &lt;c&gt; &quot;d&quot;\tok", DocxWriter.esc("a&b <c> \"d\"\u0000\u0007\tok"))
    }
}
