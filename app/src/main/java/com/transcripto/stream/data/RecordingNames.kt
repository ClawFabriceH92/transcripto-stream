package com.transcripto.stream.data

import java.io.File

/**
 * Conventions de nommage des enregistrements — un seul endroit pour éviter les
 * incohérences entre « base.wav », « base.wav.enc » (chiffré) et « base.txt »
 * (transcription seule, mode Google).
 */
object RecordingNames {

    private val FORBIDDEN = Regex("[\\\\/:*?\"<>|]")

    /** « base.wav », « base.wav.enc », « base.txt » → « base ». */
    fun baseName(fileName: String): String =
        fileName.removeSuffix(".enc").removeSuffix(".wav").removeSuffix(".txt")

    /** Suffixe conservé lors d'un renommage : « .wav », « .wav.enc » ou « .txt ». */
    fun suffix(fileName: String): String = fileName.substring(baseName(fileName).length)

    /**
     * Retire les caractères interdits dans un nom de fichier, ainsi qu'un éventuel
     * suffixe réservé final (.wav/.txt/.enc, en boucle pour « notes.txt.wav ») :
     * sinon renommer en « notes.txt » produirait « notes.txt.wav » au baseName instable.
     */
    fun sanitize(name: String): String {
        var out = name.trim().replace(FORBIDDEN, "").trim()
        while (true) {
            val stripped = baseName(out)
            if (stripped == out) break
            out = stripped.trim()
        }
        return out
    }

    /** Fichier audio (WAV en clair ou chiffré) — par opposition aux entrées texte seul. */
    fun isAudio(fileName: String): Boolean =
        fileName.endsWith(".wav") || fileName.endsWith(".enc")

    /** Entrée « texte seul » (transcription Google sans audio conservé). */
    fun isTextOnly(fileName: String): Boolean = fileName.endsWith(".txt")

    fun txtSibling(file: File): File =
        File(file.parentFile, baseName(file.name) + ".txt")

    fun srtSibling(file: File): File =
        File(file.parentFile, baseName(file.name) + ".srt")

    /** Fichier frère après renommage : même dossier, même suffixe, nouvelle base. */
    fun renamed(file: File, newBase: String): File =
        File(file.parentFile, newBase + suffix(file.name))
}
