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
            arr.put(
                JSONObject()
                    .put("s", seg.startMs)
                    .put("e", seg.endMs)
                    .put("t", seg.text.trim())
                    .put("sp", speakerIds.getOrElse(i) { 1 })
            )
        }
        return JSONObject().put("segments", arr).toString()
    }

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
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
