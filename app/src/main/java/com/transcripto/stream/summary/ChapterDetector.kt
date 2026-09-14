package com.transcripto.stream.summary

import com.transcripto.stream.data.Chapter
import com.transcripto.stream.data.StoredSegment
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Chapitres automatiques SANS modèle : la transcription est découpée en blocs
 * d'environ une minute ; à chaque frontière, on compare le vocabulaire des deux
 * blocs précédents à celui des deux suivants (cosinus sur des poids tf-idf).
 * Les frontières où le vocabulaire bascule le plus deviennent des débuts de
 * chapitre (durée minimale respectée) ; le titre reprend les termes les plus
 * caractéristiques du chapitre. Pur Kotlin, testable.
 */
object ChapterDetector {

    private const val BLOCK_MS = 60_000L
    private const val MIN_CHAPTER_MS = 120_000L
    private const val MIN_TOTAL_MS = 4 * 60_000L
    private const val MAX_CHAPTERS = 12
    /** Distance (1 − cosinus) minimale pour ouvrir un chapitre. */
    private const val MIN_SHIFT = 0.55

    private class Block(val startMs: Long, val endMs: Long, val firstSegment: Int) {
        val tf = HashMap<String, Int>()
        val surface = HashMap<String, String>()
    }

    fun detect(segments: List<StoredSegment>): List<Chapter> {
        val segs = segments.filter { it.text.isNotBlank() && it.startMs >= 0 }
        if (segs.size < 8) return emptyList()
        val totalMs = segs.last().endMs.coerceAtLeast(segs.last().startMs) - segs.first().startMs
        if (totalMs < MIN_TOTAL_MS) return emptyList()

        // 1. Blocs d'environ une minute
        val blocks = ArrayList<Block>()
        var cur: Block? = null
        for ((i, seg) in segs.withIndex()) {
            if (cur == null || seg.startMs - cur.startMs >= BLOCK_MS) {
                cur = Block(seg.startMs, seg.endMs, i).also { blocks += it }
            }
            for ((stem, word) in LocalSummarizer.tokenPairs(seg.text)) {
                cur.tf[stem] = (cur.tf[stem] ?: 0) + 1
                cur.surface.putIfAbsent(stem, word)
            }
        }
        if (blocks.size < 4) return emptyList()

        // 2. idf sur les blocs
        val df = HashMap<String, Int>()
        blocks.forEach { b -> b.tf.keys.forEach { df[it] = (df[it] ?: 0) + 1 } }
        val n = blocks.size.toDouble()
        fun idf(term: String) = ln(1.0 + n / (df[term] ?: 1))

        // 3. Bascule de vocabulaire à chaque frontière (contexte 2 blocs de chaque côté)
        val shift = DoubleArray(blocks.size) // shift[i] = distance entre [i-2,i-1] et [i,i+1]
        for (i in 1 until blocks.size) {
            val left = merge(blocks, maxOf(0, i - 2), i - 1)
            val right = merge(blocks, i, minOf(blocks.size - 1, i + 1))
            shift[i] = 1.0 - cosine(left, right, ::idf)
        }

        // 4. Frontières retenues : maxima locaux les plus marqués, espacés d'au moins MIN_CHAPTER_MS
        val candidates = (1 until blocks.size)
            .filter { i ->
                shift[i] >= MIN_SHIFT &&
                    shift[i] >= shift[i - 1] &&
                    (i + 1 >= blocks.size || shift[i] >= shift[i + 1])
            }
            .sortedByDescending { shift[it] }
        val starts = ArrayList<Long>()
        starts += segs.first().startMs
        for (i in candidates) {
            if (starts.size >= MAX_CHAPTERS) break
            val t = blocks[i].startMs
            val farEnough = starts.all { kotlin.math.abs(it - t) >= MIN_CHAPTER_MS } &&
                (segs.last().endMs - t) >= MIN_CHAPTER_MS / 2
            if (farEnough) starts += t
        }
        starts.sort()
        if (starts.size < 2) return emptyList()

        // 5. Titres : termes caractéristiques du chapitre (tf × idf chapitre)
        val chapterTf = starts.indices.map { HashMap<String, Int>() }
        val chapterSurface = HashMap<String, String>()
        for (b in blocks) {
            val idx = starts.indexOfLast { it <= b.startMs }.coerceAtLeast(0)
            b.tf.forEach { (t, c) -> chapterTf[idx][t] = (chapterTf[idx][t] ?: 0) + c }
            b.surface.forEach { (t, w) -> chapterSurface.putIfAbsent(t, w) }
        }
        val cdf = HashMap<String, Int>()
        chapterTf.forEach { m -> m.keys.forEach { cdf[it] = (cdf[it] ?: 0) + 1 } }
        val nc = chapterTf.size.toDouble()
        return starts.mapIndexed { idx, startMs ->
            val title = chapterTf[idx].entries
                .sortedByDescending { (t, c) -> c * ln(1.0 + nc / (cdf[t] ?: 1)) }
                .take(3)
                .map { (t, _) -> (chapterSurface[t] ?: t).replaceFirstChar { it.uppercaseChar() } }
                .joinToString(" · ")
            Chapter(startMs, title.ifBlank { "Chapitre ${idx + 1}" })
        }
    }

    private fun merge(blocks: List<Block>, from: Int, to: Int): Map<String, Int> {
        val m = HashMap<String, Int>()
        for (i in from..to) blocks[i].tf.forEach { (t, c) -> m[t] = (m[t] ?: 0) + c }
        return m
    }

    private fun cosine(a: Map<String, Int>, b: Map<String, Int>, idf: (String) -> Double): Double {
        // Une minute sans terme significatif (« oui, d'accord, merci ») n'est pas une bascule
        if (a.isEmpty() || b.isEmpty()) return 1.0
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for ((t, c) in a) {
            val w = c * idf(t)
            na += w * w
            val cb = b[t] ?: continue
            dot += w * (cb * idf(t))
        }
        for ((t, c) in b) {
            val w = c * idf(t)
            nb += w * w
        }
        if (na == 0.0 || nb == 0.0) return 1.0
        return dot / (sqrt(na) * sqrt(nb))
    }
}
