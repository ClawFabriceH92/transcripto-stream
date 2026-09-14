package com.transcripto.stream.audio

/**
 * Machine à états au-dessus des probabilités de parole (VAD) : hystérésis entre
 * seuil d'entrée et seuil de sortie, durée minimale de silence avant de clore
 * une phrase, durée minimale de parole pour qu'une phrase compte. Pur Kotlin, testé.
 */
class SpeechGate(
    private val startThreshold: Float = 0.5f,
    private val endThreshold: Float = 0.35f,
    private val minSilenceMs: Int = 500,
    private val minSpeechMs: Int = 250,
) {
    enum class Event { SPEECH_START, SPEECH_END }

    var speaking: Boolean = false
        private set
    private var silenceMs = 0
    private var speechMs = 0

    fun reset() {
        speaking = false
        silenceMs = 0
        speechMs = 0
    }

    /**
     * Alimente une probabilité de parole pour une trame de [frameMs] millisecondes.
     * Retourne l'événement de transition éventuel.
     */
    fun feed(probability: Float, frameMs: Int): Event? {
        if (!speaking) {
            if (probability >= startThreshold) {
                speechMs += frameMs
                if (speechMs >= minSpeechMs) {
                    speaking = true
                    silenceMs = 0
                    return Event.SPEECH_START
                }
            } else {
                // Une trame hésitante ne remet pas l'attaque à zéro : le crédit s'érode
                speechMs = (speechMs - frameMs).coerceAtLeast(0)
            }
            return null
        }
        if (probability < endThreshold) {
            silenceMs += frameMs
            if (silenceMs >= minSilenceMs) {
                speaking = false
                speechMs = 0
                silenceMs = 0
                return Event.SPEECH_END
            }
        } else {
            silenceMs = 0
        }
        return null
    }
}
