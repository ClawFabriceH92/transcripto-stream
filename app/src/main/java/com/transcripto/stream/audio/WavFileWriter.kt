package com.transcripto.stream.audio

import java.io.File
import java.io.RandomAccessFile

/**
 * Écrit un flux PCM 16 kHz mono int16 dans un fichier WAV pendant l'enregistrement.
 * Le header WAV (44 octets) est écrit à la fermeture avec la taille réelle des données.
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
        raf.writeInt(((36 + dataSize).toInt()).toInt())
        raf.writeBytes("WAVE")
        raf.writeBytes("fmt ")
        raf.writeInt(16)              // taille du chunk fmt
        raf.writeShort(1)             // PCM
        raf.writeShort(1)             // mono
        raf.writeInt(16000)           // sample rate
        raf.writeInt(32000)           // byte rate
        raf.writeShort(2)             // block align
        raf.writeShort(16)            // bits par échantillon
        raf.writeBytes("data")
        raf.writeInt(dataSize.toInt())
    }
}
