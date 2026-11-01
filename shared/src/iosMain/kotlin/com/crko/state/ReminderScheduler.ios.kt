package com.crko.state

import com.crko.domain.AppState
import com.crko.i18n.Strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import platform.darwin.NSObject
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusNotDetermined
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationPresentationOptionBanner
import platform.UserNotifications.UNNotificationPresentationOptionList
import platform.UserNotifications.UNNotificationPresentationOptionSound
import platform.UserNotifications.UNNotificationPresentationOptions
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSettings
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNUserNotificationCenterDelegateProtocol
import kotlin.coroutines.resume
import kotlin.time.Clock

/**
 * UNUserNotificationCenter backed scheduler. Every reminder becomes one non-repeating time-interval
 * request; a reschedule drops all pending requests first, which keeps iOS's 64-request budget in
 * line with the planner's own cap.
 */
internal object IosReminderScheduler : ReminderScheduler {
    /** Kept alive for the notification center's weak `delegate` reference. */
    private val foregroundPresenter = ForegroundPresenter()

    /**
     * iOS silently discards notifications that fire while the app is in the foreground unless a
     * delegate presents them, so the delegate is installed before anything is scheduled. Replaced
     * without complaint if a host app later installs its own.
     */
    private fun installForegroundPresentation(center: UNUserNotificationCenter) {
        if (center.delegate == null) center.delegate = foregroundPresenter
    }

    override suspend fun reschedule(state: AppState, strings: Strings) {
        val center = UNUserNotificationCenter.currentNotificationCenter()
        installForegroundPresentation(center)
        val settings = awaitSettings(center) ?: return
        if (settings.authorizationStatus != UNAuthorizationStatusAuthorized &&
            settings.authorizationStatus != UNAuthorizationStatusProvisional
        ) {
            return
        }
        // Planning walks every occurrence day of every event; keep it off the caller's main thread.
        val reminders = withContext(Dispatchers.Default) {
            planReminders(
                state.events,
                state.todos,
                strings,
                Clock.System.now(),
                TimeZone.currentSystemDefault(),
            )
        }
        center.removeAllPendingNotificationRequests()
        val nowMillis = Clock.System.now().toEpochMilliseconds()
        reminders.forEach { reminder ->
            val interval = (reminder.triggerAtMillis - nowMillis) / 1000.0
            if (interval <= 0.0) return@forEach
            // The content properties are bound as read-only vals; only the setters exist.
            val content = UNMutableNotificationContent()
            content.setTitle(reminder.title)
            content.setBody(reminder.body)
            content.setSound(UNNotificationSound.defaultSound())
            val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(interval, repeats = false)
            val request = UNNotificationRequest.requestWithIdentifier(reminder.key, content, trigger)
            center.addNotificationRequest(request) { /* a failed request is re-planned on the next reschedule */ }
        }
    }

    override suspend fun ensurePermission(): Boolean {
        val center = UNUserNotificationCenter.currentNotificationCenter()
        installForegroundPresentation(center)
        val settings = awaitSettings(center) ?: return false
        return when (settings.authorizationStatus) {
            UNAuthorizationStatusAuthorized, UNAuthorizationStatusProvisional -> true
            UNAuthorizationStatusNotDetermined -> suspendCancellableCoroutine { continuation ->
                center.requestAuthorizationWithOptions(
                    UNAuthorizationOptionAlert or UNAuthorizationOptionSound,
                ) { granted, _ -> continuation.resume(granted) }
            }
            else -> false
        }
    }
}

/** Presents scheduled reminders that fire while the app is open, like a background delivery would. */
private class ForegroundPresenter : NSObject(), UNUserNotificationCenterDelegateProtocol {
    override fun userNotificationCenter(
        center: UNUserNotificationCenter,
        willPresentNotification: UNNotification,
        withCompletionHandler: (UNNotificationPresentationOptions) -> Unit,
    ) {
        withCompletionHandler(
            UNNotificationPresentationOptionList or
                UNNotificationPresentationOptionBanner or
                UNNotificationPresentationOptionSound,
        )
    }
}

private suspend fun awaitSettings(center: UNUserNotificationCenter): UNNotificationSettings? =
    suspendCancellableCoroutine { continuation ->
        center.getNotificationSettingsWithCompletionHandler { settings -> continuation.resume(settings) }
    }

actual fun reminderScheduler(): ReminderScheduler? = IosReminderScheduler
