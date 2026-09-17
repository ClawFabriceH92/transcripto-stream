package com.transcripto.stream.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.nio.FloatBuffer

/**
 * Empreinte de locuteur (WeSpeaker CAM++, VoxCeleb, 512 dimensions) calculée sur l'appareil
 * avec ONNX Runtime : log-mel Kaldi 80 bandes ([Fbank]), moyenne temporelle soustraite,
 * empreinte normalisée L2. Entrée : PCM int16 16 kHz. Les appels sont synchronisés.
 */
class SpeakerEmbedder private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
) : VoiceEmbedder, AutoCloseable {

    private val lock = Any()
    private var closed = false

    /** Empreinte du passage [start, end) ; null s'il fait moins de [MIN_SAMPLES] échantillons. */
    override fun embed(pcm: ShortArray, start: Int, end: Int): FloatArray? {
        val s = start.coerceAtLeast(0)
        val e = end.coerceAtMost(pcm.size)
        if (e - s < MIN_SAMPLES) return null
        val feats = Fbank.meanNormalize(Fbank.compute(pcm, s, e))
        if (feats.isEmpty()) return null
        synchronized(lock) {
            check(!closed) { "Empreintes fermées" }
            val buf = FloatBuffer.allocate(feats.size * Fbank.NUM_BINS)
            for (row in feats) buf.put(row)
            buf.rewind()
            OnnxTensor.createTensor(env, buf, longArrayOf(1, feats.size.toLong(), Fbank.NUM_BINS.toLong())).use { x ->
                session.run(mapOf(INPUT to x)).use { out ->
                    @Suppress("UNCHECKED_CAST")
                    val raw = (out[0].value as Array<FloatArray>)[0]
                    if (raw.any { !it.isFinite() }) return null
                    val n = Fbank.l2(raw)
                    return if (n == 0f) null else FloatArray(raw.size) { raw[it] / n }
                }
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            try {
                session.close()
            } catch (_: Throwable) {
            }
        }
    }

    companion object {
        private const val TAG = "SpeakerEmbedder"
        const val ASSET = "speaker/wespeaker_voxceleb_campplus.onnx"
        private const val INPUT = "feats"
        /** Un passage d'au moins une seconde : en dessous, l'empreinte n'est pas fiable. */
        const val MIN_SAMPLES = Fbank.SAMPLE_RATE
        const val DIM = 512

        /** Charge le modèle embarqué ; null si absent ou si ONNX Runtime est indisponible (repli par hauteur de voix). */
        fun create(context: Context): SpeakerEmbedder? = try {
            val bytes = context.assets.open(ASSET).use { it.readBytes() }
            fromBytes(bytes)
        } catch (t: Throwable) {
            Log.e(TAG, "Empreintes de locuteur indisponibles : ${t.message}")
            null
        }

        /** Session à partir des octets du modèle (assets en production, fichier en test JVM). */
        fun fromBytes(bytes: ByteArray): SpeakerEmbedder? {
            val env = OrtEnvironment.getEnvironment()
            val session = OrtSession.SessionOptions().use { opts ->
                opts.setIntraOpNumThreads(2)
                opts.setInterOpNumThreads(1)
                env.createSession(bytes, opts)
            }
            if (!session.inputNames.contains(INPUT)) {
                Log.e(TAG, "Modèle inattendu (entrées : ${session.inputNames})")
                session.close()
                return null
            }
            return SpeakerEmbedder(env, session)
        }
    }
}
