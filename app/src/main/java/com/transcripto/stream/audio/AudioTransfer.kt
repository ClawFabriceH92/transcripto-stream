package com.transcripto.stream.audio

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.transcripto.stream.data.RecordingNames
import com.transcripto.stream.data.RecordingRepository
import com.transcripto.stream.data.WavCipher
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Issue d'un import : le fichier créé (chiffré selon le réglage) ou un message d'erreur. */
data class ImportResult(val file: File?, val error: String?, val unsealed: Boolean = false)

/**
 * Import d'un audio externe (décodage vers WAV 16 kHz mono dans le dépôt, chiffrement
 * selon le réglage) et export d'un enregistrement (déchiffré à la volée) vers un
 * document choisi via SAF. E/S bloquantes, à appeler hors du thread principal.
 */
class AudioTransfer(
    private val context: Context,
    private val repo: RecordingRepository,
    private val cipher: WavCipher,
    private val encryptWav: () -> Boolean,
) {

    companion object {
        private const val TAG = "AudioTransfer"
        private val DATE_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    }

    fun import(uri: Uri, onProgress: (Float) -> Unit): ImportResult {
        val base = importBaseName(uri)
        // Décodage vers un temporaire du cache : pas de WAV partiel (et en clair)
        // dans recordings/ si le process meurt en plein import
        val tmp = File(context.cacheDir, "import_${System.currentTimeMillis()}.wav")
        var lastPct = -1
        val e = AudioImporter.importToWav(context, uri, tmp) { p ->
            val pct = (p * 100).toInt()
            if (pct != lastPct) { // limite les recompositions à 1 par % affiché
                lastPct = pct
                onProgress(pct / 100f)
            }
        }
        if (e != null) {
            tmp.delete()
            return ImportResult(null, e)
        }
        val dest = File(repo.dir, "$base.wav")
        val moved = tmp.renameTo(dest) || try {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
            true
        } catch (ex: Exception) {
            false
        }
        if (!moved) {
            tmp.delete()
            return ImportResult(null, "Impossible d'enregistrer le fichier importé")
        }
        if (!encryptWav()) return ImportResult(dest, null)
        val enc = File(dest.parentFile, dest.nameWithoutExtension + ".wav.enc")
        return if (cipher.encryptFile(dest, enc)) {
            dest.delete()
            ImportResult(enc, null)
        } else {
            ImportResult(dest, null, unsealed = true)
        }
    }

    /** Nom de base unique pour un import, dérivé du nom d'origine du fichier. */
    private fun importBaseName(uri: Uri): String {
        val display = try {
            context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: Exception) {
            null
        }
        var base = RecordingNames.sanitize(display?.substringBeforeLast('.') ?: "")
        if (base.isEmpty()) base = "import_${DATE_FORMAT.format(Date())}"
        return repo.uniqueBase(base)
    }

    /** Copie l'audio (déchiffré à la volée) vers [destUri] ; false si l'export a échoué. */
    fun export(file: File, destUri: Uri): Boolean {
        // Temp de déchiffrement propre à l'export : ne pas réutiliser le temp
        // « _dec » que le partage peut encore servir via FileProvider
        val clear = if (file.extension == "enc") {
            cipher.decryptToTemp(file, context.cacheDir, "_exp_${System.currentTimeMillis()}")
        } else {
            file
        }
        if (clear == null || !clear.exists()) return false
        return try {
            // « wt » tronque un document existant ; le mode par défaut « w » des
            // DocumentsProviders ne tronque pas — remplacer un WAV plus long
            // laisserait des octets résiduels après le flux copié
            val out = try {
                context.contentResolver.openOutputStream(destUri, "wt")
            } catch (e: Exception) {
                context.contentResolver.openOutputStream(destUri)
            } ?: return false
            out.use { o ->
                clear.inputStream().use { it.copyTo(o, 64 * 1024) }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "export: ${e.message}")
            false
        } finally {
            if (clear != file) clear.delete()
        }
    }
}
