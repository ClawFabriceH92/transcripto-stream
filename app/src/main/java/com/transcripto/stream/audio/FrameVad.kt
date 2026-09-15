package com.transcripto.stream.audio

/**
 * Détecteur de parole trame par trame : [SileroVad] en production (ONNX), factice en test.
 * `process` est appelé depuis le thread de capture, `reset`/`close` depuis le thread principal.
 */
interface FrameVad : AutoCloseable {
    /** Probabilité de parole (0..1) pour [SileroVad.FRAME] échantillons int16 à partir de [offset]. Lève en cas d'erreur d'inférence. */
    fun process(frame: ShortArray, offset: Int = 0): Float

    /** Remet à zéro l'état récurrent (nouvel enregistrement, reprise après pause). */
    fun reset()

    companion object {
        /** Trame Silero : 512 échantillons à 16 kHz. */
        const val FRAME = 512
        const val FRAME_MS = 32
    }
}
