package com.transcripto.stream.summary

import com.anthropic.client.AnthropicClient
import com.anthropic.errors.BadRequestException
import com.anthropic.models.beta.AnthropicBeta
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.MessageCreateParams as BetaMessageCreateParams
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
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
 * Synthèse rédigée par Claude via le SDK Java officiel Anthropic. Opt-in : seule
 * la TRANSCRIPTION (jamais l'audio) est envoyée à api.anthropic.com, avec la
 * clé API de l'utilisateur. Appel synchrone : à exécuter hors du thread principal.
 *
 * Sur Opus 5, un repli serveur par défaut (`fallbacks: "default"`, routé par
 * catégorie de refus) est demandé pour qu'un refus des filtres de sécurité ne
 * laisse pas l'utilisateur sans synthèse ; si le serveur rejette ce paramètre,
 * la même requête est renvoyée sans repli.
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

    fun summarize(
        apiKey: String,
        modelId: String,
        input: SummaryInput,
        template: SummaryTemplate = SummaryTemplates.REUNION,
    ): AiSummaryResult {
        val transcript = input.transcript.trim()
        if (transcript.isBlank()) return AiSummaryResult.Failed("Transcription vide")
        val (body, truncated) = ClaudeSupport.truncate(transcript)
        val user = buildString {
            append("Titre : ").append(input.title.ifBlank { "Enregistrement" }).append('\n')
            append("Date : ").append(input.dateLabel).append('\n')
            if (input.durationMs > 0) {
                append("Durée : ").append(TranscriptExporter.formatHms(input.durationMs)).append('\n')
            }
            if (truncated) append("(Transcription tronquée : seule la première partie est fournie.)\n")
            append("\nTranscription :\n<<<\n").append(body).append("\n>>>")
        }
        val system = systemPrompt(template)

        val client: AnthropicClient = try {
            ClaudeSupport.newClient(apiKey)
        } catch (t: Throwable) {
            // Initialisation du SDK impossible (classe manquante…) : repli local en amont
            return AiSummaryResult.Failed("Synthèse IA indisponible : ${t.message}")
        }
        return try {
            if (modelId == MODEL_OPUS) {
                try {
                    callWithFallbacks(client, modelId, system, user)
                } catch (e: BadRequestException) {
                    // Paramètre de repli refusé par le serveur → même requête, sans repli
                    if (ClaudeSupport.isFallbackRejected(e)) callStable(client, modelId, system, user) else throw e
                }
            } else {
                callStable(client, modelId, system, user)
            }
        } catch (t: Throwable) {
            AiSummaryResult.Failed(ClaudeSupport.describe(t, "Synthèse IA"))
        } finally {
            ClaudeSupport.closeQuietly(client)
        }
    }

    /** Point d'accès bêta : repli serveur par défaut en cas de refus des filtres. */
    private fun callWithFallbacks(
        client: AnthropicClient,
        modelId: String,
        system: String,
        user: String,
    ): AiSummaryResult {
        val params = BetaMessageCreateParams.builder()
            .model(modelId)
            .maxTokens(MAX_TOKENS)
            .system(system)
            .addUserMessage(user)
            .fallbacksDefault()
            .addBeta(AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01)
            .build()
        val msg: BetaMessage = client.beta().messages().create(params)
        val stop = msg.stopReason().map { it.toString() }.orElse("")
        if (stop.contains("refusal", ignoreCase = true)) {
            return AiSummaryResult.Failed("Synthèse IA refusée par les filtres de sécurité du modèle")
        }
        val text = msg.content()
            .mapNotNull { block -> block.text().map { it.text() }.orElse(null) }
            .joinToString("\n")
            .trim()
        if (text.isBlank()) return AiSummaryResult.Failed("Réponse vide du modèle")
        return AiSummaryResult.Ok(
            markdown = text,
            model = msg.model().asString(),
            inputTokens = msg.usage().inputTokens(),
            outputTokens = msg.usage().outputTokens(),
        )
    }

    /** Point d'accès stable (Sonnet 5, Haiku 4.5, ou Opus 5 sans repli). */
    private fun callStable(
        client: AnthropicClient,
        modelId: String,
        system: String,
        user: String,
    ): AiSummaryResult {
        val builder = MessageCreateParams.builder()
            .model(modelId)
            .maxTokens(MAX_TOKENS)
            .system(system)
            .addUserMessage(user)
        if (modelId != MODEL_HAIKU) {
            // Effort « medium » : synthèse = tâche de rédaction, pas de raisonnement long
            // (paramètre refusé par Haiku 4.5, donc réservé aux modèles de la génération 5)
            builder.outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.MEDIUM).build())
        }
        val msg: Message = client.messages().create(builder.build())
        val stop = msg.stopReason().map { it.toString() }.orElse("")
        if (stop.contains("refusal", ignoreCase = true)) {
            return AiSummaryResult.Failed("Synthèse IA refusée par les filtres de sécurité du modèle")
        }
        val text = msg.content()
            .mapNotNull { block -> block.text().map { it.text() }.orElse(null) }
            .joinToString("\n")
            .trim()
        if (text.isBlank()) return AiSummaryResult.Failed("Réponse vide du modèle")
        return AiSummaryResult.Ok(
            markdown = text,
            model = msg.model().asString(),
            inputTokens = msg.usage().inputTokens(),
            outputTokens = msg.usage().outputTokens(),
        )
    }
}
