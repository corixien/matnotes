package com.matnotes.app

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Requested page changes. Sizes are in twips (1/1440 inch); [margins] = top, right, bottom, left. */
data class PageSetupChange(
    val landscape: Boolean? = null,
    val size: Pair<Int, Int>? = null,
    val margins: IntArray? = null,
)

object PageSizes {
    // portrait width x height in twips
    val A3 = 16838 to 23811
    val A4 = 11906 to 16838
    val A5 = 8391 to 11906
    val B5 = 10319 to 14570
    val Letter = 12240 to 15840
    val Legal = 12240 to 20160
}

/**
 * LibreOfficeKit exposes no dialog-free command for page size, orientation or margins, so these are applied to the
 * exported .docx (w:sectPr: w:pgSz / w:pgMar) and the document is reloaded.
 */
object PageSetup {
    private val sectPr = Regex("<w:sectPr\\b[\\s\\S]*?</w:sectPr>")
    private val pgSz = Regex("<w:pgSz\\b[^>]*/>")
    private val pgMar = Regex("<w:pgMar\\b[^>]*/>")

    fun cm(v: Double) = (v * 567.0).toInt()

    fun apply(src: File, dst: File, change: PageSetupChange) {
        ZipFile(src).use { zin ->
            ZipOutputStream(dst.outputStream().buffered()).use { zout ->
                for (e in zin.entries()) {
                    val data = zin.getInputStream(e).use { it.readBytes() }
                    zout.putNextEntry(ZipEntry(e.name))
                    val out = if (e.name == "word/document.xml") patch(data.toString(Charsets.UTF_8), change).toByteArray(Charsets.UTF_8) else data
                    zout.write(out)
                    zout.closeEntry()
                }
            }
        }
    }

    fun patch(xml: String, c: PageSetupChange): String = sectPr.replace(xml) { m -> patchSection(m.value, c) }

    private fun attr(tag: String, name: String): Int? = Regex("w:$name=\"(-?\\d+)\"").find(tag)?.groupValues?.get(1)?.toInt()

    private fun patchSection(sect: String, c: PageSetupChange): String {
        var out = sect
        pgSz.find(out)?.let { m ->
            val w0 = attr(m.value, "w") ?: PageSizes.A4.first
            val h0 = attr(m.value, "h") ?: PageSizes.A4.second
            val wasLandscape = m.value.contains("landscape") || w0 > h0
            val landscape = c.landscape ?: wasLandscape
            val (pw, ph) = c.size ?: (minOf(w0, h0) to maxOf(w0, h0))
            val w = if (landscape) ph else pw
            val h = if (landscape) pw else ph
            val tag = "<w:pgSz w:w=\"$w\" w:h=\"$h\"" + (if (landscape) " w:orient=\"landscape\"" else "") + "/>"
            out = out.replace(m.value, tag)
        }
        val mg = c.margins
        if (mg != null) {
            pgMar.find(out)?.let { m ->
                var tag = m.value
                listOf("top" to mg[0], "right" to mg[1], "bottom" to mg[2], "left" to mg[3]).forEach { (n, v) ->
                    tag = if (Regex("w:$n=\"").containsMatchIn(tag)) tag.replace(Regex("w:$n=\"-?\\d+\""), "w:$n=\"$v\"")
                    else tag.replace("<w:pgMar", "<w:pgMar w:$n=\"$v\"")
                }
                out = out.replace(m.value, tag)
            }
        }
        return out
    }
}
