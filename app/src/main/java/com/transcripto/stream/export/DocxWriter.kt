package com.transcripto.stream.export

import com.transcripto.stream.summary.MdSpan
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Rédacteur Word (.docx) minimal : un paquet OOXML écrit à la main (zip +
 * XML), sans bibliothèque tierce — styles Titre / Sous-titre / Titre 1-3,
 * puces, couleurs d'intervenants, horodatages grisés. Pur Kotlin, testable.
 */
object DocxWriter {

    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    /** Couleurs (hex RGB) des étiquettes d'intervenants, par index. */
    private val SPEAKER_COLORS = listOf("1F5FA8", "0F7B6C", "8A3FFC", "B2560D", "A8201A")

    private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
<Override PartName="/word/numbering.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml"/>
<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
</Types>"""

    private const val RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
</Relationships>"""

    private const val DOC_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering" Target="numbering.xml"/>
</Relationships>"""

    private const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="$W">
<w:docDefaults>
<w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:cs="Calibri"/><w:sz w:val="22"/><w:szCs w:val="22"/><w:lang w:val="fr-FR"/></w:rPr></w:rPrDefault>
<w:pPrDefault><w:pPr><w:spacing w:after="120" w:line="276" w:lineRule="auto"/></w:pPr></w:pPrDefault>
</w:docDefaults>
<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:qFormat/></w:style>
<w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:basedOn w:val="Normal"/><w:qFormat/><w:pPr><w:spacing w:after="60"/></w:pPr><w:rPr><w:b/><w:color w:val="1F3A5F"/><w:sz w:val="44"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Subtitle"><w:name w:val="Subtitle"/><w:basedOn w:val="Normal"/><w:qFormat/><w:pPr><w:spacing w:after="200"/></w:pPr><w:rPr><w:color w:val="4A6785"/><w:sz w:val="28"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before="360" w:after="120"/><w:outlineLvl w:val="0"/></w:pPr><w:rPr><w:b/><w:color w:val="1F3A5F"/><w:sz w:val="32"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:basedOn w:val="Normal"/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before="240" w:after="80"/><w:outlineLvl w:val="1"/></w:pPr><w:rPr><w:b/><w:color w:val="2E5077"/><w:sz w:val="26"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Heading3"><w:name w:val="heading 3"/><w:basedOn w:val="Normal"/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before="160" w:after="60"/><w:outlineLvl w:val="2"/></w:pPr><w:rPr><w:b/><w:sz w:val="23"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="ListParagraph"><w:name w:val="List Paragraph"/><w:basedOn w:val="Normal"/><w:qFormat/><w:pPr><w:spacing w:after="60"/><w:ind w:left="720"/></w:pPr></w:style>
</w:styles>"""

    private const val NUMBERING = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:numbering xmlns:w="$W">
<w:abstractNum w:abstractNumId="0"><w:multiLevelType w:val="singleLevel"/>
<w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="bullet"/><w:lvlText w:val="•"/><w:lvlJc w:val="left"/><w:pPr><w:ind w:left="720" w:hanging="360"/></w:pPr></w:lvl>
</w:abstractNum>
<w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num>
</w:numbering>"""

    /** Paquet .docx complet (octets du zip). */
    fun write(blocks: List<DocBlock>, title: String): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            entry(zip, "[Content_Types].xml", CONTENT_TYPES)
            entry(zip, "_rels/.rels", RELS)
            entry(zip, "word/_rels/document.xml.rels", DOC_RELS)
            entry(zip, "word/styles.xml", STYLES)
            entry(zip, "word/numbering.xml", NUMBERING)
            entry(zip, "docProps/core.xml", coreXml(title))
            entry(zip, "word/document.xml", documentXml(blocks))
        }
        return bos.toByteArray()
    }

    private fun entry(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun coreXml(title: String): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:dcmitype="http://purl.org/dc/dcmitype/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
<dc:title>${esc(title)}</dc:title>
<dc:creator>Transcripto Stream</dc:creator>
<dc:language>fr-FR</dc:language>
</cp:coreProperties>"""

    /** Corps du document ; visible pour les tests (XML bien formé, contenu échappé). */
    fun documentXml(blocks: List<DocBlock>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""").append('\n')
        sb.append("""<w:document xmlns:w="$W"><w:body>""").append('\n')
        for (b in blocks) {
            when (b) {
                is DocBlock.Title -> styled(sb, "Title", run(b.text))
                is DocBlock.Subtitle -> styled(sb, "Subtitle", run(b.text))
                is DocBlock.Heading -> styled(sb, "Heading${b.level.coerceIn(1, 3)}", run(b.text))
                is DocBlock.Meta -> sb.append("<w:p><w:pPr><w:spacing w:after=\"40\"/></w:pPr>")
                    .append(run(b.label + " : ", bold = true))
                    .append(run(b.value))
                    .append("</w:p>\n")
                is DocBlock.Para -> {
                    sb.append("<w:p>")
                    if (b.bullet) {
                        sb.append("<w:pPr><w:pStyle w:val=\"ListParagraph\"/><w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"1\"/></w:numPr></w:pPr>")
                    }
                    b.spans.forEach { sb.append(run(it)) }
                    sb.append("</w:p>\n")
                }
                is DocBlock.Speaker -> {
                    val color = SPEAKER_COLORS[Math.floorMod(b.index, SPEAKER_COLORS.size)]
                    sb.append("<w:p><w:pPr><w:keepNext/><w:spacing w:before=\"200\" w:after=\"40\"/></w:pPr>")
                        .append(run(b.label, bold = true, color = color))
                        .append("</w:p>\n")
                }
                is DocBlock.Segment -> {
                    sb.append("<w:p><w:pPr><w:spacing w:after=\"60\"/></w:pPr>")
                    if (b.clock != null) sb.append(run("[${b.clock}] ", color = "8A8A8A", sizeHalfPoints = 18))
                    sb.append(run(b.text)).append("</w:p>\n")
                }
                is DocBlock.Note -> sb.append("<w:p><w:pPr><w:spacing w:before=\"120\" w:after=\"120\"/></w:pPr>")
                    .append(run(b.text, italic = true, color = "6B6B6B", sizeHalfPoints = 18))
                    .append("</w:p>\n")
                DocBlock.PageBreak -> sb.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>\n")
            }
        }
        sb.append("""<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1134" w:header="708" w:footer="708" w:gutter="0"/></w:sectPr>""")
        sb.append("\n</w:body></w:document>")
        return sb.toString()
    }

    private fun styled(sb: StringBuilder, style: String, runs: String) {
        sb.append("<w:p><w:pPr><w:pStyle w:val=\"").append(style).append("\"/></w:pPr>")
            .append(runs).append("</w:p>\n")
    }

    private fun run(span: MdSpan): String = run(span.text, bold = span.bold, italic = span.italic)

    private fun run(
        text: String,
        bold: Boolean = false,
        italic: Boolean = false,
        color: String? = null,
        sizeHalfPoints: Int? = null,
    ): String {
        if (text.isEmpty()) return ""
        val sb = StringBuilder("<w:r>")
        if (bold || italic || color != null || sizeHalfPoints != null) {
            sb.append("<w:rPr>")
            if (bold) sb.append("<w:b/>")
            if (italic) sb.append("<w:i/>")
            if (color != null) sb.append("<w:color w:val=\"").append(color).append("\"/>")
            if (sizeHalfPoints != null) sb.append("<w:sz w:val=\"").append(sizeHalfPoints).append("\"/>")
            sb.append("</w:rPr>")
        }
        sb.append("<w:t xml:space=\"preserve\">").append(esc(text)).append("</w:t></w:r>")
        return sb.toString()
    }

    /** Échappement XML + suppression des caractères de contrôle interdits en XML 1.0. */
    fun esc(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            when {
                c == '&' -> sb.append("&amp;")
                c == '<' -> sb.append("&lt;")
                c == '>' -> sb.append("&gt;")
                c == '"' -> sb.append("&quot;")
                c.code < 0x20 && c != '\t' && c != '\n' && c != '\r' -> Unit
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}
