package com.transcripto.stream.data

import com.transcripto.stream.stt.SegmentData
import org.json.JSONArray
import org.json.JSONObject

/** Un segment persisté : bornes temporelles, texte, intervenant estimé. */
data class StoredSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val speaker: Int,
    /** Confiance du moteur (0..1) ; -1 pour les transcriptions antérieures ou sans mesure. */
    val confidence: Float = -1f,
)

/**
 * Sérialisation JSON des segments horodatés produits par la transcription différée —
 * c'est ce qui alimente l'écran détail (taper un segment → écouter le passage).
 * Pur (org.json), testable en JVM.
 */
object SegmentsCodec {

    fun toJson(segments: List<SegmentData>, speakerIds: List<Int>): String {
        val arr = JSONArray()
        for (i in segments.indices) {
            val seg = segments[i]
            if (seg.text.isBlank()) continue
            val o = JSONObject()
                .put("s", seg.startMs)
                .put("e", seg.endMs)
                .put("t", seg.text.trim())
                .put("sp", speakerIds.getOrElse(i) { 1 })
            if (seg.confidence >= 0f) o.put("c", round3(seg.confidence))
            arr.put(o)
        }
        return JSONObject().put("segments", arr).toString()
    }

    /** Ré-sérialisation après correction d'un passage (écran détail). */
    fun toJsonStored(segments: List<StoredSegment>): String {
        val arr = JSONArray()
        for (seg in segments) {
            if (seg.text.isBlank()) continue
            val o = JSONObject()
                .put("s", seg.startMs)
                .put("e", seg.endMs)
                .put("t", seg.text.trim())
                .put("sp", seg.speaker)
            if (seg.confidence >= 0f) o.put("c", round3(seg.confidence))
            arr.put(o)
        }
        return JSONObject().put("segments", arr).toString()
    }

    private fun round3(v: Float): Double = Math.round(v * 1000.0) / 1000.0

    /** Retourne une liste vide si le JSON est illisible (fichier corrompu). */
    fun fromJson(json: String): List<StoredSegment> {
        return try {
            val arr = JSONObject(json).getJSONArray("segments")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                StoredSegment(
                    startMs = o.getLong("s"),
                    endMs = o.getLong("e"),
                    text = o.getString("t"),
                    speaker = o.optInt("sp", 1),
                    confidence = o.optDouble("c", -1.0).toFloat(),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
