package com.matnotes.app

import android.app.Activity
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.asCoroutineDispatcher
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

/** A loaded document. All methods must be called on [Engine.dispatcher]. */
class LoDocument(private val doc: Document, val file: File) {
    val width: Int
    val height: Int
    val pages: List<PageRect>

    private val tileBuffer: ByteBuffer = ByteBuffer.allocateDirect(MAX_TILE * MAX_TILE * 4)

    init {
        doc.initializeForRendering()
        width = doc.documentWidth.toInt()
        height = doc.documentHeight.toInt()
        pages = parsePages(doc.partPageRectangles) ?: listOf(PageRect(0, 0, width, height))
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
