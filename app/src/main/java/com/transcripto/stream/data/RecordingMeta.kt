package com.transcripto.stream.data

import org.json.JSONObject

/**
 * Métadonnées d'un enregistrement, stockées dans le fichier frère « base.meta »
 * (JSON) : noms des intervenants, dossier/client, gabarit de synthèse.
 * Le transcript conserve les libellés génériques « [Intervenant N] » : les noms
 * sont appliqués à l'affichage, au partage et aux exports (renommable à tout moment).
 */
data class RecordingMeta(
    val speakers: Map<Int, String> = emptyMap(),
    val dossier: String = "",
    val template: String = "",
) {
    val isEmpty: Boolean get() = speakers.isEmpty() && dossier.isBlank() && template.isBlank()
}

/** Sérialisation JSON des métadonnées — pur (org.json), testable en JVM. */
object MetaCodec {

    fun toJson(meta: RecordingMeta): String {
        val speakers = JSONObject()
        meta.speakers.forEach { (id, name) -> if (name.isNotBlank()) speakers.put(id.toString(), name.trim()) }
        return JSONObject()
            .put("speakers", speakers)
            .put("dossier", meta.dossier.trim())
            .put("template", meta.template.trim())
            .toString()
    }

    /** Métadonnées vides si le JSON est absent ou illisible. */
    fun fromJson(json: String?): RecordingMeta {
        if (json.isNullOrBlank()) return RecordingMeta()
        return try {
            val o = JSONObject(json)
            val speakers = LinkedHashMap<Int, String>()
            o.optJSONObject("speakers")?.let { sp ->
                sp.keys().forEach { k ->
                    val id = k.toIntOrNull() ?: return@forEach
                    val name = sp.optString(k, "").trim()
                    if (name.isNotEmpty()) speakers[id] = name
                }
            }
            RecordingMeta(
                speakers = speakers,
                dossier = o.optString("dossier", "").trim(),
                template = o.optString("template", "").trim(),
            )
        } catch (e: Exception) {
            RecordingMeta()
        }
    }
}

/** Application des noms d'intervenants aux textes qui portent les libellés génériques. */
object SpeakerNames {

    private val TAG = Regex("\\[Intervenant (\\d+)\\]")
    private val STATS = Regex("^Intervenant (\\d+) : ", RegexOption.MULTILINE)

    /** « Intervenant 2 » ou le nom choisi (« Mme Durand (DAF) »). */
    fun label(speaker: Int, names: Map<Int, String>): String =
        names[speaker]?.takeIf { it.isNotBlank() } ?: "Intervenant $speaker"

    /**
     * Remplace « [Intervenant N] » et les lignes « Intervenant N : » du bloc temps de
     * parole par le nom choisi. Les intervenants sans nom restent inchangés.
     */
    fun apply(text: String, names: Map<Int, String>): String {
        if (names.isEmpty() || text.isEmpty()) return text
        val tagged = TAG.replace(text) { m ->
            val id = m.groupValues[1].toIntOrNull()
            val name = id?.let { names[it] }?.takeIf { it.isNotBlank() }
            if (name != null) "[$name]" else m.value
        }
        return STATS.replace(tagged) { m ->
            val id = m.groupValues[1].toIntOrNull()
            val name = id?.let { names[it] }?.takeIf { it.isNotBlank() }
            if (name != null) "$name : " else m.value
        }
    }
}
