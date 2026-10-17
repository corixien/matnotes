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
    recents: List<RecentFile>,
    error: String?,
    onNew: () -> Unit,
    onOpen: () -> Unit,
    onOpenRecent: (RecentFile) -> Unit,
    onRemoveRecent: (RecentFile) -> Unit,
) {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp)) {
        Text(
            "MatNotes",
            Modifier.padding(top = 28.dp),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
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
        Text(
            stringResource(R.string.recent),
            Modifier.padding(top = 28.dp, bottom = 4.dp),
            style = MaterialTheme.typography.titleMedium,
        )
        if (recents.isEmpty()) {
            Text(
                stringResource(R.string.no_recent),
                Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val fmt = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
            LazyColumn {
                items(recents, key = { it.uri }) { r ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onOpenRecent(r) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center,
                        ) { Text("W", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp) }
                        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                            Text(r.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                fmt.format(Date(r.time)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "✕",
                            Modifier.clip(CircleShape).clickable { onRemoveRecent(r) }.padding(12.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
    }
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
    val onExportPdf: () -> Unit,
    val onShareDoc: () -> Unit,
    val onPickImage: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
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
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))) {
        TopAppBar(
            title = { Text((if (state.dirty) "• " else "") + title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { TextButton(actions.onBack) { Text("‹ " + stringResource(R.string.back)) } },
            actions = {
                if (state.editing) {
                    TextButton(actions.onSave) { Text(stringResource(R.string.save)) }
                    TextButton({ state.editing = false; state.wantKeyboard = false }) { Text(stringResource(R.string.done)) }
                }
                var menu by remember { mutableStateOf(false) }
                TextButton({ menu = true }) { Text("⋮") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text(stringResource(R.string.save)) }, { menu = false; actions.onSave() })
                    DropdownMenuItem({ Text(stringResource(R.string.save_as)) }, { menu = false; actions.onSaveAs() })
                    actions.onOverwrite?.let { ow ->
                        DropdownMenuItem({ Text(stringResource(R.string.overwrite_original)) }, { menu = false; ow() })
                    }
                    HorizontalDivider()
                    DropdownMenuItem({ Text(stringResource(R.string.print)) }, { menu = false; actions.onPrint() })
                    DropdownMenuItem({ Text(stringResource(R.string.export_pdf)) }, { menu = false; actions.onExportPdf() })
                    DropdownMenuItem({ Text(stringResource(R.string.share_docx)) }, { menu = false; actions.onShareDoc() })
                }
            },
        )
        Box(Modifier.weight(1f)) {
            DocumentView(state)
            AndroidView(
                factory = { KeyInputView(it).also { v -> input = v } },
                update = { it.target = state },
                modifier = Modifier.size(1.dp),
            )
            state.tick
            FastScroller(state, Modifier.align(Alignment.CenterEnd))
            if (state.editing) SelectionToolbar(state)
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
                ExtendedFloatingActionButton(
                    onClick = { state.editing = true },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                ) { Text(stringResource(R.string.edit)) }
            }
            if (busy) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        if (state.editing) Ribbon(state, actions.onPickImage)
    }
}

/** Floating cut/copy/paste bar above the text selection, like Word on mobile. */
@Composable
private fun SelectionToolbar(state: DocumentViewState) {
    if (state.selection.isEmpty() || state.graphic != null) return
    val anchor: Offset = state.selectionAnchor() ?: return
    val density = LocalDensity.current
    val barW = 300.dp
    val barH = 44.dp
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
                    R.string.select_all to ".uno:SelectAll",
                ).forEach { (label, cmd) ->
                    Text(
                        stringResource(label),
                        Modifier.weight(1f).fillMaxSize().clickable { state.uno(cmd) }.padding(top = 12.dp),
                        fontSize = 13.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
