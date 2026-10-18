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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.TextFieldValue
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
import androidx.compose.material3.surfaceColorAtElevation
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
// Word-compatible: bundled metric clones render with Word's line breaks.
// Other Word fonts: Microsoft's fonts cannot be bundled; the name is kept in the document (so Word shows the real
// font) while this phone shows a stand-in. Free fonts: bundled, shown exactly.
private val WordFonts = listOf("Calibri", "Cambria", "Arial", "Arial Narrow", "Times New Roman", "Courier New")
private val OtherWordFonts = listOf(
    "Agency FB", "Algerian", "Arial Black", "Arial Rounded MT Bold", "Baskerville Old Face", "Bauhaus 93",
    "Bell MT", "Berlin Sans FB", "Bernard MT Condensed", "Bodoni MT", "Book Antiqua", "Bookman Old Style",
    "Bradley Hand ITC", "Britannic Bold", "Brush Script MT", "Calibri Light", "Californian FB", "Calisto MT",
    "Cambria Math", "Candara", "Castellar", "Centaur", "Century", "Century Gothic",
    "Century Schoolbook", "Chiller", "Colonna MT", "Comic Sans MS", "Consolas", "Constantia",
    "Cooper Black", "Copperplate Gothic", "Corbel", "Curlz MT", "Dubai", "Edwardian Script ITC",
    "Elephant", "Engravers MT", "Eras ITC", "Felix Titling", "Footlight MT Light", "Forte",
    "Franklin Gothic Book", "Franklin Gothic Medium", "Freestyle Script", "French Script MT", "Gabriola", "Garamond",
    "Georgia", "Gigi", "Gill Sans MT", "Gloucester MT", "Goudy Old Style", "Haettenschweiler",
    "Harlow Solid", "Harrington", "High Tower Text", "Impact", "Imprint MT Shadow", "Informal Roman",
    "Jokerman", "Juice ITC", "Kristen ITC", "Kunstler Script", "Lucida Bright", "Lucida Calligraphy",
    "Lucida Console", "Lucida Fax", "Lucida Handwriting", "Lucida Sans", "Lucida Sans Typewriter", "Magneto",
    "Maiandra GD", "Matura MT Script Capitals", "Microsoft Sans Serif", "Mistral", "Modern No. 20", "Monotype Corsiva",
    "MS Gothic", "MS Mincho", "MT Extra", "Niagara Engraved", "Niagara Solid", "OCR A Extended",
    "Old English Text MT", "Onyx", "Palatino Linotype", "Papyrus", "Parchment", "Perpetua",
    "Perpetua Titling MT", "Playbill", "Poor Richard", "Pristina", "Rage Italic", "Ravie",
    "Rockwell", "Script MT Bold", "Segoe Print", "Segoe Script", "Segoe UI", "Segoe UI Light",
    "Segoe UI Semibold", "Showcard Gothic", "SimSun", "Snap ITC", "Stencil", "Sylfaen",
    "Symbol", "Tahoma", "Tempus Sans ITC", "Trebuchet MS", "Tw Cen MT", "Verdana",
    "Viner Hand ITC", "Vivaldi", "Vladimir Script", "Webdings", "Wide Latin", "Wingdings",
    "Yu Gothic",
)
private val FreeFonts = listOf("Gentium Book Basic", "DejaVu Sans", "DejaVu Sans Condensed", "DejaVu Serif", "DejaVu Sans Mono", "Comic Neue", "Anton")
private val Fonts = listOf("#Word") + WordFonts + listOf("#Weitere Word-Schriften (Ersatzanzeige)") + OtherWordFonts +
    listOf("#Freie Schriften") + FreeFonts
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
internal const val TAB_PICTURE = 6
private const val TAB_TABLE = 7

private val RowH = 36.dp

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun Ribbon(state: DocumentViewState, onPickImage: () -> Unit, onPageSetup: (PageSetupChange) -> Unit) {
    var tab by remember { mutableIntStateOf(TAB_START) }
    var collapsed by remember { mutableStateOf(false) }
    // Landscape phone with the keyboard up leaves almost no room: show only the tab strip.
    val cramped = LocalConfiguration.current.screenHeightDp < 500 && WindowInsets.isImeVisible
    val hasGraphic = state.graphic != null
    LaunchedEffect(state.ribbonTabRequest) {
        if (state.ribbonTabRequest >= 0) { tab = state.ribbonTabRequest; collapsed = false; state.ribbonTabRequest = -1 }
    }
    LaunchedEffect(hasGraphic) {
        if (hasGraphic) { tab = TAB_PICTURE; collapsed = false } else if (tab == TAB_PICTURE) tab = TAB_START
    }
    LaunchedEffect(state.inTable) { if (!state.inTable && tab == TAB_TABLE) tab = TAB_START }
    val tabs = listOf(
        TAB_START to R.string.tab_start, TAB_INSERT to R.string.tab_insert, TAB_LAYOUT to R.string.tab_layout,
        TAB_REFS to R.string.tab_refs, TAB_REVIEW to R.string.tab_review, TAB_VIEW to R.string.tab_view,
    ) + (if (hasGraphic) listOf(TAB_PICTURE to (if (state.graphicIsImage) R.string.tab_picture else R.string.tab_shape)) else emptyList()) +
        (if (state.inTable && !hasGraphic) listOf(TAB_TABLE to R.string.tab_table) else emptyList())

    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth()) {
            HorizontalDivider()
            if (state.finding) FindBar(state)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
                        tabs.forEach { (id, label) ->
                            TabLabel(stringResource(label), tab == id) { tab = id; collapsed = false }
                        }
                    }
                    // fade at the right edge: shows that more tabs can be scrolled into view
                    val bg = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
                    Box(
                        Modifier.align(Alignment.CenterEnd).width(28.dp).height(40.dp)
                            .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color.Transparent, bg))),
                    )
                }
                Text(
                    "⌨",
                    Modifier.clip(CircleShape).clickable {
                        state.wantKeyboard = !state.wantKeyboard
                        state.focusTick++
                    }.padding(horizontal = 12.dp, vertical = 4.dp),
                    fontSize = 20.sp,
                    color = if (state.wantKeyboard) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                        TAB_LAYOUT -> LayoutTab(state, onPageSetup)
                        TAB_REFS -> RefsTab(state)
                        TAB_REVIEW -> ReviewTab(state)
                        TAB_VIEW -> ViewTab(state)
                        TAB_TABLE -> TableTab(state)
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
        BigBtn(stringResource(R.string.format_painter), onClick = { state.uno(".uno:FormatPaintbrush") }) { Glyph("🖌", 20) }
    }
    Group(stringResource(R.string.g_font)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Combo(fontName, 140.dp, Fonts, state = state) { state.charFormat(".uno:CharFontName", args(arg("CharFontName.FamilyName", "string", it))) }
                Combo(size?.let { if (it % 1f == 0f) it.toInt().toString() else it.toString() } ?: "", 62.dp, Sizes.map { it.toString() }, numeric = true, state = state) {
                    it.replace(',', '.').toFloatOrNull()?.takeIf { v -> v in 1f..1638f }?.let { v ->
                        state.charFormat(".uno:FontHeight", args(arg("FontHeight.Height", "float", "$v")))
                    }
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
                CaseMenu(state)
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
                ColorBtn(isHighlight = true, allowNone = true) { state.uno(".uno:BackgroundColor", args(arg("BackgroundColor", "long", "$it"))) }
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
            DropdownMenu(open, { open = false }, modifier = Modifier.heightIn(max = 360.dp)) {
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
        MenuBigBtn(stringResource(R.string.shapes), { Ic(26.dp) { shapesIcon(it) } }) { close ->
            ShapeGroups.forEach { (title, items) ->
                Text(
                    stringResource(title), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 2.dp),
                )
                items.chunked(5).forEach { row ->
                    Row(Modifier.padding(horizontal = 8.dp)) {
                        row.forEach { (_, cmd) ->
                            Box(
                                Modifier.size(46.dp).clip(RoundedCornerShape(6.dp)).clickable { close(); state.insertShape(cmd) },
                                contentAlignment = Alignment.Center,
                            ) {
                                val fill = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                                val line = MaterialTheme.colorScheme.primary
                                Canvas(Modifier.size(34.dp)) { drawShapeThumb(cmd, fill, line) }
                            }
                        }
                    }
                }
            }
        }
        BigBtn(stringResource(R.string.text_box), onClick = { state.insertShape(".uno:DrawText") }) { Glyph("A▭", 17) }
    }
    Group(stringResource(R.string.g_symbols)) {
        MenuBigBtn(stringResource(R.string.symbol), { Glyph("Ω", 24) }) { close ->
            Symbols.chunked(6).forEach { row ->
                Row(Modifier.padding(horizontal = 8.dp)) {
                    row.forEach { ch ->
                        Box(
                            Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)).clickable { close(); state.typeText(ch) },
                            contentAlignment = Alignment.Center,
                        ) { Text(ch, fontSize = 20.sp) }
                    }
                }
            }
        }
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
private fun LayoutTab(state: DocumentViewState, onPageSetup: (PageSetupChange) -> Unit) {
    var marginsDialog by remember { mutableStateOf(false) }
    if (marginsDialog) MarginsDialog({ marginsDialog = false }) { onPageSetup(PageSetupChange(margins = it)) }
    Group(stringResource(R.string.g_page_setup)) {
        MenuBigBtn(stringResource(R.string.margins), { Ic(26.dp) { marginsIcon(it) } }) { close ->
            @Composable fun m(top: Double, side: Double, label: Int) = DropdownMenuItem({ Text(stringResource(label)) }, {
                close()
                onPageSetup(PageSetupChange(margins = intArrayOf(PageSetup.cm(top), PageSetup.cm(side), PageSetup.cm(top), PageSetup.cm(side))))
            })
            m(2.54, 2.54, R.string.margins_normal)
            m(1.27, 1.27, R.string.margins_narrow)
            m(2.54, 1.91, R.string.margins_moderate)
            m(2.54, 5.08, R.string.margins_wide)
            DropdownMenuItem({ Text(stringResource(R.string.margins_custom)) }, { close(); marginsDialog = true })
        }
        MenuBigBtn(stringResource(R.string.orientation), { Ic(26.dp) { orientationIcon(it) } }) { close ->
            DropdownMenuItem({ Text(stringResource(R.string.portrait)) }, { close(); onPageSetup(PageSetupChange(landscape = false)) })
            DropdownMenuItem({ Text(stringResource(R.string.landscape)) }, { close(); onPageSetup(PageSetupChange(landscape = true)) })
        }
        MenuBigBtn(stringResource(R.string.page_size), { Ic(26.dp) { pageBreakIcon(it) } }) { close ->
            listOf("A3" to PageSizes.A3, "A4" to PageSizes.A4, "A5" to PageSizes.A5, "B5" to PageSizes.B5, "Letter" to PageSizes.Letter, "Legal" to PageSizes.Legal)
                .forEach { (n, sz) -> DropdownMenuItem({ Text(n) }, { close(); onPageSetup(PageSetupChange(size = sz)) }) }
        }
    }
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
        BigBtn(stringResource(R.string.column_break), onClick = { state.uno(".uno:InsertColumnBreak") }) { Glyph("⫼", 22) }
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
private fun TableTab(state: DocumentViewState) {
    Group(stringResource(R.string.g_rows_cols)) {
        listOf(
            R.string.tbl_row_above to ".uno:InsertRowsBefore",
            R.string.tbl_row_below to ".uno:InsertRowsAfter",
            R.string.tbl_col_left to ".uno:InsertColumnsBefore",
            R.string.tbl_col_right to ".uno:InsertColumnsAfter",
        ).forEach { (label, cmd) ->
            BigBtn(stringResource(label), width = 76.dp, onClick = { state.uno(cmd) }) { Ic(26.dp) { tableIcon(it) } }
        }
    }
    Group(stringResource(R.string.g_cells)) {
        BigBtn(stringResource(R.string.merge_cells), width = 76.dp, onClick = { state.uno(".uno:MergeCells") }) { Glyph("⊞", 22) }
        MenuBigBtn(stringResource(R.string.tbl_split), { Glyph("⊟", 22) }) { close ->
            DropdownMenuItem({ Text(stringResource(R.string.tbl_split_cols)) }, {
                close(); state.uno(".uno:SplitCell", args(arg("Amount", "long", "2"), arg("HorizontalFlag", "boolean", "false")))
            })
            DropdownMenuItem({ Text(stringResource(R.string.tbl_split_rows)) }, {
                close(); state.uno(".uno:SplitCell", args(arg("Amount", "long", "2"), arg("HorizontalFlag", "boolean", "true")))
            })
        }
        BigBtn(stringResource(R.string.tbl_select_table), width = 76.dp, onClick = { state.uno(".uno:SelectTable") }) { Glyph("▦", 22) }
        ColorBtn(isHighlight = true, allowNone = false) { state.uno(".uno:BackgroundColor", args(arg("BackgroundColor", "long", "$it"))) }
        Text(stringResource(R.string.tbl_cell_color), fontSize = 11.sp, modifier = Modifier.padding(end = 6.dp))
    }
    Group(stringResource(R.string.g_cell_size)) {
        BigBtn(stringResource(R.string.tbl_autofit), width = 84.dp, onClick = { state.uno(".uno:SetOptimalColumnWidth") }) { Glyph("↔", 22) }
        BigBtn(stringResource(R.string.tbl_fit_rows), width = 84.dp, onClick = { state.uno(".uno:SetOptimalRowHeight") }) { Glyph("↕", 22) }
        BigBtn(stringResource(R.string.tbl_even_cols), width = 84.dp, onClick = { state.uno(".uno:DistributeColumns") }) { Glyph("⫼", 20) }
        BigBtn(stringResource(R.string.tbl_even_rows), width = 84.dp, onClick = { state.uno(".uno:DistributeRows") }) { Glyph("≡", 22) }
    }
    Group(stringResource(R.string.g_cell_align)) {
        BigBtn(stringResource(R.string.tbl_valign_top), width = 64.dp, onClick = { state.uno(".uno:CellVertTop") }) { Glyph("⤒", 20) }
        BigBtn(stringResource(R.string.tbl_valign_center), width = 64.dp, onClick = { state.uno(".uno:CellVertCenter") }) { Glyph("↕", 20) }
        BigBtn(stringResource(R.string.tbl_valign_bottom), width = 64.dp, onClick = { state.uno(".uno:CellVertBottom") }) { Glyph("⤓", 20) }
    }
    Group(stringResource(R.string.g_table_borders)) {
        listOf(
            R.string.b_all to Edge.ALL, R.string.b_outside to Edge.OUTSIDE, R.string.b_inside to Edge.INSIDE, R.string.b_none to Edge.NONE,
            R.string.b_top to Edge.TOP, R.string.b_bottom to Edge.BOTTOM, R.string.b_left to Edge.LEFT, R.string.b_right to Edge.RIGHT,
            R.string.b_inside_h to Edge.INSIDE_H, R.string.b_inside_v to Edge.INSIDE_V,
        ).forEach { (label, edge) ->
            BigBtn(stringResource(label), width = 62.dp, onClick = { state.onTableOp?.invoke(TableOp(edge = edge, line = state.borderLine)) }) {
                Ic(26.dp) { borderIcon(it, edge) }
            }
        }
        MenuBigBtn(stringResource(R.string.b_style), { Glyph("┄", 22) }) { close ->
            listOf(R.string.ls_single to "single", R.string.ls_dashed to "dashed", R.string.ls_dotted to "dotted", R.string.ls_double to "double")
                .forEach { (l, v) -> DropdownMenuItem({ Text(stringResource(l)) }, { close(); state.borderLine = state.borderLine.copy(style = v) }) }
        }
        MenuBigBtn(stringResource(R.string.b_width), { Glyph("━", 22) }) { close ->
            listOf("0,5 pt" to 4, "1 pt" to 8, "1,5 pt" to 12, "2,25 pt" to 18, "3 pt" to 24)
                .forEach { (l, v) -> DropdownMenuItem({ Text(l) }, { close(); state.borderLine = state.borderLine.copy(size = v) }) }
        }
        MenuBigBtn(stringResource(R.string.b_color), { Box(Modifier.size(22.dp).clip(CircleShape).background(Color(0xFF000000 or state.borderLine.color.toLong(16)))) }) { close ->
            Row(Modifier.padding(horizontal = 8.dp)) {
                listOf("000000", "595959", "A6A6A6", "C00000", "ED7D31", "FFC000", "70AD47", "2F5597", "7030A0").forEach { c ->
                    Box(
                        Modifier.padding(2.dp).size(30.dp).clip(CircleShape).background(Color(0xFF000000 or c.toLong(16)))
                            .clickable { close(); state.borderLine = state.borderLine.copy(color = c) },
                    )
                }
            }
        }
    }
    Group(stringResource(R.string.g_table_styles)) {
        TableLooks.all.forEach { look ->
            val label = when (look.id) {
                "grid" -> R.string.tl_grid; "grid_gray" -> R.string.tl_grid_gray; "blue" -> R.string.tl_blue; "green" -> R.string.tl_green
                "orange" -> R.string.tl_orange; "striped" -> R.string.tl_striped; "minimal" -> R.string.tl_minimal; else -> R.string.tl_dark
            }
            BigBtn(stringResource(label), width = 76.dp, onClick = { state.onTableOp?.invoke(TableOp(look = look)) }) {
                Canvas(Modifier.size(width = 56.dp, height = 32.dp)) { drawLookThumb(look) }
            }
        }
    }
    Group(stringResource(R.string.g_table_delete)) {
        BigBtn(stringResource(R.string.tbl_delete_row), width = 76.dp, onClick = { state.uno(".uno:DeleteRows") }) { Glyph("−≡", 18) }
        BigBtn(stringResource(R.string.tbl_delete_col), width = 76.dp, onClick = { state.uno(".uno:DeleteColumns") }) { Glyph("−⫼", 18) }
        BigBtn(stringResource(R.string.tbl_delete_table), width = 76.dp, onClick = { state.uno(".uno:DeleteTable") }) { Glyph("🗑", 20) }
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
    Group(stringResource(R.string.g_arrange)) {
        BigBtn(stringResource(R.string.bring_front), width = 72.dp, onClick = { state.uno(".uno:BringToFront") }) { Glyph("⬒", 22) }
        BigBtn(stringResource(R.string.send_back), width = 72.dp, onClick = { state.uno(".uno:SendToBack") }) { Glyph("⬓", 22) }
        Column {
            Row {
                IconBtn(onClick = { state.uno(".uno:RotateLeft") }) { Glyph("↺", 20) }
                IconBtn(onClick = { state.uno(".uno:RotateRight") }) { Glyph("↻", 20) }
            }
            Row {
                IconBtn(onClick = { state.uno(".uno:FlipHorizontal") }) { Glyph("⇋", 20) }
                IconBtn(onClick = { state.uno(".uno:FlipVertical") }) { Glyph("⇅", 20) }
            }
        }
    }
    Group(stringResource(R.string.g_anchor)) {
        BigBtn(stringResource(R.string.anchor_para), width = 72.dp, onClick = { state.uno(".uno:SetAnchorToPara") }) { Glyph("⚓", 20) }
        BigBtn(stringResource(R.string.anchor_page), width = 72.dp, onClick = { state.uno(".uno:SetAnchorToPage") }) { Glyph("▭", 22) }
    }
    if (!state.graphicIsImage) {
        Group(stringResource(R.string.g_shape_style)) {
            ColorBtn(isHighlight = true, allowNone = false) { state.uno(".uno:FillColor", args(arg("FillColor", "long", "$it"))) }
            Text(stringResource(R.string.shape_fill), fontSize = 11.sp, modifier = Modifier.padding(end = 6.dp))
            ColorBtn(isHighlight = false, allowNone = false) { state.uno(".uno:XLineColor", args(arg("XLineColor", "long", "$it"))) }
            Text(stringResource(R.string.shape_outline), fontSize = 11.sp, modifier = Modifier.padding(end = 6.dp))
        }
    }
    Group(stringResource(R.string.g_edit_picture), last = true) {
        BigBtn(stringResource(R.string.cut), onClick = { state.uno(".uno:Cut") }) { Glyph("✂", 20) }
        BigBtn(stringResource(R.string.copy), onClick = { state.uno(".uno:Copy") }) { Ic(26.dp) { copyIcon(it) } }
        BigBtn(stringResource(R.string.delete), onClick = { state.uno(".uno:Delete") }) { Glyph("🗑", 20) }
    }
}

@Composable
private fun FindBar(state: DocumentViewState) {
    var text by remember { mutableStateOf("") }
    var repl by remember { mutableStateOf("") }
    var withReplace by remember { mutableStateOf(false) }
    fun esc(v: String) = v.replace("\\", "\\\\").replace("\"", "\\\"")
    // SearchItem.Command: 0 = find, 2 = replace, 3 = replace all
    fun run(command: Int, backward: Boolean = false) {
        if (text.isEmpty()) return
        state.uno(
            ".uno:ExecuteSearch",
            args(
                arg("SearchItem.SearchString", "string", esc(text)),
                arg("SearchItem.ReplaceString", "string", esc(repl)),
                arg("SearchItem.Command", "long", "$command"),
                arg("SearchItem.Backward", "boolean", "$backward"),
            ),
        )
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SearchField(text, stringResource(R.string.find), { text = it }, { run(0) }, Modifier.weight(1f))
            IconBtn(onClick = { run(0, backward = true) }) { Glyph("▲", 14) }
            IconBtn(onClick = { run(0) }) { Glyph("▼", 14) }
            IconBtn(active = withReplace, onClick = { withReplace = !withReplace }) { Glyph("⇄", 16) }
            IconBtn(onClick = { state.finding = false }) { Glyph("✕", 16) }
        }
        if (withReplace) {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                SearchField(repl, stringResource(R.string.replace_with), { repl = it }, { run(2) }, Modifier.weight(1f))
                IconBtn(wide = true, onClick = { run(2) }) { Text(stringResource(R.string.replace), fontSize = 13.sp) }
                IconBtn(wide = true, onClick = { run(3) }) { Text(stringResource(R.string.replace_all), fontSize = 13.sp) }
            }
        }
    }
}

@Composable
private fun SearchField(value: String, hint: String, onChange: (String) -> Unit, onGo: () -> Unit, modifier: Modifier) {
    Box(
        modifier.height(40.dp).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onGo() }),
            modifier = Modifier.fillMaxWidth(),
        )
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

/** Word-style combo box: type a value and press Done, or open the (scrollable) list. */
@Composable
private fun Combo(
    value: String,
    width: Dp,
    items: List<String>,
    numeric: Boolean = false,
    state: DocumentViewState,
    onCommit: (String) -> Unit,
) {
    var field by remember(value) { mutableStateOf(TextFieldValue(value)) }
    var open by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    // the tap that focuses the field also places the caret; select everything afterwards, like Word
    LaunchedEffect(focused) {
        if (focused) {
            kotlinx.coroutines.delay(120)
            field = field.copy(selection = androidx.compose.ui.text.TextRange(0, field.text.length))
        }
    }
    fun commit(v: String) {
        val t = v.trim()
        if (t.isNotEmpty()) onCommit(t)
        focus.clearFocus()
        state.focusTick++ // hand the keyboard back to the document
    }
    Box(Modifier.padding(horizontal = 2.dp)) {
        Row(
            Modifier.width(width).height(RowH - 4.dp)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                .clip(RoundedCornerShape(4.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.text.BasicTextField(
                value = field,
                onValueChange = { field = it },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = if (numeric) androidx.compose.ui.text.input.KeyboardType.Decimal else androidx.compose.ui.text.input.KeyboardType.Text,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { commit(field.text) }),
                modifier = Modifier.weight(1f).padding(start = 6.dp).androidx_onFocus(
                    onFocus = { focused = true },
                    onBlur = { focused = false; field = TextFieldValue(value) },
                ),
            )
            Box(Modifier.fillMaxHeight().clickable { open = true }.padding(horizontal = 7.dp), contentAlignment = Alignment.Center) {
                Text("▾", fontSize = 11.sp)
            }
        }
        DropdownMenu(open, { open = false }, modifier = Modifier.heightIn(max = 360.dp)) {
            items.forEach {
                if (it.startsWith("#")) {
                    DropdownMenuItem(
                        { Text(it.drop(1), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) },
                        {}, enabled = false,
                    )
                } else {
                    DropdownMenuItem({ Text(it) }, { open = false; commit(it) })
                }
            }
        }
    }
}

private fun Modifier.androidx_onFocus(onFocus: () -> Unit, onBlur: () -> Unit): Modifier =
    this.onFocusChanged { if (it.isFocused) onFocus() else onBlur() }

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
        DropdownMenu(open, { open = false }, modifier = Modifier.heightIn(max = 360.dp)) {
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
        DropdownMenu(open, { open = false }, modifier = Modifier.heightIn(max = 360.dp)) {
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

/** Big ribbon button that opens a dropdown menu. */
@Composable
private fun MenuBigBtn(label: String, icon: @Composable () -> Unit, items: @Composable (close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        BigBtn(label, onClick = { open = true }, icon = icon)
        DropdownMenu(open, { open = false }, modifier = Modifier.heightIn(max = 360.dp)) { items { open = false } }
    }
}

@Composable
private fun MarginsDialog(onDismiss: () -> Unit, onOk: (IntArray) -> Unit) {
    var top by remember { mutableStateOf("2,54") }
    var bottom by remember { mutableStateOf("2,54") }
    var left by remember { mutableStateOf("2,54") }
    var right by remember { mutableStateOf("2,54") }
    fun v(t: String) = t.replace(',', '.').toDoubleOrNull()?.coerceIn(0.0, 20.0) ?: 2.54
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.margins_custom)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.OutlinedTextField(top, { top = it }, label = { Text(stringResource(R.string.margin_top)) }, singleLine = true, modifier = Modifier.weight(1f))
                    androidx.compose.material3.OutlinedTextField(bottom, { bottom = it }, label = { Text(stringResource(R.string.margin_bottom)) }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.OutlinedTextField(left, { left = it }, label = { Text(stringResource(R.string.margin_left)) }, singleLine = true, modifier = Modifier.weight(1f))
                    androidx.compose.material3.OutlinedTextField(right, { right = it }, label = { Text(stringResource(R.string.margin_right)) }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Text(stringResource(R.string.unit_cm), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton({
                onDismiss()
                onOk(intArrayOf(PageSetup.cm(v(top)), PageSetup.cm(v(right)), PageSetup.cm(v(bottom)), PageSetup.cm(v(left))))
            }) { Text(stringResource(R.string.insert_ok)) }
        },
        dismissButton = { androidx.compose.material3.TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Word's "Aa" menu. */
@Composable
private fun CaseMenu(state: DocumentViewState) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconBtn(onClick = { open = true }) { Glyph("Aa", 15) }
        DropdownMenu(open, { open = false }) {
            listOf(
                R.string.case_sentence to ".uno:ChangeCaseSentenceCase",
                R.string.case_lower to ".uno:ChangeCaseToLower",
                R.string.case_upper to ".uno:ChangeCaseToUpper",
                R.string.case_words to ".uno:ChangeCaseCapitalizeEveryWord",
                R.string.case_toggle to ".uno:ChangeCaseToggleCase",
            ).forEach { (label, cmd) ->
                DropdownMenuItem({ Text(stringResource(label)) }, { open = false; state.charFormat(cmd) })
            }
        }
    }
}

private val Symbols = listOf(
    "©", "®", "™", "€", "£", "¥", "§", "¶", "°", "±", "×", "÷", "≠", "≈", "≤", "≥", "∞", "√",
    "→", "←", "↑", "↓", "↔", "⇒", "✓", "✗", "★", "☆", "♥", "♦", "•", "…", "–", "—", "«", "»",
    "α", "β", "γ", "δ", "π", "Σ", "Ω", "µ", "½", "¼", "¾", "²", "³", "‰", "†", "‡",
)

/** Shape ids as used by LibreOffice (.uno:Category.shape-id); each is inserted at default size in the middle of the view. */
private val ShapeGroups: List<Pair<Int, List<Pair<Int, String>>>> = listOf(
    R.string.shapes_basic to listOf(
        R.string.shape_rectangle to ".uno:BasicShapes.rectangle",
        R.string.shape_rounded to ".uno:BasicShapes.round-rectangle",
        R.string.shape_ellipse to ".uno:BasicShapes.ellipse",
        R.string.shape_triangle to ".uno:BasicShapes.isosceles-triangle",
        R.string.shape_diamond to ".uno:BasicShapes.diamond",
        R.string.shape_hexagon to ".uno:BasicShapes.hexagon",
        R.string.shape_cross to ".uno:BasicShapes.cross",
    ),
    R.string.shapes_arrows to listOf(
        R.string.shape_arrow_right to ".uno:ArrowShapes.right-arrow",
        R.string.shape_arrow_left to ".uno:ArrowShapes.left-arrow",
        R.string.shape_arrow_up to ".uno:ArrowShapes.up-arrow",
        R.string.shape_arrow_down to ".uno:ArrowShapes.down-arrow",
        R.string.shape_arrow_both to ".uno:ArrowShapes.left-right-arrow",
    ),
    R.string.shapes_stars to listOf(
        R.string.shape_star4 to ".uno:StarShapes.star4",
        R.string.shape_star5 to ".uno:StarShapes.star5",
        R.string.shape_star6 to ".uno:StarShapes.star6",
        R.string.shape_heart to ".uno:SymbolShapes.heart",
        R.string.shape_sun to ".uno:SymbolShapes.sun",
        R.string.shape_smiley to ".uno:SymbolShapes.smiley",
        R.string.shape_cloud to ".uno:SymbolShapes.cloud",
    ),
    R.string.shapes_flow to listOf(
        R.string.shape_process to ".uno:FlowChartShapes.flowchart-process",
        R.string.shape_decision to ".uno:FlowChartShapes.flowchart-decision",
        R.string.shape_terminator to ".uno:FlowChartShapes.flowchart-terminator",
        R.string.shape_data to ".uno:FlowChartShapes.flowchart-data",
        R.string.shape_document to ".uno:FlowChartShapes.flowchart-document",
    ),
    R.string.shapes_callouts to listOf(
        R.string.shape_callout to ".uno:CalloutShapes.round-rectangular-callout",
        R.string.shape_callout_round to ".uno:CalloutShapes.round-callout",
    ),
)

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

private fun DrawScope.marginsIcon(c: Color) {
    val w = size.width; val h = size.height; val sw = 1.6f * density
    drawRoundRect(c, Offset(w * 0.12f, h * 0.06f), Size(w * 0.76f, h * 0.88f), CornerRadius(2f * density), style = Stroke(sw))
    drawRect(c.copy(alpha = 0.45f), Offset(w * 0.28f, h * 0.22f), Size(w * 0.44f, h * 0.56f), style = Stroke(sw))
}

private fun DrawScope.orientationIcon(c: Color) {
    val w = size.width; val h = size.height; val sw = 1.6f * density
    drawRoundRect(c, Offset(w * 0.2f, h * 0.04f), Size(w * 0.36f, h * 0.6f), CornerRadius(2f * density), style = Stroke(sw))
    drawRoundRect(c, Offset(w * 0.34f, h * 0.5f), Size(w * 0.6f, h * 0.38f), CornerRadius(2f * density), style = Stroke(sw))
}

/** 3x3 cell grid, the lines belonging to [edge] drawn strong, the rest faint (Word's border presets). */
private fun DrawScope.borderIcon(c: Color, edge: Edge) {
    val w = size.width; val x0 = w * 0.1f; val x3 = w * 0.9f; val y0 = x0; val y3 = x3
    val xs = listOf(x0, x0 + (x3 - x0) / 3f, x0 + 2f * (x3 - x0) / 3f, x3)
    val faint = c.copy(alpha = 0.28f); val sw = 2f * density
    fun h(i: Int, col: Color) = drawLine(col, Offset(x0, xs[i]), Offset(x3, xs[i]), if (col == faint) 1f * density else sw, StrokeCap.Round)
    fun v(i: Int, col: Color) = drawLine(col, Offset(xs[i], y0), Offset(xs[i], y3), if (col == faint) 1f * density else sw, StrokeCap.Round)
    val on = when (edge) {
        Edge.ALL -> setOf("t", "b", "l", "r", "h1", "h2", "v1", "v2")
        Edge.OUTSIDE -> setOf("t", "b", "l", "r")
        Edge.INSIDE -> setOf("h1", "h2", "v1", "v2")
        Edge.INSIDE_H -> setOf("h1", "h2")
        Edge.INSIDE_V -> setOf("v1", "v2")
        Edge.TOP -> setOf("t"); Edge.BOTTOM -> setOf("b"); Edge.LEFT -> setOf("l"); Edge.RIGHT -> setOf("r")
        Edge.NONE -> emptySet()
    }
    h(0, if ("t" in on) c else faint); h(1, if ("h1" in on) c else faint); h(2, if ("h2" in on) c else faint); h(3, if ("b" in on) c else faint)
    v(0, if ("l" in on) c else faint); v(1, if ("v1" in on) c else faint); v(2, if ("v2" in on) c else faint); v(3, if ("r" in on) c else faint)
}

/** Small preview of a table look: header fill, banded rows and borders as they will be written. */
private fun DrawScope.drawLookThumb(look: TableLook) {
    fun col(hex: String) = Color(0xFF000000 or hex.toLong(16))
    val w = size.width; val h = size.height
    val rows = 4; val cols = 4
    val rh = h / rows; val cw = w / cols
    for (r in 0 until rows) {
        val fill = when {
            r == 0 && look.headerFill != null -> look.headerFill
            r != 0 && look.band != null && r % 2 == 0 -> look.band
            else -> null
        }
        if (fill != null) drawRect(col(fill), Offset(0f, r * rh), Size(w, rh))
    }
    fun line(l: BorderLine?, x0: Float, y0: Float, x1: Float, y1: Float) {
        if (l != null) drawLine(col(l.color), Offset(x0, y0), Offset(x1, y1), (l.size / 4f).coerceIn(1f, 2.5f) * density)
    }
    val e = look.edges
    line(e.top, 0f, 0f, w, 0f); line(e.bottom, 0f, h, w, h); line(e.left, 0f, 0f, 0f, h); line(e.right, w, 0f, w, h)
    for (r in 1 until rows) line(e.insideH, 0f, r * rh, w, r * rh)
    for (c in 1 until cols) line(e.insideV, c * cw, 0f, c * cw, h)
}

private fun DrawScope.shapesIcon(c: Color) {
    val w = size.width; val h = size.height; val sw = 1.6f * density
    drawRect(c, Offset(w * 0.06f, h * 0.5f), Size(w * 0.38f, h * 0.42f), style = Stroke(sw))
    drawCircle(c, w * 0.2f, Offset(w * 0.68f, h * 0.3f), style = Stroke(sw))
    drawPath(Path().apply { moveTo(w * 0.7f, h * 0.55f); lineTo(w * 0.94f, h * 0.92f); lineTo(w * 0.5f, h * 0.92f); close() }, c, style = Stroke(sw))
}
