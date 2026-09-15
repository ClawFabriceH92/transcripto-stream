package com.transcripto.stream.summary

import com.transcripto.stream.data.Chapter
import com.transcripto.stream.data.KeyProvider
import com.transcripto.stream.data.RecordingRepository
import com.transcripto.stream.data.SegmentsCodec
import com.transcripto.stream.data.TextVault
import com.transcripto.stream.stt.SegmentData
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator

class AiAssistantTest {

    private class Settings(
        override var aiSummaryEnabled: Boolean = true,
        override var aiModel: String = "claude-opus-5",
        override var hasApiKey: Boolean = true,
    ) : AiSettings

    private class FakeTransport : ClaudeTransport {
        val calls = mutableListOf<Triple<String, String, ClaudeRequest>>()
        var reply: (ClaudeRequest) -> ClaudeText = { ClaudeText("", "claude-opus-5", "end_turn", 0, 0) }
        override fun run(apiKey: String, modelId: String, request: ClaudeRequest): ClaudeText {
            calls += Triple(apiKey, modelId, request)
            return reply(request)
        }
    }

    private lateinit var dir: File
    private lateinit var repo: RecordingRepository
    private val settings = Settings()
    private val transport = FakeTransport()
    private var key: String? = "sk-test"
    private lateinit var ai: AiAssistant
    private lateinit var file: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("ai").toFile()
        repo = RecordingRepository(dir)
        TextVault.keyProvider = KeyProvider { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
        TextVault.enabled = false
        ai = AiAssistant(repo, settings, { key }, transport)
        // WAV d'une seconde (32 000 octets de données déclarés dans l'en-tête)
        val header = ByteArray(44).also { it[40] = 0x00; it[41] = 0x7D }
        file = File(dir, "Réunion clôture.wav").apply { writeBytes(header + ByteArray(32_000)) }
        repo.writeTranscriptFile(
            file,
            "[Intervenant 1] [00:00] Bonjour, la provision pour litige de 12 000 euros doit être passée.\n[Intervenant 2] [00:05] D'accord, nous validons la provision.",
            65_000L,
        )
        repo.updateMeta(file) { it.copy(speakers = mapOf(1 to "M. Martin"), template = SummaryTemplates.CLOTURE.id) }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun summaryUsesClaudeThenFallsBackLocally() {
        transport.reply = { ClaudeText("## Points clés\n- Provision de 12 000 € validée", "claude-opus-5", "end_turn", 100, 50) }
        assertEquals("Synthèse IA prête", ai.summarize(file))
        val md = repo.readSummary(file)!!
        assertTrue(md, md.startsWith("# Synthèse — Réunion clôture\n"))
        assertTrue(md, md.contains("· 00:01 · synthèse IA (Opus 5)"))
        assertTrue(md, md.contains("## Points clés\n- Provision de 12 000 € validée"))
        assertTrue(md, md.contains("(claude-opus-5, 150 tokens)"))
        val (apiKey, model, req) = transport.calls.single()
        assertEquals("sk-test", apiKey)
        assertEquals("claude-opus-5", model)
        assertEquals(ClaudeRequest.Effort.MEDIUM, req.effort)
        assertEquals(16_000L, req.maxTokens)
        assertEquals(1, req.system.size)
        assertTrue(req.system[0].contains(SummaryTemplates.CLOTURE.aiTask.trim()))
        assertEquals(ClaudeMessage.Role.USER, req.messages.single().role)
        assertTrue("noms appliqués pour l'IA", req.messages.single().text.contains("[M. Martin] [00:00] Bonjour"))
        assertTrue(req.messages.single().text.contains("Durée : 00:01"))

        // Refus des filtres : synthèse locale à la place, noms appliqués, pas de mention IA
        transport.reply = { throw ClaudeRefusal() }
        assertEquals("Synthèse IA refusée par les filtres de sécurité du modèle — synthèse locale générée à la place", ai.summarize(file))
        val local = repo.readSummary(file)!!
        assertFalse(local, local.contains("synthèse IA ("))
        assertTrue(local, local.contains("synthèse locale automatique"))
        assertTrue(local, local.contains("12 000 euros"))

        // Erreur quelconque du transport : message traduit
        transport.reply = { throw IllegalStateException("boom") }
        assertEquals("Synthèse IA impossible : boom — synthèse locale générée à la place", ai.summarize(file))

        // IA désactivée : local, sans appel
        settings.aiSummaryEnabled = false
        val calls = transport.calls.size
        assertEquals("Synthèse prête", ai.summarize(file))
        assertEquals(calls, transport.calls.size)

        // Clé enregistrée mais illisible : le dire, synthèse locale
        settings.aiSummaryEnabled = true
        key = null
        assertEquals("${AiAssistant.KEY_UNREADABLE} — synthèse locale générée à la place", ai.summarize(file))
        assertEquals(calls, transport.calls.size)

        // Sans transcription
        val empty = File(dir, "vide.wav").apply { writeBytes(ByteArray(44)) }
        assertEquals("Pas de transcription à synthétiser — lance d'abord « Transcrire »", ai.summarize(empty))
        assertFalse(ai.available().not() && settings.hasApiKey)
    }

    @Test
    fun askSendsCachedPrefixAndHistoryAndFlagsTruncation() {
        repo.writeSummary(file, "# Synthèse\n- [Intervenant 1] valide la provision")
        transport.reply = { ClaudeText("Oui, **12 000 €**", "claude-opus-5", "max_tokens", 10, 5, cacheReadTokens = 900) }
        val history = listOf(QaTurn("Qui parle ?", "M. Martin et un second intervenant."))
        val a = ai.ask(file, history, "  Quel montant ? ") as AiAnswer.Ok
        assertEquals("Oui, **12 000 €**\n\n_(réponse tronquée : limite de longueur atteinte)_", a.text)
        assertEquals(900L, a.cacheReadTokens)
        val req = transport.calls.single().third
        assertEquals(ClaudeRequest.Effort.LOW, req.effort)
        assertTrue(req.cacheLastSystemBlock)
        assertEquals(2, req.system.size)
        assertTrue(req.system[1], req.system[1].contains("Titre : Réunion clôture"))
        assertTrue(req.system[1], req.system[1].contains("[M. Martin] [00:00] Bonjour"))
        assertTrue(req.system[1], req.system[1].contains("Synthèse existante :\n<<<\n# Synthèse\n- [M. Martin] valide la provision"))
        assertEquals(
            listOf(
                ClaudeMessage(ClaudeMessage.Role.USER, "Qui parle ?"),
                ClaudeMessage(ClaudeMessage.Role.ASSISTANT, "M. Martin et un second intervenant."),
                ClaudeMessage(ClaudeMessage.Role.USER, "Quel montant ?"),
            ),
            req.messages,
        )
        // Sans clé
        key = null
        settings.hasApiKey = false
        assertEquals(AiAnswer.Failed(AiAssistant.KEY_MISSING), ai.ask(file, emptyList(), "?"))
        settings.hasApiKey = true
        assertEquals(AiAnswer.Failed(AiAssistant.KEY_UNREADABLE), ai.ask(file, emptyList(), "?"))
        key = "sk-test"
        transport.reply = { throw ClaudeEmptyReply() }
        assertEquals(AiAnswer.Failed("Réponse vide du modèle"), ai.ask(file, emptyList(), "?"))
        transport.reply = { throw ClaudeRefusal() }
        assertEquals(AiAnswer.Failed("Question refusée par les filtres de sécurité du modèle"), ai.ask(file, emptyList(), "?"))
        assertEquals(AiAnswer.Failed("Question vide"), ai.ask(file, emptyList(), "   "))
        // Transcription scellée avec une autre clé : pas d'appel à vide
        TextVault.enabled = true
        repo.writeTranscriptFile(file, "scellé", 1000)
        TextVault.keyProvider = KeyProvider { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
        val before = transport.calls.size
        assertEquals(AiAnswer.Failed("Transcription illisible — clé perdue ou fichier altéré"), ai.ask(file, emptyList(), "?"))
        assertEquals(before, transport.calls.size)
    }

    @Test
    fun chaptersFromClaudeAreClampedAndFallBackLocally() {
        assertEquals("Chapitres : lance d'abord « Transcrire » (segments horodatés requis)", ai.chapters(file))
        val few = List(5) { SegmentData("Passage $it sur les stocks", it * 60_000L, it * 60_000L + 50_000L) }
        repo.writeSidecars(file, "", SegmentsCodec.toJson(few, List(5) { 1 }))
        assertEquals("Enregistrement trop court pour des chapitres", ai.chapters(file))
        val segs = List(10) { SegmentData("Passage $it sur les stocks", it * 60_000L, it * 60_000L + 50_000L) }
        repo.writeSidecars(file, "", SegmentsCodec.toJson(segs, List(10) { 1 }))
        transport.reply = {
            ClaudeText("""[{"start":"00:00","title":"Ouverture"},{"start":"05:00","title":"Stocks"},{"start":"99:00","title":"Hors champ"}]""", "claude-opus-5", "end_turn", 1, 1)
        }
        assertEquals("2 chapitres (IA)", ai.chapters(file))
        assertEquals(listOf(Chapter(0L, "Ouverture"), Chapter(300_000L, "Stocks")), repo.readMeta(file).chapters)
        val req = transport.calls.single().third
        assertEquals(ClaudeRequest.Effort.LOW, req.effort)
        assertTrue(req.messages.single().text.contains("[01:00] M. Martin : Passage 1 sur les stocks"))
        // Refus : repli local, aucun changement de sujet dans ce texte répétitif → chapitres conservés
        transport.reply = { throw ClaudeRefusal() }
        assertEquals("Chapitrage refusé par les filtres de sécurité du modèle — et pas de changement de sujet net détecté localement", ai.chapters(file))
        assertEquals(2, repo.readMeta(file).chapters.size)
        // Réponse inexploitable
        transport.reply = { ClaudeText("Je ne sais pas.", "claude-opus-5", "end_turn", 1, 1) }
        assertEquals("Réponse du modèle inexploitable — et pas de changement de sujet net détecté localement", ai.chapters(file))
    }
}
