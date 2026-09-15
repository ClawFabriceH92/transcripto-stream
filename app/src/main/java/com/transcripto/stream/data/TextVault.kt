package com.transcripto.stream.data

import android.util.Log
import java.io.File
import javax.crypto.SecretKey

/**
 * Fournit la clé AES-256 des textes scellés : AndroidKeyStore en production
 * ([CryptoManager]), clé de test en JVM. Injectée dans [TextVault.keyProvider].
 */
fun interface KeyProvider {
    fun key(): SecretKey
}

/** Bilan d'une migration : fichiers réécrits, fichiers illisibles laissés tels quels. */
data class VaultMigration(val rewritten: Int, val unreadable: Int)

/**
 * Lecture/écriture des fichiers texte d'un enregistrement (.txt, .srt, .json,
 * .md, .meta) avec chiffrement au repos facultatif. La lecture reconnaît les
 * deux formes (scellée « TSV1 » ou claire) ; l'écriture scelle si [enabled].
 * Clé AES-256 dans AndroidKeyStore (la même que pour les WAV).
 *
 * Écritures atomiques (fichier temporaire puis renommage) : une mort du process
 * en pleine écriture ne laisse jamais un fichier tronqué.
 */
object TextVault {

    private const val TAG = "TextVault"
    private const val TMP_SUFFIX = ".tmp"

    /** Suffixes des fichiers texte gérés (les WAV ont leur propre chiffrement). */
    val TEXT_SUFFIXES = listOf(".txt", ".srt", ".json", ".md", ".meta")

    /** Positionné par le ViewModel depuis le réglage ; lu à chaque écriture. */
    @Volatile
    var enabled: Boolean = false

    /** Source de la clé de scellement — configurée au démarrage de l'application (voir TranscriptoApp). */
    @Volatile
    var keyProvider: KeyProvider = KeyProvider { throw IllegalStateException("TextVault.keyProvider non configuré") }

    private fun key(): SecretKey = keyProvider.key()

    fun isTextFile(name: String): Boolean = TEXT_SUFFIXES.any { name.endsWith(it) }

    /** Contenu en clair du fichier (déchiffré si scellé). Lève en cas d'erreur d'E/S ou de clé. */
    fun read(file: File): String {
        val bytes = file.readBytes()
        if (!TextSealer.isSealed(bytes)) return String(bytes, Charsets.UTF_8)
        return TextSealer.open(bytes, key())
    }

    /**
     * Écrit [text], scellé si le chiffrement des textes est actif. Si le scellement
     * échoue (KeyStore indisponible), le texte est conservé en clair plutôt que perdu —
     * même politique que pour les WAV. Retourne false dans ce cas de repli.
     */
    fun write(file: File, text: String): Boolean {
        if (enabled) {
            val sealed = try {
                TextSealer.seal(text, key())
            } catch (e: Exception) {
                Log.e(TAG, "seal ${file.name}: ${e.message}")
                null
            }
            if (sealed != null) {
                atomicWrite(file, sealed)
                return true
            }
            atomicWrite(file, text.toByteArray(Charsets.UTF_8))
            return false
        }
        atomicWrite(file, text.toByteArray(Charsets.UTF_8))
        return true
    }

    /** Scellé ? Ne lit que l'en-tête (4 octets) — pas le fichier entier. */
    fun isSealed(file: File): Boolean = try {
        file.length() >= 32 && file.inputStream().use { inp ->
            val head = ByteArray(TextSealer.MAGIC.size)
            inp.read(head) == head.size && head.contentEquals(TextSealer.MAGIC)
        }
    } catch (e: Exception) {
        false
    }

    /**
     * Migre tous les fichiers texte de [dir] vers la forme demandée ([seal] = chiffrer).
     * Idempotent (relançable après une interruption) ; la date de modification de
     * chaque fichier est conservée (tri de la liste, rétention). Un fichier illisible
     * (clé perdue, fichier altéré) est laissé tel quel et compté.
     */
    fun migrate(dir: File, seal: Boolean): VaultMigration {
        var rewritten = 0
        var unreadable = 0
        val files = dir.listFiles() ?: return VaultMigration(0, 0)
        for (f in files) {
            if (!f.isFile || !isTextFile(f.name)) continue
            try {
                val bytes = f.readBytes()
                val sealed = TextSealer.isSealed(bytes)
                if (sealed == seal) continue
                val plain = if (sealed) TextSealer.open(bytes, key()) else String(bytes, Charsets.UTF_8)
                val out = if (seal) TextSealer.seal(plain, key()) else plain.toByteArray(Charsets.UTF_8)
                val stamp = f.lastModified()
                atomicWrite(f, out)
                if (stamp > 0) f.setLastModified(stamp)
                rewritten++
            } catch (e: Exception) {
                Log.e(TAG, "migrate ${f.name}: ${e.message}")
                unreadable++
            }
        }
        return VaultMigration(rewritten, unreadable)
    }

    /** Supprime les temporaires d'écriture laissés par une mort du process. */
    fun purgeTemporaries(dir: File) {
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.endsWith(TMP_SUFFIX)) f.delete()
        }
    }

    private fun atomicWrite(file: File, bytes: ByteArray) {
        val tmp = File(file.parentFile, file.name + TMP_SUFFIX)
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            // Renommage refusé (système de fichiers exotique) : écriture directe, temporaire retiré
            file.writeBytes(bytes)
            tmp.delete()
        }
    }
}
