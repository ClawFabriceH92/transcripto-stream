package com.transcripto.stream.summary

import com.anthropic.client.AnthropicClient
import com.anthropic.errors.BadRequestException
import com.anthropic.models.beta.AnthropicBeta
import com.anthropic.models.beta.messages.BetaMessage
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.MessageCreateParams as BetaMessageCreateParams
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.transcripto.stream.data.Chapter
import org.json.JSONArray

sealed class AiChaptersResult {
    data class Ok(val chapters: List<Chapter>, val model: String) : AiChaptersResult()
    data class Failed(val message: String) : AiChaptersResult()
}

/**
 * Chapitrage par Claude : la transcription horodatée est découpée en chapitres
 * thématiques titrés. Même périmètre que la synthèse IA (texte seul, clé de
 * l'utilisateur, appel synchrone hors thread principal). La réponse attendue est
 * un tableau JSON ; le parseur tolère du texte autour.
 */
object ClaudeChapters {

    private const val MAX_TOKENS = 2_000L

    private val SYSTEM = """
        Tu es l'assistant d'un cabinet d'expertise comptable et de commissariat aux comptes. On te fournit la
        transcription automatique horodatée d'un enregistrement (une ligne par passage, « [mm:ss] Intervenant : texte »,
        ou « [h:mm:ss] » au-delà d'une heure).
        Découpe-la en chapitres thématiques : entre 3 et 12, chacun d'au moins deux minutes, en suivant les vrais
        changements de sujet (pas un chapitre par intervention). Le premier chapitre commence au premier horodatage.

        Réponds UNIQUEMENT avec un tableau JSON, sans commentaire ni Markdown, de la forme :
        [{"start": "mm:ss", "title": "Titre court"}, …]
        — « start » reprend exactement un horodatage présent dans la transcription (début du chapitre) ;
        — « title » : 2 à 7 mots en français, factuel, sans ponctuation finale, sans numéro de chapitre.
    """.trimIndent()

    fun detect(apiKey: String, modelId: String, title: String, timedTranscript: String): AiChaptersResult {
        if (timedTranscript.isBlank()) return AiChaptersResult.Failed("Transcription vide")
        val (body, truncated) = ClaudeSupport.truncate(timedTranscript.trim())
        val user = buildString {
            append("Titre : ").append(title.ifBlank { "Enregistrement" }).append('\n')
            if (truncated) append("(Transcription tronquée : seule la première partie est fournie.)\n")
            append("\nTranscription :\n<<<\n").append(body).append("\n>>>")
        }
        val client: AnthropicClient = try {
            ClaudeSupport.newClient(apiKey)
        } catch (t: Throwable) {
            return AiChaptersResult.Failed("Chapitrage IA indisponible : ${t.message}")
        }
        return try {
            val (text, model) = if (modelId == ClaudeSupport.MODEL_OPUS && !ClaudeSupport.fallbacksRejected) {
                try {
                    callBeta(client, modelId, user)
                } catch (e: BadRequestException) {
                    if (ClaudeSupport.isFallbackRejected(e)) callStable(client, modelId, user) else throw e
                }
            } else {
                callStable(client, modelId, user)
            }
            val chapters = parse(text)
            if (chapters.isEmpty()) AiChaptersResult.Failed("Réponse du modèle inexploitable") else AiChaptersResult.Ok(chapters, model)
        } catch (t: Throwable) {
            AiChaptersResult.Failed(ClaudeSupport.describe(t, "Chapitrage IA"))
        } finally {
            ClaudeSupport.closeQuietly(client)
        }
    }

    /** (texte de la réponse, modèle) ; lève sur refus ou réponse vide. */
    private fun callBeta(client: AnthropicClient, modelId: String, user: String): Pair<String, String> {
        val params = BetaMessageCreateParams.builder()
            .model(modelId)
            .maxTokens(MAX_TOKENS)
            .system(SYSTEM)
            .addUserMessage(user)
            .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
            .fallbacksDefault()
            .addBeta(AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01)
            .build()
        val msg: BetaMessage = client.beta().messages().create(params)
        val stop = msg.stopReason().map { it.toString() }.orElse("")
        if (stop.contains("refusal", ignoreCase = true)) throw IllegalStateException("Chapitrage refusé par les filtres de sécurité du modèle")
        val text = msg.content().mapNotNull { b -> b.text().map { it.text() }.orElse(null) }.joinToString("\n").trim()
        if (text.isBlank()) throw IllegalStateException("Réponse vide du modèle")
        return text to msg.model().asString()
    }

    private fun callStable(client: AnthropicClient, modelId: String, user: String): Pair<String, String> {
        val builder = MessageCreateParams.builder()
            .model(modelId)
            .maxTokens(MAX_TOKENS)
            .system(SYSTEM)
            .addUserMessage(user)
        if (modelId != ClaudeSupport.MODEL_HAIKU) {
            builder.outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
        }
        val msg: Message = client.messages().create(builder.build())
        val stop = msg.stopReason().map { it.toString() }.orElse("")
        if (stop.contains("refusal", ignoreCase = true)) throw IllegalStateException("Chapitrage refusé par les filtres de sécurité du modèle")
        val text = msg.content().mapNotNull { b -> b.text().map { it.text() }.orElse(null) }.joinToString("\n").trim()
        if (text.isBlank()) throw IllegalStateException("Réponse vide du modèle")
        return text to msg.model().asString()
    }

    private val CLOCK = Regex("^(?:(\\d{1,2}):)?(\\d{1,3}):(\\d{2})$")

    /** Tableau JSON [{"start":"mm:ss","title":"…"}] extrait d'une réponse éventuellement bavarde. */
    fun parse(text: String): List<Chapter> {
        val end = text.lastIndexOf(']')
        if (end < 0) return emptyList()
        // Le tableau commence au premier « [ » qui donne un JSON valide (une prose du type
        // « Voici [le] découpage » précède parfois la réponse)
        var arr: JSONArray? = null
        var start = text.indexOf('[')
        while (start in 0 until end && arr == null) {
            arr = try {
                JSONArray(text.substring(start, end + 1))
            } catch (e: Exception) {
                null
            }
            if (arr == null) start = text.indexOf('[', start + 1)
        }
        if (arr == null) return emptyList()
        val out = ArrayList<Chapter>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val ms = clockToMs(o.optString("start", "").trim()) ?: continue
            val title = o.optString("title", "").trim().trimEnd('.', ' ')
            if (title.isEmpty()) continue
            out += Chapter(ms, title)
        }
        val sorted = out.sortedBy { it.startMs }
        // Doublons d'horodatage : on garde le premier
        return sorted.filterIndexed { i, c -> i == 0 || c.startMs != sorted[i - 1].startMs }
    }

    /** « 5:07 », « 05:07 » ou « 1:05:07 » → millisecondes ; null si illisible. */
    fun clockToMs(s: String): Long? {
        val m = CLOCK.find(s.trim('[', ']', ' ')) ?: return null
        val h = m.groupValues[1].toLongOrNull() ?: 0L
        val min = m.groupValues[2].toLong()
        val sec = m.groupValues[3].toLong()
        return ((h * 3600 + min * 60 + sec) * 1000)
    }
}
