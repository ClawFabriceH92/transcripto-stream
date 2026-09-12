package com.transcripto.stream.summary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownLiteTest {

    @Test
    fun parsesHeadingsBulletsParagraphs() {
        val blocks = MarkdownLite.parse("# Titre\n\n## Section\n- un\n- deux\n\ntexte\n\n\n")
        assertEquals(MdBlock.Heading(1, "Titre"), blocks[0])
        assertEquals(MdBlock.Blank, blocks[1])
        assertEquals(MdBlock.Heading(2, "Section"), blocks[2])
        assertEquals(MdBlock.Bullet("un"), blocks[3])
        assertEquals(MdBlock.Bullet("deux"), blocks[4])
        assertEquals(MdBlock.Paragraph("texte"), blocks[6])
        assertTrue(blocks.last() != MdBlock.Blank)
    }

    @Test
    fun spansHandleBoldAndItalic() {
        val spans = MarkdownLite.spans("Montant **45 000 €** _à vérifier_ fin.")
        assertEquals(MdSpan("Montant "), spans[0])
        assertEquals(MdSpan("45 000 €", bold = true), spans[1])
        assertEquals(MdSpan(" "), spans[2])
        assertEquals(MdSpan("à vérifier", italic = true), spans[3])
        assertEquals(MdSpan(" fin."), spans[4])
    }

    @Test
    fun underscoreInsideWordIsNotItalic() {
        val spans = MarkdownLite.spans("fichier rec_2026_09.wav")
        assertEquals(1, spans.size)
        assertEquals("fichier rec_2026_09.wav", spans[0].text)
    }

    @Test
    fun plainTextKeepsBulletsDropsMarkers() {
        val plain = MarkdownLite.toPlainText("# Synthèse\n- **Point** un\n_note_")
        assertEquals("SYNTHÈSE\n• Point un\nnote", plain)
    }
}
