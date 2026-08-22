package com.transcripto.stream.export

import com.transcripto.stream.stt.SegmentData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptExporterTest {

    @Test
    fun srtTimestamp_formatsHoursMinutesSecondsMillis() {
        assertEquals("00:00:00,000", TranscriptExporter.srtTimestamp(0))
        assertEquals("00:01:23,500", TranscriptExporter.srtTimestamp(83_500))
        assertEquals("01:02:03,045", TranscriptExporter.srtTimestamp(3_723_045))
    }

    @Test
    fun buildSrt_numbersAndSkipsBlankSegments() {
        val srt = TranscriptExporter.buildSrt(
            listOf(
                SegmentData("Bonjour à tous.", 0, 1500),
                SegmentData("   ", 1500, 2000),
                SegmentData("On commence.", 2000, 3200),
            )
        )
        val expected = "1\n00:00:00,000 --> 00:00:01,500\nBonjour à tous.\n\n" +
            "2\n00:00:02,000 --> 00:00:03,200\nOn commence.\n\n"
        assertEquals(expected, srt)
    }

    @Test
    fun buildSrt_emptyWhenNoSegments() {
        assertEquals("", TranscriptExporter.buildSrt(emptyList()))
    }

    @Test
    fun speakingStats_sumsPerSpeakerWithPercent() {
        val segments = listOf(
            SegmentData("a", 0, 60_000),
            SegmentData("b", 60_000, 90_000),
            SegmentData("c", 90_000, 120_000),
        )
        val stats = TranscriptExporter.buildSpeakingStats(segments, listOf(1, 2, 1))
        assertTrue(stats.contains("Intervenant 1 : 01:30 (75 %)"))
        assertTrue(stats.contains("Intervenant 2 : 00:30 (25 %)"))
    }

    @Test
    fun speakingStats_emptyForSingleSpeakerOrMismatch() {
        val segments = listOf(SegmentData("a", 0, 1000), SegmentData("b", 1000, 2000))
        assertEquals("", TranscriptExporter.buildSpeakingStats(segments, listOf(1, 1)))
        assertEquals("", TranscriptExporter.buildSpeakingStats(segments, listOf(1)))
    }

    @Test
    fun parseDurationMs_readsTxtHeader() {
        assertEquals(83_000L, TranscriptExporter.parseDurationMs("Transcripto Stream\nDurée : 01:23\n----\n"))
        assertEquals(3_723_000L, TranscriptExporter.parseDurationMs("Durée : 01:02:03"))
        assertEquals(0L, TranscriptExporter.parseDurationMs("pas de durée ici"))
    }
}
