package com.matnotes.app

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Small drawn previews of the insertable shapes (Word-like gallery). Coordinates are fractions of the square. */
private fun poly(w: Float, h: Float, vararg p: Float): Path = Path().apply {
    moveTo(p[0] * w, p[1] * h)
    for (i in 2 until p.size step 2) lineTo(p[i] * w, p[i + 1] * h)
    close()
}

private fun star(w: Float, h: Float, n: Int, inner: Float): Path = Path().apply {
    for (i in 0 until n * 2) {
        val r = if (i % 2 == 0) 0.47f else 0.47f * inner
        val a = -PI / 2 + i * PI / n
        val x = (0.5f + r * cos(a).toFloat()) * w
        val y = (0.52f + r * sin(a).toFloat()) * h
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

fun DrawScope.drawShapeThumb(cmd: String, fill: Color, line: Color) {
    val w = size.width; val h = size.height
    val st = Stroke(1.4f * density, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun shape(p: Path) { drawPath(p, fill); drawPath(p, line, style = st) }
    fun oval(x: Float, y: Float, rw: Float, rh: Float) = Path().apply {
        addOval(androidx.compose.ui.geometry.Rect(x * w, y * h, (x + rw) * w, (y + rh) * h))
    }
    val rightArrow = floatArrayOf(0.04f, 0.34f, 0.5f, 0.34f, 0.5f, 0.14f, 0.96f, 0.5f, 0.5f, 0.86f, 0.5f, 0.66f, 0.04f, 0.66f)
    fun arrow(map: (Float, Float) -> Pair<Float, Float>) {
        val q = FloatArray(rightArrow.size)
        for (i in rightArrow.indices step 2) { val (x, y) = map(rightArrow[i], rightArrow[i + 1]); q[i] = x; q[i + 1] = y }
        shape(poly(w, h, *q))
    }
    when (cmd.substringAfterLast('.')) {
        "rectangle" -> shape(poly(w, h, 0.06f, 0.2f, 0.94f, 0.2f, 0.94f, 0.8f, 0.06f, 0.8f))
        "round-rectangle" -> shape(Path().apply {
            addRoundRect(androidx.compose.ui.geometry.RoundRect(0.06f * w, 0.2f * h, 0.94f * w, 0.8f * h, CornerRadius(0.16f * w)))
        })
        "ellipse" -> shape(oval(0.06f, 0.16f, 0.88f, 0.68f))
        "isosceles-triangle" -> shape(poly(w, h, 0.5f, 0.1f, 0.94f, 0.88f, 0.06f, 0.88f))
        "diamond" -> shape(poly(w, h, 0.5f, 0.06f, 0.94f, 0.5f, 0.5f, 0.94f, 0.06f, 0.5f))
        "hexagon" -> shape(poly(w, h, 0.26f, 0.14f, 0.74f, 0.14f, 0.96f, 0.5f, 0.74f, 0.86f, 0.26f, 0.86f, 0.04f, 0.5f))
        "cross" -> shape(poly(w, h, 0.36f, 0.06f, 0.64f, 0.06f, 0.64f, 0.36f, 0.94f, 0.36f, 0.94f, 0.64f, 0.64f, 0.64f,
            0.64f, 0.94f, 0.36f, 0.94f, 0.36f, 0.64f, 0.06f, 0.64f, 0.06f, 0.36f, 0.36f, 0.36f))
        "right-arrow" -> arrow { x, y -> x to y }
        "left-arrow" -> arrow { x, y -> (1 - x) to y }
        "up-arrow" -> arrow { x, y -> y to (1 - x) }
        "down-arrow" -> arrow { x, y -> y to x }
        "left-right-arrow" -> shape(poly(w, h, 0.04f, 0.5f, 0.26f, 0.2f, 0.26f, 0.38f, 0.74f, 0.38f, 0.74f, 0.2f, 0.96f, 0.5f,
            0.74f, 0.8f, 0.74f, 0.62f, 0.26f, 0.62f, 0.26f, 0.8f))
        "star4" -> shape(star(w, h, 4, 0.4f))
        "star5" -> shape(star(w, h, 5, 0.42f))
        "star6" -> shape(star(w, h, 6, 0.55f))
        "heart" -> shape(Path().apply {
            moveTo(0.5f * w, 0.88f * h)
            cubicTo(0.0f * w, 0.55f * h, 0.1f * w, 0.1f * h, 0.3f * w, 0.14f * h)
            cubicTo(0.42f * w, 0.16f * h, 0.5f * w, 0.28f * h, 0.5f * w, 0.32f * h)
            cubicTo(0.5f * w, 0.28f * h, 0.58f * w, 0.16f * h, 0.7f * w, 0.14f * h)
            cubicTo(0.9f * w, 0.1f * h, 1.0f * w, 0.55f * h, 0.5f * w, 0.88f * h)
            close()
        })
        "sun" -> {
            for (i in 0 until 8) {
                val a = i * PI / 4
                drawLine(line, Offset((0.5f + 0.32f * cos(a).toFloat()) * w, (0.5f + 0.32f * sin(a).toFloat()) * h),
                    Offset((0.5f + 0.46f * cos(a).toFloat()) * w, (0.5f + 0.46f * sin(a).toFloat()) * h), strokeWidth = st.width, cap = StrokeCap.Round)
            }
            shape(oval(0.24f, 0.24f, 0.52f, 0.52f))
        }
        "smiley" -> {
            shape(oval(0.06f, 0.06f, 0.88f, 0.88f))
            drawCircle(line, 0.05f * w, Offset(0.35f * w, 0.4f * h))
            drawCircle(line, 0.05f * w, Offset(0.65f * w, 0.4f * h))
            drawArc(line, 20f, 140f, false, Offset(0.28f * w, 0.3f * h), Size(0.44f * w, 0.44f * h), style = st)
        }
        "cloud" -> {
            val c = listOf(Triple(0.28f, 0.62f, 0.2f), Triple(0.42f, 0.4f, 0.22f), Triple(0.64f, 0.38f, 0.2f), Triple(0.76f, 0.6f, 0.18f), Triple(0.52f, 0.66f, 0.22f))
            var u = Path()
            c.forEach { (x, y, r) -> u = Path.combine(androidx.compose.ui.graphics.PathOperation.Union, u, oval(x - r, y - r * w / h, r * 2, r * 2 * w / h)) }
            shape(u)
        }
        "flowchart-process" -> shape(poly(w, h, 0.06f, 0.2f, 0.94f, 0.2f, 0.94f, 0.8f, 0.06f, 0.8f))
        "flowchart-decision" -> shape(poly(w, h, 0.5f, 0.1f, 0.94f, 0.5f, 0.5f, 0.9f, 0.06f, 0.5f))
        "flowchart-terminator" -> shape(Path().apply {
            addRoundRect(androidx.compose.ui.geometry.RoundRect(0.06f * w, 0.28f * h, 0.94f * w, 0.72f * h, CornerRadius(0.22f * w)))
        })
        "flowchart-data" -> shape(poly(w, h, 0.2f, 0.22f, 0.96f, 0.22f, 0.8f, 0.78f, 0.04f, 0.78f))
        "flowchart-document" -> shape(Path().apply {
            moveTo(0.06f * w, 0.18f * h); lineTo(0.94f * w, 0.18f * h); lineTo(0.94f * w, 0.74f * h)
            cubicTo(0.7f * w, 0.62f * h, 0.7f * w, 0.92f * h, 0.5f * w, 0.8f * h)
            cubicTo(0.3f * w, 0.7f * h, 0.3f * w, 0.9f * h, 0.06f * w, 0.78f * h)
            close()
        })
        "round-rectangular-callout" -> shape(Path().apply {
            addRoundRect(androidx.compose.ui.geometry.RoundRect(0.06f * w, 0.1f * h, 0.94f * w, 0.66f * h, CornerRadius(0.1f * w)))
            moveTo(0.2f * w, 0.62f * h); lineTo(0.14f * w, 0.92f * h); lineTo(0.42f * w, 0.64f * h); close()
        })
        "round-callout" -> shape(Path().apply {
            addOval(androidx.compose.ui.geometry.Rect(0.06f * w, 0.08f * h, 0.94f * w, 0.7f * h))
            moveTo(0.24f * w, 0.6f * h); lineTo(0.14f * w, 0.92f * h); lineTo(0.44f * w, 0.68f * h); close()
        })
        else -> shape(poly(w, h, 0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f))
    }
}
