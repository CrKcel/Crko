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
    /** Calendar new records and imported files go into; null means the first local calendar. */
    val defaultCalendarId: String? = null,
)
