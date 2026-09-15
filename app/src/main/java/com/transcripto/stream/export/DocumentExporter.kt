package com.transcripto.stream.export

import com.transcripto.stream.data.RecordingNames
import com.transcripto.stream.data.RecordingRepository
import com.transcripto.stream.data.SpeakerNames
import com.transcripto.stream.summary.SummaryTemplates
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Document Word ou PDF d'un enregistrement : page de garde, synthèse, transcription.
 * [build] assemble le document depuis les fichiers du dépôt (pur, testable) ;
 * [write] le compose et l'écrit dans le flux choisi. Méthodes bloquantes (E/S).
 */
class DocumentExporter(
    private val repo: RecordingRepository,
    /** Version de l'application, imprimée dans la note de génération et le pied de page. */
    private val appVersion: String,
    /** Réglage « horodatage » : utilisé quand le .txt est vide (sinon déduit de son contenu). */
    private val useTimestamps: () -> Boolean,
) {

    companion object {
        private val DATE_FORMAT = SimpleDateFormat("d MMMM yyyy 'à' HH:mm", Locale.FRANCE)
    }

    /** Assemble le document d'un enregistrement (I/O). */
    fun build(file: File): ExportDocument {
        val meta = repo.readMeta(file)
        val content = repo.readTranscript(file) ?: ""
        val raw = RecordingRepository.bodyOf(content)
        return ExportDocument(
            title = RecordingNames.baseName(file.name),
            dateLabel = DATE_FORMAT.format(Date(file.lastModified())),
            durationMs = repo.durationMsOf(file),
            dossier = meta.dossier,
            missionLabel = SummaryTemplates.ALL.firstOrNull { it.id == meta.template }?.label ?: "",
            speakerNames = meta.speakers,
            sha256 = RecordingRepository.HASH_LINE.find(content)?.groupValues?.get(1),
            encrypted = file.name.endsWith(".enc"),
            summaryMarkdown = repo.readSummary(file),
            segments = repo.readSegments(file).map { ExportSegment(it.speaker, it.startMs, it.endMs, it.text, it.confidence) },
            chapters = meta.chapters,
            actions = meta.actions.map { ExportAction(it.text, it.owner, it.dueLabel, it.done) },
            transcriptText = SpeakerNames.apply(raw, meta.speakers),
            timestamps = if (raw.isEmpty()) useTimestamps() else RecordingRepository.CLOCK_TAG.containsMatchIn(raw),
            appVersion = appVersion,
            generatedLabel = DATE_FORMAT.format(Date()),
        )
    }

    /** Document d'un dossier : ses enregistrements du plus ancien au plus récent (I/O). */
    fun buildDossier(name: String, includeTranscripts: Boolean): DossierDocument {
        val items = repo.list().items
            .filter { it.dossier.equals(name.trim(), ignoreCase = true) }
            .sortedBy { it.modifiedAt }
        return DossierDocument(
            name = name.trim(),
            recordings = items.map { build(it.file) },
            includeTranscripts = includeTranscripts,
            appVersion = appVersion,
            generatedLabel = DATE_FORMAT.format(Date()),
        )
    }

    fun writeDossier(doc: DossierDocument, out: OutputStream, format: ExportFormat) =
        writeBlocks(ExportComposer.composeDossier(doc), "Dossier ${doc.name}", out, format)

    /** Écrit [doc] (voir [build]) au format [format] dans [out] (fermé à la fin, même en cas d'échec). */
    fun write(doc: ExportDocument, out: OutputStream, format: ExportFormat) =
        writeBlocks(ExportComposer.compose(doc), doc.title, out, format)

    private fun writeBlocks(blocks: List<DocBlock>, title: String, out: OutputStream, format: ExportFormat) {
        out.use { o ->
            when (format) {
                ExportFormat.DOCX -> o.write(DocxWriter.write(blocks, title))
                ExportFormat.PDF -> PdfWriter.write(
                    blocks,
                    o,
                    footer = "Transcripto Stream" +
                        (if (appVersion.isNotBlank()) " v$appVersion" else "") +
                        " · $title",
                )
            }
        }
    }
}
