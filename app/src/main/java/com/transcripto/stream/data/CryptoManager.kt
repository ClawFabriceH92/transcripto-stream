package com.transcripto.stream.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Chiffrement AES-256-GCM des WAV avec clé stockée dans AndroidKeyStore.
 * Format : [IV 12 octets][données chiffrées] — le fichier chiffré porte l'extension .enc
 * et le IV est conservé en tête pour permettre le déchiffrement.
 */
object CryptoManager {

    private const val TAG = "CryptoManager"
    private const val ALIAS = "transcripto_stream_wav_key"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return kg.generateKey()
    }

    fun encryptFile(src: File, dest: File): Boolean {
        return try {
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            FileOutputStream(dest).use { out ->
                out.write(cipher.iv) // IV en tête
                FileInputStream(src).use { inp ->
                    CipherOutputStream(out, cipher).use { cout ->
                        inp.copyTo(cout, 64 * 1024)
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "encryptFile: ${e.message}")
            false
        }
    }

    /**
     * Déchiffre vers un fichier temporaire (cacheDir). Le fichier temp est supprimé
     * à la sortie du process — usage lecture/écoute/email uniquement.
     */
    fun decryptToTemp(encFile: File, cacheDir: File): File? {
        return try {
            val key = getOrCreateKey()
            val bytes = encFile.readBytes()
            if (bytes.size < IV_LEN + 16) return null
            val iv = bytes.copyOfRange(0, IV_LEN)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            val out = File(cacheDir, encFile.nameWithoutExtension + "_dec.wav")
            FileOutputStream(out).use { fos ->
                CipherInputStream(bytes.inputStream().buffered().apply { skip(IV_LEN.toLong()) }, cipher).use { cin ->
                    cin.copyTo(fos, 64 * 1024)
                }
            }
            out
        } catch (e: Exception) {
            Log.e(TAG, "decryptToTemp: ${e.message}")
            null
        }
    }
}
