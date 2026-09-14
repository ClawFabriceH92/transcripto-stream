package com.transcripto.stream.summary

import com.transcripto.stream.data.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeChaptersTest {

    @Test
    fun parsesJsonArrayEvenWhenWrappedInProse() {
        val text = "Voici le découpage :\n```json\n[{\"start\": \"00:00\", \"title\": \"Ouverture de séance.\"}," +
            " {\"start\": \"[12:30]\", \"title\": \"Stocks et provisions\"}, {\"start\": \"1:05:07\", \"title\": \"Questions diverses\"}," +
            " {\"start\": \"12:30\", \"title\": \"Doublon\"}, {\"start\": \"n/a\", \"title\": \"Ignoré\"}, {\"start\": \"20:00\", \"title\": \"\"}]\n```"
        val chapters = ClaudeChapters.parse(text)
        assertEquals(
            listOf(Chapter(0L, "Ouverture de séance"), Chapter(750_000L, "Stocks et provisions"), Chapter(3_907_000L, "Questions diverses")),
            chapters,
        )
    }

    @Test
    fun rejectsGarbage() {
        assertTrue(ClaudeChapters.parse("pas de json ici").isEmpty())
        assertTrue(ClaudeChapters.parse("[{\"start\": \"00:00\"").isEmpty())
        assertNull(ClaudeChapters.clockToMs("abc"))
        assertEquals(65_000L, ClaudeChapters.clockToMs("01:05"))
        assertEquals(3_665_000L, ClaudeChapters.clockToMs("1:01:05"))
    }
}
