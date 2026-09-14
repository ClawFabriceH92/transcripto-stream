package com.transcripto.stream.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import java.io.OutputStream

/**
 * Rédacteur PDF (A4) fondé sur android.graphics.pdf.PdfDocument : chaque bloc
 * est mis en page par StaticLayout puis dessiné ligne à ligne, ce qui permet de
 * couper un long paragraphe entre deux pages. Pied de page : application,
 * titre, numéro de page.
 */
object PdfWriter {

    private const val PAGE_W = 595 // A4 en points
    private const val PAGE_H = 842
    private const val MARGIN = 48f
    private const val FOOTER_H = 26f
    private const val BULLET_INDENT = 16f

    private val SPEAKER_COLORS = intArrayOf(0xFF1F5FA8.toInt(), 0xFF0F7B6C.toInt(), 0xFF8A3FFC.toInt(), 0xFFB2560D.toInt(), 0xFFA8201A.toInt())
    private const val INK = 0xFF1A1A1A.toInt()
    private const val TITLE_INK = 0xFF1F3A5F.toInt()
    private const val MUTED = 0xFF6B6B6B.toInt()
    private const val CLOCK = 0xFF8A8A8A.toInt()

    private class Styled(
        val text: CharSequence,
        val size: Float,
        val bold: Boolean,
        val italic: Boolean,
        val color: Int,
        val before: Float,
        val after: Float,
        val indent: Float = 0f,
        val bullet: Boolean = false,
        /** Titre ou étiquette : ne jamais rester seul en bas de page. */
        val keepWithNext: Boolean = false,
    )

    fun write(blocks: List<DocBlock>, out: OutputStream, footer: String) {
        val pdf = PdfDocument()
        var page: PdfDocument.Page? = null
        var pageNo = 0
        var y = MARGIN
        val contentW = PAGE_W - 2 * MARGIN
        val limit = PAGE_H - MARGIN - FOOTER_H

        fun finishPage() {
            val p = page ?: return
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = MUTED
                textSize = 8f
            }
            val fy = PAGE_H - MARGIN + 10f
            val label = "Page $pageNo"
            val labelW = paint.measureText(label)
            // Titre long : tronqué avec « … » pour ne jamais chevaucher le numéro de page
            val shown = TextUtils.ellipsize(footer, paint, contentW - labelW - 12f, TextUtils.TruncateAt.END).toString()
            p.canvas.drawText(shown, MARGIN, fy, paint)
            p.canvas.drawText(label, PAGE_W - MARGIN - labelW, fy, paint)
            pdf.finishPage(p)
            page = null
        }

        fun newPage(): Canvas {
            finishPage()
            pageNo++
            val p = pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
            page = p
            y = MARGIN
            return p.canvas
        }

        fun canvas(): Canvas = page?.canvas ?: newPage()

        fun draw(s: Styled) {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = s.color
                textSize = s.size
                typeface = Typeface.create(
                    Typeface.SANS_SERIF,
                    when {
                        s.bold && s.italic -> Typeface.BOLD_ITALIC
                        s.bold -> Typeface.BOLD
                        s.italic -> Typeface.ITALIC
                        else -> Typeface.NORMAL
                    },
                )
            }
            val indent = s.indent + if (s.bullet) BULLET_INDENT else 0f
            val width = (contentW - indent).toInt().coerceAtLeast(40)
            val layout = StaticLayout.Builder.obtain(s.text, 0, s.text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.12f)
                .setIncludePad(false)
                .build()
            // Espace avant : ignoré en haut de page
            if (page != null && y > MARGIN) y += s.before
            // Titre / étiquette : s'il ne tient pas avec au moins une ligne de corps, on change de page
            if (s.keepWithNext && page != null && y + layout.height + 16f > limit) newPage()
            var first = true
            for (line in 0 until layout.lineCount) {
                val top = layout.getLineTop(line).toFloat()
                val bottom = layout.getLineBottom(line).toFloat()
                val h = bottom - top
                if (page == null || y + h > limit) {
                    newPage()
                }
                val c = canvas()
                if (first && s.bullet) {
                    c.drawText("•", MARGIN + s.indent + 4f, y + layout.getLineBaseline(line) - top, paint)
                }
                c.save()
                c.translate(MARGIN + indent, y - top)
                c.clipRect(0f, top, width.toFloat(), bottom)
                layout.draw(c)
                c.restore()
                y += h
                first = false
            }
            y += s.after
        }

        for (b in blocks) {
            when (b) {
                is DocBlock.Title -> draw(Styled(b.text, 21f, true, false, TITLE_INK, 0f, 4f))
                is DocBlock.Subtitle -> draw(Styled(b.text, 13f, false, false, 0xFF4A6785.toInt(), 0f, 12f))
                is DocBlock.Heading -> when (b.level) {
                    1 -> draw(Styled(b.text, 15f, true, false, TITLE_INK, 18f, 6f, keepWithNext = true))
                    2 -> draw(Styled(b.text, 12.5f, true, false, 0xFF2E5077.toInt(), 12f, 4f, keepWithNext = true))
                    else -> draw(Styled(b.text, 11.5f, true, false, INK, 8f, 3f, keepWithNext = true))
                }
                is DocBlock.Meta -> {
                    val ssb = SpannableStringBuilder()
                    ssb.append(b.label).append(" : ")
                    ssb.setSpan(StyleSpan(Typeface.BOLD), 0, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    ssb.append(b.value)
                    draw(Styled(ssb, 10.5f, false, false, INK, 0f, 2f))
                }
                is DocBlock.Para -> {
                    val ssb = SpannableStringBuilder()
                    for (span in b.spans) {
                        val start = ssb.length
                        ssb.append(span.text)
                        val style = when {
                            span.bold && span.italic -> Typeface.BOLD_ITALIC
                            span.bold -> Typeface.BOLD
                            span.italic -> Typeface.ITALIC
                            else -> null
                        }
                        if (style != null) ssb.setSpan(StyleSpan(style), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    draw(Styled(ssb, 10.5f, false, false, INK, 0f, if (b.bullet) 3f else 6f, bullet = b.bullet))
                }
                is DocBlock.Speaker -> draw(
                    Styled(
                        b.label, 10.5f, true, false,
                        SPEAKER_COLORS[Math.floorMod(b.index, SPEAKER_COLORS.size)], 10f, 2f,
                        keepWithNext = true,
                    )
                )
                is DocBlock.Segment -> {
                    val ssb = SpannableStringBuilder()
                    if (b.clock != null) {
                        ssb.append("[").append(b.clock).append("] ")
                        ssb.setSpan(ForegroundColorSpan(CLOCK), 0, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        ssb.setSpan(RelativeSizeSpan(0.85f), 0, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    ssb.append(b.text)
                    draw(Styled(ssb, 10.5f, false, false, INK, 0f, 3f))
                }
                is DocBlock.Note -> draw(Styled(b.text, 9f, false, true, MUTED, 6f, 6f))
                DocBlock.PageBreak -> if (page != null && y > MARGIN) newPage()
            }
        }
        if (page == null) newPage()
        finishPage()
        try {
            pdf.writeTo(out)
        } finally {
            pdf.close()
        }
    }
}
