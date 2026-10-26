package com.matnotes.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.matnotes.app.sync.Account
import com.matnotes.app.sync.AccountState
import com.matnotes.app.sync.ApiException
import kotlinx.coroutines.launch
import java.io.IOException
import java.security.GeneralSecurityException

/** The round profile button at the top right of the start screen: initial of the name when signed in, a person outline otherwise. */
@Composable
fun ProfileButton(onClick: () -> Unit) {
    val state by Account.state.collectAsState()
    val initial = (state as? AccountState.LoggedIn)?.name?.trim()?.firstOrNull()?.uppercase()
    val fg = if (initial != null) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier.size(44.dp).clip(CircleShape)
            .background(if (initial != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (initial != null) {
            Text(initial, color = fg, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        } else {
            androidx.compose.foundation.Canvas(Modifier.size(24.dp)) {
                val w = size.width
                val st = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(fg, w * 0.2f, Offset(w / 2f, w * 0.34f), style = st)
                drawArc(fg, 200f, 140f, false, Offset(w * 0.12f, w * 0.58f), androidx.compose.ui.geometry.Size(w * 0.76f, w * 0.7f), style = st)
            }
        }
    }
}

private enum class Page { SignIn, Register, Recover }

@Composable
fun AccountDialog(onDismiss: () -> Unit, signedIn: @Composable () -> Unit) {
    val state by Account.state.collectAsState()
    var recoveryKey by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = { if (recoveryKey == null) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxWidth().padding(16.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp), tonalElevation = 6.dp) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
                when {
                    recoveryKey != null -> RecoveryKeyPage(recoveryKey!!) { recoveryKey = null }
                    state is AccountState.LoggedIn -> {
                        signedIn()
                        TextButton({ onDismiss() }, Modifier.align(Alignment.End)) { Text(stringResource(R.string.close)) }
                    }
                    else -> SignedOutPages(onRegistered = { recoveryKey = it }, onDismiss)
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.SignedOutPages(onRegistered: (String) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(Page.SignIn) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var recoveryKeyText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val errFields = stringResource(R.string.acc_err_fields)
    val errShort = stringResource(R.string.acc_err_password_short)
    val errMismatch = stringResource(R.string.acc_err_password_mismatch)
    val errCredentials = stringResource(R.string.acc_err_credentials)
    val errNetwork = stringResource(R.string.acc_err_network)
    val errEmailTaken = stringResource(R.string.acc_err_email_taken)
    val errEmail = stringResource(R.string.acc_err_email)
    val errRecovery = stringResource(R.string.acc_err_recovery)

    fun message(e: Throwable): String = when {
        e is ApiException && e.code == "invalid_credentials" -> if (page == Page.Recover) errRecovery else errCredentials
        e is ApiException && e.code == "email_taken" -> errEmailTaken
        e is ApiException && e.code == "invalid_request" && e.message?.contains("email") == true -> errEmail
        e is ApiException && e.code == "rate_limited" -> e.message ?: errNetwork
        e is GeneralSecurityException -> errRecovery
        e is IOException && e !is ApiException -> errNetwork
        else -> e.message ?: e.javaClass.simpleName
    }

    fun run(block: suspend () -> Unit) {
        busy = true
        error = null
        scope.launch {
            try { block() } catch (e: Exception) { error = message(e) } finally { busy = false }
        }
    }

    val title = when (page) {
        Page.SignIn -> R.string.acc_sign_in
        Page.Register -> R.string.acc_register
        Page.Recover -> R.string.acc_recover_title
    }
    Text(stringResource(title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(12.dp))
    if (page == Page.Register) Field(name, { name = it }, stringResource(R.string.acc_name), KeyboardType.Text, enabled = !busy)
    Field(email, { email = it.trim() }, stringResource(R.string.acc_email), KeyboardType.Email, enabled = !busy)
    if (page == Page.Recover) {
        Field(recoveryKeyText, { recoveryKeyText = it }, stringResource(R.string.acc_recovery_key), KeyboardType.Text, enabled = !busy, mono = true)
    }
    Field(
        password, { password = it },
        stringResource(if (page == Page.Recover) R.string.acc_new_password else R.string.acc_password),
        KeyboardType.Password, secret = true, enabled = !busy,
    )
    if (page != Page.SignIn) Field(repeat, { repeat = it }, stringResource(R.string.acc_password_repeat), KeyboardType.Password, secret = true, enabled = !busy)
    if (page == Page.Register) {
        Text(stringResource(R.string.acc_privacy_note), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    error?.let { Text(it, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error) }

    Spacer(Modifier.height(14.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(
            enabled = !busy,
            onClick = {
                when (page) {
                    Page.SignIn -> {
                        if (email.isBlank() || password.isEmpty()) { error = errFields; return@Button }
                        run { Account.login(email, password) }
                    }
                    Page.Register -> {
                        if (name.isBlank() || email.isBlank() || password.isEmpty()) { error = errFields; return@Button }
                        if (password.length < 10) { error = errShort; return@Button }
                        if (password != repeat) { error = errMismatch; return@Button }
                        run { onRegistered(Account.register(name, email, password)) }
                    }
                    Page.Recover -> {
                        if (email.isBlank() || recoveryKeyText.isBlank() || password.isEmpty()) { error = errFields; return@Button }
                        if (password.length < 10) { error = errShort; return@Button }
                        if (password != repeat) { error = errMismatch; return@Button }
                        run { Account.recover(email, recoveryKeyText, password) }
                    }
                }
            },
        ) { Text(stringResource(title)) }
        if (busy) {
            CircularProgressIndicator(Modifier.padding(start = 14.dp).size(22.dp), strokeWidth = 2.5.dp)
            Text(stringResource(R.string.acc_working), Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        if (page == Page.SignIn) {
            TextButton({ page = Page.Register; error = null }, enabled = !busy) { Text(stringResource(R.string.acc_register)) }
            TextButton({ page = Page.Recover; error = null }, enabled = !busy) { Text(stringResource(R.string.acc_forgot)) }
        } else {
            TextButton({ page = Page.SignIn; error = null }, enabled = !busy) { Text(stringResource(R.string.acc_back)) }
        }
    }
    TextButton({ onDismiss() }, Modifier.align(Alignment.End), enabled = !busy) { Text(stringResource(R.string.close)) }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String, type: KeyboardType, secret: Boolean = false, enabled: Boolean = true, mono: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = type, autoCorrectEnabled = false),
        textStyle = if (mono) androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp) else androidx.compose.ui.text.TextStyle.Default,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@Composable
private fun RecoveryKeyPage(key: String, onDone: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var confirmed by remember { mutableStateOf(false) }
    Text(stringResource(R.string.acc_recovery_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    Text(stringResource(R.string.acc_recovery_text), Modifier.padding(vertical = 10.dp), style = MaterialTheme.typography.bodyMedium)
    Surface(Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(
            key.chunked(20).joinToString("\n"),
            Modifier.padding(14.dp),
            fontFamily = FontFamily.Monospace, fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 26.sp,
        )
    }
    OutlinedButton(
        onClick = {
            clipboard.setText(AnnotatedString(key))
            android.widget.Toast.makeText(context, R.string.acc_recovery_copied, android.widget.Toast.LENGTH_SHORT).show()
        },
        modifier = Modifier.padding(top = 10.dp),
    ) { Text(stringResource(R.string.acc_recovery_copy)) }
    Row(Modifier.fillMaxWidth().clickable { confirmed = !confirmed }.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(confirmed, { confirmed = it })
        Text(stringResource(R.string.acc_recovery_confirm), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
    Button(onClick = onDone, enabled = confirmed, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.acc_continue)) }
}

/** Content of the dialog while signed in: who, sync status with a manual "sync now", sign out. */
@Composable
fun SignedInPage(sync: com.matnotes.app.sync.SyncEngine, onSyncNow: () -> Unit) {
    val state by Account.state.collectAsState()
    val status by sync.status.collectAsState()
    val scope = rememberCoroutineScope()
    val s = state as? AccountState.LoggedIn ?: return
    var busy by remember { mutableStateOf(false) }
    Text(stringResource(R.string.acc_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    Text(s.name, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.acc_signed_in_as, s.email), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(14.dp))
    val time = remember { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT) }
    when (val st = status) {
        is com.matnotes.app.sync.SyncStatus.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp)
            Text(stringResource(R.string.sync_running), Modifier.padding(start = 10.dp))
        }
        is com.matnotes.app.sync.SyncStatus.Done -> {
            val r = st.result
            Text(stringResource(R.string.sync_done, time.format(java.util.Date(st.time))), fontWeight = FontWeight.Medium)
            Text(stringResource(R.string.sync_counts, r.uploaded, r.downloaded, r.deleted), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (r.conflicts > 0) Text(stringResource(R.string.sync_conflicts, r.conflicts), Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.tertiary)
            r.errors.take(3).forEach { Text(it, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        }
        is com.matnotes.app.sync.SyncStatus.Failed -> Text(
            if (st.offline) stringResource(R.string.acc_err_network) else stringResource(R.string.sync_failed, st.message),
            color = MaterialTheme.colorScheme.error,
        )
        else -> Text(stringResource(R.string.sync_idle), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onSyncNow, enabled = status !is com.matnotes.app.sync.SyncStatus.Running) { Text(stringResource(R.string.sync_now)) }
        OutlinedButton(
            enabled = !busy,
            onClick = { busy = true; scope.launch { Account.logout(); busy = false } },
        ) { Text(stringResource(R.string.acc_sign_out)) }
    }
}
