package com.transcripto.stream.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class RecordingNamesTest {

    @Test
    fun baseName_stripsKnownSuffixes() {
        assertEquals("réunion client", RecordingNames.baseName("réunion client.wav"))
        assertEquals("20260809_1435-1530", RecordingNames.baseName("20260809_1435-1530.wav.enc"))
        assertEquals("dictée", RecordingNames.baseName("dictée.txt"))
        assertEquals("sans-suffixe", RecordingNames.baseName("sans-suffixe"))
    }

    @Test
    fun suffix_isPreservedOnRename() {
        assertEquals(".wav", RecordingNames.suffix("a.wav"))
        assertEquals(".wav.enc", RecordingNames.suffix("a.wav.enc"))
        assertEquals(".txt", RecordingNames.suffix("a.txt"))
        val renamed = RecordingNames.renamed(File("/tmp/rec/a.wav.enc"), "dossier X")
        assertEquals("dossier X.wav.enc", renamed.name)
    }

    @Test
    fun renamed_keepsDotBeforeExtension() {
        // Régression : l'ancien code produisait « nomwav » (sans point) et
        // l'enregistrement renommé disparaissait de la liste.
        val renamed = RecordingNames.renamed(File("/tmp/rec/a.wav"), "audit")
        assertEquals("audit.wav", renamed.name)
    }

    @Test
    fun sanitize_removesForbiddenChars() {
        assertEquals("dossier 2026", RecordingNames.sanitize("  dossier: 2026? "))
        assertEquals("ab", RecordingNames.sanitize("a\\/:*?\"<>|b"))
    }

    @Test
    fun sanitize_stripsReservedSuffixes() {
        // « notes.txt » saisi comme nom produirait « notes.txt.wav » au baseName instable
        assertEquals("notes", RecordingNames.sanitize("notes.txt"))
        assertEquals("notes", RecordingNames.sanitize("notes.txt.wav"))
        assertEquals("audio", RecordingNames.sanitize("audio.wav.enc"))
        assertEquals("réunion 2.1", RecordingNames.sanitize("réunion 2.1"))
    }

    @Test
    fun siblings_shareTheBaseName() {
        val enc = File("/tmp/rec/réunion.wav.enc")
        assertEquals("réunion.txt", RecordingNames.txtSibling(enc).name)
        assertEquals("réunion.srt", RecordingNames.srtSibling(enc).name)
    }

    @Test
    fun kinds_areDetected() {
        assertEquals(true, RecordingNames.isAudio("a.wav"))
        assertEquals(true, RecordingNames.isAudio("a.wav.enc"))
        assertEquals(false, RecordingNames.isAudio("a.txt"))
        assertEquals(true, RecordingNames.isTextOnly("a.txt"))
    }
}
