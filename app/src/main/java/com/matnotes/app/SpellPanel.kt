package com.matnotes.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private enum class Phase { Detecting, Setup, Checking, Review, Done }

/** Spelling check in place of the ribbon: detects the language, lets the user change it, then walks through the mistakes. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpellPanel(state: DocumentViewState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf(Phase.Detecting) }
    var lang by remember { mutableStateOf<SpellLang?>(null) }
    var detected by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var problems by remember { mutableStateOf<List<SpellProblem>>(emptyList()) }
    var total by remember { mutableIntStateOf(0) }
    var idx by remember { mutableIntStateOf(0) }
    var picking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(Unit) {
        val t = state.documentText(ctx.cacheDir)?.removePrefix("﻿")
        if (t == null) { error = R.string.spell_text_error; phase = Phase.Setup; lang = Spelling.forTag("en"); return@LaunchedEffect }
        text = t
        lang = Spelling.detect(ctx, t, state.format[".uno:LanguageStatus"]?.substringAfter(';', ""))
        detected = true
        phase = Phase.Setup
    }
    // show the current word in the document
    LaunchedEffect(phase, idx, problems) {
        if (phase == Phase.Review) problems.getOrNull(idx)?.let { state.findWord(it.word) }
    }

    fun start() {
        val l = lang ?: return
        phase = Phase.Checking; error = null
        scope.launch {
            val res = Spelling.check(ctx, l, text)
            if (res == null) { error = R.string.spell_error; phase = Phase.Setup; return@launch }
            problems = res; total = res.size; idx = 0
            phase = if (res.isEmpty()) Phase.Done else Phase.Review
        }
    }

    fun drop(i: Int) {
        val rest = problems.toMutableList().also { it.removeAt(i) }
        problems = rest
        if (rest.isEmpty()) phase = Phase.Done else idx = i.coerceAtMost(rest.lastIndex)
    }

    Surface(Modifier.fillMaxWidth(), tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.spell_title), fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { state.spelling = false }) { Text(stringResource(R.string.close)) }
            }
            HorizontalDivider(Modifier.padding(bottom = 8.dp))
            Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                when (phase) {
                    Phase.Detecting, Phase.Checking -> Row(Modifier.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                        Text(
                            stringResource(if (phase == Phase.Detecting) R.string.spell_detecting else R.string.spell_checking),
                            Modifier.padding(start = 14.dp),
                        )
                    }
                    Phase.Setup -> {
                        LangRow(lang, detected) { picking = true }
                        error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
                        Button(onClick = { start() }, enabled = lang != null && error != R.string.spell_text_error, modifier = Modifier.padding(top = 12.dp)) {
                            Text(stringResource(R.string.spell_start))
                        }
                    }
                    Phase.Done -> {
                        LangRow(lang, false) { picking = true }
                        Text(
                            stringResource(if (total == 0) R.string.spell_none else R.string.spell_done),
                            Modifier.padding(top = 10.dp), fontWeight = FontWeight.Medium,
                        )
                        Button(onClick = { start() }, modifier = Modifier.padding(top = 12.dp)) { Text(stringResource(R.string.spell_start)) }
                    }
                    Phase.Review -> {
                        val p = problems[idx]
                        LangRow(lang, false) { picking = true }
                        Text(
                            stringResource(R.string.spell_word_of, idx + 1, problems.size) + "  ·  " + stringResource(R.string.spell_times, p.count),
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
                        )
                        Text(p.word, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 4.dp))
                        if (p.suggestions.isEmpty()) {
                            Text(stringResource(R.string.spell_no_suggestions), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                p.suggestions.forEach { s ->
                                    OutlinedButton(
                                        onClick = { state.replaceWord(p.word, s); drop(idx) },
                                        shape = RoundedCornerShape(10.dp),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                                    ) { Text(s, fontSize = 16.sp) }
                                }
                            }
                        }
                        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { idx = (idx - 1).coerceAtLeast(0) }, enabled = idx > 0) { Text("◀") }
                            TextButton(onClick = { if (idx < problems.lastIndex) idx++ else phase = Phase.Done }) { Text(stringResource(R.string.spell_ignore)) }
                            TextButton(onClick = { Spelling.addUserWord(ctx, p.word); drop(idx) }) { Text(stringResource(R.string.spell_add)) }
                        }
                    }
                }
            }
        }
    }

    if (picking) {
        val all = remember { Spelling.languages.sortedBy { it.displayName() } }
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(stringResource(R.string.spell_pick_language)) },
            text = {
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(all) { l ->
                        Text(
                            l.displayName(),
                            Modifier.fillMaxWidth().clickable { lang = l; detected = false; picking = false; if (phase != Phase.Setup) { phase = Phase.Setup } }
                                .padding(vertical = 12.dp, horizontal = 4.dp),
                            fontWeight = if (l == lang) FontWeight.Bold else FontWeight.Normal,
                            color = if (l == lang) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun LangRow(lang: SpellLang?, detected: Boolean, onChange: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onChange).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.spell_language), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(lang?.displayName() ?: "–", fontSize = 17.sp, fontWeight = FontWeight.Medium)
            if (detected) Text(stringResource(R.string.spell_detected), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        }
        Text("▾", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
