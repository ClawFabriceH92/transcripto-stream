package com.transcripto.stream.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.io.File
import java.nio.ByteOrder

/**
 * Import d'un audio externe (m4a/AAC, mp3, ogg/opus, amr, flac, wav…) :
 * décodage via MediaCodec puis conversion en WAV PCM 16 kHz mono int16 —
 * le format du pipeline de transcription différée. Tout se fait en flux,
 * sans charger le fichier en mémoire.
 */
object AudioImporter {

    private const val TAG = "AudioImporter"
    private const val TARGET_RATE = 16000
    private const val TIMEOUT_US = 10_000L

    /**
     * Décode [uri] vers [dest] (WAV 16 kHz mono). Retourne null si OK, sinon un
     * message d'erreur ; en cas d'erreur, [dest] est supprimé.
     * [onProgress] reçoit une fraction 0..1 (basée sur les timestamps source).
     */
    fun importToWav(
        context: Context,
        uri: Uri,
        dest: File,
        onProgress: (Float) -> Unit = {},
    ): String? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var writer: WavFileWriter? = null
        var error: String? = null
        try {
            extractor.setDataSource(context, uri, null)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) {
                return "Aucune piste audio dans ce fichier"
            }
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return "Format audio inconnu"
            extractor.selectTrack(trackIndex)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else {
                -1L
            }

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            writer = WavFileWriter(dest)

            // Valeurs de repli si INFO_OUTPUT_FORMAT_CHANGED n'arrive pas avant le 1er buffer
            var srcRate = format.getIntegerOr(MediaFormat.KEY_SAMPLE_RATE, TARGET_RATE)
            var channels = format.getIntegerOr(MediaFormat.KEY_CHANNEL_COUNT, 1)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var resampler: LinearResampler? = null

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var wroteSamples = false
            var dryRuns = 0
            while (!outputDone) {
                var progressed = false
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        progressed = true
                        val buf = codec.getInputBuffer(inIdx)
                        if (buf == null) {
                            error = "Décodeur indisponible"
                            break
                        }
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(
                                inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            val ptsUs = extractor.sampleTime
                            codec.queueInputBuffer(inIdx, 0, n, ptsUs, 0)
                            if (durationUs > 0 && ptsUs >= 0) {
                                onProgress((ptsUs.toFloat() / durationUs).coerceIn(0f, 1f))
                            }
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        progressed = true
                        val of = codec.outputFormat
                        val newRate = of.getIntegerOr(MediaFormat.KEY_SAMPLE_RATE, srcRate)
                        channels = of.getIntegerOr(MediaFormat.KEY_CHANNEL_COUNT, channels)
                        pcmEncoding = of.getIntegerOr(
                            MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT
                        )
                        if (pcmEncoding != AudioFormat.ENCODING_PCM_16BIT &&
                            pcmEncoding != AudioFormat.ENCODING_PCM_FLOAT
                        ) {
                            error = "Format PCM non géré ($pcmEncoding)"
                            break
                        }
                        // Ne recréer le rééchantillonneur que si le taux change : le
                        // recréer à taux constant perdrait l'état entre deux blocs
                        if (resampler == null || newRate != srcRate) {
                            resampler = LinearResampler(newRate, TARGET_RATE)
                        }
                        srcRate = newRate
                    }
                    outIdx >= 0 -> {
                        progressed = true
                        if (info.size > 0) {
                            val ob = codec.getOutputBuffer(outIdx)
                            if (ob != null) {
                                ob.position(info.offset)
                                ob.limit(info.offset + info.size)
                                ob.order(ByteOrder.nativeOrder())
                                val shorts = if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                    val fb = ob.asFloatBuffer()
                                    val floats = FloatArray(fb.remaining())
                                    fb.get(floats)
                                    PcmResampler.floatsToShorts(floats, floats.size)
                                } else {
                                    val sb = ob.asShortBuffer()
                                    val arr = ShortArray(sb.remaining())
                                    sb.get(arr)
                                    arr
                                }
                                val frames = shorts.size / channels
                                if (frames > 0) {
                                    val mono = PcmResampler.downmixToMono(shorts, frames, channels)
                                    val rs = resampler ?: LinearResampler(srcRate, TARGET_RATE)
                                        .also { resampler = it }
                                    val out = rs.process(mono, mono.size)
                                    if (out.isNotEmpty()) {
                                        writer.write(out, out.size)
                                        wroteSamples = true
                                    }
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
                // Garde-fou : un codec qui ne progresse plus (fichier tronqué, décodeur
                // OEM défaillant) ne doit pas bloquer l'import — ni l'app — pour toujours.
                if (progressed) {
                    dryRuns = 0
                } else if (++dryRuns > 500) { // ~10 s sans progrès
                    error = "Décodage interrompu (fichier tronqué ?)"
                    break
                }
            }
            if (error == null && !wroteSamples) {
                error = "Fichier audio vide ou indéchiffrable"
            } else if (error == null) {
                onProgress(1f)
            }
        } catch (e: Exception) {
            Log.e(TAG, "importToWav", e)
            error = "Import impossible : ${e.message ?: e.javaClass.simpleName}"
        } finally {
            try {
                writer?.close()
            } catch (_: Exception) {
            }
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            try {
                codec?.release()
            } catch (_: Exception) {
            }
            extractor.release()
        }
        if (error != null) dest.delete()
        return error
    }

    private fun MediaFormat.getIntegerOr(key: String, fallback: Int): Int =
        if (containsKey(key)) getInteger(key) else fallback
}
