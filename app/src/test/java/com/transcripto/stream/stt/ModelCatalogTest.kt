package com.transcripto.stream.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {

    @Test
    fun ids_areUnique() {
        val ids = ModelCatalog.MODELS.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun embeddedModel_isFirstAndHasNoUrl() {
        val first = ModelCatalog.MODELS.first()
        assertEquals(ModelCatalog.EMBEDDED_ID, first.id)
        assertNull(first.url)
    }

    @Test
    fun downloadableUrls_endWithTheirFileName() {
        ModelCatalog.MODELS.filter { it.url != null }.forEach { m ->
            assertTrue("${m.id} : ${m.url}", m.url!!.endsWith("/${m.fileName}"))
            assertTrue(m.url!!.startsWith("https://"))
        }
    }

    @Test
    fun byId_fallsBackToEmbedded() {
        assertEquals(ModelCatalog.EMBEDDED_ID, ModelCatalog.byId("inconnu").id)
        assertEquals("small-q5_1", ModelCatalog.byId("small-q5_1").id)
    }
}
