package com.transcripto.stream.data

import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Chiffrement des sauvegardes exportables : contrairement aux WAV chiffrés avec la
 * clé AndroidKeyStore (qui ne peut pas quitter l'appareil), la sauvegarde utilise
 * une clé dérivée d'une phrase de passe — restaurable sur n'importe quel appareil.
 *
 * Format « TSBK2 », chiffré PAR TRAMES de 1 Mo : sur Android, l'implémentation
 * AES-GCM (Conscrypt) est one-shot — un flux GCM unique bufferiserait toute
 * l'archive en mémoire (OOM sur une bibliothèque de réunions). Chaque trame est
 * un doFinal indépendant avec IV = base(8) + compteur(4) : mémoire constante,
 * intégrité par trame, réordonnancement et troncature détectés (trame de fin vide).
 *
 * Fichier : "TSBK2" (5) + sel (16) + base IV (8) + [len(4) + trame GCM]* + trame vide.
 * Fonctions pures java.* : testables en JVM.
 */
object BackupCrypto {

    private const val MAGIC = "TSBK2"
    private const val SALT_LEN = 16
    private const val IV_BASE_LEN = 8
    private const val IV_LEN = 12
    private const val TAG_LEN = 16
    private const val TAG_BITS = 128
    private const val ITERATIONS = 200_000
    private const val KEY_BITS = 256
    private const val FRAME_LEN = 1 shl 20 // 1 Mo de clair par trame

    class InvalidBackupException(message: String) : Exception(message)

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, ITERATIONS, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val key = factory.generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(key, "AES")
    }

    private fun frameIv(base: ByteArray, counter: Int): ByteArray {
        val iv = ByteArray(IV_LEN)
        System.arraycopy(base, 0, iv, 0, IV_BASE_LEN)
        iv[8] = (counter ushr 24).toByte()
        iv[9] = (counter ushr 16).toByte()
        iv[10] = (counter ushr 8).toByte()
        iv[11] = counter.toByte()
        return iv
    }

    /** Écrit l'en-tête (magic + sel + base IV) puis retourne le flux chiffrant par trames. */
    fun encryptingStream(out: OutputStream, passphrase: CharArray): OutputStream {
        val random = SecureRandom()
        val salt = ByteArray(SALT_LEN).also { random.nextBytes(it) }
        val ivBase = ByteArray(IV_BASE_LEN).also { random.nextBytes(it) }
        out.write(MAGIC.toByteArray(Charsets.US_ASCII))
        out.write(salt)
        out.write(ivBase)
        return FrameOutputStream(out, deriveKey(passphrase, salt), ivBase)
    }

    /**
     * Lit et vérifie l'en-tête puis retourne le flux déchiffrant par trames.
     * Phrase de passe erronée ou archive altérée → exception pendant la lecture
     * (tag GCM invalide) ; archive tronquée → InvalidBackupException.
     */
    fun decryptingStream(inp: InputStream, passphrase: CharArray): InputStream {
        val magic = ByteArray(MAGIC.length)
        if (!readFully(inp, magic) || String(magic, Charsets.US_ASCII) != MAGIC) {
            throw InvalidBackupException("Ce fichier n'est pas une sauvegarde Transcripto")
        }
        val salt = ByteArray(SALT_LEN)
        val ivBase = ByteArray(IV_BASE_LEN)
        if (!readFully(inp, salt) || !readFully(inp, ivBase)) {
            throw InvalidBackupException("Sauvegarde tronquée")
        }
        return FrameInputStream(inp, deriveKey(passphrase, salt), ivBase)
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

    private class FrameOutputStream(
        private val out: OutputStream,
        private val key: SecretKeySpec,
        private val ivBase: ByteArray,
    ) : OutputStream() {

        private val buf = ByteArray(FRAME_LEN)
        private var filled = 0
        private var counter = 0
        private var closed = false

        override fun write(b: Int) {
            buf[filled++] = b.toByte()
            if (filled == FRAME_LEN) flushFrame()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var o = off
            var remaining = len
            while (remaining > 0) {
                val chunk = minOf(remaining, FRAME_LEN - filled)
                System.arraycopy(b, o, buf, filled, chunk)
                filled += chunk
                o += chunk
                remaining -= chunk
                if (filled == FRAME_LEN) flushFrame()
            }
        }

        private fun flushFrame() {
            if (filled == 0) return
            emit(buf, filled)
            filled = 0
        }

        private fun emit(data: ByteArray, len: Int) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, frameIv(ivBase, counter)))
            counter++
            val ct = cipher.doFinal(data, 0, len)
            writeInt(ct.size)
            out.write(ct)
        }

        private fun writeInt(v: Int) {
            out.write((v ushr 24) and 0xFF)
            out.write((v ushr 16) and 0xFF)
            out.write((v ushr 8) and 0xFF)
            out.write(v and 0xFF)
        }

        override fun flush() {
            out.flush()
        }

        override fun close() {
            if (closed) return
            closed = true
            flushFrame()
            emit(ByteArray(0), 0) // trame de fin (vide, authentifiée)
            out.flush()
            out.close()
        }
    }

    private class FrameInputStream(
        private val inp: InputStream,
        private val key: SecretKeySpec,
        private val ivBase: ByteArray,
    ) : InputStream() {

        private var current: ByteArray = ByteArray(0)
        private var pos = 0
        private var counter = 0
        private var eof = false
        private val one = ByteArray(1)

        override fun read(): Int {
            val n = read(one, 0, 1)
            return if (n < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pos >= current.size) {
                if (eof) return -1
                loadNextFrame()
                if (eof) return -1
            }
            val n = minOf(len, current.size - pos)
            System.arraycopy(current, pos, b, off, n)
            pos += n
            return n
        }

        private fun loadNextFrame() {
            val lenBytes = ByteArray(4)
            if (!readFully(inp, lenBytes)) {
                // Fin de fichier sans trame de terminaison = archive tronquée
                throw InvalidBackupException("Sauvegarde tronquée ou incomplète")
            }
            val ctLen = ((lenBytes[0].toInt() and 0xFF) shl 24) or
                ((lenBytes[1].toInt() and 0xFF) shl 16) or
                ((lenBytes[2].toInt() and 0xFF) shl 8) or
                (lenBytes[3].toInt() and 0xFF)
            if (ctLen < TAG_LEN || ctLen > FRAME_LEN + TAG_LEN) {
                throw InvalidBackupException("Sauvegarde corrompue (taille de trame invalide)")
            }
            val ct = ByteArray(ctLen)
            if (!readFully(inp, ct)) {
                throw InvalidBackupException("Sauvegarde tronquée ou incomplète")
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, frameIv(ivBase, counter)))
            counter++
            val plain = cipher.doFinal(ct) // AEADBadTagException si altéré / mauvaise phrase
            if (plain.isEmpty()) {
                eof = true // trame de fin authentifiée
            } else {
                current = plain
                pos = 0
            }
        }

        override fun close() {
            inp.close()
        }
    }
}
