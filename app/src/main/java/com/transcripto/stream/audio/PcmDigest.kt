package com.transcripto.stream.audio

import java.security.MessageDigest

/**
 * Empreinte SHA-256 du flux PCM int16 LE, calculée au fil de l'eau sur le thread audio
 * (valeur probante : l'empreinte est notée dans le .txt de l'enregistrement).
 */
class PcmDigest {

    private val digest: MessageDigest? = try {
        MessageDigest.getInstance("SHA-256")
    } catch (e: Exception) {
        null
    }
    private var scratch = ByteArray(0)

    fun update(buf: ShortArray, n: Int) {
        val d = digest ?: return
        if (scratch.size < n * 2) scratch = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = buf[i].toInt()
            scratch[2 * i] = (v and 0xFF).toByte()
            scratch[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        d.update(scratch, 0, n * 2)
    }

    /** Empreinte hexadécimale finale (null si SHA-256 est indisponible). À appeler une fois, capture arrêtée. */
    fun hex(): String? = digest?.digest()?.joinToString("") { "%02x".format(it) }
}
