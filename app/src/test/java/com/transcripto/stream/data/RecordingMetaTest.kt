package com.transcripto.stream.data

import com.transcripto.stream.stt.SegmentData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun staleFlagRoundTripsAndCountsAsContent() {
        val stale = RecordingMeta(segmentsStale = true)
        assertFalse(stale.isEmpty)
        assertTrue(RecordingMeta().isEmpty)
        val json = MetaCodec.toJson(stale)
        assertTrue(json, json.contains("\"stale\":true"))
        assertTrue(MetaCodec.fromJson(json).segmentsStale)
        assertFalse(MetaCodec.toJson(RecordingMeta(dossier = "X")).contains("stale"))
        assertFalse(MetaCodec.fromJson(MetaCodec.toJson(RecordingMeta(dossier = "X"))).segmentsStale)
    }

    @Test
    fun voicesRoundTripWithFourDecimals() {
        val v1 = FloatArray(8) { (it - 3) / 7f }
        val v2 = FloatArray(8) { 0.123456f * it }
        val meta = RecordingMeta(speakers = mapOf(1 to "M. Martin"), voices = mapOf(1 to v1, 2 to v2))
        assertFalse(meta.isEmpty)
        val json = MetaCodec.toJson(meta)
        assertTrue(json, json.contains("\"voices\""))
        val back = MetaCodec.fromJson(json)
        assertEquals(setOf(1, 2), back.voices.keys)
        for (i in 0 until 8) {
            assertEquals(v1[i], back.voices.getValue(1)[i], 5e-5f)
            assertEquals(v2[i], back.voices.getValue(2)[i], 5e-5f)
        }
        assertTrue(back.voicesContentEquals(MetaCodec.fromJson(json).voices))
        assertFalse(back.voicesContentEquals(mapOf(1 to v1)))
        // Sans empreinte : pas de clé, et une empreinte vide est ignorée
        assertFalse(MetaCodec.toJson(RecordingMeta(dossier = "X")).contains("voices"))
        assertTrue(MetaCodec.fromJson(MetaCodec.toJson(RecordingMeta(voices = mapOf(1 to FloatArray(0))))).isEmpty)
        // Empreintes illisibles : ignorées sans perdre le reste
        val broken = MetaCodec.fromJson("""{"dossier":"D","voices":{"a":[1,2],"2":"x","3":[]}}""")
        assertEquals("D", broken.dossier)
        assertTrue(broken.voices.isEmpty())
    }

    @Test
    fun voicesCanBeSkippedAndEmptyArraysDoNotCount() {
        val meta = RecordingMeta(speakers = mapOf(1 to "A"), voices = mapOf(1 to floatArrayOf(0.5f, 0.5f)))
        val json = MetaCodec.toJson(meta)
        val light = MetaCodec.fromJson(json, withVoices = false)
        assertEquals(mapOf(1 to "A"), light.speakers)
        assertTrue(light.voices.isEmpty())
        assertEquals(1, MetaCodec.fromJson(json).voices.size)
        assertTrue(RecordingMeta(voices = mapOf(1 to FloatArray(0))).isEmpty)
    }

    private fun stored(startS: Int, endS: Int, speaker: Int) = StoredSegment(startS * 1000L, endS * 1000L, "x", speaker)
    private fun seg(startS: Int, endS: Int) = SegmentData("x", startS * 1000L, endS * 1000L)

    @Test
    fun remapFollowsSpeechNotNumbers() {
        // Ancienne numérotation : 1 parle 0-10 s, 2 parle 10-20 s ; nouvelle : inversée et redécoupée
        val old = listOf(stored(0, 10, 1), stored(10, 20, 2))
        val names = mapOf(1 to "M. Martin", 2 to "Mme Durand")
        val segments = listOf(seg(0, 4), seg(4, 10), seg(10, 15), seg(15, 20))
        val r = SpeakerNames.remap(old, names, segments, listOf(2, 2, 1, 1))
        assertEquals(mapOf(1 to "Mme Durand", 2 to "M. Martin"), r.names)
        assertTrue(r.dropped.isEmpty())
        // Numérotation identique : inchangé
        assertEquals(names, SpeakerNames.remap(old, names, segments, listOf(1, 1, 2, 2)).names)
    }

    @Test
    fun remapDropsAmbiguousNamesAndKeepsOneNamePerSpeaker() {
        // L'ancien 1 (nommé) est réparti 50/50 sur les nouveaux 1 et 2 : pas de successeur net
        val old = listOf(stored(0, 10, 1), stored(10, 20, 2))
        val names = mapOf(1 to "M. Martin", 2 to "Mme Durand")
        val r = SpeakerNames.remap(old, names, listOf(seg(0, 5), seg(5, 10), seg(10, 20)), listOf(1, 2, 3))
        assertEquals(mapOf(3 to "Mme Durand"), r.names)
        assertEquals(listOf("M. Martin"), r.dropped)
        // Deux anciens fusionnés dans un seul nouveau : le plus long recouvrement garde le nom, l'autre est abandonné
        val merged = SpeakerNames.remap(old, names, listOf(seg(0, 20)), listOf(1))
        assertEquals(mapOf(1 to "M. Martin"), merged.names)
        assertEquals(listOf("Mme Durand"), merged.dropped)
        // Sans anciens passages ou sans nouveaux : noms conservés tels quels
        assertEquals(names, SpeakerNames.remap(emptyList(), names, listOf(seg(0, 1)), listOf(1)).names)
        assertEquals(names, SpeakerNames.remap(old, names, emptyList(), emptyList()).names)
        assertTrue(SpeakerNames.remap(old, emptyMap(), listOf(seg(0, 1)), listOf(1)).names.isEmpty())
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
