package com.transcripto.stream.data

import org.json.JSONArray
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
    /** Chapitres (début + titre), détectés localement ou par l'IA ; vide = pas encore détectés. */
    val chapters: List<Chapter> = emptyList(),
    /** Le .txt a été corrigé à la main sans que les segments (.json) puissent être réalignés. */
    val segmentsStale: Boolean = false,
) {
    val isEmpty: Boolean
        get() = speakers.isEmpty() && dossier.isBlank() && template.isBlank() && chapters.isEmpty() && !segmentsStale
}

/** Un chapitre : instant de début et titre court. */
data class Chapter(val startMs: Long, val title: String)

/** Sérialisation JSON des métadonnées — pur (org.json), testable en JVM. */
object MetaCodec {

    fun toJson(meta: RecordingMeta): String {
        val speakers = JSONObject()
        meta.speakers.forEach { (id, name) -> if (name.isNotBlank()) speakers.put(id.toString(), name.trim()) }
        val chapters = JSONArray()
        meta.chapters.forEach { c -> chapters.put(JSONObject().put("s", c.startMs).put("t", c.title.trim())) }
        val o = JSONObject()
            .put("speakers", speakers)
            .put("dossier", meta.dossier.trim())
            .put("template", meta.template.trim())
            .put("chapters", chapters)
        if (meta.segmentsStale) o.put("stale", true)
        return o.toString()
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
            val chapters = ArrayList<Chapter>()
            o.optJSONArray("chapters")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val c = arr.optJSONObject(i) ?: continue
                    val title = c.optString("t", "").trim()
                    if (title.isNotEmpty()) chapters += Chapter(c.optLong("s", 0L).coerceAtLeast(0L), title)
                }
            }
            RecordingMeta(
                speakers = speakers,
                dossier = o.optString("dossier", "").trim(),
                template = o.optString("template", "").trim(),
                chapters = chapters.sortedBy { it.startMs },
                segmentsStale = o.optBoolean("stale", false),
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

    /**
     * Opération inverse de [apply] : « [Nom] » → « [Intervenant N] » et « Nom : » en début
     * de ligne → « Intervenant N : », pour réécrire un texte affiché avec les noms sans
     * figer ceux-ci dans le fichier. Les noms les plus longs sont traités en premier
     * (« M. Martin (DG) » avant « M. Martin »).
     */
    fun unapply(text: String, names: Map<Int, String>): String {
        if (names.isEmpty() || text.isEmpty()) return text
        var out = text
        names.entries
            .filter { it.value.isNotBlank() }
            .sortedByDescending { it.value.length }
            .forEach { (id, name) ->
                val n = Regex.escape(name.trim())
                out = out.replace(Regex("\\[$n\\]"), Regex.escapeReplacement("[Intervenant $id]"))
                out = out.replace(Regex("^$n : ", RegexOption.MULTILINE), Regex.escapeReplacement("Intervenant $id : "))
            }
        return out
    }
}
