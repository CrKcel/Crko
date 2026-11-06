package com.crko.state

import com.crko.i18n.Strings
import com.crko.i18n.languageFromTag
import com.crko.i18n.resolveLanguage
import com.crko.i18n.stringsFor
import com.crko.state.entry.EntryInbox
import com.crko.state.entry.EntryTarget
import com.crko.storage.loadPersistedSettings
import com.crko.storage.systemLocaleTag
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSNumber
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusNotDetermined
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationAction
import platform.UserNotifications.UNNotificationActionOptionForeground
import platform.UserNotifications.UNNotificationCategory
import platform.UserNotifications.UNNotificationCategoryOptionNone
import platform.UserNotifications.UNNotificationDefaultActionIdentifier
import platform.UserNotifications.UNNotificationPresentationOptionBanner
import platform.UserNotifications.UNNotificationPresentationOptionList
import platform.UserNotifications.UNNotificationPresentationOptionSound
import platform.UserNotifications.UNNotificationPresentationOptions
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationResponse
import platform.UserNotifications.UNNotificationSettings
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNUserNotificationCenterDelegateProtocol
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.time.Clock

private const val CategoryOpen = "crko.open"
private const val CategoryOpenSnooze = "crko.open.snooze"
private const val CategoryOpenSnoozeStart = "crko.open.snooze.start"
private const val ActionOpen = "crko.action.open"
private const val ActionSnooze = "crko.action.snooze"
private const val ActionStart = "crko.action.start"
private const val UserInfoDeepLink = "deepLink"
private const val UserInfoRecordId = "recordId"
private const val UserInfoSnoozeCount = "snoozeCount"
private const val SnoozeSeconds = 600.0

/** Snoozed requests carry this marker in their identifier; the plan owns only the others. */
private const val SnoozeIdentifierMarker = "#snooze"

/**
 * UNUserNotificationCenter backed scheduler. Every reminder becomes one non-repeating time-interval
 * request identified by its reminder key, so a differential pass cancels and re-adds only the
 * requests whose key changed; only [replaceAll] — process start, boot restore, recovery — drops
 * all pending requests first, which keeps iOS's 64-request budget in line with the planner's cap.
 */
internal object IosReminderScheduler : ReminderScheduler {
    /** Kept alive for the notification center's weak delegate reference. */
    private val router = NotificationRouter()

    override fun prepare() {
        install()
    }

    /**
     * iOS silently discards notifications that fire while the app is in the foreground unless a
     * delegate presents them, and a notification tap is delivered to the same delegate. Installing
     * it at launch lets a cold start through a notification action reach [EntryInbox].
     */
    private fun install() {
        val center = UNUserNotificationCenter.currentNotificationCenter()
        if (center.delegate == null) center.delegate = router
        center.setNotificationCategories(categories())
    }

    private fun categories(): Set<UNNotificationCategory> {
        val strings = notificationStrings()
        val open = UNNotificationAction.actionWithIdentifier(
            identifier = ActionOpen,
            title = strings.reminderActionOpen,
            options = UNNotificationActionOptionForeground,
        )
        val snooze = UNNotificationAction.actionWithIdentifier(
            identifier = ActionSnooze,
            title = strings.reminderActionSnooze,
            options = 0u,
        )
        val start = UNNotificationAction.actionWithIdentifier(
            identifier = ActionStart,
            title = strings.reminderActionStart,
            options = UNNotificationActionOptionForeground,
        )
        return setOf(
            UNNotificationCategory.categoryWithIdentifier(
                identifier = CategoryOpen,
                actions = listOf(open),
                intentIdentifiers = emptyList<String>(),
                options = UNNotificationCategoryOptionNone,
            ),
            UNNotificationCategory.categoryWithIdentifier(
                identifier = CategoryOpenSnooze,
                actions = listOf(open, snooze),
                intentIdentifiers = emptyList<String>(),
                options = UNNotificationCategoryOptionNone,
            ),
            UNNotificationCategory.categoryWithIdentifier(
                identifier = CategoryOpenSnoozeStart,
                actions = listOf(open, snooze, start),
                intentIdentifiers = emptyList<String>(),
                options = UNNotificationCategoryOptionNone,
            ),
        )
    }

    /** The center when notifications are allowed; null makes the pass report "not applied". */
    private suspend fun authorizedCenter(): UNUserNotificationCenter? {
        val center = UNUserNotificationCenter.currentNotificationCenter()
        install()
        val settings = awaitSettings(center) ?: return null
        return if (settings.authorizationStatus == UNAuthorizationStatusAuthorized ||
            settings.authorizationStatus == UNAuthorizationStatusProvisional
        ) {
            center
        } else {
            null
        }
    }

    override suspend fun execute(cancelKeys: Set<String>, register: List<ScheduledReminder>): Boolean {
        val center = authorizedCenter() ?: return false
        if (cancelKeys.isNotEmpty()) {
            center.removePendingNotificationRequestsWithIdentifiers(cancelKeys.toList())
        }
        addAll(center, register)
        return true
    }

    override suspend fun replaceAll(plan: List<ScheduledReminder>): Boolean {
        val center = authorizedCenter() ?: return false
        // A snoozed request has left the plan (its trigger is already in the past) but must still
        // fire, so a full replacement drops only the requests the plan owns.
        val owned = pendingRequestIdentifiers(center)
            .filterNot { it.contains(SnoozeIdentifierMarker) }
        if (owned.isNotEmpty()) center.removePendingNotificationRequestsWithIdentifiers(owned)
        addAll(center, plan)
        return true
    }

    private fun addAll(center: UNUserNotificationCenter, reminders: List<ScheduledReminder>) {
        val nowMillis = Clock.System.now().toEpochMilliseconds()
        reminders.forEach { reminder ->
            val interval = (reminder.triggerAtMillis - nowMillis) / 1000.0
            if (interval <= 0.0) return@forEach
            // The content properties are bound as read-only vals; only the setters exist.
            val content = UNMutableNotificationContent()
            content.setTitle(reminder.title)
            content.setBody(reminder.body)
            if (reminder.level == ReminderLevel.Normal) {
                content.setSound(UNNotificationSound.defaultSound())
            } else {
                content.setSound(null)
            }
            content.setCategoryIdentifier(categoryFor(reminder.actions))
            content.setUserInfo(
                mapOf(
                    UserInfoDeepLink to reminder.deepLink.orEmpty(),
                    UserInfoRecordId to reminder.recordId.orEmpty(),
                    UserInfoSnoozeCount to 0,
                ),
            )
            val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(interval, repeats = false)
            val request = UNNotificationRequest.requestWithIdentifier(reminder.key, content, trigger)
            center.addNotificationRequest(request) { /* a failed request is re-planned on the next full pass */ }
        }
    }

    private fun categoryFor(actions: List<ReminderAction>): String = when {
        ReminderAction.Start in actions -> CategoryOpenSnoozeStart
        ReminderAction.Snooze in actions -> CategoryOpenSnooze
        else -> CategoryOpen
    }

    override suspend fun ensurePermission(): Boolean {
        val center = UNUserNotificationCenter.currentNotificationCenter()
        install()
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

/**
 * Presents reminders that fire while the app is open and routes taps and actions into
 * [EntryInbox]. The center holds this object weakly, so [IosReminderScheduler] keeps it alive.
 */
private class NotificationRouter : NSObject(), UNUserNotificationCenterDelegateProtocol {
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

    override fun userNotificationCenter(
        center: UNUserNotificationCenter,
        didReceiveNotificationResponse: UNNotificationResponse,
        withCompletionHandler: () -> Unit,
    ) {
        val request = didReceiveNotificationResponse.notification.request
        val userInfo = request.content.userInfo
        val deepLink = userInfo[UserInfoDeepLink] as? String
        val recordId = (userInfo[UserInfoRecordId] as? String)?.takeIf { it.isNotBlank() }
        when (didReceiveNotificationResponse.actionIdentifier) {
            UNNotificationDefaultActionIdentifier, ActionOpen -> EntryInbox.offerDeepLink(deepLink)
            ActionStart -> EntryInbox.offer(EntryTarget.Focus(recordId))
            ActionSnooze -> snooze(center, request)
        }
        withCompletionHandler()
    }

    /** Re-adds the same request ten minutes later, at most twice. */
    private fun snooze(center: UNUserNotificationCenter, request: UNNotificationRequest) {
        val userInfo = request.content.userInfo
        val count = (userInfo[UserInfoSnoozeCount] as? NSNumber)?.intValue?.toInt() ?: 0
        if (count >= 2) return
        val next = max(count + 1, 1)
        val content = request.content.mutableCopy() as? UNMutableNotificationContent ?: return
        content.setUserInfo(
            mapOf(
                UserInfoDeepLink to (userInfo[UserInfoDeepLink] as? String).orEmpty(),
                UserInfoRecordId to (userInfo[UserInfoRecordId] as? String).orEmpty(),
                UserInfoSnoozeCount to next,
            ),
        )
        val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(SnoozeSeconds, repeats = false)
        // A derived identifier keeps the snoozed request outside the plan: cancelling the fired
        // reminder's key, or a full replacement, must not drop this one before it fires.
        center.addNotificationRequest(
            UNNotificationRequest.requestWithIdentifier(
                "${request.identifier}$SnoozeIdentifierMarker$next",
                content,
                trigger,
            ),
        ) { /* a failed snooze simply leaves the delivered notification in place */ }
    }
}

private fun notificationStrings(): Strings {
    val settings = runCatching { loadPersistedSettings() }.getOrNull()
    return stringsFor(
        resolveLanguage(languageFromTag(settings?.language.orEmpty()), systemLocaleTag()),
    )
}

private suspend fun awaitSettings(center: UNUserNotificationCenter): UNNotificationSettings? =
    suspendCancellableCoroutine { continuation ->
        center.getNotificationSettingsWithCompletionHandler { settings -> continuation.resume(settings) }
    }

private suspend fun pendingRequestIdentifiers(center: UNUserNotificationCenter): List<String> =
    suspendCancellableCoroutine { continuation ->
        center.getPendingNotificationRequestsWithCompletionHandler { requests ->
            // The platform declaration erases the element type; the identifiers are strings.
            continuation.resume(
                requests?.mapNotNull { (it as? UNNotificationRequest)?.identifier }.orEmpty(),
            )
        }
    }

actual fun reminderScheduler(): ReminderScheduler? = IosReminderScheduler
