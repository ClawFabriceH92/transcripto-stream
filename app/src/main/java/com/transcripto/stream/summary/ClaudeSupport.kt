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
import java.time.Duration

/**
 * Plomberie commune aux appels Claude (synthèse, questions) : client, messages
 * d'erreur en français, troncature sûre de la transcription.
 */
internal object ClaudeSupport {

    const val MODEL_OPUS = "claude-opus-5"
    const val MODEL_SONNET = "claude-sonnet-5"
    const val MODEL_HAIKU = "claude-haiku-4-5"

    /** ~100k tokens : marge sous la fenêtre de 200k de Haiku, largement sous 1M ailleurs. */
    const val MAX_TRANSCRIPT_CHARS = 400_000

    /**
     * Mobile : 3 min par requête et une seule nouvelle tentative — les défauts du SDK
     * (10 min × 3 essais) laisseraient l'utilisateur près d'une demi-heure sans réponse.
     * Lève si le SDK ne peut pas s'initialiser (classe manquante…) : à intercepter en amont.
     */
    fun newClient(apiKey: String): AnthropicClient =
        AnthropicOkHttpClient.builder()
            .apiKey(apiKey)
            .timeout(Duration.ofMinutes(3))
            .maxRetries(1)
            .build()

    /** Coupe la transcription sans casser une paire de substitution (émoji). */
    fun truncate(transcript: String): Pair<String, Boolean> {
        if (transcript.length <= MAX_TRANSCRIPT_CHARS) return transcript to false
        var end = MAX_TRANSCRIPT_CHARS
        if (Character.isHighSurrogate(transcript[end - 1])) end--
        return transcript.substring(0, end) to true
    }

    /** Message utilisateur pour une erreur d'appel ; [what] = « Synthèse IA », « Réponse »… */
    fun describe(t: Throwable, what: String): String = when (t) {
        is UnauthorizedException -> "Clé API refusée (401) — vérifie-la dans les Réglages"
        is PermissionDeniedException -> "Accès refusé par l'API (403) : ${t.message}"
        is NotFoundException -> "Modèle introuvable (404) — choisis un autre modèle dans les Réglages"
        is RateLimitException -> "Limite de débit atteinte (429) — réessaie dans un instant"
        is BadRequestException -> "Requête refusée par l'API : ${t.message}"
        is InternalServerException -> "Service Anthropic indisponible — réessaie plus tard"
        is AnthropicServiceException -> "Erreur API : ${t.message}"
        is AnthropicIoException -> "Pas de connexion réseau"
        // Une erreur de chargement de classe du SDK (compat Android) ne doit pas planter l'app
        else -> "$what impossible : ${t.message}"
    }

    /** Vrai si le serveur a refusé le paramètre de repli (`fallbacks`) : on renvoie sans. */
    fun isFallbackRejected(e: BadRequestException): Boolean =
        e.message?.contains("fallback", ignoreCase = true) == true

    fun closeQuietly(client: AnthropicClient?) {
        try {
            client?.close()
        } catch (_: Throwable) {
        }
    }
}
