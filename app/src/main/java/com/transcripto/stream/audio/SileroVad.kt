package com.transcripto.stream.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Détecteur de parole neuronal Silero VAD (v5, ONNX) exécuté par ONNX Runtime :
 * une probabilité de parole par trame de 512 échantillons (32 ms à 16 kHz), avec
 * l'état récurrent et le contexte de 64 échantillons attendus par le modèle.
 *
 * Tampons d'entrée directs alloués une fois (pas d'allocation par trame) ; le
 * tenseur « sr » (constante) est créé une fois. Les appels sont synchronisés :
 * `process` sur le thread de capture, `reset`/`close` depuis le thread principal.
 * Toute erreur d'inférence lève : l'appelant se replie sur la détection par volume.
 */
class SileroVad private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val scalarSampleRate: Boolean,
) : AutoCloseable {

    private val context = FloatArray(CONTEXT)
    private val inputBuf: FloatBuffer = ByteBuffer.allocateDirect((CONTEXT + FRAME) * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val stateBuf: FloatBuffer = ByteBuffer.allocateDirect(2 * 128 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val srTensor: OnnxTensor = OnnxTensor.createTensor(
        env,
        LongBuffer.wrap(longArrayOf(SAMPLE_RATE.toLong())),
        if (scalarSampleRate) longArrayOf() else longArrayOf(1),
    )
    private val lock = Any()
    private var closed = false

    /** Remet à zéro l'état récurrent (à chaque nouvel enregistrement). */
    fun reset() {
        synchronized(lock) {
            context.fill(0f)
            for (i in 0 until 2 * 128) stateBuf.put(i, 0f)
        }
    }

    /**
     * Probabilité de parole (0..1) pour [frame] : exactement [FRAME] échantillons int16.
     * Appel bloquant d'environ une milliseconde.
     */
    fun process(frame: ShortArray, offset: Int = 0): Float {
        synchronized(lock) {
            check(!closed) { "VAD fermée" }
            for (i in 0 until CONTEXT) inputBuf.put(i, context[i])
            for (i in 0 until FRAME) inputBuf.put(CONTEXT + i, frame[offset + i] / 32768f)
            inputBuf.rewind()
            stateBuf.rewind()
            val x = OnnxTensor.createTensor(env, inputBuf, longArrayOf(1, (CONTEXT + FRAME).toLong()))
            val st = OnnxTensor.createTensor(env, stateBuf, longArrayOf(2, 1, 128))
            try {
                session.run(mapOf("input" to x, "state" to st, "sr" to srTensor)).use { res ->
                    @Suppress("UNCHECKED_CAST")
                    val out = (res.get("output").orElse(null) ?: res.get(0)).value as Array<FloatArray>
                    @Suppress("UNCHECKED_CAST")
                    val newState = (res.get("stateN").orElse(null) ?: res.get(1)).value as Array<Array<FloatArray>>
                    for (k in 0 until 2) for (j in 0 until 128) stateBuf.put(k * 128 + j, newState[k][0][j])
                    // Contexte = 64 derniers échantillons de la trame
                    for (i in 0 until CONTEXT) context[i] = frame[offset + FRAME - CONTEXT + i] / 32768f
                    return out[0][0].coerceIn(0f, 1f)
                }
            } finally {
                x.close()
                st.close()
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            try {
                srTensor.close()
            } catch (_: Exception) {
            }
            try {
                session.close()
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        private const val TAG = "SileroVad"
        const val SAMPLE_RATE = 16_000
        /** Taille de trame du modèle à 16 kHz. */
        const val FRAME = 512
        const val FRAME_MS = 32
        private const val CONTEXT = 64
        private const val ASSET = "vad/silero_vad.onnx"

        /**
         * Charge le modèle embarqué ; null si ONNX Runtime ou le modèle sont indisponibles
         * (l'app fonctionne alors avec la détection par volume). À appeler hors du thread principal.
         */
        fun create(context: Context): SileroVad? = try {
            val bytes = context.assets.open(ASSET).use { it.readBytes() }
            val env = OrtEnvironment.getEnvironment()
            val session = OrtSession.SessionOptions().use { opts ->
                opts.setIntraOpNumThreads(1)
                opts.setInterOpNumThreads(1)
                env.createSession(bytes, opts)
            }
            val names = session.inputNames
            if (!names.containsAll(listOf("input", "state", "sr"))) {
                Log.e(TAG, "Modèle inattendu (entrées : $names)")
                session.close()
                null
            } else {
                val srInfo = session.inputInfo["sr"]?.info as? TensorInfo
                val scalar = srInfo?.shape?.isEmpty() ?: true
                // Inférence d'échauffement : valide la forme du tenseur « sr » (scalaire ou [1])
                // avant le premier enregistrement ; l'autre forme est tentée en secours
                warmUp(env, session, scalar)
                    ?: warmUp(env, session, !scalar)
                    ?: run {
                        Log.e(TAG, "Modèle chargé mais inférence impossible")
                        session.close()
                        null
                    }
            }
        } catch (t: Throwable) {
            // UnsatisfiedLinkError, OrtException, IOException… : repli RMS en amont
            Log.e(TAG, "VAD indisponible : ${t.message}")
            null
        }

        private fun warmUp(env: OrtEnvironment, session: OrtSession, scalar: Boolean): SileroVad? {
            val v = try {
                SileroVad(env, session, scalar)
            } catch (t: Throwable) {
                Log.w(TAG, "Tenseur sr (${if (scalar) "scalaire" else "[1]"}) : ${t.message}")
                return null
            }
            return try {
                v.process(ShortArray(FRAME))
                v.reset()
                v
            } catch (t: Throwable) {
                Log.w(TAG, "Échauffement VAD (sr ${if (scalar) "scalaire" else "[1]"}) : ${t.message}")
                try {
                    v.srTensor.close()
                } catch (_: Exception) {
                }
                null
            }
        }
    }
}
