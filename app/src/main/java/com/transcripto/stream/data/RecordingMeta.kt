package com.transcripto.stream.data

import com.transcripto.stream.stt.SegmentData
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
    /** Actions à mener issues de la synthèse (ou saisies), avec leur état. */
    val actions: List<ActionItem> = emptyList(),
    /** Clés (texte replié) des actions supprimées à la main : une nouvelle synthèse ne les recrée pas. */
    val dismissedActions: List<String> = emptyList(),
    /**
     * Empreinte vocale (centroïde normalisé) de chaque intervenant, calculée à la transcription
     * différée — mémoire des voix : un intervenant nommé ici est reconnu dans les enregistrements
     * suivants. Comparer par [voicesContentEquals], pas par `==` (tableaux).
     */
    val voices: Map<Int, FloatArray> = emptyMap(),
) {
    val isEmpty: Boolean
        get() = speakers.isEmpty() && dossier.isBlank() && template.isBlank() && chapters.isEmpty() &&
            !segmentsStale && actions.isEmpty() && dismissedActions.isEmpty() && voices.values.all { it.isEmpty() }

    /** Égalité de contenu des empreintes (les tableaux se comparent par référence dans `==`). */
    fun voicesContentEquals(other: Map<Int, FloatArray>): Boolean =
        voices.keys == other.keys && voices.all { (id, v) -> other[id]?.contentEquals(v) == true }

    /** Actions non faites, dans l'ordre. */
    val openActions: List<ActionItem> get() = actions.filter { !it.done }
}

/** Un chapitre : instant de début et titre court. */
data class Chapter(val startMs: Long, val title: String)

/**
 * Une action à mener suivie : texte, responsable et échéance (libellé tel que repéré,
 * plus l'instant si la date a pu être résolue), état, date de création.
 */
data class ActionItem(
    val id: String,
    val text: String,
    val owner: String = "",
    val dueLabel: String = "",
    /** Échéance résolue en millisecondes (0 = inconnue). */
    val dueAt: Long = 0L,
    val done: Boolean = false,
    val createdAt: Long = 0L,
)

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
        if (meta.actions.isNotEmpty()) {
            val actions = JSONArray()
            meta.actions.forEach { a ->
                val j = JSONObject().put("id", a.id).put("t", a.text.trim())
                if (a.owner.isNotBlank()) j.put("o", a.owner.trim())
                if (a.dueLabel.isNotBlank()) j.put("d", a.dueLabel.trim())
                if (a.dueAt > 0) j.put("da", a.dueAt)
                if (a.done) j.put("done", true)
                if (a.createdAt > 0) j.put("c", a.createdAt)
                actions.put(j)
            }
            o.put("actions", actions)
        }
        if (meta.dismissedActions.isNotEmpty()) o.put("dismissed", JSONArray(meta.dismissedActions))
        if (meta.voices.isNotEmpty()) {
            val voices = JSONObject()
            meta.voices.forEach { (id, v) ->
                if (v.isEmpty()) return@forEach
                val arr = JSONArray()
                // 4 décimales : ~3,5 Ko par intervenant, précision très suffisante pour une similarité cosinus
                v.forEach { x -> arr.put(Math.round(x.toDouble() * 10000.0) / 10000.0) }
                voices.put(id.toString(), arr)
            }
            o.put("voices", voices)
        }
        return o.toString()
    }

    /**
     * Métadonnées vides si le JSON est absent ou illisible. [withVoices] = false saute les
     * empreintes (quelques Ko par intervenant) quand seuls les noms servent — liste, fiche dossier.
     */
    fun fromJson(json: String?, withVoices: Boolean = true): RecordingMeta {
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
            val actions = ArrayList<ActionItem>()
            o.optJSONArray("actions")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val a = arr.optJSONObject(i) ?: continue
                    val text = a.optString("t", "").trim()
                    val id = a.optString("id", "").trim()
                    if (text.isEmpty() || id.isEmpty()) continue
                    actions += ActionItem(
                        id = id,
                        text = text,
                        owner = a.optString("o", "").trim(),
                        dueLabel = a.optString("d", "").trim(),
                        dueAt = a.optLong("da", 0L).coerceAtLeast(0L),
                        done = a.optBoolean("done", false),
                        createdAt = a.optLong("c", 0L).coerceAtLeast(0L),
                    )
                }
            }
            val voices = LinkedHashMap<Int, FloatArray>()
            if (withVoices) o.optJSONObject("voices")?.let { vo ->
                vo.keys().forEach { k ->
                    val id = k.toIntOrNull() ?: return@forEach
                    val arr = vo.optJSONArray(k) ?: return@forEach
                    if (arr.length() == 0) return@forEach
                    voices[id] = FloatArray(arr.length()) { arr.optDouble(it, 0.0).toFloat() }
                }
            }
            RecordingMeta(
                speakers = speakers,
                dossier = o.optString("dossier", "").trim(),
                template = o.optString("template", "").trim(),
                chapters = chapters.sortedBy { it.startMs },
                segmentsStale = o.optBoolean("stale", false),
                actions = actions,
                dismissedActions = o.optJSONArray("dismissed")?.let { arr -> (0 until arr.length()).map { arr.optString(it, "") }.filter { it.isNotBlank() } } ?: emptyList(),
                voices = voices,
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

    /** Résultat de [remap] : noms sur la nouvelle numérotation, et noms qu'aucun intervenant ne reprend. */
    data class Remap(val names: Map<Int, String>, val dropped: List<String>)

    /** Part minimale du temps de parole d'un ancien intervenant que doit reprendre son successeur. */
    const val REMAP_MIN_SHARE = 0.6

    /**
     * Re-transcription : la numérotation des intervenants peut changer (autre diarisation, autre
     * nombre attendu). Chaque ancien intervenant nommé est projeté, par recouvrement temporel de ses
     * anciens passages ([old]) sur les nouveaux ([segments] étiquetés [ids]), vers le nouvel
     * intervenant qui reprend la majorité ([REMAP_MIN_SHARE]) de son temps de parole. Un nouvel
     * intervenant ne reçoit qu'un nom (le recouvrement le plus long gagne) ; les noms sans
     * successeur net sont abandonnés plutôt qu'attribués au hasard.
     */
    fun remap(old: List<StoredSegment>, names: Map<Int, String>, segments: List<SegmentData>, ids: List<Int>): Remap {
        if (names.isEmpty()) return Remap(emptyMap(), emptyList())
        if (old.isEmpty() || segments.isEmpty() || ids.size != segments.size) return Remap(names, emptyList())
        // overlap[ancien][nouveau] = ms de recouvrement
        val overlap = HashMap<Int, HashMap<Int, Long>>()
        val sortedNew = segments.indices.sortedBy { segments[it].startMs }
        for (o in old) {
            if (o.speaker !in names || o.endMs <= o.startMs) continue
            val row = overlap.getOrPut(o.speaker) { HashMap() }
            for (j in sortedNew) {
                val s = segments[j]
                if (s.startMs >= o.endMs) break
                val ov = minOf(o.endMs, s.endMs) - maxOf(o.startMs, s.startMs)
                if (ov > 0) row[ids[j]] = (row[ids[j]] ?: 0L) + ov
            }
        }
        data class Candidate(val oldId: Int, val newId: Int, val ms: Long)
        val candidates = ArrayList<Candidate>()
        for ((oldId, row) in overlap) {
            val total = row.values.sum()
            if (total <= 0L) continue
            val (newId, ms) = row.maxByOrNull { it.value }!!
            if (ms.toDouble() / total >= REMAP_MIN_SHARE) candidates += Candidate(oldId, newId, ms)
        }
        val out = LinkedHashMap<Int, String>()
        val usedOld = HashSet<Int>()
        val usedNames = HashSet<String>()
        for (c in candidates.sortedByDescending { it.ms }) {
            val name = names.getValue(c.oldId)
            if (c.newId in out || c.oldId in usedOld || name.trim().lowercase() in usedNames) continue
            out[c.newId] = name
            usedOld += c.oldId
            usedNames += name.trim().lowercase()
        }
        val dropped = names.filterKeys { it !in usedOld }.values.filter { it.isNotBlank() }
        return Remap(out.toSortedMap(), dropped)
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
