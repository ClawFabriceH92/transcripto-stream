package com.transcripto.stream.summary

import com.anthropic.client.AnthropicClient
import com.anthropic.errors.BadRequestException
import com.anthropic.models.beta.AnthropicBeta
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaTextBlockParam
import com.anthropic.models.beta.messages.MessageCreateParams as BetaMessageCreateParams
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.TextBlockParam

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

    fun ask(
        apiKey: String,
        modelId: String,
        title: String,
        transcript: String,
        summaryMarkdown: String?,
        history: List<QaTurn>,
        question: String,
    ): AiAnswer {
        val q = question.trim()
        if (q.isEmpty()) return AiAnswer.Failed("Question vide")
        if (transcript.isBlank()) return AiAnswer.Failed("Pas de transcription — lance d'abord « Transcrire »")
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
        val turns = history.takeLast(MAX_HISTORY)

        val client: AnthropicClient = try {
            ClaudeSupport.newClient(apiKey)
        } catch (t: Throwable) {
            return AiAnswer.Failed("Assistant IA indisponible : ${t.message}")
        }
        return try {
            if (modelId == ClaudeSupport.MODEL_OPUS) {
                try {
                    askWithFallbacks(client, modelId, context, turns, q)
                } catch (e: BadRequestException) {
                    if (ClaudeSupport.isFallbackRejected(e)) askStable(client, modelId, context, turns, q) else throw e
                }
            } else {
                askStable(client, modelId, context, turns, q)
            }
        } catch (t: Throwable) {
            AiAnswer.Failed(ClaudeSupport.describe(t, "Réponse"))
        } finally {
            ClaudeSupport.closeQuietly(client)
        }
    }

    private fun askWithFallbacks(
        client: AnthropicClient,
        modelId: String,
        context: String,
        turns: List<QaTurn>,
        question: String,
    ): AiAnswer {
        val builder = BetaMessageCreateParams.builder()
            .model(modelId)
            .maxTokens(MAX_TOKENS)
            .systemOfBetaTextBlockParams(
                listOf(
                    BetaTextBlockParam.builder().text(INSTRUCTIONS).build(),
                    BetaTextBlockParam.builder()
                        .text(context)
                        .cacheControl(BetaCacheControlEphemeral.builder().build())
                        .build(),
                )
            )
        turns.forEach { t ->
            builder.addUserMessage(t.question)
            builder.addAssistantMessage(t.answer)
        }
        builder.addUserMessage(question)
            .fallbacksDefault()
            .addBeta(AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01)
        val msg: BetaMessage = client.beta().messages().create(builder.build())
        val stop = msg.stopReason().map { it.toString() }.orElse("")
        if (stop.contains("refusal", ignoreCase = true)) {
            return AiAnswer.Failed("Question refusée par les filtres de sécurité du modèle")
        }
        val text = msg.content()
            .mapNotNull { block -> block.text().map { it.text() }.orElse(null) }
            .joinToString("\n")
            .trim()
        if (text.isBlank()) return AiAnswer.Failed("Réponse vide du modèle")
        return AiAnswer.Ok(
            text = text,
            model = msg.model().asString(),
            inputTokens = msg.usage().inputTokens(),
            outputTokens = msg.usage().outputTokens(),
            cacheReadTokens = msg.usage().cacheReadInputTokens().orElse(0L),
        )
    }

    private fun askStable(
        client: AnthropicClient,
        modelId: String,
        context: String,
        turns: List<QaTurn>,
        question: String,
    ): AiAnswer {
        val builder = MessageCreateParams.builder()
            .model(modelId)
            .maxTokens(MAX_TOKENS)
            .systemOfTextBlockParams(
                listOf(
                    TextBlockParam.builder().text(INSTRUCTIONS).build(),
                    TextBlockParam.builder()
                        .text(context)
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build(),
                )
            )
        turns.forEach { t ->
            builder.addUserMessage(t.question)
            builder.addAssistantMessage(t.answer)
        }
        builder.addUserMessage(question)
        if (modelId != ClaudeSupport.MODEL_HAIKU) {
            // Question ponctuelle : effort bas suffit, réponse rapide (paramètre refusé par Haiku 4.5)
            builder.outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
        }
        val msg: Message = client.messages().create(builder.build())
        val stop = msg.stopReason().map { it.toString() }.orElse("")
        if (stop.contains("refusal", ignoreCase = true)) {
            return AiAnswer.Failed("Question refusée par les filtres de sécurité du modèle")
        }
        val text = msg.content()
            .mapNotNull { block -> block.text().map { it.text() }.orElse(null) }
            .joinToString("\n")
            .trim()
        if (text.isBlank()) return AiAnswer.Failed("Réponse vide du modèle")
        return AiAnswer.Ok(
            text = text,
            model = msg.model().asString(),
            inputTokens = msg.usage().inputTokens(),
            outputTokens = msg.usage().outputTokens(),
            cacheReadTokens = msg.usage().cacheReadInputTokens().orElse(0L),
        )
    }
}
