package com.transcripto.stream.data

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Scellement d'un texte au repos : « TSV1 » + IV (12 octets) + AES-256-GCM.
 * Pur JVM (la clé est injectée) : la partie Android — clé AndroidKeyStore,
 * réglage, migration — est dans [TextVault].
 */
object TextSealer {

    val MAGIC: ByteArray = "TSV1".toByteArray(Charsets.US_ASCII)
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    fun isSealed(bytes: ByteArray): Boolean =
        bytes.size >= MAGIC.size + IV_LEN + 16 &&
            bytes[0] == MAGIC[0] && bytes[1] == MAGIC[1] && bytes[2] == MAGIC[2] && bytes[3] == MAGIC[3]

    fun seal(plain: String, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return MAGIC + cipher.iv + ct
    }

    /** Lève si les octets ne sont pas scellés, sont altérés ou si la clé ne correspond pas. */
    fun open(sealed: ByteArray, key: SecretKey): String {
        require(isSealed(sealed)) { "Texte non scellé" }
        val ivStart = MAGIC.size
        val iv = sealed.copyOfRange(ivStart, ivStart + IV_LEN)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val from = ivStart + IV_LEN
        return String(cipher.doFinal(sealed, from, sealed.size - from), Charsets.UTF_8)
    }
}
