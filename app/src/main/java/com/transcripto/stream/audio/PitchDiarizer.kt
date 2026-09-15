package com.transcripto.stream.audio

import com.transcripto.stream.stt.SegmentData
import java.io.File
import kotlin.math.abs
import kotlin.math.min

/**
 * Diarisation approximative par hauteur de voix : attribue un intervenant (1 ou 2,
 * alternance) à chaque segment quand le pitch médian F0 varie de plus de 20 % —
 * estimation adaptée aux entretiens à deux voix. Pur, testable en JVM.
 */
object PitchDiarizer {

    private const val SAMPLE_RATE = 16000

    fun detectSpeakers(shorts: ShortArray, segments: List<SegmentData>): List<Int> {
        if (shorts.isEmpty()) return List(segments.size) { 1 }
        val ids = ArrayList<Int>(segments.size)
        var current = 1
        var prevF0: Double? = null
        for (seg in segments) {
            val s0 = ((seg.startMs * SAMPLE_RATE) / 1000L).toInt().coerceIn(0, shorts.size - 1)
            val s1 = ((seg.endMs * SAMPLE_RATE) / 1000L).toInt().coerceIn(s0 + 1, shorts.size)
            val f0 = averagePitch(shorts, s0, s1)
            if (prevF0 != null && f0 != null) {
                val ratio = abs(f0 - prevF0) / min(f0, prevF0)
                if (ratio > 0.20) current = if (current == 1) 2 else 1
            }
            prevF0 = f0 ?: prevF0
            ids.add(current)
        }
        return ids
    }

    /** PCM int16 LE → échantillons. */
    fun toShorts(bytes: ByteArray): ShortArray {
        val n = bytes.size / 2
        val out = ShortArray(n)
        for (i in 0 until n) {
            out[i] = ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort()
        }
        return out
    }

    /** F0 médian (60–300 Hz) sur des fenêtres de 30 ms ; null si moins de trois fenêtres voisées. */
    fun averagePitch(pcm: ShortArray, start: Int, end: Int): Double? {
        val win = 480
        val hop = 240
        val pitches = mutableListOf<Double>()
        var s = start
        while (s + win < end) {
            val f0 = f0Window(pcm, s, win)
            if (f0 != null && f0 in 60.0..300.0) pitches.add(f0)
            s += hop
        }
        if (pitches.size < 3) return null
        pitches.sort()
        return pitches[pitches.size / 2]
    }

    private fun f0Window(pcm: ShortArray, offset: Int, len: Int): Double? {
        var bestLag = -1
        var bestScore = 0.0
        var energy = 0.0
        for (i in 0 until len) {
            val v = pcm[offset + i].toDouble()
            energy += v * v
        }
        if (energy < 1e6) return null
        for (lag in 40..267) {
            var score = 0.0
            for (i in 0 until len - lag) {
                score += pcm[offset + i].toDouble() * pcm[offset + i + lag].toDouble()
            }
            score /= (len - lag)
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        if (bestLag <= 0 || bestScore <= 0) return null
        return SAMPLE_RATE.toDouble() / bestLag
    }
}

/** Lecture des données PCM d'un WAV (chunk « data », ou PCM brut après 44 octets à défaut). */
object WavPcm {
    fun read(file: File): ByteArray? {
        return try {
            val bytes = file.readBytes()
            var idx = 12
            while (idx + 8 <= bytes.size) {
                val id = String(bytes, idx, 4)
                val size = (bytes[idx + 4].toInt() and 0xFF) or
                    ((bytes[idx + 5].toInt() and 0xFF) shl 8) or
                    ((bytes[idx + 6].toInt() and 0xFF) shl 16) or
                    ((bytes[idx + 7].toInt() and 0xFF) shl 24)
                if (id == "data") {
                    return bytes.copyOfRange(idx + 8, minOf(idx + 8 + size, bytes.size))
                }
                idx += 8 + size
            }
            // Filet de sécurité : pas de chunk "data" trouvé → PCM brut après un header de 44 octets
            if (bytes.size > 44) bytes.copyOfRange(44, bytes.size) else null
        } catch (e: Exception) {
            null
        }
    }
}
