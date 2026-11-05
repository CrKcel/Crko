package com.crko.state

import com.crko.domain.AppState
import com.crko.domain.Calendar
import com.crko.domain.CalendarSyncState
import com.crko.domain.DiscoveryFailure
import com.crko.storage.SecretStore
import com.crko.storage.SubscriptionHttp
import com.crko.storage.caldavPasswordKey
import com.crko.sync.CalDavDiscovery
import com.crko.sync.discoverCalendars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * UI view of one calendar a CalDAV server published during account discovery. The state layer
 * mirrors the sync-layer type, so the UI never imports the protocol package.
 */
data class DiscoveredCalDavCalendar(
    /** Absolute member URL of the collection; becomes the calendar's sync URL. */
    val href: String,
    val displayName: String,
    val colorArgb: Long?,
    /** The signed-in user holds a write privilege, so the calendar syncs both ways. */
    val writable: Boolean,
)

/** The outcome of one account discovery run, phrased for the add-account dialog. */
sealed interface CalDavAccountDiscovery {
    data class Found(val collections: List<DiscoveredCalDavCalendar>) : CalDavAccountDiscovery
    data class Failed(val failure: DiscoveryFailure) : CalDavAccountDiscovery
}

/**
 * Adds and maintains CalDAV accounts: the discovery walk of the add-account dialog, the calendar
 * rows it creates, and the credentials a 401 makes the user replace. The password itself only ever
 * lives in the platform keystore under the calendar id, never in the persisted state.
 */
internal class CalDavAccountService(
    private val state: () -> AppState,
    private val persist: (AppState) -> Unit,
    private val sync: (String) -> Unit,
    private val idProvider: () -> String,
    private val http: SubscriptionHttp?,
    private val secretStore: SecretStore?,
) {
    /**
     * Signs in to the CalDAV server at [serverUrl] and lists the calendars it publishes. The dialog
     * shows the failure reason when the walk does not produce a list.
     */
    suspend fun discover(serverUrl: String, username: String, password: String): CalDavAccountDiscovery {
        val http = http ?: return CalDavAccountDiscovery.Failed(DiscoveryFailure.Unreachable)
        // The PROPFIND walk is pure network work; keep it off the caller's frame.
        val result = withContext(Dispatchers.Default) {
            discoverCalendars(serverUrl, username.trim(), password, http)
        }
        return when (result) {
            is CalDavDiscovery.Success -> CalDavAccountDiscovery.Found(
                result.collections.map { collection ->
                    DiscoveredCalDavCalendar(
                        href = collection.href,
                        displayName = collection.displayName,
                        colorArgb = collection.colorArgb,
                        writable = collection.writable,
                    )
                },
            )
            is CalDavDiscovery.Failure -> CalDavAccountDiscovery.Failed(result.failure)
        }
    }

    /**
     * Adds one calendar per selected [collections] and stores the account password in the platform
     * keystore under each calendar's id, then syncs each one immediately. Returns the created ids.
     */
    suspend fun add(username: String, password: String, collections: List<DiscoveredCalDavCalendar>): List<String> {
        val user = username.trim().takeIf { it.isNotBlank() }
        val added = collections.map { collection ->
            val id = idProvider()
            secretStore?.save(caldavPasswordKey(id), password)
            val calendar = Calendar(
                id = id,
                name = collection.displayName.ifBlank { collection.href.trimEnd('/').substringAfterLast('/') },
                colorArgb = collection.colorArgb,
                syncState = CalendarSyncState(url = collection.href, username = user, writable = collection.writable),
            )
            persist(state().copy(calendars = state().calendars + calendar))
            id
        }
        added.forEach(sync)
        return added
    }

    /** Replaces the account of a CalDAV calendar and syncs with the new credentials. */
    suspend fun updateCredentials(id: String, username: String, password: String) {
        val current = state()
        val index = current.calendars.indexOfFirst { it.id == id && !it.isLocal }
        if (index < 0) return
        val calendar = current.calendars[index]
        val syncState = calendar.syncState ?: return
        if (password.isNotEmpty()) secretStore?.save(caldavPasswordKey(id), password)
        persist(
            current.copy(
                calendars = current.calendars.toMutableList().also {
                    it[index] = calendar.copy(
                        syncState = syncState.copy(username = username.trim().ifBlank { null }),
                    )
                },
            ),
        )
        sync(id)
    }
}
