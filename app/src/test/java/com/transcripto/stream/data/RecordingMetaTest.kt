package com.transcripto.stream.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RecordingMetaTest {

    @Test
    fun codecRoundTrip() {
        val meta = RecordingMeta(
            speakers = mapOf(1 to "M. Martin (DG)", 2 to "Mme Durand"),
            dossier = "SARL Martin",
            template = "cloture",
            chapters = listOf(Chapter(0L, "Introduction"), Chapter(125_000L, "Stocks · inventaire")),
        )
        val back = MetaCodec.fromJson(MetaCodec.toJson(meta))
        assertEquals(meta, back)
    }

    @Test
    fun invalidJsonYieldsEmptyMeta() {
        assertTrue(MetaCodec.fromJson("pas du json").isEmpty)
        assertTrue(MetaCodec.fromJson(null).isEmpty)
        assertTrue(MetaCodec.fromJson("").isEmpty)
    }

    @Test
    fun blankNamesAreDropped() {
        val back = MetaCodec.fromJson(MetaCodec.toJson(RecordingMeta(speakers = mapOf(1 to "  ", 2 to "Paul"))))
        assertEquals(mapOf(2 to "Paul"), back.speakers)
    }

    @Test
    fun speakerNamesAppliedToTagsAndStats() {
        val text = "[Intervenant 1] [00:05] Bonjour.\n[Intervenant 2] [00:10] Bonjour à vous.\n\n" +
            "--- Temps de parole (estimation par la voix) ---\nIntervenant 1 : 01:40 (60 %)\nIntervenant 2 : 01:05 (40 %)"
        val out = SpeakerNames.apply(text, mapOf(1 to "M. Martin"))
        assertTrue(out.contains("[M. Martin] [00:05] Bonjour."))
        assertTrue(out.contains("[Intervenant 2] [00:10]")) // sans nom : inchangé
        assertTrue(out.contains("M. Martin : 01:40 (60 %)"))
        assertTrue(out.contains("Intervenant 2 : 01:05 (40 %)"))
    }

    @Test
    fun unapplyRestoresGenericLabels() {
        val names = mapOf(1 to "M. Martin (DG)", 2 to "M. Martin", 3 to "A+B [x]")
        val raw = "[Intervenant 1] [00:05] Bonjour.\n[Intervenant 2] [00:10] Rebonjour.\n[Intervenant 3] Ok.\n\n" +
            "--- Temps de parole (estimation par la voix) ---\nIntervenant 1 : 01:40 (60 %)\nIntervenant 2 : 01:05 (40 %)"
        val shown = SpeakerNames.apply(raw, names)
        assertTrue(shown.contains("[M. Martin (DG)] [00:05]"))
        assertTrue(shown.contains("[A+B [x]] Ok."))
        assertEquals(raw, SpeakerNames.unapply(shown, names))
        // Texte modifié entre-temps : seuls les libellés sont ramenés aux génériques
        assertEquals(
            "[Intervenant 2] [00:10] Corrigé.",
            SpeakerNames.unapply("[M. Martin] [00:10] Corrigé.", names),
        )
        assertEquals("texte", SpeakerNames.unapply("texte", emptyMap()))
    }

    @Test
    fun labelFallsBackToGeneric() {
        assertEquals("Intervenant 3", SpeakerNames.label(3, mapOf(1 to "A")))
        assertEquals("A", SpeakerNames.label(1, mapOf(1 to "A")))
        assertEquals("texte", SpeakerNames.apply("texte", emptyMap()))
    }

    @Test
    fun metaSidecarNaming() {
        assertEquals("réunion", RecordingNames.baseName("réunion.meta"))
        assertEquals("réunion.meta", RecordingNames.metaSibling(File("/tmp/réunion.wav")).name)
        assertEquals("rapport.md", RecordingNames.baseName("rapport.md.wav")) // ancien nom conservé
    }
}
