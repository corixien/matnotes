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
private const val MAX_ZOOM = 6f
private const val BASE_LEVEL = 2 // level = zoom * 2; level 2 == fit-width scale
private val PageGray = Color(0xFFE6E6E6)
private val SelectionColor = Color(0x553D7BF0)
private val AccentColor = Color(0xFF2B579A)
private val CursorColor = Color(0xFF1B1B1B)

private data class TileKey(val page: Int, val level: Int, val tx: Int, val ty: Int)

private class Tile(val bmp: Bitmap, val version: Int)

private enum class Drag { SelStart, SelEnd, GraphicBody, GraphicHandle }

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
        private set
    var cursorVisible by mutableStateOf(true)
        private set
    var blinkOn by mutableStateOf(true)
    var selection by mutableStateOf<List<RectF>>(emptyList())
        private set
    var selStart by mutableStateOf<RectF?>(null)
        private set
    var selEnd by mutableStateOf<RectF?>(null)
        private set
    var graphic by mutableStateOf<RectF?>(null)
        private set
    var format by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var wantKeyboard by mutableStateOf(false)
    var finding by mutableStateOf(false)

    private val cache = object : LruCache<TileKey, Tile>(96 * 1024 * 1024) {
        override fun sizeOf(key: TileKey, value: Tile) = value.bmp.byteCount
    }
    private val main = Handler(Looper.getMainLooper())

    /** Bumped after every edit: tiles rendered with an older version are drawn until re-rendered. */
    @Volatile private var editVersion = 0
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
    private val contentWidth get() = layout.width * scale
    private val contentHeight get() = layout.height * scale

    fun clamp(o: Offset): Offset {
        val x = if (contentWidth <= viewSize.width) (viewSize.width - contentWidth) / 2f
        else o.x.coerceIn(viewSize.width - contentWidth, 0f)
        val y = if (contentHeight <= viewSize.height) 0f
        else o.y.coerceIn(viewSize.height - contentHeight, 0f)
        return Offset(x, y)
    }

    fun transform(centroid: Offset, pan: Offset, zoomChange: Float) {
        val nz = (zoom * zoomChange).coerceIn(1f, MAX_ZOOM)
        val k = nz / zoom
        zoom = nz
        offset = clamp(
            Offset(
                centroid.x - (centroid.x - offset.x) * k + pan.x,
                centroid.y - (centroid.y - offset.y) * k + pan.y,
            ),
        )
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
        doc.post {
            block()
            refreshLayout()
            main.post { this@DocumentViewState.layout = doc.layout; editVersion++; tick++ }
        }
    }

    fun click(at: Offset, count: Int = 1) {
        val x = twX(at.x).roundToInt()
        val y = twY(at.y).roundToInt()
        val s = scale
        followCursor = true
        edit {
            mouse(Document.MOUSE_EVENT_BUTTON_DOWN, x, y, count, s)
            mouse(Document.MOUSE_EVENT_BUTTON_UP, x, y, count, s)
        }
        wantKeyboard = true
    }

    fun typeText(t: String) { markDirty(); followCursor = true; edit { type(t) } }
    fun pressKey(code: Int) { if (code == Keys.BACKSPACE || code == Keys.DELETE || code == Keys.RETURN) markDirty(); followCursor = true; edit { pressKey(code) } }
    fun uno(cmd: String, args: String = "") { if (cmd != ".uno:Copy") markDirty(); edit { uno(cmd, args) } }
    fun markDirty() { dirty = true }

    /** Character formatting: like Word, applies to the word under the cursor when nothing is selected. */
    fun charFormat(cmd: String, args: String = "") {
        markDirty()
        if (selection.isEmpty() && graphic == null) edit { uno(".uno:SelectWord"); uno(cmd, args) } else edit { uno(cmd, args) }
    }

    fun zoomBy(f: Float) = transform(Offset(viewSize.width / 2f, viewSize.height / 2f), Offset.Zero, f)
    fun fitWidth() = transform(Offset(viewSize.width / 2f, viewSize.height / 2f), Offset.Zero, 1f / zoom)

    // ---- drag handling for selection handles / graphics ----
    private var drag: Drag? = null
    private var dragStartTw = Offset.Zero
    private var dragRectH = 0f
    private var dragHandleTw = Offset.Zero
    private var dragPos = Offset.Zero
    private var dragHandleIdx = -1
    var preview by mutableStateOf<RectF?>(null)
        private set

    private fun dist(a: Offset, b: Offset) = (a - b).getDistance()

    fun beginDrag(p: Offset): Boolean {
        if (!editing) return false
        val hit = 28f * density
        selStart?.let {
            val c = Offset(pxX(it.left), pxY(it.bottom) + 10 * density)
            if (dist(p, c) < hit && selection.isNotEmpty()) { startDrag(Drag.SelStart, p, Offset(it.left, it.bottom), it.height()); return true }
        }
        selEnd?.let {
            val c = Offset(pxX(it.right), pxY(it.bottom) + 10 * density)
            if (dist(p, c) < hit && selection.isNotEmpty()) { startDrag(Drag.SelEnd, p, Offset(it.right, it.bottom), it.height()); return true }
        }
        graphic?.let { g ->
            val handles = graphicHandles(g)
            val idx = handles.indices.minByOrNull { dist(p, Offset(pxX(handles[it].x), pxY(handles[it].y))) } ?: -1
            if (idx >= 0 && dist(p, Offset(pxX(handles[idx].x), pxY(handles[idx].y))) < hit) {
                val h = handles[idx]
                startDrag(Drag.GraphicHandle, p, h, 0f)
                dragHandleIdx = idx
                markDirty()
                edit { setGraphicSelection(Document.SET_GRAPHIC_SELECTION_START, h.x.roundToInt(), h.y.roundToInt()) }
                return true
            }
            if (g.contains(twX(p.x), twY(p.y))) {
                val t = Offset(twX(p.x), twY(p.y))
                startDrag(Drag.GraphicBody, p, t, 0f)
                markDirty()
                edit { setGraphicSelection(Document.SET_GRAPHIC_SELECTION_START, t.x.roundToInt(), t.y.roundToInt()) }
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
            Drag.GraphicBody -> {
                val g = graphic ?: return
                val d = cur - dragStartTw
                preview = RectF(g.left + d.x, g.top + d.y, g.right + d.x, g.bottom + d.y)
            }
            Drag.GraphicHandle -> {
                val g = graphic ?: return
                val d = cur - dragStartTw
                val r = RectF(g)
                val x = dragHandleTw.x + d.x; val y = dragHandleTw.y + d.y
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
        when (kind) {
            Drag.GraphicBody, Drag.GraphicHandle -> {
                val pt = (if (kind == Drag.GraphicBody) dragStartTw else dragHandleTw) + (cur - dragStartTw)
                preview = null
                edit { setGraphicSelection(Document.SET_GRAPHIC_SELECTION_END, pt.x.roundToInt(), pt.y.roundToInt()) }
            }
            else -> Unit
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
                if (followCursor) scrollToCursor()
            }
            Document.CALLBACK_CURSOR_VISIBLE -> cursorVisible = payload.trim() == "true"
            Document.CALLBACK_TEXT_SELECTION -> {
                selection = parseRects(payload)
                if (finding) selection.firstOrNull()?.let { scrollToRect(it) }
            }
            Document.CALLBACK_TEXT_SELECTION_START -> selStart = parseRects(payload).firstOrNull()
            Document.CALLBACK_TEXT_SELECTION_END -> selEnd = parseRects(payload).firstOrNull()
            Document.CALLBACK_GRAPHIC_SELECTION -> graphic = parseRects(payload).firstOrNull()
            Document.CALLBACK_STATE_CHANGED -> {
                val i = payload.indexOf('=')
                if (i > 0) format = format + (payload.substring(0, i) to payload.substring(i + 1))
            }
        }
        tick++
    }

    private fun parseRects(s: String): List<RectF> {
        if (s.isBlank() || s.startsWith("EMPTY") || s.startsWith("{")) return emptyList()
        return s.split(';').mapNotNull { part ->
            val n = part.split(',').mapNotNull { it.trim().toFloatOrNull() }
            if (n.size >= 4 && n[2] >= 0f && n[3] > 0f) RectF(n[0], n[1], n[0] + n[2], n[1] + n[3]) else null
        }
    }

    private fun invalidate(r: RectF?) {
        if (r == null) cache.evictAll()
        editVersion++
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

    // ---- drawing ----
    private fun level(): Int = ceil(zoom * BASE_LEVEL - 0.001f).toInt().coerceIn(BASE_LEVEL, (MAX_ZOOM * BASE_LEVEL).toInt())

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
        val tileTw = TILE_PX / rs
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
            if (request && (t == null || t.version != editVersion)) request(k)
        }
    }

    private fun DrawScope.drawBitmap(bmp: Bitmap, x: Float, y: Float, w: Float, h: Float) {
        // overlap by one pixel to avoid hairline seams between tiles
        drawImage(
            bmp.asImageBitmap(),
            dstOffset = IntOffset(x.roundToInt(), y.roundToInt()),
            dstSize = IntSize(w.roundToInt().coerceAtLeast(1) + 1, h.roundToInt().coerceAtLeast(1) + 1),
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
        preview?.let { r ->
            drawRect(
                AccentColor.copy(alpha = 0.25f),
                Offset(pxX(r.left), pxY(r.top)),
                Size(r.width() * zb, r.height() * zb),
            )
            drawRect(AccentColor, Offset(pxX(r.left), pxY(r.top)), Size(r.width() * zb, r.height() * zb), style = Stroke(2f * density))
        }
        val c = cursor
        if (c != null && cursorVisible && blinkOn && selection.isEmpty() && graphic == null) {
            drawRect(
                CursorColor,
                Offset(pxX(c.left) - 0.5f * density, pxY(c.top)),
                Size(1f * density, c.height() * zb),
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
        val v0 = editVersion
        scope.launch {
            val bmp = withContext(Engine.dispatcher) {
                if (k !in wanted) return@withContext null
                val p = layout.pages.getOrNull(k.page) ?: return@withContext null
                val rs = b * (k.level / BASE_LEVEL.toFloat())
                val tileTw = (TILE_PX / rs).toInt().coerceAtLeast(1)
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
            if (bmp != null) cache.put(k, Tile(bmp, v0))
            tick++ // redraw: shows the tile or re-requests what is still visible
        }
    }
}

@Composable
fun DocumentView(state: DocumentViewState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.editing) {
        while (state.editing) {
            state.blinkOn = !state.blinkOn
            delay(530)
        }
        state.blinkOn = true
    }
    Canvas(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged {
                state.viewSize = it
                state.offset = state.clamp(state.offset)
                if (state.editing) state.scrollToCursor()
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { if (state.editing) state.click(it) },
                    onDoubleTap = { if (state.editing) state.click(it, 2) else state.toggleZoom(it) },
                    onLongPress = { if (state.editing) state.click(it, 2) },
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
                            ev.changes.forEach { it.consume() }
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
