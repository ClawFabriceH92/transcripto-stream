package com.transcripto.stream.summary

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Synthèse rédigée par un modèle local servi par Ollama.
 *
 * Troisième voie entre l'extraction de phrases (Locale) et Claude (cloud) : le
 * verbatim part sur le serveur Ollama du réseau domestique, jamais sur Internet.
 * La consigne et le message utilisateur sont ceux de [ClaudeSummarizer], pour
 * qu'un changement de fournisseur ne change pas le résultat attendu.
 */
object OllamaSummarizer {

    const val URL_DEFAUT = "http://192.168.0.162:11434"
    const val MODELE_DEFAUT = "ministral-3:8b"

    private const val MAX_TOKENS = 900
    /** Délai de lecture d'une génération (une synthèse locale peut être longue). */
    const val DELAI_LECTURE_MS = 240_000

    /** Adresse normalisée (sans barre oblique finale). */
    fun normaliser(adresse: String): String =
        adresse.trim().ifEmpty { URL_DEFAUT }.trimEnd('/')

    fun promptSysteme(template: SummaryTemplate): String = ClaudeSummarizer.systemPrompt(template)

    fun promptUtilisateur(input: SummaryInput, openActions: List<String> = emptyList()): String =
        ClaudeSummarizer.userMessage(input, openActions)

    /** Corps JSON envoyé à `/api/generate` (visible pour les tests). */
    fun corpsRequete(modele: String, prompt: String, nombreTokens: Int = MAX_TOKENS): String = buildString {
        append("{\"model\":").append(echapper(modele))
        append(",\"prompt\":").append(echapper(prompt))
        append(",\"stream\":false,\"options\":{\"temperature\":0.2,\"num_predict\":")
        append(nombreTokens).append("}}")
    }

    /** Texte de la réponse d'Ollama, déséchappé. Lève IOException si illisible. */
    fun extraireReponse(JSON: String): String {
        val motif = Regex(
            "\"response\"\\s*:\\s*\"(.*?)\"\\s*,\\s*\"done\"",
            RegexOption.DOT_MATCHES_ALL,
        )
        val brut = motif.find(JSON)?.groupValues?.get(1)
            ?: Regex("\"response\"\\s*:\\s*\"(.*?)\"", RegexOption.DOT_MATCHES_ALL)
                .find(JSON)?.groupValues?.get(1)
            ?: throw IOException("réponse illisible du serveur local")
        return desechapper(brut).trim()
    }

    /**
     * Synthèse par le modèle local.
     *
     * @param transport envoi HTTP (factice dans les tests)
     */
    fun summarize(
        url: String,
        modele: String,
        input: SummaryInput,
        template: SummaryTemplate = SummaryTemplates.REUNION,
        transport: OllamaTransport,
        openActions: List<String> = emptyList(),
    ): AiSummaryResult {
        if (input.transcript.isBlank()) return AiSummaryResult.Failed("Transcription vide")
        val prompt = promptSysteme(template) + "\n\n" + promptUtilisateur(input, openActions)
        return try {
            val texte = transport.generer(normaliser(url), modele, prompt)
            if (texte.isBlank()) {
                AiSummaryResult.Failed("Le modèle local a renvoyé une synthèse vide")
            } else {
                AiSummaryResult.Ok(
                    markdown = texte,
                    model = modele,
                    inputTokens = 0,
                    outputTokens = 0,
                )
            }
        } catch (e: IOException) {
            AiSummaryResult.Failed("Modèle local injoignable : ${e.message ?: "erreur réseau"}")
        } catch (t: Throwable) {
            AiSummaryResult.Failed("Synthèse locale impossible : ${t.message ?: "erreur"}")
        }
    }

    private fun echapper(texte: String): String {
        val sb = StringBuilder("\"")
        for (caractere in texte) {
            when (caractere) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (caractere.code < 0x20) sb.append(' ') else sb.append(caractere)
            }
        }
        return sb.append('"').toString()
    }

    private fun desechapper(texte: String): String {
        val sb = StringBuilder()
        var index = 0
        while (index < texte.length) {
            val caractere = texte[index]
            if (caractere == '\\' && index + 1 < texte.length) {
                when (val suivant = texte[index + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    'u' -> {
                        val code = texte.substring(index + 2, (index + 6).coerceAtMost(texte.length))
                            .toIntOrNull(16)
                        if (code != null) {
                            sb.append(code.toChar())
                            index += 4
                        } else {
                            sb.append(suivant)
                        }
                    }
                    else -> sb.append(suivant)
                }
                index += 2
            } else {
                sb.append(caractere)
                index += 1
            }
        }
        return sb.toString()
    }
}

/** Envoi d'une requête de génération à un serveur Ollama. */
interface OllamaTransport {
    fun generer(url: String, modele: String, prompt: String): String
}

/** Transport réel : POST /api/generate, réponse non streamée. */
class HttpOllamaTransport : OllamaTransport {

    override fun generer(url: String, modele: String, prompt: String): String {
        val connexion = URL(url + "/api/generate").openConnection() as HttpURLConnection
        try {
            connexion.requestMethod = "POST"
            connexion.connectTimeout = 8_000
            connexion.readTimeout = OllamaSummarizer.DELAI_LECTURE_MS
            connexion.doOutput = true
            connexion.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connexion.outputStream.use { flux ->
                flux.write(OllamaSummarizer.corpsRequete(modele, prompt).toByteArray(Charsets.UTF_8))
            }
            val code = connexion.responseCode
            val flux = if (code in 200..299) connexion.inputStream else connexion.errorStream
            val corps = flux?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("le serveur local a répondu $code")
            return OllamaSummarizer.extraireReponse(corps)
        } finally {
            connexion.disconnect()
        }
    }
}

/** Vérifie l'accès au serveur : liste des modèles installés. */
object OllamaProbe {

    fun modeles(url: String, delaiMs: Int = 6_000): List<String> {
        val connexion = URL(OllamaSummarizer.normaliser(url) + "/api/tags")
            .openConnection() as HttpURLConnection
        try {
            connexion.connectTimeout = delaiMs
            connexion.readTimeout = delaiMs
            val code = connexion.responseCode
            val flux = if (code in 200..299) connexion.inputStream else connexion.errorStream
            val corps = flux?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("le serveur local a répondu $code")
            return Regex("\"name\"\\s*:\\s*\"([^\"]+)\"")
                .findAll(corps)
                .map { it.groupValues[1] }
                .toList()
        } finally {
            connexion.disconnect()
        }
    }
}
