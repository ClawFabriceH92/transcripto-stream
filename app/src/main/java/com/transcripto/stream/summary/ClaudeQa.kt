package com.transcripto.stream.summary

/** Un échange question → réponse sur un enregistrement. */
data class QaTurn(val question: String, val answer: String)

sealed class AiAnswer {
    data class Ok(
        val text: String,
        val model: String,
        val inputTokens: Long,
        val outputTokens: Long,
        val cacheReadTokens: Long,
    ) : AiAnswer()

    data class Failed(val message: String) : AiAnswer()
}

/**
 * Questions en langage naturel sur un enregistrement, répondues par Claude à partir
 * de la transcription (et de la synthèse si elle existe). Même périmètre que la
 * synthèse IA : texte seul, clé API de l'utilisateur, appel synchrone hors thread principal.
 *
 * La consigne et la transcription forment un préfixe stable placé dans `system` et
 * marqué `cache_control` : les questions successives sur le même enregistrement
 * relisent ce préfixe depuis le cache (moins cher, plus rapide) au lieu de le
 * refacturer à chaque tour.
 */
object ClaudeQa {

    private const val MAX_TOKENS = 4_000L
    private const val MAX_HISTORY = 12

    private val INSTRUCTIONS = """
        Tu es l'assistant d'un cabinet d'expertise comptable et de commissariat aux comptes. On te fournit
        la transcription automatique (reconnaissance vocale en français, parfois bruitée) d'un enregistrement,
        et éventuellement sa synthèse. Tu réponds en français aux questions de l'utilisateur sur cet
        enregistrement, uniquement à partir de son contenu.

        Règles : cite les passages utiles (horodatage [mm:ss] s'il figure dans la transcription) ; si la
        réponse n'est pas dans l'enregistrement, dis-le clairement plutôt que de supposer ; si un passage
        est ambigu ou probablement mal transcrit, signale-le par « (à vérifier) » ; désigne les intervenants
        exactement comme dans la transcription ; réponds de façon concise (quelques phrases ou puces),
        en Markdown léger, sans introduction ni conclusion.
    """.trimIndent()

    /** Requête envoyée au modèle (visible pour les tests) ; null si la question ou la transcription est vide. */
    fun request(title: String, transcript: String, summaryMarkdown: String?, history: List<QaTurn>, question: String): ClaudeRequest? {
        val q = question.trim()
        if (q.isEmpty() || transcript.isBlank()) return null
        val (body, truncated) = ClaudeSupport.truncate(transcript.trim())
        // Préfixe stable (mis en cache) : titre, transcription, synthèse — rien qui varie d'un tour à l'autre
        val context = buildString {
            append("Titre : ").append(title.ifBlank { "Enregistrement" }).append('\n')
            if (truncated) append("(Transcription tronquée : seule la première partie est fournie.)\n")
            append("\nTranscription :\n<<<\n").append(body).append("\n>>>\n")
            if (!summaryMarkdown.isNullOrBlank()) {
                append("\nSynthèse existante :\n<<<\n").append(summaryMarkdown.trim()).append("\n>>>\n")
            }
        }
        val messages = ArrayList<ClaudeMessage>()
        history.takeLast(MAX_HISTORY).forEach { t ->
            messages += ClaudeMessage(ClaudeMessage.Role.USER, t.question)
            messages += ClaudeMessage(ClaudeMessage.Role.ASSISTANT, t.answer)
        }
        messages += ClaudeMessage(ClaudeMessage.Role.USER, q)
        // Question ponctuelle : effort bas suffit, réponse rapide
        return ClaudeRequest(
            system = listOf(INSTRUCTIONS, context),
            messages = messages,
            maxTokens = MAX_TOKENS,
            effort = ClaudeRequest.Effort.LOW,
            cacheLastSystemBlock = true,
        )
    }

    fun ask(
        apiKey: String,
        modelId: String,
        title: String,
        transcript: String,
        summaryMarkdown: String?,
        history: List<QaTurn>,
        question: String,
        transport: ClaudeTransport,
    ): AiAnswer {
        if (question.isBlank()) return AiAnswer.Failed("Question vide")
        if (transcript.isBlank()) return AiAnswer.Failed("Pas de transcription — lance d'abord « Transcrire »")
        val request = request(title, transcript, summaryMarkdown, history, question) ?: return AiAnswer.Failed("Question vide")
        return try {
            val r = transport.run(apiKey, modelId, request)
            AiAnswer.Ok(
                text = ClaudeSupport.markTruncated(r.text, r.stopReason),
                model = r.model,
                inputTokens = r.inputTokens,
                outputTokens = r.outputTokens,
                cacheReadTokens = r.cacheReadTokens,
            )
        } catch (e: ClaudeRefusal) {
            AiAnswer.Failed("Question refusée par les filtres de sécurité du modèle")
        } catch (t: Throwable) {
            AiAnswer.Failed(ClaudeSupport.describe(t, "Réponse"))
        }
    }
}
