package com.transcripto.stream.audio

/**
 * Regroupement des empreintes de locuteur : agglomératif (lien moyen) sur la similarité
 * cosinus. Le nombre de locuteurs est choisi automatiquement par un seuil de similarité,
 * ou imposé ([expected] entre 1 et 6). Les passages sans empreinte (trop courts) prennent
 * le locuteur du voisin précédent (ou suivant en tête). Pur, testé.
 */
object SpeakerClustering {

    /** Similarité cosinus en dessous de laquelle deux groupes ne fusionnent plus (empreintes normalisées). */
    const val DEFAULT_THRESHOLD = 0.60f
    const val MAX_SPEAKERS = 6

    /**
     * Étiquettes 1..k pour chaque empreinte de [embeddings] (null = passage sans empreinte),
     * numérotées par ordre de première apparition.
     */
    fun cluster(embeddings: List<FloatArray?>, expected: Int? = null, threshold: Float = DEFAULT_THRESHOLD): List<Int> {
        val n = embeddings.size
        if (n == 0) return emptyList()
        val valid = embeddings.indices.filter { embeddings[it] != null }
        if (valid.isEmpty()) return List(n) { 1 }
        val vecs = valid.map { normalize(embeddings[it]!!) }
        val target = expected?.coerceIn(1, MAX_SPEAKERS)
        // Groupes : listes d'indices dans « vecs »
        val groups = ArrayList<MutableList<Int>>(vecs.indices.map { mutableListOf(it) })
        val cap = if (target != null) target else 1
        while (groups.size > cap) {
            var bi = -1
            var bj = -1
            var best = -2f
            for (i in groups.indices) for (j in i + 1 until groups.size) {
                val s = averageSimilarity(vecs, groups[i], groups[j])
                if (s > best) {
                    best = s
                    bi = i
                    bj = j
                }
            }
            if (target == null && best < threshold) break
            if (target == null && groups.size <= MAX_SPEAKERS && best < threshold) break
            groups[bi].addAll(groups[bj])
            groups.removeAt(bj)
            if (target == null && groups.size <= MAX_SPEAKERS && (groups.size == 1)) break
        }
        // Auto : borne haute de six locuteurs même sous le seuil
        while (target == null && groups.size > MAX_SPEAKERS) {
            var bi = -1; var bj = -1; var best = -2f
            for (i in groups.indices) for (j in i + 1 until groups.size) {
                val s = averageSimilarity(vecs, groups[i], groups[j])
                if (s > best) { best = s; bi = i; bj = j }
            }
            groups[bi].addAll(groups[bj]); groups.removeAt(bj)
        }
        val labelOfVec = IntArray(vecs.size)
        // Numérotation par ordre de première apparition
        val firstIndex = groups.map { g -> g.minOrNull()!! }
        val order = groups.indices.sortedBy { firstIndex[it] }
        order.forEachIndexed { rank, gi -> groups[gi].forEach { labelOfVec[it] = rank + 1 } }
        val out = IntArray(n) { 0 }
        valid.forEachIndexed { k, idx -> out[idx] = labelOfVec[k] }
        // Passages sans empreinte : voisin précédent, sinon suivant
        var last = 0
        for (i in 0 until n) if (out[i] != 0) last = out[i] else if (last != 0) out[i] = last
        var next = 0
        for (i in n - 1 downTo 0) if (out[i] != 0) next = out[i] else out[i] = if (next != 0) next else 1
        return out.toList()
    }

    /** Similarité cosinus entre deux empreintes (normalisées ou non). */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i]
            na += a[i].toDouble() * a[i]
            nb += b[i].toDouble() * b[i]
        }
        if (na == 0.0 || nb == 0.0) return 0f
        return (dot / Math.sqrt(na * nb)).toFloat()
    }

    /** Centroïde normalisé d'un groupe d'empreintes (mémoire des voix). */
    fun centroid(vectors: List<FloatArray>): FloatArray? {
        if (vectors.isEmpty()) return null
        val dim = vectors[0].size
        val c = FloatArray(dim)
        for (v in vectors) for (i in 0 until dim) c[i] += v[i]
        return normalize(c)
    }

    private fun normalize(v: FloatArray): FloatArray {
        val n = Fbank.l2(v)
        return if (n == 0f) v.copyOf() else FloatArray(v.size) { v[it] / n }
    }

    private fun averageSimilarity(vecs: List<FloatArray>, a: List<Int>, b: List<Int>): Float {
        var s = 0.0
        for (i in a) for (j in b) s += cosine(vecs[i], vecs[j])
        return (s / (a.size * b.size)).toFloat()
    }
}
