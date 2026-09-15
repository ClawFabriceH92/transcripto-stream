package com.transcripto.stream.data

import android.util.Log
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Bilan d'un export : fichiers archivés, textes scellés illisibles ignorés. */
data class BackupExport(val count: Int, val unreadable: Int)

/** Bilan d'une restauration : fichiers écrits, WAV laissés en clair faute de chiffrement. */
data class BackupRestore(val count: Int, val unsealed: Int)

/**
 * Sauvegarde chiffrée par phrase de passe (format .tsbk : zip AES-256-GCM, clé
 * PBKDF2) — restaurable sur un autre appareil, contrairement aux WAV et textes
 * scellés avec la clé AndroidKeyStore : dans l'archive, tout est en clair.
 * Méthodes bloquantes, à appeler hors du thread principal ; les erreurs lèvent.
 */
class BackupManager(
    private val repo: RecordingRepository,
    private val cacheDir: File,
    private val cipher: WavCipher,
    /** Réglage « chiffrer les WAV » : les WAV restaurés sont chiffrés en conséquence. */
    private val encryptWav: () -> Boolean,
) {

    companion object {
        private const val TAG = "BackupManager"
        private val ALLOWED_SUFFIXES = setOf(".wav", ".txt", ".srt", ".json", ".md", ".meta")
    }

    /** Exporte tous les enregistrements dans [out] (fermé à la fin, même en cas d'échec). */
    fun export(out: OutputStream, passphrase: CharArray): BackupExport {
        var count = 0
        var unreadable = 0
        // out.use englobe tout : même un échec avant le flux chiffrant ferme le SAF
        out.use { rawOut ->
            BackupCrypto.encryptingStream(rawOut, passphrase).use { enc ->
                ZipOutputStream(enc).use { zip ->
                    val files = repo.dir.listFiles()?.sortedBy { it.name } ?: emptyList()
                    // « a.wav » et « a.wav.enc » donneraient la même entrée
                    // « a.wav » — un doublon ferait planter tout l'export (ZipException)
                    val usedNames = HashSet<String>()
                    for (f in files) {
                        if (!f.isFile) continue
                        if (f.name.endsWith(".enc")) {
                            val entryName = RecordingNames.baseName(f.name) + ".wav"
                            if (!usedNames.add(entryName)) continue
                            // Ré-encodé en clair DANS l'archive (elle-même chiffrée
                            // par la phrase de passe) : la clé KeyStore ne voyage pas
                            val clear = cipher.decryptToTemp(f, cacheDir, "_bak_${System.currentTimeMillis()}")
                                ?: continue
                            try {
                                zip.putNextEntry(ZipEntry(entryName))
                                clear.inputStream().use { it.copyTo(zip, 64 * 1024) }
                                zip.closeEntry()
                            } finally {
                                clear.delete()
                            }
                        } else {
                            if (!usedNames.add(f.name)) continue
                            // Textes scellés (chiffrement au repos) : en clair DANS l'archive,
                            // comme les WAV — la clé KeyStore ne voyage pas. Un texte scellé
                            // illisible (clé perdue) est ignoré : il ne serait lisible nulle part.
                            val sealed = TextVault.isTextFile(f.name) && TextVault.isSealed(f)
                            val plain = if (sealed) {
                                try {
                                    TextVault.read(f)
                                } catch (e: Exception) {
                                    unreadable++
                                    continue
                                }
                            } else {
                                null
                            }
                            zip.putNextEntry(ZipEntry(f.name))
                            if (plain != null) {
                                zip.write(plain.toByteArray(Charsets.UTF_8))
                            } else {
                                f.inputStream().use { it.copyTo(zip, 64 * 1024) }
                            }
                            zip.closeEntry()
                        }
                        count++
                    }
                }
            }
        }
        return BackupExport(count, unreadable)
    }

    /**
     * Restaure une archive : jamais destructif (les doublons sont suffixés), entrées
     * hors dossier ou de type inconnu ignorées. Lève [BackupCrypto.InvalidBackupException]
     * si le fichier n'est pas une sauvegarde, une autre exception si la phrase de passe
     * est incorrecte ou l'archive corrompue.
     */
    fun restore(inp: InputStream, passphrase: CharArray): BackupRestore {
        var count = 0
        var unsealed = 0
        val renames = HashMap<String, String>() // base d'origine → base locale
        val dir = repo.dir
        // inp.use englobe tout : un en-tête invalide (exception AVANT la
        // création du flux déchiffrant) ferme quand même le flux SAF
        inp.use { rawIn ->
            BackupCrypto.decryptingStream(rawIn, passphrase).use { dec ->
                ZipInputStream(dec).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val rawName = entry.name
                        if (!entry.isDirectory && !rawName.contains('/') && !rawName.contains('\\')) {
                            val base = RecordingNames.sanitize(RecordingNames.baseName(rawName))
                            val suffixPart = rawName.substring(RecordingNames.baseName(rawName).length)
                            if (base.isNotEmpty() && suffixPart in ALLOWED_SUFFIXES) {
                                val target = renames.getOrPut(base) { repo.uniqueBase(base, renames.values) }
                                val destFile = File(dir, target + suffixPart)
                                destFile.outputStream().use { zip.copyTo(it, 64 * 1024) }
                                if (suffixPart == ".wav") {
                                    if (encryptWav() && !sealWav(destFile)) unsealed++
                                } else if (TextVault.enabled) {
                                    // Textes restaurés en clair : scellés si le réglage est actif
                                    try {
                                        TextVault.write(destFile, TextVault.read(destFile))
                                    } catch (e: Exception) {
                                        Log.e(TAG, "restore seal ${destFile.name}: ${e.message}")
                                    }
                                }
                                count++
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
        }
        return BackupRestore(count, unsealed)
    }

    /** Chiffre un WAV restauré en « base.wav.enc » ; false si le WAV reste en clair. */
    private fun sealWav(file: File): Boolean {
        val enc = File(file.parentFile, file.nameWithoutExtension + ".wav.enc")
        return if (cipher.encryptFile(file, enc)) {
            file.delete()
            true
        } else {
            false
        }
    }
}
