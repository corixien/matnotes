package com.matnotes.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.text.DateFormat
import java.util.Date
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun HomeScreen(
    myDocs: List<StoredDoc>,
    recents: List<RecentFile>,
    error: String?,
    onNew: () -> Unit,
    onOpen: () -> Unit,
    onOpenStored: (StoredDoc) -> Unit,
    onOpenRecent: (RecentFile) -> Unit,
    onRemoveRecent: (RecentFile) -> Unit,
    onProfile: () -> Unit,
    sharedDocs: List<Pair<String, List<StoredDoc>>> = emptyList(),
    invitations: Int = 0,
) {
    val storedUris = remember(myDocs, sharedDocs) { (myDocs + sharedDocs.flatMap { it.second }).map { it.uri.toString() }.toSet() }
    val others = recents.filter { it.uri !in storedUris }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "MatNotes",
                Modifier.weight(1f),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            ProfileButton(invitations > 0, onProfile)
        }
        Text(
            stringResource(R.string.tagline),
            Modifier.padding(top = 4.dp, bottom = 24.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionCard(Modifier.weight(1f), "+", stringResource(R.string.new_document), onNew)
            ActionCard(Modifier.weight(1f), "▤", stringResource(R.string.open_document), onOpen)
        }
        if (error != null) {
            Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
        }
        val fmt = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
        LazyColumn(Modifier.padding(top = 12.dp)) {
            if (myDocs.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.my_documents)) }
                items(myDocs, key = { "s" + it.uri }) { d -> DocRow(d.name, fmt.format(Date(d.modified)), { onOpenStored(d) }, null) }
            }
            sharedDocs.forEach { (folderName, docs) ->
                item(key = "h$folderName") { SectionTitle(folderName) }
                items(docs, key = { "f" + it.uri }) { d -> DocRow(d.name, fmt.format(Date(d.modified)), { onOpenStored(d) }, null) }
            }
            item { SectionTitle(stringResource(R.string.recent)) }
            if (others.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.no_recent),
                        Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(others, key = { "r" + it.uri }) { r -> DocRow(r.name, fmt.format(Date(r.time)), { onOpenRecent(r) }, { onRemoveRecent(r) }) }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, Modifier.padding(top = 18.dp, bottom = 4.dp), style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun DocRow(name: String, subtitle: String, onClick: () -> Unit, onRemove: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) { Text("W", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp) }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onRemove != null) {
            Text(
                "✕",
                Modifier.clip(CircleShape).clickable(onClick = onRemove).padding(12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

@Composable
private fun ActionCard(modifier: Modifier, glyph: String, label: String, onClick: () -> Unit) {
    Surface(
        modifier.height(112.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(glyph, fontSize = 30.sp, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Light)
            Text(label, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator()
            Text(stringResource(R.string.loading))
        }
    }
}

class ViewerActions(
    val onBack: () -> Unit,
    val onSave: () -> Unit,
    val onSaveAs: () -> Unit,
    val onOverwrite: (() -> Unit)?,
    val onPrint: () -> Unit,
    val onShare: () -> Unit,
    val onPickImage: () -> Unit,
    val onPageSetup: (PageSetupChange) -> Unit,
)

@Composable
fun ViewerScreen(
    title: String,
    state: DocumentViewState,
    busy: Boolean,
    actions: ViewerActions,
) {
    var input by remember { mutableStateOf<KeyInputView?>(null) }
    LaunchedEffect(state.wantKeyboard, state.editing, input, state.focusTick) {
        val v = input ?: return@LaunchedEffect
        if (state.editing && state.wantKeyboard) v.showKeyboard() else v.hideKeyboard()
    }
    LaunchedEffect(state.editing) {
        if (!state.editing) state.spelling = false
        if (state.editing) { kotlinx.coroutines.delay(600); state.ensureCursor() }
    }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))) {
        TopBar(title, state, actions)
        Box(Modifier.weight(1f)) {
            DocumentView(state)
            AndroidView(
                factory = { KeyInputView(it).also { v -> input = v } },
                update = { it.target = state },
                modifier = Modifier.size(1.dp),
            )
            state.tick
            FastScroller(state, Modifier.align(Alignment.CenterEnd))
            if (state.editing) {
                if (!state.spelling) SelectionToolbar(state)
                GraphicToolbar(state)
            }
            ZoomIndicator(state, Modifier.align(Alignment.TopCenter).padding(top = 12.dp))
            if (!state.editing) {
                Surface(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f),
                ) {
                    Text(
                        stringResource(R.string.page_of, state.currentPage(), state.layout.pages.size),
                        Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            if (busy) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        if (state.editing) { if (state.spelling) SpellPanel(state) else Ribbon(state, actions.onPickImage, actions.onPageSetup) }
    }
}

/** Compact app bar: back, title with save state, undo/redo/save/done while editing, overflow menu. */
@Composable
private fun TopBar(title: String, state: DocumentViewState, actions: ViewerActions) {
    val f = state.format
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(60.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarButton(actions.onBack) { Text("←", fontSize = 22.sp) }
            Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Text(
                    if (state.dirty) stringResource(R.string.unsaved_changes_short) else stringResource(R.string.saved),
                    fontSize = 12.sp,
                    color = if (state.dirty) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (state.editing) {
                BarButton({ state.uno(".uno:Undo") }, enabled = f[".uno:Undo"] != "disabled") { Text("↶", fontSize = 21.sp) }
                BarButton({ state.uno(".uno:Redo") }, enabled = state.redoEnabled()) { Text("↷", fontSize = 21.sp) }
                BarButton(actions.onSave) { SaveIcon() }
                BarButton({ state.editing = false; state.wantKeyboard = false }) {
                    CheckIcon(MaterialTheme.colorScheme.primary)
                }
            } else {
                BarButton(actions.onSave) { SaveIcon() }
                BarButton({ state.editing = true }) { PencilIcon(MaterialTheme.colorScheme.primary) }
            }
            var menu by remember { mutableStateOf(false) }
            Box {
                BarButton({ menu = true }) { MoreIcon() }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text(stringResource(R.string.save)) }, { menu = false; actions.onSave() })
                    DropdownMenuItem({ Text(stringResource(R.string.save_as)) }, { menu = false; actions.onSaveAs() })
                    actions.onOverwrite?.let { ow ->
                        DropdownMenuItem({ Text(stringResource(R.string.overwrite_original)) }, { menu = false; ow() })
                    }
                    HorizontalDivider()
                    DropdownMenuItem({ Text(stringResource(R.string.share_export)) }, { menu = false; actions.onShare() })
                    DropdownMenuItem({ Text(stringResource(R.string.print)) }, { menu = false; actions.onPrint() })
                }
            }
        }
    }
}

@Composable
private fun BarButton(onClick: () -> Unit, enabled: Boolean = true, content: @Composable () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick)
            .then(if (enabled) Modifier else Modifier.alpha(0.35f)),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun PencilIcon(color: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
        val w = size.width
        val st = androidx.compose.ui.graphics.drawscope.Stroke(
            1.8.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round,
        )
        // outlined pencil pointing to the lower left
        val body = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.70f, w * 0.12f); lineTo(w * 0.88f, w * 0.30f); lineTo(w * 0.34f, w * 0.84f)
            lineTo(w * 0.10f, w * 0.90f); lineTo(w * 0.16f, w * 0.66f); close()
        }
        drawPath(body, color, style = st)
        drawLine(color, Offset(w * 0.58f, w * 0.24f), Offset(w * 0.76f, w * 0.42f), strokeWidth = st.width, cap = st.cap)
    }
}

@Composable
private fun CheckIcon(color: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
        val w = size.width
        val p = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.16f, w * 0.54f); lineTo(w * 0.40f, w * 0.78f); lineTo(w * 0.86f, w * 0.24f)
        }
        drawPath(p, color, style = androidx.compose.ui.graphics.drawscope.Stroke(
            1.8.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round,
        ))
    }
}

@Composable
private fun SaveIcon() {
    val c = MaterialTheme.colorScheme.onSurface
    androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
        val w = size.width; val sw = 1.8.dp.toPx()
        drawRoundRect(c, Offset(sw / 2, sw / 2), androidx.compose.ui.geometry.Size(w - sw, w - sw),
            androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
        drawRect(c, Offset(w * 0.28f, w * 0.1f), androidx.compose.ui.geometry.Size(w * 0.44f, w * 0.28f))
        drawRoundRect(c, Offset(w * 0.24f, w * 0.55f), androidx.compose.ui.geometry.Size(w * 0.52f, w * 0.3f),
            androidx.compose.ui.geometry.CornerRadius(1.5.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
    }
}

@Composable
private fun MoreIcon() {
    val c = MaterialTheme.colorScheme.onSurface
    androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
        for (i in 0..2) drawCircle(c, 2.2.dp.toPx(), Offset(size.width / 2, size.height * (0.2f + i * 0.3f)))
    }
}

/** Floating cut/copy/paste bar above the text selection, like Word on mobile. */
@Composable
private fun SelectionToolbar(state: DocumentViewState) {
    if (state.selection.isEmpty() || state.graphic != null) return
    val anchor: Offset = state.selectionAnchor() ?: return
    val density = LocalDensity.current
    val barW = 360.dp
    val barH = 36.dp
    val viewW = state.viewSize.width
    with(density) {
        val x = (anchor.x - barW.toPx() / 2f).roundToInt().coerceIn(8.dp.roundToPx(), max(8.dp.roundToPx(), viewW - barW.roundToPx() - 8.dp.roundToPx()))
        // above the selection; below it when there is no room at the top
        val above = anchor.y - barH.toPx() - 14.dp.toPx()
        val y = if (above > 4.dp.toPx()) above else state.selectionBottom() + 34.dp.toPx()
        Surface(
            Modifier.offset { IntOffset(x, y.roundToInt()) }.width(barW).height(barH),
            shape = RoundedCornerShape(10.dp),
            shadowElevation = 6.dp,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                listOf(
                    R.string.cut to ".uno:Cut",
                    R.string.copy to ".uno:Copy",
                    R.string.paste to ".uno:Paste",
                    R.string.select_all_short to ".uno:SelectAll",
                ).forEach { (label, cmd) ->
                    Box(
                        Modifier.weight(1f).fillMaxSize().clickable {
                            when (cmd) {
                                ".uno:Cut" -> state.copy(cut = true)
                                ".uno:Copy" -> state.copy()
                                ".uno:Paste" -> state.paste()
                                else -> state.uno(cmd)
                            }
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(label),
                            fontSize = 12.sp,
                            maxLines = 1,
                            softWrap = false,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** Floating bar over the selected image/shape (long press or tap): quick actions, "more" opens the Picture tab. */
@Composable
private fun GraphicToolbar(state: DocumentViewState) {
    val anchor = state.graphicAnchor() ?: return
    val density = LocalDensity.current
    val barW = 360.dp
    val barH = 36.dp
    with(density) {
        val x = (anchor.x - barW.toPx() / 2f).roundToInt()
            .coerceIn(8.dp.roundToPx(), max(8.dp.roundToPx(), state.viewSize.width - barW.roundToPx() - 8.dp.roundToPx()))
        val above = anchor.y - barH.toPx() - 16.dp.toPx()
        val y = if (above > 4.dp.toPx()) above else state.graphicBottom() + 16.dp.toPx()
        Surface(
            Modifier.offset { IntOffset(x, y.roundToInt()) }.width(barW).height(barH),
            shape = RoundedCornerShape(10.dp),
            shadowElevation = 6.dp,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                listOf(
                    R.string.cut to { state.copy(cut = true) },
                    R.string.copy to { state.copy() },
                    R.string.rotate to { state.uno(".uno:RotateRight") },
                    R.string.delete to { state.uno(".uno:Delete") },
                ).forEach { (label, act) ->
                    Box(Modifier.weight(1f).fillMaxSize().clickable { act() }, contentAlignment = Alignment.Center) {
                        Text(stringResource(label), fontSize = 12.sp, maxLines = 1, softWrap = false, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Shows the zoom (100 % = real paper size) briefly while pinching, like Word. */
@Composable
private fun ZoomIndicator(state: DocumentViewState, modifier: Modifier) {
    val xdpi = LocalContext.current.resources.displayMetrics.xdpi
    var visible by remember { mutableStateOf(false) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(state.zoom) {
        if (first) { first = false; return@LaunchedEffect }
        visible = true
        kotlinx.coroutines.delay(1100)
        visible = false
    }
    if (visible) {
        Surface(modifier, shape = RoundedCornerShape(50), color = Color(0xCC202124)) {
            Text(
                "${state.zoomPercent(xdpi)} %",
                Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/** Word-style fast scroll handle on the right edge: appears while scrolling, drag it to jump through the document. */
@Composable
private fun FastScroller(state: DocumentViewState, modifier: Modifier) {
    var show by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(state.offset.y, dragging) {
        show = true
        if (!dragging) {
            kotlinx.coroutines.delay(1400)
            show = false
        }
    }
    if (state.scrollRange <= 0f || !(show || dragging)) return
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier
            .fillMaxHeight()
            .width(40.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, _ ->
                    change.consume()
                    state.scrollToFraction(change.position.y / size.height)
                }
            },
    ) {
        val thumbH = 56.dp
        val travel = maxHeight - thumbH
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = (-4).dp, y = travel * state.scrollFraction())
                .width(if (dragging) 10.dp else 6.dp)
                .height(thumbH)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = if (dragging) 0.95f else 0.6f)),
        )
    }
}

/** Pick the export format, then share via the system sheet or attach it to a new e-mail. */
@Composable
fun ShareDialog(onDismiss: () -> Unit, onShare: (format: String, email: Boolean) -> Unit) {
    var fmt by remember { mutableStateOf("docx") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.share_export)) },
        text = {
            Column {
                listOf("docx" to R.string.fmt_docx, "odt" to R.string.fmt_odt, "pdf" to R.string.fmt_pdf).forEach { (k, label) ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { fmt = k }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.RadioButton(selected = fmt == k, onClick = { fmt = k })
                        Text(stringResource(label))
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton({ onDismiss(); onShare(fmt, true) }) { Text(stringResource(R.string.share_email)) }
                TextButton({ onDismiss(); onShare(fmt, false) }) { Text(stringResource(R.string.share_system)) }
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** First save of a new document: choose the file name (stored in Documents/MatNotes). */
@Composable
fun NameDialog(initial: String, choices: List<Pair<String, String>>, onDismiss: () -> Unit, onOk: (name: String, dir: String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    var dir by remember { mutableStateOf(choices.firstOrNull()?.second ?: "") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.save_document_title)) },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.file_name)) })
                if (choices.size > 1) {
                    Text(stringResource(R.string.share_in_folder), Modifier.padding(top = 12.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        choices.forEach { (label, d) ->
                            androidx.compose.material3.FilterChip(selected = dir == d, onClick = { dir = d }, label = { Text(label) })
                        }
                    }
                }
                Text(
                    stringResource(R.string.saved_in_folder),
                    Modifier.padding(top = 8.dp),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton({ onDismiss(); onOk(name.trim().ifEmpty { initial }, dir) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
