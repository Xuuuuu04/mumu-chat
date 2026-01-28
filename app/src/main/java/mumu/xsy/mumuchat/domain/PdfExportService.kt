package mumu.xsy.mumuchat.domain

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream
import kotlin.math.ceil

class PdfExportService {
    fun exportTextToPdfFile(
        context: Context,
        title: String,
        text: String
    ): File? {
        return try {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            val safeTitle = title.take(40).replace(Regex("[^a-zA-Z0-9_\\-\\u4e00-\\u9fa5]+"), "_")
            val file = File(dir, "${safeTitle.ifBlank { "chat" }}_${System.currentTimeMillis()}.pdf")

            val pageWidth = 595
            val pageHeight = 842
            val margin = 32f

            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 16f
                isFakeBoldText = true
            }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 11.5f
            }

            val doc = PdfDocument()
            var pageNumber = 1
            var page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
            var canvas = page.canvas
            var y = margin

            canvas.drawText(title, margin, y + titlePaint.textSize, titlePaint)
            y += titlePaint.textSize + 14f

            val usableWidth = pageWidth - margin * 2
            val lineHeight = ceil(paint.textSize * 1.45f)
            val lines = wrapLines(text, paint, usableWidth)

            for (line in lines) {
                if (y + lineHeight > pageHeight - margin) {
                    doc.finishPage(page)
                    pageNumber += 1
                    page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
                    canvas = page.canvas
                    y = margin
                }
                y += lineHeight
                canvas.drawText(line, margin, y, paint)
            }

            doc.finishPage(page)
            FileOutputStream(file).use { out -> doc.writeTo(out) }
            doc.close()
            file
        } catch (_: Exception) {
            null
        }
    }

    private fun wrapLines(text: String, paint: Paint, maxWidth: Float): List<String> {
        val result = mutableListOf<String>()
        val paragraphs = text.replace("\r\n", "\n").split("\n")
        for (para in paragraphs) {
            if (para.isEmpty()) {
                result.add("")
                continue
            }
            var start = 0
            while (start < para.length) {
                var end = para.length
                var best = start + 1
                while (best <= end) {
                    val candidate = para.substring(start, best)
                    val w = paint.measureText(candidate)
                    if (w <= maxWidth) {
                        best += 1
                    } else {
                        break
                    }
                }
                val lineEnd = (best - 1).coerceAtLeast(start + 1)
                result.add(para.substring(start, lineEnd))
                start = lineEnd
            }
        }
        return result
    }
}

