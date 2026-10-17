package com.matnotes.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private fun arg(name: String, type: String, value: String) = "\"$name\":{\"type\":\"$type\",\"value\":\"$value\"}"
private fun args(vararg a: String) = a.joinToString(",", "{", "}")

private val Palette = listOf(
    0x000000, 0xC00000, 0xFF0000, 0xFFC000, 0xFFFF00, 0x92D050, 0x00B050,
    0x00B0F0, 0x0070C0, 0x002060, 0x7030A0, 0x7F7F7F, 0xFFFFFF,
)
private val Fonts = listOf("Calibri", "Cambria", "Arial", "Times New Roman", "Courier New")
private val Styles = listOf(
    R.string.style_text to "Default Paragraph Style",
    R.string.style_title to "Title",
    R.string.style_h1 to "Heading 1",
    R.string.style_h2 to "Heading 2",
    R.string.style_h3 to "Heading 3",
)

@Composable
fun Ribbon(state: DocumentViewState, onPickImage: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    val hasGraphic = state.graphic != null
    LaunchedEffect(hasGraphic) { if (hasGraphic) tab = 2 else if (tab == 2) tab = 0 }

    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth()) {
            HorizontalDivider()
            Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TabLabel(stringResource(R.string.tab_start), tab == 0) { tab = 0 }
                TabLabel(stringResource(R.string.tab_insert), tab == 1) { tab = 1 }
                if (hasGraphic) TabLabel(stringResource(R.string.tab_picture), tab == 2) { tab = 2 }
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (tab) {
                    0 -> StartTab(state)
                    1 -> InsertTab(state, onPickImage)
                    else -> PictureTab(state)
                }
            }
        }
    }
}

@Composable
private fun TabLabel(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        style = MaterialTheme.typography.labelLarge,
    )
}

@Composable
private fun RibbonButton(active: Boolean = false, wide: Boolean = false, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .height(44.dp)
            .then(if (wide) Modifier.padding(0.dp) else Modifier.width(44.dp))
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .then(if (wide) Modifier.padding(horizontal = 12.dp) else Modifier),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun Sep() {
    Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(24.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

@Composable
private fun StartTab(state: DocumentViewState) {
    val f = state.format
    fun on(cmd: String) = f[cmd] == "true"
    val size = f[".uno:FontHeight"]?.toFloatOrNull()
    val fontName = f[".uno:CharFontName"] ?: "Calibri"

    RibbonButton(onClick = { state.uno(".uno:Undo") }) { Text("↶", fontSize = 22.sp) }
    RibbonButton(onClick = { state.uno(".uno:Redo") }) { Text("↷", fontSize = 22.sp) }
    Sep()
    FontPicker(fontName) { state.uno(".uno:CharFontName", args(arg("CharFontName.FamilyName", "string", it))) }
    RibbonButton(onClick = { size?.let { state.uno(".uno:FontHeight", args(arg("FontHeight.Height", "float", "${(it - 1).coerceAtLeast(1f)}"))) } }) { Text("A−", fontSize = 16.sp) }
    Text(
        size?.let { if (it % 1f == 0f) it.toInt().toString() else it.toString() } ?: "–",
        Modifier.width(28.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        fontSize = 16.sp,
    )
    RibbonButton(onClick = { size?.let { state.uno(".uno:FontHeight", args(arg("FontHeight.Height", "float", "${it + 1}"))) } }) { Text("A+", fontSize = 16.sp) }
    Sep()
    RibbonButton(on(".uno:Bold"), onClick = { state.uno(".uno:Bold") }) { Text("B", fontWeight = FontWeight.Bold, fontSize = 18.sp) }
    RibbonButton(on(".uno:Italic"), onClick = { state.uno(".uno:Italic") }) { Text("I", fontStyle = FontStyle.Italic, fontSize = 18.sp) }
    RibbonButton(on(".uno:Underline"), onClick = { state.uno(".uno:Underline") }) { Text("U", textDecoration = TextDecoration.Underline, fontSize = 18.sp) }
    RibbonButton(on(".uno:Strikeout"), onClick = { state.uno(".uno:Strikeout") }) { Text("S", textDecoration = TextDecoration.LineThrough, fontSize = 18.sp) }
    ColorPicker("A", Color.Black) { state.uno(".uno:Color", args(arg("Color", "long", "$it"))) }
    ColorPicker("▌", Color(0xFFFFEB3B), allowNone = true) {
        state.uno(".uno:BackColor", args(arg("BackColor", "long", "$it")))
    }
    Sep()
    RibbonButton(on(".uno:LeftPara"), onClick = { state.uno(".uno:LeftPara") }) { AlignIcon(0) }
    RibbonButton(on(".uno:CenterPara"), onClick = { state.uno(".uno:CenterPara") }) { AlignIcon(1) }
    RibbonButton(on(".uno:RightPara"), onClick = { state.uno(".uno:RightPara") }) { AlignIcon(2) }
    RibbonButton(on(".uno:JustifyPara"), onClick = { state.uno(".uno:JustifyPara") }) { AlignIcon(3) }
    Sep()
    RibbonButton(on(".uno:DefaultBullet"), onClick = { state.uno(".uno:DefaultBullet") }) { Text("•≡", fontSize = 18.sp) }
    RibbonButton(on(".uno:DefaultNumbering"), onClick = { state.uno(".uno:DefaultNumbering") }) { Text("1.", fontSize = 18.sp) }
    RibbonButton(onClick = { state.uno(".uno:DecrementIndent") }) { Text("⇤", fontSize = 20.sp) }
    RibbonButton(onClick = { state.uno(".uno:IncrementIndent") }) { Text("⇥", fontSize = 20.sp) }
    Sep()
    StylePicker { state.uno(".uno:StyleApply", args(arg("Style", "string", it), arg("FamilyName", "string", "ParagraphStyles"))) }
}

@Composable
private fun InsertTab(state: DocumentViewState, onPickImage: () -> Unit) {
    RibbonButton(wide = true, onClick = onPickImage) { Text(stringResource(R.string.insert_picture)) }
    RibbonButton(wide = true, onClick = {
        state.uno(".uno:InsertTable", args(arg("Columns", "long", "3"), arg("Rows", "long", "3")))
    }) { Text(stringResource(R.string.insert_table)) }
    RibbonButton(wide = true, onClick = { state.uno(".uno:InsertPagebreak") }) { Text(stringResource(R.string.insert_pagebreak)) }
    Sep()
    RibbonButton(wide = true, onClick = { state.uno(".uno:InsertRowsAfter") }) { Text(stringResource(R.string.table_row_add)) }
    RibbonButton(wide = true, onClick = { state.uno(".uno:InsertColumnsAfter") }) { Text(stringResource(R.string.table_col_add)) }
    RibbonButton(wide = true, onClick = { state.uno(".uno:DeleteRows") }) { Text(stringResource(R.string.table_row_del)) }
    RibbonButton(wide = true, onClick = { state.uno(".uno:DeleteColumns") }) { Text(stringResource(R.string.table_col_del)) }
}

@Composable
private fun PictureTab(state: DocumentViewState) {
    val items = listOf(
        R.string.wrap_inline to ".uno:SetAnchorToCharAsChar",
        R.string.wrap_square to ".uno:WrapOn",
        R.string.wrap_tight to ".uno:WrapContour",
        R.string.wrap_topbottom to ".uno:WrapOff",
        R.string.wrap_front to ".uno:WrapThrough",
        R.string.wrap_behind to ".uno:SetObjectToBackground",
    )
    items.forEach { (label, cmd) ->
        RibbonButton(wide = true, onClick = { state.uno(cmd) }) { Text(stringResource(label)) }
    }
    Sep()
    RibbonButton(wide = true, onClick = { state.uno(".uno:Delete") }) { Text(stringResource(R.string.delete)) }
}

@Composable
private fun FontPicker(current: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        RibbonButton(wide = true, onClick = { open = true }) {
            Text(current.take(14) + " ▾", fontSize = 15.sp)
        }
        DropdownMenu(open, { open = false }) {
            Fonts.forEach { DropdownMenuItem({ Text(it) }, { open = false; onPick(it) }) }
        }
    }
}

@Composable
private fun StylePicker(onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        RibbonButton(wide = true, onClick = { open = true }) { Text(stringResource(R.string.styles) + " ▾", fontSize = 15.sp) }
        DropdownMenu(open, { open = false }) {
            Styles.forEach { (label, name) -> DropdownMenuItem({ Text(stringResource(label)) }, { open = false; onPick(name) }) }
        }
    }
}

@Composable
private fun ColorPicker(glyph: String, tint: Color, allowNone: Boolean = false, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        RibbonButton(onClick = { open = true }) {
            Text(glyph, color = tint, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        DropdownMenu(open, { open = false }) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Palette.chunked(5).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { c ->
                            Box(
                                Modifier.size(32.dp).clip(CircleShape).background(Color(0xFF000000 or c.toLong()))
                                    .clickable { open = false; onPick(c) },
                            )
                        }
                    }
                }
                if (allowNone) {
                    Text(
                        stringResource(R.string.no_color),
                        Modifier.clickable { open = false; onPick(-1) }.padding(vertical = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AlignIcon(kind: Int) {
    val color = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.size(20.dp)) {
        val w = size.width
        val rows = listOf(1f, 0.65f, 1f, 0.65f)
        val sw = 2.dp.toPx()
        rows.forEachIndexed { i, frac ->
            val y = sw * 1.5f + i * (size.height - sw * 3f) / 3f
            val len = w * (if (kind == 3) 1f else frac)
            val x0 = when (kind) { 1 -> (w - len) / 2; 2 -> w - len; else -> 0f }
            drawLineRound(color, Offset(x0, y), Offset(x0 + len, y), sw)
        }
    }
}

private fun DrawScope.drawLineRound(c: Color, a: Offset, b: Offset, w: Float) =
    drawLine(c, a, b, strokeWidth = w, cap = StrokeCap.Round)
