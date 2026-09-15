package com.transcripto.stream.data

import java.io.File

/**
 * Chiffrement des fichiers audio : [CryptoManager] (clé AndroidKeyStore) en
 * production, implémentation factice en test. Permet à la sauvegarde d'être
 * testée en JVM sans KeyStore.
 */
interface WavCipher {
    /** Chiffre [src] vers [dest] (« base.wav.enc ») ; false si le chiffrement a échoué (rien n'est écrit). */
    fun encryptFile(src: File, dest: File): Boolean

    /** Déchiffre vers un temporaire de [cacheDir] ; null si illisible. [suffix] distingue les usages simultanés. */
    fun decryptToTemp(encFile: File, cacheDir: File, suffix: String = "_dec"): File?
}
