package com.transcripto.stream.data

import com.transcripto.stream.stt.SegmentData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentsCodecTest {

    @Test
    fun roundTrip_keepsSegmentsAndSpeakers() {
        val segments = listOf(
            SegmentData("Bonjour à tous.", 0, 2000),
            SegmentData("   ", 2000, 2500), // blanc : exclu
            SegmentData("On commence l'audit.", 2500, 6000),
        )
        val json = SegmentsCodec.toJson(segments, listOf(1, 1, 2))
        val back = SegmentsCodec.fromJson(json)
        assertEquals(2, back.size)
        assertEquals(StoredSegment(0, 2000, "Bonjour à tous.", 1), back[0])
        assertEquals(StoredSegment(2500, 6000, "On commence l'audit.", 2), back[1])
    }

    @Test
    fun fromJson_invalidReturnsEmpty() {
        assertTrue(SegmentsCodec.fromJson("pas du json").isEmpty())
        assertTrue(SegmentsCodec.fromJson("{}").isEmpty())
    }

    @Test
    fun toJson_missingSpeakerDefaultsTo1() {
        val json = SegmentsCodec.toJson(listOf(SegmentData("a", 0, 100)), emptyList())
        assertEquals(1, SegmentsCodec.fromJson(json).single().speaker)
    }
}
