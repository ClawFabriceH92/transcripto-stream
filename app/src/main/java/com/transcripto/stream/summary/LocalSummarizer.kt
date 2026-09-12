package com.transcripto.stream.summary

import com.transcripto.stream.export.TranscriptExporter
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Données d'entrée d'une synthèse (locale ou IA). */
data class SummaryInput(
    val title: String,
    val transcript: String,
    val durationMs: Long,
    val dateLabel: String,
)

/**
 * Synthèse LOCALE d'une transcription, sans réseau ni modèle : extraction de
 * phrases (fréquence des termes, bonus pour les chiffres, les dates, les
 * formulations de décision/action et les passages marqués ⭐), puis rubriques
 * Markdown : points clés, décisions, actions, vigilance, chiffres et dates,
 * moments marqués, répartition de la parole, mots-clés.
 *
 * Pur Kotlin (testable en JVM). Les transcriptions vocales sont bruitées et
 * parfois sans ponctuation : les longues phrases sont redécoupées par blocs.
 */
object LocalSummarizer {

    private val STOPWORDS: Set<String> = (
        "le la les l un une des du de d et ou où mais donc or ni car que qui quoi dont est sont été être " +
            "avoir a ai as avons avez ont j je tu il elle on nous vous ils elles me te se moi toi lui leur " +
            "leurs mon ma mes ton ta tes son sa ses notre nos votre vos ce cet cette ces ça cela celui celle " +
            "ceux celles y en au aux pour par sur sous dans avec sans vers chez entre jusque jusqu depuis " +
            "pendant avant après comme ainsi alors aussi très trop peu plus moins bien mal tout tous toute " +
            "toutes rien quelque quelques chaque autre autres même mêmes tel telle tels telles si oui non " +
            "pas ne n c s qu t m là ici puis ensuite encore déjà toujours jamais souvent parfois faire fait " +
            "faites font fais faisons dit dire dis dites disons va vais vas allons allez vont aller peut " +
            "peux pouvons pouvez peuvent pourrait pourrais veut veux voulons voulez veulent voudrais " +
            "doit dois devons devez doivent devrait sait sais savons savez savent vraiment juste enfin " +
            "voilà voici bon ben euh hein hum ok okay accord parce the and or of to " +
            "in on at for with from by is are was were be been this that these those it its we you they " +
            "he she i my our your their not no yes so if then than as an into about just very also " +
            "have has had do does did will would can could should may might there here what which who"
        ).split(' ').filter { it.isNotBlank() }.toSet()

    private val DECISION = Regex(
        "(?i)\\b(décid|décision|valid[ée]|validons|convenu|d'accord pour|accord sur|retenu|on part sur|acté|tranch|entérin|approuv|adopt)"
    )
    private val ACTION = Regex(
        "(?i)\\b(il faut|il faudra|nous devons|on doit|on va |on devra|à faire|action|tâche|prévoir|planifi|envoyer|" +
            "transmettre|relancer|vérifier|préparer|finaliser|rédiger|contacter|rappeler|organiser|rendez-vous|" +
            "échéance|deadline|avant le|d'ici|au plus tard|prochaine étape|à confirmer|à valider|livrable|" +
            "s'occupe|se charge|prend en charge|à envoyer|à transmettre|à signer)"
    )
    private val VIGILANCE = Regex(
        "(?i)\\b(attention|risque|vigilance|problème|inquiét|alerte|litige|retard|non conforme|anomalie|écart|réserve|doute|à vérifier|bloqu)"
    )
    private val AMOUNT = Regex(
        "\\d[\\d\\s\\u00A0.,]*\\s?(?:k€|M€|€|euros?|%|millions?|milliers?|jours?|semaines?|mois|ans?|heures?|h\\b|min\\b)",
        RegexOption.IGNORE_CASE
    )
    private val DATE = Regex(
        "\\b(?:\\d{1,2}(?:er)?\\s+(?:janvier|février|mars|avril|mai|juin|juillet|août|septembre|octobre|novembre|décembre)(?:\\s+\\d{4})?" +
            "|\\d{1,2}/\\d{1,2}(?:/\\d{2,4})?" +
            "|(?:lundi|mardi|mercredi|jeudi|vendredi|samedi|dimanche)\\s+(?:prochain|dernier|\\d{1,2})" +
            "|(?:fin|début|mi)[- ](?:janvier|février|mars|avril|mai|juin|juillet|août|septembre|octobre|novembre|décembre|mois|année|semaine|trimestre)" +
            "|T[1-4]\\s?20\\d{2}|20\\d{2})\\b",
        RegexOption.IGNORE_CASE
    )
    private val MARKER = Regex("\\[⭐\\s?(\\d{1,2}:\\d{2}(?::\\d{2})?)\\]")
    private val SPEAKER = Regex("\\[Intervenant\\s+(\\d+)\\]")
    private val BRACKET = Regex("\\[[^\\]]*\\]")
    private val SPEAK_STATS_LINE = Regex("^Intervenant \\d+ : \\S+ \\(\\d+ %\\)$", RegexOption.MULTILINE)
    private val WORD = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}'’-]*")
    private val SENTENCE_SPLIT = Regex("(?<=[.!?…])\\s+|\\n+")
    private const val MARK = "MARQUEURETOILE"

    private class Sentence(val index: Int, val text: String, val marked: Boolean) {
        val tokens: List<String> = tokenize(text)
        var score = 0.0
    }

    fun summarize(input: SummaryInput): String {
        val raw = input.transcript.trim()
        val sb = StringBuilder()
        val speakerCount = SPEAKER.findAll(raw).map { it.groupValues[1] }.toSet().size
        val stats = SPEAK_STATS_LINE.findAll(raw).map { it.value }.toList()
        val markers = extractMarkers(raw)

        sb.append("# Synthèse — ").append(input.title.ifBlank { "Enregistrement" }).append('\n')
        sb.append('_').append(input.dateLabel)
        if (input.durationMs > 0) sb.append(" · ").append(TranscriptExporter.formatHms(input.durationMs))
        when {
            speakerCount > 1 -> sb.append(" · ").append(speakerCount).append(" intervenants")
            stats.size > 1 -> sb.append(" · ").append(stats.size).append(" intervenants")
        }
        sb.append(" · synthèse locale automatique_\n\n")

        val sentences = splitSentences(raw)
        if (sentences.isEmpty()) {
            sb.append("_Transcription trop courte pour une synthèse._\n")
            return sb.toString()
        }

        // Fréquence des termes (hors mots vides) → score de chaque phrase
        val tf = HashMap<String, Int>()
        sentences.forEach { s -> s.tokens.forEach { t -> tf[t] = (tf[t] ?: 0) + 1 } }
        sentences.forEach { s ->
            val uniq = s.tokens.toSet()
            var score = uniq.sumOf { (tf[it] ?: 0).toDouble() } /
                sqrt(s.tokens.size.toDouble().coerceAtLeast(1.0))
            if (AMOUNT.containsMatchIn(s.text) || DATE.containsMatchIn(s.text)) score *= 1.3
            if (DECISION.containsMatchIn(s.text) || ACTION.containsMatchIn(s.text)) score *= 1.4
            if (s.marked) score *= 1.6
            s.score = score
        }

        val used = HashSet<Int>()
        val decisions = pick(sentences, used, 6) { DECISION.containsMatchIn(it.text) }
        val actions = pick(sentences, used, 6) { ACTION.containsMatchIn(it.text) }
        val vigilance = pick(sentences, used, 4) { VIGILANCE.containsMatchIn(it.text) }

        val target = (sentences.size * 0.12).roundToInt().coerceIn(3, 8)
        val keyPoints = sentences.filter { it.index !in used }
            .sortedByDescending { it.score }
            .take(target)
            .sortedBy { it.index }
        keyPoints.forEach { used.add(it.index) }

        if (keyPoints.isNotEmpty()) {
            sb.append("## Points clés\n")
            keyPoints.forEach { sb.append("- ").append(clean(it.text)).append('\n') }
            sb.append('\n')
        }

        if (decisions.isNotEmpty()) {
            sb.append("## Décisions\n")
            decisions.forEach { sb.append("- ").append(clean(it.text)).append('\n') }
            sb.append('\n')
        }
        if (actions.isNotEmpty()) {
            sb.append("## Actions à mener\n")
            actions.forEach { sb.append("- ").append(clean(it.text)).append('\n') }
            sb.append('\n')
        }
        if (vigilance.isNotEmpty()) {
            sb.append("## Points de vigilance\n")
            vigilance.forEach { sb.append("- ").append(clean(it.text)).append('\n') }
            sb.append('\n')
        }

        val figures = extractFigures(sentences)
        if (figures.isNotEmpty()) {
            sb.append("## Chiffres et dates cités\n")
            figures.forEach { (value, snippet) ->
                sb.append("- **").append(value).append("** — ").append(snippet).append('\n')
            }
            sb.append('\n')
        }

        if (markers.isNotEmpty()) {
            sb.append("## Moments marqués ⭐\n")
            markers.forEach { (time, snippet) ->
                sb.append("- **").append(time).append("** — ")
                    .append(snippet.ifBlank { "(marqueur)" }).append('\n')
            }
            sb.append('\n')
        }

        if (stats.isNotEmpty()) {
            sb.append("## Répartition de la parole\n")
            stats.forEach { sb.append("- ").append(it).append('\n') }
            sb.append('\n')
        }

        val keywords = tf.entries
            .filter { it.value >= 2 && it.key.any { c -> c.isLetter() } }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(8)
            .map { it.key }
        if (keywords.isNotEmpty()) {
            sb.append("## Mots-clés\n")
            sb.append(keywords.joinToString(" · ")).append("\n\n")
        }

        sb.append("_Synthèse générée localement par extraction de phrases (aucune donnée envoyée). À relire avant diffusion._\n")
        return sb.toString()
    }

    private fun pick(
        sentences: List<Sentence>,
        used: MutableSet<Int>,
        max: Int,
        predicate: (Sentence) -> Boolean,
    ): List<Sentence> {
        val picked = sentences.filter { it.index !in used && predicate(it) }
            .sortedByDescending { it.score }
            .take(max)
            .sortedBy { it.index }
        picked.forEach { used.add(it.index) }
        return picked
    }

    /** Découpe en phrases : ponctuation ou retours à la ligne, redécoupage des blocs trop longs. */
    private fun splitSentences(raw: String): List<Sentence> {
        val withMarks = MARKER.replace(raw, " $MARK ")
        val noBrackets = BRACKET.replace(withMarks, " ")
        val body = SPEAK_STATS_LINE.replace(noBrackets, " ")
            .replace(Regex("---[^\\n]*---"), " ")
        val out = ArrayList<Sentence>()
        val seen = HashSet<String>()
        var index = 0
        for (chunk in SENTENCE_SPLIT.split(body)) {
            val marked = chunk.contains(MARK)
            val text = chunk.replace(MARK, " ").replace(Regex("\\s+"), " ").trim()
            if (text.isEmpty()) continue
            val words = text.split(' ')
            if (words.size < 4) continue
            val pieces = if (words.size > 60) words.chunked(25).map { it.joinToString(" ") } else listOf(text)
            for ((i, piece) in pieces.withIndex()) {
                val key = piece.lowercase()
                if (!seen.add(key)) continue
                out.add(Sentence(index++, piece, marked && i == 0))
            }
        }
        return out
    }

    private fun extractMarkers(raw: String): List<Pair<String, String>> =
        MARKER.findAll(raw).map { m ->
            val after = raw.substring(m.range.last + 1)
            val snippet = BRACKET.replace(after, " ").replace(Regex("\\s+"), " ").trim()
            m.groupValues[1] to shorten(snippet, 110)
        }.toList()

    private fun extractFigures(sentences: List<Sentence>): List<Pair<String, String>> {
        val out = LinkedHashMap<String, String>()
        for (s in sentences) {
            val matches = (AMOUNT.findAll(s.text) + DATE.findAll(s.text))
                .map { it.value.replace(Regex("\\s+"), " ").trim() }
                .filter { it.length >= 2 }
            for (v in matches) {
                if (out.size >= 10) return out.map { it.key to it.value }
                out.putIfAbsent(v, shorten(clean(s.text), 120))
            }
        }
        return out.map { it.key to it.value }
    }

    private fun shorten(text: String, max: Int): String {
        if (text.length <= max) return text
        val cut = text.substring(0, max)
        val lastSpace = cut.lastIndexOf(' ')
        return (if (lastSpace > max / 2) cut.substring(0, lastSpace) else cut).trimEnd(',', ';', ':') + "…"
    }

    /** Majuscule initiale, ponctuation finale : une phrase extraite lisible dans une puce. */
    private fun clean(text: String): String {
        val t = text.trim()
        if (t.isEmpty()) return t
        val capped = t.replaceFirstChar { it.uppercaseChar() }
        return if (capped.last() in ".!?…") capped else "$capped."
    }

    private fun tokenize(text: String): List<String> =
        WORD.findAll(text.lowercase())
            .map { it.value.trim('\'', '’', '-') }
            .filter { it.length >= 3 && it !in STOPWORDS && !it.all { c -> c.isDigit() } }
            .map { stem(it) }
            .toList()

    /** Regroupement minimal singulier/pluriel (« comptes » → « compte »). */
    private fun stem(w: String): String = when {
        w.length > 4 && w.endsWith("aux") -> w.dropLast(3) + "al"
        w.length > 4 && w.endsWith("s") && !w.endsWith("ss") -> w.dropLast(1)
        w.length > 4 && w.endsWith("x") -> w.dropLast(1)
        else -> w
    }
}
