package com.matnotes.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val WordBlue = Color(0xFF2B579A)

private val Light = lightColorScheme(
    primary = WordBlue, onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE6F7), onPrimaryContainer = Color(0xFF0B2A57),
    surface = Color(0xFFFAFAFA), background = Color(0xFFFAFAFA),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF9DBBEA), onPrimary = Color(0xFF0B2A57),
    primaryContainer = Color(0xFF1F3F72), onPrimaryContainer = Color(0xFFDCE6F7),
)

@Composable
fun MatNotesTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light) {
        Surface(color = MaterialTheme.colorScheme.background, content = content)
    }
}
