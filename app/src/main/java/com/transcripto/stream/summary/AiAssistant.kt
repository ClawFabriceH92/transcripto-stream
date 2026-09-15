package com.transcripto.stream.summary

import com.transcripto.stream.data.RecordingNames
import com.transcripto.stream.data.RecordingRepository
import com.transcripto.stream.data.SpeakerNames
import com.transcripto.stream.export.TranscriptExporter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Réglages de l'assistant IA (SharedPreferences en production, valeurs fixes en test). */
interface AiSettings {
    val aiSummaryEnabled: Boolean
    val aiModel: String
    /** Une clé API est enregistrée (même si elle s'avère indéchiffrable). */
    val hasApiKey: Boolean
}

/**
 * Enchaînements « IA » d'un enregistrement — synthèse, questions, chapitres :
 * lecture des fichiers (transcription, noms d'intervenants, synthèse), appel de
 * Claude quand l'IA est configurée, repli local sinon ou en cas d'échec, écriture
 * des résultats (.md, .meta) et message utilisateur. Méthodes bloquantes (réseau,
 * E/S) : à appeler hors du thread principal.
 */
class AiAssistant(
    private val repo: RecordingRepository,
    private val settings: AiSettings,
    /** Clé API déchiffrée ; null si absente ou illisible (clé KeyStore perdue). */
    private val apiKey: () -> String?,
) {

    companion object {
        private val DATE_FORMAT = SimpleDateFormat("d MMMM yyyy 'à' HH:mm", Locale.FRANCE)
        const val KEY_UNREADABLE = "Clé API illisible — ressaisis-la dans les Réglages"
        const val KEY_MISSING = "Clé API absente — Réglages › Synthèse"
    }

    /** L'IA est utilisable (option active et clé enregistrée) — sans I/O. */
    fun available(): Boolean = settings.aiSummaryEnabled && settings.hasApiKey

    /** Clé à utiliser si l'IA est active, avec le message d'échec si elle est présente mais illisible. */
    private fun keyForAi(): Pair<String?, String?> {
        if (!settings.aiSummaryEnabled) return null to null
        val key = apiKey()
        // Blob présent mais indéchiffrable (clé KeyStore perdue) : le dire, pas se taire
        val failure = if (key == null && settings.hasApiKey) KEY_UNREADABLE else null
        return key to failure
    }

    // ---- Synthèse ----

    /**
     * Génère la synthèse — par Claude si l'option est active et qu'une clé est
     * enregistrée (repli local en cas d'échec), sinon localement — et l'écrit en
     * « base.md ». Retourne le message à afficher.
     */
    fun summarize(file: File): String {
        val text = repo.transcriptBody(file)
        if (text.isBlank()) return "Pas de transcription à synthétiser — lance d'abord « Transcrire »"
        val meta = repo.readMeta(file)
        val names = meta.speakers
        val template = SummaryTemplates.byId(meta.template)
        val input = SummaryInput(
            title = RecordingNames.baseName(file.name),
            transcript = text,
            durationMs = repo.durationMsOf(file),
            dateLabel = DATE_FORMAT.format(Date(file.lastModified())),
        )
        var (key, failure) = keyForAi()
        val markdown = if (key != null) {
            // L'IA reçoit les vrais noms (meilleure rédaction) ; le local les applique en sortie
            val named = input.copy(transcript = SpeakerNames.apply(text, names))
            when (val r = ClaudeSummarizer.summarize(key, settings.aiModel, named, template)) {
                is AiSummaryResult.Ok -> wrapAiSummary(input, r)
                is AiSummaryResult.Failed -> {
                    failure = r.message
                    SpeakerNames.apply(LocalSummarizer.summarize(input, template), names)
                }
            }
        } else {
            SpeakerNames.apply(LocalSummarizer.summarize(input, template), names)
        }
        return try {
            repo.writeSummary(file, markdown)
            when {
                failure != null -> "$failure — synthèse locale générée à la place"
                key != null -> "Synthèse IA prête"
                else -> "Synthèse prête"
            }
        } catch (e: Exception) {
            "Écriture de la synthèse impossible : ${e.message}"
        }
    }

    private fun wrapAiSummary(input: SummaryInput, r: AiSummaryResult.Ok): String = buildString {
        append("# Synthèse — ").append(input.title).append('\n')
        append('_').append(input.dateLabel)
        if (input.durationMs > 0) append(" · ").append(TranscriptExporter.formatHms(input.durationMs))
        append(" · synthèse IA (").append(ClaudeSummarizer.modelLabel(r.model)).append(")_\n\n")
        append(r.markdown.trim()).append("\n\n")
        append("_Synthèse rédigée par Claude (").append(r.model).append(", ")
            .append(r.inputTokens + r.outputTokens)
            .append(" tokens) à partir de la transcription — à relire avant diffusion._\n")
    }

    // ---- Questions ----

    /**
     * Pose une question à Claude sur [file] : transcription (noms appliqués) + synthèse
     * existante en contexte, [history] des échanges du même enregistrement conservé
     * (préfixe mis en cache côté API).
     */
    fun ask(file: File, history: List<QaTurn>, question: String): AiAnswer {
        val key = apiKey() ?: return AiAnswer.Failed(if (settings.hasApiKey) KEY_UNREADABLE else KEY_MISSING)
        val raw = repo.transcriptBody(file)
        val names = repo.readMeta(file).speakers
        return ClaudeQa.ask(
            apiKey = key,
            modelId = settings.aiModel,
            title = RecordingNames.baseName(file.name),
            transcript = SpeakerNames.apply(raw, names),
            summaryMarkdown = repo.readSummary(file),
            history = history,
            question = question,
        )
    }

    // ---- Chapitres ----

    /**
     * Détecte les chapitres — par Claude si l'IA est configurée (repli local en cas
     * d'échec), sinon par bascule de vocabulaire — et les écrit dans le .meta.
     * Requiert les segments horodatés (« Transcrire »). Retourne le message à afficher.
     */
    fun chapters(file: File): String {
        if (!repo.hasSegments(file)) return "Chapitres : lance d'abord « Transcrire » (segments horodatés requis)"
        val segs = repo.readSegments(file)
        if (segs.size < 8) return "Enregistrement trop court pour des chapitres"
        val meta = repo.readMeta(file)
        var (key, failure) = keyForAi()
        val detected = if (key != null) {
            // Horodatages hh:mm:ss au-delà d'une heure (réunions longues) : pas de repli à 00:00
            val timed = segs.joinToString("\n") { s ->
                "[${TranscriptExporter.formatHms(s.startMs)}] ${SpeakerNames.label(s.speaker, meta.speakers)} : ${s.text.trim()}"
            }
            when (val r = ClaudeChapters.detect(key, settings.aiModel, RecordingNames.baseName(file.name), timed)) {
                is AiChaptersResult.Ok -> r.chapters
                is AiChaptersResult.Failed -> {
                    failure = r.message
                    ChapterDetector.detect(segs)
                }
            }
        } else {
            ChapterDetector.detect(segs)
        }
        // Bornes : pas de chapitre après la fin ; le premier commence avec le premier passage
        val lastMs = segs.last().endMs.coerceAtLeast(segs.last().startMs)
        val chapters = detected.filter { it.startMs <= lastMs }
            .sortedBy { it.startMs }
            .mapIndexed { i, c -> if (i == 0) c.copy(startMs = minOf(c.startMs, segs.first().startMs)) else c }
        if (chapters.isEmpty()) {
            return failure?.let { "$it — et pas de changement de sujet net détecté localement" }
                ?: "Pas de changement de sujet assez net pour des chapitres"
        }
        // Relu au moment d'écrire : un nom d'intervenant ou un dossier saisi pendant
        // l'appel (jusqu'à plusieurs minutes) n'est pas écrasé
        repo.updateMeta(file) { it.copy(chapters = chapters) }
        return when {
            failure != null -> "$failure — ${chapters.size} chapitres détectés localement"
            key != null -> "${chapters.size} chapitres (IA)"
            else -> "${chapters.size} chapitres détectés"
        }
    }
}
