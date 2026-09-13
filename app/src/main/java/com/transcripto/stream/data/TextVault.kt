package com.transcripto.stream.data

import android.util.Log
import java.io.File

/**
 * Lecture/écriture des fichiers texte d'un enregistrement (.txt, .srt, .json,
 * .md, .meta) avec chiffrement au repos facultatif. La lecture reconnaît les
 * deux formes (scellée « TSV1 » ou claire) ; l'écriture scelle si [enabled].
 * Clé AES-256 dans AndroidKeyStore (la même que pour les WAV).
 */
object TextVault {

    private const val TAG = "TextVault"

    /** Suffixes des fichiers texte gérés (les WAV ont leur propre chiffrement). */
    val TEXT_SUFFIXES = listOf(".txt", ".srt", ".json", ".md", ".meta")

    /** Positionné par le ViewModel depuis le réglage ; lu à chaque écriture. */
    @Volatile
    var enabled: Boolean = false

    fun isTextFile(name: String): Boolean = TEXT_SUFFIXES.any { name.endsWith(it) }

    /** Contenu en clair du fichier (déchiffré si scellé). Lève en cas d'erreur d'E/S. */
    fun read(file: File): String {
        val bytes = file.readBytes()
        if (!TextSealer.isSealed(bytes)) return String(bytes, Charsets.UTF_8)
        return TextSealer.open(bytes, CryptoManager.key())
    }

    /** Écrit [text], scellé si le chiffrement des textes est actif. */
    fun write(file: File, text: String) {
        if (enabled) {
            file.writeBytes(TextSealer.seal(text, CryptoManager.key()))
        } else {
            file.writeText(text)
        }
    }

    fun isSealed(file: File): Boolean = try {
        file.exists() && TextSealer.isSealed(file.readBytes())
    } catch (e: Exception) {
        false
    }

    /**
     * Migre tous les fichiers texte de [dir] vers la forme demandée ([seal] = chiffrer).
     * Retourne le nombre de fichiers réécrits ; un fichier illisible est laissé tel quel.
     */
    fun migrate(dir: File, seal: Boolean): Int {
        var n = 0
        val files = dir.listFiles() ?: return 0
        for (f in files) {
            if (!f.isFile || !isTextFile(f.name)) continue
            try {
                val bytes = f.readBytes()
                val sealed = TextSealer.isSealed(bytes)
                if (sealed == seal) continue
                val plain = if (sealed) TextSealer.open(bytes, CryptoManager.key()) else String(bytes, Charsets.UTF_8)
                if (seal) f.writeBytes(TextSealer.seal(plain, CryptoManager.key())) else f.writeText(plain)
                n++
            } catch (e: Exception) {
                Log.e(TAG, "migrate ${f.name}: ${e.message}")
            }
        }
        return n
    }
}
