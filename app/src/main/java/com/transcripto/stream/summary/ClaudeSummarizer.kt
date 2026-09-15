package com.transcripto.stream.summary

import com.transcripto.stream.export.TranscriptExporter

/** Résultat d'une synthèse IA : Markdown (rubriques) ou message d'erreur exploitable. */
sealed class AiSummaryResult {
    data class Ok(
        val markdown: String,
        val model: String,
        val inputTokens: Long,
        val outputTokens: Long,
    ) : AiSummaryResult()

    data class Failed(val message: String) : AiSummaryResult()
}

/**
 * Synthèse rédigée par Claude. Opt-in : seule la TRANSCRIPTION (jamais l'audio)
 * est envoyée à api.anthropic.com, avec la clé API de l'utilisateur. Appel
 * synchrone : à exécuter hors du thread principal. La plomberie (client, repli
 * serveur, refus, erreurs) est dans [ClaudeTransport] ; ici, seulement la
 * construction de la requête et la lecture de la réponse.
 */
object ClaudeSummarizer {

    const val MODEL_OPUS = ClaudeSupport.MODEL_OPUS
    const val MODEL_SONNET = ClaudeSupport.MODEL_SONNET
    const val MODEL_HAIKU = ClaudeSupport.MODEL_HAIKU

    /** Modèles proposés dans les réglages : identifiant → libellé. */
    val MODELS: List<Pair<String, String>> = listOf(
        MODEL_OPUS to "Opus 5",
        MODEL_SONNET to "Sonnet 5",
        MODEL_HAIKU to "Haiku 4.5",
    )

    fun modelLabel(id: String): String = MODELS.firstOrNull { it.first == id }?.second ?: id

    private const val MAX_TOKENS = 16_000L
    private const val MAX_OPEN_ACTIONS = 30

    private const val PREAMBLE = """
        Tu es l'assistant de rédaction d'un cabinet d'expertise comptable et de commissariat aux comptes.
        On te fournit la transcription automatique (reconnaissance vocale en français, parfois bruitée,
        ponctuation approximative) d'un enregistrement. Tu rédiges en français, en Markdown.
    """

    private const val RULES = """
        Règles : reste strictement factuel et n'invente rien ; si un passage est ambigu ou
        probablement mal transcrit, signale-le par « (à vérifier) » ; désigne les intervenants
        exactement comme dans la transcription (noms ou « Intervenant N ») ; les marqueurs
        [⭐mm:ss] signalent des moments jugés importants par l'utilisateur, exploite-les ;
        longueur cible 250 à 450 mots, jamais plus de 600 ; pas de titre de niveau 1, pas
        d'introduction ni de conclusion hors rubriques, aucun commentaire sur la tâche.
    """

    /** Consigne système selon le gabarit (mission). */
    fun systemPrompt(template: SummaryTemplate): String =
        PREAMBLE.trimIndent().trim() + "\n\n" + template.aiTask.trim() + "\n\n" + RULES.trimIndent().trim()

    /**
     * Requête envoyée au modèle (visible pour les tests). [openActions] : actions encore
     * ouvertes du dossier, issues d'enregistrements précédents — le modèle signale celles
     * qui sont traitées ou reconduites dans « Actions à mener ».
     */
    fun request(input: SummaryInput, template: SummaryTemplate, openActions: List<String> = emptyList()): ClaudeRequest {
        val (body, truncated) = ClaudeSupport.truncate(input.transcript.trim())
        val user = buildString {
            append("Titre : ").append(input.title.ifBlank { "Enregistrement" }).append('\n')
            append("Date : ").append(input.dateLabel).append('\n')
            if (input.durationMs > 0) {
                append("Durée : ").append(TranscriptExporter.formatHms(input.durationMs)).append('\n')
            }
            if (truncated) append("(Transcription tronquée : seule la première partie est fournie.)\n")
            if (openActions.isNotEmpty()) {
                append("\nActions encore ouvertes dans ce dossier (enregistrements précédents) — dans « Actions à mener », ")
                append("indique celles que cet enregistrement traite (« traitée ») ou reconduit (« reconduite »), sans les réinventer :\n")
                openActions.take(MAX_OPEN_ACTIONS).forEach { append("- ").append(it.trim()).append('\n') }
            }
            append("\nTranscription :\n<<<\n").append(body).append("\n>>>")
        }
        // Effort « medium » : synthèse = tâche de rédaction, pas de raisonnement long
        return ClaudeRequest.single(systemPrompt(template), user, MAX_TOKENS, ClaudeRequest.Effort.MEDIUM)
    }

    fun summarize(
        apiKey: String,
        modelId: String,
        input: SummaryInput,
        template: SummaryTemplate = SummaryTemplates.REUNION,
        transport: ClaudeTransport,
        openActions: List<String> = emptyList(),
    ): AiSummaryResult {
        if (input.transcript.isBlank()) return AiSummaryResult.Failed("Transcription vide")
        return try {
            val r = transport.run(apiKey, modelId, request(input, template, openActions))
            AiSummaryResult.Ok(
                markdown = r.text,
                model = r.model,
                inputTokens = r.inputTokens,
                outputTokens = r.outputTokens,
            )
        } catch (e: ClaudeRefusal) {
            AiSummaryResult.Failed("Synthèse IA refusée par les filtres de sécurité du modèle")
        } catch (t: Throwable) {
            AiSummaryResult.Failed(ClaudeSupport.describe(t, "Synthèse IA"))
        }
    }
}
