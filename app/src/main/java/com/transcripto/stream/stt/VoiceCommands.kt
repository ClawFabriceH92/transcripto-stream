package com.transcripto.stream.stt

/**
 * Mode dictée : convertit la ponctuation dite à la voix (« point », « virgule »,
 * « à la ligne »…) en signes, à la manière des dictaphones professionnels.
 * Pur, testable en JVM. Comportement dictaphone assumé : chaque « point » isolé
 * est converti (y compris dans « un point de détail ») — c'est pour cela que le
 * mode est optionnel et pensé pour la dictée de notes, pas pour les réunions.
 */
object VoiceCommands {

    // Ordre important : les commandes les plus longues d'abord, sinon « point »
    // avalerait « point d'interrogation ».
    private val RULES: List<Pair<Regex, String>> = listOf(
        "point d'interrogation" to " ?",
        "point d'exclamation" to " !",
        "points de suspension" to "…",
        "nouveau paragraphe" to "\n\n",
        "point virgule" to " ;",
        "à la ligne" to "\n",
        "deux points" to " :",
        "virgule" to ",",
        "point" to ".",
    ).map { (spoken, repl) ->
        // La commande doit être un mot entier précédé d'un espace (ou en début de
        // texte) et suivi d'un espace, d'une ponctuation ou de la fin du texte.
        Regex("(?i)(?:^|\\s+)${Regex.escape(spoken)}(?=[\\s.,;:!?…]|$)") to repl
    }

    /** Applique les commandes vocales de ponctuation au texte. */
    fun apply(text: String): String {
        var out = text
        for ((regex, repl) in RULES) {
            out = regex.replace(out) { repl }
        }
        // Nettoyage : espaces avant . , ; et espaces multiples hors sauts de ligne
        out = out
            .replace(Regex(" +([.,])"), "$1")
            .replace(Regex("[ ]{2,}"), " ")
            .replace(Regex(" *\n *"), "\n")
        return out.trim()
    }
}
