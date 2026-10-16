package com.matnotes.app

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import java.io.File
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    private var page by mutableStateOf<Bitmap?>(null)
    private var status by mutableStateOf("Lade Engine ...")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val bmp = page
                if (bmp != null) {
                    Image(
                        bmp.asImageBitmap(), null,
                        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                        contentScale = ContentScale.FillWidth,
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(status) }
                }
            }
        }
        thread {
            try {
                Engine.init(this)
                val f = File(getExternalFilesDir(null), "t.docx")
                val doc = Engine.open(f.absolutePath)
                if (doc == null) { status = "Öffnen fehlgeschlagen"; return@thread }
                page = Engine.renderAll(doc, 1080)
            } catch (e: Throwable) {
                android.util.Log.e("MatNotes", "spike failed", e)
                status = "Fehler: ${e.message}"
            }
        }
    }
}
