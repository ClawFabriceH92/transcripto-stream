package com.transcripto.stream.data

/**
 * Relecture assistée : seuil de confiance sous lequel un passage est « à vérifier »,
 * et repérage des montants, pourcentages et dates à relire avec attention. Pur, testé.
 */
object ReviewMarks {

    /** Confiance (probabilité moyenne des jetons) en dessous de laquelle un passage est douteux. */
    const val THRESHOLD = 0.6f

    /** Passage à vérifier : confiance connue et sous le seuil. */
    fun isDoubtful(confidence: Float): Boolean = confidence >= 0f && confidence < THRESHOLD

    private const val MONTHS = "janvier|février|fevrier|mars|avril|mai|juin|juillet|août|aout|septembre|octobre|novembre|décembre|decembre"
    private val FIGURE = Regex(
        // montants et pourcentages : 12 000 €, 12.000,50 euros, 3,5 %, 2 M€, 150 k€
        "\\d(?:[\\d\\u00a0\\u202f ]*\\d)?(?:[.,]\\d+)?\\s?(?:%|€|k€|M€|euros?|millions?|milliers?)" +
            // dates numériques : 15/03, 15/03/2026, 15.03.2026
            "|\\b\\d{1,2}[./]\\d{1,2}(?:[./]\\d{2,4})?\\b" +
            // dates en lettres : 15 mars, 1er juillet 2026, mars 2026
            "|\\b(?:\\d{1,2}(?:er)?\\s+)?(?:$MONTHS)(?:\\s+\\d{4})?\\b" +
            // trimestres et exercices : T2 2026, exercice 2025
            "|\\bT[1-4]\\s?\\d{4}\\b|\\bexercice\\s+\\d{4}\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Plages (indices de caractères) des montants, pourcentages et dates de [text]. */
    fun figureRanges(text: String): List<IntRange> =
        FIGURE.findAll(text).map { it.range }.filter { it.last - it.first >= 1 }.toList()
}
