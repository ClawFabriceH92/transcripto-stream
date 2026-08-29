package com.transcripto.stream.data

import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Chiffrement des sauvegardes exportables : contrairement aux WAV chiffrés avec la
 * clé AndroidKeyStore (qui ne peut pas quitter l'appareil), la sauvegarde utilise
 * une clé dérivée d'une phrase de passe — restaurable sur n'importe quel appareil.
 *
 * Format du fichier : "TSBK1" (5 octets) + sel (16) + IV (12) + zip chiffré AES-256-GCM.
 * Fonctions pures java.* : testables en JVM.
 */
object BackupCrypto {

    private const val MAGIC = "TSBK1"
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private const val ITERATIONS = 200_000
    private const val KEY_BITS = 256

    class InvalidBackupException(message: String) : Exception(message)

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, ITERATIONS, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val key = factory.generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(key, "AES")
    }

    /** Écrit l'en-tête (magic + sel + IV) puis retourne le flux chiffrant à remplir. */
    fun encryptingStream(out: OutputStream, passphrase: CharArray): OutputStream {
        val random = SecureRandom()
        val salt = ByteArray(SALT_LEN).also { random.nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(TAG_BITS, iv))
        out.write(MAGIC.toByteArray(Charsets.US_ASCII))
        out.write(salt)
        out.write(iv)
        return CipherOutputStream(out, cipher)
    }

    /**
     * Lit et vérifie l'en-tête puis retourne le flux déchiffrant.
     * Une phrase de passe erronée se manifeste par une exception (tag GCM invalide)
     * pendant la lecture ou à la fermeture du flux.
     */
    fun decryptingStream(inp: InputStream, passphrase: CharArray): InputStream {
        val magic = ByteArray(MAGIC.length)
        if (!readFully(inp, magic) || String(magic, Charsets.US_ASCII) != MAGIC) {
            throw InvalidBackupException("Ce fichier n'est pas une sauvegarde Transcripto")
        }
        val salt = ByteArray(SALT_LEN)
        val iv = ByteArray(IV_LEN)
        if (!readFully(inp, salt) || !readFully(inp, iv)) {
            throw InvalidBackupException("Sauvegarde tronquée")
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(TAG_BITS, iv))
        return CipherInputStream(inp, cipher)
    }

    private fun readFully(inp: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = inp.read(buf, off, buf.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }
}
