package com.transcripto.stream.stt

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Un segment transcrit avec ses timestamps.
 */
data class SegmentData(
    val text: String,
    val startMs: Long,
    val endMs: Long,
)

/**
 * Résultat d'une transcription de fenêtre.
 */
data class StreamResult(
    val fullText: String,
    val segments: List<SegmentData>,
    val error: String? = null,
)

/**
 * Pont JNI vers whisper.cpp — transcription d'un buffer PCM brut.
 *
 * Native library: libwhisper.so (compilée avec whisper_jni.cpp)
 */
class WhisperStreamEngine {

    private var nativeHandle: Long = 0L

    @Volatile
    var isLoaded: Boolean = false
        private set

    val nativeLibAvailable: Boolean
        get() = NativeHolder.available

    suspend fun loadModel(modelPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (!NativeHolder.available) {
            return@withContext Result.failure(
                IllegalStateException(
                    "libwhisper.so non chargée : ${NativeHolder.loadError ?: "absente de l'APK"}"
                )
            )
        }
        try {
            nativeHandle = nativeLoadModel(modelPath)
            isLoaded = true
            Result.success(Unit)
        } catch (e: Throwable) {
            isLoaded = false
            Result.failure(IllegalStateException("Échec chargement modèle : ${e.message}", e))
        }
    }

    suspend fun unloadModel(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (nativeHandle != 0L) {
                nativeUnloadModel(nativeHandle)
                nativeHandle = 0L
            }
            isLoaded = false
            Result.success(Unit)
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /**
     * Transcrit un buffer PCM (int16 LE, mono, 16 kHz) — bloquant, à appeler hors du main thread.
     */
    suspend fun transcribeBuffer(
        pcmBytes: ByteArray,
        language: String = "fr",
    ): StreamResult = withContext(Dispatchers.IO) {
        if (!isLoaded) {
            return@withContext StreamResult("", emptyList(), "Modèle non chargé")
        }
        try {
            val json = nativeTranscribeBuffer(nativeHandle, pcmBytes, language)
            val obj = JSONObject(json)
            if (obj.has("error")) {
                StreamResult("", emptyList(), obj.getString("error"))
            } else {
                val segments = mutableListOf<SegmentData>()
                val arr = obj.optJSONArray("segments")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val seg = arr.getJSONObject(i)
                        segments.add(
                            SegmentData(
                                text = seg.optString("text", ""),
                                startMs = seg.optLong("start_ms", 0L),
                                endMs = seg.optLong("end_ms", 0L),
                            )
                        )
                    }
                }
                StreamResult(obj.optString("full_text", ""), segments)
            }
        } catch (e: Exception) {
            Log.e(TAG, "transcribeBuffer: ${e.message}")
            StreamResult("", emptyList(), e.message)
        }
    }

    // ---- JNI stubs ----
    private external fun nativeLoadModel(modelPath: String): Long
    private external fun nativeTranscribeBuffer(handle: Long, pcm: ByteArray, language: String): String
    private external fun nativeUnloadModel(handle: Long)

    private object NativeHolder {
        var loadError: String? = null
        val available: Boolean = try {
            System.loadLibrary("whisper")
            true
        } catch (e: UnsatisfiedLinkError) {
            // Le message contient la vraie cause (ex: "dlopen failed: library libomp.so not found")
            Log.e(TAG, "Échec chargement libwhisper.so : ${e.message}")
            loadError = e.message
            false
        }
    }

    companion object {
        private const val TAG = "WhisperStream"
    }
}
