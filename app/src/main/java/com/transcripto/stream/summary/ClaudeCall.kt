package com.transcripto.stream.summary

import com.anthropic.client.AnthropicClient
import com.anthropic.errors.BadRequestException
import com.anthropic.models.beta.AnthropicBeta
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaTextBlockParam
import com.anthropic.models.beta.messages.MessageCreateParams as BetaMessageCreateParams
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.TextBlockParam

/** Un message de l'échange envoyé au modèle. */
data class ClaudeMessage(val role: Role, val text: String) {
    enum class Role { USER, ASSISTANT }
}

/**
 * Une requête textuelle à Claude, indépendante du SDK : blocs système (le dernier
 * peut être marqué pour la mise en cache du préfixe), échange, plafond de sortie,
 * effort de raisonnement.
 */
data class ClaudeRequest(
    val system: List<String>,
    val messages: List<ClaudeMessage>,
    val maxTokens: Long,
    val effort: Effort,
    /** Le dernier bloc système est un préfixe stable (transcription) : `cache_control` posé dessus. */
    val cacheLastSystemBlock: Boolean = false,
) {
    enum class Effort { LOW, MEDIUM }

    companion object {
        fun single(system: String, user: String, maxTokens: Long, effort: Effort) =
            ClaudeRequest(listOf(system), listOf(ClaudeMessage(ClaudeMessage.Role.USER, user)), maxTokens, effort)
    }
}

/** Réponse textuelle du modèle (texte brut : la troncature est signalée par l'appelant). */
data class ClaudeText(
    val text: String,
    val model: String,
    val stopReason: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadTokens: Long = 0L,
)

/** Le modèle a refusé la requête (filtres de sécurité). */
class ClaudeRefusal : Exception("refusé par les filtres de sécurité du modèle")

/** Le modèle a renvoyé une réponse sans texte. */
class ClaudeEmptyReply : Exception("Réponse vide du modèle")

/** Le SDK ne peut pas s'initialiser (classe manquante, compat Android…). */
class ClaudeUnavailable(cause: Throwable) : Exception(cause.message, cause)

/**
 * Transport d'une requête : SDK Anthropic en production ([SdkClaudeTransport]),
 * factice en test. Appel synchrone, hors du thread principal. Lève [ClaudeRefusal],
 * [ClaudeEmptyReply], [ClaudeUnavailable] ou une exception du SDK (traduite par
 * [ClaudeSupport.describe]).
 */
interface ClaudeTransport {
    fun run(apiKey: String, modelId: String, request: ClaudeRequest): ClaudeText
}

/**
 * Transport SDK : un seul client HTTP, créé à la première demande et recréé si la
 * clé change, fermé par [close] (fin de vie du ViewModel) — plus de poignée TLS à
 * chaque question. Sur Opus 5, point d'accès bêta avec repli serveur par défaut
 * (`fallbacks: "default"`) pour qu'un refus des filtres ne laisse pas l'utilisateur
 * sans réponse ; si le serveur rejette ce paramètre, la même requête est renvoyée
 * sur le point d'accès stable et le rejet est mémorisé pour la durée du process.
 */
class SdkClaudeTransport : ClaudeTransport, AutoCloseable {

    private val lock = Any()
    private var client: AnthropicClient? = null
    private var clientKey: String? = null

    private fun clientFor(apiKey: String): AnthropicClient = synchronized(lock) {
        val current = client
        if (current != null && clientKey == apiKey) return current
        ClaudeSupport.closeQuietly(current)
        client = null
        clientKey = null
        val created = try {
            ClaudeSupport.newClient(apiKey)
        } catch (t: Throwable) {
            throw ClaudeUnavailable(t)
        }
        client = created
        clientKey = apiKey
        created
    }

    override fun close() {
        synchronized(lock) {
            ClaudeSupport.closeQuietly(client)
            client = null
            clientKey = null
        }
    }

    override fun run(apiKey: String, modelId: String, request: ClaudeRequest): ClaudeText {
        val c = clientFor(apiKey)
        if (modelId == ClaudeSupport.MODEL_OPUS && !ClaudeSupport.fallbacksRejected) {
            return try {
                beta(c, modelId, request)
            } catch (e: BadRequestException) {
                // Paramètre de repli refusé par le serveur → même requête, sans repli
                if (ClaudeSupport.isFallbackRejected(e)) stable(c, modelId, request) else throw e
            }
        }
        return stable(c, modelId, request)
    }

    /** Point d'accès bêta : repli serveur par défaut en cas de refus des filtres. */
    private fun beta(client: AnthropicClient, modelId: String, r: ClaudeRequest): ClaudeText {
        val builder = BetaMessageCreateParams.builder()
            .model(modelId)
            .maxTokens(r.maxTokens)
            .systemOfBetaTextBlockParams(
                r.system.mapIndexed { i, text ->
                    val b = BetaTextBlockParam.builder().text(text)
                    if (r.cacheLastSystemBlock && i == r.system.lastIndex) b.cacheControl(BetaCacheControlEphemeral.builder().build())
                    b.build()
                },
            )
        r.messages.forEach { m ->
            if (m.role == ClaudeMessage.Role.USER) builder.addUserMessage(m.text) else builder.addAssistantMessage(m.text)
        }
        builder
            .outputConfig(BetaOutputConfig.builder().effort(effortBeta(r.effort)).build())
            .fallbacksDefault()
            .addBeta(AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01)
        val msg: BetaMessage = client.beta().messages().create(builder.build())
        val stop = msg.stopReason().map { it.toString() }.orElse("")
        if (stop.contains("refusal", ignoreCase = true)) throw ClaudeRefusal()
        val text = msg.content().mapNotNull { b -> b.text().map { it.text() }.orElse(null) }.joinToString("\n").trim()
        if (text.isBlank()) throw ClaudeEmptyReply()
        return ClaudeText(
            text = text,
            model = msg.model().asString(),
            stopReason = stop,
            inputTokens = msg.usage().inputTokens(),
            outputTokens = msg.usage().outputTokens(),
            cacheReadTokens = msg.usage().cacheReadInputTokens().orElse(0L),
        )
    }

    /** Point d'accès stable (Sonnet 5, Haiku 4.5, ou Opus 5 sans repli). */
    private fun stable(client: AnthropicClient, modelId: String, r: ClaudeRequest): ClaudeText {
        val builder = MessageCreateParams.builder()
            .model(modelId)
            .maxTokens(r.maxTokens)
            .systemOfTextBlockParams(
                r.system.mapIndexed { i, text ->
                    val b = TextBlockParam.builder().text(text)
                    if (r.cacheLastSystemBlock && i == r.system.lastIndex) b.cacheControl(CacheControlEphemeral.builder().build())
                    b.build()
                },
            )
        r.messages.forEach { m ->
            if (m.role == ClaudeMessage.Role.USER) builder.addUserMessage(m.text) else builder.addAssistantMessage(m.text)
        }
        if (modelId != ClaudeSupport.MODEL_HAIKU) {
            // Paramètre d'effort refusé par Haiku 4.5 : réservé aux modèles de la génération 5
            builder.outputConfig(OutputConfig.builder().effort(effortStable(r.effort)).build())
        }
        val msg: Message = client.messages().create(builder.build())
        val stop = msg.stopReason().map { it.toString() }.orElse("")
        if (stop.contains("refusal", ignoreCase = true)) throw ClaudeRefusal()
        val text = msg.content().mapNotNull { b -> b.text().map { it.text() }.orElse(null) }.joinToString("\n").trim()
        if (text.isBlank()) throw ClaudeEmptyReply()
        return ClaudeText(
            text = text,
            model = msg.model().asString(),
            stopReason = stop,
            inputTokens = msg.usage().inputTokens(),
            outputTokens = msg.usage().outputTokens(),
            cacheReadTokens = msg.usage().cacheReadInputTokens().orElse(0L),
        )
    }

    private fun effortBeta(e: ClaudeRequest.Effort) = when (e) {
        ClaudeRequest.Effort.LOW -> BetaOutputConfig.Effort.LOW
        ClaudeRequest.Effort.MEDIUM -> BetaOutputConfig.Effort.MEDIUM
    }

    private fun effortStable(e: ClaudeRequest.Effort) = when (e) {
        ClaudeRequest.Effort.LOW -> OutputConfig.Effort.LOW
        ClaudeRequest.Effort.MEDIUM -> OutputConfig.Effort.MEDIUM
    }
}
