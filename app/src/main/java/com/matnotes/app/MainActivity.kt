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
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

private const val TAG = "MatNotes"

/** [source] is the Uri the document was opened from (null for new documents). */
private class OpenDoc(val doc: LoDocument, val title: String, val state: DocumentViewState, val source: Uri?)

class MainActivity : ComponentActivity() {
    private lateinit var recents: Recents
    private var current by mutableStateOf<OpenDoc?>(null)
    private var loading by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var recentList by mutableStateOf<List<RecentFile>>(emptyList())
    private var saveUri by mutableStateOf<Uri?>(null)
    private var closeAfterSave = false
    private var confirmClose by mutableStateOf(false)
    private var confirmOverwrite by mutableStateOf(false)

    private val createDoc = registerForActivityResult(ActivityResultContracts.CreateDocument(DOCX_MIME)) { uri ->
        if (uri != null) { saveUri = uri; doSave() } else closeAfterSave = false
    }
    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) insertImage(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        recents = Recents(this)
        recentList = recents.list()
        File(cacheDir, "open").deleteRecursively()
        File(cacheDir, "share").deleteRecursively()
        setContent {
            MatNotesTheme {
                // ask for write access too, so 'overwrite original' works when the provider allows it
                val pickContract = remember {
                    object : ActivityResultContracts.OpenDocument() {
                        override fun createIntent(context: android.content.Context, input: Array<String>) =
                            super.createIntent(context, input).addFlags(
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
                            )
                    }
                }
                val picker = rememberLauncherForActivityResult(pickContract) { uri ->
                    if (uri != null) {
                        val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        runCatching { contentResolver.takePersistableUriPermission(uri, rw) }.onFailure {
                            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                        }
                        openUri(uri)
                    }
                }
                val cur = current
                BackHandler(enabled = cur != null) {
                    if (cur?.state?.editing == true && cur.state.wantKeyboard) cur.state.wantKeyboard = false
                    else requestClose()
                }
                if (confirmClose) {
                    AlertDialog(
                        onDismissRequest = { confirmClose = false },
                        title = { Text(stringResource(R.string.unsaved_title)) },
                        text = { Text(stringResource(R.string.unsaved_msg)) },
                        confirmButton = {
                            TextButton({ confirmClose = false; closeAfterSave = true; save() }) { Text(stringResource(R.string.save)) }
                        },
                        dismissButton = {
                            Row {
                                TextButton({ confirmClose = false }) { Text(stringResource(R.string.cancel)) }
                                TextButton({ confirmClose = false; closeDocument() }) { Text(stringResource(R.string.discard)) }
                            }
                        },
                    )
                }
                if (confirmOverwrite) {
                    AlertDialog(
                        onDismissRequest = { confirmOverwrite = false },
                        title = { Text(stringResource(R.string.overwrite_title)) },
                        text = { Text(stringResource(R.string.overwrite_msg)) },
                        confirmButton = {
                            TextButton({ confirmOverwrite = false; overwriteOriginal() }) { Text(stringResource(R.string.overwrite_ok)) }
                        },
                        dismissButton = { TextButton({ confirmOverwrite = false }) { Text(stringResource(R.string.cancel)) } },
                    )
                }
                when {
                    loading -> LoadingScreen()
                    cur != null -> ViewerScreen(
                        title = cur.title,
                        state = cur.state,
                        busy = busy,
                        actions = ViewerActions(
                            onBack = ::requestClose,
                            onSave = ::save,
                            onSaveAs = { saveUri = null; save() },
                            onOverwrite = if (cur.source != null && cur.source.scheme == "content") ({ confirmOverwrite = true }) else null,
                            onPrint = ::printDocument,
                            onExportPdf = { export("pdf", "application/pdf") },
                            onShareDoc = { export("docx", DOCX_MIME) },
                            onPickImage = { pickImage.launch("image/*") },
                        ),
                    )
                    else -> HomeScreen(
                        recents = recentList,
                        error = error,
                        onNew = ::newDocument,
                        onOpen = { picker.launch(arrayOf("*/*")) },
                        onOpenRecent = { openUri(Uri.parse(it.uri)) },
                        onRemoveRecent = { recents.remove(it.uri); recentList = recents.list() },
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
        if (BuildConfig.DEBUG) {
            // adb test hooks: --es uno <cmd> [--es unoargs <json>] ; --es insert_image <path in app files dir>
            i?.getStringExtra("zoom")?.toFloatOrNull()?.let { f -> current?.state?.zoomBy(f); return }
            i?.getStringExtra("link")?.let { l ->
                val (t, u) = l.split('|'); current?.state?.insertLink(t, u); return
            }
            i?.getStringExtra("uno")?.let { cmd ->
                current?.state?.uno(cmd, i.getStringExtra("unoargs").orEmpty())
                return
            }
            i?.getStringExtra("insert_image")?.let { p ->
                current?.state?.uno(
                    ".uno:InsertGraphic",
                    "{\"FileName\":{\"type\":\"string\",\"value\":\"file://$p\"},\"AsLink\":{\"type\":\"boolean\",\"value\":\"false\"}}",
                )
                return
            }
        }
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
                    saveUri = null
                    current = OpenDoc(
                        doc, name,
                        DocumentViewState(doc, lifecycleScope, resources.displayMetrics.density).also { it.onSave = ::save },
                        uri,
                    )
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

    /** Blank A4 document with Word's defaults (Calibri 11 pt, 2.54 cm margins, 8 pt paragraph spacing). */
    private fun newDocument() {
        loading = true
        error = null
        lifecycleScope.launch {
            try {
                val name = getString(R.string.new_doc_name)
                val file = withContext(Dispatchers.IO) {
                    val dir = File(cacheDir, "open/${UUID.randomUUID()}").apply { mkdirs() }
                    File(dir, name).also { f ->
                        resources.openRawResource(R.raw.blank).use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                    }
                }
                val doc = Engine.open(this@MainActivity, file)
                if (doc == null) {
                    error = getString(R.string.open_failed)
                } else {
                    current?.let { closeDocument() }
                    saveUri = null
                    val st = DocumentViewState(doc, lifecycleScope, resources.displayMetrics.density).also { it.onSave = ::save }
                    current = OpenDoc(doc, name, st, null)
                    st.editing = true
                    st.wantKeyboard = true
                }
            } catch (e: Throwable) {
                Log.e(TAG, "new document failed", e)
                error = getString(R.string.open_failed)
            } finally {
                loading = false
            }
        }
    }

    /** Explicit, confirmed write back to the file the document was opened from. */
    private fun overwriteOriginal() {
        val c = current ?: return
        saveUri = c.source ?: return
        doSave()
    }

    private fun printDocument() {
        val c = current ?: return
        busy = true
        lifecycleScope.launch {
            val out = withContext(Engine.dispatcher) {
                val dir = File(cacheDir, "share").apply { mkdirs() }
                val f = File(dir, c.title.substringBeforeLast('.') + ".pdf")
                if (c.doc.saveAs(f, "pdf")) f else null
            }
            busy = false
            if (out == null) {
                android.widget.Toast.makeText(this@MainActivity, R.string.export_failed, android.widget.Toast.LENGTH_LONG).show()
                return@launch
            }
            val pm = getSystemService(PRINT_SERVICE) as android.print.PrintManager
            pm.print(c.title, PdfPrintAdapter(out, c.title), null)
        }
    }

    private fun closeDocument() {
        val c = current ?: return
        c.state.editing = false
        current = null
        lifecycleScope.launch(Engine.dispatcher) { c.doc.close() }
        recentList = recents.list()
    }

    private fun requestClose() {
        val c = current ?: return
        if (c.state.dirty) confirmClose = true else closeDocument()
    }

    /** First save asks for a target (a new copy); the original is never overwritten implicitly. */
    private fun save() {
        val c = current ?: return
        if (saveUri == null) {
            createDoc.launch(c.title.substringBeforeLast('.') + " (MatNotes).docx")
        } else {
            doSave()
        }
    }

    private fun doSave() {
        val c = current ?: return
        val target = saveUri ?: return
        busy = true
        lifecycleScope.launch {
            val ok = try {
                val tmp = withContext(Engine.dispatcher) {
                    val f = File(File(cacheDir, "share").apply { mkdirs() }, "save.docx")
                    if (c.doc.saveAs(f, "docx")) f else null
                }
                if (tmp == null) false else withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(target, "wt")!!.use { o -> tmp.inputStream().use { it.copyTo(o) } }
                    true
                }
            } catch (e: Throwable) {
                Log.e(TAG, "save failed", e)
                false
            }
            busy = false
            if (ok) {
                c.state.dirty = false
                android.widget.Toast.makeText(this@MainActivity, R.string.saved, android.widget.Toast.LENGTH_SHORT).show()
                if (closeAfterSave) { closeAfterSave = false; closeDocument() }
            } else {
                closeAfterSave = false
                android.widget.Toast.makeText(this@MainActivity, R.string.save_failed, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun insertImage(uri: Uri) {
        val c = current ?: return
        lifecycleScope.launch {
            val f = withContext(Dispatchers.IO) {
                val dir = File(cacheDir, "open/img").apply { mkdirs() }
                val out = File(dir, "img_${System.currentTimeMillis()}.${imageExt(uri)}")
                contentResolver.openInputStream(uri)!!.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                out
            }
            c.state.uno(
                ".uno:InsertGraphic",
                "{\"FileName\":{\"type\":\"string\",\"value\":\"file://${f.absolutePath}\"}," +
                    "\"AsLink\":{\"type\":\"boolean\",\"value\":\"false\"}}",
            )
        }
    }

    private fun imageExt(uri: Uri): String {
        val type = contentResolver.getType(uri) ?: return "jpg"
        return when (type) { "image/png" -> "png"; "image/gif" -> "gif"; "image/webp" -> "webp"; else -> "jpg" }
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
