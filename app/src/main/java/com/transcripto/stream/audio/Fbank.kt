package com.transcripto.stream.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Banc de filtres log-mel à la manière Kaldi (torchaudio.compliance.kaldi.fbank), tel
 * qu'attendu par les encodeurs de locuteur WeSpeaker : trames de 25 ms toutes les 10 ms,
 * suppression de la composante continue, préaccentuation 0,97, fenêtre de Hamming, FFT 512,
 * spectre de puissance, 80 bandes mel entre 20 Hz et Nyquist, logarithme borné. Entrée :
 * échantillons à l'échelle int16 (±32768), 16 kHz, sans dither. Pur, testé contre des
 * valeurs de référence calculées hors ligne.
 */
object Fbank {

    const val SAMPLE_RATE = 16_000
    const val NUM_BINS = 80
    const val FRAME = 400
    const val SHIFT = 160
    private const val NFFT = 512
    private const val PREEMPH = 0.97f
    private const val LOW_FREQ = 20.0
    private const val EPS = 1.1920928955078125e-07

    private val window = FloatArray(FRAME) { (0.54 - 0.46 * cos(2 * PI * it / (FRAME - 1))).toFloat() }

    /** Poids mel : par bande, (premier indice FFT, poids…) — les indices hors triangle sont absents. */
    private val banks: Array<Pair<Int, FloatArray>> = buildBanks()

    private fun mel(f: Double) = 1127.0 * ln(1.0 + f / 700.0)

    private fun buildBanks(): Array<Pair<Int, FloatArray>> {
        val melLow = mel(LOW_FREQ)
        val melHigh = mel(SAMPLE_RATE / 2.0)
        val delta = (melHigh - melLow) / (NUM_BINS + 1)
        val binWidth = SAMPLE_RATE.toDouble() / NFFT
        val nbins = NFFT / 2 // la bande de Nyquist n'est pas utilisée (comme Kaldi)
        return Array(NUM_BINS) { j ->
            val left = melLow + j * delta
            val center = melLow + (j + 1) * delta
            val right = melLow + (j + 2) * delta
            val weights = ArrayList<Float>()
            var first = -1
            for (k in 0 until nbins) {
                val m = mel(k * binWidth)
                val w = when {
                    m > left && m <= center -> (m - left) / (center - left)
                    m > center && m < right -> (right - m) / (right - center)
                    else -> 0.0
                }
                if (w > 0.0) {
                    if (first < 0) first = k
                    weights += w.toFloat()
                } else if (first >= 0) {
                    break
                }
            }
            (if (first < 0) 0 else first) to weights.toFloatArray()
        }
    }

    /** Nombre de trames pour [n] échantillons (bords rognés, comme Kaldi `snip_edges`). */
    fun frameCount(n: Int): Int = if (n < FRAME) 0 else 1 + (n - FRAME) / SHIFT

    /**
     * Trames × 80 log-mel pour [pcm] (int16, 16 kHz) entre [start] (inclus) et [end] (exclu).
     * Tableau vide si le passage fait moins de 25 ms.
     */
    fun compute(pcm: ShortArray, start: Int = 0, end: Int = pcm.size): Array<FloatArray> {
        val n = (end - start).coerceAtLeast(0)
        val frames = frameCount(n)
        if (frames == 0) return emptyArray()
        val re = FloatArray(NFFT)
        val im = FloatArray(NFFT)
        val power = FloatArray(NFFT / 2)
        val out = Array(frames) { FloatArray(NUM_BINS) }
        val frame = FloatArray(FRAME)
        for (t in 0 until frames) {
            val off = start + t * SHIFT
            var mean = 0.0
            for (i in 0 until FRAME) {
                frame[i] = pcm[off + i].toFloat()
                mean += frame[i]
            }
            val dc = (mean / FRAME).toFloat()
            for (i in 0 until FRAME) frame[i] -= dc // suppression de la composante continue
            // Préaccentuation (de la fin vers le début : x[i] -= 0,97·x[i-1] sur les valeurs d'origine)
            for (i in FRAME - 1 downTo 1) frame[i] -= PREEMPH * frame[i - 1]
            frame[0] -= PREEMPH * frame[0]
            for (i in 0 until FRAME) {
                re[i] = frame[i] * window[i]
                im[i] = 0f
            }
            for (i in FRAME until NFFT) {
                re[i] = 0f
                im[i] = 0f
            }
            fft(re, im)
            for (k in 0 until NFFT / 2) power[k] = re[k] * re[k] + im[k] * im[k]
            val row = out[t]
            for (j in 0 until NUM_BINS) {
                val (first, weights) = banks[j]
                var e = 0.0
                for (w in weights.indices) e += power[first + w] * weights[w]
                row[j] = ln(max(e, EPS)).toFloat()
            }
        }
        return out
    }

    /** Soustraction de la moyenne temporelle par bande (normalisation cepstrale attendue par WeSpeaker). */
    fun meanNormalize(feats: Array<FloatArray>): Array<FloatArray> {
        if (feats.isEmpty()) return feats
        val mean = FloatArray(NUM_BINS)
        for (f in feats) for (j in 0 until NUM_BINS) mean[j] += f[j]
        for (j in 0 until NUM_BINS) mean[j] /= feats.size
        return Array(feats.size) { t -> FloatArray(NUM_BINS) { j -> feats[t][j] - mean[j] } }
    }

    /** FFT radix-2 en place (NFFT = 512). */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f
                var ci = 0f
                for (k in 0 until len / 2) {
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr
                    im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr
                    im[i + k + len / 2] = ui - vi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** Norme L2 (utilitaire des empreintes). */
    fun l2(v: FloatArray): Float {
        var s = 0.0
        for (x in v) s += x.toDouble() * x
        return sqrt(s).toFloat()
    }
}
