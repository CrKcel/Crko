package com.crko.ui.more

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crko.i18n.Strings
import com.crko.state.AppStateHolder
import com.crko.state.CloudAccountResult
import com.crko.ui.components.CardRowDivider
import com.crko.ui.components.SurfaceCard
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * The cloud sync card of the settings page. Signed out, it offers the account dialog; signed in, it
 * shows the account, the per-device switch, the last pass and the destructive exits. Everything the
 * rows display lives in the state holder; this file only collects input and maps failures to text.
 */
@Composable
internal fun CloudSyncSection(state: AppStateHolder, strings: Strings) {
    var showAccountDialog by remember { mutableStateOf(false) }
    var showSignOutDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val account = state.cloudAccount
    SurfaceCard(modifier = Modifier.padding(horizontal = 16.dp)) {
        if (account == null) {
            BasicComponent(
                onClick = { showAccountDialog = true },
                insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(strings.cloudSync, style = MiuixTheme.textStyles.body1)
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = strings.cloudNotSignedIn,
                        style = MiuixTheme.textStyles.body1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        } else {
            // The account fields pass as primitives: the UI layer never imports the storage type.
            CloudSignedInRows(
                state = state,
                name = account.name,
                server = account.server,
                enabled = account.enabled,
                strings = strings,
                onSignOut = { showSignOutDialog = true },
                onDelete = { showDeleteDialog = true },
            )
        }
    }
    if (showAccountDialog) {
        CloudAccountDialog(state, strings, onDismiss = { showAccountDialog = false })
    }
    if (showSignOutDialog) {
        ConfirmDialog(
            title = strings.cloudSignOut,
            summary = strings.cloudSignOutSummary,
            confirmText = strings.cloudSignOut,
            strings = strings,
            onConfirm = {
                scope.launch { state.signOutCloudAccount() }
                showSignOutDialog = false
            },
            onDismiss = { showSignOutDialog = false },
        )
    }
    if (showDeleteDialog) {
        CloudDeleteDialog(state, strings, onDismiss = { showDeleteDialog = false })
    }
}

@Composable
private fun CloudSignedInRows(
    state: AppStateHolder,
    name: String,
    server: String,
    enabled: Boolean,
    strings: Strings,
    onSignOut: () -> Unit,
    onDelete: () -> Unit,
) {
    BasicComponent(insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(strings.cloudSync, style = MiuixTheme.textStyles.body1)
                Text(
                    text = "$name · $server",
                    style = MiuixTheme.textStyles.footnote1,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Text(
                text = when {
                    state.isCloudSyncing -> strings.cloudSyncing
                    state.cloudLastSyncMs == 0L -> strings.cloudNeverSynced
                    else -> strings.cloudLastSyncAt(formatSyncTime(state.cloudLastSyncMs))
                },
                style = MiuixTheme.textStyles.footnote1,
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
    CardRowDivider()
    BasicComponent(
        onClick = { state.setCloudSyncEnabled(!enabled) },
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(strings.cloudSyncEnabled, style = MiuixTheme.textStyles.body1)
            Spacer(Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = { state.setCloudSyncEnabled(it) })
        }
    }
    if (enabled) {
        CardRowDivider()
        BasicComponent(
            onClick = { state.syncCloudNow() },
            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = if (state.isCloudSyncing) strings.cloudSyncing else strings.cloudSyncNow,
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.primary,
            )
        }
    }
    CardRowDivider()
    Text(
        text = strings.cloudE2eeNote,
        style = MiuixTheme.textStyles.footnote1,
        fontSize = 12.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
    CardRowDivider()
    BasicComponent(
        onClick = onSignOut,
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(text = strings.cloudSignOut, style = MiuixTheme.textStyles.body1)
    }
    CardRowDivider()
    BasicComponent(
        onClick = onDelete,
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(text = strings.cloudDeleteAccount, style = MiuixTheme.textStyles.body1)
    }
}

/** Signup and login share one dialog; the mode toggle switches the invite field in and out. */
@Composable
private fun CloudAccountDialog(state: AppStateHolder, strings: Strings, onDismiss: () -> Unit) {
    var signupMode by remember { mutableStateOf(true) }
    var server by remember { mutableStateOf(DefaultCloudServer) }
    var invite by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<CloudAccountResult?>(null) }
    val scope = rememberCoroutineScope()
    WindowDialog(
        show = true,
        onDismissRequest = onDismiss,
        title = strings.cloudSync,
        summary = strings.cloudE2eeNote,
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (signupMode) strings.cloudSignUp else strings.cloudSignIn,
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (signupMode) strings.cloudSwitchToSignIn else strings.cloudSwitchToSignUp,
                    style = MiuixTheme.textStyles.footnote1,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        signupMode = !signupMode
                        failure = null
                    },
                )
            }
            Spacer(Modifier.height(12.dp))
            TextField(
                value = server,
                onValueChange = { server = it; failure = null },
                label = strings.cloudServerField,
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (signupMode) {
                Spacer(Modifier.height(10.dp))
                TextField(
                    value = invite,
                    onValueChange = { invite = it; failure = null },
                    label = strings.cloudInviteField,
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(10.dp))
            TextField(
                value = name,
                onValueChange = { name = it; failure = null },
                label = strings.cloudNameField,
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            TextField(
                value = password,
                onValueChange = { password = it; failure = null },
                label = strings.cloudPasswordField,
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            failure?.let { result ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = cloudFailureText(result, strings),
                    style = MiuixTheme.textStyles.footnote2,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onErrorContainer,
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        val result = if (signupMode) {
                            state.signupCloudAccount(server, invite, name, password)
                        } else {
                            state.loginCloudAccount(server, name, password)
                        }
                        busy = false
                        if (result == CloudAccountResult.SignedIn) onDismiss() else failure = result
                    }
                },
                enabled = !busy && server.isNotBlank() && name.isNotBlank() && password.isNotBlank() &&
                    (!signupMode || invite.isNotBlank()),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(
                    when {
                        busy -> strings.cloudWorking
                        signupMode -> strings.cloudSignUp
                        else -> strings.cloudSignIn
                    },
                )
            }
        }
    }
}

/** Password re-confirmation in front of the irreversible server-side account deletion. */
@Composable
private fun CloudDeleteDialog(state: AppStateHolder, strings: Strings, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<CloudAccountResult?>(null) }
    val scope = rememberCoroutineScope()
    WindowDialog(
        show = true,
        onDismissRequest = onDismiss,
        title = strings.cloudDeleteAccount,
        summary = strings.cloudDeleteAccountSummary,
    ) {
        Column {
            TextField(
                value = password,
                onValueChange = { password = it; failure = null },
                label = strings.cloudPasswordField,
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            failure?.let { result ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = cloudFailureText(result, strings),
                    style = MiuixTheme.textStyles.footnote2,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onErrorContainer,
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        val result = state.deleteCloudAccount(password)
                        busy = false
                        if (result == CloudAccountResult.SignedIn) onDismiss() else failure = result
                    }
                },
                enabled = !busy && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(if (busy) strings.cloudWorking else strings.cloudDeleteAccount)
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(strings.cancel)
            }
        }
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    summary: String,
    confirmText: String,
    strings: Strings,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    WindowDialog(show = true, onDismissRequest = onDismiss, title = title, summary = summary) {
        Column {
            Button(
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(confirmText)
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(strings.cancel)
            }
        }
    }
}

/** The default server of the account dialog; overridable for local development. */
private const val DefaultCloudServer = "https://crko.cloud"

private fun cloudFailureText(result: CloudAccountResult, strings: Strings): String = when {
    result !is CloudAccountResult.Failed -> strings.cloudErrorGeneric
    result.error == "invalid_invite" -> strings.cloudErrorInvite
    result.error == "name_taken" -> strings.cloudErrorNameTaken
    result.error == "invalid_credentials" -> strings.cloudErrorCredentials
    result.error == "unknown_account" -> strings.cloudErrorUnknownAccount
    result.error == "invalid_server" -> strings.cloudErrorServer
    else -> strings.cloudErrorGeneric
}

/** `MM-dd HH:mm` in the device zone; the sync card only needs a coarse stamp. */
private fun formatSyncTime(ms: Long): String {
    val value = Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.currentSystemDefault())
    val month = value.monthNumber.toString().padStart(2, '0')
    val day = value.dayOfMonth.toString().padStart(2, '0')
    val hour = value.hour.toString().padStart(2, '0')
    val minute = value.minute.toString().padStart(2, '0')
    return "$month-$day $hour:$minute"
}
