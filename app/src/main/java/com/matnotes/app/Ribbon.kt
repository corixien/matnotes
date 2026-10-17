package com.matnotes.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private fun arg(name: String, type: String, value: String) = "\"$name\":{\"type\":\"$type\",\"value\":\"$value\"}"
private fun args(vararg a: String) = a.joinToString(",", "{", "}")

private val Palette = listOf(
    0x000000, 0x7F7F7F, 0xFFFFFF, 0xC00000, 0xFF0000, 0xFFC000, 0xFFFF00,
    0x92D050, 0x00B050, 0x00B0F0, 0x0070C0, 0x002060, 0x7030A0,
)
// Entries starting with '#' are group headings in the menu.
private val Fonts = listOf(
    "#Word", "Calibri", "Cambria", "Arial", "Arial Narrow", "Times New Roman", "Courier New",
    "#Weitere Schriften", "Gentium Book Basic", "DejaVu Sans", "DejaVu Sans Condensed", "DejaVu Serif",
    "DejaVu Sans Mono", "Comic Neue", "Anton",
)
private val Sizes = listOf(8, 9, 10, 11, 12, 14, 16, 18, 20, 24, 28, 36, 48, 72)
private val Styles = listOf(
    R.string.style_text to "Standard",
    R.string.style_title to "Title",
    R.string.style_h1 to "Heading 1",
    R.string.style_h2 to "Heading 2",
    R.string.style_h3 to "Heading 3",
)

private const val TAB_START = 0
private const val TAB_INSERT = 1
private const val TAB_LAYOUT = 2
private const val TAB_REFS = 3
private const val TAB_REVIEW = 4
private const val TAB_VIEW = 5
private const val TAB_PICTURE = 6

private val RowH = 36.dp

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun Ribbon(state: DocumentViewState, onPickImage: () -> Unit) {
    var tab by remember { mutableIntStateOf(TAB_START) }
    var collapsed by remember { mutableStateOf(false) }
    // Landscape phone with the keyboard up leaves almost no room: show only the tab strip.
    val cramped = LocalConfiguration.current.screenHeightDp < 500 && WindowInsets.isImeVisible
    val hasGraphic = state.graphic != null
    LaunchedEffect(hasGraphic) {
        if (hasGraphic) { tab = TAB_PICTURE; collapsed = false } else if (tab == TAB_PICTURE) tab = TAB_START
    }
    val tabs = listOf(
        TAB_START to R.string.tab_start, TAB_INSERT to R.string.tab_insert, TAB_LAYOUT to R.string.tab_layout,
        TAB_REFS to R.string.tab_refs, TAB_REVIEW to R.string.tab_review, TAB_VIEW to R.string.tab_view,
    ) + if (hasGraphic) listOf(TAB_PICTURE to R.string.tab_picture) else emptyList()

    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth()) {
            HorizontalDivider()
            if (state.finding) FindBar(state)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
                    tabs.forEach { (id, label) ->
                        TabLabel(stringResource(label), tab == id) { tab = id; collapsed = false }
                    }
                }
                Text(
                    if (collapsed) "⌃" else "⌄",
                    Modifier.clip(CircleShape).clickable { collapsed = !collapsed }.padding(horizontal = 14.dp, vertical = 4.dp),
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!collapsed && !cramped) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    when (tab) {
                        TAB_START -> StartTab(state)
                        TAB_INSERT -> InsertTab(state, onPickImage)
                        TAB_LAYOUT -> LayoutTab(state)
                        TAB_REFS -> RefsTab(state)
                        TAB_REVIEW -> ReviewTab(state)
                        TAB_VIEW -> ViewTab(state)
                        else -> PictureTab(state)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- tabs (Word order and group names)

@Composable
private fun StartTab(state: DocumentViewState) {
    val f = state.format
    fun on(cmd: String) = f[cmd] == "true"
    fun enabled(cmd: String) = f[cmd] != "disabled"
    val size = f[".uno:FontHeight"]?.toFloatOrNull()
    val fontName = f[".uno:CharFontName"] ?: "Calibri"

    Group(stringResource(R.string.g_undo)) {
        Column {
            Row {
                IconBtn(enabled = enabled(".uno:Undo"), onClick = { state.uno(".uno:Undo") }) { Glyph("↶", 20) }
                IconBtn(enabled = enabled(".uno:Redo"), onClick = { state.uno(".uno:Redo") }) { Glyph("↷", 20) }
            }
            Row {
                IconBtn(enabled = enabled(".uno:Cut"), onClick = { state.uno(".uno:Cut") }) { Glyph("✂", 17) }
                IconBtn(enabled = enabled(".uno:Copy"), onClick = { state.uno(".uno:Copy") }) { Ic { copyIcon(it) } }
            }
        }
        BigBtn(stringResource(R.string.paste), enabled = enabled(".uno:Paste"), onClick = { state.uno(".uno:Paste") }) { Ic(26.dp) { clipboardIcon(it) } }
    }
    Group(stringResource(R.string.g_font)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dropdown(fontName.take(16), 132.dp, Fonts) { state.charFormat(".uno:CharFontName", args(arg("CharFontName.FamilyName", "string", it))) }
                Dropdown(size?.let { if (it % 1f == 0f) it.toInt().toString() else it.toString() } ?: "–", 52.dp, Sizes.map { it.toString() }) {
                    state.charFormat(".uno:FontHeight", args(arg("FontHeight.Height", "float", it)))
                }
                IconBtn(onClick = { size?.let { state.charFormat(".uno:FontHeight", args(arg("FontHeight.Height", "float", "${it + 1}"))) } }) { Glyph("A⁺", 15) }
                IconBtn(onClick = { size?.let { state.charFormat(".uno:FontHeight", args(arg("FontHeight.Height", "float", "${(it - 1).coerceAtLeast(1f)}"))) } }) { Glyph("A⁻", 13) }
            }
            Row {
                IconBtn(on(".uno:Bold"), onClick = { state.charFormat(".uno:Bold") }) { Text("B", fontWeight = FontWeight.Bold, fontSize = 17.sp) }
                IconBtn(on(".uno:Italic"), onClick = { state.charFormat(".uno:Italic") }) { Text("I", fontStyle = FontStyle.Italic, fontSize = 17.sp) }
                IconBtn(on(".uno:Underline"), onClick = { state.charFormat(".uno:Underline") }) { Text("U", textDecoration = TextDecoration.Underline, fontSize = 17.sp) }
                IconBtn(on(".uno:Strikeout"), onClick = { state.charFormat(".uno:Strikeout") }) { Text("ab", textDecoration = TextDecoration.LineThrough, fontSize = 15.sp) }
                IconBtn(on(".uno:SubScript"), onClick = { state.charFormat(".uno:SubScript") }) { Glyph("x₂", 15) }
                IconBtn(on(".uno:SuperScript"), onClick = { state.charFormat(".uno:SuperScript") }) { Glyph("x²", 15) }
                ColorBtn(isHighlight = true, allowNone = true) { state.charFormat(".uno:BackColor", args(arg("BackColor", "long", "$it"))) }
                ColorBtn(isHighlight = false) { state.charFormat(".uno:Color", args(arg("Color", "long", "$it"))) }
                IconBtn(onClick = { state.charFormat(".uno:ResetAttributes") }) { Ic { clearFormatIcon(it) } }
            }
        }
    }
    Group(stringResource(R.string.g_paragraph)) {
        Column {
            Row {
                IconBtn(on(".uno:DefaultBullet"), onClick = { state.uno(".uno:DefaultBullet") }) { Ic { bulletsIcon(it) } }
                IconBtn(on(".uno:DefaultNumbering"), onClick = { state.uno(".uno:DefaultNumbering") }) { NumberingIcon() }
                IconBtn(onClick = { state.uno(".uno:DecrementIndent") }) { Ic { indentIcon(it, false) } }
                IconBtn(onClick = { state.uno(".uno:IncrementIndent") }) { Ic { indentIcon(it, true) } }
                SpacingBtn(state)
            }
            Row {
                IconBtn(on(".uno:LeftPara"), onClick = { state.uno(".uno:LeftPara") }) { Ic { alignIcon(it, 0) } }
                IconBtn(on(".uno:CenterPara"), onClick = { state.uno(".uno:CenterPara") }) { Ic { alignIcon(it, 1) } }
                IconBtn(on(".uno:RightPara"), onClick = { state.uno(".uno:RightPara") }) { Ic { alignIcon(it, 2) } }
                IconBtn(on(".uno:JustifyPara"), onClick = { state.uno(".uno:JustifyPara") }) { Ic { alignIcon(it, 3) } }
                IconBtn(on(".uno:ControlCodes"), onClick = { state.uno(".uno:ControlCodes") }) { Glyph("¶", 18) }
            }
        }
    }
    Group(stringResource(R.string.g_styles)) {
        Styles.forEach { (label, name) ->
            StyleCard(stringResource(label), name) {
                state.uno(".uno:StyleApply", args(arg("Style", "string", name), arg("FamilyName", "string", "ParagraphStyles")))
            }
        }
    }
    Group(stringResource(R.string.g_editing), last = true) {
        BigBtn(stringResource(R.string.find), onClick = { state.finding = true }) { Glyph("⌕", 26) }
        BigBtn(stringResource(R.string.select_all), onClick = { state.uno(".uno:SelectAll") }) { Glyph("▤", 24) }
    }
}

@Composable
private fun InsertTab(state: DocumentViewState, onPickImage: () -> Unit) {
    var linkDialog by remember { mutableStateOf(false) }
    if (linkDialog) LinkDialog({ linkDialog = false }) { text, url -> state.insertLink(text, url) }
    Group(stringResource(R.string.g_pages)) {
        BigBtn(stringResource(R.string.insert_pagebreak), onClick = { state.uno(".uno:InsertPagebreak") }) { Ic(26.dp) { pageBreakIcon(it) } }
    }
    Group(stringResource(R.string.g_tables)) {
        var open by remember { mutableStateOf(false) }
        Box {
            BigBtn(stringResource(R.string.insert_table), onClick = { open = true }) { Ic(26.dp) { tableIcon(it) } }
            DropdownMenu(open, { open = false }) {
                listOf(2 to 2, 3 to 3, 4 to 4, 5 to 5, 3 to 6).forEach { (c, r) ->
                    DropdownMenuItem({ Text("$c × $r") }, {
                        open = false
                        state.uno(".uno:InsertTable", args(arg("Columns", "long", "$c"), arg("Rows", "long", "$r")))
                    })
                }
            }
        }
        Column {
            Row {
                IconBtn(onClick = { state.uno(".uno:InsertRowsAfter") }) { Glyph("+≡", 15) }
                IconBtn(onClick = { state.uno(".uno:InsertColumnsAfter") }) { Glyph("+⫼", 15) }
            }
            Row {
                IconBtn(onClick = { state.uno(".uno:DeleteRows") }) { Glyph("−≡", 15) }
                IconBtn(onClick = { state.uno(".uno:DeleteColumns") }) { Glyph("−⫼", 15) }
            }
        }
        IconBtn(onClick = { state.uno(".uno:MergeCells") }, wide = true) { Text(stringResource(R.string.merge_cells), fontSize = 12.sp) }
    }
    Group(stringResource(R.string.g_illustrations)) {
        BigBtn(stringResource(R.string.insert_picture), onClick = onPickImage) { Ic(26.dp) { imageIcon(it) } }
    }
    Group(stringResource(R.string.g_links)) {
        BigBtn(stringResource(R.string.link), onClick = { linkDialog = true }) { Glyph("🔗", 22) }
    }
    Group(stringResource(R.string.g_headfoot)) {
        val hdr = { on: Boolean, cmd: String ->
            state.uno(cmd, args(arg("PageStyle", "string", "Default Page Style"), arg("On", "boolean", "$on")))
        }
        BigBtn(stringResource(R.string.header), onClick = { hdr(true, ".uno:InsertPageHeader") }) { Glyph("▔", 22) }
        BigBtn(stringResource(R.string.footer), onClick = { hdr(true, ".uno:InsertPageFooter") }) { Glyph("▁", 22) }
        BigBtn(stringResource(R.string.page_number), onClick = { state.uno(".uno:InsertPageNumberField") }) { Glyph("#", 22) }
    }
    Group(stringResource(R.string.g_text), last = true) {
        BigBtn(stringResource(R.string.date_time), onClick = { state.uno(".uno:InsertDateField") }) { Glyph("📅", 20) }
    }
}

@Composable
private fun LayoutTab(state: DocumentViewState) {
    Group(stringResource(R.string.g_indent_spacing)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBtn(onClick = { state.uno(".uno:DecrementIndent") }) { Ic { indentIcon(it, false) } }
                IconBtn(onClick = { state.uno(".uno:IncrementIndent") }) { Ic { indentIcon(it, true) } }
                Text(stringResource(R.string.indent), fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBtn(onClick = { state.uno(".uno:ParaspaceDecrease") }) { Glyph("−", 18) }
                IconBtn(onClick = { state.uno(".uno:ParaspaceIncrease") }) { Glyph("+", 18) }
                Text(stringResource(R.string.para_spacing), fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
            }
        }
        SpacingBtn(state, big = true)
    }
    Group(stringResource(R.string.g_pages), last = true) {
        BigBtn(stringResource(R.string.insert_pagebreak), onClick = { state.uno(".uno:InsertPagebreak") }) { Ic(26.dp) { pageBreakIcon(it) } }
    }
}

@Composable
private fun RefsTab(state: DocumentViewState) {
    Group(stringResource(R.string.g_footnotes), last = true) {
        BigBtn(stringResource(R.string.footnote), onClick = { state.uno(".uno:InsertFootnote") }) { Glyph("¹", 22) }
        BigBtn(stringResource(R.string.endnote), onClick = { state.uno(".uno:InsertEndnote") }) { Glyph("¹¹", 20) }
    }
}

@Composable
private fun ReviewTab(state: DocumentViewState) {
    val f = state.format
    val context = androidx.compose.ui.platform.LocalContext.current
    Group(stringResource(R.string.g_proofing)) {
        BigBtn(stringResource(R.string.spell_check), onClick = { state.uno(".uno:SpellOnline") }) { Glyph("ABC✓", 14) }
        BigBtn(stringResource(R.string.word_count), onClick = {
            android.widget.Toast.makeText(context, f[".uno:StateWordCount"] ?: "–", android.widget.Toast.LENGTH_LONG).show()
        }) { Glyph("123", 18) }
    }
    Group(stringResource(R.string.g_changes), last = true) {
        BigBtn(stringResource(R.string.track_changes), onClick = { state.uno(".uno:TrackChanges") }) { Glyph("✎", 22) }
        BigBtn(stringResource(R.string.accept_all), onClick = { state.uno(".uno:AcceptAllTrackedChanges") }) { Glyph("✓", 22) }
        BigBtn(stringResource(R.string.reject_all), onClick = { state.uno(".uno:RejectAllTrackedChanges") }) { Glyph("✗", 22) }
    }
}

@Composable
private fun ViewTab(state: DocumentViewState) {
    Group(stringResource(R.string.g_show)) {
        BigBtn(stringResource(R.string.formatting_marks), width = 72.dp, onClick = { state.uno(".uno:ControlCodes") }) { Glyph("¶", 24) }
    }
    Group(stringResource(R.string.g_zoom), last = true) {
        BigBtn(stringResource(R.string.zoom_out), onClick = { state.zoomBy(1 / 1.3f) }) { Glyph("−", 26) }
        BigBtn(stringResource(R.string.zoom_fit), onClick = { state.fitWidth() }) { Ic(26.dp) { fitIcon(it) } }
        BigBtn(stringResource(R.string.zoom_in), onClick = { state.zoomBy(1.3f) }) { Glyph("+", 26) }
    }
}

@Composable
private fun PictureTab(state: DocumentViewState) {
    Group(stringResource(R.string.g_wrap)) {
        listOf(
            Triple(R.string.wrap_inline, ".uno:SetAnchorToCharAsChar", 0),
            Triple(R.string.wrap_square, ".uno:WrapOn", 1),
            Triple(R.string.wrap_tight, ".uno:WrapContour", 2),
            Triple(R.string.wrap_topbottom, ".uno:WrapOff", 3),
            Triple(R.string.wrap_front, ".uno:WrapThrough", 4),
            Triple(R.string.wrap_behind, ".uno:SetObjectToBackground", 5),
        ).forEach { (label, cmd, kind) ->
            BigBtn(stringResource(label), width = 72.dp, onClick = { state.uno(cmd) }) { Ic(26.dp) { wrapIcon(it, kind) } }
        }
    }
    Group(stringResource(R.string.g_arrange), last = true) {
        BigBtn(stringResource(R.string.bring_front), width = 72.dp, onClick = { state.uno(".uno:BringToFront") }) { Glyph("⬒", 22) }
        BigBtn(stringResource(R.string.send_back), width = 72.dp, onClick = { state.uno(".uno:SendToBack") }) { Glyph("⬓", 22) }
        BigBtn(stringResource(R.string.delete), onClick = { state.uno(".uno:Delete") }) { Glyph("🗑", 20) }
    }
}

@Composable
private fun FindBar(state: DocumentViewState) {
    var text by remember { mutableStateOf("") }
    fun search(backward: Boolean) {
        if (text.isEmpty()) return
        state.uno(
            ".uno:ExecuteSearch",
            args(arg("SearchItem.SearchString", "string", text.replace("\\", "\\\\").replace("\"", "\\\"")), arg("SearchItem.Backward", "boolean", "$backward")),
        )
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.text.BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { search(false) }),
            modifier = Modifier.weight(1f).height(40.dp)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 9.dp),
        )
        IconBtn(onClick = { search(true) }) { Glyph("▲", 14) }
        IconBtn(onClick = { search(false) }) { Glyph("▼", 14) }
        IconBtn(onClick = { state.finding = false }) { Glyph("✕", 16) }
    }
}

@Composable
private fun LinkDialog(onDismiss: () -> Unit, onOk: (String, String) -> Unit) {
    var text by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("https://") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.link)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedTextField(text, { text = it }, label = { Text(stringResource(R.string.link_text)) }, singleLine = true)
                androidx.compose.material3.OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.link_address)) }, singleLine = true)
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton({ onDismiss(); onOk(text.ifBlank { url }, url) }) { Text(stringResource(R.string.insert_ok)) }
        },
        dismissButton = { androidx.compose.material3.TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

// ---------------------------------------------------------------- building blocks

@Composable
private fun TabLabel(text: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            style = MaterialTheme.typography.labelLarge,
        )
        Box(
            Modifier.padding(top = 2.dp).width(24.dp).height(2.dp)
                .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
        )
    }
}

/** A Word-style ribbon group: content on top, caption below, divider to the right. */
@Composable
private fun Group(title: String, last: Boolean = false, content: @Composable () -> Unit) {
    Row {
        Column(Modifier.padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.height(RowH * 2), verticalAlignment = Alignment.CenterVertically) { content() }
            Text(
                title, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 2.dp),
            )
        }
        if (!last) Box(Modifier.padding(vertical = 6.dp).width(1.dp).height(RowH * 2 + 14.dp).background(MaterialTheme.colorScheme.outlineVariant))
    }
}

@Composable
private fun IconBtn(
    active: Boolean = false,
    enabled: Boolean = true,
    wide: Boolean = false,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .height(RowH)
            .then(if (wide) Modifier.padding(horizontal = 2.dp) else Modifier.width(RowH))
            .alpha(if (enabled) 1f else 0.38f)
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .then(if (wide) Modifier.padding(horizontal = 8.dp) else Modifier),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun BigBtn(label: String, width: Dp = 60.dp, enabled: Boolean = true, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Column(
        Modifier.width(width).height(RowH * 2).alpha(if (enabled) 1f else 0.38f)
            .clip(RoundedCornerShape(6.dp)).clickable(enabled = enabled, onClick = onClick).padding(vertical = 4.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.height(32.dp), contentAlignment = Alignment.Center) { icon() }
        Text(label, fontSize = 10.sp, textAlign = TextAlign.Center, maxLines = 2, lineHeight = 11.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun Glyph(text: String, size: Int) = Text(text, fontSize = size.sp)

@Composable
private fun Dropdown(label: String, width: Dp, items: List<String>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(horizontal = 2.dp)) {
        Row(
            Modifier.width(width).height(RowH - 4.dp)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                .clip(RoundedCornerShape(4.dp)).clickable { open = true }.padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, fontSize = 13.sp, maxLines = 1)
            Text("▾", fontSize = 11.sp)
        }
        DropdownMenu(open, { open = false }) {
            items.forEach {
                if (it.startsWith("#")) {
                    DropdownMenuItem(
                        { Text(it.drop(1), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) },
                        {}, enabled = false,
                    )
                } else {
                    DropdownMenuItem({ Text(it) }, { open = false; onPick(it) })
                }
            }
        }
    }
}

@Composable
private fun ColorBtn(isHighlight: Boolean, allowNone: Boolean = false, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var color by remember { mutableIntStateOf(if (isHighlight) 0xFFFF00 else 0xFF0000) }
    Box {
        IconBtn(onClick = { open = true }) {
            val fg = MaterialTheme.colorScheme.onSurface
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (isHighlight) Text("ab", fontSize = 14.sp, fontWeight = FontWeight.Bold) else Text("A", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = fg)
                Box(Modifier.width(18.dp).height(4.dp).background(Color(0xFF000000 or color.toLong())))
            }
        }
        DropdownMenu(open, { open = false }) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Palette.chunked(5).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { c ->
                            Box(
                                Modifier.size(32.dp).clip(CircleShape).background(Color(0xFF000000 or c.toLong()))
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                    .clickable { open = false; color = c; onPick(c) },
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
private fun SpacingBtn(state: DocumentViewState, big: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    Box {
        if (big) BigBtn(stringResource(R.string.line_spacing), onClick = { open = true }) { Ic(26.dp) { spacingIcon(it) } }
        else IconBtn(onClick = { open = true }) { Ic { spacingIcon(it) } }
        DropdownMenu(open, { open = false }) {
            listOf("1.0" to ".uno:SpacePara1", "1.5" to ".uno:SpacePara15", "2.0" to ".uno:SpacePara2").forEach { (label, cmd) ->
                DropdownMenuItem({ Text(label) }, { open = false; state.uno(cmd) })
            }
        }
    }
}

@Composable
private fun StyleCard(label: String, style: String, onClick: () -> Unit) {
    Column(
        Modifier.padding(horizontal = 2.dp).width(76.dp).height(RowH * 2 - 6.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
            .clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val (size, weight) = when (style) {
            "Title" -> 22 to FontWeight.Light
            "Heading 1" -> 19 to FontWeight.Bold
            "Heading 2" -> 17 to FontWeight.Bold
            "Heading 3" -> 15 to FontWeight.SemiBold
            else -> 16 to FontWeight.Normal
        }
        Text("AaBb", fontSize = size.sp, fontWeight = weight, color = if (style == "Standard") MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary)
        Text(label, fontSize = 10.sp, maxLines = 1)
    }
}

// ---------------------------------------------------------------- icons

@Composable
private fun Ic(size: Dp = 20.dp, draw: DrawScope.(Color) -> Unit) {
    val c = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.size(size)) { draw(c) }
}

private fun DrawScope.line(c: Color, x0: Float, y0: Float, x1: Float, y1: Float, w: Float = 2f * density) =
    drawLine(c, Offset(x0, y0), Offset(x1, y1), strokeWidth = w, cap = StrokeCap.Round)

private fun DrawScope.alignIcon(c: Color, kind: Int) {
    val w = size.width
    val rows = listOf(1f, 0.65f, 1f, 0.65f)
    val sw = 2f * density
    rows.forEachIndexed { i, frac ->
        val y = sw * 1.5f + i * (size.height - sw * 3f) / 3f
        val len = w * (if (kind == 3) 1f else frac)
        val x0 = when (kind) { 1 -> (w - len) / 2; 2 -> w - len; else -> 0f }
        line(c, x0, y, x0 + len, y, sw)
    }
}

private fun DrawScope.bulletsIcon(c: Color) {
    val h = size.height; val w = size.width
    for (i in 0..2) {
        val y = h * (0.2f + i * 0.3f)
        drawCircle(c, 1.7f * density, Offset(w * 0.1f, y))
        line(c, w * 0.32f, y, w, y)
    }
}

@Composable
private fun NumberingIcon() {
    val c = MaterialTheme.colorScheme.onSurface
    Row(Modifier.size(20.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            listOf("1", "2", "3").forEach { Text(it, fontSize = 6.sp, lineHeight = 6.sp, color = c) }
        }
        Canvas(Modifier.weight(1f).fillMaxHeight()) {
            for (i in 0..2) { val y = size.height * (0.15f + i * 0.35f); line(c, 0f, y, size.width, y) }
        }
    }
}

private fun DrawScope.indentIcon(c: Color, increase: Boolean) {
    val w = size.width; val h = size.height
    line(c, 0f, h * 0.1f, w, h * 0.1f); line(c, 0f, h * 0.9f, w, h * 0.9f)
    line(c, w * 0.5f, h * 0.37f, w, h * 0.37f); line(c, w * 0.5f, h * 0.63f, w, h * 0.63f)
    val p = Path().apply {
        if (increase) { moveTo(0f, h * 0.3f); lineTo(w * 0.3f, h * 0.5f); lineTo(0f, h * 0.7f) }
        else { moveTo(w * 0.3f, h * 0.3f); lineTo(0f, h * 0.5f); lineTo(w * 0.3f, h * 0.7f) }
        close()
    }
    drawPath(p, c)
}

private fun DrawScope.spacingIcon(c: Color) {
    val w = size.width; val h = size.height
    line(c, w * 0.45f, h * 0.15f, w, h * 0.15f); line(c, w * 0.45f, h * 0.5f, w, h * 0.5f); line(c, w * 0.45f, h * 0.85f, w, h * 0.85f)
    line(c, w * 0.15f, h * 0.15f, w * 0.15f, h * 0.85f, 1.5f * density)
    drawPath(Path().apply { moveTo(0f, h * 0.3f); lineTo(w * 0.15f, h * 0.05f); lineTo(w * 0.3f, h * 0.3f); close() }, c)
    drawPath(Path().apply { moveTo(0f, h * 0.7f); lineTo(w * 0.15f, h * 0.95f); lineTo(w * 0.3f, h * 0.7f); close() }, c)
}

private fun DrawScope.clipboardIcon(c: Color) {
    val w = size.width; val h = size.height; val sw = 1.8f * density
    drawRoundRect(c, Offset(w * 0.12f, h * 0.12f), Size(w * 0.76f, h * 0.84f), CornerRadius(3f * density), style = Stroke(sw))
    drawRoundRect(c, Offset(w * 0.32f, h * 0.02f), Size(w * 0.36f, h * 0.2f), CornerRadius(2f * density))
    line(c, w * 0.28f, h * 0.5f, w * 0.72f, h * 0.5f, sw); line(c, w * 0.28f, h * 0.7f, w * 0.72f, h * 0.7f, sw)
}

private fun DrawScope.copyIcon(c: Color) {
    val w = size.width; val h = size.height; val sw = 1.6f * density
    drawRoundRect(c, Offset(w * 0.1f, h * 0.1f), Size(w * 0.5f, h * 0.6f), CornerRadius(2f * density), style = Stroke(sw))
    drawRoundRect(c, Offset(w * 0.4f, h * 0.3f), Size(w * 0.5f, h * 0.6f), CornerRadius(2f * density), style = Stroke(sw))
}

private fun DrawScope.clearFormatIcon(c: Color) {
    val w = size.width; val h = size.height
    line(c, w * 0.1f, h * 0.9f, w * 0.7f, h * 0.9f, 1.6f * density)
    line(c, w * 0.15f, h * 0.15f, w * 0.85f, h * 0.7f, 2f * density)
    line(c, w * 0.85f, h * 0.15f, w * 0.15f, h * 0.7f, 2f * density)
}

private fun DrawScope.imageIcon(c: Color) {
    val w = size.width; val h = size.height; val sw = 1.8f * density
    drawRoundRect(c, Offset(w * 0.06f, h * 0.14f), Size(w * 0.88f, h * 0.72f), CornerRadius(3f * density), style = Stroke(sw))
    drawCircle(c, w * 0.08f, Offset(w * 0.32f, h * 0.36f))
    drawPath(Path().apply { moveTo(w * 0.12f, h * 0.8f); lineTo(w * 0.42f, h * 0.5f); lineTo(w * 0.62f, h * 0.7f); lineTo(w * 0.74f, h * 0.58f); lineTo(w * 0.9f, h * 0.8f); close() }, c)
}

private fun DrawScope.tableIcon(c: Color) {
    val w = size.width; val h = size.height; val sw = 1.6f * density
    drawRoundRect(c, Offset(w * 0.06f, h * 0.12f), Size(w * 0.88f, h * 0.76f), CornerRadius(2f * density), style = Stroke(sw))
    line(c, w * 0.06f, h * 0.4f, w * 0.94f, h * 0.4f, sw); line(c, w * 0.06f, h * 0.64f, w * 0.94f, h * 0.64f, sw)
    line(c, w * 0.36f, h * 0.12f, w * 0.36f, h * 0.88f, sw); line(c, w * 0.64f, h * 0.12f, w * 0.64f, h * 0.88f, sw)
}

private fun DrawScope.pageBreakIcon(c: Color) {
    val w = size.width; val h = size.height; val sw = 1.6f * density
    drawRoundRect(c, Offset(w * 0.22f, h * 0.04f), Size(w * 0.56f, h * 0.36f), CornerRadius(2f * density), style = Stroke(sw))
    drawRoundRect(c, Offset(w * 0.22f, h * 0.6f), Size(w * 0.56f, h * 0.36f), CornerRadius(2f * density), style = Stroke(sw))
    for (i in 0..3) { val x = w * (0.08f + i * 0.24f); line(c, x, h * 0.5f, x + w * 0.12f, h * 0.5f, sw) }
}

private fun DrawScope.fitIcon(c: Color) {
    val w = size.width; val h = size.height; val sw = 1.8f * density
    drawRoundRect(c, Offset(w * 0.2f, h * 0.06f), Size(w * 0.6f, h * 0.88f), CornerRadius(2f * density), style = Stroke(sw))
    line(c, w * 0.02f, h * 0.5f, w * 0.18f, h * 0.5f, sw); line(c, w * 0.82f, h * 0.5f, w * 0.98f, h * 0.5f, sw)
}

/** Mini preview of the text-wrap modes: text lines with a picture block. */
private fun DrawScope.wrapIcon(c: Color, kind: Int) {
    val w = size.width; val h = size.height
    val lw = 1.5f * density
    val rows = 5
    val block = when (kind) {
        0 -> Size(w * 0.3f, h * 0.2f)
        3 -> Size(w * 0.5f, h * 0.24f)
        else -> Size(w * 0.4f, h * 0.4f)
    }
    val bx = (w - block.width) / 2f
    val by = if (kind == 0) h * 0.4f else (h - block.height) / 2f
    for (i in 0 until rows) {
        val y = h * (0.08f + i * 0.21f)
        val covered = y + lw > by - 1 && y - lw < by + block.height
        when {
            kind == 4 || kind == 5 -> line(c.copy(alpha = if (kind == 5) 0.9f else 0.5f), 0f, y, w, y, lw)
            kind == 3 && covered -> Unit
            kind == 0 && covered -> { line(c, 0f, y, bx - 1, y, lw); line(c, bx + block.width + 1, y, w, y, lw) }
            covered -> { line(c, 0f, y, bx - 2 * density, y, lw); line(c, bx + block.width + 2 * density, y, w, y, lw) }
            else -> line(c, 0f, y, w, y, lw)
        }
    }
    val fill = when (kind) { 4 -> c; 5 -> c.copy(alpha = 0.25f); else -> c }
    drawRect(fill, Offset(bx, by), block)
}
