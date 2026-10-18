package com.matnotes.app

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.io.StringWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/** One border line as WordprocessingML writes it: style name, width in eighths of a point, colour as RRGGBB. */
data class BorderLine(val style: String = "single", val size: Int = 4, val color: String = "000000")

/** The six edges of a table. A null edge has no line. */
data class TableEdges(
    val top: BorderLine? = null, val left: BorderLine? = null, val bottom: BorderLine? = null, val right: BorderLine? = null,
    val insideH: BorderLine? = null, val insideV: BorderLine? = null,
) {
    fun all(l: BorderLine?) = TableEdges(l, l, l, l, l, l)
    fun outside(l: BorderLine?) = copy(top = l, left = l, bottom = l, right = l)
    fun inside(l: BorderLine?) = copy(insideH = l, insideV = l)
}

enum class Edge { ALL, OUTSIDE, INSIDE, TOP, BOTTOM, LEFT, RIGHT, INSIDE_H, INSIDE_V, NONE }

/** Table look: borders plus optional header fill/text colour and banded rows (RRGGBB, null = leave unchanged / none). */
data class TableLook(
    val id: String,
    val edges: TableEdges,
    val headerFill: String? = null,
    val headerText: String? = null,
    val headerBold: Boolean = false,
    val band: String? = null,
)

/** What to do to the table that holds the caret. */
data class TableOp(val edge: Edge? = null, val line: BorderLine = BorderLine(), val look: TableLook? = null)

object TableLooks {
    private fun thin(c: String, sz: Int = 4) = BorderLine("single", sz, c)
    val all: List<TableLook> = listOf(
        TableLook("grid", TableEdges().all(thin("000000"))),
        TableLook("grid_gray", TableEdges().all(thin("A6A6A6"))),
        TableLook("blue", TableEdges().all(thin("8EAADB")), "2F5597", "FFFFFF", true, "DAE3F3"),
        TableLook("green", TableEdges().all(thin("A9D18E")), "548235", "FFFFFF", true, "E2EFDA"),
        TableLook("orange", TableEdges().all(thin("F4B183")), "C55A11", "FFFFFF", true, "FBE5D6"),
        TableLook("striped", TableEdges(top = thin("7F7F7F", 8), bottom = thin("7F7F7F", 8), insideH = thin("D9D9D9")), "F2F2F2", null, true, "F7F7F7"),
        TableLook("minimal", TableEdges(top = thin("000000", 8), bottom = thin("000000", 8), insideH = null), null, null, true, null),
        TableLook("dark", TableEdges().all(thin("595959")), "262626", "FFFFFF", true, "EDEDED"),
    )
}

/**
 * LibreOfficeKit has no dialog-free command for table borders or table styles, so they are written into the exported
 * .docx (w:tblBorders, w:tcPr/w:shd, run bold) and the document is reloaded, like [PageSetup].
 * The caret's table is found through a marker text typed into its cell beforehand ([MARK]); the marker is removed here.
 */
object TablePatch {
    const val MARK = "MNTBLMARK7Q"
    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

    private val tblPrOrder = listOf(
        "tblStyle", "tblpPr", "tblOverlap", "bidiVisual", "tblStyleRowBandSize", "tblStyleColBandSize", "tblW", "jc",
        "tblCellSpacing", "tblInd", "tblBorders", "shd", "tblLayout", "tblCellMar", "tblLook", "tblCaption", "tblDescription",
    )
    private val tcPrOrder = listOf(
        "cnfStyle", "tcW", "gridSpan", "hMerge", "vMerge", "tcBorders", "shd", "noWrap", "tcMar", "textDirection", "tcFitText", "vAlign", "hideMark",
    )
    private val edgeNames = listOf("top", "left", "bottom", "right", "insideH", "insideV")

    /** Returns false when the marker was not found (nothing written then). */
    fun apply(src: File, dst: File, op: TableOp): Boolean {
        var found = false
        ZipFile(src).use { zin ->
            ZipOutputStream(dst.outputStream().buffered()).use { zout ->
                for (e in zin.entries()) {
                    val data = zin.getInputStream(e).use { it.readBytes() }
                    zout.putNextEntry(ZipEntry(e.name))
                    val out = if (e.name == "word/document.xml") {
                        val patched = patch(data, op)
                        if (patched != null) { found = true; patched } else data
                    } else data
                    zout.write(out)
                    zout.closeEntry()
                }
            }
        }
        return found
    }

    fun patch(xml: ByteArray, op: TableOp): ByteArray? {
        val dbf = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }
        val doc = dbf.newDocumentBuilder().parse(xml.inputStream())
        val ts = doc.getElementsByTagNameNS(W, "t")
        val markT = (0 until ts.length).map { ts.item(it) as Element }.firstOrNull { it.textContent.contains(MARK) } ?: return null
        val tc = ancestor(markT, "tc") ?: return null
        val tr = tc.parentNode as Element
        val tbl = tr.parentNode as Element
        removeMarker(markT)

        val look = op.look
        var edges = readEdges(doc, tbl)
        if (look != null) edges = look.edges
        op.edge?.let { edges = applyEdge(edges, it, op.line) }
        // flatten: cell-level borders would override the table-level ones
        children(tbl, "tr").forEach { tr -> children(tr, "tc").forEach { tc -> child(child(tc, "tcPr"), "tcBorders")?.let { it.parentNode.removeChild(it) } } }
        writeEdges(doc, tbl, edges)
        if (look != null) applyLook(doc, tbl, look)
        return serialize(doc)
    }

    // ---------------------------------------------------------------- edges

    private fun applyEdge(e: TableEdges, which: Edge, l: BorderLine): TableEdges {
        fun toggle(cur: BorderLine?) = if (cur == null) l else null
        return when (which) {
            Edge.ALL -> if (e == e.all(l)) e.all(null) else e.all(l)
            Edge.NONE -> e.all(null)
            Edge.OUTSIDE -> if (listOf(e.top, e.left, e.bottom, e.right).all { it != null }) e.outside(null) else e.outside(l)
            Edge.INSIDE -> if (e.insideH != null && e.insideV != null) e.inside(null) else e.inside(l)
            Edge.TOP -> e.copy(top = toggle(e.top))
            Edge.BOTTOM -> e.copy(bottom = toggle(e.bottom))
            Edge.LEFT -> e.copy(left = toggle(e.left))
            Edge.RIGHT -> e.copy(right = toggle(e.right))
            Edge.INSIDE_H -> e.copy(insideH = toggle(e.insideH))
            Edge.INSIDE_V -> e.copy(insideV = toggle(e.insideV))
        }
    }

    private fun readLine(el: Element?): BorderLine? {
        el ?: return null
        val v = el.getAttributeNS(W, "val")
        if (v.isEmpty() || v == "nil" || v == "none") return null
        return BorderLine(v, el.getAttributeNS(W, "sz").toIntOrNull() ?: 4, el.getAttributeNS(W, "color").ifEmpty { "000000" }.let { if (it == "auto") "000000" else it })
    }

    private fun child(parent: Element?, name: String): Element? {
        parent ?: return null
        var n = parent.firstChild
        while (n != null) { if (n is Element && n.localName == name && n.namespaceURI == W) return n; n = n.nextSibling }
        return null
    }

    private fun children(parent: Element, name: String): List<Element> {
        val out = ArrayList<Element>()
        var n = parent.firstChild
        while (n != null) { if (n is Element && n.localName == name && n.namespaceURI == W) out += n; n = n.nextSibling }
        return out
    }

    private fun elements(root: Element, name: String): List<Element> =
        (0 until root.getElementsByTagNameNS(W, name).length).map { root.getElementsByTagNameNS(W, name).item(it) as Element }

    /** Edges currently on the table: its w:tblBorders, else derived from the cells' own borders. */
    private fun readEdges(doc: Document, tbl: Element): TableEdges {
        val tb = child(child(tbl, "tblPr"), "tblBorders")
        if (tb != null) {
            fun side(n: String) = readLine(child(tb, n) ?: child(tb, if (n == "left") "start" else if (n == "right") "end" else n))
            return TableEdges(side("top"), side("left"), side("bottom"), side("right"), side("insideH"), side("insideV"))
        }
        val rows = children(tbl, "tr")
        if (rows.isEmpty()) return TableEdges()
        fun cells(r: Element) = children(r, "tc")
        fun cb(tc: Element?, n: String) = readLine(child(child(child(tc, "tcPr"), "tcBorders"), n))
        val first = cells(rows.first()).firstOrNull(); val last = cells(rows.last()).lastOrNull()
        val lastOfFirst = cells(rows.first()).lastOrNull()
        return TableEdges(
            top = cb(first, "top"), left = cb(first, "left") ?: cb(first, "start"),
            bottom = cb(last, "bottom"), right = cb(lastOfFirst, "right") ?: cb(lastOfFirst, "end"),
            insideH = if (rows.size > 1) cb(first, "bottom") else null,
            insideV = if (cells(rows.first()).size > 1) cb(first, "right") ?: cb(first, "end") else null,
        )
    }

    private fun lineEl(doc: Document, name: String, l: BorderLine?): Element {
        val e = doc.createElementNS(W, "w:$name")
        if (l == null) {
            e.setAttributeNS(W, "w:val", "nil")
        } else {
            e.setAttributeNS(W, "w:val", l.style)
            e.setAttributeNS(W, "w:sz", l.size.toString())
            e.setAttributeNS(W, "w:space", "0")
            e.setAttributeNS(W, "w:color", l.color)
        }
        return e
    }

    private fun writeEdges(doc: Document, tbl: Element, e: TableEdges) {
        val pr = child(tbl, "tblPr") ?: doc.createElementNS(W, "w:tblPr").also { tbl.insertBefore(it, tbl.firstChild) }
        child(pr, "tblBorders")?.let { pr.removeChild(it) }
        val b = doc.createElementNS(W, "w:tblBorders")
        listOf("top" to e.top, "left" to e.left, "bottom" to e.bottom, "right" to e.right, "insideH" to e.insideH, "insideV" to e.insideV)
            .forEach { (n, l) -> b.appendChild(lineEl(doc, n, l)) }
        insertOrdered(pr, b, tblPrOrder)
    }

    // ---------------------------------------------------------------- look (header, bands)

    private fun applyLook(doc: Document, tbl: Element, look: TableLook) {
        val rows = children(tbl, "tr")
        rows.forEachIndexed { ri, tr ->
            val header = ri == 0
            val fill = when {
                header && look.headerFill != null -> look.headerFill
                !header && look.band != null && ri % 2 == 0 -> look.band
                else -> null
            }
            children(tr, "tc").forEach { tc ->
                setShading(doc, tc, fill)
                if (header) formatRuns(doc, tc, look)
            }
        }
    }

    private fun setShading(doc: Document, tc: Element, fill: String?) {
        val pr = child(tc, "tcPr") ?: doc.createElementNS(W, "w:tcPr").also { tc.insertBefore(it, tc.firstChild) }
        child(pr, "shd")?.let { pr.removeChild(it) }
        if (fill != null) {
            val s = doc.createElementNS(W, "w:shd")
            s.setAttributeNS(W, "w:val", "clear"); s.setAttributeNS(W, "w:color", "auto"); s.setAttributeNS(W, "w:fill", fill)
            insertOrdered(pr, s, tcPrOrder)
        }
    }

    private fun formatRuns(doc: Document, tc: Element, look: TableLook) {
        if (!look.headerBold && look.headerText == null) return
        elements(tc, "r").forEach { r ->
            val rpr = child(r, "rPr") ?: doc.createElementNS(W, "w:rPr").also { r.insertBefore(it, r.firstChild) }
            if (look.headerBold && child(rpr, "b") == null) rpr.insertBefore(doc.createElementNS(W, "w:b"), rpr.firstChild)
            if (look.headerText != null) {
                child(rpr, "color")?.let { rpr.removeChild(it) }
                val c = doc.createElementNS(W, "w:color"); c.setAttributeNS(W, "w:val", look.headerText)
                rpr.appendChild(c)
            }
        }
    }

    // ---------------------------------------------------------------- dom helpers

    private fun insertOrdered(parent: Element, el: Element, order: List<String>) {
        val rank = order.indexOf(el.localName)
        var n = parent.firstChild
        while (n != null) {
            if (n is Element) {
                val r = order.indexOf(n.localName)
                if (r > rank) { parent.insertBefore(el, n); return }
            }
            n = n.nextSibling
        }
        parent.appendChild(el)
    }

    private fun ancestor(n: Node, name: String): Element? {
        var p: Node? = n.parentNode
        while (p != null) {
            if (p is Element && p.localName == name && p.namespaceURI == W) return p
            p = p.parentNode
        }
        return null
    }

    private fun removeMarker(t: Element) {
        val text = t.textContent.replace(MARK, "")
        if (text.isEmpty()) {
            val run = t.parentNode
            if (run is Element && run.localName == "r" && elements(run, "t").size == 1) run.parentNode.removeChild(run) else t.textContent = ""
        } else t.textContent = text
    }

    private fun serialize(doc: Document): ByteArray {
        val tf = TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            setOutputProperty(OutputKeys.STANDALONE, "yes")
        }
        val sw = StringWriter()
        tf.transform(DOMSource(doc), StreamResult(sw))
        return sw.toString().toByteArray(Charsets.UTF_8)
    }
}
