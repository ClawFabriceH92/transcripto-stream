package com.transcripto.stream.summary

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.AnthropicBeta
import com.anthropic.models.beta.messages.BetaFallbackParam
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.MessageCreateParams as BetaMessageCreateParams
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.Model
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
 * Sur Opus 5, un repli serveur vers Opus 4.8 est demandé (comme dans l'exemple
 * officiel du SDK) pour qu'un refus des filtres de sécurité ne laisse pas
 * l'utilisateur sans synthèse ; si le serveur rejette ce paramètre, la même
 * requête est renvoyée sans repli.
 */
object ClaudeSummarizer {

    const val MODEL_OPUS = "claude-opus-5"
    const val MODEL_SONNET = "claude-sonnet-5"
    const val MODEL_HAIKU = "claude-haiku-4-5"

    /** Modèles proposés dans les réglages : identifiant → libellé. */
    val MODELS: List<Pair<String, String>> = listOf(
        MODEL_OPUS to "Opus 5",
        MODEL_SONNET to "Sonnet 5",
        MODEL_HAIKU to "Haiku 4.5",
    )

    fun modelLabel(id: String): String = MODELS.firstOrNull { it.first == id }?.second ?: id

    /** ~100k tokens : marge sous la fenêtre de 200k de Haiku, largement sous 1M ailleurs. */
    private const val MAX_TRANSCRIPT_CHARS = 400_000
    private const val MAX_TOKENS = 16_000L

    private val SYSTEM_PROMPT = """
        Tu es l'assistant de rédaction d'un cabinet d'expertise comptable et de commissariat aux comptes.
        On te fournit la transcription automatique (reconnaissance vocale en français, parfois bruitée,
        ponctuation approximative) d'une réunion, d'un entretien ou d'une dictée.

        Rédige une synthèse en français, en Markdown, avec exactement ces rubriques dans cet ordre
        (omets celles qui seraient vides) :
        ## Contexte — 2 à 3 phrases : objet, participants s'ils sont identifiables, tonalité.
        ## Points clés — puces.
        ## Décisions — puces.
        ## Actions à mener — puces « qui — quoi — échéance » (« non précisé » si absent).
        ## Chiffres et dates cités — puces, valeur en gras puis son contexte.
        ## Points de vigilance — risques, désaccords, questions restées ouvertes.

        Règles : reste strictement factuel et n'invente rien ; si un passage est ambigu ou
        probablement mal transcrit, signale-le par « (à vérifier) » ; conserve les libellés
        « Intervenant N » tels quels ; les marqueurs [⭐mm:ss] signalent des moments jugés
        importants par l'utilisateur, exploite-les ; longueur cible 250 à 450 mots, jamais plus
        de 600 ; pas de titre de niveau 1, pas d'introduction ni de conclusion hors rubriques,
        aucun commentaire sur la tâche.
    """.trimIndent()

    fun summarize(apiKey: String, modelId: String, input: SummaryInput): AiSummaryResult {
        val transcript = input.transcript.trim()
        if (transcript.isBlank()) return AiSummaryResult.Failed("Transcription vide")
        val truncated = transcript.length > MAX_TRANSCRIPT_CHARS
        val body = if (truncated) transcript.substring(0, MAX_TRANSCRIPT_CHARS) else transcript
        val user = buildString {
            append("Titre : ").append(input.title.ifBlank { "Enregistrement" }).append('\n')
            append("Date : ").append(input.dateLabel).append('\n')
            if (input.durationMs > 0) {
                append("Durée : ").append(TranscriptExporter.formatHms(input.durationMs)).append('\n')
            }
            if (truncated) append("(Transcription tronquée : seule la première partie est fournie.)\n")
            append("\nTranscription :\n<<<\n").append(body).append("\n>>>")
        }

        val client = AnthropicOkHttpClient.builder().apiKey(apiKey).build()
        return try {
            if (modelId == MODEL_OPUS) {
                try {
                    callWithFallbacks(client, modelId, user)
                } catch (e: BadRequestException) {
                    // Paramètre de repli refusé par le serveur → même requête, sans repli
                    if (e.message?.contains("fallback", ignoreCase = true) == true) {
                        callStable(client, modelId, user)
                    } else {
                        throw e
                    }
                }
            } else {
                callStable(client, modelId, user)
            }
        } catch (e: UnauthorizedException) {
            AiSummaryResult.Failed("Clé API refusée (401) — vérifie-la dans les Réglages")
        } catch (e: PermissionDeniedException) {
            AiSummaryResult.Failed("Accès refusé par l'API (403) : ${e.message}")
        } catch (e: NotFoundException) {
            AiSummaryResult.Failed("Modèle introuvable (404) — choisis un autre modèle dans les Réglages")
        } catch (e: RateLimitException) {
            AiSummaryResult.Failed("Limite de débit atteinte (429) — réessaie dans un instant")
        } catch (e: BadRequestException) {
            AiSummaryResult.Failed("Requête refusée par l'API : ${e.message}")
        } catch (e: InternalServerException) {
            AiSummaryResult.Failed("Service Anthropic indisponible — réessaie plus tard")
        } catch (e: AnthropicServiceException) {
            AiSummaryResult.Failed("Erreur API : ${e.message}")
        } catch (e: AnthropicIoException) {
            AiSummaryResult.Failed("Pas de connexion réseau")
        } catch (e: Exception) {
            AiSummaryResult.Failed("Synthèse IA impossible : ${e.message}")
        }
    }

    /** Point d'accès bêta : repli serveur vers Opus 4.8 en cas de refus des filtres. */
    private fun callWithFallbacks(client: AnthropicClient, modelId: String, user: String): AiSummaryResult {
        val params = BetaMessageCreateParams.builder()
            .model(modelId)
            .maxTokens(MAX_TOKENS)
            .system(SYSTEM_PROMPT)
            .addUserMessage(user)
            .fallbacksOfFallbackParams(
                listOf(BetaFallbackParam.builder().model(Model.CLAUDE_OPUS_4_8).build())
            )
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
    private fun callStable(client: AnthropicClient, modelId: String, user: String): AiSummaryResult {
        val builder = MessageCreateParams.builder()
            .model(modelId)
            .maxTokens(MAX_TOKENS)
            .system(SYSTEM_PROMPT)
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
