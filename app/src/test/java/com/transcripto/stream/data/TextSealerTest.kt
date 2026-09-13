package com.transcripto.stream.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import javax.crypto.KeyGenerator

class TextSealerTest {

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun roundTripAndMagic() {
        val text = "Transcripto Stream\nDate : 2026-09-13 10:00\n----\n\n[Intervenant 1] Bonjour — « test » ✓ 12 000 €"
        val sealed = TextSealer.seal(text, key)
        assertTrue(TextSealer.isSealed(sealed))
        assertEquals("TSV1", String(sealed.copyOfRange(0, 4), Charsets.US_ASCII))
        assertEquals(text, TextSealer.open(sealed, key))
        // Deux scellements du même texte diffèrent (IV aléatoire)
        assertFalse(sealed.contentEquals(TextSealer.seal(text, key)))
    }

    @Test
    fun plainTextIsNotSealed() {
        assertFalse(TextSealer.isSealed("TSV1 mais trop court".toByteArray()))
        assertFalse(TextSealer.isSealed("Transcripto Stream\n".toByteArray()))
        assertFalse(TextSealer.isSealed(ByteArray(0)))
    }

    @Test
    fun tamperingOrWrongKeyFails() {
        val sealed = TextSealer.seal("secret", key)
        val tampered = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
        try {
            TextSealer.open(tampered, key)
            fail("altération non détectée")
        } catch (e: Exception) {
            // attendu (tag GCM)
        }
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        try {
            TextSealer.open(sealed, other)
            fail("mauvaise clé acceptée")
        } catch (e: Exception) {
            // attendu
        }
    }
}
