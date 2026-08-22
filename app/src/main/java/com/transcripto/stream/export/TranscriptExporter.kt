package com.transcripto.stream.export

import com.transcripto.stream.stt.SegmentData

/**
 * Génération d'exports à partir des segments Whisper : sous-titres SRT et
 * statistiques de temps de parole par intervenant (cas d'usage réunion/audit).
 * Fonctions pures, testables sans Android.
 */
object TranscriptExporter {

    /** 83_500 ms → « 00:01:23,500 » (format horodatage SRT). */
    fun srtTimestamp(ms: Long): String {
        val h = ms / 3_600_000
        val m = (ms % 3_600_000) / 60_000
        val s = (ms % 60_000) / 1000
        val milli = ms % 1000
        return "%02d:%02d:%02d,%03d".format(h, m, s, milli)
    }

    /** Fichier SRT complet à partir des segments horodatés (vide si aucun segment). */
    fun buildSrt(segments: List<SegmentData>): String {
        val sb = StringBuilder()
        var index = 1
        for (seg in segments) {
            val text = seg.text.trim()
            if (text.isEmpty()) continue
            sb.append(index).append('\n')
            sb.append(srtTimestamp(seg.startMs)).append(" --> ").append(srtTimestamp(seg.endMs)).append('\n')
            sb.append(text).append("\n\n")
            index++
        }
        return sb.toString()
    }

    fun formatHms(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%02d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    /**
     * Bloc « temps de parole » : durée cumulée et part de chaque intervenant.
     * speakerIds[i] est l'intervenant (1, 2, …) du segment i, détecté par pitch.
     * Retourne une chaîne vide si moins de deux intervenants (aucune valeur ajoutée).
     */
    fun buildSpeakingStats(segments: List<SegmentData>, speakerIds: List<Int>): String {
        if (segments.size != speakerIds.size) return ""
        val totals = LinkedHashMap<Int, Long>()
        for (i in segments.indices) {
            val dur = (segments[i].endMs - segments[i].startMs).coerceAtLeast(0)
            totals.merge(speakerIds[i], dur, Long::plus)
        }
        if (totals.size < 2) return ""
        val grandTotal = totals.values.sum()
        if (grandTotal <= 0) return ""
        val sb = StringBuilder()
        sb.append("--- Temps de parole (estimation par la voix) ---\n")
        for ((speaker, dur) in totals) {
            val pct = (dur * 100.0 / grandTotal).toInt()
            sb.append("Intervenant ").append(speaker).append(" : ")
                .append(formatHms(dur)).append(" (").append(pct).append(" %)\n")
        }
        return sb.toString().trimEnd()
    }

    private val DURATION_LINE = Regex("""Durée : (?:(\d+):)?(\d+):(\d+)""")

    /** Relit la durée depuis l'en-tête d'un .txt (« Durée : 01:23 » ou « 01:02:03 »). */
    fun parseDurationMs(txtContent: String): Long {
        val m = DURATION_LINE.find(txtContent) ?: return 0L
        val h = m.groupValues[1].ifEmpty { "0" }.toLong()
        val min = m.groupValues[2].toLong()
        val s = m.groupValues[3].toLong()
        return ((h * 3600 + min * 60 + s) * 1000)
    }
}
