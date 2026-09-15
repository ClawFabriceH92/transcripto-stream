package com.transcripto.stream.summary

import com.transcripto.stream.data.ActionItem
import com.transcripto.stream.data.TextFold
import java.util.Calendar
import java.util.Locale
import java.util.UUID

/**
 * Extrait les actions à mener d'une synthèse (locale ou IA) : puces de la rubrique
 * « Actions à mener », forme « qui — quoi — échéance » quand elle est respectée,
 * échéance repérée dans le texte sinon (dates françaises : « avant le 15 mars »,
 * « fin juin », « T2 2026 », « 15/03/2026 », « d'ici 2 semaines »). Pur, testé.
 */
object ActionExtractor {

    private val HEADING = Regex("^##\\s*(.+?)\\s*$")
    private val BULLET = Regex("^\\s*[-*•]\\s+(.+?)\\s*$")
    private val ACTIONS_TITLE = Regex("^actions?\\b.*", RegexOption.IGNORE_CASE)
    private val SPLIT = Regex("\\s+[—–]\\s+")
    private val NOT_SPECIFIED = Regex("^(non précisée?|à préciser|non défini[e]?|\\?|n/?a|aucune?)$", RegexOption.IGNORE_CASE)
    private val OWNER_PREFIX = Regex("^([A-ZÀ-Ý][\\p{L}.'’\\- ]{1,40}?)\\s*:\\s+(.+)$")
    private val BOLD = Regex("\\*\\*(.+?)\\*\\*")

    private val MONTHS = listOf(
        "janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre", "novembre", "décembre",
    )
    private const val MONTH_RE = "janv(?:ier|\\.)?|f[ée]v(?:rier|\\.)?|mars|avr(?:il|\\.)?|mai|juin|juil(?:let|\\.)?|ao[uû]t|sept(?:embre|\\.)?|oct(?:obre|\\.)?|nov(?:embre|\\.)?|d[ée]c(?:embre|\\.)?"
    private val DAY_MONTH = Regex(
        "(?:(?:avant|d['’]ici|pour|au plus tard|jusqu['’]au|dès)\\s+)?(?:le\\s+)?(\\d{1,2})(?:er)?\\s+($MONTH_RE)(?:\\s+(\\d{4}))?",
        RegexOption.IGNORE_CASE,
    )
    private val PART_MONTH = Regex("(début|mi|fin)[\\s-]+(?:du\\s+mois\\s+d[e'’]\\s*)?($MONTH_RE)(?:\\s+(\\d{4}))?", RegexOption.IGNORE_CASE)
    private val QUARTER = Regex("\\bT([1-4])\\s*(\\d{4})", RegexOption.IGNORE_CASE)
    private val NUMERIC = Regex("\\b(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?\\b")
    private val RELATIVE = Regex("(?:d['’]ici|sous|dans)\\s+(\\d{1,2})\\s+(jours?|semaines?|mois)", RegexOption.IGNORE_CASE)
    private val PART_OF_PERIOD = Regex("(début|mi|fin)[\\s-]+(?:de\\s+la\\s+|de\\s+l['’]|du\\s+|d['’]|de\\s+)?(semaine|mois|trimestre|année)(?:\\s+prochaine?)?", RegexOption.IGNORE_CASE)

    /** Échéance repérée dans un texte : libellé tel qu'écrit et instant résolu (0 si impossible). */
    data class Due(val label: String, val at: Long)

    /** Actions de la rubrique « Actions à mener » de [markdown] ; [reference] = date de l'enregistrement. */
    fun extract(markdown: String, reference: Long = System.currentTimeMillis()): List<ActionItem> {
        val out = ArrayList<ActionItem>()
        val seen = HashSet<String>()
        var inActions = false
        for (line in markdown.lines()) {
            val h = HEADING.find(line)
            if (h != null) {
                inActions = ACTIONS_TITLE.matches(h.groupValues[1].trim())
                continue
            }
            if (!inActions) continue
            val b = BULLET.find(line) ?: continue
            val item = parseBullet(b.groupValues[1], reference) ?: continue
            if (seen.add(TextFold.fold(item.text))) out += item
        }
        return out
    }

    /** Une puce → action ; null si elle est vide ou ne dit rien. */
    fun parseBullet(raw: String, reference: Long): ActionItem? {
        val bullet = BOLD.replace(raw.trim()) { it.groupValues[1] }
        if (bullet.isEmpty()) return null
        val parts = bullet.split(SPLIT).map { it.trim() }.filter { it.isNotEmpty() }
        var owner = ""
        var text: String
        var due: Due? = null
        when {
            parts.size >= 3 -> {
                owner = parts[0]
                text = parts.subList(1, parts.size - 1).joinToString(" — ")
                val tail = parts.last()
                due = if (NOT_SPECIFIED.matches(tail)) null else (findDue(tail, reference) ?: Due(tail, 0L))
            }
            parts.size == 2 -> {
                // « qui — quoi » ou « quoi — échéance »
                val d = findDue(parts[1], reference)
                if (d != null || NOT_SPECIFIED.matches(parts[1])) {
                    text = parts[0]
                    due = d
                } else {
                    owner = parts[0]
                    text = parts[1]
                }
            }
            else -> text = bullet
        }
        OWNER_PREFIX.find(text)?.let { m ->
            if (owner.isEmpty()) {
                owner = m.groupValues[1].trim()
                text = m.groupValues[2].trim()
            }
        }
        if (NOT_SPECIFIED.matches(owner)) owner = ""
        if (due == null) due = findDue(text, reference)
        text = text.trim().trimEnd('.', ';', ',').trim()
        if (text.isEmpty()) return null
        return ActionItem(
            id = UUID.randomUUID().toString(),
            text = text,
            owner = owner,
            dueLabel = due?.label ?: "",
            dueAt = due?.at ?: 0L,
            createdAt = reference,
        )
    }

    /** Première échéance reconnue dans [text] ; [reference] sert à compléter l'année et les durées relatives. */
    fun findDue(text: String, reference: Long): Due? {
        val cal = Calendar.getInstance(Locale.FRANCE).apply { timeInMillis = reference }
        val refYear = cal.get(Calendar.YEAR)
        val refMonth = cal.get(Calendar.MONTH)

        DAY_MONTH.find(text)?.let { m ->
            val day = m.groupValues[1].toInt()
            val month = monthIndex(m.groupValues[2]) ?: return@let
            val year = m.groupValues[3].toIntOrNull() ?: inferYear(refYear, refMonth, month)
            return Due(m.value.trim(), dateMs(year, month, day.coerceIn(1, 31)))
        }
        PART_MONTH.find(text)?.let { m ->
            val month = monthIndex(m.groupValues[2]) ?: return@let
            val year = m.groupValues[3].toIntOrNull() ?: inferYear(refYear, refMonth, month)
            val day = when (m.groupValues[1].lowercase()) {
                "début" -> 1
                "mi" -> 15
                else -> lastDay(year, month)
            }
            return Due(m.value.trim(), dateMs(year, month, day))
        }
        QUARTER.find(text)?.let { m ->
            val q = m.groupValues[1].toInt()
            val year = m.groupValues[2].toInt()
            val month = q * 3 - 1
            return Due(m.value.trim(), dateMs(year, month, lastDay(year, month)))
        }
        NUMERIC.find(text)?.let { m ->
            val day = m.groupValues[1].toInt()
            val month = m.groupValues[2].toInt() - 1
            if (day !in 1..31 || month !in 0..11) return@let
            val y = m.groupValues[3]
            val year = when {
                y.isEmpty() -> inferYear(refYear, refMonth, month)
                y.length == 2 -> 2000 + y.toInt()
                else -> y.toInt()
            }
            return Due(m.value.trim(), dateMs(year, month, day))
        }
        RELATIVE.find(text)?.let { m ->
            val n = m.groupValues[1].toInt()
            val unit = m.groupValues[2].lowercase()
            val c = Calendar.getInstance(Locale.FRANCE).apply { timeInMillis = reference }
            when {
                unit.startsWith("jour") -> c.add(Calendar.DAY_OF_MONTH, n)
                unit.startsWith("semaine") -> c.add(Calendar.DAY_OF_MONTH, 7 * n)
                else -> c.add(Calendar.MONTH, n)
            }
            return Due(m.value.trim(), startOfDay(c))
        }
        PART_OF_PERIOD.find(text)?.let { m ->
            val c = Calendar.getInstance(Locale.FRANCE).apply { timeInMillis = reference }
            val part = m.groupValues[1].lowercase()
            val next = m.value.contains("prochain", ignoreCase = true)
            when (m.groupValues[2].lowercase()) {
                "semaine" -> {
                    c.firstDayOfWeek = Calendar.MONDAY
                    c.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                    if (next) c.add(Calendar.DAY_OF_MONTH, 7)
                    c.add(Calendar.DAY_OF_MONTH, if (part == "fin") 4 else if (part == "mi") 2 else 0)
                }
                "mois" -> {
                    if (next) c.add(Calendar.MONTH, 1)
                    c.set(Calendar.DAY_OF_MONTH, if (part == "début") 1 else if (part == "mi") 15 else c.getActualMaximum(Calendar.DAY_OF_MONTH))
                }
                "trimestre" -> {
                    if (next) c.add(Calendar.MONTH, 3)
                    val first = (c.get(Calendar.MONTH) / 3) * 3
                    c.set(Calendar.MONTH, if (part == "début") first else if (part == "mi") first + 1 else first + 2)
                    c.set(Calendar.DAY_OF_MONTH, if (part == "début") 1 else if (part == "mi") 15 else c.getActualMaximum(Calendar.DAY_OF_MONTH))
                }
                else -> {
                    if (next) c.add(Calendar.YEAR, 1)
                    c.set(Calendar.MONTH, if (part == "début") 0 else if (part == "mi") 5 else 11)
                    c.set(Calendar.DAY_OF_MONTH, if (part == "début") 1 else if (part == "mi") 30 else 31)
                }
            }
            return Due(m.value.trim(), startOfDay(c))
        }
        return null
    }

    /**
     * Fusionne les actions déjà suivies (état, responsable et échéance modifiés à la main
     * conservés) avec celles extraites d'une nouvelle synthèse : une action est reconnue
     * par son texte replié ; les nouvelles sont ajoutées à la fin.
     */
    fun merge(existing: List<ActionItem>, extracted: List<ActionItem>): List<ActionItem> {
        val known = existing.map { TextFold.fold(it.text) }.toHashSet()
        return existing + extracted.filter { known.add(TextFold.fold(it.text)) }
    }

    private fun monthIndex(name: String): Int? {
        val n = TextFold.fold(name).trimEnd('.')
        return MONTHS.indexOfFirst { TextFold.fold(it).startsWith(n.take(3)) }.takeIf { it >= 0 }
    }

    /** Sans année : la prochaine occurrence du mois (ou le mois courant), jamais un mois déjà passé. */
    private fun inferYear(refYear: Int, refMonth: Int, month: Int): Int = if (month < refMonth) refYear + 1 else refYear

    private fun lastDay(year: Int, month: Int): Int =
        Calendar.getInstance(Locale.FRANCE).apply { clear(); set(year, month, 1) }.getActualMaximum(Calendar.DAY_OF_MONTH)

    private fun dateMs(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance(Locale.FRANCE).apply {
            clear()
            set(year, month, minOf(day, lastDay(year, month)))
        }.timeInMillis

    private fun startOfDay(c: Calendar): Long {
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }
}
