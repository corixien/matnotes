package com.matnotes.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

private const val TAG = "MatNotes"

private class OpenDoc(val doc: LoDocument, val title: String, val state: DocumentViewState)

class MainActivity : ComponentActivity() {
    private lateinit var recents: Recents
    private var current by mutableStateOf<OpenDoc?>(null)
    private var loading by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var recentList by mutableStateOf<List<RecentFile>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        recents = Recents(this)
        recentList = recents.list()
        File(cacheDir, "open").deleteRecursively()
        File(cacheDir, "share").deleteRecursively()
        setContent {
            MatNotesTheme {
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri != null) {
                        runCatching {
                            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        openUri(uri)
                    }
                }
                val cur = current
                BackHandler(enabled = cur != null) { closeDocument() }
                when {
                    loading -> LoadingScreen()
                    cur != null -> ViewerScreen(
                        title = cur.title,
                        state = cur.state,
                        pageCount = cur.doc.pages.size,
                        busy = busy,
                        onBack = ::closeDocument,
                        onExportPdf = { export("pdf", "application/pdf") },
                        onShareDoc = { export("docx", DOCX_MIME) },
                    )
                    else -> HomeScreen(
                        recents = recentList,
                        error = error,
                        onOpen = { picker.launch(arrayOf("*/*")) },
                        onOpenRecent = { openUri(Uri.parse(it.uri)) },
                    )
                }
            }
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        val uri = when (i?.action) {
            Intent.ACTION_VIEW, Intent.ACTION_EDIT -> i.data
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            else -> null
        }
        if (uri != null) {
            i?.action = null // do not reopen after rotation / recreate
            openUri(uri)
        }
    }

    private fun openUri(uri: Uri) {
        loading = true
        error = null
        lifecycleScope.launch {
            try {
                val name = withContext(Dispatchers.IO) { displayName(uri) }
                val file = withContext(Dispatchers.IO) { copyToCache(uri, name) }
                val doc = Engine.open(this@MainActivity, file)
                if (doc == null) {
                    error = getString(R.string.open_failed)
                } else {
                    current?.let { closeDocument() }
                    current = OpenDoc(doc, name, DocumentViewState(doc, lifecycleScope))
                    recents.add(uri.toString(), name)
                    recentList = recents.list()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "open failed", e)
                error = getString(R.string.open_failed)
            } finally {
                loading = false
            }
        }
    }

    private fun closeDocument() {
        val c = current ?: return
        current = null
        lifecycleScope.launch(Engine.dispatcher) { c.doc.close() }
        recentList = recents.list()
    }

    private fun export(format: String, mime: String) {
        val c = current ?: return
        busy = true
        lifecycleScope.launch {
            val out = withContext(Engine.dispatcher) {
                val dir = File(cacheDir, "share").apply { mkdirs() }
                val base = c.title.substringBeforeLast('.')
                val f = File(dir, "$base.$format")
                if (c.doc.saveAs(f, format)) f else null
            }
            busy = false
            if (out == null) {
                error = getString(R.string.export_failed)
                return@launch
            }
            val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", out)
            val send = Intent(Intent.ACTION_SEND)
                .setType(mime)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData = android.content.ClipData.newRawUri(c.title, uri)
            startActivity(Intent.createChooser(send, getString(R.string.share_via)))
        }
    }

    private fun displayName(uri: Uri): String {
        if (uri.scheme == "content") {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0)?.let { n -> return sanitize(n) }
            }
        }
        return sanitize(uri.lastPathSegment ?: "document.docx")
    }

    private fun sanitize(n: String) = n.substringAfterLast('/').replace(Regex("[\\\\:*?\"<>|]"), "_").ifBlank { "document.docx" }

    private fun copyToCache(uri: Uri, name: String): File {
        val dir = File(cacheDir, "open/${UUID.randomUUID()}").apply { mkdirs() }
        val f = File(dir, name)
        contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        return f
    }

    private companion object {
        const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    }
}
