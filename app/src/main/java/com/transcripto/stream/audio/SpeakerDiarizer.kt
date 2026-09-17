package com.transcripto.stream.audio

import com.transcripto.stream.stt.SegmentData

/**
 * Attribution des intervenants par empreintes vocales : une empreinte par passage
 * d'au moins une seconde (les passages plus courts suivent leur voisin), regroupement
 * ([SpeakerClustering]), puis centroïde par intervenant pour la mémoire des voix.
 * Pur hormis l'encodeur injecté ([VoiceEmbedder]) : testable avec un encodeur factice.
 */
object SpeakerDiarizer {

    /** Similarité minimale entre deux passages d'un même locuteur (empreintes CAM++ sur des passages courts). */
    const val THRESHOLD = 0.50f
    /** Similarité à partir de laquelle une voix mémorisée est reconnue. */
    const val RECOGNITION = 0.75f

    data class Result(val speakerIds: List<Int>, val voices: Map<Int, FloatArray>)

    /**
     * Intervenants (1..k) pour [segments] de [pcm] (16 kHz) ; [expected] impose le nombre
     * d'intervenants (null = automatique). Retourne aussi le centroïde de chaque intervenant.
     */
    fun assign(pcm: ShortArray, segments: List<SegmentData>, embedder: VoiceEmbedder, expected: Int? = null): Result {
        if (segments.isEmpty()) return Result(emptyList(), emptyMap())
        val embeddings = segments.map { seg ->
            val s = ((seg.startMs * Fbank.SAMPLE_RATE) / 1000L).toInt().coerceIn(0, pcm.size)
            val e = ((seg.endMs * Fbank.SAMPLE_RATE) / 1000L).toInt().coerceIn(s, pcm.size)
            if (seg.text.isBlank()) null else embedder.embed(pcm, s, e)
        }
        val ids = SpeakerClustering.cluster(embeddings, expected, THRESHOLD)
        val voices = LinkedHashMap<Int, FloatArray>()
        ids.toSet().sorted().forEach { id ->
            val vecs = embeddings.indices.filter { ids[it] == id && embeddings[it] != null }.map { embeddings[it]!! }
            SpeakerClustering.centroid(vecs)?.let { voices[id] = it }
        }
        return Result(ids, voices)
    }

    /**
     * Voix reconnues : pour chaque intervenant de [voices], le nom mémorisé le plus proche
     * parmi [known] (nom → centroïde) si la similarité atteint [RECOGNITION] ; un nom n'est
     * attribué qu'à un seul intervenant (le plus proche).
     */
    fun recognise(voices: Map<Int, FloatArray>, known: Map<String, FloatArray>): Map<Int, String> {
        if (voices.isEmpty() || known.isEmpty()) return emptyMap()
        val candidates = ArrayList<Triple<Int, String, Float>>()
        for ((id, v) in voices) for ((name, k) in known) {
            val s = SpeakerClustering.cosine(v, k)
            if (s >= RECOGNITION) candidates += Triple(id, name, s)
        }
        val out = LinkedHashMap<Int, String>()
        val usedNames = HashSet<String>()
        for ((id, name, _) in candidates.sortedByDescending { it.third }) {
            if (id in out || name in usedNames) continue
            out[id] = name
            usedNames += name
        }
        return out
    }
}
