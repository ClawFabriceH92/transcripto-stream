package com.transcripto.stream.data

import java.io.File
import java.text.Normalizer

/** Passage trouvé par la recherche : enregistrement + segment (ou ligne) qui contient les termes. */
data class SearchHit(
    val file: File,
    val baseName: String,
    val dossier: String,
    val hasAudio: Boolean,
    val modifiedAt: Long,
    val speakerNames: Map<Int, String>,
    /** Position du segment dans le .json (ou de la ligne dans le .txt). */
    val index: Int,
    /** Début du passage, ou -1 si inconnu (transcription texte seul). */
    val startMs: Long,
    val speaker: Int,
    val text: String,
)

/** Ce que l'index doit savoir d'un enregistrement pour décider de le (ré)indexer. */
data class IndexSource(
    val file: File,
    /** Date du contenu textuel (max des .txt/.json) : un changement force la réindexation. */
    val contentStamp: Long,
    val baseName: String,
    val dossier: String,
    val hasAudio: Boolean,
    val speakerNames: Map<Int, String>,
    val modifiedAt: Long,
)

/** Repli de casse et d'accents pour comparer sans tenir compte des diacritiques. */
object TextFold {
    private val MARKS = Regex("\\p{M}+")

    /** « Écriture d'Été » → « ecriture d'ete » ; ligatures œ/æ décomposées (NFD ne le fait pas). */
    fun fold(s: String): String =
        MARKS.replace(Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD), "")
            .replace("œ", "oe").replace("æ", "ae")

    /** Termes de la requête (repliés, non vides, contenant au moins une lettre ou un chiffre). */
    fun terms(query: String): List<String> =
        fold(query).split(Regex("\\s+"))
            .map { it.trim('«', '»', '"', '\'', ',', ';', '.', '!', '?', '(', ')', ':', '…', '—', '-') }
            .filter { t -> t.any { it.isLetterOrDigit() } }
}

/**
 * Index de recherche EN MÉMOIRE de tous les passages : rien n'est persisté en clair
 * (compatible avec le chiffrement des textes au repos), reconstruit à la demande
 * depuis les fichiers et rafraîchi seulement pour les enregistrements modifiés.
 * Recherche par sous-chaînes repliées (accents/casse ignorés) : « provision 12 »
 * trouve les passages contenant les deux termes. Pur Kotlin, testable.
 */
class SearchIndex {

    private class Entry(
        val source: IndexSource,
        val segments: List<StoredSegment>,
        val folded: List<String>,
    )

    private val entries = HashMap<String, Entry>()

    /** Nombre d'enregistrements indexés. */
    val size: Int get() = synchronized(entries) { entries.size }

    /**
     * Met l'index en phase avec [sources] : retire les enregistrements disparus,
     * (ré)indexe ceux dont le contenu a changé via [loader]. Retourne le nombre
     * d'enregistrements relus.
     */
    fun sync(sources: List<IndexSource>, loader: (File) -> List<StoredSegment>): Int {
        var reloaded = 0
        val wanted = sources.associateBy { it.file.absolutePath }
        val toLoad = ArrayList<IndexSource>()
        synchronized(entries) {
            entries.keys.retainAll(wanted.keys)
            for ((path, src) in wanted) {
                val cur = entries[path]
                if (cur == null || cur.source.contentStamp != src.contentStamp) {
                    toLoad += src
                } else if (cur.source != src) {
                    // Métadonnées (nom, dossier, intervenants) changées sans le texte : pas de relecture
                    entries[path] = Entry(src, cur.segments, cur.folded)
                }
            }
        }
        for (src in toLoad) {
            val segs = try {
                loader(src.file)
            } catch (e: Exception) {
                emptyList()
            }
            val entry = Entry(src, segs, segs.map { TextFold.fold(it.text) })
            synchronized(entries) { entries[src.file.absolutePath] = entry }
            reloaded++
        }
        return reloaded
    }

    /** Chemins des enregistrements dont au moins un passage contient tous les termes. */
    fun matchingFiles(query: String): Set<String> {
        val terms = TextFold.terms(query)
        if (terms.isEmpty()) return emptySet()
        synchronized(entries) {
            return entries.filterValues { e -> e.folded.any { f -> terms.all { f.contains(it) } } }.keys.toSet()
        }
    }

    /**
     * Passages contenant tous les termes, enregistrements les plus récents d'abord,
     * puis ordre chronologique dans l'enregistrement ; au plus [perRecording] par
     * enregistrement et [limit] au total.
     */
    fun search(query: String, limit: Int = 80, perRecording: Int = 12): List<SearchHit> {
        val terms = TextFold.terms(query)
        if (terms.isEmpty()) return emptyList()
        val out = ArrayList<SearchHit>()
        synchronized(entries) {
            val ordered = entries.values.sortedByDescending { it.source.modifiedAt }
            for (e in ordered) {
                var n = 0
                for (i in e.folded.indices) {
                    if (!terms.all { e.folded[i].contains(it) }) continue
                    val seg = e.segments[i]
                    out += SearchHit(
                        file = e.source.file,
                        baseName = e.source.baseName,
                        dossier = e.source.dossier,
                        hasAudio = e.source.hasAudio,
                        modifiedAt = e.source.modifiedAt,
                        speakerNames = e.source.speakerNames,
                        index = i,
                        startMs = seg.startMs,
                        speaker = seg.speaker,
                        text = seg.text,
                    )
                    n++
                    if (n >= perRecording || out.size >= limit) break
                }
                if (out.size >= limit) break
            }
        }
        return out
    }

    fun clear() = synchronized(entries) { entries.clear() }

    companion object {
        /** Étiquette d'intervenant en tête de ligne — jamais un horodatage « [mm:ss] ». */
        private val SPEAKER_LINE = Regex("^\\[(?:Intervenant (\\d+)|[^\\]\\d][^\\]]*)\\]\\s*")
        private val CLOCK = Regex("^\\[(\\d{1,2}):(\\d{2})(?::(\\d{2}))?\\]\\s*")

        /**
         * Segments « de fortune » pour une transcription texte seul (pas de .json) : une
         * ligne = un passage, avec l'intervenant et l'horodatage si la ligne en porte.
         */
        fun segmentsFromText(body: String): List<StoredSegment> {
            val out = ArrayList<StoredSegment>()
            for (raw in body.lines()) {
                var line = raw.trim()
                if (line.isEmpty() || line.startsWith("---")) continue
                var speaker = 0
                SPEAKER_LINE.find(line)?.let { m ->
                    speaker = m.groupValues[1].toIntOrNull() ?: 0
                    line = line.substring(m.range.last + 1).trim()
                }
                var startMs = -1L
                CLOCK.find(line)?.let { m ->
                    val a = m.groupValues[1].toLong()
                    val b = m.groupValues[2].toLong()
                    val c = m.groupValues[3].toLongOrNull()
                    startMs = if (c != null) (a * 3600 + b * 60 + c) * 1000 else (a * 60 + b) * 1000
                    line = line.substring(m.range.last + 1).trim()
                }
                if (line.isEmpty()) continue
                out += StoredSegment(startMs, startMs, line, speaker)
            }
            return out
        }
    }
}
