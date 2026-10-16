package com.matnotes.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow

@Composable
fun HomeScreen(
    recents: List<RecentFile>,
    error: String?,
    onOpen: () -> Unit,
    onOpenRecent: (RecentFile) -> Unit,
) {
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp)) {
        Text("MatNotes", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.tagline), style = MaterialTheme.typography.bodyMedium)
        Button(onOpen, Modifier.padding(top = 24.dp).fillMaxWidth()) { Text(stringResource(R.string.open_document)) }
        if (error != null) {
            Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
        }
        Text(
            stringResource(R.string.recent),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 28.dp, bottom = 8.dp),
        )
        if (recents.isEmpty()) {
            Text(stringResource(R.string.no_recent), style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyColumn {
                items(recents, key = { it.uri }) { r ->
                    Text(
                        r.name,
                        Modifier.fillMaxWidth().clickable { onOpenRecent(r) }.padding(vertical = 14.dp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    title: String,
    state: DocumentViewState,
    pageCount: Int,
    busy: Boolean,
    onBack: () -> Unit,
    onExportPdf: () -> Unit,
    onShareDoc: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { TextButton(onBack) { Text("‹ " + stringResource(R.string.back)) } },
            actions = {
                var menu by remember { mutableStateOf(false) }
                TextButton({ menu = true }) { Text("⋮") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text(stringResource(R.string.export_pdf)) }, { menu = false; onExportPdf() })
                    DropdownMenuItem({ Text(stringResource(R.string.share_docx)) }, { menu = false; onShareDoc() })
                }
            },
        )
        Box(Modifier.weight(1f)) {
            DocumentView(state)
            Surface(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f),
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                    state.tick // refresh while scrolling
                    Text(
                        stringResource(R.string.page_of, state.currentPage(), pageCount),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            if (busy) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
    }
}
