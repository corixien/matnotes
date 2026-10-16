package com.matnotes.app

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val TILE_PX = 512
private const val MAX_ZOOM = 6f
private val PageGray = Color(0xFFE6E6E6)

private data class TileKey(val page: Int, val level: Int, val tx: Int, val ty: Int)

/** Scroll/zoom state plus an async tile cache for one document. */
class DocumentViewState(private val doc: LoDocument, private val scope: CoroutineScope) {
    var zoom by mutableStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
    var viewSize by mutableStateOf(IntSize.Zero)
    var tick by mutableIntStateOf(0)
        private set

    private val cache = object : LruCache<TileKey, Bitmap>(96 * 1024 * 1024) {
        override fun sizeOf(key: TileKey, value: Bitmap) = value.byteCount
    }
    private val inflight = HashSet<TileKey>()
    @Volatile private var wanted: Set<TileKey> = emptySet()
    private var fling: Job? = null

    /** Pixels per twip that make the document fit the view width at zoom 1. */
    val base: Float get() = if (viewSize.width == 0) 0f else viewSize.width.toFloat() / doc.width

    val contentWidth get() = doc.width * base * zoom
    val contentHeight get() = doc.height * base * zoom

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
        val o = Offset(
            centroid.x - (centroid.x - offset.x) * k + pan.x,
            centroid.y - (centroid.y - offset.y) * k + pan.y,
        )
        zoom = nz
        offset = clamp(o)
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

    fun doubleTap(at: Offset) {
        val target = if (zoom > 1.3f) 1f else 2.5f
        transform(at, Offset.Zero, target / zoom)
    }

    /** 1-based page index nearest to the view center. */
    fun currentPage(): Int {
        val b = base * zoom
        if (b == 0f) return 1
        val centerY = (viewSize.height / 2f - offset.y) / b
        val idx = doc.pages.indexOfFirst { centerY < it.y + it.h }
        return (if (idx < 0) doc.pages.lastIndex else idx) + 1
    }

    private fun level(): Int = ceil(zoom * 2f - 0.001f).toInt().coerceIn(2, (MAX_ZOOM * 2).toInt())

    fun draw(scope: androidx.compose.ui.graphics.drawscope.DrawScope) = with(scope) {
        tick // subscribe to tile updates
        drawRect(PageGray)
        val b = base
        if (b == 0f) return
        val zb = b * zoom
        val level = level()
        val renderScale = b * (level / 2f)
        val want = HashSet<TileKey>()
        doc.pages.forEachIndexed { pi, p ->
            val px = offset.x + p.x * zb
            val py = offset.y + p.y * zb
            val pw = p.w * zb
            val ph = p.h * zb
            if (py > size.height || py + ph < 0f || px > size.width || px + pw < 0f) return@forEachIndexed
            drawRect(Color.White, Offset(px, py), androidx.compose.ui.geometry.Size(pw, ph))

            // overview (fit-width) tile as placeholder and for zoom 1
            val ok = TileKey(pi, 0, 0, 0)
            want += ok
            val ob = cache.get(ok)
            if (ob != null) drawBitmap(ob, px, py, pw, ph) else request(ok)

            if (level > 2) {
                val tileTw = TILE_PX / renderScale
                val cols = ceil(p.w / tileTw).toInt()
                val rows = ceil(p.h / tileTw).toInt()
                val x0 = max(0, ((0f - px) / (tileTw * zb)).toInt())
                val x1 = min(cols - 1, ((size.width - px) / (tileTw * zb)).toInt())
                val y0 = max(0, ((0f - py) / (tileTw * zb)).toInt())
                val y1 = min(rows - 1, ((size.height - py) / (tileTw * zb)).toInt())
                for (ty in y0..y1) for (tx in x0..x1) {
                    val k = TileKey(pi, level, tx, ty)
                    want += k
                    val twX = tx * tileTw
                    val twY = ty * tileTw
                    val twW = min(tileTw, p.w - twX)
                    val twH = min(tileTw, p.h - twY)
                    val bmp = cache.get(k)
                    if (bmp != null) drawBitmap(bmp, px + twX * zb, py + twY * zb, twW * zb, twH * zb)
                    else request(k)
                }
            }
        }
        wanted = want
    }

    private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBitmap(
        bmp: Bitmap, x: Float, y: Float, w: Float, h: Float,
    ) {
        val iw = w.roundToInt().coerceAtLeast(1)
        val ih = h.roundToInt().coerceAtLeast(1)
        drawImage(
            bmp.asImageBitmap(),
            dstOffset = IntOffset(x.roundToInt(), y.roundToInt()),
            dstSize = IntSize(iw, ih),
        )
    }

    private fun request(k: TileKey) {
        if (!inflight.add(k)) return
        val b = base
        scope.launch {
            val bmp = withContext(Engine.dispatcher) {
                if (k !in wanted) return@withContext null
                val p = doc.pages[k.page]
                if (k.level == 0) {
                    val wPx = (p.w * b).roundToInt().coerceIn(1, LoDocument.MAX_TILE * 2)
                    val hPx = (p.h * b).roundToInt().coerceIn(1, LoDocument.MAX_TILE * 4)
                    doc.paintTile(p, 0, 0, p.w, p.h, wPx, hPx)
                } else {
                    val rs = b * (k.level / 2f)
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
            }
            inflight.remove(k)
            if (bmp != null) {
                cache.put(k, bmp)
                tick++
            } else {
                tick++ // wanted set may have changed; redraw re-requests what is still visible
            }
        }
    }
}

@Composable
fun DocumentView(state: DocumentViewState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    Canvas(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged {
                state.viewSize = it
                state.offset = state.clamp(state.offset)
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { state.doubleTap(it) })
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    state.stopFling()
                    val tracker = VelocityTracker()
                    do {
                        val ev = awaitPointerEvent()
                        val zoomChange = ev.calculateZoom()
                        val pan = ev.calculatePan()
                        val c = ev.calculateCentroid()
                        if (c.isSpecified && (zoomChange != 1f || pan != Offset.Zero)) {
                            state.transform(c, pan, zoomChange)
                        }
                        ev.changes.forEach {
                            tracker.addPosition(it.uptimeMillis, it.position)
                            if (it.positionChanged()) it.consume()
                        }
                    } while (ev.changes.any { it.pressed })
                    val v = tracker.calculateVelocity()
                    if (state.zoom >= 1f) state.flingBy(scope, v.x, v.y)
                }
            },
    ) { state.draw(this) }
}
