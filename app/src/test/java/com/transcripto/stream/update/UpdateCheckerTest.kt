package com.transcripto.stream.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun `compareVersions - égalité`() {
        assertEquals(0, UpdateChecker.compareVersions("0.2.0", "0.2.0"))
        assertEquals(0, UpdateChecker.compareVersions("1.0", "1.0.0"))
        assertEquals(0, UpdateChecker.compareVersions("0.3", "0.3.0"))
    }

    @Test
    fun `compareVersions - plus récent`() {
        assert(UpdateChecker.compareVersions("0.3.0", "0.2.0") > 0)
        assert(UpdateChecker.compareVersions("1.0.0", "0.9.9") > 0)
        assert(UpdateChecker.compareVersions("0.10.0", "0.9.0") > 0)
        assert(UpdateChecker.compareVersions("0.2.10", "0.2.9") > 0)
    }

    @Test
    fun `compareVersions - plus ancien`() {
        assert(UpdateChecker.compareVersions("0.2.0", "0.3.0") < 0)
        assert(UpdateChecker.compareVersions("0.9.9", "1.0.0") < 0)
    }

    @Test
    fun `parseReleases - prend la version la plus haute avec APK`() {
        val json = """
            [
              {"tag_name": "v0.2.0", "draft": false,
               "body": "fix caméra", "published_at": "2026-08-15T20:20:33Z",
               "assets": [{"name": "transcripto-stream-v0.2.0.apk", "browser_download_url": "https://x/v0.2.0.apk"}]},
              {"tag_name": "v0.3.0", "draft": false,
               "body": "mise à jour auto", "published_at": "2026-08-16T08:00:00Z",
               "assets": [{"name": "transcripto-stream-v0.3.0.apk", "browser_download_url": "https://x/v0.3.0.apk"}]},
              {"tag_name": "v0.4.0-beta", "draft": true,
               "assets": [{"name": "transcripto-stream-v0.4.0.apk", "browser_download_url": "https://x/v0.4.0.apk"}]}
            ]
        """.trimIndent()
        val info = UpdateChecker.parseReleases(json)
        assertEquals("0.3.0", info?.versionName)
        assertEquals("https://x/v0.3.0.apk", info?.downloadUrl)
        assertEquals("mise à jour auto", info?.notes)
    }

    @Test
    fun `parseReleases - ignore les drafts`() {
        val json = """
            [
              {"tag_name": "v9.9.9", "draft": true,
               "assets": [{"name": "transcripto-stream-v9.9.9.apk", "browser_download_url": "https://x/9.9.9.apk"}]}
            ]
        """.trimIndent()
        assertNull(UpdateChecker.parseReleases(json))
    }

    @Test
    fun `parseReleases - vide`() {
        assertNull(UpdateChecker.parseReleases("[]"))
        assertNull(UpdateChecker.parseReleases("pas du json"))
    }
}
