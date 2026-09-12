package com.transcripto.stream.summary

/** Sous-ensemble Markdown suffisant pour les synthèses : titres, puces, gras, italique. */
sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Bullet(val text: String) : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
    data object Blank : MdBlock()
}

/** Segment de texte enrichi (gras / italique). */
data class MdSpan(val text: String, val bold: Boolean = false, val italic: Boolean = false)

object MarkdownLite {

    fun parse(md: String): List<MdBlock> {
        val out = ArrayList<MdBlock>()
        for (line in md.lines()) {
            val t = line.trimEnd()
            val lead = t.trimStart()
            when {
                t.isBlank() -> if (out.lastOrNull() != MdBlock.Blank) out.add(MdBlock.Blank)
                t.startsWith("### ") -> out.add(MdBlock.Heading(3, t.removePrefix("### ").trim()))
                t.startsWith("## ") -> out.add(MdBlock.Heading(2, t.removePrefix("## ").trim()))
                t.startsWith("# ") -> out.add(MdBlock.Heading(1, t.removePrefix("# ").trim()))
                lead.startsWith("- ") || lead.startsWith("* ") -> out.add(MdBlock.Bullet(lead.substring(2).trim()))
                else -> out.add(MdBlock.Paragraph(t.trim()))
            }
        }
        while (out.lastOrNull() == MdBlock.Blank) out.removeAt(out.size - 1)
        return out
    }

    /** Découpe une ligne en segments : `**gras**`, `*italique*`, `_italique_`. */
    fun spans(text: String): List<MdSpan> {
        val out = ArrayList<MdSpan>()
        val buf = StringBuilder()
        var bold = false
        var italic = false
        fun flush() {
            if (buf.isNotEmpty()) {
                out.add(MdSpan(buf.toString(), bold, italic))
                buf.setLength(0)
            }
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                text.startsWith("**", i) -> {
                    flush()
                    bold = !bold
                    i += 2
                    continue
                }
                c == '*' -> {
                    flush()
                    italic = !italic
                }
                c == '_' && isItalicToggle(text, i, italic) -> {
                    flush()
                    italic = !italic
                }
                else -> buf.append(c)
            }
            i++
        }
        flush()
        return out
    }

    /** `_` ne bascule l'italique qu'en bord de mot (pas dans snake_case). */
    private fun isItalicToggle(text: String, i: Int, italicOpen: Boolean): Boolean {
        val prev = if (i > 0) text[i - 1] else ' '
        val next = if (i + 1 < text.length) text[i + 1] else ' '
        return if (!italicOpen) {
            (prev.isWhitespace() || prev in "([«") && !next.isWhitespace()
        } else {
            (next.isWhitespace() || next in ".,;:!?…)]»") && !prev.isWhitespace()
        }
    }

    /** Texte brut (presse-papiers, corps d'e-mail) : marqueurs retirés, puces conservées. */
    fun toPlainText(md: String): String = parse(md).joinToString("\n") { block ->
        when (block) {
            is MdBlock.Heading -> {
                val t = spans(block.text).joinToString("") { it.text }
                if (block.level == 1) t.uppercase() else t
            }
            is MdBlock.Bullet -> "• " + spans(block.text).joinToString("") { it.text }
            is MdBlock.Paragraph -> spans(block.text).joinToString("") { it.text }
            MdBlock.Blank -> ""
        }
    }
}
