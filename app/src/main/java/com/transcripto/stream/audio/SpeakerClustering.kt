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
        val m = vecs.size
        val target = expected?.coerceIn(1, MAX_SPEAKERS)
        // Similarités calculées une seule fois. sum[g][h] = somme des similarités entre les membres
        // des groupes g et h (lien moyen = sum / (|g|·|h|)), entretenue à chaque fusion par la
        // récurrence de Lance–Williams : O(m²) par fusion, indépendant de la dimension.
        val sum = Array(m) { FloatArray(m) }
        for (i in 0 until m) for (j in i + 1 until m) {
            val s = cosine(vecs[i], vecs[j])
            sum[i][j] = s
            sum[j][i] = s
        }
        val members = Array<MutableList<Int>>(m) { i -> mutableListOf(i) }
        val alive = BooleanArray(m) { true }
        var count = m
        while (count > 1) {
            var bi = -1
            var bj = -1
            var best = Float.NEGATIVE_INFINITY
            for (g in 0 until m) {
                if (!alive[g]) continue
                val row = sum[g]
                val sg = members[g].size
                for (h in g + 1 until m) {
                    if (!alive[h]) continue
                    val avg = row[h] / (sg * members[h].size)
                    if (avg > best) {
                        best = avg
                        bi = g
                        bj = h
                    }
                }
            }
            // Nombre imposé : fusionner jusqu'à l'atteindre ; auto : s'arrêter sous le seuil,
            // mais jamais plus de MAX_SPEAKERS groupes
            val done = if (target != null) count <= target else (best < threshold && count <= MAX_SPEAKERS)
            if (done) break
            for (k in 0 until m) {
                if (!alive[k] || k == bi || k == bj) continue
                sum[bi][k] += sum[bj][k]
                sum[k][bi] = sum[bi][k]
            }
            members[bi].addAll(members[bj])
            members[bj].clear()
            alive[bj] = false
            count--
        }
        val groups = members.indices.filter { alive[it] }.map { members[it] }
        val labelOfVec = IntArray(m)
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

    /** Similarité cosinus entre deux empreintes (normalisées ou non) ; 0 si les tailles diffèrent. */
    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
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
        val dim = vectors.firstOrNull { it.isNotEmpty() }?.size ?: return null
        val c = FloatArray(dim)
        for (v in vectors) if (v.size == dim) for (i in 0 until dim) c[i] += v[i]
        return normalize(c)
    }

    private fun normalize(v: FloatArray): FloatArray {
        val n = Fbank.l2(v)
        return if (n == 0f) v.copyOf() else FloatArray(v.size) { v[it] / n }
    }

}
