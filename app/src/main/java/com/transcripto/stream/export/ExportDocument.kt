package com.transcripto.stream.export

import com.transcripto.stream.data.Chapter
import com.transcripto.stream.data.SpeakerNames
import com.transcripto.stream.summary.MarkdownLite
import com.transcripto.stream.summary.MdBlock
import com.transcripto.stream.summary.MdSpan
import kotlin.math.roundToInt

/** Formats d'export structuré d'un enregistrement. */
enum class ExportFormat(val label: String, val mime: String, val extension: String) {
    DOCX("Word", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
    PDF("PDF", "application/pdf", "pdf"),
}

/** Segment horodaté attribué à un intervenant (numéro brut, le nom vient de [ExportDocument.speakerNames]). */
data class ExportSegment(val speaker: Int, val startMs: Long, val endMs: Long, val text: String)

/**
 * Tout ce qu'un export structuré (Word, PDF) doit contenir. Assemblé par le
 * ViewModel à partir des fichiers de l'enregistrement, puis composé en blocs
 * typés par [ExportComposer] — indépendant d'Android, testable.
 */
data class ExportDocument(
    val title: String,
    val dateLabel: String,
    val durationMs: Long,
    val dossier: String = "",
    val missionLabel: String = "",
    val speakerNames: Map<Int, String> = emptyMap(),
    val sha256: String? = null,
    val encrypted: Boolean = false,
    /** Synthèse Markdown (noms d'intervenants déjà appliqués) ou null. */
    val summaryMarkdown: String? = null,
    val segments: List<ExportSegment> = emptyList(),
    /** Chapitres (début + titre) insérés comme sous-titres dans la transcription. */
    val chapters: List<Chapter> = emptyList(),
    /** Transcription texte (noms appliqués) — utilisée quand il n'y a pas de segments. */
    val transcriptText: String = "",
    val timestamps: Boolean = true,
    val appVersion: String = "",
    val generatedLabel: String = "",
)

/** Bloc de mise en page commun aux rédacteurs Word et PDF. */
sealed class DocBlock {
    data class Title(val text: String) : DocBlock()
    data class Subtitle(val text: String) : DocBlock()
    /** [level] 1 à 3. */
    data class Heading(val level: Int, val text: String) : DocBlock()
    data class Meta(val label: String, val value: String) : DocBlock()
    data class Para(val spans: List<MdSpan>, val bullet: Boolean = false) : DocBlock()
    /** Étiquette d'intervenant ; [index] sert à varier la couleur. */
    data class Speaker(val label: String, val index: Int) : DocBlock()
    /** Passage de transcription, horodatage facultatif « mm:ss ». */
    data class Segment(val clock: String?, val text: String) : DocBlock()
    data class Note(val text: String) : DocBlock()
    data object PageBreak : DocBlock()
}

/** Compose la page de garde, la synthèse et la transcription en blocs. Pur Kotlin. */
object ExportComposer {

    fun compose(doc: ExportDocument): List<DocBlock> {
        val out = ArrayList<DocBlock>()
        out += DocBlock.Title(doc.title.ifBlank { "Enregistrement" })
        if (doc.dossier.isNotBlank()) out += DocBlock.Subtitle(doc.dossier)
        out += DocBlock.Meta("Date", doc.dateLabel)
        if (doc.durationMs > 0) out += DocBlock.Meta("Durée", TranscriptExporter.formatHms(doc.durationMs))
        if (doc.missionLabel.isNotBlank()) out += DocBlock.Meta("Type de mission", doc.missionLabel)
        val speakers = speakerLabels(doc)
        if (speakers.isNotEmpty()) out += DocBlock.Meta("Intervenants", speakers.joinToString(", "))
        speakingShares(doc)?.let { out += DocBlock.Meta("Temps de parole", it) }
        if (doc.chapters.isNotEmpty()) out += DocBlock.Meta("Chapitres", doc.chapters.size.toString())
        if (doc.encrypted) out += DocBlock.Meta("Audio", "chiffré sur l'appareil (AES-256-GCM)")
        doc.sha256?.takeIf { it.isNotBlank() }?.let { out += DocBlock.Meta("Empreinte SHA-256 (PCM)", it) }
        out += DocBlock.Note(
            buildString {
                append("Document généré par Transcripto Stream")
                if (doc.appVersion.isNotBlank()) append(" v").append(doc.appVersion)
                if (doc.generatedLabel.isNotBlank()) append(" le ").append(doc.generatedLabel)
                append(". Transcription automatique : à relire avant diffusion.")
            }
        )

        val summary = doc.summaryMarkdown?.trim().orEmpty()
        if (summary.isNotEmpty()) {
            out += DocBlock.Heading(1, "Synthèse")
            for (block in MarkdownLite.parse(summary)) {
                when (block) {
                    // Le titre de niveau 1 de la synthèse (« # Synthèse — … ») fait doublon
                    is MdBlock.Heading -> if (block.level > 1) {
                        out += DocBlock.Heading(block.level.coerceIn(2, 3), block.text)
                    }
                    is MdBlock.Bullet -> out += DocBlock.Para(MarkdownLite.spans(block.text), bullet = true)
                    is MdBlock.Paragraph -> out += DocBlock.Para(MarkdownLite.spans(block.text))
                    MdBlock.Blank -> Unit
                }
            }
        }

        out += DocBlock.PageBreak
        out += DocBlock.Heading(1, "Transcription")
        val segs = doc.segments.filter { it.text.isNotBlank() }
        when {
            segs.isNotEmpty() -> {
                val multi = segs.map { it.speaker }.distinct().size > 1 || doc.speakerNames.isNotEmpty()
                val chapters = doc.chapters.sortedBy { it.startMs }
                var nextChapter = 0
                var current = Int.MIN_VALUE
                for (seg in segs) {
                    while (nextChapter < chapters.size && chapters[nextChapter].startMs <= seg.startMs) {
                        val c = chapters[nextChapter++]
                        out += DocBlock.Heading(2, "${TranscriptExporter.formatHms(c.startMs)} — ${c.title}")
                        current = Int.MIN_VALUE // l'étiquette d'intervenant est répétée après un titre de chapitre
                    }
                    if (multi && seg.speaker != current) {
                        current = seg.speaker
                        out += DocBlock.Speaker(SpeakerNames.label(seg.speaker, doc.speakerNames), seg.speaker)
                    }
                    val clock = if (doc.timestamps) TranscriptExporter.formatHms(seg.startMs) else null
                    out += DocBlock.Segment(clock, seg.text.trim())
                }
                // Chapitres situés après le dernier passage : listés quand même (jamais perdus)
                while (nextChapter < chapters.size) {
                    val c = chapters[nextChapter++]
                    out += DocBlock.Heading(2, "${TranscriptExporter.formatHms(c.startMs)} — ${c.title}")
                }
            }
            doc.transcriptText.isNotBlank() -> {
                doc.transcriptText.lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .forEach { out += DocBlock.Para(listOf(MdSpan(it))) }
            }
            else -> out += DocBlock.Note("Pas de transcription pour cet enregistrement.")
        }
        return out
    }

    private fun speakerLabels(doc: ExportDocument): List<String> {
        val ids = LinkedHashSet<Int>()
        doc.segments.forEach { ids += it.speaker }
        doc.speakerNames.keys.sorted().forEach { ids += it }
        if (ids.size < 2 && doc.speakerNames.isEmpty()) return emptyList()
        return ids.sorted().map { SpeakerNames.label(it, doc.speakerNames) }
    }

    /** « M. Martin 60 % · Intervenant 2 40 % » ou null si moins de deux intervenants. */
    private fun speakingShares(doc: ExportDocument): String? {
        val totals = LinkedHashMap<Int, Long>()
        doc.segments.filter { it.text.isNotBlank() }.forEach { s ->
            totals.merge(s.speaker, (s.endMs - s.startMs).coerceAtLeast(0), Long::plus)
        }
        if (totals.size < 2) return null
        val grand = totals.values.sum()
        if (grand <= 0) return null
        return totals.entries.sortedBy { it.key }.joinToString(" · ") { (id, dur) ->
            "${SpeakerNames.label(id, doc.speakerNames)} ${(dur * 100.0 / grand).roundToInt()} %"
        }
    }
}
