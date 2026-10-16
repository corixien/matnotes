package com.matnotes.app

import android.app.Activity
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.util.Log
import org.libreoffice.kit.Document
import org.libreoffice.kit.LibreOfficeKit
import org.libreoffice.kit.Office
import java.io.File
import java.nio.ByteBuffer

/** Thin wrapper over LibreOfficeKit. All calls must run on one worker thread. */
object Engine {
    private const val TAG = "MatNotesEngine"
    private var office: Office? = null

    @Synchronized
    fun init(activity: Activity) {
        if (office != null) return
        unpackAssets(activity)
        LibreOfficeKit.putenv("SAL_LOK_OPTIONS=compact_fonts")
        LibreOfficeKit.init(activity)
        office = Office(LibreOfficeKit.getLibreOfficeKitHandle())
    }

    fun open(path: String): Document? {
        val file = File(path)
        val doc = office?.documentLoad(File(file.parent, android.net.Uri.encode(file.name)).path)
        if (doc == null) Log.e(TAG, "load failed: ${office?.getError()}")
        return doc
    }

    /** Renders [doc] fully, scaled to [widthPx]. Spike only: one big tile. */
    fun renderAll(doc: Document, widthPx: Int): Bitmap {
        doc.initializeForRendering()
        val wTw = doc.documentWidth.toInt()
        val hTw = doc.documentHeight.toInt()
        val heightPx = (widthPx.toLong() * hTw / wTw).toInt().coerceAtMost(8000)
        val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val buf = ByteBuffer.allocateDirect(widthPx * heightPx * 4)
        doc.paintTile(buf, widthPx, heightPx, 0, 0, wTw, hTw)
        buf.rewind()
        bmp.copyPixelsFromBuffer(buf)
        return bmp
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
