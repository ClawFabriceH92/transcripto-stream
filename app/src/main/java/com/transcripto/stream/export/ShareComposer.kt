package com.transcripto.stream.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.transcripto.stream.data.RecordingNames
import com.transcripto.stream.data.RecordingRepository
import com.transcripto.stream.data.TextVault
import com.transcripto.stream.data.WavCipher
import com.transcripto.stream.summary.MarkdownLite
import java.io.File

/**
 * Intent de partage d'un enregistrement : transcription (ou synthèse) en corps de
 * message, .txt joint, audio déchiffré à la volée, sous-titres .srt et synthèse .md.
 * Les copies en clair vont dans cacheDir/exports (purgé au démarrage). E/S bloquantes.
 */
class ShareComposer(
    private val context: Context,
    private val repo: RecordingRepository,
    private val cipher: WavCipher,
) {

    companion object {
        private const val TAG = "ShareComposer"
    }

    private fun uri(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    fun intentFor(file: File, transcriptText: String): Intent? {
        return try {
            val base = RecordingNames.baseName(file.name)
            val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
            val txt = File(exportDir, "$base.txt")
            txt.writeText(
                if (transcriptText.isBlank()) "Transcripto Stream — enregistrement sans transcription\n" else transcriptText
            )

            val attachments = arrayListOf(uri(txt))
            if (RecordingNames.isAudio(file.name)) {
                val clear = if (file.extension != "enc") file else cipher.decryptToTemp(file, context.cacheDir, "_dec")
                if (clear != null) attachments.add(uri(clear))
            }
            // .srt et .md : copies en clair dans le cache (les originaux peuvent être scellés)
            val srt = RecordingNames.srtSibling(file)
            if (srt.exists()) {
                val srtCopy = File(exportDir, "$base.srt")
                srtCopy.writeText(TextVault.read(srt))
                attachments.add(uri(srtCopy))
            }
            // Synthèse : en corps de message (la transcription complète reste jointe) + .md joint
            val summaryText = repo.readSummary(file)
            if (summaryText != null) {
                val mdCopy = File(exportDir, "$base.md")
                mdCopy.writeText(summaryText)
                attachments.add(uri(mdCopy))
            }
            val body = if (summaryText != null) {
                MarkdownLite.toPlainText(summaryText) +
                    "\n\n— Transcription complète en pièce jointe (.txt)."
            } else {
                transcriptText
            }

            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(""))
                putExtra(Intent.EXTRA_SUBJECT, if (summaryText != null) "Synthèse — $base" else "Transcription $base")
                putExtra(Intent.EXTRA_TEXT, body)
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, attachments)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (e: Exception) {
            Log.e(TAG, "intentFor: ${e.message}")
            null
        }
    }
}
