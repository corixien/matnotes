package com.matnotes.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.matnotes.app.sync.Account
import com.matnotes.app.sync.ApiException
import com.matnotes.app.sync.FolderRow
import com.matnotes.app.sync.SyncEngine
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.IOException

private class Member(val userId: String, val name: String, val role: String, val status: String)

private fun members(f: FolderRow): List<Member> = runCatching {
    val a = JSONArray(f.members)
    (0 until a.length()).map { val o = a.getJSONObject(it); Member(o.getString("userId"), o.getString("name"), o.getString("role"), o.getString("status")) }
}.getOrDefault(emptyList())

/** Pending invitations, shared folders with their members, and the actions around them. */
@Composable
fun FoldersDialog(sync: SyncEngine, onSyncNow: () -> Unit, onDismiss: () -> Unit) {
    val folders by sync.folderList.collectAsState()
    val scope = rememberCoroutineScope()
    val me = Account.userId
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var inviteTo by remember { mutableStateOf<FolderRow?>(null) }
    var confirmDelete by remember { mutableStateOf<FolderRow?>(null) }
    val errNetwork = stringResource(R.string.acc_err_network)
    val errNoUser = stringResource(R.string.share_err_no_user)
    val errAlready = stringResource(R.string.share_err_already)

    fun run(after: Boolean = false, block: suspend () -> Unit) {
        busy = true
        error = null
        scope.launch {
            try { block(); if (after) onSyncNow() } catch (e: Exception) {
                error = when {
                    e is ApiException && e.code == "not_found" -> errNoUser
                    e is ApiException && e.code == "already_member" -> errAlready
                    e is ApiException -> e.message
                    e is IOException -> errNetwork
                    else -> e.message
                }
            } finally { busy = false }
        }
    }

    val invitations = folders.filter { it.status == "invited" && !it.deleted }
    val shared = folders.filter { it.active && !it.personal }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(16.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp), tonalElevation = 6.dp) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
                Text(stringResource(R.string.share_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.share_hint), Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                if (invitations.isNotEmpty()) {
                    Text(stringResource(R.string.share_invitations), Modifier.padding(top = 16.dp, bottom = 6.dp), style = MaterialTheme.typography.titleMedium)
                    invitations.forEach { f ->
                        val owner = members(f).firstOrNull { it.role == "owner" }?.name.orEmpty()
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Text(f.name, fontWeight = FontWeight.Medium)
                            Text(stringResource(R.string.share_from, owner), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                                Button(enabled = !busy, onClick = { run(after = true) { sync.answerInvite(f.id, true) } }) { Text(stringResource(R.string.share_accept)) }
                                OutlinedButton(enabled = !busy, onClick = { run { sync.answerInvite(f.id, false) } }) { Text(stringResource(R.string.share_decline)) }
                            }
                        }
                    }
                    HorizontalDivider(Modifier.padding(top = 8.dp))
                }

                Text(stringResource(R.string.share_folders), Modifier.padding(top = 16.dp, bottom = 6.dp), style = MaterialTheme.typography.titleMedium)
                if (shared.isEmpty()) Text(stringResource(R.string.share_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
                shared.forEach { f ->
                    val owner = f.role == "owner"
                    val list = members(f)
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(f.name, fontWeight = FontWeight.Medium, fontSize = 17.sp)
                        list.forEach { m ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val suffix = when {
                                    m.status == "invited" -> " (" + stringResource(R.string.share_pending) + ")"
                                    m.role == "owner" -> " (" + stringResource(R.string.share_owner) + ")"
                                    else -> ""
                                }
                                Text(m.name + (if (m.userId == me) " " + stringResource(R.string.share_you) else "") + suffix, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                if (owner && m.role != "owner") {
                                    TextButton(enabled = !busy, onClick = { run { sync.removeMember(f.id, m.userId) } }) { Text(stringResource(R.string.share_remove)) }
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                            if (owner) {
                                OutlinedButton(enabled = !busy, onClick = { inviteTo = f }) { Text(stringResource(R.string.share_invite)) }
                                TextButton(enabled = !busy, onClick = { confirmDelete = f }) { Text(stringResource(R.string.share_delete), color = MaterialTheme.colorScheme.error) }
                            } else {
                                TextButton(enabled = !busy, onClick = { run { sync.removeMember(f.id, me ?: return@run) } }) { Text(stringResource(R.string.share_leave)) }
                            }
                        }
                    }
                    HorizontalDivider()
                }
                Spacer(Modifier.height(10.dp))
                Button(enabled = !busy, onClick = { newFolder = true }) { Text(stringResource(R.string.share_new)) }
                error?.let { Text(it, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error) }
                if (busy) CircularProgressIndicator(Modifier.padding(top = 10.dp).size(22.dp), strokeWidth = 2.5.dp)
                TextButton(onDismiss, Modifier.align(Alignment.End)) { Text(stringResource(R.string.close)) }
            }
        }
    }

    if (newFolder) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newFolder = false },
            title = { Text(stringResource(R.string.share_new)) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.share_folder_name)) }) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { newFolder = false; run(after = true) { sync.createSharedFolder(name) } }) { Text(stringResource(R.string.share_create)) }
            },
            dismissButton = { TextButton({ newFolder = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    inviteTo?.let { f -> InviteDialog(sync, f, onDismiss = { inviteTo = null }, onDone = { inviteTo = null }) }

    confirmDelete?.let { f ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.share_delete_title, f.name)) },
            text = { Text(stringResource(R.string.share_delete_msg)) },
            confirmButton = { TextButton({ confirmDelete = null; run { sync.deleteFolder(f.id) } }) { Text(stringResource(R.string.share_delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton({ confirmDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Two steps: look the person up by e-mail, then confirm their key code before the folder key is handed over. */
@Composable
private fun InviteDialog(sync: SyncEngine, folder: FolderRow, onDismiss: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var user by remember { mutableStateOf<SyncEngine.UserInfo?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val errNoUser = stringResource(R.string.share_err_no_user)
    val errAlready = stringResource(R.string.share_err_already)
    val errNetwork = stringResource(R.string.acc_err_network)

    fun run(block: suspend () -> Unit) {
        busy = true
        error = null
        scope.launch {
            try { block() } catch (e: Exception) {
                error = when {
                    e is ApiException && e.code == "not_found" -> errNoUser
                    e is ApiException && e.code == "already_member" -> errAlready
                    e is ApiException -> e.message
                    e is IOException -> errNetwork
                    else -> e.message
                }
            } finally { busy = false }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.share_invite_to, folder.name)) },
        text = {
            Column {
                val u = user
                if (u == null) {
                    OutlinedTextField(email, { email = it.trim() }, singleLine = true, enabled = !busy, label = { Text(stringResource(R.string.acc_email)) })
                } else {
                    Text(u.name, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                    Text(email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.share_code), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
                    Text(u.fingerprint, fontFamily = FontFamily.Monospace, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text(stringResource(R.string.share_code_hint, u.name), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                error?.let { Text(it, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error) }
                if (busy) CircularProgressIndicator(Modifier.padding(top = 10.dp).size(22.dp), strokeWidth = 2.5.dp)
            }
        },
        confirmButton = {
            val u = user
            if (u == null) {
                TextButton(enabled = !busy && email.isNotBlank(), onClick = { run { user = sync.lookupUser(email) } }) { Text(stringResource(R.string.share_find)) }
            } else {
                TextButton(enabled = !busy, onClick = { run { sync.invite(folder.id, u); onDone() } }) { Text(stringResource(R.string.share_send_invite)) }
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
