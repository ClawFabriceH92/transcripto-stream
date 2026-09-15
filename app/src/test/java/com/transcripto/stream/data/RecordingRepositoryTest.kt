package com.transcripto.stream.data

import com.transcripto.stream.stt.SegmentData
import com.transcripto.stream.stt.StreamResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator

class RecordingRepositoryTest {

    private lateinit var dir: File
    private lateinit var repo: RecordingRepository
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("recrepo").toFile()
        repo = RecordingRepository(dir)
        TextVault.keyProvider = KeyProvider { key }
        TextVault.enabled = false
    }

    @After
    fun tearDown() {
        TextVault.enabled = false
        dir.deleteRecursively()
    }

    /** WAV PCM 16 kHz mono : en-tête de 44 octets + [dataBytes] octets de données. */
    private fun wav(name: String, dataBytes: Int = 3200, modified: Long? = null): File {
        val f = File(dir, name)
        val header = ByteArray(44)
        "RIFF".toByteArray().copyInto(header, 0)
        "WAVEfmt ".toByteArray().copyInto(header, 8)
        "data".toByteArray().copyInto(header, 36)
        header[40] = (dataBytes and 0xFF).toByte()
        header[41] = ((dataBytes shr 8) and 0xFF).toByte()
        header[42] = ((dataBytes shr 16) and 0xFF).toByte()
        header[43] = ((dataBytes shr 24) and 0xFF).toByte()
        f.writeBytes(header + ByteArray(dataBytes))
        if (modified != null) f.setLastModified(modified)
        return f
    }

    private fun text(name: String, content: String, modified: Long? = null): File =
        File(dir, name).apply {
            writeText(content)
            if (modified != null) setLastModified(modified)
        }

    private fun names() = dir.list()!!.sorted()

    @Test
    fun renameMovesEverySiblingAndRefusesCollisions() {
        val a = wav("a.wav")
        text("a.txt", "Transcripto Stream\n----\n\nBonjour")
        text("a.srt", "1\n")
        text("a.json", "{\"segments\":[]}")
        text("a.md", "# Synthèse")
        text("a.meta", "{\"dossier\":\"X\"}")
        text("c.txt", "Transcripto Stream\n----\n\nSeul")
        assertEquals(RenameResult.Unchanged, repo.rename(a, "  "))
        assertEquals(RenameResult.Unchanged, repo.rename(a, "a"))
        // « c » existe déjà comme entrée texte seul : collision tous types confondus
        assertEquals(RenameResult.Clash, repo.rename(a, "c"))
        val r = repo.rename(a, "b/1:2")
        assertTrue(r is RenameResult.Renamed)
        assertEquals(File(dir, "b12.wav"), (r as RenameResult.Renamed).dest)
        assertEquals(listOf("b12.json", "b12.md", "b12.meta", "b12.srt", "b12.txt", "b12.wav", "c.txt"), names())
        assertEquals("X", repo.readMeta(r.dest).dossier)
        // Le suffixe complet est conservé pour un WAV chiffré
        val enc = File(dir, "e.wav.enc").apply { writeBytes(ByteArray(100)) }
        val r2 = repo.rename(enc, "f") as RenameResult.Renamed
        assertEquals("f.wav.enc", r2.dest.name)
    }

    @Test
    fun listSkipsLegacyTxtAndOrdersRecentFirst() {
        val now = System.currentTimeMillis()
        val old = File(dir, "old.wav.enc").apply { writeBytes(ByteArray(12 + 16 + 44 + 32_000)); setLastModified(now - 3_000_000) }
        text("old.wav.txt", "Transcripto Stream\n----\n\n[Intervenant 1] Ancien format") // convention v0.2.x
        wav("new.wav", modified = now - 1_000_000)
        text("new.txt", "Transcripto Stream\n----\n\nRécent", modified = now - 1_000_000)
        text("note.txt", "Transcripto Stream\nDurée : 01:05\n----\n\nTexte seul", modified = now - 2_000_000)
        text("new.meta", "{\"speakers\":{\"1\":\"Mme Durand\"},\"dossier\":\"SARL X\"}")
        val listing = repo.list()
        assertEquals(listOf("new", "note", "old"), listing.items.map { it.baseName })
        val new = listing.items[0]
        assertTrue(new.hasAudio)
        assertFalse(new.encrypted)
        assertEquals("SARL X", new.dossier)
        assertEquals(mapOf(1 to "Mme Durand"), new.speakerNames)
        assertEquals(100L, new.durationMs) // 3 200 octets = 100 ms
        val note = listing.items[1]
        assertFalse(note.hasAudio)
        assertEquals(65_000L, note.durationMs)
        val old2 = listing.items[2]
        assertTrue(old2.encrypted)
        assertEquals(1000L, old2.durationMs) // taille moins IV, tag et en-tête
        assertEquals("[Intervenant 1] Ancien format", old2.transcript)
        assertEquals(File(dir, "old.wav.txt"), repo.transcriptFileFor(old))
        assertEquals(dir.listFiles()!!.sumOf { it.length() }, listing.totalBytes)
    }

    @Test
    fun cleanupExpiredRemovesOldRecordingsAndOrphans() {
        val now = System.currentTimeMillis()
        val day = 86_400_000L
        wav("expired.wav", modified = now - 10 * day)
        text("expired.txt", "x", modified = now - 10 * day)
        text("expired.meta", "{\"dossier\":\"D\"}", modified = now - 10 * day)
        wav("recent.wav", modified = now - 2 * day)
        text("recent.md", "# ok", modified = now - 2 * day)
        text("oldnote.txt", "Transcripto Stream\n----\n\nvieux", modified = now - 30 * day)
        text("oldnote.md", "# vieux", modified = now - 30 * day)
        text("orphan.md", "# sans propriétaire", modified = now)
        text("orphan.meta", "{}", modified = now)
        assertEquals(0, repo.cleanupExpired(0, now))
        assertEquals(9, names().size)
        assertEquals(2, repo.cleanupExpired(7, now))
        assertEquals(listOf("recent.md", "recent.wav"), names())
    }

    @Test
    fun transcriptWritePreservesHashAndSealsWhenEnabled() {
        val f = wav("r.wav")
        assertTrue(repo.writeTranscriptFile(f, "Bonjour", 65_000L, "ab".repeat(32)))
        val first = repo.readTranscript(f)!!
        assertTrue(first.contains("Durée : 01:05\n"))
        assertTrue(first.contains("SHA-256 (PCM) : " + "ab".repeat(32)))
        assertEquals("Bonjour", repo.transcriptBody(f))
        // Réécriture sans empreinte (transcription différée) : l'empreinte est conservée
        TextVault.enabled = true
        assertTrue(repo.writeTranscriptFile(f, "Bonjour à tous", 65_000L))
        val txt = File(dir, "r.txt")
        assertTrue(TextVault.isSealed(txt))
        assertTrue(repo.readTranscript(f)!!.contains("SHA-256 (PCM) : " + "ab".repeat(32)))
        assertEquals("Bonjour à tous", repo.transcriptBody(f))
        assertEquals(RecordingRepository.HASH_LINE.find(repo.readTranscript(f)!!)!!.groupValues[1], "ab".repeat(32))
        // Texte corrigé à la main : les noms affichés redeviennent génériques dans le fichier
        repo.setSpeakerName(f, 1, "M. Martin")
        assertTrue(repo.saveEditedTranscript(f, "[M. Martin] Corrigé").sealed)
        assertEquals("[Intervenant 1] Corrigé", repo.transcriptBody(f))
        // Fichier scellé avec une autre clé : illisible → null, aperçu vide, liste utilisable
        TextVault.keyProvider = KeyProvider { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
        assertNull(repo.readTranscript(f))
        assertEquals("", repo.item(f).transcript)
        assertEquals(1, repo.list().items.size)
    }

    @Test
    fun updateSegmentTextReplacesInPlaceOrRebuilds() {
        val f = wav("s.wav")
        val segs = listOf(SegmentData("Bonjour tout le monde", 0, 4000), SegmentData("Passons aux stocks", 5000, 9000))
        File(dir, "s.json").writeText(SegmentsCodec.toJson(segs, listOf(1, 2)))
        repo.writeTranscriptFile(f, "[Intervenant 1] [00:00] Bonjour tout le monde\n[Intervenant 2] [00:05] Passons aux stocks (note manuelle)", 9000)
        assertNull(repo.updateSegmentText(f, 5, "x", true))
        assertNull(repo.updateSegmentText(f, 0, "   ", true))
        // L'ancien passage est unique dans le .txt : remplacement ciblé, la note manuelle survit
        assertEquals(EditOutcome(sealed = true, segmentsStale = false), repo.updateSegmentText(f, 1, "Passons aux provisions", true))
        assertEquals("[Intervenant 1] [00:00] Bonjour tout le monde\n[Intervenant 2] [00:05] Passons aux provisions (note manuelle)", repo.transcriptBody(f))
        assertEquals("Passons aux provisions", repo.readSegments(f)[1].text)
        assertTrue(File(dir, "s.srt").readText().contains("Passons aux provisions"))
        // Passage introuvable dans le .txt (corrigé à la main entre-temps) : reconstruction, horodatage conservé
        repo.writeTranscriptFile(f, "[Intervenant 1] [00:00] Texte réécrit à la main", 9000)
        assertEquals(EditOutcome(sealed = true, segmentsStale = false), repo.updateSegmentText(f, 0, "Bonjour à toutes et à tous", false))
        val body = repo.transcriptBody(f)
        assertTrue(body, body.startsWith("[Intervenant 1] [00:00] Bonjour à toutes et à tous"))
        assertTrue(body, body.contains("[Intervenant 2] [00:05] Passons aux provisions"))
        assertTrue(body, body.contains("--- Temps de parole"))
        // Sans .json : rien à corriger
        assertNull(repo.updateSegmentText(wav("t.wav"), 0, "x", true))
        // .json scellé avec une autre clé : lecture stricte en erreur, lecture tolérante vide
        val u = wav("u.wav")
        File(dir, "u.json").writeBytes(TextSealer.seal("{\"segments\":[]}", KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()))
        assertTrue(repo.readSegments(u).isEmpty())
        assertTrue(runCatching { repo.readSegmentsStrict(u) }.isFailure)
        assertTrue(repo.readSegmentsStrict(wav("v.wav")).isEmpty())
    }

    @Test
    fun speakerMarkedTranscriptMarksPausesSpeakersAndStats() {
        val res = StreamResult(
            fullText = "a b c",
            segments = listOf(SegmentData("Un", 0, 1000), SegmentData("Deux", 3000, 4000), SegmentData("Trois", 4000, 6000), SegmentData("  ", 6000, 7000)),
        )
        val out = repo.buildSpeakerMarkedTranscript(res, listOf(1, 1, 2, 2), timestamps = true)
        assertEquals("[Intervenant 1] [00:00] Un  [pause 2s] [00:03] Deux \n[Intervenant 2] [00:04] Trois \n\n--- Temps de parole (estimation par la voix) ---\nIntervenant 1 : 00:02 (50 %)\nIntervenant 2 : 00:02 (50 %)", out)
        assertEquals("[Intervenant 1] Un", repo.buildSpeakerMarkedTranscript(res.copy(segments = res.segments.take(1)), listOf(1), false))
        assertEquals("a b c", repo.buildSpeakerMarkedTranscript(res.copy(segments = emptyList()), emptyList(), true))
    }

    @Test
    fun metaSummaryAndUniqueNames() {
        val f = wav("m.wav")
        repo.setSpeakerName(f, 2, " Mme Durand ")
        repo.updateMeta(f) { it.copy(dossier = "SARL X") }
        assertEquals(RecordingMeta(speakers = mapOf(2 to "Mme Durand"), dossier = "SARL X"), repo.readMeta(f))
        repo.writeSummary(f, "# Synthèse\n- [Intervenant 2] valide")
        assertEquals("# Synthèse\n- [Mme Durand] valide", repo.readSummary(f))
        assertNull(repo.readSummary(wav("none.wav")))
        // Dé-nommer le dernier intervenant et vider le dossier supprime le .meta
        repo.setSpeakerName(f, 2, "")
        repo.updateMeta(f) { it.copy(dossier = "") }
        assertFalse(File(dir, "m.meta").exists())
        text("a.txt", "x")
        text("a (2).txt", "x")
        assertEquals("a (3)", repo.uniqueBase("a"))
        assertEquals("a (4)", repo.uniqueBase("a", listOf("a (3)")))
        assertEquals("zz", repo.uniqueBase("zz"))
        assertEquals("b (2)", RecordingRepository.uniqueBase("b", setOf("b")))
        assertEquals("00:05", RecordingRepository.formatClock(5_400))
        assertEquals("01:02:02", RecordingRepository.formatClock(3_722_000)) // au-delà d'une heure, comme partout
    }

    @Test
    fun editedTranscriptRealignsSegmentsOrFlagsThemStale() {
        val f = wav("e.wav")
        val segs = listOf(SegmentData("Bonjour à tous", 0, 4000), SegmentData("Passons aux stocks", 5000, 9000), SegmentData("Puis aux provisions", 12_000, 15_000))
        repo.writeSidecars(f, "", SegmentsCodec.toJson(segs, listOf(1, 2, 2)))
        repo.setSpeakerName(f, 2, "Mme Durand")
        repo.writeTranscriptFile(f, "[Intervenant 1] [00:00] Bonjour à tous\n[Intervenant 2] [00:05] Passons aux stocks  [pause 3s] [00:12] Puis aux provisions\n\n--- Temps de parole (estimation par la voix) ---\nIntervenant 1 : 00:04 (36 %)\nIntervenant 2 : 00:07 (64 %)", 15_000)
        // Correction ligne à ligne (un horodatage par segment) : segments et .srt suivent
        val r = repo.saveEditedTranscript(f, "[Intervenant 1] [00:00] Bonjour à toutes et à tous\n[Mme Durand] [00:05] Passons aux stocks [⭐00:07] [pause 3s] [00:12] Puis aux provisions pour litige\n\n--- Temps de parole (estimation par la voix) ---\nIntervenant 1 : 00:04 (36 %)\nMme Durand : 00:07 (64 %)")
        assertEquals(EditOutcome(sealed = true, segmentsStale = false), r)
        assertEquals(listOf("Bonjour à toutes et à tous", "Passons aux stocks [⭐00:07]", "Puis aux provisions pour litige"), repo.readSegments(f).map { it.text })
        assertEquals(listOf(1, 2, 2), repo.readSegments(f).map { it.speaker })
        assertTrue(File(dir, "e.srt").readText().contains("Puis aux provisions pour litige"))
        assertFalse(repo.readMeta(f).segmentsStale)
        assertTrue("les noms ne sont pas figés dans le .txt", repo.transcriptBody(f).startsWith("[Intervenant 1] [00:00] Bonjour à toutes et à tous\n[Intervenant 2] [00:05]"))
        // Texte réécrit sans horodatages : impossible de réaligner → segments marqués désynchronisés
        val r2 = repo.saveEditedTranscript(f, "Résumé libre de la réunion, sans horodatage.")
        assertEquals(EditOutcome(sealed = true, segmentsStale = true), r2)
        assertTrue(repo.readMeta(f).segmentsStale)
        assertTrue(repo.item(f).segmentsStale)
        assertEquals(3, repo.readSegments(f).size)
        // Une nouvelle transcription (segments régénérés) lève le drapeau ; les autres métadonnées survivent
        repo.writeSidecars(f, "", SegmentsCodec.toJson(segs, listOf(1, 2, 2)))
        assertFalse(repo.readMeta(f).segmentsStale)
        assertEquals("Mme Durand", repo.readMeta(f).speakers[2])
        // Entrée sans segments : rien à réaligner, rien à signaler
        val t = File(dir, "note.txt").apply { writeText("Transcripto Stream\nDurée : 00:10\n----\n\nTexte seul") }
        assertEquals(EditOutcome(sealed = true, segmentsStale = false), repo.saveEditedTranscript(t, "Texte seul corrigé"))
        assertEquals("Texte seul corrigé", repo.transcriptBody(t))
        assertEquals(10_000L, repo.item(t).durationMs)
        // realign : un segment vidé ou un nombre d'horodatages différent → null
        assertNull(RecordingRepository.realign("[00:00] a [00:05]  [00:12] c", repo.readSegments(f)))
        assertNull(RecordingRepository.realign("[00:00] a [00:05] b", repo.readSegments(f)))
        assertNull(RecordingRepository.realign("[00:00] a", emptyList()))
    }

    @Test
    fun openActionsOfADossierComeFromOtherRecordings() {
        val now = System.currentTimeMillis()
        val a = wav("a.wav", modified = now - 3000)
        val b = wav("b.wav", modified = now - 2000)
        val c = text("c.txt", "Transcripto Stream\n----\n\nx", modified = now - 1000)
        val other = wav("d.wav", modified = now)
        repo.updateMeta(a) { it.copy(dossier = "SARL X", actions = listOf(ActionItem("1", "Une", done = false), ActionItem("2", "Deux", done = true))) }
        repo.updateMeta(b) { it.copy(dossier = "sarl x", actions = listOf(ActionItem("3", "Trois"))) }
        repo.updateMeta(c) { it.copy(dossier = "SARL X", actions = listOf(ActionItem("4", "Quatre"))) }
        repo.updateMeta(other) { it.copy(dossier = "Autre", actions = listOf(ActionItem("5", "Cinq"))) }
        assertEquals(listOf("c" to "Quatre", "b" to "Trois", "a" to "Une"), repo.openActionsInDossier("SARL X").map { it.first to it.second.text })
        assertEquals(listOf("c" to "Quatre", "a" to "Une"), repo.openActionsInDossier("SARL X", except = b).map { it.first to it.second.text })
        assertTrue(repo.openActionsInDossier("").isEmpty())
        assertTrue(repo.openActionsInDossier("Inconnu").isEmpty())
        assertEquals(1, repo.item(a).openActionCount)
        // Renommage / fusion de dossier : le .meta de chaque enregistrement rattaché est réécrit
        assertEquals(3, repo.renameDossier("sarl X", "SARL Martin"))
        assertEquals("SARL Martin", repo.readMeta(a).dossier)
        assertEquals("SARL Martin", repo.readMeta(c).dossier)
        assertEquals("Autre", repo.readMeta(other).dossier)
        assertEquals(0, repo.renameDossier("SARL Martin", " SARL Martin "))
        assertEquals(0, repo.renameDossier("", "X"))
        assertEquals(1, repo.renameDossier("Autre", "SARL Martin")) // fusion
        assertEquals(setOf("SARL Martin"), repo.list().items.map { it.dossier }.toSet())
    }

    @Test
    fun indexSourcesReadSegmentsOrTextLines() {
        val f = wav("i.wav")
        repo.writeTranscriptFile(f, "[Intervenant 1] [00:05] Ligne un.\nLigne deux.", 10_000)
        val fromTxt = repo.segmentsForIndex(f)
        assertEquals(2, fromTxt.size)
        assertEquals(StoredSegment(5000, 5000, "Ligne un.", 1), fromTxt[0])
        repo.writeSidecars(f, "", SegmentsCodec.toJson(listOf(SegmentData("Depuis le json", 0, 1000)), listOf(2)))
        assertFalse(File(dir, "i.srt").exists())
        assertEquals(listOf(StoredSegment(0, 1000, "Depuis le json", 2)), repo.segmentsForIndex(f))
        val src = repo.indexSource(repo.item(f))
        assertEquals("i", src.baseName)
        assertEquals(maxOf(File(dir, "i.txt").lastModified(), File(dir, "i.json").lastModified()), src.contentStamp)
        assertTrue(repo.segmentsForIndex(wav("empty.wav")).isEmpty())
    }
}
