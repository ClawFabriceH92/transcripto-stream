package com.transcripto.stream.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class RecordingNamesMdTest {
    @Test
    fun markdownSummaryIsASibling() {
        assertEquals("réunion", RecordingNames.baseName("réunion.md"))
        assertEquals(".md", RecordingNames.suffix("réunion.md"))
        assertEquals("réunion.md", RecordingNames.mdSibling(File("/tmp/réunion.wav.enc")).name)
        assertEquals("notes", RecordingNames.sanitize("notes.md"))
    }
}
