package com.crko.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Box
import com.crko.domain.CalendarSubscription
import com.crko.domain.DiscoveryFailure
import com.crko.i18n.Strings
import com.crko.state.AppStateHolder
import com.crko.state.CalDavAccountDiscovery
import com.crko.state.DiscoveredCalDavCalendar
import com.crko.ui.components.CardRowDivider
import com.crko.ui.components.SurfaceCard
import com.crko.ui.icons.AppIcons
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * Management of subscribed calendars: every subscription syncs on tap, a dialog adds a new one from
 * a pasted webcal/CalDAV link, and a second dialog signs in to a CalDAV server and adds the picked
 * calendars for two-way sync. Sync bookkeeping lives in the state holder, so the rows stay a plain
 * view of [com.crko.domain.AppState.subscriptions].
 */
@Composable
fun SubscriptionsSection(state: AppStateHolder, strings: Strings) {
    val subscriptions = state.appState.subscriptions
    var showAddDialog by remember { mutableStateOf(false) }
    var showAccountDialog by remember { mutableStateOf(false) }
    var credentialsFor by remember { mutableStateOf<CalendarSubscription?>(null) }
    var removing by remember { mutableStateOf<CalendarSubscription?>(null) }
    if (showAddDialog) {
        AddSubscriptionDialog(
            strings = strings,
            // False when the URL has no scheme the app can fetch; the dialog points at the field.
            onConfirm = { name, url ->
                val added = state.addSubscription(name, url)
                if (added != null) {
                    state.syncSubscription(added.id)
                    showAddDialog = false
                }
                added != null
            },
            onDismiss = { showAddDialog = false },
        )
    }
    if (showAccountDialog) {
        AddAccountDialog(strings = strings, state = state, onDismiss = { showAccountDialog = false })
    }
    credentialsFor?.let { subscription ->
        CredentialsDialog(
            subscription = subscription,
            strings = strings,
            state = state,
            onDismiss = { credentialsFor = null },
        )
    }
    removing?.let { subscription ->
        WindowDialog(
            show = true,
            onDismissRequest = { removing = null },
            title = subscription.name,
            summary = strings.caldavRemoveSummary,
        ) {
            Column {
                Button(
                    onClick = {
                        state.removeSubscription(subscription.id)
                        removing = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(strings.delete)
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { removing = null },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(strings.cancel)
                }
            }
        }
    }
    SurfaceCard {
        if (subscriptions.isEmpty()) {
            Text(
                text = strings.subscriptionsEmpty,
                style = MiuixTheme.textStyles.footnote1,
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            )
        }
        subscriptions.forEachIndexed { index, subscription ->
            SubscriptionRow(
                subscription = subscription,
                strings = strings,
                syncing = state.isSyncing(subscription.id),
                onSync = { state.syncSubscription(subscription.id) },
                onEditCredentials = subscription.username?.takeIf { it.isNotBlank() }?.let {
                    { credentialsFor = subscription }
                },
                onDelete = {
                    // A writable calendar only ever leaves the app; the server keeps its events.
                    if (subscription.writable) removing = subscription else state.removeSubscription(subscription.id)
                },
            )
            if (index != subscriptions.lastIndex) CardRowDivider()
        }
        if (subscriptions.isNotEmpty()) CardRowDivider()
        CardRow(
            icon = AppIcons.Add,
            title = strings.caldavAdd,
            subtitle = strings.caldavAddHint,
            onClick = { showAccountDialog = true },
        )
        CardRowDivider()
        CardRow(
            icon = AppIcons.Import,
            title = strings.subscriptionAdd,
            subtitle = strings.subscriptionAddHint,
            onClick = { showAddDialog = true },
        )
    }
}

@Composable
private fun SubscriptionRow(
    subscription: CalendarSubscription,
    strings: Strings,
    syncing: Boolean,
    onSync: () -> Unit,
    onEditCredentials: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    BasicComponent(
        onClick = onSync,
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (syncing) "${subscription.name}…" else subscription.name,
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subscriptionSubtitle(subscription, strings),
                    style = MiuixTheme.textStyles.footnote1,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onEditCredentials != null) {
                Icon(
                    imageVector = AppIcons.Tune,
                    contentDescription = strings.caldavEditCredentials,
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onEditCredentials)
                        .padding(2.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            Icon(
                imageVector = AppIcons.Delete,
                contentDescription = strings.delete,
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onDelete)
                    .padding(2.dp),
            )
        }
    }
}

private fun subscriptionSubtitle(subscription: CalendarSubscription, strings: Strings): String {
    val synced = subscription.lastSync?.let { last ->
        strings.subscriptionLastSynced(formatSyncTime(last, strings))
    } ?: strings.subscriptionNeverSynced
    val access = if (subscription.writable) strings.caldavWritable else strings.caldavReadonly
    return "$access · $synced"
}

/** Local date and time of a stored sync instant, in the wording the rest of the app uses. */
private fun formatSyncTime(instantText: String, strings: Strings): String =
    runCatching { kotlin.time.Instant.parse(instantText) }.getOrNull()
        ?.toLocalDateTime(TimeZone.currentSystemDefault())
        ?.let { "${strings.dayMonth(it.date)} ${strings.time(it.time)}" }
        ?: strings.subscriptionNeverSynced

@Composable
private fun CardRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    BasicComponent(onClick = onClick, insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.15f))
                    .padding(6.dp),
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(text = title, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium)
                Text(
                    text = subtitle,
                    style = MiuixTheme.textStyles.footnote1,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

/** Dialog that adds a subscription from a pasted URL; the name may stay empty. */
@Composable
private fun AddSubscriptionDialog(
    strings: Strings,
    onConfirm: (name: String, url: String) -> Boolean,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var showInvalid by remember { mutableStateOf(false) }
    WindowDialog(
        show = true,
        onDismissRequest = onDismiss,
        title = strings.subscriptionAdd,
        summary = strings.subscriptionsHint,
    ) {
        Column {
            TextField(
                value = url,
                onValueChange = {
                    url = it
                    showInvalid = false
                },
                label = strings.subscriptionUrl,
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (showInvalid) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = strings.subscriptionUrlInvalid,
                    style = MiuixTheme.textStyles.footnote2,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onErrorContainer,
                )
            }
            Spacer(Modifier.height(12.dp))
            TextField(
                value = name,
                onValueChange = { name = it },
                label = strings.subscriptionName,
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    // A rejected URL keeps the dialog open and names the field as the problem.
                    if (!onConfirm(name, url)) showInvalid = true
                },
                enabled = url.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(strings.save)
            }
        }
    }
}

/**
 * Dialog that signs in to a CalDAV server and adds the picked calendars. The first step collects
 * the account; the second lists what discovery found for selection.
 */
@Composable
private fun AddAccountDialog(
    strings: Strings,
    state: AppStateHolder,
    onDismiss: () -> Unit,
) {
    var server by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var discovering by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<DiscoveryFailure?>(null) }
    var collections by remember { mutableStateOf<List<DiscoveredCalDavCalendar>>(emptyList()) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var adding by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    WindowDialog(
        show = true,
        onDismissRequest = onDismiss,
        title = strings.caldavAdd,
        summary = strings.caldavAddHint,
    ) {
        Column {
            if (collections.isEmpty()) {
                TextField(
                    value = server,
                    onValueChange = {
                        server = it
                        failure = null
                    },
                    label = strings.caldavServer,
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                TextField(
                    value = username,
                    onValueChange = {
                        username = it
                        failure = null
                    },
                    label = strings.caldavUsername,
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                TextField(
                    value = password,
                    onValueChange = {
                        password = it
                        failure = null
                    },
                    label = strings.caldavPassword,
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                failure?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = strings.caldavDiscoverFailed(it),
                        style = MiuixTheme.textStyles.footnote2,
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onErrorContainer,
                    )
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        scope.launch {
                            discovering = true
                            failure = null
                            val result = state.discoverCalDavAccount(server, username, password)
                            when (result) {
                                is CalDavAccountDiscovery.Found -> collections = result.collections
                                is CalDavAccountDiscovery.Failed -> failure = result.failure
                            }
                            discovering = false
                        }
                    },
                    enabled = !discovering && server.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(if (discovering) strings.caldavDiscovering else strings.caldavDiscover)
                }
            } else {
                if (collections.isEmpty()) {
                    Text(
                        text = strings.caldavNoCalendars,
                        style = MiuixTheme.textStyles.footnote1,
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(vertical = 14.dp),
                    )
                }
                collections.forEach { collection ->
                    CollectionRow(
                        collection = collection,
                        selected = collection.href in selected,
                        strings = strings,
                        onToggle = {
                            selected = if (collection.href in selected) {
                                selected - collection.href
                            } else {
                                selected + collection.href
                            }
                        },
                    )
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        scope.launch {
                            adding = true
                            val picked = collections.filter { it.href in selected }
                            state.addCalDavAccount(username, password, picked)
                            adding = false
                            onDismiss()
                        }
                    },
                    enabled = !adding && selected.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(strings.caldavSave)
                }
            }
        }
    }
}

@Composable
private fun CollectionRow(
    collection: DiscoveredCalDavCalendar,
    selected: Boolean,
    strings: Strings,
    onToggle: () -> Unit,
) {
    BasicComponent(
        onClick = onToggle,
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(
                        collection.colorArgb?.let(::Color) ?: MiuixTheme.colorScheme.primary,
                    ),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = collection.displayName,
                    style = MiuixTheme.textStyles.body1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (collection.writable) strings.caldavWritable else strings.caldavReadonly,
                    style = MiuixTheme.textStyles.footnote1,
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            if (selected) {
                Icon(
                    imageVector = AppIcons.Check,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** Dialog that replaces the account of an existing CalDAV calendar. */
@Composable
private fun CredentialsDialog(
    subscription: CalendarSubscription,
    strings: Strings,
    state: AppStateHolder,
    onDismiss: () -> Unit,
) {
    var username by remember { mutableStateOf(subscription.username.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    WindowDialog(
        show = true,
        onDismissRequest = onDismiss,
        title = strings.caldavEditCredentials,
        summary = subscription.name,
    ) {
        Column {
            TextField(
                value = username,
                onValueChange = { username = it },
                label = strings.caldavUsername,
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            TextField(
                value = password,
                onValueChange = { password = it },
                label = strings.caldavPassword,
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    scope.launch {
                        saving = true
                        state.updateCalDavCredentials(subscription.id, username, password)
                        saving = false
                        onDismiss()
                    }
                },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(strings.save)
            }
        }
    }
}
