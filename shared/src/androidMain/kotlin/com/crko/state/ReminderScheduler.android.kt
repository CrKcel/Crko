package com.crko.state

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import com.crko.ActivityHolder
import com.crko.AndroidAppContext
import com.crko.i18n.Strings
import com.crko.i18n.languageFromTag
import com.crko.i18n.resolveLanguage
import com.crko.i18n.stringsFor
import com.crko.storage.AppStorage
import com.crko.storage.loadPersistedSettings
import com.crko.storage.systemLocaleTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlin.time.Clock

private const val REMINDER_PREFS = "crko.reminders"
private const val KEY_SCHEDULED = "scheduled"
const val REMINDER_CHANNEL_ID = "reminders"
const val REMINDER_QUIET_CHANNEL_ID = "reminders_quiet"
const val EXTRA_REMINDER_KEY = "crko.extra.KEY"
const val EXTRA_REMINDER_TITLE = "crko.extra.TITLE"
const val EXTRA_REMINDER_BODY = "crko.extra.BODY"
const val EXTRA_REMINDER_LEVEL = "crko.extra.LEVEL"
const val EXTRA_REMINDER_ACTIONS = "crko.extra.ACTIONS"
const val EXTRA_REMINDER_DEEP_LINK = "crko.extra.DEEP_LINK"
const val EXTRA_REMINDER_RECORD_ID = "crko.extra.RECORD_ID"
const val EXTRA_SNOOZE_COUNT = "crko.extra.SNOOZE_COUNT"
const val ACTION_SNOOZE_REMINDER = "crko.action.SNOOZE"

private const val SnoozeMillis = 10 * 60 * 1000L

/**
 * Alarm-manager backed scheduler. Already-registered alarms are tracked by key in a private prefs
 * file so a pass can cancel exactly the keys that changed; the notification itself is posted by
 * [ReminderReceiver] with the title, body, level and actions computed at schedule time.
 */
object AndroidReminders {
    /**
     * Cancels [cancelKeys] and registers [register]; an alarm registered under the same key is
     * replaced in place, so a changed reminder needs no separate cancel.
     */
    fun apply(context: Context, cancelKeys: Set<String>, register: List<ScheduledReminder>): Boolean {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        val prefs = context.getSharedPreferences(REMINDER_PREFS, Context.MODE_PRIVATE)
        // getStringSet returns an internal set; it must be copied before anything mutates it.
        val tracked = prefs.getStringSet(KEY_SCHEDULED, emptySet()).orEmpty().toMutableSet()
        cancelKeys.forEach { key ->
            if (tracked.remove(key)) alarmManager.cancel(firePendingIntent(context, key))
        }
        register.forEach { reminder ->
            registerAlarm(context, alarmManager, reminder, reminder.triggerAtMillis, snoozeCount = 0)
            tracked += reminder.key
        }
        prefs.edit { putStringSet(KEY_SCHEDULED, tracked) }
        return true
    }

    /** Replaces every registered alarm with [reminders]. */
    fun reschedule(context: Context, reminders: List<ScheduledReminder>): Boolean {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        val prefs = context.getSharedPreferences(REMINDER_PREFS, Context.MODE_PRIVATE)
        prefs.getStringSet(KEY_SCHEDULED, emptySet()).orEmpty().forEach { key ->
            alarmManager.cancel(firePendingIntent(context, key))
        }
        if (reminders.isEmpty()) {
            prefs.edit { remove(KEY_SCHEDULED) }
            return true
        }
        val registered = mutableSetOf<String>()
        reminders.forEach { reminder ->
            registerAlarm(context, alarmManager, reminder, reminder.triggerAtMillis, snoozeCount = 0)
            registered += reminder.key
        }
        prefs.edit { putStringSet(KEY_SCHEDULED, registered) }
        return true
    }

    /** "稍后 10 分钟": rebuilds one alarm for [reminder] without touching the rest of the plan. */
    fun snooze(context: Context, reminder: ScheduledReminder, snoozeCount: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val at = Clock.System.now().toEpochMilliseconds() + SnoozeMillis
        registerAlarm(context, alarmManager, reminder, at, snoozeCount)
        val prefs = context.getSharedPreferences(REMINDER_PREFS, Context.MODE_PRIVATE)
        val tracked = prefs.getStringSet(KEY_SCHEDULED, emptySet()).orEmpty().toMutableSet()
        tracked += reminder.key
        prefs.edit { putStringSet(KEY_SCHEDULED, tracked) }
    }

    /**
     * Rebuilds the reminders from stored state; used after a reboot, when registered alarms are
     * gone and nothing else would bring them back.
     */
    fun rescheduleFromStorage(context: Context) {
        AndroidAppContext.install(context)
        runCatching {
            val settings = AppStorage.loadSettings()
            val strings = stringsFor(resolveLanguage(languageFromTag(settings.language), systemLocaleTag()))
            val state = AppStorage.loadState()
            val reminders = planReminders(
                state.events,
                state.todos,
                strings,
                Clock.System.now(),
                TimeZone.currentSystemDefault(),
            )
            reschedule(context, reminders)
        }
    }

    private fun registerAlarm(
        context: Context,
        alarmManager: AlarmManager,
        reminder: ScheduledReminder,
        triggerAtMillis: Long,
        snoozeCount: Int,
    ) {
        val pending = firePendingIntent(context, reminder.key, reminder, snoozeCount)
        // Exact alarms need a user grant on API 31+; the inexact fallback still fires, just with
        // the system's own batching latitude.
        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        try {
            if (exactAllowed) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
            }
        } catch (_: SecurityException) {
            // The grant can be revoked between the check and the call; inexact still fires.
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
        }
    }

    /**
     * One pending intent per reminder key. The data URI makes intents with different keys compare
     * unequal (`filterEquals` ignores extras), so cancelling replaces exactly the old alarm.
     */
    private fun firePendingIntent(
        context: Context,
        key: String,
        reminder: ScheduledReminder? = null,
        snoozeCount: Int = 0,
    ): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setData(Uri.parse("crko://reminder/${Uri.encode(key)}"))
        if (reminder != null) {
            intent.putExtra(EXTRA_REMINDER_KEY, reminder.key)
            intent.putExtra(EXTRA_REMINDER_TITLE, reminder.title)
            intent.putExtra(EXTRA_REMINDER_BODY, reminder.body)
            intent.putExtra(EXTRA_REMINDER_LEVEL, reminder.level.name)
            intent.putStringArrayListExtra(
                EXTRA_REMINDER_ACTIONS,
                ArrayList(reminder.actions.map { it.name }),
            )
            reminder.deepLink?.let { intent.putExtra(EXTRA_REMINDER_DEEP_LINK, it) }
            reminder.recordId?.let { intent.putExtra(EXTRA_REMINDER_RECORD_ID, it) }
            intent.putExtra(EXTRA_SNOOZE_COUNT, snoozeCount)
        }
        return PendingIntent.getBroadcast(
            context,
            key.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/** Posts the notification for one fired alarm; the payload travels in the intent extras. */
class ReminderReceiver : BroadcastReceiver() {
    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra(EXTRA_REMINDER_TITLE) ?: return
        val body = intent.getStringExtra(EXTRA_REMINDER_BODY).orEmpty()
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val quiet = intent.getStringExtra(EXTRA_REMINDER_LEVEL) == ReminderLevel.Quiet.name
        ensureChannel(context, manager, quiet)
        // The reminder key is the stable identity: an edited reminder replaces its previous
        // notification instead of stacking a second one beside it.
        val key = intent.getStringExtra(EXTRA_REMINDER_KEY) ?: intent.data?.lastPathSegment
        val actions = intent.getStringArrayListExtra(EXTRA_REMINDER_ACTIONS).orEmpty()
        manager.notify(
            key,
            key?.hashCode() ?: intent.hashCode(),
            buildNotification(context, intent, title, body, quiet, actions),
        )
    }

    private fun buildNotification(
        context: Context,
        intent: Intent,
        title: String,
        body: String,
        quiet: Boolean,
        actions: List<String>,
    ): Notification {
        val key = intent.getStringExtra(EXTRA_REMINDER_KEY) ?: "crko"
        val deepLink = intent.getStringExtra(EXTRA_REMINDER_DEEP_LINK) ?: "crko://today"
        val recordId = intent.getStringExtra(EXTRA_REMINDER_RECORD_ID)
        val snoozeCount = intent.getIntExtra(EXTRA_SNOOZE_COUNT, 0)
        val contentIntent = openPendingIntent(context, key, deepLink, recordId)
        val text = reminderStrings()
        val builder = NotificationCompat.Builder(
            context,
            if (quiet) REMINDER_QUIET_CHANNEL_ID else REMINDER_CHANNEL_ID,
        )
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setSilent(quiet)

        actions.forEach { action ->
            when (action) {
                ReminderAction.Open.name -> builder.addAction(
                    context.applicationInfo.icon,
                    text.reminderActionOpen,
                    contentIntent,
                )

                ReminderAction.Start.name -> if (recordId != null) {
                    builder.addAction(
                        context.applicationInfo.icon,
                        text.reminderActionStart,
                        openPendingIntent(context, "$key:start", "crko://start", recordId, actionSuffix = "start"),
                    )
                }

                ReminderAction.Snooze.name -> if (snoozeCount < 2) {
                    builder.addAction(
                        context.applicationInfo.icon,
                        text.reminderActionSnooze,
                        snoozePendingIntent(context, key, intent, snoozeCount + 1),
                    )
                }
            }
        }
        return builder.build()
    }
}

/** Receives the "稍后 10 分钟" action; the alarm fires again with the same payload. */
class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SNOOZE_REMINDER) return
        val key = intent.getStringExtra(EXTRA_REMINDER_KEY) ?: return
        val snoozeCount = intent.getIntExtra(EXTRA_SNOOZE_COUNT, 0)
        if (snoozeCount > 2) return
        val reminder = ScheduledReminder(
            key = key,
            triggerAtMillis = 0,
            title = intent.getStringExtra(EXTRA_REMINDER_TITLE).orEmpty(),
            body = intent.getStringExtra(EXTRA_REMINDER_BODY).orEmpty(),
            fingerprint = ReminderFingerprint(0, "", ""),
            origin = ReminderOrigin.User,
            level = if (intent.getStringExtra(EXTRA_REMINDER_LEVEL) == ReminderLevel.Quiet.name) {
                ReminderLevel.Quiet
            } else {
                ReminderLevel.Normal
            },
            actions = intent.getStringArrayListExtra(EXTRA_REMINDER_ACTIONS).orEmpty().mapNotNull { name ->
                ReminderAction.entries.firstOrNull { it.name == name }
            },
            deepLink = intent.getStringExtra(EXTRA_REMINDER_DEEP_LINK),
            recordId = intent.getStringExtra(EXTRA_REMINDER_RECORD_ID),
        )
        AndroidReminders.snooze(context, reminder, snoozeCount)
    }
}

/** Re-registers every reminder after a reboot wiped the alarm manager. */
class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        AndroidReminders.rescheduleFromStorage(context)
    }
}

private fun openPendingIntent(
    context: Context,
    key: String,
    deepLink: String,
    recordId: String?,
    actionSuffix: String = "open",
): PendingIntent {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLink))
        .setPackage(context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    recordId?.let { intent.putExtra(EXTRA_REMINDER_RECORD_ID, it) }
    return PendingIntent.getActivity(
        context,
        "$key:$actionSuffix".hashCode(),
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

private fun snoozePendingIntent(
    context: Context,
    key: String,
    source: Intent,
    nextCount: Int,
): PendingIntent {
    val intent = Intent(context, ReminderActionReceiver::class.java)
        .setAction(ACTION_SNOOZE_REMINDER)
        .setData(Uri.parse("crko://snooze/${Uri.encode(key)}"))
        .putExtra(EXTRA_REMINDER_KEY, key)
        .putExtra(EXTRA_REMINDER_TITLE, source.getStringExtra(EXTRA_REMINDER_TITLE))
        .putExtra(EXTRA_REMINDER_BODY, source.getStringExtra(EXTRA_REMINDER_BODY))
        .putExtra(EXTRA_REMINDER_LEVEL, source.getStringExtra(EXTRA_REMINDER_LEVEL))
        .putStringArrayListExtra(
            EXTRA_REMINDER_ACTIONS,
            source.getStringArrayListExtra(EXTRA_REMINDER_ACTIONS),
        )
        .putExtra(EXTRA_REMINDER_DEEP_LINK, source.getStringExtra(EXTRA_REMINDER_DEEP_LINK))
        .putExtra(EXTRA_REMINDER_RECORD_ID, source.getStringExtra(EXTRA_REMINDER_RECORD_ID))
        .putExtra(EXTRA_SNOOZE_COUNT, nextCount)
    return PendingIntent.getBroadcast(
        context,
        "$key:snooze:$nextCount".hashCode(),
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

private fun reminderStrings(): Strings = runCatching {
    val settings = loadPersistedSettings()
    stringsFor(resolveLanguage(languageFromTag(settings.language), systemLocaleTag()))
}.getOrElse { stringsFor(AppLanguageFallback) }

private val AppLanguageFallback = resolveLanguage(languageFromTag(""), systemLocaleTag())

private fun ensureChannel(context: Context, manager: NotificationManagerCompat, quiet: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val id = if (quiet) REMINDER_QUIET_CHANNEL_ID else REMINDER_CHANNEL_ID
    if (manager.getNotificationChannel(id) != null) return
    val name = context.applicationInfo.loadLabel(context.packageManager).ifBlank { "Reminders" }
    val channel = when {
        quiet -> NotificationChannel(id, name, NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null)
            enableVibration(false)
        }

        else -> NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH)
    }
    manager.createNotificationChannel(channel)
}

internal object AndroidReminderScheduler : ReminderScheduler {
    override suspend fun execute(cancelKeys: Set<String>, register: List<ScheduledReminder>): Boolean {
        val context = AndroidAppContext.getOrNull() ?: return false
        // Registration is one binder call per key; keep it off the caller's (UI) dispatcher. The
        // plan itself was computed by the state layer on its persistence dispatcher.
        return withContext(Dispatchers.Default) {
            AndroidReminders.apply(context, cancelKeys, register)
        }
    }

    override suspend fun replaceAll(plan: List<ScheduledReminder>): Boolean {
        val context = AndroidAppContext.getOrNull() ?: return false
        return withContext(Dispatchers.Default) {
            AndroidReminders.reschedule(context, plan)
        }
    }

    override suspend fun ensurePermission(): Boolean {
        val context = AndroidAppContext.getOrNull() ?: return false
        val manager = NotificationManagerCompat.from(context)
        if (manager.areNotificationsEnabled()) return true
        // The system dialog needs an activity; without one (or on older versions where the
        // permission does not exist) the current state stands.
        val activity = ActivityHolder.current() ?: return false
        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                ReminderPermissionRequest,
            )
        }
        return false
    }
}

private const val ReminderPermissionRequest = 4001

actual fun reminderScheduler(): ReminderScheduler? =
    if (AndroidAppContext.getOrNull() != null) AndroidReminderScheduler else null
