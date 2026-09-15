package com.transcripto.stream.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.KeyGenerator

class BackupManagerTest {

    /** Chiffrement factice : préfixe « ENC: » — suffit à vérifier qui chiffre quoi. */
    private class FakeCipher : WavCipher {
        var failEncrypt = false
        override fun encryptFile(src: File, dest: File): Boolean {
            if (failEncrypt) return false
            dest.writeBytes("ENC:".toByteArray() + src.readBytes())
            return true
        }
        override fun decryptToTemp(encFile: File, cacheDir: File, suffix: String): File? {
            val bytes = encFile.readBytes()
            if (bytes.size < 4 || String(bytes, 0, 4) != "ENC:") return null
            return File(cacheDir, encFile.nameWithoutExtension + suffix + ".wav").apply { writeBytes(bytes.copyOfRange(4, bytes.size)) }
        }
    }

    private lateinit var root: File
    private lateinit var src: RecordingRepository
    private lateinit var dst: RecordingRepository
    private lateinit var cache: File
    private val cipher = FakeCipher()
    private var encryptWav = false
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val pass = "phrase de passe".toCharArray()

    @Before
    fun setUp() {
        root = Files.createTempDirectory("backup").toFile()
        src = RecordingRepository(File(root, "src"))
        dst = RecordingRepository(File(root, "dst"))
        cache = File(root, "cache").apply { mkdirs() }
        TextVault.keyProvider = KeyProvider { key }
        TextVault.enabled = false
        encryptWav = false
    }

    @After
    fun tearDown() {
        TextVault.enabled = false
        root.deleteRecursively()
    }

    private fun manager(repo: RecordingRepository) = BackupManager(repo, cache, cipher) { encryptWav }

    private fun entries(archive: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(BackupCrypto.decryptingStream(ByteArrayInputStream(archive), pass)).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                out[e.name] = zip.readBytes()
                e = zip.nextEntry
            }
        }
        return out
    }

    @Test
    fun exportPutsEverythingInClearThenRestoreSealsAndSuffixesDuplicates() {
        val wavBytes = ByteArray(300) { (it % 7).toByte() }
        File(src.dir, "a.wav").writeBytes(wavBytes)
        TextVault.enabled = true
        TextVault.write(File(src.dir, "a.txt"), "Transcripto Stream\n----\n\nScellé chez l'expéditeur")
        TextVault.enabled = false
        File(src.dir, "a.meta").writeText("{\"dossier\":\"SARL X\"}")
        assertTrue(cipher.encryptFile(File(src.dir, "b-src.wav").apply { writeBytes(wavBytes) }, File(src.dir, "b.wav.enc")))
        File(src.dir, "b-src.wav").delete()
        File(src.dir, "b.txt").writeText("clair")
        File(src.dir, "c.txt").writeText("Transcripto Stream\nDurée : 00:10\n----\n\ntexte seul")
        File(src.dir, "a.wav.tmp").writeText("temporaire d'écriture, pas un enregistrement")
        // Un texte scellé avec une autre clé : illisible, ignoré et compté
        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        File(src.dir, "d.md").writeBytes(TextSealer.seal("# perdu", otherKey))
        File(src.dir, "sub").mkdirs()

        val out = ByteArrayOutputStream()
        val r = manager(src).export(out, pass)
        assertEquals(BackupExport(count = 7, unreadable = 1), r) // le .tmp part tel quel (filtré à la restauration)
        val zip = entries(out.toByteArray())
        assertEquals(setOf("a.meta", "a.txt", "a.wav", "a.wav.tmp", "b.txt", "b.wav", "c.txt"), zip.keys)
        assertTrue(wavBytes.contentEquals(zip.getValue("a.wav")))
        assertTrue("le .enc est déchiffré dans l'archive", wavBytes.contentEquals(zip.getValue("b.wav")))
        assertEquals("Transcripto Stream\n----\n\nScellé chez l'expéditeur", String(zip.getValue("a.txt")))
        assertFalse(TextSealer.isSealed(zip.getValue("a.txt")))
        assertTrue("aucun temporaire de déchiffrement ne reste", cache.listFiles()!!.isEmpty())

        // Restauration avec WAV chiffrés et textes scellés
        encryptWav = true
        TextVault.enabled = true
        val restored = manager(dst).restore(ByteArrayInputStream(out.toByteArray()), pass)
        assertEquals(BackupRestore(count = 6, unsealed = 0), restored) // .tmp ignoré
        assertEquals(listOf("a.meta", "a.txt", "a.wav.enc", "b.txt", "b.wav.enc", "c.txt"), dst.dir.list()!!.sorted())
        assertTrue(TextVault.isSealed(File(dst.dir, "a.txt")))
        assertTrue(TextVault.isSealed(File(dst.dir, "a.meta")))
        assertEquals("Scellé chez l'expéditeur", dst.transcriptBody(File(dst.dir, "a.wav.enc")))
        assertEquals("SARL X", dst.readMeta(File(dst.dir, "a.wav.enc")).dossier)
        assertTrue(String(File(dst.dir, "b.wav.enc").readBytes(), 0, 4) == "ENC:")
        assertEquals(3, dst.list().items.size)

        // Deuxième restauration : jamais destructif, doublons suffixés de façon cohérente par base
        val again = manager(dst).restore(ByteArrayInputStream(out.toByteArray()), pass)
        assertEquals(6, again.count)
        assertEquals(
            listOf("a (2).meta", "a (2).txt", "a (2).wav.enc", "a.meta", "a.txt", "a.wav.enc", "b (2).txt", "b (2).wav.enc", "b.txt", "b.wav.enc", "c (2).txt", "c.txt"),
            dst.dir.list()!!.sorted(),
        )
    }

    @Test
    fun restoreKeepsWavClearWhenEncryptionFailsAndCountsIt() {
        File(src.dir, "a.wav").writeBytes(ByteArray(50))
        val out = ByteArrayOutputStream()
        manager(src).export(out, pass)
        encryptWav = true
        cipher.failEncrypt = true
        val r = manager(dst).restore(ByteArrayInputStream(out.toByteArray()), pass)
        assertEquals(BackupRestore(count = 1, unsealed = 1), r)
        assertEquals(listOf("a.wav"), dst.dir.list()!!.toList())
    }

    @Test
    fun restoreIgnoresPathsAndUnknownTypesAndRejectsBadArchives() {
        val raw = ByteArrayOutputStream()
        ZipOutputStream(BackupCrypto.encryptingStream(raw, pass)).use { zip ->
            for ((name, content) in listOf("../evil.txt" to "x", "dir/x.txt" to "x", "ok.txt" to "bien", "script.sh" to "x", "no*name?.wav" to "y", "sub/" to "")) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        val r = manager(dst).restore(ByteArrayInputStream(raw.toByteArray()), pass)
        assertEquals(2, r.count)
        assertEquals(listOf("noname.wav", "ok.txt"), dst.dir.list()!!.sorted())
        // Mauvaise phrase de passe : exception, jamais des fichiers faux
        try {
            manager(dst).restore(ByteArrayInputStream(raw.toByteArray()), "autre".toCharArray())
            fail("phrase de passe incorrecte acceptée")
        } catch (e: BackupCrypto.InvalidBackupException) {
            fail("l'en-tête est valide : l'échec doit venir du déchiffrement")
        } catch (e: Exception) {
            // attendu
        }
        assertEquals(2, dst.dir.list()!!.size)
        try {
            manager(dst).restore(ByteArrayInputStream("pas une archive".toByteArray()), pass)
            fail("fichier quelconque accepté")
        } catch (e: BackupCrypto.InvalidBackupException) {
            assertTrue(e.message!!.contains("pas une sauvegarde"))
        }
    }
}
