package com.transcripto.stream.data

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator

class TextVaultTest {

    private lateinit var dir: File
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("vault").toFile()
        TextVault.keyProvider = KeyProvider { key }
        TextVault.enabled = false
    }

    @After
    fun tearDown() {
        TextVault.enabled = false
        dir.deleteRecursively()
    }

    @Test
    fun migrationRoundTripKeepsDatesAndCountsUnreadable() {
        val old = 1_600_000_000_000L
        val txt = File(dir, "a.txt").apply { writeText("Transcripto Stream\n----\n\nclair"); setLastModified(old) }
        val meta = File(dir, "a.meta").apply { writeText("{\"dossier\":\"X\"}"); setLastModified(old + 1000) }
        val wav = File(dir, "a.wav").apply { writeBytes(ByteArray(100) { 7 }); setLastModified(old) }
        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val lost = File(dir, "b.md").apply { writeBytes(TextSealer.seal("# perdu", otherKey)) }
        File(dir, "sub").mkdirs()

        assertEquals(VaultMigration(rewritten = 2, unreadable = 0), TextVault.migrate(dir, seal = true))
        assertTrue(TextVault.isSealed(txt))
        assertTrue(TextVault.isSealed(meta))
        assertEquals("date de modification conservée", old, txt.lastModified())
        assertEquals(old + 1000, meta.lastModified())
        assertArrayEquals("les WAV ne sont pas touchés", ByteArray(100) { 7 }, wav.readBytes())
        assertEquals("Transcripto Stream\n----\n\nclair", TextVault.read(txt))
        // Idempotent : rien à réécrire, le fichier scellé avec une autre clé reste compté illisible
        assertEquals(VaultMigration(0, 0), TextVault.migrate(dir, seal = true))
        assertEquals(VaultMigration(rewritten = 2, unreadable = 1), TextVault.migrate(dir, seal = false))
        assertFalse(TextVault.isSealed(txt))
        assertEquals("{\"dossier\":\"X\"}", meta.readText())
        assertTrue("fichier illisible laissé tel quel", TextSealer.isSealed(lost.readBytes()))
        assertEquals(old, txt.lastModified())
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun writeIsAtomicSealsWhenEnabledAndFallsBackToClear() {
        val f = File(dir, "n.txt")
        assertTrue(TextVault.write(f, "un"))
        assertFalse(TextVault.isSealed(f))
        TextVault.enabled = true
        assertTrue(TextVault.write(f, "deux"))
        assertTrue(TextVault.isSealed(f))
        assertEquals("deux", TextVault.read(f))
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") })
        // Clé indisponible : texte conservé en clair plutôt que perdu, signalé par false
        TextVault.keyProvider = KeyProvider { throw IllegalStateException("KeyStore HS") }
        assertFalse(TextVault.write(f, "trois"))
        assertEquals("trois", f.readText())
        assertEquals("trois", TextVault.read(f))
        // Temporaire d'écriture abandonné par une mort du process : purgé
        File(dir, "z.txt.tmp").writeText("reste")
        TextVault.purgeTemporaries(dir)
        assertFalse(File(dir, "z.txt.tmp").exists())
    }

    @Test
    fun isSealedReadsOnlyTheHeader() {
        assertFalse(TextVault.isSealed(File(dir, "absent.txt")))
        assertFalse(TextVault.isSealed(File(dir, "short.txt").apply { writeText("TSV1") }))
        assertFalse(TextVault.isSealed(File(dir, "plain.txt").apply { writeText("x".repeat(100)) }))
        assertTrue(TextVault.isSealed(File(dir, "s.txt").apply { writeBytes(TextSealer.seal("x", key)) }))
        assertTrue(TextVault.isTextFile("a.meta"))
        assertFalse(TextVault.isTextFile("a.wav.enc"))
    }
}
