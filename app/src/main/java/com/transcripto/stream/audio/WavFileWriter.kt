package com.transcripto.stream.audio

import java.io.File
import java.io.RandomAccessFile

/**
 * Écrit un flux PCM 16 kHz mono int16 dans un fichier WAV pendant l'enregistrement.
 * Le header WAV (44 octets) est écrit à la fermeture avec la taille réelle des données.
 *
 * ⚠️ Les champs de taille WAV sont en LITTLE-ENDIAN — RandomAccessFile.writeInt()
 * écrit en big-endian, ce qui corrompait le header (lecture MediaPlayer impossible).
 */
class WavFileWriter(file: File) {

    private val raf = RandomAccessFile(file, "rw")
    private var dataSize = 0L
    private val byteBuf = ByteArray(8192)

    init {
        writeHeader(0L)
    }

    fun write(samples: ShortArray, count: Int) {
        var i = 0
        while (i < count) {
            var n = 0
            while (i < count && n < byteBuf.size - 1) {
                val v = samples[i].toInt()
                byteBuf[n] = (v and 0xFF).toByte()
                byteBuf[n + 1] = ((v shr 8) and 0xFF).toByte()
                n += 2
                i++
            }
            raf.write(byteBuf, 0, n)
            dataSize += n
        }
    }

    fun close() {
        try {
            raf.seek(0)
            writeHeader(dataSize)
        } finally {
            raf.close()
        }
    }

    private fun writeHeader(dataSize: Long) {
        raf.writeBytes("RIFF")
        writeIntLE((36 + dataSize).toInt())
        raf.writeBytes("WAVE")
        raf.writeBytes("fmt ")
        writeIntLE(16)              // taille du chunk fmt
        writeShortLE(1)             // PCM
        writeShortLE(1)             // mono
        writeIntLE(16000)           // sample rate
        writeIntLE(32000)           // byte rate
        writeShortLE(2)             // block align
        writeShortLE(16)            // bits par échantillon
        raf.writeBytes("data")
        writeIntLE(dataSize.toInt())
    }

    /** Écrit un int en little-endian (WAV). */
    private fun writeIntLE(v: Int) {
        raf.write(v and 0xFF)
        raf.write((v shr 8) and 0xFF)
        raf.write((v shr 16) and 0xFF)
        raf.write((v shr 24) and 0xFF)
    }

    /** Écrit un short en little-endian (WAV). */
    private fun writeShortLE(v: Int) {
        raf.write(v and 0xFF)
        raf.write((v shr 8) and 0xFF)
    }
}
