package com.transcripto.stream.data

import com.transcripto.stream.stt.SegmentData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
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

    @Test
    fun confidenceIsStoredWhenKnownAndDefaultsOtherwise() {
        val json = SegmentsCodec.toJson(listOf(SegmentData("sûr", 0, 1000, confidence = 0.91234f), SegmentData("ancien", 1000, 2000)), listOf(1, 1))
        assertTrue(json, json.contains("\"c\":0.912"))
        val back = SegmentsCodec.fromJson(json)
        assertEquals(0.912f, back[0].confidence, 0.0005f)
        assertEquals(-1f, back[1].confidence, 0f)
        assertFalse(json.substringAfter("ancien").contains("\"c\""))
        val again = SegmentsCodec.fromJson(SegmentsCodec.toJsonStored(back))
        assertEquals(back, again)
        // Fichiers antérieurs (sans « c ») : confiance inconnue
        assertEquals(-1f, SegmentsCodec.fromJson("{\"segments\":[{\"s\":0,\"e\":1,\"t\":\"x\"}]}")[0].confidence, 0f)
    }
}
