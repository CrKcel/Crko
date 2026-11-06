package com.crko.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** JSON configuration of the persisted payload and settings. */
val AppJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** Preferences written outside the calendar payload, so a damaged state stays recoverable. */
@Serializable
data class PersistedSettings(
    val language: String = "",
    val darkMode: String = "",
    val palette: String = "",
    val keyColor: Long = 0xFF3B6CF5,
    val agendaView: String = "",
    val agendaScrollHour: Int = -1,
    /** Two-finger pinch toggles the day view and the week overview on the schedule page. */
    val gesturePinchOverview: Boolean = true,
    /** Swipe toggles the list and the timeline on the schedule page. */
    val gestureSwipeSwitch: Boolean = true,
    /** Calendar new records and imported files go into; null means the first local calendar. */
    val defaultCalendarId: String? = null,
    /** App-created prompts per day: 0 disables them, 3 and 6 are the offered choices. */
    val coachReminderBudget: Int = 6,
    /** AI drafts stay off until the user enables them from the settings. */
    val aiEnabled: Boolean = false,
    /** OpenAI-compatible base URL; empty until the user connects one. */
    val aiEndpoint: String = "",
    val aiModel: String = "",
    val aiDailyRequestBudget: Int = 30,
    val aiAllowCalendarContext: Boolean = false,
)
