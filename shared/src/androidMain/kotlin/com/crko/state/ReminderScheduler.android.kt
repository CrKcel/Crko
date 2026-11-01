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
import com.crko.domain.AppState
import com.crko.i18n.Strings
import com.crko.i18n.languageFromTag
import com.crko.i18n.resolveLanguage
import com.crko.i18n.stringsFor
import com.crko.storage.AppStorage
import com.crko.storage.systemLocaleTag
import kotlinx.datetime.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Clock

private const val REMINDER_PREFS = "crko.reminders"
private const val KEY_SCHEDULED = "scheduled"
const val REMINDER_CHANNEL_ID = "reminders"
const val EXTRA_REMINDER_TITLE = "crko.extra.TITLE"
const val EXTRA_REMINDER_BODY = "crko.extra.BODY"

/**
 * Alarm-manager backed scheduler. Already-registered alarms are tracked by key in a private prefs
 * file so a reschedule can cancel them; the notification itself is posted by [ReminderReceiver]
 * with the title and body that were computed at schedule time.
 */
object AndroidReminders {
    /** Replaces every registered alarm with [reminders]. */
    fun reschedule(context: Context, reminders: List<ScheduledReminder>) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val prefs = context.getSharedPreferences(REMINDER_PREFS, Context.MODE_PRIVATE)
        prefs.getStringSet(KEY_SCHEDULED, emptySet()).orEmpty().forEach { key ->
            alarmManager.cancel(firePendingIntent(context, key, null, null))
        }
        if (reminders.isEmpty()) {
            prefs.edit { remove(KEY_SCHEDULED) }
            return
        }
        // Exact alarms need a user grant on API 31+; the inexact fallback still fires, just with
        // the system's own batching latitude.
        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
        val registered = mutableSetOf<String>()
        reminders.forEach { reminder ->
            val pending = firePendingIntent(context, reminder.key, reminder.title, reminder.body)
            try {
                if (exactAllowed) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.triggerAtMillis, pending)
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.triggerAtMillis, pending)
                }
            } catch (_: SecurityException) {
                // The grant can be revoked between the check and the call; inexact still fires.
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.triggerAtMillis, pending)
            }
            registered += reminder.key
        }
        prefs.edit { putStringSet(KEY_SCHEDULED, registered) }
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

    /**
     * One pending intent per reminder key. The data URI makes intents with different keys compare
     * unequal (`filterEquals` ignores extras), so cancelling replaces exactly the old alarm.
     */
    private fun firePendingIntent(context: Context, key: String, title: String?, body: String?): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setData(Uri.parse("crko://reminder/${Uri.encode(key)}"))
        if (title != null) intent.putExtra(EXTRA_REMINDER_TITLE, title)
        if (body != null) intent.putExtra(EXTRA_REMINDER_BODY, body)
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
        ensureChannel(context, manager)
        // The reminder key is the stable identity: an edited reminder replaces its previous
        // notification instead of stacking a second one beside it.
        val key = intent.data?.lastPathSegment
        manager.notify(
            key,
            key?.hashCode() ?: intent.hashCode(),
            buildNotification(context, title, body),
        )
    }

    private fun buildNotification(context: Context, title: String, body: String): Notification {
        val content = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val contentIntent = content?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        return NotificationCompat.Builder(context, REMINDER_CHANNEL_ID)
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
    }
}

/** Re-registers every reminder after a reboot wiped the alarm manager. */
class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        AndroidReminders.rescheduleFromStorage(context)
    }
}

private fun ensureChannel(context: Context, manager: NotificationManagerCompat) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || manager.getNotificationChannel(REMINDER_CHANNEL_ID) != null) return
    val name = context.applicationInfo.loadLabel(context.packageManager).ifBlank { "Reminders" }
    val channel = NotificationChannel(REMINDER_CHANNEL_ID, name, NotificationManager.IMPORTANCE_HIGH)
    manager.createNotificationChannel(channel)
}

internal object AndroidReminderScheduler : ReminderScheduler {
    override suspend fun reschedule(state: AppState, strings: Strings) {
        val context = AndroidAppContext.getOrNull() ?: return
        // Planning walks every occurrence day of every event; the caller's scope is the UI main
        // dispatcher, so the whole pass runs off it to keep saving and startup jank-free.
        withContext(Dispatchers.Default) {
            val reminders = planReminders(
                state.events,
                state.todos,
                strings,
                Clock.System.now(),
                TimeZone.currentSystemDefault(),
            )
            AndroidReminders.reschedule(context, reminders)
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
