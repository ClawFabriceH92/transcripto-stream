package com.transcripto.stream.audio

import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Conversion PCM pour l'import d'audio externe : mixage vers mono et
 * rééchantillonnage linéaire en flux (par blocs, sans tout charger en mémoire).
 * Fonctions pures, testables sans Android.
 */
object PcmResampler {

    /** Moyenne les canaux entrelacés vers un signal mono. */
    fun downmixToMono(input: ShortArray, frames: Int, channels: Int): ShortArray {
        if (channels <= 1) return if (frames == input.size) input else input.copyOf(frames)
        val out = ShortArray(frames)
        for (f in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) {
                sum += input[f * channels + c].toInt()
            }
            out[f] = (sum / channels).toShort()
        }
        return out
    }

    /** Convertit des échantillons float [-1;1] (ENCODING_PCM_FLOAT) en int16. */
    fun floatsToShorts(input: FloatArray, n: Int): ShortArray {
        val out = ShortArray(n)
        for (i in 0 until n) {
            out[i] = (input[i] * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }
}

/**
 * Rééchantillonneur linéaire avec état : accepte le flux par blocs et interpole
 * correctement à la frontière entre deux blocs (le dernier échantillon du bloc
 * précédent est conservé). Fonctionne dans les deux sens (48 k→16 k, 8 k→16 k…).
 */
class LinearResampler(private val srcRate: Int, private val dstRate: Int) {

    private val step = srcRate.toDouble() / dstRate

    // Position source fractionnaire du prochain échantillon de sortie, relative au
    // début du bloc courant. Dans [-1 ; 0[, on interpole entre `prev` et input[0].
    private var pos = 0.0
    private var prev: Short = 0

    fun process(input: ShortArray, n: Int): ShortArray {
        if (n <= 0) return ShortArray(0)
        if (srcRate == dstRate) return if (n == input.size) input else input.copyOf(n)
        val capacity = ((n - pos) / step).toInt() + 2
        val out = ShortArray(capacity)
        var count = 0
        while (pos <= n - 1) {
            val i = floor(pos).toInt()
            val frac = pos - i
            val s0 = if (i < 0) prev.toDouble() else input[i].toDouble()
            val s1 = if (i + 1 < n) input[i + 1].toDouble() else input[n - 1].toDouble()
            val v = s0 + (s1 - s0) * frac
            out[count++] = v.roundToInt().coerceIn(-32768, 32767).toShort()
            pos += step
        }
        pos -= n
        prev = input[n - 1]
        return if (count == capacity) out else out.copyOf(count)
    }
}
