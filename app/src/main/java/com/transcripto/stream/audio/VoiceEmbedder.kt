package com.transcripto.stream.audio

/**
 * Calcule l'empreinte (vecteur normalisé L2) du passage [start, end) d'un PCM 16 kHz ;
 * null si le passage est trop court ou l'inférence impossible. Interface d'injection :
 * [SpeakerEmbedder] (ONNX) en production, un encodeur factice dans les tests.
 */
fun interface VoiceEmbedder {
    fun embed(pcm: ShortArray, start: Int, end: Int): FloatArray?
}
