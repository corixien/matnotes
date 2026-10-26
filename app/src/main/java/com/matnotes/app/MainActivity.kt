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
import androidx.compose.runtime.collectAsState
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
private class OpenDoc(val doc: LoDocument, title: String, val state: DocumentViewState, val source: Uri?) {
    var title by mutableStateOf(title)
}

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
    private var shareDialog by mutableStateOf(false)
    private var nameDialog by mutableStateOf<String?>(null)
    private var myDocs by mutableStateOf<List<StoredDoc>>(emptyList())
    private var sharedDocs by mutableStateOf<List<Pair<com.matnotes.app.sync.FolderRow, List<StoredDoc>>>>(emptyList())
    private val sync by lazy { com.matnotes.app.sync.SyncEngine(applicationContext) }
    private val syncHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastSyncStart = 0L

    private val createDoc = registerForActivityResult(ActivityResultContracts.CreateDocument(DOCX_MIME)) { uri ->
        if (uri != null) { saveUri = uri; doSave() } else closeAfterSave = false
    }
    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) insertImage(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        com.matnotes.app.sync.Account.init(this)
        com.matnotes.app.sync.Account.onSignedOut = { sync.reset() }
        // signing in (or registering) starts the first sync right away
        lifecycleScope.launch {
            com.matnotes.app.sync.Account.state.collect { if (it is com.matnotes.app.sync.AccountState.LoggedIn) syncNow() }
        }
        lifecycleScope.launch { sync.folderList.collect { refreshStore() } }
        recents = Recents(this)
        recentList = recents.list()
        refreshStore()
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
                // the system hides a visible keyboard on the first Back; this runs when it is already hidden
                BackHandler(enabled = cur != null) { requestClose() }
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
                var accountDialog by remember { mutableStateOf(false) }
                val folderState by sync.folderList.collectAsState()
                if (accountDialog) AccountDialog({ accountDialog = false }) { SignedInPage(sync, onSyncNow = ::syncNow) }
                if (shareDialog) ShareDialog({ shareDialog = false }) { fmt, email -> shareDocument(fmt, email) }
                nameDialog?.let { initial ->
                    val personal = stringResource(R.string.share_personal)
                    val choices = listOf(personal to "") + sharedDocs.map { it.first.name to it.first.localDir }
                    NameDialog(initial, choices, { nameDialog = null }) { n, dir -> saveToStore(if (n.endsWith(".docx", true)) n else "$n.docx", dir) }
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
                            onSaveAs = { saveUri = null; createDoc.launch(defaultCopyName(cur)) },
                            onOverwrite = if (cur.source != null && cur.source.scheme == "content") ({ confirmOverwrite = true }) else null,
                            onPrint = ::printDocument,
                            onShare = { shareDialog = true },
                            onPickImage = { pickImage.launch("image/*") },
                            onPageSetup = ::applyPageSetup,
                        ),
                    )
                    else -> HomeScreen(
                        myDocs = myDocs,
                        recents = recentList,
                        error = error,
                        onNew = ::newDocument,
                        onOpen = { picker.launch(arrayOf("*/*")) },
                        onOpenStored = { openUri(it.uri) },
                        onOpenRecent = { openUri(Uri.parse(it.uri)) },
                        onRemoveRecent = { recents.remove(it.uri); recentList = recents.list() },
                        onProfile = { accountDialog = true },
                        sharedDocs = sharedDocs.map { it.first.name to it.second },
                        invitations = folderState.count { it.status == "invited" && !it.deleted },
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
            i?.getStringExtra("shape")?.let { c -> current?.state?.insertShape(c); return }
            i?.getStringExtra("dtap")?.let { xy ->
                val (x, y) = xy.split(',').map { it.toFloat() }
                current?.state?.doubleTap(androidx.compose.ui.geometry.Offset(x, y)); return
            }
            if (i?.hasExtra("dump") == true) { current?.state?.dumpState(); return }
            if (i?.hasExtra("key") == true) { current?.state?.pressKey(i.getIntExtra("key", 0)); return }
            i?.getStringExtra("mousetw")?.let { m ->
                // type,x,y,count,buttons,modifier in document twips
                val v = m.split(',').map { it.toInt() }
                current?.state?.debugMouseTw(v[0], v[1], v[2], v[3], v[4], v[5]); return
            }
            i?.getStringExtra("zoom")?.toFloatOrNull()?.let { f -> current?.state?.zoomBy(f); return }
            i?.getStringExtra("link")?.let { l ->
                val (t, u) = l.split('|'); current?.state?.insertLink(t, u); return
            }
            i?.getStringExtra("setclip")?.let { t ->
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                val h = i.getStringExtra("setclip_html")
                cm.setPrimaryClip(if (h == null) android.content.ClipData.newPlainText("t", t) else android.content.ClipData.newHtmlText("t", t, h))
                return
            }
            if (i?.hasExtra("setclip_image") == true) {
                val dir = File(cacheDir, "share").apply { mkdirs() }
                val f = File(dir, "clip-test.png")
                val bmp = android.graphics.Bitmap.createBitmap(300, 200, android.graphics.Bitmap.Config.ARGB_8888)
                android.graphics.Canvas(bmp).drawColor(android.graphics.Color.rgb(220, 40, 40))
                f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
                getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newUri(contentResolver, "img", uri))
                return
            }
            if (i?.hasExtra("getclip") == true) {
                val c = getSystemService(android.content.ClipboardManager::class.java).primaryClip
                val it0 = c?.takeIf { it.itemCount > 0 }?.getItemAt(0)
                android.util.Log.d("MatNotesDump", "clip mime=${c?.description?.filterMimeTypes("*/*")?.toList()} text=${it0?.text} html=${it0?.htmlText?.take(300)}")
                return
            }
            if (i?.hasExtra("paste") == true) { current?.state?.paste(); return }
            if (i?.hasExtra("copy") == true) { current?.state?.copy(cut = i.getBooleanExtra("cut", false)); return }
            if (i?.hasExtra("sync") == true) { syncNow(); return }
            i?.getStringExtra("cmdvalues")?.let { c -> current?.state?.logCommandValues(c); return }
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
                    // documents from Documents/MatNotes are the app's own: Save writes straight back to them
                    saveUri = if (allStored().any { it.uri == uri }) uri else null
                    current = OpenDoc(
                        doc, name,
                        DocumentViewState(doc, lifecycleScope, resources.displayMetrics.density).also { it.onSave = ::save; it.onTableOp = ::applyTableOp; it.onPasteImage = ::insertImage; it.appContext = applicationContext },
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
                    val st = DocumentViewState(doc, lifecycleScope, resources.displayMetrics.density).also { it.onSave = ::save; it.onTableOp = ::applyTableOp; it.onPasteImage = ::insertImage; it.appContext = applicationContext }
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

    private fun defaultCopyName(c: OpenDoc) = c.title.substringBeforeLast('.') + " (MatNotes).docx"

    /**
     * Save: new documents ask for a name and go to Documents/MatNotes; opened documents are saved as a copy there
     * (the original is never overwritten implicitly). Without MediaStore support the file picker is used.
     */
    private fun save() {
        val c = current ?: return
        when {
            saveUri != null -> doSave()
            !DocStore.supported -> createDoc.launch(defaultCopyName(c))
            c.source == null -> nameDialog = c.title.substringBeforeLast('.')
            else -> saveToStore(defaultCopyName(c))
        }
    }

    private fun saveToStore(fileName: String, dir: String = "") {
        val uri = DocStore.create(this, fileName, DOCX_MIME, dir)
        if (uri == null) {
            createDoc.launch(fileName)
        } else {
            saveUri = uri
            doSave()
        }
    }

    private fun refreshStore() {
        myDocs = DocStore.list(this)
        sharedDocs = sync.folderList.value.filter { it.active && !it.personal }.map { f -> f to DocStore.listIn(this, f.localDir, com.matnotes.app.sync.SyncEngine.EXTENSIONS).orEmpty() }
    }

    private fun allStored() = myDocs + sharedDocs.flatMap { it.second }

    override fun onResume() {
        super.onResume()
        refreshStore()
        if (com.matnotes.app.sync.Account.loggedIn && System.currentTimeMillis() - lastSyncStart > 60_000L) syncNow()
    }

    /** One sync cycle. Files that are open in the editor are left alone. */
    private fun syncNow() {
        if (!com.matnotes.app.sync.Account.loggedIn) return
        lastSyncStart = System.currentTimeMillis()
        lifecycleScope.launch {
            sync.sync { uri -> current?.source == uri || saveUri == uri }
            refreshStore()
        }
    }

    /** After a save: sync a few seconds later (several saves in a row become one sync). */
    private fun scheduleSync() {
        if (!com.matnotes.app.sync.Account.loggedIn) return
        syncHandler.removeCallbacksAndMessages(null)
        syncHandler.postDelayed({ syncNow() }, 5_000L)
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
                DocStore.displayName(this@MainActivity, target)?.let { c.title = it }
                recents.add(target.toString(), c.title)
                recentList = recents.list()
                refreshStore()
                scheduleSync()
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
        c.state.hideKeyboardNow()
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

    /** Exports to [format] (docx, odt, pdf) and hands it to the system share sheet or to an e-mail app as attachment. */
    private fun shareDocument(format: String, email: Boolean) {
        val c = current ?: return
        busy = true
        lifecycleScope.launch {
            val mime = when (format) {
                "pdf" -> "application/pdf"
                "odt" -> ODT_MIME
                else -> DOCX_MIME
            }
            val out = withContext(Engine.dispatcher) {
                val dir = File(cacheDir, "share").apply { mkdirs() }
                val f = File(dir, c.title.substringBeforeLast('.') + "." + format)
                if (c.doc.saveAs(f, format)) f else null
            }
            busy = false
            if (out == null) {
                android.widget.Toast.makeText(this@MainActivity, R.string.export_failed, android.widget.Toast.LENGTH_LONG).show()
                return@launch
            }
            val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", out)
            // mailto: restricts the chooser to e-mail apps; they open a compose screen (to, subject, text) with the file attached
            val send = (if (email) Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")) else Intent(Intent.ACTION_SEND).setType(mime))
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (email) send.putExtra(Intent.EXTRA_SUBJECT, c.title.substringBeforeLast('.'))
            send.clipData = android.content.ClipData.newRawUri(c.title, uri)
            try {
                startActivity(Intent.createChooser(send, getString(if (email) R.string.share_email else R.string.share_via)))
            } catch (e: android.content.ActivityNotFoundException) {
                android.widget.Toast.makeText(this@MainActivity, R.string.no_email_app, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Applies page size / orientation / margins to the exported docx and reloads the document. */
    private fun applyPageSetup(change: PageSetupChange) {
        val cur = current ?: return
        busy = true
        lifecycleScope.launch {
            try {
                val dir = File(cacheDir, "open/${UUID.randomUUID()}").apply { mkdirs() }
                val src = File(dir, "in.docx")
                val dst = File(dir, cur.title)
                val exported = withContext(Engine.dispatcher) { cur.doc.saveAs(src, "docx") }
                check(exported) { "export failed" }
                withContext(Dispatchers.IO) { PageSetup.apply(src, dst, change) }
                val doc = Engine.open(this@MainActivity, dst) ?: error("reload failed")
                val st = DocumentViewState(doc, lifecycleScope, resources.displayMetrics.density).also { it.onSave = ::save; it.onTableOp = ::applyTableOp; it.onPasteImage = ::insertImage; it.appContext = applicationContext }
                st.editing = cur.state.editing
                st.dirty = true
                current = OpenDoc(doc, cur.title, st, cur.source)
                lifecycleScope.launch(Engine.dispatcher) { cur.doc.close() }
            } catch (e: Throwable) {
                Log.e(TAG, "page setup failed", e)
                android.widget.Toast.makeText(this@MainActivity, R.string.export_failed, android.widget.Toast.LENGTH_LONG).show()
            } finally {
                busy = false
            }
        }
    }

    /** Writes a table border / look into the exported docx (the caret's table is found by a typed marker) and reloads. */
    private fun applyTableOp(op: TableOp) {
        val cur = current ?: return
        busy = true
        lifecycleScope.launch {
            try {
                cur.state.typeText(TablePatch.MARK)
                kotlinx.coroutines.delay(700) // LibreOffice applies the typed marker asynchronously
                val dir = File(cacheDir, "open/${UUID.randomUUID()}").apply { mkdirs() }
                val src = File(dir, "in.docx")
                val dst = File(dir, cur.title)
                val exported = withContext(Engine.dispatcher) { cur.doc.saveAs(src, "docx") }
                check(exported) { "export failed" }
                val found = withContext(Dispatchers.IO) { TablePatch.apply(src, dst, op) }
                if (!found) {
                    android.widget.Toast.makeText(this@MainActivity, R.string.table_not_found, android.widget.Toast.LENGTH_LONG).show()
                    return@launch
                }
                val doc = Engine.open(this@MainActivity, dst) ?: error("reload failed")
                val st = DocumentViewState(doc, lifecycleScope, resources.displayMetrics.density).also { it.onSave = ::save; it.onTableOp = ::applyTableOp; it.onPasteImage = ::insertImage; it.appContext = applicationContext }
                st.editing = cur.state.editing
                st.dirty = true
                st.copyViewFrom(cur.state)
                current = OpenDoc(doc, cur.title, st, cur.source)
                lifecycleScope.launch(Engine.dispatcher) { cur.doc.close() }
            } catch (e: Throwable) {
                Log.e(TAG, "table patch failed", e)
                android.widget.Toast.makeText(this@MainActivity, R.string.export_failed, android.widget.Toast.LENGTH_LONG).show()
            } finally {
                busy = false
            }
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
        const val ODT_MIME = "application/vnd.oasis.opendocument.text"
    }
}
