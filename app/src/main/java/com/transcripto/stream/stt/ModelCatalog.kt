package com.transcripto.stream.stt

/**
 * Un modèle Whisper utilisable par l'app : soit embarqué dans l'APK (Base),
 * soit téléchargeable à la demande (dépôt ggml officiel de whisper.cpp).
 */
data class WhisperModel(
    val id: String,
    val label: String,
    val fileName: String,
    /** null = embarqué dans l'APK (assets), sinon URL de téléchargement direct. */
    val url: String?,
    val approxMb: Int,
    val description: String,
)

object ModelCatalog {

    const val EMBEDDED_ID = "base"

    private const val HF_BASE = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"

    val MODELS = listOf(
        WhisperModel(
            id = EMBEDDED_ID,
            label = "Base (embarqué)",
            fileName = "ggml-base.bin",
            url = null,
            approxMb = 142,
            description = "Fourni avec l'app. Rapide, qualité correcte.",
        ),
        WhisperModel(
            id = "small-q5_1",
            label = "Small quantisé — recommandé",
            fileName = "ggml-small-q5_1.bin",
            url = "$HF_BASE/ggml-small-q5_1.bin",
            approxMb = 190,
            description = "Nettement meilleur en français, bon compromis vitesse/mémoire.",
        ),
        WhisperModel(
            id = "small",
            label = "Small",
            fileName = "ggml-small.bin",
            url = "$HF_BASE/ggml-small.bin",
            approxMb = 488,
            description = "Small non quantisé : un peu plus précis, plus lourd en RAM.",
        ),
        WhisperModel(
            id = "medium-q5_0",
            label = "Medium quantisé",
            fileName = "ggml-medium-q5_0.bin",
            url = "$HF_BASE/ggml-medium-q5_0.bin",
            approxMb = 539,
            description = "Très bonne qualité, lent — plutôt pour la transcription différée.",
        ),
        WhisperModel(
            id = "large-v3-turbo-q5_0",
            label = "Large-v3 Turbo quantisé",
            fileName = "ggml-large-v3-turbo-q5_0.bin",
            url = "$HF_BASE/ggml-large-v3-turbo-q5_0.bin",
            approxMb = 574,
            description = "La meilleure qualité. Téléphone récent conseillé (~1,5 Go de RAM libre), transcription différée de préférence.",
        ),
    )

    /** Modèle par id, avec repli sur le modèle embarqué si l'id est inconnu. */
    fun byId(id: String): WhisperModel = MODELS.firstOrNull { it.id == id } ?: MODELS.first()
}
