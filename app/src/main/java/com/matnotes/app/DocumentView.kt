package com.matnotes.app

import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.libreoffice.kit.Document
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val TILE_PX = 512
private const val CALLBACK_TABLE_GEOMETRY = 44 // JSON: inner column/row borders of the table under the caret ("{ }" outside tables)
private const val CALLBACK_CONTEXT_CHANGED = 39 // payload "<app> <context>", e.g. "com.sun.star.text.TextDocument DrawText"
private const val MAX_ZOOM = 6f
private const val MIN_ZOOM = 0.2f // zoom out well beyond one page width
private const val BASE_LEVEL = 2 // level = zoom * 2; level 2 == fit-width scale
private val PageGray = Color(0xFFE6E6E6)
private val SelectionColor = Color(0x553D7BF0)
private val AccentColor = Color(0xFF2B579A)
private val MinusColor = Color(0xFFC62828)
private val CursorColor = Color(0xFF1B1B1B)

private data class TileKey(val page: Int, val level: Int, val tx: Int, val ty: Int)

private class Tile(val bmp: Bitmap, @Volatile var stale: Boolean = false)

/** Borders of the table under the caret in document twips, outer edges included (so n cells have n + 1 values). */
class TableGeom(val colX: List<Float>, val rowY: List<Float>)

/**
 * A speech bubble attached to a table border or to a row/column: "+" ([minus] false) inserts at border [k], "-" deletes
 * column/row [k]. [column] true = above the table, else left of it. [tail] is the point the bubble's tail touches.
 */
class TableMarker(val center: Offset, val column: Boolean, val k: Int, val minus: Boolean, val tail: Offset, val r: Float)

private enum class Drag { SelStart, SelEnd, GraphicBody, GraphicHandle, TableBorder }

/** Scroll/zoom state, async tile cache and edit overlay state for one document. */
class DocumentViewState(val doc: LoDocument, private val scope: CoroutineScope, private val density: Float) {
    var zoom by mutableStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
    var viewSize by mutableStateOf(IntSize.Zero)
    var tick by mutableIntStateOf(0)
        private set
    var layout by mutableStateOf(doc.layout)
        private set

    // edit state
    var editing by mutableStateOf(false)
    var dirty by mutableStateOf(false)
    var cursor by mutableStateOf<RectF?>(null)
    /** Geometry of the table the caret is in (null outside tables); drives the "+" markers and the table bar. */
    var table by mutableStateOf<TableGeom?>(null)
    var inTable by mutableStateOf(false)
        private set
    var cursorVisible by mutableStateOf(true)
        private set
    var blinkOn by mutableStateOf(true)
    var blinkReset by mutableIntStateOf(0)
    var selection by mutableStateOf<List<RectF>>(emptyList())
        private set
    var selStart by mutableStateOf<RectF?>(null)
        private set
    var selEnd by mutableStateOf<RectF?>(null)
        private set
    var graphic by mutableStateOf<RectF?>(null)
        private set
    /** True for pictures, false for drawing shapes / text boxes (decides which ribbon tab is offered). */
    var graphicIsImage by mutableStateOf(true)
        private set
    private var pendingShapeMove = false
    private var enterTextAfterMove = false
    /** True while the caret is inside the selected text box/shape (taps place the caret, drags do not move it). */
    /** True while LibreOffice reports the "DrawText" context: the caret is inside a text box / shape. */
    private var inTextBox = false
    private var lastGraphic: RectF? = null
    private var cursorEvents = 0
    private var selHoldUntil = 0L
    private var recoverOffUntil = 0L
    var format by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var wantKeyboard by mutableStateOf(false)
    /** Ribbon tab the UI should switch to (-1 = none). */
    var ribbonTabRequest by mutableIntStateOf(-1)
    var focusTick by mutableIntStateOf(0) // bump to move focus back to the document input
    var finding by mutableStateOf(false)
    /** The spelling panel replaces the ribbon while this is on. */
    var spelling by mutableStateOf(false)

    private val cache = object : LruCache<TileKey, Tile>(96 * 1024 * 1024) {
        override fun sizeOf(key: TileKey, value: Tile) = value.bmp.byteCount
    }
    private val main = Handler(Looper.getMainLooper())

    /** Bumped on every invalidation: a tile rendered while the counter moved is stale and re-rendered. */
    @Volatile private var gen = 0
    private var settleScheduled = false
    private var lastEditAt = 0L
    private val inflight = HashSet<TileKey>()
    @Volatile private var wanted: Set<TileKey> = emptySet()
    private var fling: Job? = null
    private var followCursor = false

    init {
        doc.onMessage = ::onMessage
    }

    /** Pixels per twip that make the document fit the view width at zoom 1. */
    val base: Float get() = if (viewSize.width == 0) 0f else viewSize.width.toFloat() / layout.width

    private val scale get() = base * zoom

    /** Set by the activity so Ctrl+S on a hardware keyboard can save. */
    var onSave: (() -> Unit)? = null
    /** Set by the activity: table borders / looks are written into the exported docx and the document reloads. */
    var onTableOp: ((TableOp) -> Unit)? = null
    /** Border line used by the border presets of the Table tab. */
    var borderLine by mutableStateOf(BorderLine())

    /** Scrollable distance in view pixels (0 when the whole document fits). */
    val scrollRange: Float get() = (layout.height * scale - viewSize.height).coerceAtLeast(0f)
    fun scrollFraction(): Float = if (scrollRange <= 0f) 0f else (-offset.y / scrollRange).coerceIn(0f, 1f)
    fun scrollToFraction(f: Float) { offset = clamp(Offset(offset.x, -f.coerceIn(0f, 1f) * scrollRange)) }
    private val contentWidth get() = layout.width * scale
    private val contentHeight get() = layout.height * scale

    fun clamp(o: Offset): Offset {
        // a grey margin all around the pages, so zooming out leaves room on every side (like Word)
        val pad = 28f * density
        val x = if (contentWidth + 2 * pad <= viewSize.width) (viewSize.width - contentWidth) / 2f
        else o.x.coerceIn(viewSize.width - contentWidth - pad, pad)
        val y = if (contentHeight + 2 * pad <= viewSize.height) pad
        else o.y.coerceIn(viewSize.height - contentHeight - pad, pad)
        return Offset(x, y)
    }

    fun transform(centroid: Offset, pan: Offset, zoomChange: Float) {
        val nz = (zoom * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val k = nz / zoom
        zoom = nz
        offset = clamp(
            Offset(
                centroid.x - (centroid.x - offset.x) * k + pan.x,
                centroid.y - (centroid.y - offset.y) * k + pan.y,
            ),
        )
    }

    fun onViewSize(sz: IntSize) {
        if (sz.width != viewSize.width) { cache.evictAll(); inflight.clear() }
        viewSize = sz
    }

    fun stopFling() { fling?.cancel() }

    fun flingBy(uiScope: CoroutineScope, vx: Float, vy: Float) {
        fling?.cancel()
        fling = uiScope.launch {
            val a = Animatable(offset, Offset.VectorConverter)
            a.animateDecay(Offset(vx, vy), exponentialDecay(frictionMultiplier = 1.4f)) {
                offset = clamp(value)
            }
        }
    }

    fun toggleZoom(at: Offset) {
        val target = if (zoom > 1.3f) 1f else 2.5f
        transform(at, Offset.Zero, target / zoom)
    }

    /** 1-based page index nearest to the view center. */
    fun currentPage(): Int {
        val b = scale
        if (b == 0f) return 1
        val centerY = (viewSize.height / 2f - offset.y) / b
        val pages = layout.pages
        val idx = pages.indexOfFirst { centerY < it.y + it.h }
        return (if (idx < 0) pages.lastIndex else idx) + 1
    }

    // ---- coordinates ----
    private fun twX(px: Float) = ((px - offset.x) / scale)
    private fun twY(py: Float) = ((py - offset.y) / scale)
    private fun pxX(tw: Float) = offset.x + tw * scale
    private fun pxY(tw: Float) = offset.y + tw * scale

    // ---- edit actions ----
    /** Runs [block] on the engine thread, then refreshes layout and marks all tiles stale. */
    private fun edit(block: LoDocument.() -> Unit) {
        lastEditAt = android.os.SystemClock.uptimeMillis()
        blinkOn = true; blinkReset++ // caret stays solid while typing, like Word
        doc.post {
            block()
            refreshLayout()
            main.post {
                this@DocumentViewState.layout = doc.layout
                markStale(nearCursor())
                scheduleSettle()
            }
        }
    }

    /**
     * LibreOffice applies key/mouse/uno input asynchronously and sends no tile invalidations here, so a render
     * right after posting the event would show the old text. Re-render the area around the cursor now, again when
     * LibreOffice reports the cursor/selection change, and everything once input has settled.
     */
    private fun nearCursor(): RectF? {
        val c = cursor ?: return null
        val line = max(c.height(), 200f)
        return RectF(0f, c.top - line, layout.width.toFloat(), c.bottom + 6 * line)
    }

    private fun scheduleSettle() {
        if (settleScheduled) main.removeCallbacks(settle)
        settleScheduled = true
        main.postDelayed(settle, 300)
    }

    private val settle = Runnable {
        settleScheduled = false
        doc.post {
            refreshLayout()
            main.post { this@DocumentViewState.layout = doc.layout; markStale(null) }
        }
    }

    /** True when [at] (view pixels) lies on a page, not on the grey area around it. */
    fun hitsPage(at: Offset): Boolean {
        val x = twX(at.x); val y = twY(at.y)
        return layout.pages.any { x >= it.x && x <= it.x + it.w && y >= it.y && y <= it.y + it.h }
    }

    /** Tap beside the page: leave typing mode (hide the keyboard) without touching the document. */
    fun tapOutside() {
        wantKeyboard = false
        focusTick++
    }

    /** Drops the text selection overlay (rectangles, handles and the hold used against LibreOffice's empty updates). */
    /** Debug hook: raw document twips, [buttons] and [modifier] as in LibreOfficeKit. */
    fun debugMouseTw(type: Int, x: Int, y: Int, count: Int, buttons: Int, modifier: Int) {
        val sc = scale
        edit { mouse(type, x, y, count, sc, buttons, modifier) }
    }

    fun dumpState() {
        android.util.Log.d("MatNotesDump", "inTable=$inTable table=${table?.colX}/${table?.rowY} markers=${tableMarkers().size} editing=$editing box_px=${graphic?.let { "${pxX(it.left).toInt()},${pxY(it.top).toInt() + 262} ${pxX(it.right).toInt()},${pxY(it.bottom).toInt() + 262}" }} inTB=$inTextBox graphic=$graphic img=$graphicIsImage cursor=$cursor vis=$cursorVisible sel=${selection.size} selStart=$selStart hold=${selHoldUntil > 0}")
    }

    private fun clearTextSelection() {
        selHoldUntil = 0L
        selection = emptyList()
        selStart = null
        selEnd = null
    }

    fun click(at: Offset, count: Int = 1) {
        if (count == 1) tableMarkerAt(at)?.let { if (it.minus) deleteAt(it.column, it.k) else insertAtBorder(it.column, it.k); return }
        val g = graphic ?: if (inTextBox) lastGraphic else null
        if (g != null && !graphicIsImage && g.contains(twX(at.x), twY(at.y))) {
            if (!inTextBox && count == 1) { enterTextBox(at); return }
        }
        val x = twX(at.x).roundToInt()
        val y = twY(at.y).roundToInt()
        val s = scale
        followCursor = true
        val seen = cursorEvents
        if (count == 1) { selHoldUntil = 0L; selection = emptyList() }
        edit {
            mouse(Document.MOUSE_EVENT_BUTTON_DOWN, x, y, count, s)
            mouse(Document.MOUSE_EVENT_BUTTON_UP, x, y, count, s)
        }
        wantKeyboard = true
        focusTick++
        main.postDelayed({ ensureCursor() }, 500)
        // a tap that only selected a text box / shape (no caret): go on into its text, like a text box in Word
        if (count == 1 && !inTextBox) main.postDelayed({
            val b = graphic
            if (!inTextBox && b != null && !graphicIsImage && b.contains(twX(at.x), twY(at.y))) enterTextBox(at)
        }, 400)
        // a tap on the empty part of a text box leaves its edit mode: re-enter with the caret at the end
        if (inTextBox && count == 1) main.postDelayed({ if (!inTextBox && android.os.SystemClock.uptimeMillis() > recoverOffUntil) enterTextBox(at) }, 350)
    }

    /**
     * LibreOffice sends a cursor callback only when the caret moves, so a fresh or empty document (caret already at
     * the tapped spot) never shows one. Nudge the caret; in an empty document there is nothing to step over, so type
     * a character and undo it. Leaves no trace in the undo history and does not mark the document changed.
     */
    fun ensureCursor() {
        if (cursor != null || !editing || graphic != null || selection.isNotEmpty()) return
        val seen = cursorEvents
        edit { uno(".uno:CharRight"); uno(".uno:CharLeft") }
        main.postDelayed({
            if (cursor == null && cursorEvents == seen && editing && graphic == null && selection.isEmpty()) {
                edit { type("x") }
                main.postDelayed({ ghostRedo = true; edit { uno(".uno:Undo") } }, 250) // input is applied asynchronously: undo only after the key landed
            }
        }, 300)
    }

    /** Double click inside a text box. The button-up of a double click clears the word selection there, so send none. */
    private fun selectWordAt(at: Offset) {
        val x = twX(at.x).roundToInt()
        val y = twY(at.y).roundToInt()
        val s = scale
        selHoldUntil = 0L
        // LibreOffice only treats the second press as a double click after a first click at the same spot
        edit { mouse(Document.MOUSE_EVENT_BUTTON_DOWN, x, y, 1, s); mouse(Document.MOUSE_EVENT_BUTTON_UP, x, y, 1, s) }
        main.postDelayed({
            selHoldUntil = android.os.SystemClock.uptimeMillis() + 600_000
            edit { mouse(Document.MOUSE_EVENT_BUTTON_DOWN, x, y, 2, s) }
        }, 150)
        main.postDelayed({ edit { mouse(Document.MOUSE_EVENT_BUTTON_UP, x, y, 1, s) } }, 300)
    }

    /**
     * First tap into a selected text box. A double click on the text enters it; on the empty inside of the box LibreOffice
     * only selects the shape, so press Enter to start the text edit with the caret at the end.
     */
    private fun enterTextBox(at: Offset) {
        val s = scale
        followCursor = true
        fun dbl(x: Int, y: Int) = edit {
            mouse(Document.MOUSE_EVENT_BUTTON_DOWN, x, y, 2, s)
            mouse(Document.MOUSE_EVENT_BUTTON_UP, x, y, 2, s)
        }
        val x = twX(at.x).roundToInt()
        val y = twY(at.y).roundToInt()
        dbl(x, y)
        wantKeyboard = true
        focusTick++
        main.postDelayed({
            if (inTextBox) {
                // entered on a word (it got selected): put the caret exactly where the finger was. On empty space the
                // caret already sits at the end and a click there would leave the box again.
                if (selection.isNotEmpty()) {
                    edit { mouse(Document.MOUSE_EVENT_BUTTON_DOWN, x, y, 1, s); mouse(Document.MOUSE_EVENT_BUTTON_UP, x, y, 1, s) }
                }
            } else {
                // the double click only selected the shape: Enter on a selected shape starts its text, caret at the end
                // (never with the body text active: Enter there would add a paragraph)
                if (graphic != null && !graphicIsImage) edit { pressKey(Keys.RETURN) }
            }
        }, 300)
    }

    /** Scroll and zoom of [o], so a reloaded document opens where the user was. */
    fun copyViewFrom(o: DocumentViewState) { zoom = o.zoom; offset = o.offset }

    fun typeText(t: String) { selHoldUntil = 0L; markDirty(); followCursor = true; edit { type(t) } }
    fun pressKey(code: Int) { selHoldUntil = 0L; if (code == Keys.BACKSPACE || code == Keys.DELETE || code == Keys.RETURN) markDirty(); followCursor = true; edit { pressKey(code) } }
    /** Plain text of the document (exported through LibreOffice), or null when the export fails. */
    suspend fun documentText(cacheDir: java.io.File): String? = withContext(Engine.dispatcher) {
        val f = java.io.File(cacheDir, "spellcheck.txt")
        try { if (doc.saveAs(f, "txt")) f.readText() else null } finally { f.delete() }
    }

    private fun wordSearch(word: String, repl: String, command: Int): String {
        fun j(v: String) = v.replace("\\", "\\\\").replace("\"", "\\\"")
        fun a(n: String, t: String, v: String) = "\"SearchItem.$n\":{\"type\":\"$t\",\"value\":\"$v\"}"
        // whole word, case-sensitive (TransliterateFlags 0), regular expression (AlgorithmType2 2)
        return listOf(
            a("SearchString", "string", j("\\b" + word + "\\b")),
            a("ReplaceString", "string", j(repl)),
            a("Command", "long", "$command"),
            a("AlgorithmType2", "short", "2"),
            a("TransliterateFlags", "long", "0"),
        ).joinToString(",", "{", "}")
    }

    /** Selects the first occurrence of [word] and scrolls to it (spelling check). */
    fun findWord(word: String) { followCursor = true; edit { uno(".uno:ExecuteSearch", wordSearch(word, "", 0)) } }

    /** Replaces every occurrence of the whole word [from] by [to] (spelling check). */
    fun replaceWord(from: String, to: String) { markDirty(); edit { uno(".uno:ExecuteSearch", wordSearch(from, to, 3)) } }

    /** Debug: logs the answer of getCommandValues. */
    fun logCommandValues(cmd: String) { doc.post { android.util.Log.d("MatNotesDump", "cmdvalues $cmd = ${commandValues(cmd)}") } }

    fun uno(cmd: String, args: String = "") { if (cmd != ".uno:Copy") markDirty(); edit { uno(cmd, args) } }
    fun markDirty() { dirty = true; ghostRedo = false }

    /** True while the only redo entry is the probe character of [ensureCursor]. */
    private var ghostRedo by mutableStateOf(false)
    fun redoEnabled() = format[".uno:Redo"] != "disabled" && !ghostRedo

    /**
     * LibreOffice inserts a new shape centred on the page's top-left corner. Insert, then move it to the middle of
     * what the user currently sees (kept inside the page).
     */
    /** Inserting a picture/shape needs no typing: drop the keyboard. */
    fun hideKeyboardNow() {
        wantKeyboard = false
        focusTick++
    }

    fun insertShape(cmd: String) {
        pendingShapeMove = true
        enterTextAfterMove = cmd == ".uno:DrawText"
        if (!enterTextAfterMove) hideKeyboardNow()
        uno(cmd)
    }

    private fun moveGraphicToViewCenter(r: RectF) {
        val cx = (r.left + r.right) / 2f
        val cy = (r.top + r.bottom) / 2f
        var tx = twX(viewSize.width / 2f)
        var ty = twY(viewSize.height / 2f)
        val page = layout.pages.firstOrNull { ty >= it.y && ty <= it.y + it.h } ?: layout.pages.firstOrNull() ?: return
        val hw = r.width() / 2f
        val hh = r.height() / 2f
        tx = tx.coerceIn(page.x + hw, max(page.x + hw, page.x + page.w - hw))
        ty = ty.coerceIn(page.y + hh, max(page.y + hh, page.y + page.h - hh))
        val s = scale
        edit {
            setGraphicSelection(Document.SET_GRAPHIC_SELECTION_START, cx.roundToInt(), cy.roundToInt(), s)
            setGraphicSelection(Document.SET_GRAPHIC_SELECTION_END, tx.roundToInt(), ty.roundToInt(), s)
        }
        if (enterTextAfterMove) {
            enterTextAfterMove = false
            // a text box is useless until the caret is inside it: double-click its middle to start typing
            main.postDelayed({ if (!inTextBox) click(Offset(pxX(tx), pxY(ty)), 2) }, 700)
        }
    }

    /** Long press: select an image/shape under the finger, otherwise select the word (double click). */
    /** Double tap: select the word; in a text box that is not entered yet it enters first. */
    fun doubleTap(at: Offset) {
        val box = graphic ?: if (inTextBox) lastGraphic else null
        val inside = box != null && !graphicIsImage && box.contains(twX(at.x), twY(at.y))
        recoverOffUntil = android.os.SystemClock.uptimeMillis() + 2000
        when {
            inside && inTextBox -> selectWordAt(at)
            inside -> enterTextBox(at)
            else -> click(at, 1) // selects the box; the tap logic then enters it
        }
        if (!(inside && inTextBox)) main.postDelayed({ if (inTextBox && selection.isEmpty()) selectWordAt(at) }, 1100)
    }

    fun longPress(at: Offset) {
        val box = graphic ?: if (inTextBox) lastGraphic else null
        if (box != null && !graphicIsImage && box.contains(twX(at.x), twY(at.y))) {
            // inside a text box: a double click enters it (if needed) and selects the word
            recoverOffUntil = android.os.SystemClock.uptimeMillis() + 1500
            selectWordAt(at)
            return
        }
        click(at, 1)
        main.postDelayed({ if (graphic == null && selection.isEmpty()) click(at, 2) }, 380)
    }

    /** Top-centre / bottom of the selected image or shape in view pixels (for its floating toolbar). */
    fun graphicAnchor(): Offset? {
        val g = graphic ?: return null
        return Offset(pxX((g.left + g.right) / 2f), pxY(g.top))
    }

    fun graphicBottom(): Float = pxY(graphic?.bottom ?: 0f)

    /** Character formatting: applies to the selection, or to what is typed next when nothing is selected. */
    fun charFormat(cmd: String, args: String = "") {
        markDirty()
        edit { uno(cmd, args) }
    }

    // ---- system clipboard ----
    /** Set by the activity: the application context (clipboard) and the picture insertion. */
    var appContext: android.content.Context? = null
    var onPasteImage: ((android.net.Uri) -> Unit)? = null

    private fun clipboard() = appContext?.getSystemService(android.content.ClipboardManager::class.java)
    private fun clipStamp(): Long = clipboard()?.primaryClipDescription?.timestamp ?: 0L

    /**
     * Copy / cut through the phone's clipboard (plain text + HTML, so other apps can paste it). LibreOffice's own
     * clipboard is filled too: pasting what was copied here goes through it, which keeps tables, pictures and shapes
     * exact (see [paste]).
     */
    fun copy(cut: Boolean = false) {
        if (cut) markDirty()
        val cmd = if (cut) ".uno:Cut" else ".uno:Copy"
        val graphicOnly = graphic != null && !inTextBox
        doc.post {
            val plain = if (graphicOnly) null else selectedText()
            val html = if (plain.isNullOrEmpty()) null else selectionAs("text/html")
            uno(cmd)
            refreshLayout()
            main.post {
                this@DocumentViewState.layout = doc.layout
                markStale(nearCursor()); scheduleSettle()
                val cm = clipboard()
                if (cm != null && !plain.isNullOrEmpty()) {
                    val clip = if (html.isNullOrEmpty()) android.content.ClipData.newPlainText("MatNotes", plain)
                    else android.content.ClipData.newHtmlText("MatNotes", plain, html)
                    runCatching { cm.setPrimaryClip(clip) }
                }
                // what is on the phone clipboard right now came from us (or, for a picture, is older than the copy)
                ownClipStamp = clipStamp()
            }
        }
    }

    private var ownClipStamp = -1L

    /** A single `<p>...</p>` pasted as HTML leaves an extra empty paragraph behind: hand over its content inline. */
    private fun inlineHtml(html: String): String {
        val body = Regex("(?is)<body[^>]*>(.*)</body>").find(html)?.groupValues?.get(1) ?: html
        val cleaned = body.replace(Regex("(?is)<!--.*?-->"), "").trim()
        val one = Regex("(?is)^<(p|div)\\b[^>]*>(.*)</\\1>$").find(cleaned) ?: return html
        val inner = one.groupValues[2]
        return if (Regex("(?i)<(p|div|br|li|ul|ol|table|h[1-6]|pre|blockquote)\\b").containsMatchIn(inner)) html else inner
    }

    /** Paste from the phone's clipboard: a picture, HTML or plain text. Our own copy goes through LibreOffice. */
    fun paste() {
        markDirty()
        val cm = clipboard()
        val clip = cm?.primaryClip
        if (cm == null || clip == null || clip.itemCount == 0 || clipStamp() == ownClipStamp) { edit { uno(".uno:Paste") }; return }
        val item = clip.getItemAt(0)
        val desc = clip.description
        val uri = item.uri
        if (uri != null && desc.hasMimeType("image/*")) { onPasteImage?.invoke(uri); return }
        val ctx = appContext
        val html = if (desc.hasMimeType("text/html")) item.htmlText?.let { inlineHtml(it) } else null
        val text = item.coerceToText(ctx)?.toString()
        if (html.isNullOrEmpty() && text.isNullOrEmpty()) { edit { uno(".uno:Paste") }; return }
        followCursor = true
        edit {
            val ok = !html.isNullOrEmpty() && pasteData("text/html", html)
            if (!ok && !text.isNullOrEmpty()) pasteData("text/plain;charset=utf-8", text)
        }
    }

    /** Link on the selection, or at the cursor: type [text], select it, then apply the hyperlink. */
    fun insertLink(text: String, url: String) {
        markDirty()
        fun j(v: String) = v.replace("\\", "\\\\").replace("\"", "\\\"")
        val args = "{\"Hyperlink.Text\":{\"type\":\"string\",\"value\":\"${j(text)}\"}," +
            "\"Hyperlink.URL\":{\"type\":\"string\",\"value\":\"${j(url)}\"}}"
        val hadSelection = selection.isNotEmpty()
        edit {
            if (!hadSelection) {
                type(text)
                repeat(text.length) { uno(".uno:CharLeftSel") }
            }
            uno(".uno:SetHyperlink", args)
            // the selection was made leftwards: the first RIGHT collapses it, the rest walk to its end
            repeat(if (hadSelection) 1 else text.length) { pressKey(Keys.RIGHT) }
        }
    }

    /** Top-centre of the selection in view pixels (for the floating toolbar). */
    fun selectionAnchor(): Offset? {
        val sel = selection
        if (sel.isEmpty()) return null
        val top = sel.minOf { it.top }
        return Offset(pxX((sel.minOf { it.left } + sel.maxOf { it.right }) / 2f), pxY(top))
    }

    fun selectionBottom(): Float = pxY(selection.maxOfOrNull { it.bottom } ?: 0f)

    /** Word-style zoom: 100 % is the real paper size on this screen. */
    fun zoomPercent(xdpi: Float): Int = if (xdpi <= 0f) 100 else (1440f * scale / xdpi * 100f).roundToInt()

    fun zoomBy(f: Float) = transform(Offset(viewSize.width / 2f, viewSize.height / 2f), Offset.Zero, f)
    /** The current page fills the width with the same grey gap on left, right and top. */
    fun fitWidth() {
        val pg = layout.pages.getOrNull(currentPage() - 1) ?: layout.pages.firstOrNull() ?: return
        if (viewSize.width == 0 || base == 0f || pg.w == 0) return
        val gap = 12f * density
        zoom = ((viewSize.width - 2 * gap) / (pg.w * base)).coerceIn(MIN_ZOOM, MAX_ZOOM)
        offset = clamp(Offset(gap - pg.x * scale, gap - pg.y * scale))
    }

    // ---- drag handling for selection handles / graphics ----
    private var drag: Drag? = null
    private var dragStartTw = Offset.Zero
    private var dragRectH = 0f
    private var bodyStarted = false
    // table border drag (column width / row height): LibreOffice resizes with Alt+arrow keys, one step per press
    private var borderColumn = true
    private var borderIndex = 0
    private var borderStarted = false
    private var borderStartPos = 0f
    private var borderStartExtent = 0f
    private var borderKeyAt = 0L
    private var borderNet = 0
    private var bodyReadyAt = 0L
    private var dragHandleTw = Offset.Zero
    private var dragPos = Offset.Zero
    private var dragHandleIdx = -1
    private var dragRect = RectF()
    var preview by mutableStateOf<RectF?>(null)
        private set

    private fun dist(a: Offset, b: Offset) = (a - b).getDistance()

    fun beginDrag(p: Offset): Boolean {
        if (!editing) return false
        val hit = 28f * density
        if (selection.isNotEmpty()) {
            // both handles can overlap on a short selection: take the nearer one
            val ds = selStart?.let { dist(p, Offset(pxX(it.left), pxY(it.bottom) + 10 * density)) } ?: Float.MAX_VALUE
            val de = selEnd?.let { dist(p, Offset(pxX(it.right), pxY(it.bottom) + 10 * density)) } ?: Float.MAX_VALUE
            if (ds < hit && ds < de) { selStart?.let { startDrag(Drag.SelStart, p, Offset(it.left, it.bottom), it.height()); return true } }
            if (de < hit) { selEnd?.let { startDrag(Drag.SelEnd, p, Offset(it.right, it.bottom), it.height()); return true } }
        }
        // inner table borders: drag to change a column width / row height
        table?.takeIf { graphic == null && inTable && editing }?.let { t ->
            val near = 14f * density
            val top = pxY(t.rowY.first()); val bottom = pxY(t.rowY.last())
            val left = pxX(t.colX.first()); val right = pxX(t.colX.last())
            if (p.y in top..bottom) for (k in 1 until t.colX.size - 1) if (abs(p.x - pxX(t.colX[k])) < near) {
                startDrag(Drag.TableBorder, p, Offset(t.colX[k], t.rowY.first()), 0f)
                borderColumn = true; borderIndex = k; borderStarted = false; return true
            }
            if (p.x in left..right) for (k in 1 until t.rowY.size - 1) if (abs(p.y - pxY(t.rowY[k])) < near) {
                startDrag(Drag.TableBorder, p, Offset(t.colX.first(), t.rowY[k]), 0f)
                borderColumn = false; borderIndex = k; borderStarted = false; return true
            }
        }
        graphic?.let { g ->
            val handles = graphicHandles(g)
            // small images: shrink the hit radius so edge handles do not steal the corners
            val minSidePx = min(g.width(), g.height()) * scale
            val radius = min(hit, max(minSidePx * 0.35f, 10f * density)).let { if (inTextBox && !graphicIsImage) min(it, 12f * density) else it }
            val corners = listOf(0, 2, 5, 7)
            fun d(i: Int) = dist(p, Offset(pxX(handles[i].x), pxY(handles[i].y)))
            val idx = corners.minByOrNull(::d)?.takeIf { d(it) < radius }
                ?: handles.indices.minByOrNull(::d)?.takeIf { d(it) < radius }
                ?: -1
            if (idx >= 0) {
                val h = handles[idx]
                val s = scale
                startDrag(Drag.GraphicHandle, p, h, 0f)
                dragHandleIdx = idx
                dragRect = RectF(g)
                markDirty()
                edit { setGraphicSelection(Document.SET_GRAPHIC_SELECTION_START, h.x.roundToInt(), h.y.roundToInt(), s) }
                return true
            }
            if (g.contains(twX(p.x), twY(p.y))) {
                // hold and drag moves a picture, shape or text box
                // the move only starts after touch slop (dragTo), so a plain tap inside the box stays a tap
                startDrag(Drag.GraphicBody, p, Offset(twX(p.x), twY(p.y)), 0f)
                bodyStarted = false
                return true
            }
        }
        return false
    }

    private fun graphicHandles(g: RectF): List<Offset> {
        val cx = (g.left + g.right) / 2; val cy = (g.top + g.bottom) / 2
        return listOf(
            Offset(g.left, g.top), Offset(cx, g.top), Offset(g.right, g.top),
            Offset(g.left, cy), Offset(g.right, cy),
            Offset(g.left, g.bottom), Offset(cx, g.bottom), Offset(g.right, g.bottom),
        )
    }

    private fun startDrag(kind: Drag, p: Offset, tw: Offset, rectH: Float) {
        drag = kind; dragPos = p; dragStartTw = Offset(twX(p.x), twY(p.y)); dragHandleTw = tw; dragRectH = rectH
        followCursor = false
    }

    fun dragConsumesEvents(): Boolean = when (drag) { Drag.GraphicBody -> bodyStarted; Drag.TableBorder -> borderStarted; else -> true }

    fun dragTo(p: Offset) {
        val kind = drag ?: return
        dragPos = p
        val cur = Offset(twX(p.x), twY(p.y))
        when (kind) {
            Drag.SelStart, Drag.SelEnd -> {
                val pt = dragHandleTw + (cur - dragStartTw)
                val type = if (kind == Drag.SelStart) Document.SET_TEXT_SELECTION_START else Document.SET_TEXT_SELECTION_END
                val x = pt.x.roundToInt(); val y = (pt.y - dragRectH / 2).roundToInt()
                doc.post { setTextSelection(type, x, y) }
            }
            Drag.TableBorder -> dragTableBorder(p, cur)
            Drag.GraphicBody -> {
                val g = graphic ?: return
                if (!bodyStarted) {
                    if (dist(p, Offset(pxX(dragStartTw.x), pxY(dragStartTw.y))) < 8f * density) return
                    bodyStarted = true
                    clearTextSelection()
                    markDirty()
                    val t = dragHandleTw; val sc = scale
                    // inside the text edit LibreOffice would take the drag as a text selection: leave the edit first
                    if (inTextBox) {
                        edit { pressKey(Keys.ESCAPE) }
                        bodyReadyAt = android.os.SystemClock.uptimeMillis() + 200
                        main.postDelayed({ edit { setGraphicSelection(Document.SET_GRAPHIC_SELECTION_START, t.x.roundToInt(), t.y.roundToInt(), sc) } }, 200)
                    } else {
                        bodyReadyAt = 0L
                        edit { setGraphicSelection(Document.SET_GRAPHIC_SELECTION_START, t.x.roundToInt(), t.y.roundToInt(), sc) }
                    }
                }
                val d = cur - dragStartTw
                preview = RectF(g.left + d.x, g.top + d.y, g.right + d.x, g.bottom + d.y)
            }
            Drag.GraphicHandle -> {
                val g = graphic ?: return
                val d = cur - dragStartTw
                val r = RectF(g)
                val x = dragHandleTw.x + d.x; val y = dragHandleTw.y + d.y
                if (dragHandleIdx in listOf(0, 2, 5, 7)) {
                    // corners keep the aspect ratio: scale around the opposite corner by the larger change
                    val w = g.width(); val h = g.height()
                    val right = dragHandleIdx == 2 || dragHandleIdx == 7
                    val bottom = dragHandleIdx == 5 || dragHandleIdx == 7
                    val sx = (if (right) w + d.x else w - d.x) / w
                    val sy = (if (bottom) h + d.y else h - d.y) / h
                    val k = max(sx, sy).coerceAtLeast(0.05f)
                    val nw = w * k; val nh = h * k
                    preview = RectF(
                        if (right) g.left else g.right - nw,
                        if (bottom) g.top else g.bottom - nh,
                        if (right) g.left + nw else g.right,
                        if (bottom) g.top + nh else g.bottom,
                    )
                    return
                }
                when (dragHandleIdx) {
                    0 -> { r.left = x; r.top = y }
                    1 -> r.top = y
                    2 -> { r.right = x; r.top = y }
                    3 -> r.left = x
                    4 -> r.right = x
                    5 -> { r.left = x; r.bottom = y }
                    6 -> r.bottom = y
                    7 -> { r.right = x; r.bottom = y }
                }
                preview = r
            }
        }
    }

    fun endDrag() {
        val kind = drag ?: return
        drag = null
        val cur = Offset(twX(dragPos.x), twY(dragPos.y))
        val delta = cur - dragStartTw
        when (kind) {
            Drag.GraphicBody -> {
                if (!bodyStarted) { preview = null; return }
                val pt = dragHandleTw + delta
                preview = null
                val s = scale
                val wait = (bodyReadyAt - android.os.SystemClock.uptimeMillis()).coerceAtLeast(0L) + 30
                main.postDelayed({ edit { setGraphicSelection(Document.SET_GRAPHIC_SELECTION_END, pt.x.roundToInt(), pt.y.roundToInt(), s) } }, wait)
            }
            Drag.GraphicHandle -> {
                val pv = preview
                preview = null
                val s = scale
                // corners: end exactly at the aspect-preserving corner shown in the preview, not at the finger
                val pt = if (pv != null && dragHandleIdx in listOf(0, 2, 5, 7)) {
                    Offset(
                        if (dragHandleIdx == 0 || dragHandleIdx == 5) pv.left else pv.right,
                        if (dragHandleIdx == 0 || dragHandleIdx == 2) pv.top else pv.bottom,
                    )
                } else dragHandleTw + delta
                edit { setGraphicSelection(Document.SET_GRAPHIC_SELECTION_END, pt.x.roundToInt(), pt.y.roundToInt(), s) }
            }
            Drag.TableBorder -> if (borderStarted) finishTableBorder()
            else -> Unit
        }
    }

    /** Puts the caret into a cell next to the dragged border: the cell left of / above it. */
    private fun caretNextToBorder(t: TableGeom) {
        val sc = scale
        val k = borderIndex
        val x: Int; val y: Int
        if (borderColumn) {
            x = ((t.colX[k - 1] + t.colX[k]) / 2f).roundToInt()
            y = (cursor?.let { (it.top + it.bottom) / 2f } ?: (t.rowY[0] + 150f)).roundToInt()
        } else {
            x = (cursor?.left ?: (t.colX[0] + 150f)).roundToInt()
            y = ((t.rowY[k - 1] + t.rowY[k]) / 2f).roundToInt()
        }
        edit { mouse(Document.MOUSE_EVENT_BUTTON_DOWN, x, y, 1, sc); mouse(Document.MOUSE_EVENT_BUTTON_UP, x, y, 1, sc) }
    }

    private fun dragTableBorder(p: Offset, cur: Offset) {
        val t = table ?: return
        if (!borderStarted) {
            if (dist(p, Offset(pxX(dragStartTw.x), pxY(dragStartTw.y))) < 8f * density) return
            borderStarted = true
            markDirty()
            borderNet = 0
            borderStartPos = if (borderColumn) t.colX[borderIndex] else t.rowY[borderIndex]
            borderStartExtent = if (borderColumn) t.colX.last() - t.colX.first() else t.rowY.last() - t.rowY.first()
            caretNextToBorder(t)
            borderKeyAt = android.os.SystemClock.uptimeMillis() + 250 // let the caret settle first
        }
        val now = android.os.SystemClock.uptimeMillis()
        if (now < borderKeyAt) return
        val want = borderStartPos + if (borderColumn) cur.x - dragStartTw.x else cur.y - dragStartTw.y
        val have = if (borderColumn) t.colX.getOrNull(borderIndex) else t.rowY.getOrNull(borderIndex)
        if (have == null) return
        val diff = want - have
        if (abs(diff) < 150f) return
        val grow = diff > 0
        val key = (if (borderColumn) (if (grow) Keys.RIGHT else Keys.LEFT) else (if (grow) Keys.DOWN else Keys.UP)) or Keys.ALT
        borderNet += if (grow) 1 else -1
        borderKeyAt = now + 140 // wait for the new geometry callback before judging again
        edit { pressKey(key) }
    }

    /**
     * Alt+Left/Up shrink the table instead of widening the neighbour. Put the lost extent back on the last column
     * (rows need nothing: the table just gets shorter/longer like in Word).
     */
    private fun finishTableBorder() {
        if (!borderColumn) return
        if (table == null) return
        val start = borderStartExtent
        scope.launch {
            repeat(12) {
                kotlinx.coroutines.delay(180)
                val t = table ?: return@launch
                val missing = start - (t.colX.last() - t.colX.first())
                if (missing < 200f) return@launch
                val sc = scale
                val x = ((t.colX[t.colX.size - 2] + t.colX.last()) / 2f).roundToInt()
                val y = ((t.rowY[0] + t.rowY[1]) / 2f).roundToInt()
                edit {
                    mouse(Document.MOUSE_EVENT_BUTTON_DOWN, x, y, 1, sc); mouse(Document.MOUSE_EVENT_BUTTON_UP, x, y, 1, sc)
                    pressKey(Keys.RIGHT or Keys.ALT)
                }
                kotlinx.coroutines.delay(150)
            }
        }
    }

    // ---- LibreOfficeKit callbacks (main thread) ----
    private fun onMessage(type: Int, payload: String) {
        if (BuildConfig.DEBUG && type != Document.CALLBACK_STATE_CHANGED) android.util.Log.d("MatNotesCb", "$type $payload")
        when (type) {
            Document.CALLBACK_INVALIDATE_TILES -> invalidate(parseRects(payload).firstOrNull())
            Document.CALLBACK_DOCUMENT_SIZE_CHANGED -> { layout = doc.layout; tick++ }
            Document.CALLBACK_INVALIDATE_VISIBLE_CURSOR -> {
                cursor = parseRects(payload).firstOrNull()
                cursorEvents++
                blinkOn = true; blinkReset++ // a moved caret is solid for a moment
                if (android.os.SystemClock.uptimeMillis() - lastEditAt < 1500) { markStale(nearCursor()); scheduleSettle() }
                if (followCursor) scrollToCursor()
            }
            CALLBACK_TABLE_GEOMETRY -> table = parseTable(payload)
            CALLBACK_CONTEXT_CHANGED -> {
                // context "Text" and "Table" arrive back to back after the geometry: the geometry callback alone owns `table`
                inTable = payload.trim().endsWith("Table")
                val was = inTextBox
                inTextBox = payload.trim().endsWith("DrawText")
                if (was && !inTextBox) clearTextSelection()
            }
            Document.CALLBACK_CURSOR_VISIBLE -> cursorVisible = payload.trim() == "true"
            Document.CALLBACK_GRAPHIC_VIEW_SELECTION, Document.CALLBACK_REDLINE_TABLE_ENTRY_MODIFIED -> scheduleSettle()
            Document.CALLBACK_TEXT_SELECTION -> {
                val rects = parseRects(payload)
                val now = android.os.SystemClock.uptimeMillis()
                // inside a text box LibreOffice reports the new word selection and then an empty one although the
                // selection is still active: keep showing it for a moment after a selection gesture
                if (rects.isEmpty() && inTextBox && selection.isNotEmpty() && now < selHoldUntil) return
                if (rects.isNotEmpty() && inTextBox) selHoldUntil = now + 600_000
                selection = rects
                if (finding) selection.firstOrNull()?.let { scrollToRect(it) }
            }
            Document.CALLBACK_TEXT_SELECTION_START -> selStart = parseRects(payload).firstOrNull()
            Document.CALLBACK_TEXT_SELECTION_END -> selEnd = parseRects(payload).firstOrNull()
            Document.CALLBACK_GRAPHIC_SELECTION -> {
                val r = parseRects(payload).firstOrNull()
                graphic = r
                // the box moved or was resized: selection rectangles and caret of the old position are stale
                val old = lastGraphic
                if (r != null && old != null && (old.left != r.left || old.top != r.top || old.right != r.right)) clearTextSelection()
                if (r != null) lastGraphic = r
                graphicIsImage = Regex("\"isWriterGraphic\"\\s*:\\s*true").containsMatchIn(payload)
                if (pendingShapeMove && r != null) {
                    pendingShapeMove = false
                    moveGraphicToViewCenter(r)
                }
            }
            Document.CALLBACK_STATE_CHANGED -> {
                val i = payload.indexOf('=')
                if (i > 0) format = format + (payload.substring(0, i) to payload.substring(i + 1))
            }
        }
        tick++
    }

    private fun parseTable(payload: String): TableGeom? = try {
        val o = org.json.JSONObject(payload)
        fun axis(name: String): List<Float>? {
            val a = o.optJSONObject(name) ?: return null
            val off = a.optDouble("tableOffset", 0.0).toFloat()
            val e = a.optJSONArray("entries") ?: return null
            val lo = a.optDouble("left", 0.0).toFloat()
            val hi = a.optDouble("right", 0.0).toFloat()
            return listOf(off + lo) + (0 until e.length()).map { off + e.getJSONObject(it).getDouble("position").toFloat() } + listOf(off + hi)
        }
        val c = axis("columns"); val r = axis("rows")
        if (c != null && r != null) TableGeom(c, r) else null
    } catch (e: Exception) { null }

    /** Bounds of the caret's table in view pixels. */
    fun tableBounds(): RectF? {
        val t = table ?: return null
        return RectF(pxX(t.colX.first()), pxY(t.rowY.first()), pxX(t.colX.last()), pxY(t.rowY.last()))
    }

    /** "+" buttons on the borders: above the table for columns, left of it for rows. Crowded ones are left out. */
    fun tableMarkers(): List<TableMarker> {
        val t = table ?: return emptyList()
        if (!editing) return emptyList()
        val top = pxY(t.rowY.first()); val left = pxX(t.colX.first())
        val xs = t.colX.map { pxX(it) }; val ys = t.rowY.map { pxY(it) }
        val out = ArrayList<TableMarker>()
        // bubble size follows the narrowest column / row so neighbours do not overlap
        fun radius(v: List<Float>): Float {
            val m = v.zipWithNext { a, b -> b - a }.minOrNull() ?: (24f * density)
            return (m * 0.4f).coerceAtMost(11f * density)
        }
        val rc = radius(xs); val rr = radius(ys)
        // zoomed far out the bubbles would be larger than the cells: hide that axis' bubbles until the user zooms in
        val minR = 5f * density
        val plusY = top - (2f * rc + 4f * density); val minusY = plusY - (2f * rc + 6f * density)
        val plusX = left - (2f * rr + 4f * density); val minusX = plusX - (2f * rr + 6f * density)
        if (rc >= minR) xs.forEachIndexed { k, x -> out += TableMarker(Offset(x, plusY), true, k, false, Offset(x, top), rc) }
        if (rc >= minR && xs.size > 2) for (i in 0 until xs.size - 1) {
            val c = (xs[i] + xs[i + 1]) / 2f
            out += TableMarker(Offset(c, minusY), true, i, true, Offset(c, top), rc)
        }
        if (rr >= minR) ys.forEachIndexed { k, y -> out += TableMarker(Offset(plusX, y), false, k, false, Offset(left, y), rr) }
        if (rr >= minR && ys.size > 2) for (i in 0 until ys.size - 1) {
            val c = (ys[i] + ys[i + 1]) / 2f
            out += TableMarker(Offset(minusX, c), false, i, true, Offset(left, c), rr)
        }
        return out
    }

    private fun tableMarkerAt(at: Offset): TableMarker? =
        tableMarkers().minByOrNull { dist(at, it.center) }?.takeIf { dist(at, it.center) < it.r + 8f * density }

    /** Deletes column/row [i]: the caret goes into one of its cells first. */
    fun deleteAt(column: Boolean, i: Int) {
        val t = table ?: return
        val cur = cursor
        val sc = scale
        markDirty()
        if (column) {
            val x = ((t.colX[i] + t.colX[i + 1]) / 2f).roundToInt()
            val y = (cur?.let { (it.top + it.bottom) / 2f } ?: (t.rowY[0] + 150f)).roundToInt()
            edit {
                mouse(Document.MOUSE_EVENT_BUTTON_DOWN, x, y, 1, sc); mouse(Document.MOUSE_EVENT_BUTTON_UP, x, y, 1, sc)
                uno(".uno:DeleteColumns")
            }
        } else {
            val y = ((t.rowY[i] + t.rowY[i + 1]) / 2f).roundToInt()
            val x = (cur?.left ?: (t.colX[0] + 150f)).roundToInt()
            edit {
                mouse(Document.MOUSE_EVENT_BUTTON_DOWN, x, y, 1, sc); mouse(Document.MOUSE_EVENT_BUTTON_UP, x, y, 1, sc)
                uno(".uno:DeleteRows")
            }
        }
    }

    /** Inserts a column/row at border [k]: put the caret in the neighbouring cell, then use LibreOffice's command. */
    fun insertAtBorder(column: Boolean, k: Int) {
        val t = table ?: return
        val cur = cursor
        val sc = scale
        markDirty()
        if (column) {
            val n = t.colX.size - 1
            val col = if (k == 0) 0 else k - 1
            val x = ((t.colX[col] + t.colX[col + 1]) / 2f).roundToInt()
            val y = (cur?.let { (it.top + it.bottom) / 2f } ?: (t.rowY[0] + 150f)).roundToInt()
            edit {
                mouse(Document.MOUSE_EVENT_BUTTON_DOWN, x, y, 1, sc); mouse(Document.MOUSE_EVENT_BUTTON_UP, x, y, 1, sc)
                uno(if (k == 0) ".uno:InsertColumnsBefore" else ".uno:InsertColumnsAfter")
            }
            if (n < 0) return
        } else {
            val row = if (k == 0) 0 else k - 1
            val y = ((t.rowY[row] + t.rowY[row + 1]) / 2f).roundToInt()
            val x = (cur?.left ?: (t.colX[0] + 150f)).roundToInt()
            edit {
                mouse(Document.MOUSE_EVENT_BUTTON_DOWN, x, y, 1, sc); mouse(Document.MOUSE_EVENT_BUTTON_UP, x, y, 1, sc)
                uno(if (k == 0) ".uno:InsertRowsBefore" else ".uno:InsertRowsAfter")
            }
        }
    }

    private fun parseRects(s: String): List<RectF> {
        if (s.isBlank() || s.startsWith("EMPTY") || s.startsWith("{")) return emptyList()
        return s.split(';').mapNotNull { part ->
            val n = part.split(',').mapNotNull { it.trim().toFloatOrNull() }
            if (n.size >= 4 && n[2] >= 0f && n[3] > 0f) RectF(n[0], n[1], n[0] + n[2], n[1] + n[3]) else null
        }
    }

    private fun invalidate(r: RectF?) = markStale(r)

    private fun tileRect(k: TileKey): RectF? {
        val p = layout.pages.getOrNull(k.page) ?: return null
        val rs = base * (k.level / BASE_LEVEL.toFloat())
        if (rs <= 0f) return null
        val t = tileTwips(rs)
        val x = p.x + k.tx * t.toFloat(); val y = p.y + k.ty * t.toFloat()
        return RectF(x, y, x + min(t, p.w - k.tx * t).toFloat(), y + min(t, p.h - k.ty * t).toFloat())
    }

    /** Marks cached tiles intersecting [r] (document twips; null = all) stale. Stale tiles stay visible until re-rendered. */
    private fun markStale(r: RectF?) {
        gen++
        for ((k, t) in cache.snapshot()) {
            if (r == null) t.stale = true
            else tileRect(k)?.let { if (RectF.intersects(it, r)) t.stale = true }
        }
        tick++
    }

    fun scrollToRect(r: RectF) {
        if (viewSize.height == 0) return
        val top = pxY(r.top); val bottom = pxY(r.bottom)
        val margin = 48f * density
        var dy = 0f
        if (bottom + margin > viewSize.height) dy = viewSize.height - bottom - margin
        else if (top - margin < 0f) dy = margin - top
        if (dy != 0f) offset = clamp(offset.copy(y = offset.y + dy))
    }

    fun scrollToCursor() {
        val c = cursor ?: return
        if (viewSize.height == 0) return
        val top = pxY(c.top); val bottom = pxY(c.bottom)
        val margin = 24f * density
        var dy = 0f
        if (bottom + margin > viewSize.height) dy = viewSize.height - bottom - margin
        else if (top - margin < 0f) dy = margin - top
        if (dy != 0f) offset = clamp(offset.copy(y = offset.y + dy))
    }

    private fun tileTwips(renderScale: Float) = (TILE_PX / renderScale).toInt().coerceAtLeast(1)

    // ---- drawing ----
    private fun level(): Int = ceil(zoom * BASE_LEVEL - 0.001f).toInt().coerceIn(1, (MAX_ZOOM * BASE_LEVEL).toInt())

    fun draw(scope: DrawScope) = with(scope) {
        tick // subscribe to tile updates
        drawRect(PageGray)
        val b = base
        if (b == 0f) return
        val zb = scale
        val level = level()
        val want = HashSet<TileKey>()
        layout.pages.forEachIndexed { pi, p ->
            val px = offset.x + p.x * zb
            val py = offset.y + p.y * zb
            val pw = p.w * zb
            val ph = p.h * zb
            if (py > size.height || py + ph < 0f || px > size.width || px + pw < 0f) return@forEachIndexed
            drawRect(Color.White, Offset(px, py), Size(pw, ph))
            if (level > BASE_LEVEL) drawLevel(pi, p, BASE_LEVEL, px, py, zb, want, request = false)
            drawLevel(pi, p, level, px, py, zb, want, request = true)
        }
        wanted = want
        if (editing) drawOverlay()
    }

    private fun DrawScope.drawLevel(
        pi: Int, p: PageRect, level: Int, px: Float, py: Float, zb: Float, want: MutableSet<TileKey>, request: Boolean,
    ) {
        val rs = base * (level / BASE_LEVEL.toFloat())
        val tileTw = tileTwips(rs).toFloat()
        val cols = ceil(p.w / tileTw).toInt()
        val rows = ceil(p.h / tileTw).toInt()
        val x0 = max(0, ((0f - px) / (tileTw * zb)).toInt())
        val x1 = min(cols - 1, ((size.width - px) / (tileTw * zb)).toInt())
        val y0 = max(0, ((0f - py) / (tileTw * zb)).toInt())
        val y1 = min(rows - 1, ((size.height - py) / (tileTw * zb)).toInt())
        for (ty in y0..y1) for (tx in x0..x1) {
            val k = TileKey(pi, level, tx, ty)
            if (request) want += k
            val twX = tx * tileTw
            val twY = ty * tileTw
            val twW = min(tileTw, p.w - twX)
            val twH = min(tileTw, p.h - twY)
            val t = cache.get(k)
            if (t != null) drawBitmap(t.bmp, px + twX * zb, py + twY * zb, twW * zb, twH * zb)
            if (request && (t == null || t.stale)) request(k)
        }
    }

    private fun DrawScope.drawBitmap(bmp: Bitmap, x: Float, y: Float, w: Float, h: Float) {
        // snap both edges to whole pixels so neighbouring tiles meet without gaps or stretching
        val l = x.roundToInt(); val t = y.roundToInt()
        val r = (x + w).roundToInt(); val b = (y + h).roundToInt()
        drawImage(
            bmp.asImageBitmap(),
            dstOffset = IntOffset(l, t),
            dstSize = IntSize((r - l).coerceAtLeast(1), (b - t).coerceAtLeast(1)),
        )
    }

    private fun DrawScope.drawOverlay() {
        val zb = scale
        for (r in selection) {
            drawRect(
                SelectionColor,
                Offset(pxX(r.left), pxY(r.top)),
                Size(r.width() * zb, r.height() * zb),
            )
        }
        val hr = 5.5f * density
        if (selection.isNotEmpty()) {
            selStart?.let { drawHandle(Offset(pxX(it.left), pxY(it.bottom)), hr) }
            selEnd?.let { drawHandle(Offset(pxX(it.right), pxY(it.bottom)), hr) }
        }
        graphic?.let { g ->
            val tl = Offset(pxX(g.left), pxY(g.top))
            drawRect(AccentColor, tl, Size(g.width() * zb, g.height() * zb), style = Stroke(2f * density))
            graphicHandles(g).forEach {
                val c = Offset(pxX(it.x), pxY(it.y))
                drawCircle(Color.White, hr * 0.8f, c)
                drawCircle(AccentColor, hr * 0.8f, c, style = Stroke(2f * density))
            }
        }
        for (m in tableMarkers()) {
            val r = m.r
            val col = if (m.minus) MinusColor else AccentColor
            // tail: a thin triangle from the bubble to the border / row / column it belongs to
            val dx = m.tail.x - m.center.x; val dy = m.tail.y - m.center.y
            val len = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
            val ux = dx / len; val uy = dy / len
            val px = -uy * r * 0.55f; val py = ux * r * 0.55f
            val base = Offset(m.center.x + ux * r * 0.6f, m.center.y + uy * r * 0.6f)
            val tail = androidx.compose.ui.graphics.Path().apply {
                moveTo(base.x + px, base.y + py); lineTo(m.tail.x, m.tail.y); lineTo(base.x - px, base.y - py); close()
            }
            drawPath(tail, col)
            drawCircle(Color.White, r + 1.2f * density, m.center)
            drawCircle(col, r, m.center)
            val a = r * 0.5f; val sw = (r * 0.22f).coerceAtLeast(1.4f * density)
            drawLine(Color.White, Offset(m.center.x - a, m.center.y), Offset(m.center.x + a, m.center.y), sw, StrokeCap.Round)
            if (!m.minus) drawLine(Color.White, Offset(m.center.x, m.center.y - a), Offset(m.center.x, m.center.y + a), sw, StrokeCap.Round)
        }
        preview?.let { r ->
            drawRect(
                AccentColor.copy(alpha = 0.25f),
                Offset(pxX(r.left), pxY(r.top)),
                Size(r.width() * zb, r.height() * zb),
            )
            drawRect(AccentColor, Offset(pxX(r.left), pxY(r.top)), Size(r.width() * zb, r.height() * zb), style = Stroke(2f * density))
        }
        val c = cursor
        if (c != null && cursorVisible && blinkOn && selection.isEmpty() && (graphic == null || inTextBox)) {
            drawRect(
                CursorColor,
                Offset(pxX(c.left).roundToInt().toFloat() + 1f, pxY(c.top)),
                Size(max(2f, 0.75f * density), c.height() * zb),
            )
        }
    }

    private fun DrawScope.drawHandle(tip: Offset, r: Float) {
        val center = Offset(tip.x, tip.y + r + 2f * density)
        drawCircle(AccentColor, r, center)
        drawRect(AccentColor, Offset(tip.x - r, tip.y), Size(r, r + 2f * density))
    }

    private fun request(k: TileKey) {
        if (!inflight.add(k)) return
        val b = base
        val g0 = gen
        scope.launch {
            val bmp = withContext(Engine.dispatcher) {
                if (k !in wanted) return@withContext null
                val p = layout.pages.getOrNull(k.page) ?: return@withContext null
                val rs = b * (k.level / BASE_LEVEL.toFloat())
                val tileTw = tileTwips(rs)
                val xTw = k.tx * tileTw
                val yTw = k.ty * tileTw
                val wTw = min(tileTw, p.w - xTw)
                val hTw = min(tileTw, p.h - yTw)
                if (wTw <= 0 || hTw <= 0) return@withContext null
                doc.paintTile(
                    p, xTw, yTw, wTw, hTw,
                    (wTw * rs).roundToInt().coerceAtLeast(1),
                    (hTw * rs).roundToInt().coerceAtLeast(1),
                )
            }
            inflight.remove(k)
            if (bmp != null) cache.put(k, Tile(bmp, stale = g0 != gen))
            tick++ // redraw: shows the tile or re-requests what is still visible
        }
    }
}

@Composable
fun DocumentView(state: DocumentViewState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.editing, state.blinkReset) {
        state.blinkOn = true
        while (state.editing) {
            delay(530)
            state.blinkOn = !state.blinkOn
        }
        state.blinkOn = true
    }
    Canvas(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged {
                state.onViewSize(it)
                state.offset = state.clamp(state.offset)
                if (state.editing) state.scrollToCursor()
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { if (state.editing) { if (state.hitsPage(it)) state.click(it) else state.tapOutside() } },
                    onDoubleTap = {
                        if (!state.editing) state.toggleZoom(it)
                        else if (state.hitsPage(it)) state.doubleTap(it) else state.tapOutside()
                    },
                    onLongPress = { if (state.editing && state.hitsPage(it)) state.longPress(it) },
                )
            }
            .pointerInput(Unit) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    state.stopFling()
                    val dragging = state.beginDrag(down.position)
                    val tracker = VelocityTracker()
                    var panning = false
                    var accPan = Offset.Zero
                    do {
                        val ev = awaitPointerEvent()
                        if (dragging) {
                            val ch = ev.changes.first()
                            state.dragTo(ch.position)
                            // a body drag that has not passed touch slop yet is still a tap candidate
                            if (state.dragConsumesEvents()) ev.changes.forEach { it.consume() }
                        } else {
                            val zoomChange = ev.calculateZoom()
                            val pan = ev.calculatePan()
                            val c = ev.calculateCentroid()
                            accPan += pan
                            val multi = ev.changes.count { it.pressed } > 1
                            // A real finger always jitters a little: only take over (and consume) past touch slop,
                            // otherwise taps and double-taps never reach the tap detector.
                            if (!panning && (multi || accPan.getDistance() > slop)) {
                                panning = true
                                if (c.isSpecified && !multi) state.transform(c, accPan - pan, 1f)
                            }
                            if (panning) {
                                if (c.isSpecified && (zoomChange != 1f || pan != Offset.Zero)) state.transform(c, pan, zoomChange)
                                ev.changes.forEach {
                                    tracker.addPosition(it.uptimeMillis, it.position)
                                    if (it.positionChanged()) it.consume()
                                }
                            } else {
                                ev.changes.forEach { tracker.addPosition(it.uptimeMillis, it.position) }
                            }
                        }
                    } while (ev.changes.any { it.pressed })
                    if (dragging) {
                        state.endDrag()
                    } else if (panning) {
                        val v = tracker.calculateVelocity()
                        if (abs(v.x) + abs(v.y) > 0f) state.flingBy(scope, v.x, v.y)
                    }
                }
            },
    ) { state.draw(this) }
}
