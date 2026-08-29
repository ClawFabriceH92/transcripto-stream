package com.transcripto.stream.audio

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import kotlin.concurrent.thread

/**
 * Capture audio continue en PCM brut 16 kHz mono (int16) — le format attendu par whisper.cpp.
 *
 * Chaque bloc lu est transmis au callback [onSamples] depuis un thread dédié.
 */
class PcmAudioRecorder(
    val sampleRate: Int = 16000,
    /** Appelé si la capture meurt sans stop() (micro préempté, erreur de lecture). */
    private val onStopped: (() -> Unit)? = null,
    private val onSamples: (buffer: ShortArray, count: Int) -> Unit,
) {

    @Volatile
    var isRecording: Boolean = false
        private set

    private var audioRecord: AudioRecord? = null
    private var recordThread: Thread? = null

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(): Boolean {
        if (isRecording) return true

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) return false

        // Tampon d'environ 100 ms
        val bufBytes = maxOf(minBuf, sampleRate * 2 * 2 / 10)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufBytes,
            )
        } catch (e: Exception) {
            return false
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }

        audioRecord = record
        isRecording = true

        recordThread = thread(name = "pcm-recorder") {
            val buf = ShortArray(bufBytes / 2)
            try {
                record.startRecording()
            } catch (e: IllegalStateException) {
                isRecording = false
                record.release()
                if (audioRecord === record) audioRecord = null
                onStopped?.invoke()
                return@thread
            }
            while (isRecording) {
                val n = try {
                    record.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                } catch (e: Exception) {
                    -1 // capture morte : sortir de la boucle (0 tournerait à vide en boucle chaude)
                }
                if (n > 0) {
                    onSamples(buf, n)
                } else if (n < 0) {
                    break
                }
            }
            // stop() n'a pas été appelé → la capture est morte toute seule
            val unexpected = isRecording
            isRecording = false
            try {
                record.stop()
            } catch (_: IllegalStateException) {
            }
            record.release()
            if (audioRecord === record) audioRecord = null
            if (unexpected) onStopped?.invoke()
        }
        return true
    }

    fun stop() {
        isRecording = false
        recordThread?.join(1000)
        recordThread = null
        audioRecord = null
    }
}
