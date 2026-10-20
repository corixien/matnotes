package com.matnotes.app

import android.app.Activity
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.libreoffice.kit.Document
import org.libreoffice.kit.LibreOfficeKit
import org.libreoffice.kit.Office
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/** Page rectangle in document twips (1/1440 inch). */
data class PageRect(val x: Int, val y: Int, val w: Int, val h: Int)

/** Thin wrapper over LibreOfficeKit. Every native call runs on one dedicated thread. */
object Engine {
    private const val TAG = "MatNotesEngine"
    val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(null, r, "lo-engine", 32L shl 20) }
        .asCoroutineDispatcher()
    val scope = CoroutineScope(dispatcher + SupervisorJob())
    private var office: Office? = null

    private fun ensureInit(activity: Activity) {
        if (office != null) return
        unpackAssets(activity)
        LibreOfficeKit.putenv("SAL_LOK_OPTIONS=compact_fonts")
        LibreOfficeKit.init(activity)
        office = Office(LibreOfficeKit.getLibreOfficeKitHandle())
    }

    suspend fun open(activity: Activity, file: File): LoDocument? = withContext(dispatcher) {
        ensureInit(activity)
        val doc = office!!.documentLoad(File(file.parent, android.net.Uri.encode(file.name)).path)
        if (doc == null) {
            Log.e(TAG, "load failed: ${office?.getError()}")
            null
        } else {
            LoDocument(doc, file)
        }
    }

    /** LibreOffice expects its config/fonts/rdb under dataDir; copy once per app version. */
    private fun unpackAssets(activity: Activity) {
        val marker = File(activity.applicationInfo.dataDir, ".engine-unpacked-${BuildConfig.VERSION_CODE}")
        if (marker.exists()) return
        check(copyAssets(activity.assets, "unpack", activity.applicationInfo.dataDir)) { "asset unpack failed" }
        marker.writeText("ok")
    }

    private fun copyAssets(am: AssetManager, from: String, to: String): Boolean {
        val children = am.list(from)
        if (children.isNullOrEmpty()) {
            File(to).parentFile?.mkdirs()
            am.open(from).use { i -> File(to).outputStream().use { o -> i.copyTo(o) } }
            return true
        }
        File(to).mkdirs()
        return children.all { copyAssets(am, "$from/$it", "$to/$it") }
    }
}

/** Document size and page rectangles in twips. */
data class Layout(val width: Int, val height: Int, val pages: List<PageRect>)

/** A loaded document. Native calls run on [Engine.dispatcher]; [post] is the safe entry point. */
class LoDocument(private val doc: Document, val file: File) {
    @Volatile var layout: Layout
        private set

    /** Receives LibreOfficeKit callbacks on the main thread. */
    var onMessage: ((type: Int, payload: String) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val tileBuffer: ByteBuffer = ByteBuffer.allocateDirect(MAX_TILE * MAX_TILE * 4)

    init {
        doc.initializeForRendering()
        layout = readLayout()
        doc.setMessageCallback { type, payload ->
            if (type == Document.CALLBACK_DOCUMENT_SIZE_CHANGED) {
                Engine.scope.launch { layout = readLayout(); deliver(type, payload) }
            } else {
                deliver(type, payload)
            }
        }
    }

    private fun deliver(type: Int, payload: String) {
        main.post { onMessage?.invoke(type, payload) }
    }

    /** Re-reads document size and pages (engine thread). LibreOfficeKit does not always send size callbacks. */
    fun refreshLayout() { layout = readLayout() }

    private fun readLayout(): Layout {
        val w = doc.documentWidth.toInt()
        val h = doc.documentHeight.toInt()
        return Layout(w, h, parsePages(doc.partPageRectangles) ?: listOf(PageRect(0, 0, w, h)))
    }

    /** Runs [block] on the engine thread, in call order. */
    fun post(block: LoDocument.() -> Unit) {
        Engine.scope.launch { block() }
    }

    fun paintTile(page: PageRect, xTw: Int, yTw: Int, wTw: Int, hTw: Int, wPx: Int, hPx: Int): Bitmap {
        val buf = if (wPx * hPx * 4 <= tileBuffer.capacity()) tileBuffer else ByteBuffer.allocateDirect(wPx * hPx * 4)
        buf.clear()
        doc.paintTile(buf, wPx, hPx, page.x + xTw, page.y + yTw, wTw, hTw)
        buf.rewind()
        val bmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(buf)
        return bmp
    }

    // --- editing (engine thread) ---

    fun mouse(type: Int, xTw: Int, yTw: Int, count: Int, pxPerTwip: Float, buttons: Int = Document.MOUSE_BUTTON_LEFT, modifier: Int = Document.KEYBOARD_MODIFIER_NONE) {
        val tile = (256 / pxPerTwip).toInt().coerceAtLeast(1)
        doc.setClientZoom(256, 256, tile, tile)
        doc.postMouseEvent(type, xTw, yTw, count, buttons, modifier)
    }

    fun key(type: Int, charCode: Int, keyCode: Int) = doc.postKeyEvent(type, charCode, keyCode)

    fun type(text: String) {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (cp == '\n'.code) {
                pressKey(Keys.RETURN)
            } else {
                doc.postKeyEvent(Document.KEY_EVENT_PRESS, cp, 0)
                doc.postKeyEvent(Document.KEY_EVENT_RELEASE, cp, 0)
            }
        }
    }

    fun pressKey(awtKey: Int) {
        doc.postKeyEvent(Document.KEY_EVENT_PRESS, 0, awtKey)
        doc.postKeyEvent(Document.KEY_EVENT_RELEASE, 0, awtKey)
    }

    fun commandValues(command: String): String? = doc.getCommandValues(command)

    fun uno(command: String, args: String = "") = doc.postUnoCommand(command, args.ifEmpty { null }, false)

    fun setTextSelection(type: Int, xTw: Int, yTw: Int) = doc.setTextSelection(type, xTw, yTw)

    fun setGraphicSelection(type: Int, xTw: Int, yTw: Int, pxPerTwip: Float) {
        // LibreOffice sizes its handle hit-area from the client zoom; without it small images pick edge handles
        val tile = (256 / pxPerTwip).toInt().coerceAtLeast(1)
        doc.setClientZoom(256, 256, tile, tile)
        doc.setGraphicSelection(type, xTw, yTw)
    }

    fun selectedText(): String? = doc.getTextSelection("text/plain;charset=utf-8")
    fun selectionAs(mime: String): String? = doc.getTextSelection(mime)
    fun pasteData(mime: String, data: String): Boolean = doc.paste(mime, data)

    /** Exports to [out] in the LibreOffice filter [format] (e.g. "pdf", "docx"). */
    fun saveAs(out: File, format: String): Boolean {
        out.delete()
        doc.saveAs(out.absolutePath, format, "")
        if (!out.exists()) doc.saveAs("file://" + out.absolutePath, format, "")
        return out.exists() && out.length() > 0
    }

    fun close() = doc.destroy()

    private fun parsePages(s: String?): List<PageRect>? {
        if (s.isNullOrBlank()) return null
        val list = s.split(';').mapNotNull { part ->
            val n = part.split(',').mapNotNull { it.trim().toIntOrNull() }
            if (n.size == 4 && n[2] > 0 && n[3] > 0) PageRect(n[0], n[1], n[2], n[3]) else null
        }
        return list.ifEmpty { null }
    }

    companion object {
        const val MAX_TILE = 1024
    }
}

/** com.sun.star.awt.Key codes used by LibreOfficeKit. */
object Keys {
    const val DOWN = 1024
    const val UP = 1025
    const val LEFT = 1026
    const val RIGHT = 1027
    const val HOME = 1028
    const val END = 1029
    const val PAGEUP = 1030
    const val PAGEDOWN = 1031
    const val RETURN = 1280
    const val ESCAPE = 1281
    /** Modifier bit: Alt. Alt+arrows resize the caret cell's column/row border in Writer tables. */
    const val ALT = 0x4000
    const val TAB = 1282
    const val BACKSPACE = 1283
    const val DELETE = 1286
}
