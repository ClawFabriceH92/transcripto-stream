package com.transcripto.stream.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupCryptoTest {

    private fun encrypt(passphrase: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        BackupCrypto.encryptingStream(out, passphrase.toCharArray()).use { it.write(payload) }
        return out.toByteArray()
    }

    @Test
    fun roundTrip_restoresPayload() {
        val payload = "contenu de test é à ü".toByteArray()
        val blob = encrypt("ma phrase secrète", payload)
        val back = BackupCrypto.decryptingStream(
            ByteArrayInputStream(blob), "ma phrase secrète".toCharArray()
        ).use { it.readBytes() }
        assertArrayEquals(payload, back)
    }

    @Test
    fun wrongPassphrase_failsNotGarbage() {
        val blob = encrypt("bonne phrase", "données sensibles".toByteArray())
        // Mauvaise phrase : le tag GCM doit invalider la lecture (exception),
        // jamais retourner silencieusement des octets faux.
        val result = runCatching {
            BackupCrypto.decryptingStream(
                ByteArrayInputStream(blob), "mauvaise phrase".toCharArray()
            ).use { it.readBytes() }
        }
        assertTrue(result.isFailure)
    }

    @Test
    fun invalidMagic_isRejectedWithClearError() {
        val result = runCatching {
            BackupCrypto.decryptingStream(
                ByteArrayInputStream("PAS-UNE-SAUVEGARDE".toByteArray()), "x".toCharArray()
            )
        }
        assertTrue(result.exceptionOrNull() is BackupCrypto.InvalidBackupException)
    }

    @Test
    fun zipInsideEncryption_roundTrips() {
        // Le format réel : un zip à l'intérieur du flux chiffré
        val out = ByteArrayOutputStream()
        BackupCrypto.encryptingStream(out, "pass".toCharArray()).use { enc ->
            ZipOutputStream(enc).use { zip ->
                zip.putNextEntry(ZipEntry("réunion.wav"))
                zip.write(ByteArray(1000) { it.toByte() })
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("réunion.txt"))
                zip.write("transcription".toByteArray())
                zip.closeEntry()
            }
        }
        val names = mutableListOf<String>()
        var totalBytes = 0
        BackupCrypto.decryptingStream(ByteArrayInputStream(out.toByteArray()), "pass".toCharArray())
            .use { dec ->
                ZipInputStream(dec).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        names.add(entry.name)
                        totalBytes += zip.readBytes().size
                        entry = zip.nextEntry
                    }
                }
            }
        assertEquals(listOf("réunion.wav", "réunion.txt"), names)
        assertEquals(1000 + "transcription".toByteArray().size, totalBytes)
    }
}
